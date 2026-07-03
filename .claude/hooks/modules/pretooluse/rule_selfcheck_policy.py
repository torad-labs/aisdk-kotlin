"""Rule self-check policy: gate edits to the gates themselves.

LP-1 ("an unexercised check is indistinguishable from a broken one") applied at
generation time: when anyone — builder or orchestrator — edits an ast-grep rule
file or the fixture manifest, validate the POST-EDIT content immediately:

- rule .yaml: must still parse under ast-grep (block otherwise); if the manifest
  carries a fixture for the rule, badExample must match and goodExample must not
  (block otherwise); if the manifest copy of the yaml has drifted from the file,
  or the rule has no fixture at all, warn (GH-09/GH-13 close those).
- manifest.json: must parse as JSON and every entry's id must correspond to an
  existing rule file (block otherwise).

This is the edit-time twin of GH-01 (which wires validate_rules into ci-gate).
"""
from __future__ import annotations

import json
import os
import tempfile
from pathlib import Path

from orchestrator.result import HookResult
from rules.validate_rules import _parses, _scan, _write_kt, ast_grep_binary

MODULE_ORDER = 30
MODULE_NAME = "rule_selfcheck_policy"

WRITE_TOOLS = {"Write", "Edit", "MultiEdit"}

HOOKS_ROOT = Path(__file__).resolve().parents[2]
RULES_DIR = HOOKS_ROOT / "rules" / "kotlin"
MANIFEST_PATH = HOOKS_ROOT / "rules" / "manifest.json"


def applies(data: dict) -> bool:
    if data.get("tool_name") not in WRITE_TOOLS:
        return False
    path = _target_path(data)
    return path is not None


def run(data: dict) -> HookResult | None:
    path = _target_path(data)
    if path is None:
        return None
    post = _post_edit_content(data, path)
    if post is None:
        return None  # cannot reconstruct the edit; commit-time GH-01 backstops

    if path.name == "manifest.json":
        return _check_manifest(post)
    return _check_rule_file(path, post)


def _target_path(data: dict) -> Path | None:
    raw = str((data.get("tool_input") or {}).get("file_path") or "")
    if not raw:
        return None
    path = Path(raw)
    normalized = str(path).replace("\\", "/")
    if normalized.endswith(".claude/hooks/rules/manifest.json"):
        return path
    if "/.claude/hooks/rules/kotlin/" in normalized and path.suffix == ".yaml":
        return path
    return None


def _post_edit_content(data: dict, path: Path) -> str | None:
    tool = data.get("tool_name")
    tool_input = data.get("tool_input") or {}
    if tool == "Write":
        return str(tool_input.get("content") or "")
    try:
        current = path.read_text(encoding="utf-8")
    except OSError:
        return None
    edits = (
        [tool_input] if tool == "Edit" else list(tool_input.get("edits") or [])
    )
    text = current
    for edit in edits:
        old = str(edit.get("old_string") or "")
        new = str(edit.get("new_string") or "")
        if not old:
            return None
        if edit.get("replace_all"):
            text = text.replace(old, new)
        elif old in text:
            text = text.replace(old, new, 1)
        else:
            return None  # edit will fail anyway; let the tool report it
    return text


def _check_rule_file(path: Path, post: str) -> HookResult | None:
    binary = ast_grep_binary()
    if binary is None:
        return None  # environment without ast-grep; ci-gate backstops
    rule_id = path.stem

    rule_tmp = _write_temp(post, ".yaml")
    probe_tmp = _write_kt("val probe = 1\n")
    try:
        returncode, _, stderr = _scan(binary, rule_tmp, probe_tmp)
        if not _parses(stderr, returncode):
            first = next((ln for ln in stderr.splitlines() if ln.strip()), f"exit {returncode}")
            return _block(
                f"RULE SELF-CHECK: `{path.name}` would no longer parse under "
                f"ast-grep after this edit:\n  {first.strip()}\n"
                "Fix the rule YAML; do not commit a gate that cannot fire."
            )

        entry = _manifest_entry(rule_id)
        if entry is None:
            return _warn(
                f"RULE SELF-CHECK: `{rule_id}` has no fixture in manifest.json — "
                "the rule parses but is unproven (LP-1). Add a badExample/"
                "goodExample pair (see GH-09; scaffold via validate_rules)."
            )

        bad = entry.get("badExample")
        good = entry.get("goodExample")
        if bad:
            bad_tmp = _write_kt(str(bad))
            try:
                _, matches, _ = _scan(binary, rule_tmp, bad_tmp)
            finally:
                os.unlink(bad_tmp)
            if matches == 0:
                return _block(
                    f"RULE SELF-CHECK: after this edit `{rule_id}` no longer "
                    "matches its own badExample fixture — the gate would go "
                    "silently inert. Fix the rule or update the fixture "
                    "deliberately (never to force green)."
                )
        if good:
            good_tmp = _write_kt(str(good))
            try:
                _, matches, _ = _scan(binary, rule_tmp, good_tmp)
            finally:
                os.unlink(good_tmp)
            if matches > 0:
                return _block(
                    f"RULE SELF-CHECK: after this edit `{rule_id}` matches its "
                    "goodExample fixture — the gate would misfire on compliant "
                    "code (misfires invite dodges, LP-3). Fix the rule."
                )

        manifest_yaml = entry.get("yaml")
        if isinstance(manifest_yaml, str) and manifest_yaml.strip() != post.strip():
            return _warn(
                f"RULE SELF-CHECK: manifest.json carries a stale copy of "
                f"`{rule_id}` — re-sync the manifest `yaml` field with the "
                "rule file so semantic validation tests what actually runs."
            )
    finally:
        os.unlink(rule_tmp)
        os.unlink(probe_tmp)
    return None


def _check_manifest(post: str) -> HookResult | None:
    try:
        entries = json.loads(post)
    except json.JSONDecodeError as exc:
        return _block(f"RULE SELF-CHECK: manifest.json would not parse: {exc}")
    if not isinstance(entries, list):
        return _block("RULE SELF-CHECK: manifest.json must be a list of rule entries.")
    dangling = [
        str(e.get("id"))
        for e in entries
        if isinstance(e, dict) and not (RULES_DIR / f"{e.get('id')}.yaml").is_file()
    ]
    if dangling:
        return _block(
            "RULE SELF-CHECK: manifest entries reference nonexistent rule "
            "files: " + ", ".join(dangling[:5]) + ". Fix the ids or add the rules."
        )
    return None


def _manifest_entry(rule_id: str) -> dict | None:
    try:
        entries = json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return None
    for entry in entries:
        if isinstance(entry, dict) and entry.get("id") == rule_id:
            return entry
    return None


def _write_temp(text: str, suffix: str) -> str:
    with tempfile.NamedTemporaryFile(
        mode="w", suffix=suffix, delete=False, encoding="utf-8"
    ) as handle:
        handle.write(text)
        return handle.name


def _block(message: str) -> HookResult:
    return HookResult(kind="block", payload=message, module_name=MODULE_NAME)


def _warn(message: str) -> HookResult:
    return HookResult(kind="warn", payload=message, module_name=MODULE_NAME)
