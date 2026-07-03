#!/usr/bin/env python3
"""Project-local Codex adapter for this repo's Claude PreToolUse hook."""
from __future__ import annotations

import json
import os
import subprocess
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

TARGET_TIMEOUT_SECONDS = 8


@dataclass
class PatchFile:
    path: str
    kind: str
    new_lines: list[str] = field(default_factory=list)
    old_lines: list[str] = field(default_factory=list)
    hunks: list["PatchHunk"] = field(default_factory=list)


@dataclass
class PatchHunk:
    new_lines: list[str] = field(default_factory=list)
    old_lines: list[str] = field(default_factory=list)
    added_lines: list[str] = field(default_factory=list)


def main() -> int:
    if len(sys.argv) != 3:
        print("usage: claude_compat.py <lifecycle> <target_hook_script>", file=sys.stderr)
        return 2

    lifecycle = sys.argv[1].lower()
    target = Path(sys.argv[2]).expanduser()
    if lifecycle != "pretooluse":
        print(json.dumps({
            "decision": "block",
            "reason": f"Unsupported local Claude hook lifecycle for Codex adapter: {lifecycle}",
        }))
        return 0

    try:
        data = json.load(sys.stdin)
    except (json.JSONDecodeError, OSError):
        print(json.dumps({
            "decision": "block",
            "reason": "Local Claude PreToolUse hook received invalid JSON input.",
        }))
        return 0
    if not isinstance(data, dict):
        print(json.dumps({
            "decision": "block",
            "reason": "Local Claude PreToolUse hook received a non-object JSON event.",
        }))
        return 0

    return _run_pretooluse(target, data)


def _run_pretooluse(target: Path, data: dict[str, Any]) -> int:
    # Events arriving through this adapter are, by construction, the builder
    # session's tool calls. Stamp them so repo policy modules can enforce the
    # fleet protocol (orchestrator-owned files, no builder pushes) structurally.
    data = dict(data)
    data["fleet_role"] = "builder"
    first = _run_once(target, data)
    if _forward_if_blocking(first) or _fail_closed_if_nonzero(first, target):
        return 0

    for synthetic in _synthetic_write_events(data):
        result = _run_once(target, synthetic)
        if _forward_if_blocking(result) or _fail_closed_if_nonzero(result, target):
            return 0
    return 0


def _run_once(target: Path, payload: dict[str, Any]) -> subprocess.CompletedProcess[str]:
    try:
        result = subprocess.run(
            [sys.executable, str(target)],
            input=json.dumps(payload),
            capture_output=True,
            text=True,
            timeout=TARGET_TIMEOUT_SECONDS,
        )
    except subprocess.TimeoutExpired:
        return _blocking_result(f"Local Claude hook target timed out: {target}")
    except OSError as exc:
        return _blocking_result(f"Local Claude hook target could not execute: {target}: {exc}")

    if result.stderr:
        sys.stderr.write(result.stderr)
    return result


def _blocking_result(reason: str) -> subprocess.CompletedProcess[str]:
    return subprocess.CompletedProcess(
        ["local-claude-hook"],
        0,
        json.dumps({"decision": "block", "reason": reason}),
        "",
    )


def _fail_closed_if_nonzero(result: subprocess.CompletedProcess[str], target: Path) -> bool:
    if result.returncode == 0:
        return False
    print(json.dumps({
        "decision": "block",
        "reason": f"Local Claude PreToolUse hook exited nonzero: {target} (exit {result.returncode})",
    }))
    return True


def _forward_if_blocking(result: subprocess.CompletedProcess[str]) -> bool:
    out = result.stdout.strip()
    if not out:
        return False
    try:
        payload = json.loads(out)
    except json.JSONDecodeError:
        print(out, file=sys.stderr)
        return False

    specific = payload.get("hookSpecificOutput") if isinstance(payload, dict) else None
    denied = isinstance(specific, dict) and specific.get("permissionDecision") == "deny"
    blocked = isinstance(payload, dict) and payload.get("decision") == "block"
    if denied or blocked:
        print(json.dumps(payload))
        return True
    return False


def _synthetic_write_events(data: dict[str, Any]) -> list[dict[str, Any]]:
    if data.get("tool_name") not in ("apply_patch", "functions.apply_patch"):
        return []
    patch_text = _patch_text(data)
    if not patch_text:
        return []

    cwd = Path(str(data.get("cwd") or os.getcwd()))
    events: list[dict[str, Any]] = []
    parsed_files = _parse_apply_patch(patch_text)
    aggregate_edits: list[dict[str, str]] = []
    aggregate_path = ""
    for patch_file in parsed_files:
        hook_path = _hook_path(cwd, patch_file.path)
        for hunk in patch_file.hunks:
            if hunk.old_lines or hunk.new_lines:
                aggregate_edits.append({
                    "old_string": "\n".join(hunk.old_lines),
                    "new_string": "\n".join(hunk.new_lines),
                    "file_path": hook_path,
                })
                if not aggregate_path:
                    aggregate_path = hook_path
    if aggregate_edits:
        event = dict(data)
        event["tool_name"] = "MultiEdit"
        event["tool_input"] = {"file_path": aggregate_path, "edits": aggregate_edits}
        events.append(event)

    for patch_file in parsed_files:
        hook_path = _hook_path(cwd, patch_file.path)
        if patch_file.kind == "add":
            event = dict(data)
            event["tool_name"] = "Write"
            event["tool_input"] = {"file_path": hook_path, "content": "\n".join(patch_file.new_lines)}
            events.append(event)
        elif patch_file.kind == "delete":
            event = dict(data)
            event["tool_name"] = "Write"
            event["tool_input"] = {"file_path": hook_path, "content": "", "operation": "delete"}
            events.append(event)
    return events


def _patch_text(data: dict[str, Any]) -> str:
    tool_input = data.get("tool_input")
    if isinstance(tool_input, str):
        return tool_input
    if not isinstance(tool_input, dict):
        return ""
    for key in ("command", "patch", "input"):
        value = tool_input.get(key)
        if isinstance(value, str):
            return value
    return ""


def _parse_apply_patch(patch_text: str) -> list[PatchFile]:
    files: list[PatchFile] = []
    current: PatchFile | None = None
    current_hunk: PatchHunk | None = None

    def flush() -> None:
        nonlocal current, current_hunk
        flush_hunk()
        if current is not None:
            files.append(current)
        current = None
        current_hunk = None

    def flush_hunk() -> None:
        nonlocal current_hunk
        if current is not None and current_hunk is not None:
            if current_hunk.old_lines or current_hunk.new_lines:
                current.hunks.append(current_hunk)
        current_hunk = None

    def hunk() -> PatchHunk:
        nonlocal current_hunk
        if current_hunk is None:
            current_hunk = PatchHunk()
        return current_hunk

    for raw in patch_text.splitlines():
        if raw.startswith("*** Add File: "):
            flush()
            current = PatchFile(path=raw.split(": ", 1)[1], kind="add")
            continue
        if raw.startswith("*** Update File: "):
            flush()
            current = PatchFile(path=raw.split(": ", 1)[1], kind="update")
            continue
        if raw.startswith("*** Delete File: "):
            flush()
            current = PatchFile(path=raw.split(": ", 1)[1], kind="delete")
            continue
        if raw.startswith("*** Move to: "):
            if current is not None:
                current.kind = "move"
                current.path = raw.split(": ", 1)[1]
            continue
        if raw.startswith("@@"):
            flush_hunk()
            current_hunk = PatchHunk()
            continue
        if raw.startswith("*** End Patch"):
            flush()
            continue
        if current is None:
            continue
        if current.kind == "add" and raw.startswith("+"):
            current.new_lines.append(raw[1:])
            continue
        if raw.startswith("+"):
            target = hunk()
            target.new_lines.append(raw[1:])
            target.added_lines.append(raw[1:])
        elif raw.startswith("-"):
            hunk().old_lines.append(raw[1:])
        elif raw.startswith(" "):
            line = raw[1:]
            target = hunk()
            target.old_lines.append(line)
            target.new_lines.append(line)

    flush()
    return files


def _hook_path(cwd: Path, path: str) -> str:
    candidate = Path(path)
    if candidate.is_absolute():
        return str(candidate)
    return str((cwd / candidate).resolve(strict=False))


if __name__ == "__main__":
    raise SystemExit(main())
