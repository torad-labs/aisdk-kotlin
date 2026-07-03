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

Known limits: command substitution such as `$(git push ...)` and Git alias
indirection such as `git -c alias.p=push p` remain expressible through token-level
guarding. The review protocol and branch protection remain the structural
backstops for those dynamic shell/Git behaviors.
"""
from __future__ import annotations

from dataclasses import dataclass
import re
import shlex
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

READ_ONLY_COMMANDS = {
    "cat",
    "less",
    "head",
    "tail",
    "grep",
    "rg",
    "diff",
    "wc",
    "md5sum",
    "sha256sum",
}
READ_ONLY_GIT_SUBCOMMANDS = {"diff", "log", "show", "status"}
BROAD_ADD_ARGS = {".", "-A", "--all", "-u", "--update"}
MESSAGE_OPTIONS = {"-m", "--message", "-F"}
SHELL_COMMANDS = {"bash", "dash", "sh", "zsh"}

_REPO_ROOT = Path(__file__).resolve().parents[4]


@dataclass(frozen=True)
class CommandSegment:
    tokens: tuple[str, ...]


@dataclass(frozen=True)
class GitCommand:
    tokens: tuple[str, ...]
    subcommand: str
    args: tuple[str, ...]
    cwd: Path
    head_args: tuple[str, ...]


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
        owned = next(
            (_owned_match(path) for path in _patch_target_paths(patch_text) if _owned_match(path)),
            None,
        )
        if owned:
            return _block(
                f"FLEET PROTOCOL: this patch touches orchestrator-owned `{owned}`.\n"
                "Remove it from the patch; report via sendMessage instead."
            )
        return None

    command = _command_text(tool_input)
    if not command:
        return None

    segments, parse_failed = _command_segments(command)
    if parse_failed:
        if role == "builder":
            return _block(
                "FLEET PROTOCOL: could not parse this builder shell command "
                "safely. Use a simpler command shape or split it into explicit "
                "tool calls so the fleet guards can inspect it."
            )
        return None

    segments, parse_failed = _expand_nested_shell_segments(segments, data)
    if parse_failed and role == "builder":
        return _block(
            "FLEET PROTOCOL: could not parse this nested builder shell command "
            "safely. Use a simpler command shape or split it into explicit "
            "tool calls so the fleet guards can inspect it."
        )

    git_commands = [
        git
        for segment in segments
        if (git := _git_command(segment.tokens, data)) is not None
    ]

    # Role-agnostic history/gate safety (protects the orchestrator too).
    for git in git_commands:
        args = _args_without_message(git.args)
        if git.subcommand in {"commit", "push"} and _has_no_verify(args):
            return _block(
                "GATE POLICY: `--no-verify` is never allowed (CLAUDE.md working "
                "agreement). Fix the failing gate; do not bypass it."
            )
        if git.subcommand == "push" and _forbidden_force_push(args):
            return _block(
                "HISTORY SAFETY: bare `git push --force` or `+refspec` is not "
                "allowed. Use `--force-with-lease=<branch>:<expected-sha>` after "
                "verifying the remote tip, so a concurrent push cannot be clobbered."
            )
        if git.subcommand == "commit" and "--amend" in args and _head_is_published(git):
            return _block(
                "HISTORY SAFETY: HEAD is already on origin — amending it forks "
                "published history (this raced once on 2026-07-02). Make a "
                "follow-up commit instead, or coordinate a lease-guarded rewrite "
                "with the orchestrator."
            )

    if role != "builder":
        return None

    for segment in segments:
        owned = _owned_token_in_segment(segment, data)
        if owned and not _is_read_only_segment(segment, data):
            return _block(
                f"FLEET PROTOCOL: `{owned}` is orchestrator-owned. The builder "
                "may read it, but must not write, stage, restore, or commit it. "
                "Report status via sendMessage instead."
            )

    for git in git_commands:
        if git.subcommand == "push":
            return _block(
                "FLEET PROTOCOL: the builder never pushes. Commit locally and "
                "report the SHA via sendMessage to aisdk-orchestrator; the "
                "orchestrator reviews, then pushes."
            )
        if git.subcommand == "add" and _broad_add(git):
            return _block(
                "FLEET PROTOCOL: builder staging must be explicit-file staging. "
                "`git add .`, `git add -A/--all`, `git add -u/--update`, and "
                "directory operands are blocked."
            )
        if git.subcommand == "commit" and _commit_uses_all(git.args):
            return _block(
                "FLEET PROTOCOL: `git commit -a/-am` is blocked for builders. "
                "Stage explicit files first so orchestrator-owned ledgers cannot "
                "be swept in."
            )

    return None


def _patch_target_paths(patch_text: str) -> list[str]:
    targets: list[str] = []
    for line in patch_text.splitlines():
        if line.startswith("*** Add File: "):
            targets.append(line.split(": ", 1)[1])
        elif line.startswith("*** Update File: "):
            targets.append(line.split(": ", 1)[1])
        elif line.startswith("*** Delete File: "):
            targets.append(line.split(": ", 1)[1])
        elif line.startswith("*** Move to: "):
            targets.append(line.split(": ", 1)[1])
    return targets


def _command_segments(command: str) -> tuple[list[CommandSegment], bool]:
    stripped = _strip_heredoc_bodies(command)
    raw_segments, split_ok = _split_command_segments(stripped)
    if not split_ok:
        return ([], True)
    segments: list[CommandSegment] = []
    for raw in raw_segments:
        if not raw.strip():
            continue
        try:
            tokens = tuple(shlex.split(raw, posix=True))
        except ValueError:
            return ([], True)
        if tokens:
            segments.append(CommandSegment(tokens=tokens))
    return (segments, False)


def _expand_nested_shell_segments(
    segments: list[CommandSegment],
    data: dict,
    depth: int = 0,
) -> tuple[list[CommandSegment], bool]:
    if depth >= 4:
        return (segments, False)
    expanded: list[CommandSegment] = []
    parse_failed = False
    for segment in segments:
        expanded.append(segment)
        inner = _shell_command_operand(segment.tokens, data)
        if inner is None:
            continue
        inner_segments, inner_failed = _command_segments(inner)
        if inner_failed:
            parse_failed = True
            continue
        nested, nested_failed = _expand_nested_shell_segments(
            inner_segments,
            data,
            depth + 1,
        )
        parse_failed = parse_failed or nested_failed
        expanded.extend(nested)
    return (expanded, parse_failed)


_HEREDOC_RE = re.compile(r"<<-?\s*(?:'([^']+)'|\"([^\"]+)\"|([A-Za-z_][A-Za-z0-9_]*))")


def _strip_heredoc_bodies(text: str) -> str:
    lines = text.splitlines(keepends=True)
    output: list[str] = []
    pending: list[tuple[str, bool]] = []
    for line in lines:
        if pending:
            delimiter, allow_tabs = pending[0]
            candidate = line.rstrip("\r\n")
            if allow_tabs:
                candidate = candidate.lstrip("\t")
            if candidate == delimiter:
                pending.pop(0)
            continue

        output.append(line)
        for match in _HEREDOC_RE.finditer(line):
            delimiter = next(group for group in match.groups() if group is not None)
            pending.append((delimiter, match.group(0).startswith("<<-")))
    return "".join(output)


def _split_command_segments(text: str) -> tuple[list[str], bool]:
    segments: list[str] = []
    current: list[str] = []
    quote: str | None = None
    escaped = False
    index = 0
    while index < len(text):
        char = text[index]
        if escaped:
            current.append(char)
            escaped = False
            index += 1
            continue
        if char == "\\" and quote != "'":
            current.append(char)
            escaped = True
            index += 1
            continue
        if quote:
            current.append(char)
            if char == quote:
                quote = None
            index += 1
            continue
        if char in {"'", '"'}:
            quote = char
            current.append(char)
            index += 1
            continue
        if char == ";":
            segments.append("".join(current))
            current = []
            index += 1
            continue
        if char == "&" and index + 1 < len(text) and text[index + 1] == "&":
            segments.append("".join(current))
            current = []
            index += 2
            continue
        if char == "|":
            segments.append("".join(current))
            current = []
            index += 2 if index + 1 < len(text) and text[index + 1] == "|" else 1
            continue
        current.append(char)
        index += 1
    if quote or escaped:
        return ([], False)
    segments.append("".join(current))
    return (segments, True)


def _git_command(tokens: tuple[str, ...], data: dict) -> GitCommand | None:
    index, cwd = _skip_exec_prefixes(tokens, 0, _event_cwd(data))
    if index >= len(tokens) or Path(tokens[index]).name != "git":
        return None

    head_args: list[str] = []
    index += 1
    while index < len(tokens):
        token = tokens[index]
        if token == "-C" and index + 1 < len(tokens):
            cwd = _resolve_path(tokens[index + 1], cwd)
            index += 2
            continue
        if token.startswith("-C") and len(token) > 2:
            cwd = _resolve_path(token[2:], cwd)
            index += 1
            continue
        if token == "-c" and index + 1 < len(tokens):
            head_args.extend([token, tokens[index + 1]])
            index += 2
            continue
        if token.startswith("--git-dir=") or token.startswith("--work-tree="):
            head_args.append(token)
            index += 1
            continue
        if token in {"--git-dir", "--work-tree"} and index + 1 < len(tokens):
            head_args.extend([token, tokens[index + 1]])
            index += 2
            continue
        if token in {"--no-pager", "--bare"}:
            head_args.append(token)
            index += 1
            continue
        if token.startswith("-"):
            index += 1
            continue
        return GitCommand(
            tokens=tokens,
            subcommand=token,
            args=tuple(tokens[index + 1:]),
            cwd=cwd,
            head_args=tuple(head_args),
        )
    return None


def _skip_exec_prefixes(tokens: tuple[str, ...], start: int, cwd: Path) -> tuple[int, Path]:
    index = start
    while True:
        while index < len(tokens) and _is_env_assignment(tokens[index]):
            index += 1
        if index >= len(tokens):
            return (index, cwd)

        command = Path(tokens[index]).name
        if command == "nohup":
            index += 1
            continue
        if command == "env":
            index, cwd = _skip_env_prefix(tokens, index + 1, cwd)
            continue
        if command == "nice":
            index = _skip_nice_prefix(tokens, index + 1)
            continue
        if command == "time":
            index = _skip_time_prefix(tokens, index + 1)
            continue
        if command == "stdbuf":
            index = _skip_stdbuf_prefix(tokens, index + 1)
            continue
        if command == "timeout":
            index = _skip_timeout_prefix(tokens, index + 1)
            continue
        return (index, cwd)


def _skip_env_prefix(tokens: tuple[str, ...], index: int, cwd: Path) -> tuple[int, Path]:
    while index < len(tokens):
        token = tokens[index]
        if _is_env_assignment(token) or token == "-":
            index += 1
            continue
        if token in {"-i", "--ignore-environment", "-0", "--null"}:
            index += 1
            continue
        if token in {"-u", "--unset", "-S", "--split-string"} and index + 1 < len(tokens):
            index += 2
            continue
        if token in {"-C", "--chdir"} and index + 1 < len(tokens):
            cwd = _resolve_path(tokens[index + 1], cwd)
            index += 2
            continue
        if token.startswith("--chdir="):
            cwd = _resolve_path(token.split("=", 1)[1], cwd)
            index += 1
            continue
        if token.startswith("--unset=") or token.startswith("--split-string="):
            index += 1
            continue
        if token.startswith("-u") and token != "-u":
            index += 1
            continue
        if token.startswith("-S") and token != "-S":
            index += 1
            continue
        if token.startswith("-"):
            index += 1
            continue
        break
    return (index, cwd)


def _skip_nice_prefix(tokens: tuple[str, ...], index: int) -> int:
    while index < len(tokens):
        token = tokens[index]
        if token == "-n" and index + 1 < len(tokens):
            index += 2
            continue
        if token == "--adjustment" and index + 1 < len(tokens):
            index += 2
            continue
        if token.startswith("-n") and token != "-n":
            index += 1
            continue
        if token.startswith("--adjustment="):
            index += 1
            continue
        if token.startswith("-"):
            index += 1
            continue
        break
    return index


def _skip_time_prefix(tokens: tuple[str, ...], index: int) -> int:
    while index < len(tokens) and tokens[index].startswith("-"):
        if tokens[index] in {"-f", "-o", "--format", "--output"} and index + 1 < len(tokens):
            index += 2
        else:
            index += 1
    return index


def _skip_stdbuf_prefix(tokens: tuple[str, ...], index: int) -> int:
    while index < len(tokens) and tokens[index].startswith("-"):
        if tokens[index] in {"-e", "-i", "-o"} and index + 1 < len(tokens):
            index += 2
        else:
            index += 1
    return index


def _skip_timeout_prefix(tokens: tuple[str, ...], index: int) -> int:
    while index < len(tokens) and tokens[index].startswith("-"):
        if tokens[index] in {"-k", "-s", "--kill-after", "--signal"} and index + 1 < len(tokens):
            index += 2
        else:
            index += 1
    if index < len(tokens):
        index += 1
    return index


def _shell_command_operand(tokens: tuple[str, ...], data: dict) -> str | None:
    index, _ = _skip_exec_prefixes(tokens, 0, _event_cwd(data))
    if index >= len(tokens) or Path(tokens[index]).name not in SHELL_COMMANDS:
        return None
    index += 1
    while index < len(tokens):
        token = tokens[index]
        if token == "--":
            index += 1
            continue
        if token == "-c" or (token.startswith("-") and "c" in token[1:]):
            return tokens[index + 1] if index + 1 < len(tokens) else None
        if token.startswith("-"):
            index += 1
            continue
        return None
    return None


def _event_cwd(data: dict) -> Path:
    return Path(str(data.get("cwd") or _REPO_ROOT)).resolve(strict=False)


def _resolve_path(raw: str, base: Path) -> Path:
    path = Path(raw)
    if path.is_absolute():
        return path.resolve(strict=False)
    return (base / path).resolve(strict=False)


def _is_env_assignment(token: str) -> bool:
    return bool(re.match(r"^[A-Za-z_][A-Za-z0-9_]*=", token))


def _args_without_message(args: tuple[str, ...]) -> tuple[str, ...]:
    kept: list[str] = []
    skip_next = False
    for token in args:
        if skip_next:
            skip_next = False
            continue
        if token in MESSAGE_OPTIONS:
            skip_next = True
            continue
        if token.startswith("--message=") or token.startswith("--file="):
            continue
        if token.startswith("-") and not token.startswith("--"):
            short_options = token[1:]
            if "m" in short_options or "F" in short_options:
                skip_next = short_options.endswith(("m", "F"))
                continue
        if token.startswith("-m") and token != "-m":
            continue
        if token.startswith("-F") and token != "-F":
            continue
        kept.append(token)
    return tuple(kept)


def _has_no_verify(args: tuple[str, ...]) -> bool:
    return "--no-verify" in args or "-n" in args


def _forbidden_force_push(args: tuple[str, ...]) -> bool:
    if any(arg.startswith("+") for arg in args):
        return True
    has_lease = any(
        arg == "--force-with-lease" or arg.startswith("--force-with-lease=")
        for arg in args
    )
    if has_lease:
        return False
    return any(arg in {"--force", "-f"} for arg in args)


def _owned_token_in_segment(segment: CommandSegment, data: dict) -> str | None:
    git = _git_command(segment.tokens, data)
    tokens = segment.tokens
    if git is not None:
        tokens = ("git", git.subcommand, *_args_without_message(git.args))
    for token in tokens:
        owned = _owned_token_match(token)
        if owned:
            return owned
    return None


def _owned_token_match(token: str) -> str | None:
    candidates = [token, token.lstrip("<>")]
    if "=" in token:
        candidates.append(token.split("=", 1)[1].lstrip("<>"))
    for candidate in candidates:
        cleaned = candidate.strip().rstrip(",:)")
        owned = _owned_match(cleaned)
        if owned:
            return owned
    return None


def _is_read_only_segment(segment: CommandSegment, data: dict) -> bool:
    command = Path(segment.tokens[0]).name
    if command in READ_ONLY_COMMANDS:
        return True
    git = _git_command(segment.tokens, data)
    return git is not None and git.subcommand in READ_ONLY_GIT_SUBCOMMANDS


def _broad_add(git: GitCommand) -> bool:
    args = _args_without_message(git.args)
    for arg in args:
        if arg == "--":
            continue
        if arg in BROAD_ADD_ARGS:
            return True
        if arg.startswith("-"):
            continue
        if _is_directory_operand(arg, git.cwd):
            return True
    return False


def _is_directory_operand(arg: str, cwd: Path) -> bool:
    if arg.endswith("/"):
        return True
    if any(char in arg for char in "*?["):
        return False
    path = Path(arg)
    candidate = path if path.is_absolute() else cwd / path
    return candidate.is_dir()


def _commit_uses_all(args: tuple[str, ...]) -> bool:
    skip_next = False
    for arg in args:
        if skip_next:
            skip_next = False
            continue
        if arg == "--":
            break
        if arg in MESSAGE_OPTIONS:
            skip_next = True
            continue
        if arg.startswith("--message=") or arg.startswith("--file="):
            continue
        if arg in {"-a", "--all"}:
            return True
        if arg.startswith("-") and not arg.startswith("--") and "a" in arg[1:]:
            return True
        if arg.startswith("-") and not arg.startswith("--"):
            short_options = arg[1:]
            if "m" in short_options or "F" in short_options:
                skip_next = short_options.endswith(("m", "F"))
    return False


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
        parts = [str(part) for part in raw]
        if parts and Path(parts[0]).name in {"bash", "sh", "zsh"}:
            for index, part in enumerate(parts[:-1]):
                if "c" in part.lstrip("-") and part.startswith("-"):
                    return parts[index + 1]
        return " ".join(shlex.quote(part) for part in parts)
    return str(raw)


def _head_is_published(git: GitCommand) -> bool:
    try:
        result = subprocess.run(
            ["git", *git.head_args, "branch", "-r", "--contains", "HEAD"],
            cwd=str(git.cwd),
            capture_output=True,
            text=True,
            timeout=3,
        )
    except (subprocess.TimeoutExpired, OSError):
        return False  # heuristic guard only; --force-with-lease is the backstop
    return result.returncode == 0 and bool(result.stdout.strip())


def _block(message: str) -> HookResult:
    return HookResult(kind="block", payload=message, module_name=MODULE_NAME)
