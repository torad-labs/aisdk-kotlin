#!/usr/bin/env python3
"""Public `data class` budget gate.

A public Kotlin `data class` is an ABI-evolvability trap: adding a constructor
field is a BINARY-INCOMPATIBLE change (the synthesized constructor AND `copy()`
signatures change; `@JvmOverloads` / `@ConsistentCopyVisibility` do NOT rescue
it). For a library that runs `explicitApi()` + committed ABI dumps, every new
public `data class` is a future binary break waiting to happen.

Project policy (see CLAUDE.md "Public value types"): model growable read-only
result/response/metadata types as `@Poko class` (value semantics WITHOUT
`copy()`/`componentN()`), and construct-types via builders. Keep `data class`
ONLY for genuinely-frozen small value types.

This gate is a RATCHET: it counts public `data class` declarations in
commonMain and fails if the count rises above the recorded budget. It never
blocks the existing grandfathered set — it just stops the count from growing and
ratchets down as types are demoted to @Poko. Re-seed the budget downward (only
downward) with `--update` after a demotion.
"""
from __future__ import annotations

import argparse
import json
import re
import shutil
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[3]
DEFAULT_BUDGET_FILE = REPO_ROOT / "data-class-budget.json"
DEFAULT_RULE_FILE = Path(__file__).resolve().parent / "public-abi-data-class.yaml"
BUDGET_KEY = "publicDataClassesCommonMain"
TRACKED_KEY = "trackedPublicDataClassesCommonMain"

_CLASS_NAME = re.compile(r"\bdata\s+class\s+([A-Za-z_][A-Za-z0-9_]*)")


@dataclass(frozen=True)
class DataClassDeclaration:
    symbol: str
    line: int


def ast_grep_binary() -> str | None:
    candidates = [REPO_ROOT / "node_modules" / ".bin" / "ast-grep"]
    path_candidate = shutil.which("ast-grep")
    if path_candidate:
        candidates.append(Path(path_candidate))
    candidates.append(Path.home() / ".local" / "bin" / "ast-grep")
    for candidate in candidates:
        if candidate.is_file() and candidate.stat().st_mode & 0o111:
            return str(candidate)
    return None


def collect_public_data_classes(
    root: Path,
    *,
    rule_file: Path = DEFAULT_RULE_FILE,
    binary: str | None = None,
) -> list[DataClassDeclaration]:
    binary = binary or ast_grep_binary()
    if binary is None:
        raise RuntimeError("ast-grep not found")
    if not rule_file.is_file():
        raise RuntimeError(f"measurement rule not found: {rule_file}")

    root = root.resolve(strict=False)
    cp = subprocess.run(
        [binary, "scan", "--rule", str(rule_file), str(root), "--json=compact"],
        capture_output=True,
        text=True,
    )
    if cp.returncode not in (0, 1):
        stderr = (cp.stderr or "").strip()
        raise RuntimeError(f"ast-grep scan failed with exit {cp.returncode}: {stderr}")
    try:
        matches = json.loads((cp.stdout or "").strip() or "[]")
    except json.JSONDecodeError as exc:
        raise RuntimeError(f"ast-grep returned invalid JSON: {exc}") from exc
    if not isinstance(matches, list):
        raise RuntimeError("ast-grep returned a non-list JSON payload")

    declarations: list[tuple[Path, str, int]] = []
    for match in matches:
        if not isinstance(match, dict):
            continue
        text = str(match.get("text") or "")
        name_match = _CLASS_NAME.search(text)
        if name_match is None:
            raise RuntimeError("ast-grep matched a data class but class name could not be read")
        line = int(((match.get("range") or {}).get("start") or {}).get("line", 0)) + 1
        declarations.append((_relative_match_path(root, str(match.get("file") or "")), name_match.group(1), line))

    declarations.sort(key=lambda item: (item[0].as_posix(), item[2], item[1]))
    occurrence: dict[tuple[str, str], int] = {}
    tracked: list[DataClassDeclaration] = []
    for path, name, line in declarations:
        relative = path.as_posix()
        key = (relative, name)
        occurrence[key] = occurrence.get(key, 0) + 1
        tracked.append(DataClassDeclaration(symbol=f"{relative}:{name}#{occurrence[key]}", line=line))
    return tracked


def _relative_match_path(root: Path, raw_file: str) -> Path:
    path = Path(raw_file)
    absolute = path if path.is_absolute() else (Path.cwd() / path)
    absolute = absolute.resolve(strict=False)
    try:
        return absolute.relative_to(root)
    except ValueError:
        return Path(raw_file)


def count_public_data_classes(root: Path, *, rule_file: Path = DEFAULT_RULE_FILE) -> int:
    return len(collect_public_data_classes(root, rule_file=rule_file))


def read_budget(budget_file: Path) -> dict[str, object]:
    if not budget_file.exists():
        return {}
    return json.loads(budget_file.read_text())


def budget_count(budget: dict[str, object]) -> int:
    return int(budget.get(BUDGET_KEY, -1))


def tracked_symbols(budget: dict[str, object]) -> set[str] | None:
    tracked = budget.get(TRACKED_KEY)
    if tracked is None:
        return None
    if not isinstance(tracked, list) or not all(isinstance(item, str) for item in tracked):
        raise ValueError(f"{TRACKED_KEY} must be a list of strings")
    return set(tracked)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", help="source root to scan (e.g. src/commonMain/kotlin)")
    parser.add_argument("--check", action="store_true", help="fail if the count exceeds the budget")
    parser.add_argument("--update", action="store_true", help="re-seed the budget to the current count (downward only)")
    parser.add_argument(
        "--budget",
        default=str(DEFAULT_BUDGET_FILE),
        help="budget JSON path (default: repo-root data-class-budget.json)",
    )
    parser.add_argument(
        "--rule",
        default=str(DEFAULT_RULE_FILE),
        help="ast-grep measurement rule path (default: public-abi-data-class.yaml beside this script)",
    )
    args = parser.parse_args()

    try:
        declarations = collect_public_data_classes(Path(args.root), rule_file=Path(args.rule).resolve(strict=False))
    except RuntimeError as exc:
        print(f"data-class budget gate: {exc}")
        return 2
    count = len(declarations)
    current_symbols = {declaration.symbol for declaration in declarations}
    budget_file = Path(args.budget).resolve(strict=False)

    if args.update:
        prev = budget_count(read_budget(budget_file))
        if prev >= 0 and count > prev:
            print(
                f"refusing to RAISE the data-class budget ({prev} -> {count}); "
                "the ratchet only moves down. Demote a public data class to @Poko instead."
            )
            return 1
        payload = {
            BUDGET_KEY: count,
            TRACKED_KEY: sorted(current_symbols),
        }
        budget_file.write_text(json.dumps(payload, indent=2) + "\n")
        print(f"data-class budget set to {count}; tracked {len(current_symbols)} declarations")
        return 0

    budget_data = read_budget(budget_file)
    budget = budget_count(budget_data)
    if budget < 0:
        print(f"data-class budget gate: missing {budget_file} (run with --update once to seed)")
        return 1
    try:
        tracked = tracked_symbols(budget_data)
    except ValueError as exc:
        print(f"data-class budget gate: invalid data-class-budget.json: {exc}")
        return 1
    if tracked is None:
        print(
            f"data-class budget gate: missing {TRACKED_KEY} in data-class-budget.json "
            "(run `python3 .claude/hooks/rules/detect-public-data-class-budget.py "
            "src/commonMain/kotlin --update` to seed the tracked declaration set)"
        )
        return 1
    if args.check and count > budget:
        print(
            f"PUBLIC DATA-CLASS BUDGET EXCEEDED: {count} public `data class` in commonMain, budget is {budget}.\n"
            "  You added a public `data class`. Public data classes break binary compatibility when a\n"
            "  field is added later (constructor + copy() signatures change). Project policy: use\n"
            "  `@Poko class` for growable read-only result/response/metadata types (value semantics\n"
            "  without copy()/componentN()), or a builder for construct-types. Keep `data class` ONLY\n"
            "  for genuinely-frozen small value types — and if so, demote a different one so the budget\n"
            "  does not rise. See CLAUDE.md (\"Public value types\")."
        )
        return 1
    new_symbols = sorted(current_symbols - tracked)
    if args.check and new_symbols:
        print(
            "NEW PUBLIC DATA-CLASS DECLARATION(S) REQUIRE ACKNOWLEDGMENT:\n"
            + "\n".join(f"  - {symbol}" for symbol in new_symbols)
            + "\nRun `python3 .claude/hooks/rules/detect-public-data-class-budget.py "
            "src/commonMain/kotlin --update` in the same commit if this public data class is intentional. "
            "The count ratchet still refuses upward movement."
        )
        return 1
    print(
        f"data-class budget gate OK: {count} public data class "
        f"(budget {budget}, tracked {len(tracked)} declarations)"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
