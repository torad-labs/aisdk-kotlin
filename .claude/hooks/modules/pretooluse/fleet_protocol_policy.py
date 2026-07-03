"""Fleet-protocol policy: structural enforcement of the orchestrator/builder split.

Concept #924 ("make drift not compile"): the fleet working agreement — builder
never pushes, never touches orchestrator-owned ledgers, nobody amends published
history or bypasses gates — was previously prose. Prose is a probabilistic
filter over an unbounded generator; this module makes the violations
inexpressible at the tool boundary instead.

Builder detection: the Codex adapter (.codex/hooks/claude_compat.py) stamps
every forwarded event with fleet_role="builder". Unstamped events are the
orchestrator/owner session and keep full authority over the owned files, but
still get the role-agnostic history-safety guards.
"""
from __future__ import annotations

import re
import subprocess
from pathlib import Path

from orchestrator.result import HookResult

MODULE_ORDER = 20
MODULE_NAME = "fleet_protocol_policy"

WRITE_TOOLS = {"Write", "Edit", "MultiEdit", "NotebookEdit"}
SHELL_TOOLS = {"Bash", "shell", "local_shell", "exec_command", "functions.shell"}
PATCH_TOOLS = {"apply_patch", "functions.apply_patch"}

ORCHESTRATOR_OWNED = (
    "docs/audit-remediation-backlog.md",
    "docs/gate-hardening-backlog.md",
    ".claude/PLAN.md",
)

_GIT_PUSH_RE = re.compile(r"\bgit\b[^|;&]*\bpush\b")
_GIT_STAGE_RE = re.compile(r"\bgit\b[^|;&]*\b(add|commit|rm|mv|restore|checkout)\b")
_GIT_AMEND_RE = re.compile(r"\bgit\b[^|;&]*\bcommit\b[^|;&]*--amend")
_NO_VERIFY_RE = re.compile(r"\bgit\b[^|;&]*\b(commit|push)\b[^|;&]*--no-verify")
_FORCE_RE = re.compile(r"\bgit\b[^|;&]*\bpush\b[^|;&]*(\s--force\b|\s-f\b)")

_REPO_ROOT = Path(__file__).resolve().parents[4]


def applies(data: dict) -> bool:
    tool = data.get("tool_name")
    return tool in WRITE_TOOLS or tool in SHELL_TOOLS or tool in PATCH_TOOLS


def run(data: dict) -> HookResult | None:
    role = data.get("fleet_role")
    tool = data.get("tool_name")
    tool_input = data.get("tool_input") or {}

    if tool in WRITE_TOOLS:
        if role != "builder":
            return None
        path = str(tool_input.get("file_path") or tool_input.get("notebook_path") or "")
        owned = _owned_match(path)
        if owned:
            return _block(
                f"FLEET PROTOCOL: `{owned}` is orchestrator-owned.\n"
                "The builder never edits or stages the backlog/plan ledgers — "
                "report your status via torad-fleet sendMessage to "
                "aisdk-orchestrator instead; the orchestrator records it."
            )
        return None

    if tool in PATCH_TOOLS:
        if role != "builder":
            return None
        patch_text = _command_text(tool_input)
        owned = next((o for o in ORCHESTRATOR_OWNED if o in patch_text), None)
        if owned:
            return _block(
                f"FLEET PROTOCOL: this patch touches orchestrator-owned `{owned}`.\n"
                "Remove it from the patch; report via sendMessage instead."
            )
        return None

    command = _command_text(tool_input)
    if not command or "git" not in command:
        return None

    # Role-agnostic history/gate safety (protects the orchestrator too).
    if _NO_VERIFY_RE.search(command):
        return _block(
            "GATE POLICY: `--no-verify` is never allowed (CLAUDE.md working "
            "agreement). Fix the failing gate; do not bypass it."
        )
    force = _FORCE_RE.search(command)
    if force and "--force-with-lease" not in command:
        return _block(
            "HISTORY SAFETY: bare `git push --force` is not allowed. Use "
            "`--force-with-lease=<branch>:<expected-sha>` after verifying the "
            "remote tip, so a concurrent push cannot be clobbered."
        )
    if _GIT_AMEND_RE.search(command) and _head_is_published(data):
        return _block(
            "HISTORY SAFETY: HEAD is already on origin — amending it forks "
            "published history (this raced once on 2026-07-02). Make a "
            "follow-up commit instead, or coordinate a lease-guarded rewrite "
            "with the orchestrator."
        )

    if role == "builder":
        if _GIT_PUSH_RE.search(command):
            return _block(
                "FLEET PROTOCOL: the builder never pushes. Commit locally and "
                "report the SHA via sendMessage to aisdk-orchestrator; the "
                "orchestrator reviews, then pushes."
            )
        if _GIT_STAGE_RE.search(command):
            owned = next((o for o in ORCHESTRATOR_OWNED if o in command), None)
            if owned:
                return _block(
                    f"FLEET PROTOCOL: `{owned}` is orchestrator-owned; do not "
                    "stage, restore, or commit it. Leave it untouched and "
                    "report via sendMessage instead."
                )

    return None


def _owned_match(path: str) -> str | None:
    if not path:
        return None
    normalized = path.replace("\\", "/")
    for owned in ORCHESTRATOR_OWNED:
        if normalized.endswith(owned):
            return owned
    return None


def _command_text(tool_input: dict) -> str:
    raw = tool_input.get("command", "")
    if isinstance(raw, list):
        text = " ".join(str(part) for part in raw)
    else:
        text = str(raw)
    return _strip_quoted(text)


_QUOTED_RE = re.compile(r"'[^']*'|\"[^\"]*\"")


def _strip_quoted(text: str) -> str:
    # Flags inside quoted strings (commit messages, heredoc-ish args) are prose,
    # not arguments — this module's own announcement commit proved it. Strip
    # quoted spans before any regex claim about the command's arguments.
    return _QUOTED_RE.sub(" ", text)


def _head_is_published(data: dict) -> bool:
    cwd = str(data.get("cwd") or _REPO_ROOT)
    try:
        result = subprocess.run(
            ["git", "branch", "-r", "--contains", "HEAD"],
            cwd=cwd, capture_output=True, text=True, timeout=3,
        )
    except (subprocess.TimeoutExpired, OSError):
        return False  # heuristic guard only; --force-with-lease is the backstop
    return result.returncode == 0 and bool(result.stdout.strip())


def _block(message: str) -> HookResult:
    return HookResult(kind="block", payload=message, module_name=MODULE_NAME)
