#!/usr/bin/env python3
"""
Run EVERY opt-in style rule against the tree and ratchet its violation count.

Why this exists: ci-gate's warning loop hardcoded five rule names, so 5 of 77 style rules were
ever scanned. The comment above that loop already said the right thing — "a warning-severity rule
that nothing prints is inert: it parses, validates against its fixture, and reports nothing about
the real tree — coverage in name only" — and the code under it did exactly that for 72 rules.
Three rules authored the same day this was found were inert on arrival, and one had a live hit the
gate reported as PASS.

Model is the repo's existing ratchet, same shape as data-class-budget.json: each rule carries its
current count, the gate fails when a count RISES, and re-seeding downward is the way debt is paid.
That gives warning rules a blocking edge on regression without red-gating the backlog they were
adopted to measure.

Fail-closed: a rule with no budget entry is an error, so a rule cannot join the lane invisibly —
which is the specific hole that made this necessary.

  check-style-rule-counts.py "<space separated dirs>"   verify
  check-style-rule-counts.py "<dirs>" --update          re-seed (review the diff)
"""
from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
STYLE_DIR = ROOT / ".rules" / "kotlin" / "ast-grep" / "rules-style"
BUDGET = Path(__file__).with_name("style-rule-count-budget.json")


def ast_grep() -> str:
    for candidate in (
        os.environ.get("AG"),
        str(ROOT / "node_modules" / ".bin" / "ast-grep"),
        shutil.which("ast-grep"),
        str(Path.home() / ".local" / "bin" / "ast-grep"),
    ):
        if candidate and Path(candidate).exists():
            return candidate
    print("check-style-rule-counts: ast-grep not found", file=sys.stderr)
    raise SystemExit(1)


def count(binary: str, rule: Path, dirs: list[str]) -> int:
    result = subprocess.run(
        [binary, "scan", "--rule", str(rule), *dirs],
        capture_output=True,
        text=True,
        cwd=ROOT,
    )
    return sum(1 for line in result.stdout.splitlines() if line.startswith(("warning[", "error[")))


def main() -> int:
    dirs = [d for d in (sys.argv[1] if len(sys.argv) > 1 else "").split() if d]
    if not dirs:
        dirs = ["src/commonMain/kotlin"]
    update = "--update" in sys.argv
    binary = ast_grep()

    rules = sorted(STYLE_DIR.glob("*.yaml"))
    current = {rule.stem: count(binary, rule, dirs) for rule in rules}

    if update:
        BUDGET.write_text(
            json.dumps({"schemaVersion": 1, "maxViolationsByRule": current}, indent=2) + "\n",
            encoding="utf-8",
        )
        print(f"style-rule count budget re-seeded for {len(current)} rule(s) — review the diff")
        return 0

    budget = (
        json.loads(BUDGET.read_text(encoding="utf-8")).get("maxViolationsByRule", {})
        if BUDGET.exists()
        else {}
    )

    regressions = [(name, budget[name], n) for name, n in current.items() if name in budget and n > budget[name]]
    unbudgeted = [name for name in current if name not in budget]
    slack = [(name, budget[name], n) for name, n in current.items() if name in budget and n < budget[name]]

    total = sum(current.values())
    print(f"style rule scan: {len(rules)} rules, {total} live violation(s)")
    for name, n in sorted(current.items(), key=lambda kv: -kv[1]):
        if n:
            print(f"  {name}: {n}")

    if regressions:
        print("\nstyle-rule count gate FAILED — these rose:")
        for name, was, now in regressions:
            print(f"  {name}: {was} -> {now}")
        return 1
    if unbudgeted:
        print("\nstyle-rule count gate FAILED — no budget entry (a rule must not join this lane invisibly):")
        for name in unbudgeted:
            print(f"  {name}")
        return 1
    if slack:
        print("\nstyle-rule count gate FAILED — counts fell; re-seed DOWN in the same commit (--update):")
        for name, was, now in slack:
            print(f"  {name}: {was} -> {now}")
        return 1

    print("style-rule count gate OK: no rule regressed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
