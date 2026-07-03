#!/usr/bin/env python3
"""Regression tests for the rule self-check PreToolUse policy (both directions)."""
from __future__ import annotations

import json
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
TARGET = ROOT / ".claude" / "hooks" / "orchestrator" / "pretooluse.py"
RULES_DIR = ROOT / ".claude" / "hooks" / "rules" / "kotlin"
MANIFEST = ROOT / ".claude" / "hooks" / "rules" / "manifest.json"
REGISTRY = ROOT / ".claude" / "hooks" / "rules" / "autofix-registry.json"
VALIDATE_RULES = ROOT / ".claude" / "hooks" / "rules" / "validate_rules.py"

failures: list[str] = []
ran = 0


def check(name: str, condition: bool) -> None:
    global ran
    ran += 1
    if not condition:
        failures.append(name)


def run_target(event: dict) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [sys.executable, str(TARGET)],
        input=json.dumps(event),
        capture_output=True,
        text=True,
        timeout=30,
    )


def blocked(result: subprocess.CompletedProcess[str]) -> bool:
    return '"decision": "block"' in result.stdout or '"permissionDecision": "deny"' in result.stdout


def run_validate_rules(*args: object) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [sys.executable, str(VALIDATE_RULES), *(str(arg) for arg in args)],
        capture_output=True,
        text=True,
        timeout=60,
    )


entries = json.loads(MANIFEST.read_text(encoding="utf-8"))
covered = next(e for e in entries if isinstance(e, dict) and e.get("badExample") and e.get("goodExample"))
covered_id = covered["id"]
covered_path = RULES_DIR / f"{covered_id}.yaml"
covered_text = covered_path.read_text(encoding="utf-8")
no_fix_entry = next(
    e for e in entries
    if isinstance(e, dict)
    and isinstance(e.get("id"), str)
    and isinstance(e.get("yaml"), str)
    and "\nfix:" not in str(e.get("yaml"))
)
no_fix_id = no_fix_entry["id"]

# 1 — rewriting a covered rule with its exact current content passes.
check(
    "faithful rewrite of a covered rule is allowed",
    not blocked(run_target({
        "tool_name": "Write",
        "tool_input": {"file_path": str(covered_path), "content": covered_text},
    })),
)

# 2 — unparseable rule YAML is blocked.
check(
    "unparseable rule yaml is blocked",
    blocked(run_target({
        "tool_name": "Write",
        "tool_input": {"file_path": str(covered_path), "content": "id: broken\nrule: {kind: ["},
    })),
)
check(
    "relative rule path is self-checked",
    blocked(run_target({
        "tool_name": "Write",
        "cwd": str(ROOT),
        "tool_input": {
            "file_path": str(covered_path.relative_to(ROOT)),
            "content": "id: broken\nrule: {kind: [",
        },
    })),
)

# 3 — a rule edited to no longer match its own badExample fixture is blocked.
neutered = covered_text.replace("kotlin", "kotlin") + (
    "\n# appended constraint that can never match\n"
)
neutered_yaml = (
    f"id: {covered_id}\n"
    "language: kotlin\n"
    "severity: warning\n"
    "rule:\n"
    "  kind: property_declaration\n"
    "  has: { kind: simple_identifier, regex: '^zz_never_matches_zz$' }\n"
)
check(
    "rule neutered against its badExample is blocked",
    blocked(run_target({
        "tool_name": "Write",
        "tool_input": {"file_path": str(covered_path), "content": neutered_yaml},
    })),
)

# 4 — manifest with a dangling rule id is blocked; the real manifest passes.
dangling = json.dumps([{"id": "rule-that-does-not-exist", "yaml": "x"}])
check(
    "manifest with dangling rule id is blocked",
    blocked(run_target({
        "tool_name": "Write",
        "tool_input": {"file_path": str(MANIFEST), "content": dangling},
    })),
)
check(
    "current manifest content passes",
    not blocked(run_target({
        "tool_name": "Write",
        "tool_input": {"file_path": str(MANIFEST), "content": MANIFEST.read_text(encoding="utf-8")},
    })),
)

# 5 — --new keeps no-fix scaffolds unchanged unless --fix is requested.
plain_scaffold = run_validate_rules("--new", no_fix_id)
plain_payload = json.loads(plain_scaffold.stdout)
check("--new no-fix scaffold exits zero", plain_scaffold.returncode == 0)
check("--new no-fix scaffold has no fixExamples", "fixExamples" not in plain_payload)
check("--new no-fix scaffold has no fix template", "\nfix:" not in str(plain_payload.get("yaml", "")))

# 6 — --new --fix emits fix stubs that fail loud until filled.
fix_scaffold = run_validate_rules("--new", no_fix_id, "--fix")
fix_payload = json.loads(fix_scaffold.stdout)
check("--new --fix scaffold exits zero", fix_scaffold.returncode == 0)
check("--new --fix scaffold adds fix template", "TODO: replace with an ast-grep fix template" in fix_payload["yaml"])
check("--new --fix scaffold adds fixExamples", fix_payload.get("fixExamples") == [{
    "input": "TODO: replace with code BEFORE applying this fix",
    "output": "TODO: replace with code AFTER applying this fix",
}])

with tempfile.TemporaryDirectory() as tmp:
    tmp_manifest = Path(tmp) / "manifest.json"
    tmp_registry = Path(tmp) / "autofix-registry.json"
    scaffolded_entries: list[dict[str, object]] = []
    for entry in entries:
        if isinstance(entry, dict) and entry.get("id") == no_fix_id:
            replacement = dict(entry)
            replacement["yaml"] = fix_payload["yaml"]
            replacement["fixExamples"] = fix_payload["fixExamples"]
            scaffolded_entries.append(replacement)
        else:
            scaffolded_entries.append(entry)
    tmp_manifest.write_text(json.dumps(scaffolded_entries), encoding="utf-8")
    tmp_registry.write_text(json.dumps([{"id": no_fix_id}]), encoding="utf-8")
    stub_result = run_validate_rules("--manifest", tmp_manifest, "--autofix-registry", tmp_registry)
check("--new --fix stub state fails registry gate", stub_result.returncode == 1)
check("--new --fix stub state says needs examples", "fixExamples[1] needs examples" in stub_result.stdout)

filled_registry = run_validate_rules("--manifest", MANIFEST, "--autofix-registry", REGISTRY)
check("filled fixExamples registry passes", filled_registry.returncode == 0)

if failures:
    print(f"FAILED {ran - len(failures)}/{ran}")
    for failure in failures:
        print(f"- {failure}")
    raise SystemExit(1)

print(f"ok {ran}")
