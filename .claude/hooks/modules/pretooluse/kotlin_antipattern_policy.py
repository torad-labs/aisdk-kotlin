"""Kotlin anti-pattern policy backed by ast-grep (catalog-derived rules).

Rules live as standalone ast-grep YAML files under .claude/hooks/rules/kotlin/.
Each rule declares `severity: error` (block the edit) or `severity: warning`
(surface a non-blocking nudge). Detection is purely structural via ast-grep —
NO regex is used to make any AST claim.

The policy is incremental: it compares the full file BEFORE and AFTER the planned
edit and only acts on anti-patterns the edit *introduces*. Pre-existing instances
are grandfathered so the builder can refactor freely without tripping on debt it
is in the middle of removing.
"""
from __future__ import annotations

import json
import os
import shutil
import subprocess
import tempfile
from collections import Counter
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Optional

from orchestrator.result import HookResult

MODULE_ORDER = 10

WATCHED = {"Write", "Edit", "MultiEdit"}

HOOKS_ROOT = Path(__file__).resolve().parents[2]
RULES_DIR = HOOKS_ROOT / "rules" / "kotlin"
SCAN_TIMEOUT = 5


@dataclass(frozen=True)
class Hit:
    rule_id: str
    severity: str  # "error" -> block, anything else -> warn
    text: str
    line: int
    column: int


@dataclass(frozen=True)
class Change:
    file_path: str
    old_text: str
    new_text: str


# Rules that ban JVM-only APIs purely for multiplatform (commonMain / Kotlin-Native /
# wasm / js) safety. They are LEGITIMATE Kotlin/JVM in JVM-backed source sets, so they
# must not be enforced there — only in common + the non-JVM platform source sets.
_JVM_PLATFORM_OK_RULES = frozenset({
    "no-java-import",
    "no-thread-sleep",
    "no-string-format",
    "no-print-stack-trace",
})
_JVM_SOURCE_SET_MARKERS = (
    "/jvmMain/",
    "/jvmTest/",
    "/androidMain/",
    "/jvmAndAndroidMain/",
    "/androidUnitTest/",
    "/androidInstrumentedTest/",
)


def _rules_for_path(rule_files: list[Path], file_path: str) -> list[Path]:
    """Scope JVM-platform-legitimate rules out of JVM-backed source sets.

    `java.*` imports, Thread.sleep, String.format, printStackTrace are illegal in
    commonMain / Native / wasm / js (they break those targets) but perfectly valid
    Kotlin/JVM. The rules banning them exist for multiplatform safety, so they apply
    everywhere EXCEPT the JVM/Android source sets where the APIs genuinely exist.
    """
    if any(marker in file_path for marker in _JVM_SOURCE_SET_MARKERS):
        return [r for r in rule_files if r.stem not in _JVM_PLATFORM_OK_RULES]
    return rule_files


def applies(data: dict) -> bool:
    return data.get("tool_name") in WATCHED


def run(data: dict) -> Optional[HookResult]:
    binary = _ast_grep_binary()
    if binary is None:
        return _incomplete("ast-grep is required for the Kotlin anti-pattern policy and was not found on PATH.")

    rule_files = _rule_files()
    if not rule_files:
        return _incomplete("No ast-grep rule files found under .claude/hooks/rules/kotlin.")

    tool_input = data.get("tool_input") or {}
    if not isinstance(tool_input, dict):
        return None

    severities = _rule_severities(rule_files)
    block_hits: list[tuple[str, Hit]] = []
    warn_hits: list[tuple[str, Hit]] = []

    for change in _changes(tool_input):
        if Path(change.file_path).suffix.lower() != ".kt":
            continue
        applicable = _rules_for_path(rule_files, change.file_path)
        for hit in _introduced(binary, applicable, severities, change.old_text, change.new_text):
            target = block_hits if hit.severity == "error" else warn_hits
            target.append((change.file_path, hit))

    if block_hits:
        return HookResult(kind="block", payload=_block_message(block_hits, warn_hits), module_name="kotlin_antipattern_policy")
    if warn_hits:
        return HookResult(kind="warn", payload=_warn_message(warn_hits), module_name="kotlin_antipattern_policy")
    return None


# --- planned change reconstruction (full-file before vs after) -------------------

def _changes(tool_input: dict) -> Iterable[Change]:
    file_path = str(tool_input.get("file_path") or "")
    old_file = _read(file_path)

    content = tool_input.get("content")
    if isinstance(content, str):  # Write: whole-file replacement
        yield Change(file_path, old_file, content)
        return

    new_string = tool_input.get("new_string")
    if isinstance(new_string, str):  # Edit
        old_string = tool_input.get("old_string")
        old_string = old_string if isinstance(old_string, str) else ""
        if old_string and old_string in old_file:
            yield Change(file_path, old_file, old_file.replace(old_string, new_string, 1))
        else:
            yield Change(file_path, old_string, new_string)
        return

    edits = tool_input.get("edits")
    if isinstance(edits, list):  # MultiEdit
        new_file = old_file
        applied_all = True
        for edit in edits:
            if not isinstance(edit, dict):
                continue
            old_string = _str_field(edit, "old_string")
            edit_new = _str_field(edit, "new_string")
            if old_string and old_string in new_file:
                new_file = new_file.replace(old_string, edit_new, 1)
            else:
                applied_all = False
        if applied_all:
            yield Change(file_path, old_file, new_file)
        else:
            for edit in edits:
                if isinstance(edit, dict) and isinstance(edit.get("new_string"), str):
                    yield Change(file_path, _str_field(edit, "old_string"), _str_field(edit, "new_string"))


def _str_field(mapping: dict, key: str) -> str:
    value = mapping.get(key)
    return value if isinstance(value, str) else ""


def _read(file_path: str) -> str:
    if not file_path:
        return ""
    try:
        return Path(file_path).read_text(encoding="utf-8")
    except OSError:
        return ""


# --- scanning --------------------------------------------------------------------

def _introduced(
    binary: str,
    rule_files: list[Path],
    severities: dict[str, str],
    old_text: str,
    new_text: str,
) -> list[Hit]:
    old_hits = _scan(binary, rule_files, severities, old_text)
    new_hits = _scan(binary, rule_files, severities, new_text)
    old_counts = Counter((h.rule_id, _identity(h.text)) for h in old_hits)
    seen: Counter = Counter()
    introduced: list[Hit] = []
    for hit in new_hits:
        key = (hit.rule_id, _identity(hit.text))
        seen[key] += 1
        if seen[key] > old_counts.get(key, 0):
            introduced.append(hit)
    return introduced


def _identity(text: str) -> str:
    """Stable identity of a match for incremental diffing.

    Keys on the match's first non-blank line (the declaration/signature), NOT the
    full text. Editing the BODY of a grandfathered match (e.g. a pre-existing loose
    top-level function) keeps the same signature, so it stays grandfathered instead
    of being mis-counted as a newly-introduced violation. Genuinely new declarations
    (new signature) still register as introduced.
    """
    for line in text.splitlines():
        stripped = line.strip()
        if stripped:
            return stripped
    return text.strip()


def _scan(binary: str, rule_files: list[Path], severities: dict[str, str], content: str) -> list[Hit]:
    if not content.strip():
        return []
    code_path = None
    hits: list[Hit] = []
    try:
        with tempfile.NamedTemporaryFile(mode="w", suffix=".kt", delete=False, encoding="utf-8") as code_file:
            code_file.write(content)
            code_path = code_file.name
        # Scan per-rule so a single malformed rule cannot disable the whole policy.
        for rule_file in rule_files:
            completed = subprocess.run(
                [binary, "scan", "--rule", str(rule_file), code_path, "--json=compact"],
                capture_output=True,
                text=True,
                timeout=SCAN_TIMEOUT,
            )
            if completed.returncode not in (0, 1):
                continue
            raw = (completed.stdout or "").strip()
            if not raw:
                continue
            try:
                payload = json.loads(raw)
            except json.JSONDecodeError:
                continue
            if not isinstance(payload, list):
                continue
            for item in payload:
                if not isinstance(item, dict):
                    continue
                start = (item.get("range") or {}).get("start") or {}
                if not isinstance(start, dict):
                    continue
                rule_id = str(item.get("ruleId") or "")
                hits.append(
                    Hit(
                        rule_id=rule_id,
                        severity=severities.get(rule_id, "warning"),
                        text=str(item.get("text") or "").strip(),
                        line=int(start.get("line") or 0),
                        column=int(start.get("column") or 0),
                    )
                )
        return hits
    except (OSError, subprocess.TimeoutExpired, ValueError):
        return hits
    finally:
        if code_path:
            try:
                os.unlink(code_path)
            except OSError:
                pass


# --- rule discovery --------------------------------------------------------------

def _rule_files() -> list[Path]:
    if not RULES_DIR.is_dir():
        return []
    return sorted(p for p in RULES_DIR.glob("*.yaml") if not p.name.startswith("disabled_"))


def _rule_severities(rule_files: list[Path]) -> dict[str, str]:
    severities: dict[str, str] = {}
    for rule_file in rule_files:
        rule_id: Optional[str] = None
        severity = "warning"
        try:
            for line in rule_file.read_text(encoding="utf-8").splitlines():
                stripped = line.strip()
                if stripped.startswith("id:"):
                    rule_id = stripped.split(":", 1)[1].strip()
                elif stripped.startswith("severity:"):
                    severity = stripped.split(":", 1)[1].strip()
        except OSError:
            continue
        if rule_id:
            severities[rule_id] = severity
    return severities


def _ast_grep_binary() -> Optional[str]:
    for candidate in ("ast-grep", str(Path.home() / ".local" / "bin" / "ast-grep")):
        found = shutil.which(candidate) if os.sep not in candidate else candidate
        if found and Path(found).is_file():
            return found
    return None


# --- messaging -------------------------------------------------------------------

def _incomplete(detail: str) -> HookResult:
    return HookResult(
        kind="block",
        payload="REPO HOOK POLICY INCOMPLETE\n\n" + detail,
        module_name="kotlin_antipattern_policy",
    )


def _loc(file_path: str, hit: Hit) -> str:
    return f"{file_path}:{hit.line + 1}:{hit.column + 1}  [{hit.rule_id}]  {hit.text.splitlines()[0][:100] if hit.text else ''}"


def _block_message(block_hits: list[tuple[str, Hit]], warn_hits: list[tuple[str, Hit]]) -> str:
    lines = [
        "REPO KOTLIN ANTI-PATTERN POLICY — BLOCKED",
        "",
        "This edit introduces a Kotlin anti-pattern this repository forbids (see docs/reports/bad-practices-catalog.md).",
        "Make illegal states unrepresentable instead of guarding them at runtime / through a broken channel.",
        "",
        "Introduced (must fix before this edit is allowed):",
    ]
    for file_path, hit in block_hits:
        lines.append("  - " + _loc(file_path, hit))
    if warn_hits:
        lines.append("")
        lines.append("Also flagged (non-blocking, but please address):")
        for file_path, hit in warn_hits:
            lines.append("  - " + _loc(file_path, hit))
    return "\n".join(lines)


def _warn_message(warn_hits: list[tuple[str, Hit]]) -> str:
    lines = [
        "REPO KOTLIN ANTI-PATTERN POLICY — WARNING",
        "This edit introduces patterns the catalog discourages (docs/reports/bad-practices-catalog.md):",
    ]
    for file_path, hit in warn_hits:
        lines.append("  - " + _loc(file_path, hit))
    return "\n".join(lines)
