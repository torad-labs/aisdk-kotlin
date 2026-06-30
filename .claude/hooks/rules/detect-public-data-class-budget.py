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
from pathlib import Path

BUDGET_FILE = Path("data-class-budget.json")
BUDGET_KEY = "publicDataClassesCommonMain"

# Modifiers that may sit between an (optional) annotation block and `class`.
_CLASS_DECL = re.compile(
    r"(?m)^[ \t]*"
    r"((?:@[\w.]+(?:\([^\n]*?\))?[ \t]*\n?[ \t]*)*)"   # leading annotations (best-effort)
    r"((?:(?:public|private|internal|protected|abstract|open|sealed|final|"
    r"inner|expect|actual|data|value|annotation|enum)[ \t]+)*)"
    r"class\b"
)
_NON_PUBLIC = re.compile(r"\b(private|internal|protected)\b")


def count_public_data_classes(root: Path) -> int:
    total = 0
    for path in sorted(root.rglob("*.kt")):
        text = path.read_text(encoding="utf-8")
        for match in _CLASS_DECL.finditer(text):
            modifiers = match.group(2)
            if "data " not in modifiers:
                continue
            if _NON_PUBLIC.search(modifiers):
                continue
            total += 1
    return total


def read_budget() -> int:
    if not BUDGET_FILE.exists():
        return -1
    return int(json.loads(BUDGET_FILE.read_text()).get(BUDGET_KEY, -1))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", help="source root to scan (e.g. src/commonMain/kotlin)")
    parser.add_argument("--check", action="store_true", help="fail if the count exceeds the budget")
    parser.add_argument("--update", action="store_true", help="re-seed the budget to the current count (downward only)")
    args = parser.parse_args()

    count = count_public_data_classes(Path(args.root))

    if args.update:
        prev = read_budget()
        if prev >= 0 and count > prev:
            print(
                f"refusing to RAISE the data-class budget ({prev} -> {count}); "
                "the ratchet only moves down. Demote a public data class to @Poko instead."
            )
            return 1
        BUDGET_FILE.write_text(json.dumps({BUDGET_KEY: count}, indent=2) + "\n")
        print(f"data-class budget set to {count}")
        return 0

    budget = read_budget()
    if budget < 0:
        print("data-class budget gate: missing data-class-budget.json (run with --update once to seed)")
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
    print(f"data-class budget gate OK: {count} public data class (budget {budget})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
