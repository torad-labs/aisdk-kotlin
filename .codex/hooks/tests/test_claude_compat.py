#!/usr/bin/env python3
from __future__ import annotations

import json
import subprocess
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
WRAPPER = ROOT / ".codex" / "hooks" / "claude_compat.py"
TARGET = ROOT / ".claude" / "hooks" / "orchestrator" / "pretooluse.py"


def run(payload: dict) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [sys.executable, str(WRAPPER), "pretooluse", str(TARGET)],
        input=json.dumps(payload),
        capture_output=True,
        text=True,
        timeout=15,
    )


def run_raw(lifecycle: str, stdin: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [sys.executable, str(WRAPPER), lifecycle, str(TARGET)],
        input=stdin,
        capture_output=True,
        text=True,
        timeout=15,
    )


def check(name: str, condition: bool, failures: list[str]) -> None:
    if not condition:
        failures.append(name)


def collect_failures() -> list[str]:
    failures: list[str] = []
    hook_config = json.loads((ROOT / ".codex" / "hooks.json").read_text())
    command = hook_config["hooks"]["PreToolUse"][0]["hooks"][0]["command"]
    check("local hooks config uses repo-local Codex adapter", "$root/.codex/hooks/claude_compat.py" in command, failures)
    check("local hooks config targets repo-local Claude hook", "$root/.claude/hooks/orchestrator/pretooluse.py" in command, failures)
    check("local hooks config does not target global hooks", "$HOME/.codex" not in command and "$HOME/.claude" not in command, failures)

    bad_patch = (
        "*** Begin Patch\n"
        "*** Add File: src/commonMain/kotlin/ai/torad/aisdk/CodexHookSmoke.kt\n"
        "+package ai.torad.aisdk\n"
        "+\n"
        "+internal object CodexHookSmoke { fun value(x: String?) = x!! }\n"
        "*** End Patch\n"
    )
    blocked = run({
        "session_id": "local-codex-hook-test",
        "cwd": str(ROOT),
        "tool_name": "apply_patch",
        "tool_input": {"command": bad_patch},
    })
    check("apply_patch Kotlin anti-pattern blocks", '"decision": "block"' in blocked.stdout, failures)
    check("apply_patch block names no-not-null-assertion", "no-not-null-assertion" in blocked.stdout, failures)

    namespaced = run({
        "session_id": "local-codex-hook-test",
        "cwd": str(ROOT),
        "tool_name": "functions.apply_patch",
        "tool_input": {"command": bad_patch},
    })
    check("functions.apply_patch Kotlin anti-pattern blocks", '"decision": "block"' in namespaced.stdout, failures)

    safe_patch = (
        "*** Begin Patch\n"
        "*** Add File: src/commonMain/kotlin/ai/torad/aisdk/CodexHookSafe.kt\n"
        "+package ai.torad.aisdk\n"
        "+\n"
        "+internal object CodexHookSafe { fun value(x: String?): String = x ?: \"\" }\n"
        "*** End Patch\n"
    )
    allowed = run({
        "session_id": "local-codex-hook-test",
        "cwd": str(ROOT),
        "tool_name": "apply_patch",
        "tool_input": {"command": safe_patch},
    })
    check("safe apply_patch passes", allowed.stdout.strip() == "", failures)

    invalid_json = run_raw("pretooluse", "{not-json")
    check("invalid JSON fails closed", '"decision": "block"' in invalid_json.stdout, failures)
    check("invalid JSON block explains malformed input", "invalid JSON input" in invalid_json.stdout, failures)

    non_object = run_raw("pretooluse", "[]")
    check("non-object JSON fails closed", '"decision": "block"' in non_object.stdout, failures)
    check("non-object JSON block explains event shape", "non-object JSON event" in non_object.stdout, failures)

    unsupported_lifecycle = run_raw("posttooluse", json.dumps({"tool_name": "Write", "tool_input": {}}))
    check("unsupported lifecycle fails closed", '"decision": "block"' in unsupported_lifecycle.stdout, failures)
    check("unsupported lifecycle block names lifecycle", "posttooluse" in unsupported_lifecycle.stdout, failures)

    return failures


class ClaudeCompatHookTest(unittest.TestCase):
    def test_local_codex_hook_compatibility(self) -> None:
        failures = collect_failures()
        self.assertEqual([], failures)


def main() -> int:
    failures = collect_failures()
    if failures:
        print(f"{len(failures)} failed")
        for failure in failures:
            print(f"  - {failure}")
        return 1
    print("ok")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
