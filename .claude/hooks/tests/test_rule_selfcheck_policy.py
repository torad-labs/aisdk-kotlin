#!/usr/bin/env python3
"""Regression tests for the rule self-check PreToolUse policy (both directions)."""
from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
TARGET = ROOT / ".claude" / "hooks" / "orchestrator" / "pretooluse.py"
RULES_DIR = ROOT / ".claude" / "hooks" / "rules" / "kotlin"
MANIFEST = ROOT / ".claude" / "hooks" / "rules" / "manifest.json"

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


entries = json.loads(MANIFEST.read_text(encoding="utf-8"))
covered = next(e for e in entries if isinstance(e, dict) and e.get("badExample") and e.get("goodExample"))
covered_id = covered["id"]
covered_path = RULES_DIR / f"{covered_id}.yaml"
covered_text = covered_path.read_text(encoding="utf-8")

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

if failures:
    print(f"FAILED {ran - len(failures)}/{ran}")
    for failure in failures:
        print(f"- {failure}")
    raise SystemExit(1)

print(f"ok {ran}")
