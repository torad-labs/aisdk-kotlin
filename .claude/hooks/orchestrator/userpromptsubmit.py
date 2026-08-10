#!/usr/bin/env python3
"""Repo-local UserPromptSubmit orchestrator.

Mirrors the PreToolUse orchestrator's module contract so both events load modules the same way:
`MODULE_ORDER`, optional `applies(data)`, and `run(data) -> HookResult | None`. A module that
returns a "warn" result has its payload surfaced to the user as additional context.

WHY THIS EVENT EXISTS HERE AT ALL. It carries `/grant`, and the whole security value of the grant
gate is that issuance originates from text a HUMAN typed. UserPromptSubmit fires only on that; an
assistant emits tool calls and assistant messages and can never emit a user prompt. Putting
issuance anywhere an assistant can reach — a script, a CLI subcommand — would make the gate
decorative.
"""
from __future__ import annotations

import importlib.util
import json
import sys
import time
import traceback
from pathlib import Path
from typing import Optional

ROOT = Path(__file__).resolve().parents[3]
HOOKS_ROOT = ROOT / ".claude" / "hooks"
MODULES_DIR = HOOKS_ROOT / "modules" / "userpromptsubmit"
LIMIT_SECONDS = 9.0

sys.dont_write_bytecode = True

if str(HOOKS_ROOT) not in sys.path:
    sys.path.insert(0, str(HOOKS_ROOT))

from orchestrator.result import HookResult  # noqa: E402


def main() -> int:
    try:
        data = json.load(sys.stdin)
    except (json.JSONDecodeError, OSError):
        return 0
    if not isinstance(data, dict):
        return 0

    started = time.monotonic()
    notes: list[str] = []

    for path in _module_files():
        if time.monotonic() - started > LIMIT_SECONDS:
            break
        result, _ = _run(path, data)
        if result is None:
            continue
        if result.kind in ("block", "warn"):
            notes.append(result.payload.rstrip())

    if notes:
        sys.stdout.write(
            json.dumps(
                {
                    "hookSpecificOutput": {
                        "hookEventName": "UserPromptSubmit",
                        "additionalContext": "\n\n".join(notes),
                    }
                }
            )
        )
    return 0


def _module_files() -> list[Path]:
    if not MODULES_DIR.is_dir():
        return []
    modules = [
        path
        for path in MODULES_DIR.glob("*.py")
        if path.name != "__init__.py" and not path.name.startswith("disabled_")
    ]
    return sorted(modules, key=_module_order)


def _module_order(path: Path) -> tuple[int, str]:
    spec = importlib.util.spec_from_file_location(path.stem, path)
    if spec is None or spec.loader is None:
        return (1000, path.name)
    module = importlib.util.module_from_spec(spec)
    try:
        sys.modules[path.stem] = module
        spec.loader.exec_module(module)
        order = getattr(module, "MODULE_ORDER", 1000)
    except Exception:
        order = 1000
    return (order if isinstance(order, int) else 1000, path.name)


def _run(path: Path, data: dict) -> tuple[Optional[HookResult], Optional[str]]:
    try:
        spec = importlib.util.spec_from_file_location(path.stem, path)
        if spec is None or spec.loader is None:
            return (None, "load")
        module = importlib.util.module_from_spec(spec)
        sys.modules[path.stem] = module
        spec.loader.exec_module(module)

        applies = getattr(module, "applies", None)
        if callable(applies) and not applies(data):
            return (None, None)

        run = getattr(module, "run", None)
        if not callable(run):
            return (None, "missing run")

        result = run(data)
        if result is not None and not isinstance(result, HookResult):
            return (None, "bad result")
        return (result, None)
    except Exception:
        sys.stderr.write(traceback.format_exc())
        return (None, "exception")


if __name__ == "__main__":
    raise SystemExit(main())
