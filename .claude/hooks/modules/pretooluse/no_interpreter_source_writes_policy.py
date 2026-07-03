"""Block Bash-routed source writes that bypass edit-time guards.

The edit layer sees Write/Edit/MultiEdit events, not arbitrary shell writes.
Inline interpreters, redirection, tee, and in-place stream editors can otherwise
modify guarded source files without the 71-rule edit layer seeing the content.
Generated API dump files are stricter: hand edits are never allowed, because
they can make ABI checks green while silently changing the public contract.
"""
from __future__ import annotations

import re
from pathlib import Path
from typing import Any, Iterable

from fleet_protocol_policy import (
    _command_segments,
    _command_text,
    _event_cwd,
    _expand_nested_shell_segments,
    _resolve_path,
    _skip_exec_prefixes,
)
from orchestrator.result import HookResult

MODULE_ORDER = 26
MODULE_NAME = "no_interpreter_source_writes_policy"

WRITE_TOOLS = {"Write", "Edit", "MultiEdit", "NotebookEdit"}
SHELL_TOOLS = {"Bash", "shell", "local_shell", "exec_command", "functions.shell"}
_REPO_ROOT = Path(__file__).resolve().parents[4]
_API_DIR = _REPO_ROOT / "api"

_SOURCE_PATH_RE = re.compile(
    r"(?:[~./A-Za-z0-9_-][~./A-Za-z0-9_-]*"
    r"(?:\.kt|\.kts|\.py|\.yaml|\.api))"
)
_INTERPRETER_RE = re.compile(
    r"(?:\bpython3?\b|\bsh\b|\bbash\b|\bdash\b|\bzsh\b|\bperl\b|\bruby\b)"
    r".*(?:<<|-c\b)",
    re.DOTALL,
)
_RAW_WRITE_MARKER_RE = re.compile(
    r"open\s*\([^)]*['\"]w['\"]|write_text\s*\(|\.write\s*\(|"
    r"\bos\.rename\b|\bshutil\.(?:copy|move)\b|"
    r"(?:^|[\s;])tee\b|(?:^|[\s;])(?:sed|perl)\b[^\n;]*\s-i|>",
    re.DOTALL,
)


def applies(data: dict[str, Any]) -> bool:
    tool = data.get("tool_name")
    if tool in SHELL_TOOLS:
        return bool(_command_text(data.get("tool_input") or {}))
    return tool in WRITE_TOOLS and any(
        _is_api_dump(_resolve_path(raw, _event_cwd(data)))
        for raw in _raw_file_paths(data.get("tool_input"))
    )


def run(data: dict[str, Any]) -> HookResult | None:
    tool = data.get("tool_name")
    if tool in WRITE_TOOLS:
        target = next(
            (
                _resolve_path(raw, _event_cwd(data))
                for raw in _raw_file_paths(data.get("tool_input"))
                if _is_api_dump(_resolve_path(raw, _event_cwd(data)))
            ),
            None,
        )
        if target is not None:
            return _block_api_dump(target)
        return None

    command = _command_text(data.get("tool_input") or {})
    if not command:
        return None

    if _raw_interpreter_write_targets(command, _event_cwd(data)):
        return _block_source_write("inline interpreter or shell heredoc")

    segments, parse_failed = _command_segments(command)
    if parse_failed:
        if _raw_write_targets(command, _event_cwd(data)):
            return _block_source_write("unparseable shell write")
        return None

    segments, nested_failed = _expand_nested_shell_segments(segments, data)
    if nested_failed and _raw_write_targets(command, _event_cwd(data)):
        return _block_source_write("unparseable nested shell write")

    for tokens in (segment.tokens for segment in segments):
        if _is_allowed_gradle_update_abi(tokens):
            continue
        if _is_allowed_ast_grep_rewrite(tokens):
            continue

        api_target = _first_target(tokens, api_only=True, cwd=_event_cwd(data))
        if api_target is not None:
            return _block_api_dump(api_target)

        source_target = _first_target(tokens, api_only=False, cwd=_event_cwd(data))
        if source_target is not None:
            return _block_source_write(_write_shape(tokens))

    return None


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


def _first_target(tokens: tuple[str, ...], *, api_only: bool, cwd: Path) -> Path | None:
    index, effective_cwd = _skip_exec_prefixes(tokens, 0, cwd)
    active = tokens[index:]
    if not active:
        return None

    for raw in _redirection_targets(active):
        target = _guarded_path(raw, effective_cwd)
        if _target_matches(target, api_only=api_only):
            return target

    command = Path(active[0]).name
    if command == "tee":
        for raw in _tee_targets(active):
            target = _guarded_path(raw, effective_cwd)
            if _target_matches(target, api_only=api_only):
                return target

    if command in {"sed", "perl"} and _uses_in_place(active):
        for raw in _stream_editor_targets(active):
            target = _guarded_path(raw, effective_cwd)
            if _target_matches(target, api_only=api_only):
                return target

    return None


def _redirection_targets(tokens: tuple[str, ...]) -> Iterable[str]:
    operators = {">", ">>", ">|", "1>", "1>>", "&>", "&>>"}
    skip_next = False
    for index, token in enumerate(tokens):
        if skip_next:
            skip_next = False
            continue
        if token in operators and index + 1 < len(tokens):
            skip_next = True
            yield tokens[index + 1]
            continue
        for prefix in (">>", ">|", ">", "1>>", "1>", "&>>", "&>"):
            if token.startswith(prefix) and len(token) > len(prefix):
                yield token[len(prefix):]
                break


def _tee_targets(tokens: tuple[str, ...]) -> Iterable[str]:
    index = 1
    while index < len(tokens):
        token = tokens[index]
        if token == "--":
            yield from tokens[index + 1:]
            return
        if token in {"-a", "--append", "-i", "--ignore-interrupts"}:
            index += 1
            continue
        if token.startswith("-"):
            index += 1
            continue
        yield token
        index += 1


def _uses_in_place(tokens: tuple[str, ...]) -> bool:
    return any(
        token == "-i"
        or token.startswith("-i")
        or token == "--in-place"
        or token.startswith("--in-place=")
        for token in tokens[1:]
    )


def _stream_editor_targets(tokens: tuple[str, ...]) -> Iterable[str]:
    for token in tokens[1:]:
        if token == "--":
            continue
        if token.startswith("-"):
            continue
        if _SOURCE_PATH_RE.search(token):
            yield token


def _raw_interpreter_write_targets(command: str, cwd: Path) -> list[Path]:
    if not _INTERPRETER_RE.search(command) or not _RAW_WRITE_MARKER_RE.search(command):
        return []
    return _raw_write_targets(command, cwd)


def _raw_write_targets(command: str, cwd: Path) -> list[Path]:
    targets: list[Path] = []
    for match in _SOURCE_PATH_RE.finditer(command):
        target = _guarded_path(match.group(0), cwd)
        if _is_guarded_source(target) or _is_api_dump(target):
            targets.append(target)
    return targets


def _guarded_path(raw: str, cwd: Path) -> Path:
    cleaned = raw.strip().strip("<>(),;")
    return _resolve_path(cleaned, cwd)


def _target_matches(target: Path, *, api_only: bool) -> bool:
    if api_only:
        return _is_api_dump(target)
    return _is_guarded_source(target)


def _is_guarded_source(path: Path) -> bool:
    suffix = path.suffix.lower()
    if suffix in {".kt", ".kts"}:
        return True
    try:
        relative = path.relative_to(_REPO_ROOT)
    except ValueError:
        return False
    parts = relative.parts
    if len(parts) >= 3 and parts[:3] == (".claude", "hooks", "rules"):
        return suffix == ".yaml"
    if len(parts) >= 2 and parts[:2] == (".claude", "hooks"):
        return suffix == ".py"
    return False


def _is_api_dump(path: Path) -> bool:
    return _API_DIR in path.parents and path.name.endswith(".api")


def _is_allowed_ast_grep_rewrite(tokens: tuple[str, ...]) -> bool:
    if not tokens or Path(tokens[0]).name not in {"ast-grep", "sg"}:
        return False
    if len(tokens) < 2 or tokens[1] not in {"run", "scan"}:
        return False
    return "--rewrite" in tokens or "--update-all" in tokens


def _is_allowed_gradle_update_abi(tokens: tuple[str, ...]) -> bool:
    if not tokens or Path(tokens[0]).name not in {"gradle", "gradlew"}:
        return False
    return any(token == "updateKotlinAbi" or token.endswith(":updateKotlinAbi") for token in tokens[1:])


def _write_shape(tokens: tuple[str, ...]) -> str:
    if not tokens:
        return "shell write"
    command = Path(tokens[0]).name
    if command == "tee":
        return "tee"
    if command in {"sed", "perl"}:
        return "in-place stream editor"
    return "shell redirection"


def _block_source_write(shape: str) -> HookResult:
    return HookResult(
        kind="block",
        module_name=MODULE_NAME,
        payload=(
            "SOURCE WRITE POLICY: Bash-routed source writes are blocked.\n"
            f"Detected {shape} targeting Kotlin or hook source. Use Edit/Write "
            "so edit-time guards see the content, or use `ast-grep run --rewrite` "
            "/ `ast-grep scan --update-all` for deterministic structural rewrites."
        ),
    )


def _block_api_dump(path: Path) -> HookResult:
    return HookResult(
        kind="block",
        module_name=MODULE_NAME,
        payload=(
            f"ABI DUMP POLICY: `{_display(path)}` is generated trust output.\n"
            "Do not hand-edit API dumps or write them through shell redirection. "
            "Regenerate them with `./gradlew updateKotlinAbi`."
        ),
    )


def _display(path: Path) -> str:
    try:
        return path.relative_to(_REPO_ROOT).as_posix()
    except ValueError:
        return str(path)
