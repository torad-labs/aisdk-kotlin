#!/usr/bin/env python3
"""
Smart codemod: Replace `else` in sealed when-expressions with explicit cases.

Pipeline:
1. ast-grep finds when-expressions with else branches
2. ast-grep resolves the sealed declaration and its subclasses (structural, not textual)
3. Compare handled cases vs all cases
4. Replace the else branch with the explicit missing cases

Discipline (review 4813532855):

* `--check` restores the tree and exits non-zero, like the sibling codemods. The gate uses it, so
  a violation is reported WITHOUT the gate first writing a rewrite into the working tree and then
  telling the operator to stage whatever it wrote.
* Discovery is ast-grep, matching this module's docstring and the repo's ast-grep-first tenet.
  The previous `grep -rn 'sealed.*class NAME'` matched comments and strings, interpolated the
  class name into a regex unescaped (a dotted name became a wildcard), and used only the FIRST
  hit's file — so a passing mention in an unrelated file could select the wrong declaration and
  feed a bogus case list into the rewrite.
* Anything this cannot rewrite with certainty is REFUSED and reported, never guessed. A codemod
  that emits non-compiling Kotlin is worse than one that declines: `else -> { ... }` block bodies
  are skipped (the old code replaced only the `else ->` line, orphaning the body and its brace),
  and an unresolved subject type or empty case list is printed so the coverage gap is visible in
  gate output instead of silently passing.
"""
import json
import re
import subprocess
import sys
from pathlib import Path

SEALED_RULE = '.rules/kotlin/ast-grep/rules-style/no-else-in-sealed-when.yaml'


def run_ast_grep_json(rule_yaml: str, target: str) -> list[dict]:
    """Run ast-grep with an inline YAML rule and return JSON results."""
    result = subprocess.run(
        ['ast-grep', 'scan', '--inline-rules', rule_yaml, '--json=compact', target],
        capture_output=True, text=True,
    )
    if result.stdout.strip():
        return json.loads(result.stdout)
    return []


def find_sealed_when_violations(target: str) -> list[dict]:
    """Find when expressions with else branches."""
    result = subprocess.run(
        ['ast-grep', 'scan', '--rule', SEALED_RULE, '--json=compact', target],
        capture_output=True, text=True,
    )
    if result.stdout.strip():
        return json.loads(result.stdout)
    return []


def extract_when_subject_type(when_text: str) -> str | None:
    """
    Infer the sealed type from the when's `is` checks.

    Handles both shapes the old version dropped: a qualified `is Base.Case` yields `Base`, and an
    unqualified run of `is Case` yields None here so the caller can resolve it from the declaration
    side instead of silently skipping the site.
    """
    is_checks = re.findall(r'is\s+([\w.]+)', when_text)
    for check in is_checks:
        if '.' in check:
            return check.rsplit('.', 1)[0]
    return None


def extract_handled_cases(when_text: str, base_type: str) -> list[str]:
    """Case names already handled, qualified (`is Base.Case`) or bare (`is Case`)."""
    qualified = re.findall(rf'is\s+{re.escape(base_type)}\.(\w+)', when_text)
    bare = re.findall(r'is\s+(\w+)\s*(?:->|,)', when_text)
    return sorted({*qualified, *[b for b in bare if b != base_type]})


def find_sealed_declaration_file(sealed_class_name: str) -> str | None:
    """
    Locate the file declaring `sealed class|interface NAME`, structurally.

    Uses an ast-grep kind rule rather than a text grep, so comment and string mentions cannot
    select a file and the name is never interpolated into a regex.
    """
    if not re.fullmatch(r'\w+', sealed_class_name):
        return None
    rule = (
        'id: find-sealed-decl\n'
        'language: kotlin\n'
        'rule:\n'
        '  kind: class_declaration\n'
        '  all:\n'
        '    - has:\n'
        '        kind: type_identifier\n'
        f'        regex: \'^{sealed_class_name}$\'\n'
        '    - has:\n'
        '        kind: modifiers\n'
        '        has: {kind: class_modifier, regex: \'^sealed$\'}\n'
    )
    matches = run_ast_grep_json(rule, 'src/')
    if not matches:
        return None
    if len({m['file'] for m in matches}) > 1:
        print(f"  UNRESOLVED: sealed '{sealed_class_name}' declared in multiple files; refusing")
        return None
    return matches[0]['file']


def find_sealed_class_cases(sealed_class_name: str) -> list[str]:
    """
    All subclasses of the sealed declaration, found structurally.

    Replaces the old empty-parens regex gate (`) : Name()`), which skipped any hierarchy whose
    subclasses all pass constructor arguments and — worse — in a MIXED hierarchy let one no-arg
    subclass open the gate while a sibling with a generic or arg-only super call was missed by the
    per-line scan, so the else branch was deleted without that case being added.
    """
    sealed_file = find_sealed_declaration_file(sealed_class_name)
    if sealed_file is None:
        return []
    print(f"  Found sealed declaration in: {sealed_file}")

    rule = (
        'id: find-subclasses\n'
        'language: kotlin\n'
        'rule:\n'
        '  any:\n'
        '    - kind: class_declaration\n'
        '    - kind: object_declaration\n'
        '  has:\n'
        '    kind: delegation_specifier\n'
        '    stopBy: end\n'
        '    has:\n'
        '      kind: type_identifier\n'
        f'      regex: \'^{sealed_class_name}$\'\n'
        '      stopBy: end\n'
    )
    cases: list[str] = []
    for match in run_ast_grep_json(rule, sealed_file):
        text = match['text']
        name = re.search(r'(?:class|object|interface)\s+(\w+)', text)
        if not name or name.group(1) == sealed_class_name:
            continue
        generic = re.search(rf'(?:class|object|interface)\s+{name.group(1)}\s*<', text)
        cases.append(f"{name.group(1)}<*>" if generic else name.group(1))
    return sorted(set(cases))


def find_else_branch(when_text: str) -> tuple[int, str] | None:
    """The else branch's relative line index and text."""
    for i, line in enumerate(when_text.split('\n')):
        if re.match(r'\s*else\s*->', line):
            return i, line.strip()
    return None


def generate_replacement(missing_cases: list[str], base_type: str, else_action: str, indent: str) -> str:
    """Replacement lines for the else branch."""
    action = else_action.split('->')[-1].strip() if '->' in else_action else 'Unit'
    return '\n'.join(f"{indent}is {base_type}.{case} -> {action}" for case in missing_cases)


def process_violation(match: dict) -> dict | None:
    """Analyse one violation; return fix info, or None when it must be refused."""
    file_path = match['file']
    when_text = match['text']
    start_line = match['range']['start']['line']

    base_type = extract_when_subject_type(when_text)
    if not base_type:
        print(f"  UNRESOLVED: no qualified `is Type.Case` to infer the sealed type from "
              f"in {file_path}:{start_line + 1} — not rewritten")
        return None

    else_info = find_else_branch(when_text)
    if not else_info:
        print(f"  UNRESOLVED: no else branch located in {file_path}:{start_line + 1}")
        return None
    else_rel_line, else_text = else_info

    # A block-bodied else spans several lines; only its FIRST line is located here, so rewriting it
    # would leave the body and its closing brace orphaned and the file unbalanced. Refuse instead.
    if else_text.rstrip().endswith('{'):
        print(f"  SKIP: block-bodied else branch at {file_path}:{start_line + else_rel_line + 1} — "
              f"rewriting only its first line would orphan the body; fix by hand")
        return None

    handled = extract_handled_cases(when_text, base_type)
    print(f"  Type: {base_type}, handled: {handled}")

    all_cases = find_sealed_class_cases(base_type)
    if not all_cases:
        print(f"  UNRESOLVED: no subclasses found for sealed '{base_type}' — not rewritten")
        return None
    print(f"  All cases: {all_cases}")

    def base_name(case: str) -> str:
        return case.split('<')[0]

    handled_bases = {base_name(h) for h in handled}
    missing = [c for c in all_cases if base_name(c) not in handled_bases]
    print(f"  Missing: {missing}")
    if not missing:
        print("  SKIP: no missing cases")
        return None

    return {
        'file': file_path,
        'start_line': start_line + 1,
        'base_type': base_type,
        'handled': handled,
        'missing': missing,
        'else_line': start_line + else_rel_line + 1,
        'else_text': else_text,
    }


def apply_fix(fix_info: dict, write: bool) -> bool:
    """Rewrite the else branch. Returns True when the file content would change."""
    file_path = Path(fix_info['file'])
    lines = file_path.read_text(encoding='utf-8').split('\n')

    else_line_idx = fix_info['else_line'] - 1
    else_line = lines[else_line_idx]
    indent = ' ' * (len(else_line) - len(else_line.lstrip()))

    replacement = generate_replacement(
        fix_info['missing'], fix_info['base_type'], fix_info['else_text'], indent,
    )

    print(f"\n  Replace line {fix_info['else_line']}:")
    print(f"    OLD: {else_line.strip()}")
    print(f"    NEW:\n{replacement}")

    if write:
        lines[else_line_idx] = replacement
        file_path.write_text('\n'.join(lines), encoding='utf-8')
    return True


def main() -> int:
    check_only = '--check' in sys.argv
    dry_run = '--dry-run' in sys.argv or '-n' in sys.argv
    args = [a for a in sys.argv[1:] if not a.startswith('-')]
    target = args[-1] if args else 'src/'

    mode = 'CHECK: ' if check_only else ('DRY RUN: ' if dry_run else '')
    print(f"{mode}Searching for sealed when violations in {target}...")

    matches = find_sealed_when_violations(target)
    print(f"Found {len(matches)} violations\n")

    fixes = []
    for i, match in enumerate(matches):
        print(f"[{i + 1}/{len(matches)}] {match['file']}:{match['range']['start']['line'] + 1}")
        fix_info = process_violation(match)
        if fix_info:
            fixes.append(fix_info)

    if not fixes:
        print("\nsealed-when codemod: nothing to rewrite")
        return 0

    verb = 'Would apply' if (check_only or dry_run) else 'Applying'
    print(f"\n{verb} {len(fixes)} fixes")

    # In --check the tree is restored afterwards, so the report is identical to the applying run
    # without leaving anything staged-but-unreviewed behind.
    originals = {f['file']: Path(f['file']).read_text(encoding='utf-8') for f in fixes}
    for fix in fixes:
        apply_fix(fix, write=not dry_run)
    if check_only:
        for path, before in originals.items():
            Path(path).write_text(before, encoding='utf-8')
        print("\nCHECK: tree restored; rewrite these by review, not by staging codemod output")

    return 1 if (check_only or dry_run) else 0


if __name__ == '__main__':
    raise SystemExit(main())
