"""Block raw edits to shared ledgers; require the ledger CLIs instead.

The campaign and measurements ledgers are cross-session shared memory. Their
CLIs provide locking, TOML validation, and history-preserving semantics; direct
Write/Edit/MultiEdit tool calls bypass those protections. The Bash CLI path is
left untouched by design.
"""
from __future__ import annotations

import os
from pathlib import Path
from typing import Any

from orchestrator.result import HookResult

MODULE_ORDER = 24
MODULE_NAME = "ledger_cli_only_policy"

WRITE_TOOLS = {"Write", "Edit", "MultiEdit", "NotebookEdit"}
OVERRIDE_ENV = "AISDK_LEDGER_RAW_OK"
_REPO_ROOT = Path(__file__).resolve().parents[4]
_CAMPAIGNS_DIR = _REPO_ROOT / "dev" / "campaigns"
_MEASUREMENTS = _REPO_ROOT / "dev" / "measurements.toml"


def applies(data: dict[str, Any]) -> bool:
    return data.get("tool_name") in WRITE_TOOLS and bool(_ledger_paths(data))


def run(data: dict[str, Any]) -> HookResult | None:
    targets = _ledger_paths(data)
    if not targets:
        return None

    target = targets[0]
    if os.environ.get(OVERRIDE_ENV):
        return HookResult(
            kind="warn",
            module_name=MODULE_NAME,
            payload=(
                f"{MODULE_NAME}: {OVERRIDE_ENV} override allowed a raw ledger "
                f"write to `{_display(target)}`. This override is operator-only; "
                "normal work must use the ledger CLI."
            ),
        )

    if data.get("tool_name") == "Write" and not target.exists():
        return None

    return HookResult(
        kind="block",
        module_name=MODULE_NAME,
        payload=(
            f"LEDGER POLICY: raw tool writes to `{_display(target)}` are blocked.\n"
            "Use the ledger CLI through Bash so updates keep the flock, TOML "
            "validation, and append-only measurement history:\n"
            f"  { _cli_hint(target) }\n"
            f"Operator-only emergency override: {OVERRIDE_ENV}=1."
        ),
    )


def _ledger_paths(data: dict[str, Any]) -> list[Path]:
    paths: list[Path] = []
    for raw in _raw_file_paths(data.get("tool_input")):
        resolved = _resolve(raw, _event_cwd(data))
        if _is_ledger(resolved):
            paths.append(resolved)
    return paths


def _raw_file_paths(tool_input: object) -> list[str]:
    if not isinstance(tool_input, dict):
        return []
    paths: list[str] = []
    for key in ("file_path", "notebook_path"):
        value = tool_input.get(key)
        if isinstance(value, str) and value:
            paths.append(value)
    edits = tool_input.get("edits")
    if isinstance(edits, list):
        for edit in edits:
            if isinstance(edit, dict):
                value = edit.get("file_path")
                if isinstance(value, str) and value:
                    paths.append(value)
    return paths


def _is_ledger(path: Path) -> bool:
    if path == _MEASUREMENTS:
        return True
    return path.parent == _CAMPAIGNS_DIR and path.suffix == ".toml"


def _resolve(raw: str, cwd: Path) -> Path:
    path = Path(raw)
    if path.is_absolute():
        return path.resolve(strict=False)
    return (cwd / path).resolve(strict=False)


def _event_cwd(data: dict[str, Any]) -> Path:
    return Path(str(data.get("cwd") or _REPO_ROOT)).resolve(strict=False)


def _display(path: Path) -> str:
    try:
        return path.relative_to(_REPO_ROOT).as_posix()
    except ValueError:
        return str(path)


def _cli_hint(path: Path) -> str:
    if path == _MEASUREMENTS:
        return "python3 dev/measurements_ledger.py <command> ..."
    return "python3 dev/campaigns/manifest.py <command> ..."
