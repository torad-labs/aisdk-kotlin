#!/usr/bin/env python3
"""
Smart codemod: Replace `else` in sealed when-expressions with explicit cases.

Pipeline:
1. ast-grep finds when-expressions with else branches
2. Extract the sealed class name from the when subject
3. ast-grep finds all subclasses of that sealed class
4. Compare handled cases vs all cases
5. Replace else with explicit missing cases
"""
import json
import re
import subprocess
import sys
from pathlib import Path
from dataclasses import dataclass

@dataclass
class WhenViolation:
    file: str
    line: int
    subject_type: str  # e.g., "StreamEvent"
    handled_cases: list[str]  # e.g., ["Error", "StreamStart"]
    else_line: int
    else_text: str  # e.g., "else -> Unit"

def run_ast_grep_json(rule_yaml: str, target: str) -> list[dict]:
    """Run ast-grep with inline YAML rule and return JSON results."""
    result = subprocess.run(
        ['ast-grep', 'scan', '--inline-rules', rule_yaml, '--json=compact', target],
        capture_output=True, text=True
    )
    if result.stdout.strip():
        return json.loads(result.stdout)
    return []

def find_sealed_when_violations(target: str) -> list[dict]:
    """Find when expressions with else branches."""
    # Use the existing rule
    result = subprocess.run(
        ['ast-grep', 'scan', '--rule', '.rules/kotlin/ast-grep/rules-style/no-else-in-sealed-when.yaml',
         '--json=compact', target],
        capture_output=True, text=True
    )
    if result.stdout.strip():
        return json.loads(result.stdout)
    return []

def extract_when_subject_type(when_text: str) -> str | None:
    """Extract the type from 'when (val x = expr)' or 'when (expr)'."""
    # Pattern: when (event) or when (val body = request.body)
    # The type comes from the variable name or expression
    # We need to look for the is-checks to infer the type

    # Find all "is TypeName" patterns
    is_checks = re.findall(r'is\s+([\w.]+)', when_text)
    if is_checks:
        # Get the base type (before the dot if any)
        first_check = is_checks[0]
        if '.' in first_check:
            return first_check.rsplit('.', 1)[0]
    return None

def extract_handled_cases(when_text: str, base_type: str) -> list[str]:
    """Extract the case names that are already handled."""
    # Pattern: is BaseType.CaseName or is BaseType.CaseName ->
    pattern = rf'is\s+{re.escape(base_type)}\.(\w+)'
    return re.findall(pattern, when_text)

def find_sealed_class_cases(sealed_class_name: str, search_dir: str) -> list[str]:
    """Find all cases (subclasses) of a sealed class using ast-grep."""
    # Search in src/ (not just search_dir) to find sealed classes in other modules
    src_root = 'src/'
    result = subprocess.run(
        ['grep', '-rn', f'sealed.*class {sealed_class_name}', src_root, '--include=*.kt'],
        capture_output=True, text=True
    )
    if not result.stdout.strip():
        # Try sealed interface
        result = subprocess.run(
            ['grep', '-rn', f'sealed.*interface {sealed_class_name}', src_root, '--include=*.kt'],
            capture_output=True, text=True
        )
    if not result.stdout.strip():
        return []

    # Parse grep output: file:line:content
    first_match = result.stdout.strip().split('\n')[0]
    sealed_file = first_match.split(':')[0]
    print(f"  Found sealed class in: {sealed_file}")

    matches = [{'file': sealed_file}]  # Simulate ast-grep result
    if not matches:
        return []

    # Get the file where the sealed class is defined
    sealed_file = matches[0]['file']

    # Now find all subclasses in that file
    # Pattern: class CaseName ... : SealedClassName()
    content = Path(sealed_file).read_text()

    # Find classes that extend the sealed class
    # Pattern: class/object CaseName ... : SealedClassName
    cases = []

    # Method 1: Look for the exhaustive when in the sealed class itself (if it has one)
    # This is the most reliable way
    exhaustive_when = re.search(
        rf'when\s*\(\s*this\s*\)\s*\{{([^}}]+)\}}',
        content, re.DOTALL
    )
    if exhaustive_when:
        when_body = exhaustive_when.group(1)
        # Extract case names: "is CaseName ->" or just "CaseName ->"
        case_matches = re.findall(r'(?:is\s+)?(\w+)\s*->', when_body)
        cases = [c for c in case_matches if c not in ('else',)]
        if cases:
            return cases

    # Method 2: Find class declarations that extend the sealed class
    # Handle multiline declarations like:
    #   public class Started<TContext>(
    #       ...
    #   ) : AgentEvent()
    extends_pattern = rf'\)\s*:\s*{re.escape(sealed_class_name)}\s*\(\s*\)'

    # Check which ones extend the sealed class
    # Look for ") : SealedClassName()" pattern
    if re.search(extends_pattern, content):
        # Find classes defined before ": SealedClassName()" markers
        # Track both class name AND whether it has generics
        cases = []
        lines = content.split('\n')
        current_class = None
        current_has_generics = False
        for line in lines:
            # Match class Name or class Name<...>
            class_match = re.search(rf'(?:class|object|data class)\s+(\w+)(<[^>]+>)?', line)
            if class_match:
                current_class = class_match.group(1)
                # Check if it has generics
                current_has_generics = class_match.group(2) is not None
            if re.search(rf'\)\s*:\s*{re.escape(sealed_class_name)}\s*\(', line):
                if current_class and current_class != sealed_class_name:
                    # If generic, append with wildcard
                    if current_has_generics:
                        cases.append(f"{current_class}<*>")
                    else:
                        cases.append(current_class)
        return cases

    return []

def find_else_branch(when_text: str) -> tuple[int, str] | None:
    """Find the else branch line number (relative) and its content."""
    lines = when_text.split('\n')
    for i, line in enumerate(lines):
        if re.match(r'\s*else\s*->', line):
            return i, line.strip()
    return None

def generate_replacement(missing_cases: list[str], base_type: str, else_action: str, indent: str) -> str:
    """Generate the replacement code for else branch."""
    # else_action is typically "Unit" or something similar
    action = else_action.split('->')[-1].strip() if '->' in else_action else 'Unit'

    lines = []
    for case in missing_cases:
        lines.append(f"{indent}is {base_type}.{case} -> {action}")
    return '\n'.join(lines)

def process_violation(match: dict, search_dir: str) -> dict | None:
    """Process a single violation and return fix info."""
    file_path = match['file']
    when_text = match['text']
    start_line = match['range']['start']['line']

    # Extract sealed class name
    base_type = extract_when_subject_type(when_text)
    if not base_type:
        print(f"  SKIP: Could not determine sealed type in {file_path}:{start_line}")
        return None

    # Find handled cases
    handled = extract_handled_cases(when_text, base_type)
    print(f"  Type: {base_type}, handled: {handled}")

    # Find all cases
    all_cases = find_sealed_class_cases(base_type, search_dir)
    if not all_cases:
        print(f"  SKIP: Could not find sealed class {base_type}")
        return None
    print(f"  All cases: {all_cases}")

    # Find missing cases - strip generics for comparison
    def base_name(case: str) -> str:
        return case.split('<')[0]

    handled_bases = {base_name(h) for h in handled}
    missing = [c for c in all_cases if base_name(c) not in handled_bases]
    print(f"  Missing: {missing}")

    if not missing:
        print(f"  SKIP: No missing cases")
        return None

    # Find else branch info
    else_info = find_else_branch(when_text)
    if not else_info:
        print(f"  SKIP: Could not find else branch")
        return None

    else_rel_line, else_text = else_info

    # ast-grep lines are 0-indexed, but we need 1-indexed for file operations
    # else_rel_line is the index within when_text
    return {
        'file': file_path,
        'start_line': start_line + 1,  # Convert to 1-indexed
        'base_type': base_type,
        'handled': handled,
        'missing': missing,
        'else_line': start_line + else_rel_line + 1,  # Convert to 1-indexed
        'else_text': else_text,
    }

def apply_fix(fix_info: dict, dry_run: bool = True) -> bool:
    """Apply the fix to the file."""
    file_path = Path(fix_info['file'])
    lines = file_path.read_text().split('\n')

    else_line_idx = fix_info['else_line'] - 1  # 0-indexed
    else_line = lines[else_line_idx]

    # Detect indent
    indent = ' ' * (len(else_line) - len(else_line.lstrip()))

    # Generate replacement
    replacement = generate_replacement(
        fix_info['missing'],
        fix_info['base_type'],
        fix_info['else_text'],
        indent
    )

    print(f"\n  Replace line {fix_info['else_line']}:")
    print(f"    OLD: {else_line.strip()}")
    print(f"    NEW:\n{replacement}")

    if not dry_run:
        lines[else_line_idx] = replacement
        file_path.write_text('\n'.join(lines))
        return True

    return False

def main():
    dry_run = '--dry-run' in sys.argv or '-n' in sys.argv
    target = sys.argv[-1] if len(sys.argv) > 1 and not sys.argv[-1].startswith('-') else 'src/'

    print(f"{'DRY RUN: ' if dry_run else ''}Searching for sealed when violations in {target}...")

    matches = find_sealed_when_violations(target)
    print(f"Found {len(matches)} violations\n")

    fixes = []
    for i, match in enumerate(matches):
        print(f"[{i+1}/{len(matches)}] {match['file']}:{match['range']['start']['line']}")
        fix_info = process_violation(match, 'src/commonMain/kotlin')
        if fix_info:
            fixes.append(fix_info)

    print(f"\n{'Would apply' if dry_run else 'Applying'} {len(fixes)} fixes")

    for fix in fixes:
        apply_fix(fix, dry_run=dry_run)

if __name__ == '__main__':
    main()
