#!/usr/bin/env python3
"""Repo-local PreToolUse orchestrator."""
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
MODULES_DIR = HOOKS_ROOT / "modules" / "pretooluse"
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
    errors: list[str] = []

    for path in _module_files():
        if time.monotonic() - started > LIMIT_SECONDS:
            _block("REPO HOOK POLICY INCOMPLETE\n\nLocal PreToolUse policy exceeded its time budget.")
            return 0

        result, error = _run(path, data)
        if error:
            errors.append(path.stem)
            continue
        if result is None:
            continue
        if result.kind == "block":
            _block(result.payload)
            return 0
        if result.kind == "warn":
            sys.stderr.write(result.payload.rstrip() + "\n")

    if errors:
        _block(
            "REPO HOOK POLICY INCOMPLETE\n\n"
            "Local PreToolUse module error(s): " + ", ".join(errors[:5]) + "."
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


def _block(reason: str) -> None:
    sys.stdout.write(json.dumps({"decision": "block", "reason": reason}))


if __name__ == "__main__":
    raise SystemExit(main())
