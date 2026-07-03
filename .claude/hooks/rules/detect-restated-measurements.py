#!/usr/bin/env python3
"""Warn when docs restate measured numbers instead of citing dev/measurements.toml.

Docs should cite measurement keys such as `[meas: coverage_branch_percent]`.
Raw percentages, latency values, token counts, or coverage count triples drift
independently from the ledger. This detector is warning-only in ci-gate; the
fixture harness runs it in --check mode so the pattern itself stays live.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from dataclasses import dataclass
from pathlib import Path

ROOT_DOCS = ("CHANGELOG.md", "INTERFACE_CONTRACT.md", "CLAUDE.md")
MEAS_CITATION_RE = re.compile(r"\[meas:\s*[A-Za-z0-9_.-]+\s*\]")
PATTERNS: tuple[tuple[str, re.Pattern[str]], ...] = (
    ("percent", re.compile(r"(?<![\w.])\d{1,3}\.\d{1,2}%")),
    ("seconds", re.compile(r"(?<![\w.])\d+(?:\.\d+)?s\b")),
    ("milliseconds", re.compile(r"(?<![\w.])\d+(?:\.\d+)?\s*(?:ms|milliseconds)\b")),
    ("seconds-word", re.compile(r"(?<![\w.])\d+(?:\.\d+)?\s+seconds\b")),
    ("tokens", re.compile(r"(?<![\w.])\d[\d,]*\s+(?:input\s+tokens|output\s+tokens|tokens)\b")),
    ("coverage-counts", re.compile(r"\bcovered\s*=\s*\d[\d,]*\s+total\s*=\s*\d[\d,]*\b")),
    (
        "coverage-triple",
        re.compile(
            r"\b(?:line|lines|instruction|instructions|branch|branches)"
            r"\s*[:=]\s*\d{1,3}\.\d{1,2}%"
        ),
    ),
)


@dataclass(frozen=True)
class Hit:
    path: str
    line: int
    kind: str
    literal: str
    text: str


@dataclass(frozen=True)
class AllowEntry:
    path: str
    literal: str
    line_contains: str
    reason: str


def target_files(root: Path) -> list[Path]:
    files: list[Path] = []
    for name in ROOT_DOCS:
        path = root / name
        if path.is_file():
            files.append(path)
    docs_dir = root / "docs"
    if docs_dir.is_dir():
        files.extend(sorted(path for path in docs_dir.glob("*.md") if path.is_file()))
    campaigns_dir = root / "dev" / "campaigns"
    if campaigns_dir.is_dir():
        files.extend(sorted(path for path in campaigns_dir.glob("*.toml") if path.is_file()))
    return sorted(set(files))


def candidate_lines(path: Path) -> list[tuple[int, str]]:
    text = path.read_text(encoding="utf-8")
    if path.suffix == ".toml":
        return campaign_header_lines(text)
    return markdown_lines(text)


def campaign_header_lines(text: str) -> list[tuple[int, str]]:
    out: list[tuple[int, str]] = []
    for line_number, line in enumerate(text.splitlines(), start=1):
        stripped = line.strip()
        if stripped == "":
            out.append((line_number, line))
            continue
        if stripped.startswith("#"):
            out.append((line_number, line))
            continue
        break
    return out


def markdown_lines(text: str) -> list[tuple[int, str]]:
    out: list[tuple[int, str]] = []
    in_fence = False
    for line_number, line in enumerate(text.splitlines(), start=1):
        stripped = line.lstrip()
        if stripped.startswith("```") or stripped.startswith("~~~"):
            in_fence = not in_fence
            continue
        if in_fence:
            continue
        out.append((line_number, line))
    return out


def detect(root: Path, allowlist: list[AllowEntry]) -> tuple[list[Hit], int]:
    hits: list[Hit] = []
    allowlisted = 0
    for path in target_files(root):
        rel = path.relative_to(root).as_posix()
        for line_number, line in candidate_lines(path):
            if is_verbatim_context(line):
                continue
            citation_spans = [match.span() for match in MEAS_CITATION_RE.finditer(line)]
            for kind, pattern in PATTERNS:
                for match in pattern.finditer(line):
                    if inside_any_span(match.span(), citation_spans):
                        continue
                    hit = Hit(rel, line_number, kind, match.group(0), line.rstrip())
                    if is_non_measurement_literal(hit):
                        continue
                    if is_allowlisted(hit, allowlist):
                        allowlisted += 1
                    else:
                        hits.append(hit)
    return hits, allowlisted


def inside_any_span(span: tuple[int, int], spans: list[tuple[int, int]]) -> bool:
    start, end = span
    return any(start >= allowed_start and end <= allowed_end for allowed_start, allowed_end in spans)


def is_verbatim_context(line: str) -> bool:
    stripped = line.lstrip()
    if stripped.startswith(("note = ", "reconcile = ")):
        return True
    if stripped.startswith(">") and ("note =" in stripped or "reconcile =" in stripped):
        return True
    if stripped.startswith("# [") and " done:" in stripped:
        return True
    return False


def is_non_measurement_literal(hit: Hit) -> bool:
    if hit.kind == "seconds" and hit.literal in {"400s", "401s", "403s", "404s", "500s"}:
        return True
    return False


def is_allowlisted(hit: Hit, allowlist: list[AllowEntry]) -> bool:
    return any(
        entry.path == hit.path
        and entry.literal == hit.literal
        and entry.line_contains in hit.text
        for entry in allowlist
    )


def read_allowlist(path: Path) -> tuple[list[AllowEntry] | None, str | None]:
    if not path.exists():
        return [], None
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        return None, f"cannot read allowlist: {exc}"
    entries = payload.get("entries") if isinstance(payload, dict) else None
    if not isinstance(entries, list):
        return None, "allowlist must be an object with an entries list"
    out: list[AllowEntry] = []
    for index, raw in enumerate(entries, start=1):
        if not isinstance(raw, dict):
            return None, f"allowlist entry {index} must be an object"
        path_value = raw.get("path")
        literal = raw.get("literal")
        line_contains = raw.get("lineContains")
        reason = raw.get("reason")
        if not all(isinstance(value, str) and value.strip() for value in (path_value, literal, line_contains, reason)):
            return None, f"allowlist entry {index} needs path, literal, lineContains, and reason"
        out.append(AllowEntry(path_value, literal, line_contains, reason))
    return out, None


def print_report(hits: list[Hit], allowlisted: int) -> None:
    print(f"restated measurement warning report: {len(hits)} warning(s), {allowlisted} allowlisted")
    for hit in hits[:20]:
        print(f"  {hit.path}:{hit.line}: {hit.literal} [{hit.kind}]")
        print(f"    {hit.text.strip()}")
    if len(hits) > 20:
        print(f"  ... {len(hits) - 20} more warning(s)")


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path.cwd())
    parser.add_argument(
        "--allowlist",
        type=Path,
        default=Path(".claude/hooks/rules/restated-measurements-allowlist.json"),
    )
    parser.add_argument("--check", action="store_true", help="exit 1 when unallowlisted literals are found")
    args = parser.parse_args(argv[1:])

    root = args.root.resolve(strict=False)
    allowlist_path = args.allowlist
    if not allowlist_path.is_absolute():
        allowlist_path = (Path.cwd() / allowlist_path).resolve(strict=False)
    allowlist, error = read_allowlist(allowlist_path)
    if error:
        print(f"RESTATED MEASUREMENTS DETECTOR ERROR: {error}")
        return 2
    assert allowlist is not None

    hits, allowlisted = detect(root, allowlist)
    print_report(hits, allowlisted)
    if args.check and hits:
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
