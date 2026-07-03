#!/usr/bin/env python3
"""Fail when gate/check scripts are not wired or explicitly allowlisted.

Inventory covers:
- .claude/hooks/rules/detect-*.py
- tools/check-* scripts
- tools/*-check scripts
- tools/run-*-smoke scripts

Each candidate must be referenced by ci-gate.sh, ci.yml, or release.yml, or be
listed in the allowlist with a non-empty reason.
"""
from __future__ import annotations

import argparse
import json
import sys
from dataclasses import dataclass
from pathlib import Path

SURFACE_PATHS = (
    ".claude/hooks/rules/ci-gate.sh",
    ".github/workflows/ci.yml",
    ".github/workflows/release.yml",
)


@dataclass(frozen=True)
class AllowEntry:
    path: str
    reason: str


def candidate_paths(root: Path) -> list[str]:
    paths: set[str] = set()
    rules_dir = root / ".claude" / "hooks" / "rules"
    tools_dir = root / "tools"
    if rules_dir.is_dir():
        paths.update(path.relative_to(root).as_posix() for path in rules_dir.glob("detect-*.py"))
    if tools_dir.is_dir():
        paths.update(path.relative_to(root).as_posix() for path in tools_dir.glob("check-*") if path.is_file())
        paths.update(path.relative_to(root).as_posix() for path in tools_dir.glob("*-check") if path.is_file())
        paths.update(path.relative_to(root).as_posix() for path in tools_dir.glob("run-*-smoke") if path.is_file())
    return sorted(paths)


def surface_text(root: Path) -> str:
    chunks: list[str] = []
    for rel in SURFACE_PATHS:
        path = root / rel
        if path.is_file():
            chunks.append(path.read_text(encoding="utf-8"))
    return "\n".join(chunks)


def read_allowlist(path: Path) -> tuple[dict[str, AllowEntry] | None, str | None]:
    if not path.exists():
        return {}, None
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        return None, f"cannot read allowlist: {exc}"
    entries = payload.get("entries") if isinstance(payload, dict) else None
    if not isinstance(entries, list):
        return None, "allowlist must be an object with an entries list"
    out: dict[str, AllowEntry] = {}
    for index, raw in enumerate(entries, start=1):
        if not isinstance(raw, dict):
            return None, f"entry {index} must be an object"
        rel = raw.get("path")
        reason = raw.get("reason")
        if not isinstance(rel, str) or not rel.strip() or not isinstance(reason, str) or not reason.strip():
            return None, f"entry {index} needs non-empty path and reason"
        if rel in out:
            return None, f"duplicate allowlist entry for {rel}"
        out[rel] = AllowEntry(rel, reason)
    return out, None


def check(root: Path, allowlist: dict[str, AllowEntry]) -> tuple[list[str], list[str], list[str]]:
    surfaces = surface_text(root)
    wired: list[str] = []
    allowed: list[str] = []
    orphaned: list[str] = []
    for rel in candidate_paths(root):
        if rel in surfaces:
            wired.append(rel)
        elif rel in allowlist:
            allowed.append(rel)
        else:
            orphaned.append(rel)
    return wired, allowed, orphaned


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path.cwd())
    parser.add_argument(
        "--allowlist",
        type=Path,
        default=Path(".claude/hooks/rules/orphan-gate-allowlist.json"),
    )
    args = parser.parse_args(argv[1:])

    root = args.root.resolve(strict=False)
    allowlist_path = args.allowlist
    if not allowlist_path.is_absolute():
        allowlist_path = (Path.cwd() / allowlist_path).resolve(strict=False)
    allowlist, error = read_allowlist(allowlist_path)
    if error:
        print(f"ORPHAN GATE DETECTOR ERROR: {error}")
        return 2
    assert allowlist is not None

    wired, allowed, orphaned = check(root, allowlist)
    if orphaned:
        print(f"ORPHAN GATE DETECTOR FAIL: {len(orphaned)} unwired gate script(s)")
        for rel in orphaned:
            print(f"  - {rel}: wire into ci-gate.sh/ci.yml/release.yml or add an allowlist entry with reason")
        print(f"({len(wired)} wired, {len(allowed)} allowlisted)")
        return 1
    print(f"orphan gate detector OK: {len(wired)} wired, {len(allowed)} allowlisted, 0 orphaned")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
