#!/usr/bin/env python3
"""Regression tests for the repo-local Codex -> Claude hook adapter."""
from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
ADAPTER = ROOT / ".codex" / "hooks" / "claude_compat.py"
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


def run_adapter(patch: str) -> subprocess.CompletedProcess[str]:
    env = dict(os.environ)
    env["CLAUDE_PROJECT_DIR"] = str(ROOT)
    payload = {
        "tool_name": "apply_patch",
        "cwd": str(ROOT),
        "tool_input": {"command": patch},
    }
    return subprocess.run(
        [sys.executable, str(ADAPTER), "pretooluse", str(TARGET)],
        input=json.dumps(payload),
        capture_output=True,
        text=True,
        timeout=15,
        env=env,
    )


with tempfile.TemporaryDirectory() as tmp:
    member_file = Path(tmp) / "Member.kt"
    member_file.write_text(
        "package x\n\npublic class C {\n    public fun messageCount(): Int = 1\n}\n",
        encoding="utf-8",
    )
    member_patch = f"""*** Begin Patch
*** Update File: {member_file}
@@
 public class C {{
-    public fun messageCount(): Int = 1
+    public fun messageCount(): Int = 2
 }}
*** End Patch
"""
    member_result = run_adapter(member_patch)
    check("member function hunk edit is not scanned as top-level", member_result.returncode == 0)
    check("member function hunk edit passes policy", '"decision": "block"' not in member_result.stdout)

    top_level_file = Path(tmp) / "TopLevel.kt"
    top_level_file.write_text("package x\n", encoding="utf-8")
    top_level_patch = f"""*** Begin Patch
*** Update File: {top_level_file}
@@
 package x
+public fun generateText(): Unit = Unit
*** End Patch
"""
    top_level_result = run_adapter(top_level_patch)
    check("top-level function hunk edit is still blocked", '"decision": "block"' in top_level_result.stdout)

    normal_file = Path(tmp) / "Normal.txt"
    normal_file.write_text("old\n", encoding="utf-8")
    two_file_kotlin_patch = f"""*** Begin Patch
*** Update File: {normal_file}
@@
-old
+new
*** Update File: {top_level_file}
@@
 package x
+public fun generateText(): Unit = Unit
*** End Patch
"""
    two_file_kotlin_result = run_adapter(two_file_kotlin_patch)
    check(
        "two-file patch with Kotlin violation second is blocked",
        '"decision": "block"' in two_file_kotlin_result.stdout,
    )

    entries = json.loads(MANIFEST.read_text(encoding="utf-8"))
    covered = next(e for e in entries if isinstance(e, dict) and e.get("badExample") and e.get("goodExample"))
    rule_file = RULES_DIR / f"{covered['id']}.yaml"
    first_rule_line = rule_file.read_text(encoding="utf-8").splitlines()[0]
    two_file_rule_patch = f"""*** Begin Patch
*** Update File: {normal_file}
@@
-old
+new
*** Update File: {rule_file}
@@
-{first_rule_line}
+id: [
*** End Patch
"""
    two_file_rule_result = run_adapter(two_file_rule_patch)
    check(
        "two-file patch with rule violation second is blocked",
        '"decision": "block"' in two_file_rule_result.stdout,
    )

if failures:
    print(f"FAILED {ran - len(failures)}/{ran}")
    for failure in failures:
        print(f"- {failure}")
    raise SystemExit(1)

print(f"ok {ran}")
