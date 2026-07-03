#!/usr/bin/env python3
"""Validate warning-only Python guard rules for hook/gate code.

These rules do not enter the Kotlin edit-time policy manifest. They protect the
guard layer itself by requiring each Python ast-grep rule to match a historical
bug fixture and skip its compliant counterpart, then printing a non-blocking
warning count over the live hook tree.
"""
from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
from pathlib import Path


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


def scan(binary: str, rule: Path, targets: list[Path]) -> tuple[int, int, str]:
    if not targets:
        return 0, 0, ""
    completed = subprocess.run(
        [binary, "scan", "--rule", str(rule), *(str(target) for target in targets), "--json=compact"],
        capture_output=True,
        text=True,
        timeout=10,
    )
    raw = (completed.stdout or "").strip()
    count = 0
    if raw:
        payload = json.loads(raw)
        if isinstance(payload, list):
            count = len(payload)
    return completed.returncode, count, completed.stderr or ""


def python_sources(source_root: Path, fixtures_dir: Path) -> list[Path]:
    if not source_root.exists():
        return []
    out: list[Path] = []
    for path in sorted(source_root.rglob("*.py")):
        if "__pycache__" in path.parts:
            continue
        try:
            path.relative_to(fixtures_dir)
            continue
        except ValueError:
            pass
        out.append(path)
    return out


def validate_rule(binary: str, rule: Path, fixtures_dir: Path) -> list[str]:
    errors: list[str] = []
    case_dir = fixtures_dir / rule.stem
    bad = case_dir / "bad.py"
    good = case_dir / "good.py"
    if not bad.is_file() or not good.is_file():
        return [f"{rule.name}: missing fixtures/{rule.stem}/bad.py or good.py"]

    rc_bad, bad_count, bad_err = scan(binary, rule, [bad])
    if rc_bad not in (0, 1):
        return [f"{rule.name}: bad fixture scan failed: {bad_err.strip() or rc_bad}"]
    if bad_count < 1:
        errors.append(f"{rule.name}: bad.py did not match")

    rc_good, good_count, good_err = scan(binary, rule, [good])
    if rc_good not in (0, 1):
        errors.append(f"{rule.name}: good fixture scan failed: {good_err.strip() or rc_good}")
    elif good_count:
        errors.append(f"{rule.name}: good.py matched {good_count} time(s)")
    return errors


def main(argv: list[str]) -> int:
    root = repo_root()
    rules_dir = Path(argv[1]) if len(argv) > 1 else root / ".claude" / "hooks" / "rules" / "python"
    source_root = Path(argv[2]) if len(argv) > 2 else root / ".claude" / "hooks"
    if not rules_dir.is_absolute():
        rules_dir = root / rules_dir
    if not source_root.is_absolute():
        source_root = root / source_root

    binary = ast_grep_binary(root)
    if binary is None:
        print("ERROR: ast-grep not found")
        return 2
    rule_files = sorted(path for path in rules_dir.glob("*.yaml") if path.is_file())
    if not rule_files:
        print(f"ERROR: no Python guard rule files in {rules_dir}")
        return 2

    fixtures_dir = rules_dir / "fixtures"
    errors: list[str] = []
    for rule in rule_files:
        errors.extend(validate_rule(binary, rule, fixtures_dir))
    if errors:
        print(f"PYTHON GUARD RULE FAIL: {len(errors)} issue(s)")
        for error in errors:
            print(f"  - {error}")
        return 1

    sources = python_sources(source_root, fixtures_dir)
    print(f"python guard fixture gate OK: {len(rule_files)} rule file(s)")
    print("python guard warning report:")
    for rule in rule_files:
        rc, count, err = scan(binary, rule, sources)
        if rc not in (0, 1):
            print(f"  {rule.stem}: scan error: {err.strip() or rc}")
            return 1
        print(f"  {rule.stem}: {count} warning(s)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
