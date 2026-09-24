#!/usr/bin/env python3
"""The committed pre-commit hook must not let the gate reach the committing repository.

Under a pre-commit hook in a LINKED worktree, git exports GIT_DIR and GIT_INDEX_FILE as absolute
paths into .git/worktrees/<name>. The gate's steps build scratch repositories, for example
test_fleet_protocol_policy.py's `git init -q <tmp>/work` and `git -C <tmp>/work config user.name t`.
When those calls inherited the variables, they rewrote the shared .git/config on 2026-09-23: the
init re-initialised it with core.bare=true and the config call wrote user.name=t. The primary
checkout stopped being a work tree.

This commits from a linked worktree of a throwaway victim whose gate does exactly what those suites
did. The hook is the real .githooks/pre-commit and the environment is git's own, so it asserts on the
hook's contract rather than on any one gate step: nothing the gate runs can write into the repository
being committed.
"""
from __future__ import annotations

import os
import shutil
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
HOOK = ROOT / ".githooks" / "pre-commit"

failures: list[str] = []
ran = 0


def check(name: str, condition: bool, detail: str = "") -> None:
    global ran
    ran += 1
    if not condition:
        failures.append(f"{name}{': ' + detail if detail else ''}")


# This file itself runs inside the gate. Its own git calls must not inherit a hook's
# repository-local variables either, or building the victim would damage the repository under test.
LOCAL = set(
    subprocess.run(["git", "rev-parse", "--local-env-vars"], capture_output=True, text=True, check=True)
    .stdout.split()
)
CLEAN = {name: value for name, value in os.environ.items() if name not in LOCAL}

# What the scratch-repo suites did, reduced to the two calls that did the damage.
LEAKY_GATE = """#!/usr/bin/env bash
scratch="$(mktemp -d)"
git init --quiet "$scratch/work"
git -C "$scratch/work" config user.name t
rm -rf "$scratch"
exit 0
"""


def git(cwd: Path, *args: str) -> str:
    result = subprocess.run(
        ["git", "-C", str(cwd), "-c", "user.name=v", "-c", "user.email=v@v", *args],
        capture_output=True,
        text=True,
        env=CLEAN,
    )
    if result.returncode != 0:
        raise SystemExit(f"setup: git {' '.join(args)} failed: {result.stderr.strip()}")
    return result.stdout.strip()


temp = Path(tempfile.mkdtemp(prefix="pre-commit-git-env-"))
try:
    victim = temp / "victim"
    gate = victim / ".claude" / "hooks" / "rules" / "ci-gate.sh"
    gate.parent.mkdir(parents=True)
    gate.write_text(LEAKY_GATE, encoding="utf-8")
    gate.chmod(0o755)
    git(victim, "init", "--quiet")
    git(victim, "add", "-A")
    git(victim, "commit", "--quiet", "-m", "victim")
    linked = temp / "linked"
    git(victim, "worktree", "add", "--detach", "--quiet", str(linked))
    victim_head = git(victim, "rev-parse", "HEAD")

    hooks = temp / "hooks"
    hooks.mkdir()
    shutil.copy2(HOOK, hooks / "pre-commit")

    commit = subprocess.run(
        [
            "git", "-C", str(linked), "-c", f"core.hooksPath={hooks}", "-c", "user.name=v",
            "-c", "user.email=v@v", "commit", "--allow-empty", "--quiet", "-m", "probe",
        ],
        capture_output=True,
        text=True,
        env=CLEAN,
    )
    shared_config = victim / ".git" / "config"

    def config_get(key: str) -> str:
        return subprocess.run(
            ["git", "config", "--file", str(shared_config), "--get", key],
            capture_output=True, text=True, env=CLEAN,
        ).stdout.strip()

    check("the probe commit went through the hook", commit.returncode == 0, commit.stderr.strip())
    check("the gate left the shared config non-bare", config_get("core.bare") != "true", config_get("core.bare"))
    check("the gate wrote no identity into the shared config", config_get("user.name") == "", config_get("user.name"))
    check("the victim's branch did not move", git(victim, "rev-parse", "HEAD") == victim_head)
finally:
    shutil.rmtree(temp, ignore_errors=True)

if failures:
    print(f"FAILED {ran - len(failures)}/{ran}")
    for failure in failures:
        print(f"- {failure}")
    raise SystemExit(1)

print(f"ok {ran}")
