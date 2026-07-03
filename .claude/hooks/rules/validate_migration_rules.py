#!/usr/bin/env python3
"""Validate consumer migration ast-grep rules.

Migration rules live under docs/migrations and are shipped to consumers, not
installed as repo invariants. This gate keeps them from rotting by requiring
each rule file to have a same-named fixture directory:

  docs/migrations/<name>.yaml
  docs/migrations/fixtures/<name>/bad.kt
  docs/migrations/fixtures/<name>/good.kt

Every rule id in the YAML must match the bad fixture and skip the good fixture.
If a rule file contains a `fix:`, the runner also applies it to a scratch copy
and requires a clean re-scan plus an idempotent second application.
"""
from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

RULE_ID_RE = re.compile(r"(?m)^id:\s*[\"']?([A-Za-z0-9_.:-]+)")
FIX_RE = re.compile(r"(?m)^\s*fix\s*:")


def repo_root() -> Path:
    return Path(__file__).resolve().parents[3]


def ast_grep_binary(root: Path) -> str | None:
    env = os.environ.get("AG")
    candidates = [
        env,
        str(root / "node_modules" / ".bin" / "ast-grep"),
        shutil.which("ast-grep"),
        str(Path.home() / ".local" / "bin" / "ast-grep"),
    ]
    for candidate in candidates:
        if candidate and Path(candidate).is_file() and os.access(candidate, os.X_OK):
            return candidate
    return None


def scan(binary: str, rule: Path, target: Path) -> tuple[int, list[dict[str, object]], str]:
    cp = subprocess.run(
        [binary, "scan", "--rule", str(rule), str(target), "--json=compact"],
        capture_output=True,
        text=True,
        timeout=10,
    )
    raw = cp.stdout.strip()
    matches: list[dict[str, object]] = []
    if raw:
        payload = json.loads(raw)
        if isinstance(payload, list):
            matches = [item for item in payload if isinstance(item, dict)]
    return cp.returncode, matches, cp.stderr


def apply_fix(binary: str, rule: Path, target: Path) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [binary, "scan", "--rule", str(rule), str(target), "--update-all"],
        capture_output=True,
        text=True,
        timeout=10,
    )


def match_rule_ids(matches: list[dict[str, object]]) -> set[str]:
    out: set[str] = set()
    for item in matches:
        rid = item.get("ruleId") or item.get("rule_id") or item.get("id")
        if isinstance(rid, str):
            out.add(rid)
    return out


def rule_ids(rule_text: str) -> set[str]:
    return set(RULE_ID_RE.findall(rule_text))


def validate_rule_file(binary: str, rule: Path, migrations_dir: Path) -> list[str]:
    errors: list[str] = []
    text = rule.read_text(encoding="utf-8")
    ids = rule_ids(text)
    if not ids:
        return [f"{rule.name}: no rule ids found"]

    fixture_dir = migrations_dir / "fixtures" / rule.stem
    bad = fixture_dir / "bad.kt"
    good = fixture_dir / "good.kt"
    if not bad.is_file() or not good.is_file():
        return [f"{rule.name}: missing fixtures/{rule.stem}/bad.kt or good.kt"]

    rc_bad, bad_matches, err_bad = scan(binary, rule, bad)
    if rc_bad not in (0, 1):
        return [f"{rule.name}: bad fixture scan failed: {err_bad.strip() or rc_bad}"]
    matched_ids = match_rule_ids(bad_matches)
    missing = sorted(ids - matched_ids)
    if missing:
        errors.append(f"{rule.name}: bad.kt did not match {', '.join(missing)}")

    rc_good, good_matches, err_good = scan(binary, rule, good)
    if rc_good not in (0, 1):
        errors.append(f"{rule.name}: good fixture scan failed: {err_good.strip() or rc_good}")
    elif good_matches:
        errors.append(f"{rule.name}: good.kt matched {len(good_matches)} time(s)")

    if FIX_RE.search(text):
        with tempfile.TemporaryDirectory() as tmp:
            scratch = Path(tmp) / "bad.kt"
            shutil.copyfile(bad, scratch)
            first = apply_fix(binary, rule, scratch)
            if first.returncode not in (0, 1):
                errors.append(f"{rule.name}: fix application failed: {first.stderr.strip() or first.returncode}")
            _, after_matches, _ = scan(binary, rule, scratch)
            if after_matches:
                errors.append(f"{rule.name}: fixed bad.kt still matches {len(after_matches)} time(s)")
            once = scratch.read_text(encoding="utf-8")
            second = apply_fix(binary, rule, scratch)
            if second.returncode not in (0, 1):
                errors.append(f"{rule.name}: second fix application failed: {second.stderr.strip() or second.returncode}")
            twice = scratch.read_text(encoding="utf-8")
            if once != twice:
                errors.append(f"{rule.name}: fix is not idempotent")
    return errors


def main(argv: list[str]) -> int:
    root = repo_root()
    migrations_dir = Path(argv[1]) if len(argv) > 1 else root / "docs" / "migrations"
    if not migrations_dir.is_absolute():
        migrations_dir = root / migrations_dir
    binary = ast_grep_binary(root)
    if binary is None:
        print("ERROR: ast-grep not found")
        return 2
    rule_files = sorted(p for p in migrations_dir.glob("*.yaml") if p.is_file())
    if not rule_files:
        print(f"ERROR: no migration rule files in {migrations_dir}")
        return 2
    errors: list[str] = []
    for rule in rule_files:
        errors.extend(validate_rule_file(binary, rule, migrations_dir))
    if errors:
        print(f"MIGRATION RULE FAIL: {len(errors)} issue(s)")
        for error in errors:
            print(f"  - {error}")
        return 1
    print(f"migration rule gate OK: {len(rule_files)} rule file(s)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
