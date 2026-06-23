#!/usr/bin/env python3
"""Detect tool-call identity regressions in Kotlin main sources.

Provider `toolCallId` values are correlation labels, not unique occurrence ids.
When code stores one value per raw id, duplicate provider ids collapse into one
logical tool call. This guard catches the high-risk shapes that caused the
regressions fixed in this branch: associateBy/toSet collapses and single-value
maps keyed by call ids.
"""
from __future__ import annotations

import argparse
import re
from dataclasses import dataclass
from pathlib import Path

MAIN_SOURCE_SETS = {
    "commonMain",
    "jvmMain",
    "jvmAndAndroidMain",
    "androidMain",
    "nativeMain",
}

COLLAPSE_PATTERNS = [
    (
        "associateBy(toolCallId) collapses duplicate tool-call occurrences",
        re.compile(r"\.associateBy\s*\{[^}\n]*toolCallId[^}\n]*}"),
    ),
    (
        "toSet(toolCallId) collapses duplicate tool-call occurrences",
        re.compile(r"(?:toolCallId[^;\n]*\.toSet\(\)|\.map(?:NotNull)?\s*\{[^}\n]*toolCallId[^}\n]*}\.toSet\(\))"),
    ),
]

DECLARATION = re.compile(
    r"\b(?:val|var)\s+([A-Za-z][A-Za-z0-9_]*)\s*=\s*"
    r"(mutableMapOf|linkedMapOf|hashMapOf|mutableSetOf|linkedSetOf)<([^>\n]*)>\("
)

SAFE_NAME_HINTS = ("Count", "Counts", "Ordinal", "Ordinals", "Occurrence", "Occurrences")
SAFE_VALUE_HINTS = ("List", "Set", "Collection", "Deque", "Queue")
RISKY_NAME_HINTS = ("ToolCallId", "toolCallId", "ByCallId", "byCallId", "ToolCalls", "ToolResults")


@dataclass(frozen=True)
class Finding:
    path: Path
    line_number: int
    reason: str
    line: str


def kotlin_main_files(root: Path) -> list[Path]:
    if root.is_file():
        return [root]
    return sorted(
        path
        for path in root.rglob("*.kt")
        if any(source_set in path.parts for source_set in MAIN_SOURCE_SETS)
    )


def strip_line_comment(line: str) -> str:
    return line.split("//", 1)[0]


def risky_declaration(line: str) -> str | None:
    match = DECLARATION.search(line)
    if not match:
        return None
    name, factory, type_args = match.groups()
    if not any(hint in name for hint in RISKY_NAME_HINTS):
        return None
    if any(hint in name for hint in SAFE_NAME_HINTS):
        return None
    if factory.endswith("SetOf"):
        return f"{factory} named {name} stores raw tool-call ids as identity"
    if any(hint in type_args for hint in SAFE_VALUE_HINTS):
        return None
    return f"{factory} named {name} stores one value per raw tool-call id"


def scan_file(path: Path) -> list[Finding]:
    findings: list[Finding] = []
    for line_number, raw_line in enumerate(path.read_text(encoding="utf-8", errors="ignore").splitlines(), start=1):
        line = strip_line_comment(raw_line)
        for reason, pattern in COLLAPSE_PATTERNS:
            if pattern.search(line):
                findings.append(Finding(path, line_number, reason, raw_line.strip()))
        declaration_reason = risky_declaration(line)
        if declaration_reason is not None:
            findings.append(Finding(path, line_number, declaration_reason, raw_line.strip()))
    return findings


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("root", nargs="?", default="src/commonMain/kotlin")
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()

    findings = [finding for path in kotlin_main_files(Path(args.root)) for finding in scan_file(path)]
    if not findings:
        print("tool identity gate OK: no raw toolCallId occurrence-collapse patterns")
        return 0

    print(f"TOOL IDENTITY GATE FAILED: {len(findings)} risky raw toolCallId identity pattern(s):")
    for finding in findings:
        print(f"  - {finding.path}:{finding.line_number}: {finding.reason}")
        print(f"      {finding.line}")
    print("\nUse occurrence-indexed lists or explicit approval ids; do not key unique state by provider toolCallId alone.")
    return 1 if args.check else 0


if __name__ == "__main__":
    raise SystemExit(main())
