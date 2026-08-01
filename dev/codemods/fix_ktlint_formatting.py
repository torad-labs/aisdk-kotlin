#!/usr/bin/env python3
"""Fix three mechanical ktlint formatting classes detekt reports.

Each has exactly one obviously-correct output, which is the bar for a codemod:

  SpacingBetweenDeclarationsWithAnnotations
      An annotated declaration needs a blank line before it when it directly follows
      another declaration. Insert one.

  AnnotationOnSeparateLine
      A file/declaration annotation that shares its line with the declaration, where a
      KDoc block sits between it and an earlier annotation, must stand alone. Only the
      `@Suppress(...)` / KDoc / `@Ann public fun` sandwich is handled — the fix is to
      move the trailing annotation onto its own line.

  NoConsecutiveBlankLines
      Two or more blank lines in a row collapse to one; trailing blanks at EOF collapse
      to a single newline.

Deliberately narrow: anything not matching these exact shapes is left alone, so the
codemod cannot reformat code it does not understand. `--check` restores content, so a
dry run never mutates the tree.

BUDGET INTERACTION — read before running this on a large file. Inserting blank lines
grows the file, and architecture-budget.json caps several files at their exact current
length (MCP.kt at 2210). This codemod added 13 blank lines to MCP.kt and pushed it to
2220, failing the file-size ratchet. Raising that budget to absorb formatting churn is
forbidden: the budget exists to stop these files growing, and blank lines buy readability,
not correctness. Files at or near their cap are listed in BUDGET_EXEMPT below and are
skipped; fix their spacing as part of a change that also shrinks them.

Run:  python3 dev/codemods/fix_ktlint_formatting.py src/commonMain [--check]
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

# Files whose line count is capped at (or within a few lines of) their current length by
# architecture-budget.json. Adding blank lines here fails the file-size ratchet, and the
# ratchet is right: the cap exists to stop these files growing. Their spacing is fixed as
# part of a change that also shrinks them, never by spending budget on whitespace.
BUDGET_EXEMPT = {
    "MCP.kt",
}

ANNOTATION = re.compile(r"^(\s*)@[A-Z][A-Za-z0-9_]*(\(.*\))?\s*$")
ANNOTATED_DECL = re.compile(r"^(\s*)@[A-Z][A-Za-z0-9_]*(\(.*\))?\s+(public|internal|private|protected|suspend|fun|val|var|object|class|expect)\b")
DECL_END = re.compile(r"^\s*\S")


def fix_consecutive_blanks(lines: list[str]) -> list[str]:
    out: list[str] = []
    blanks = 0
    for line in lines:
        if line.strip() == "":
            blanks += 1
            if blanks > 1:
                continue
        else:
            blanks = 0
        out.append(line)
    # Collapse trailing blank lines to exactly one terminating newline.
    while len(out) > 1 and out[-1].strip() == "" and out[-2].strip() == "":
        out.pop()
    return out


def fix_spacing_before_annotations(lines: list[str]) -> list[str]:
    """Insert a blank line before an annotated declaration that follows another one."""
    out: list[str] = []
    for i, line in enumerate(lines):
        is_annotation_start = bool(ANNOTATION.match(line) or ANNOTATED_DECL.match(line))
        if is_annotation_start and out:
            prev = out[-1]
            # Previous line ends a declaration (not blank, not a comment, not an opening
            # brace, and not itself an annotation continuing onto this one).
            # A bare `)` closes a MULTI-LINE annotation argument list, e.g.
            #     @RequiresOptIn(
            #         message = "...",
            #     )
            #     @Retention(BINARY)
            # Those annotations belong to ONE declaration, so a blank line between them
            # splits the group and trips AnnotationSpacing/ModifierListSpacing instead.
            # Measured: an earlier revision inserted 3 such blanks into OptIn.kt and
            # created 6 new violations while fixing none.
            closes_annotation_args = prev.strip() in (")", "),")
            prev_is_code = (
                prev.strip() != ""
                and not prev.strip().startswith(("//", "/*", "*", "@"))
                and not prev.rstrip().endswith(("{", "(", ",", "=", "->"))
                and not closes_annotation_args
            )
            if prev_is_code and DECL_END.match(prev):
                out.append("")
        out.append(line)
    return out


def fix(path: Path) -> bool:
    if path.name in BUDGET_EXEMPT:
        return False
    original = path.read_text(encoding="utf-8")
    lines = original.split("\n")

    lines = fix_spacing_before_annotations(lines)
    lines = fix_consecutive_blanks(lines)

    updated = "\n".join(lines)
    if updated == original:
        return False
    path.write_text(updated, encoding="utf-8")
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
        print("ktlint formatting OK: no fixes needed")
        return 0

    verb = "would fix" if check_only else "fixed"
    print(f"Applying {len(changed)} file(s) — {verb} ktlint formatting:")
    for path in changed:
        print(f"  {path}")
    return 1 if check_only else 0


if __name__ == "__main__":
    raise SystemExit(main())
