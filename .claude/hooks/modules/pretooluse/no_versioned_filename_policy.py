"""Block new files that encode parallel implementations in their names."""
from __future__ import annotations

import re
from pathlib import Path
from typing import Any

from orchestrator.result import HookResult

MODULE_ORDER = 25
MODULE_NAME = "no_versioned_filename_policy"

WRITE_TOOLS = {"Write", "Edit", "MultiEdit", "NotebookEdit"}
BLOCK_EXTENSIONS = {".kt", ".kts", ".py", ".yaml"}
WARN_EXTENSIONS = {".md"}
_FORBIDDEN_STEM = re.compile(r"_(?:v\d+|new|final|old|backup|copy)$", re.IGNORECASE)
_REPO_ROOT = Path(__file__).resolve().parents[4]


def applies(data: dict[str, Any]) -> bool:
    return data.get("tool_name") in WRITE_TOOLS and bool(_candidate_paths(data))


def run(data: dict[str, Any]) -> HookResult | None:
    for target in _candidate_paths(data):
        if target.exists():
            continue
        suffix = _forbidden_suffix(target)
        if suffix is None:
            continue
        if target.suffix.lower() in WARN_EXTENSIONS:
            return HookResult(
                kind="warn",
                module_name=MODULE_NAME,
                payload=(
                    f"{MODULE_NAME}: creating `{_display(target)}` uses the "
                    f"parallel-copy suffix `{suffix}`. Prefer editing or "
                    "renaming the canonical file in place."
                ),
            )
        return HookResult(
            kind="block",
            module_name=MODULE_NAME,
            payload=(
                f"FILENAME POLICY: do not create `{_display(target)}`.\n"
                f"The stem ends in `{suffix}`, which signals a parallel copy "
                "rather than a canonical replacement. Edit or rename the "
                "existing file in place."
            ),
        )
    return None


def _candidate_paths(data: dict[str, Any]) -> list[Path]:
    paths: list[Path] = []
    for raw in _raw_file_paths(data.get("tool_input")):
        resolved = _resolve(raw, _event_cwd(data))
        if resolved.suffix.lower() in BLOCK_EXTENSIONS | WARN_EXTENSIONS:
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


def _forbidden_suffix(path: Path) -> str | None:
    match = _FORBIDDEN_STEM.search(path.stem)
    return match.group(0) if match else None


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
