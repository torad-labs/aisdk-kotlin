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

if failures:
    print(f"FAILED {ran - len(failures)}/{ran}")
    for failure in failures:
        print(f"- {failure}")
    raise SystemExit(1)

print(f"ok {ran}")
