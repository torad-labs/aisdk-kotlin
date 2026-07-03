#!/usr/bin/env python3
"""Regression tests for the fleet-protocol PreToolUse policy (both directions)."""
from __future__ import annotations

import json
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
TARGET = ROOT / ".claude" / "hooks" / "orchestrator" / "pretooluse.py"
ADAPTER = ROOT / ".codex" / "hooks" / "claude_compat.py"
OWNED = ROOT / "docs" / "audit-remediation-backlog.md"

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
        timeout=20,
    )


def blocked(result: subprocess.CompletedProcess[str]) -> bool:
    return '"decision": "block"' in result.stdout or '"permissionDecision": "deny"' in result.stdout


# 1/2 — orchestrator-owned file: builder blocked, unstamped (orchestrator) allowed.
builder_write = {
    "tool_name": "Write",
    "fleet_role": "builder",
    "tool_input": {"file_path": str(OWNED), "content": "x"},
}
check("builder write to owned ledger is blocked", blocked(run_target(builder_write)))
orch_write = {k: v for k, v in builder_write.items() if k != "fleet_role"}
check("orchestrator write to owned ledger is allowed", not blocked(run_target(orch_write)))

# 3 — builder push blocked, incl. list-form codex shell commands.
check(
    "builder git push is blocked",
    blocked(run_target({
        "tool_name": "shell",
        "fleet_role": "builder",
        "tool_input": {"command": ["bash", "-lc", "git push origin refactor/ts-residue-cleanup"]},
    })),
)
check(
    "builder quoted git push subcommand is blocked",
    blocked(run_target({
        "tool_name": "Bash",
        "fleet_role": "builder",
        "tool_input": {"command": 'git "push" origin main'},
    })),
)
check(
    "builder nested bash -c git push is blocked",
    blocked(run_target({
        "tool_name": "Bash",
        "fleet_role": "builder",
        "tool_input": {"command": "bash -c 'git push origin main'"},
    })),
)
check(
    "builder env-prefixed git push is blocked",
    blocked(run_target({
        "tool_name": "Bash",
        "fleet_role": "builder",
        "tool_input": {"command": "env GIT_TRACE=1 git push origin main"},
    })),
)
check(
    "builder nested bash -c innocent command is allowed",
    not blocked(run_target({
        "tool_name": "Bash",
        "fleet_role": "builder",
        "tool_input": {"command": "bash -c 'echo ok'"},
    })),
)

# 4 — builder staging an owned file is blocked; staging normal files is not.
check(
    "builder git add of owned ledger is blocked",
    blocked(run_target({
        "tool_name": "Bash",
        "fleet_role": "builder",
        "tool_input": {"command": "git add docs/gate-hardening-backlog.md src/Foo.kt"},
    })),
)
check(
    "builder git add of normal files is allowed",
    not blocked(run_target({
        "tool_name": "Bash",
        "fleet_role": "builder",
        "tool_input": {"command": "git add src/commonMain/kotlin/ai/torad/aisdk/Foo.kt"},
    })),
)
check(
    "builder git add dot is blocked",
    blocked(run_target({
        "tool_name": "Bash",
        "fleet_role": "builder",
        "tool_input": {"command": "git add ."},
    })),
)
check(
    "builder git add all is blocked",
    blocked(run_target({
        "tool_name": "Bash",
        "fleet_role": "builder",
        "tool_input": {"command": "git add -A"},
    })),
)
check(
    "builder git commit all with message is blocked",
    blocked(run_target({
        "tool_name": "Bash",
        "fleet_role": "builder",
        "tool_input": {"command": "git commit -am x"},
    })),
)
check(
    "builder shell write to owned ledger is blocked",
    blocked(run_target({
        "tool_name": "Bash",
        "fleet_role": "builder",
        "tool_input": {"command": "printf x > docs/audit-remediation-backlog.md"},
    })),
)

# 5/6 — role-agnostic gate/history safety.
check(
    "--no-verify is blocked for any role",
    blocked(run_target({
        "tool_name": "Bash",
        "tool_input": {"command": "git commit --no-verify -m x"},
    })),
)
check(
    "quoted --no-verify argument is blocked",
    blocked(run_target({
        "tool_name": "Bash",
        "tool_input": {"command": 'git commit "--no-verify" -m x'},
    })),
)
check(
    "commit -n no-verify alias is blocked",
    blocked(run_target({
        "tool_name": "Bash",
        "tool_input": {"command": "git commit -n -m x"},
    })),
)
check(
    "bare force push is blocked",
    blocked(run_target({
        "tool_name": "Bash",
        "tool_input": {"command": "git push --force origin main"},
    })),
)
check(
    "plus-refspec force push is blocked",
    blocked(run_target({
        "tool_name": "Bash",
        "tool_input": {"command": "git push origin +HEAD:main"},
    })),
)
check(
    "force-with-lease is allowed",
    not blocked(run_target({
        "tool_name": "Bash",
        "tool_input": {"command": "git push --force-with-lease=main:abc123 origin main"},
    })),
)
check(
    "flag text inside a quoted commit message is prose, not an argument",
    not blocked(run_target({
        "tool_name": "Bash",
        "tool_input": {"command": 'git commit -m "guards block --no-verify and git push --force misuse"'},
    })),
)
check(
    "heredoc prose mentioning git push is allowed",
    not blocked(run_target({
        "tool_name": "Bash",
        "fleet_role": "builder",
        "tool_input": {"command": "cat <<EOF\nplease run git push origin main\nEOF"},
    })),
)
readme_mention_patch = {
    "tool_name": "apply_patch",
    "fleet_role": "builder",
    "cwd": str(ROOT),
    "tool_input": {
        "command": """*** Begin Patch
*** Update File: README.md
@@
 old
+Mention docs/audit-remediation-backlog.md in prose.
*** End Patch
"""
    },
}
check("patch body mention of owned ledger is allowed", not blocked(run_target(readme_mention_patch)))

# 7 — amend guard: blocked when HEAD is published, allowed when local-only.
with tempfile.TemporaryDirectory() as tmp:
    bare = Path(tmp) / "origin.git"
    work = Path(tmp) / "work"
    subprocess.run(["git", "init", "--bare", "-q", str(bare)], check=True)
    subprocess.run(["git", "init", "-q", str(work)], check=True)
    env_git = ["git", "-C", str(work)]
    subprocess.run(env_git + ["config", "user.email", "t@t"], check=True)
    subprocess.run(env_git + ["config", "user.name", "t"], check=True)
    (work / "a.txt").write_text("1")
    subprocess.run(env_git + ["add", "a.txt"], check=True)
    subprocess.run(env_git + ["commit", "-qm", "one"], check=True)
    subprocess.run(env_git + ["remote", "add", "origin", str(bare)], check=True)
    subprocess.run(env_git + ["push", "-q", "origin", "HEAD:main"], check=True)

    amend_event = {
        "tool_name": "Bash",
        "cwd": str(work),
        "tool_input": {"command": "git commit --amend -m rewritten"},
    }
    check("amend of a published HEAD is blocked", blocked(run_target(amend_event)))

    foreign = Path(tmp) / "foreign"
    subprocess.run(["git", "init", "-q", str(foreign)], check=True)
    foreign_git = ["git", "-C", str(foreign)]
    subprocess.run(foreign_git + ["config", "user.email", "t@t"], check=True)
    subprocess.run(foreign_git + ["config", "user.name", "t"], check=True)
    (foreign / "local.txt").write_text("1")
    subprocess.run(foreign_git + ["add", "local.txt"], check=True)
    subprocess.run(foreign_git + ["commit", "-qm", "local"], check=True)
    check(
        "git -C published amend is checked against target repo",
        blocked(run_target({
            "tool_name": "Bash",
            "cwd": str(foreign),
            "tool_input": {"command": f"git -C {work} commit --amend -m rewritten"},
        })),
    )

    (work / "b.txt").write_text("2")
    subprocess.run(env_git + ["add", "b.txt"], check=True)
    subprocess.run(env_git + ["commit", "-qm", "two"], check=True)
    check("amend of an unpushed HEAD is allowed", not blocked(run_target(amend_event)))

# 8 — adapter end-to-end: an apply_patch from the builder session touching an
# owned file must be blocked via the fleet_role stamp.
adapter_event = {
    "tool_name": "apply_patch",
    "cwd": str(ROOT),
    "tool_input": {
        "command": f"""*** Begin Patch
*** Update File: {OWNED}
@@
-old
+new
*** End Patch
"""
    },
}
adapter_result = subprocess.run(
    [sys.executable, str(ADAPTER), "pretooluse", str(TARGET)],
    input=json.dumps(adapter_event),
    capture_output=True,
    text=True,
    timeout=20,
)
check("adapter stamps builder role: owned-file patch blocked", blocked(adapter_result))

if failures:
    print(f"FAILED {ran - len(failures)}/{ran}")
    for failure in failures:
        print(f"- {failure}")
    raise SystemExit(1)

print(f"ok {ran}")
