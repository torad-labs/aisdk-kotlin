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
import sys
from dataclasses import dataclass
from pathlib import Path

BUDGET_FILE = Path("data-class-budget.json")
BUDGET_KEY = "publicDataClassesCommonMain"
TRACKED_KEY = "trackedPublicDataClassesCommonMain"

# Modifiers that may sit between an (optional) annotation block and `class`.
_CLASS_DECL = re.compile(
    r"(?m)^[ \t]*"
    r"((?:@[\w.]+(?:\([^\n]*?\))?[ \t]*\n?[ \t]*)*)"   # leading annotations (best-effort)
    r"((?:(?:public|private|internal|protected|abstract|open|sealed|final|"
    r"inner|expect|actual|data|value|annotation|enum)[ \t]+)*)"
    r"class\b"
)
_CLASS_NAME = re.compile(r"\bclass\s+([A-Za-z_][A-Za-z0-9_]*)")
_NON_PUBLIC = re.compile(r"\b(private|internal|protected)\b")


@dataclass(frozen=True)
class DataClassDeclaration:
    symbol: str
    line: int


def collect_public_data_classes(root: Path) -> list[DataClassDeclaration]:
    declarations: list[tuple[Path, str, int]] = []
    for path in sorted(root.rglob("*.kt")):
        text = path.read_text(encoding="utf-8")
        for match in _CLASS_DECL.finditer(text):
            modifiers = match.group(2)
            if "data " not in modifiers:
                continue
            if _NON_PUBLIC.search(modifiers):
                continue
            name_match = _CLASS_NAME.search(text, match.start(), min(len(text), match.end() + 120))
            if name_match is None:
                continue
            declarations.append((path, name_match.group(1), text.count("\n", 0, match.start()) + 1))

    occurrence: dict[tuple[str, str], int] = {}
    tracked: list[DataClassDeclaration] = []
    for path, name, line in declarations:
        relative = path.relative_to(root).as_posix()
        key = (relative, name)
        occurrence[key] = occurrence.get(key, 0) + 1
        tracked.append(DataClassDeclaration(symbol=f"{relative}:{name}#{occurrence[key]}", line=line))
    return tracked


def count_public_data_classes(root: Path) -> int:
    return len(collect_public_data_classes(root))


def read_budget() -> dict[str, object]:
    if not BUDGET_FILE.exists():
        return {}
    return json.loads(BUDGET_FILE.read_text())


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
    args = parser.parse_args()

    declarations = collect_public_data_classes(Path(args.root))
    count = len(declarations)
    current_symbols = {declaration.symbol for declaration in declarations}

    if args.update:
        prev = budget_count(read_budget())
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
        BUDGET_FILE.write_text(json.dumps(payload, indent=2) + "\n")
        print(f"data-class budget set to {count}; tracked {len(current_symbols)} declarations")
        return 0

    budget_data = read_budget()
    budget = budget_count(budget_data)
    if budget < 0:
        print("data-class budget gate: missing data-class-budget.json (run with --update once to seed)")
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
