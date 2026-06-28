#!/usr/bin/env python3
from __future__ import annotations

import json
import subprocess
import sys
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


def check(name: str, condition: bool, failures: list[str]) -> None:
    if not condition:
        failures.append(name)


def main() -> int:
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

    if failures:
        print(f"{len(failures)} failed")
        for failure in failures:
            print(f"  - {failure}")
        return 1
    print("ok")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
