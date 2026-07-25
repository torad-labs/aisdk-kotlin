#!/usr/bin/env python3
"""Move a leading `import kotlin.jvm.JvmSynthetic` into its sorted position.

The 0a3bba1 codemod added @JvmSynthetic across commonMain and inserted its import
directly under the package declaration, separated by a blank line. ktlint's
ImportOrdering requires one contiguous block in lexicographic order with `kotlin`
imports last, so 17 files report a violation for an import that is otherwise correct.

This is a pure move: delete the leading occurrence and its trailing blank line, then
re-insert after the final import. It is deliberately conservative — a file is skipped
unless the import is the FIRST import and appears exactly once, so a correctly-placed
file cannot be disturbed.

Run:  python3 dev/codemods/fix_jvmsynthetic_import_order.py src/commonMain [--check]
"""
from __future__ import annotations

import sys
from pathlib import Path

IMPORT = "import kotlin.jvm.JvmSynthetic"


def fix(path: Path) -> bool:
    """Return True when the file needed (or would need) a change."""
    lines = path.read_text(encoding="utf-8").split("\n")
    import_idx = [i for i, line in enumerate(lines) if line.startswith("import ")]
    if not import_idx:
        return False

    first = import_idx[0]
    if lines[first] != IMPORT:
        return False  # not the misplaced shape
    if sum(1 for i in import_idx if lines[i] == IMPORT) != 1:
        return False  # duplicated: leave it for a human

    rest = [i for i in import_idx[1:]]
    if not rest:
        return False  # sole import; nothing to reorder against

    # Drop the leading import plus one following blank line, if present.
    end = first + 1
    if end < len(lines) and lines[end].strip() == "":
        end += 1
    trimmed = lines[:first] + lines[end:]

    # Re-insert after the last remaining import so `kotlin.*` sorts last.
    last_import = max(i for i, line in enumerate(trimmed) if line.startswith("import "))
    trimmed.insert(last_import + 1, IMPORT)

    path.write_text("\n".join(trimmed), encoding="utf-8")
    return True


def main() -> int:
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    check_only = "--check" in sys.argv
    roots = [Path(a) for a in args] or [Path("src/commonMain")]

    changed: list[Path] = []
    for root in roots:
        for path in sorted(root.rglob("*.kt")):
            before = path.read_text(encoding="utf-8")
            if fix(path):
                changed.append(path)
                if check_only:
                    path.write_text(before, encoding="utf-8")

    if not changed:
        print("jvmsynthetic import order OK: no fixes needed")
        return 0

    verb = "would move" if check_only else "moved"
    print(f"Applying {len(changed)} fix(es) — {verb} {IMPORT} into sorted position:")
    for path in changed:
        print(f"  {path}")
    return 1 if check_only else 0


if __name__ == "__main__":
    raise SystemExit(main())
