#!/usr/bin/env python3
"""Install gate for the Kotlin ast-grep hook rules.

ast-grep silently ACCEPTS structurally-odd rules (e.g. a sibling `kind:` + `all:`
under `rule:` is read as an implicit AND), so a rule can parse yet not do what
its author intended. A parse check is therefore necessary but NOT sufficient.
This gate has two modes:

  PARSE mode (default) — every rule file in a dir must parse as a valid ast-grep
  rule. Catches malformed YAML before it reaches the live PreToolUse policy.
      python3 validate_rules.py [<rules-dir>]      # default: ./kotlin

  SEMANTIC mode — each candidate rule must MATCH its bad example and NOT match
  its good example. This is the real install gate for foundry-produced rules.
      python3 validate_rules.py --manifest <rules.json>
  where rules.json is a list of {id, severity, yaml, badExample, goodExample}.

Exit 0 if all checks pass; 1 listing offenders; 2 on environment error.
"""
from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

PARSE_ERROR_MARKERS = ("Cannot parse", "not a valid ast-grep rule", "Rule must specify", "is not a valid")
PROBE = "package probe\n\npublic class Probe {\n    public fun run(): Int = 0\n}\n"


def ast_grep_binary() -> str | None:
    for candidate in ("ast-grep", str(Path.home() / ".local" / "bin" / "ast-grep")):
        found = shutil.which(candidate) if os.sep not in candidate else candidate
        if found and Path(found).is_file():
            return found
    return None


def _scan(binary: str, rule_path: str, code_path: str) -> tuple[int, int, str]:
    """Return (returncode, match_count, stderr) for one rule against one file."""
    cp = subprocess.run(
        [binary, "scan", "--rule", rule_path, code_path, "--json=compact"],
        capture_output=True, text=True, timeout=10,
    )
    count = 0
    raw = (cp.stdout or "").strip()
    if raw:
        try:
            payload = json.loads(raw)
            count = len(payload) if isinstance(payload, list) else 0
        except json.JSONDecodeError:
            count = 0
    return cp.returncode, count, (cp.stderr or "")


def _parses(stderr: str, returncode: int) -> bool:
    return returncode in (0, 1) and not any(m in stderr for m in PARSE_ERROR_MARKERS)


def parse_mode(binary: str, rules_dir: Path) -> int:
    rule_files = sorted(rules_dir.glob("*.yaml"))
    if not rule_files:
        print(f"ERROR: no rule files in {rules_dir}")
        return 2
    with tempfile.NamedTemporaryFile(mode="w", suffix=".kt", delete=False, encoding="utf-8") as f:
        f.write(PROBE); probe = f.name
    bad: list[tuple[str, str]] = []
    try:
        for rf in rule_files:
            rc, _, err = _scan(binary, str(rf), probe)
            if not _parses(err, rc):
                first = next((ln for ln in err.splitlines() if ln.strip()), f"exit {rc}")
                bad.append((rf.name, first.strip()))
    finally:
        os.unlink(probe)
    if bad:
        print(f"INVALID: {len(bad)}/{len(rule_files)} rule files failed to parse")
        for n, e in bad:
            print(f"  - {n}: {e}")
        return 1
    print(f"ok: all {len(rule_files)} rule files parse")
    return 0


def _write_kt(text: str) -> str:
    with tempfile.NamedTemporaryFile(mode="w", suffix=".kt", delete=False, encoding="utf-8") as f:
        f.write(text if text.strip().startswith("package") else "package probe\n\n" + text)
        return f.name


def _write_yaml(text: str) -> str:
    with tempfile.NamedTemporaryFile(mode="w", suffix=".yaml", delete=False, encoding="utf-8") as f:
        f.write(text)
        return f.name


def semantic_mode(binary: str, manifest_path: Path) -> int:
    try:
        rules = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        print(f"ERROR: cannot read manifest: {exc}")
        return 2
    if not isinstance(rules, list) or not rules:
        print("ERROR: manifest must be a non-empty JSON list")
        return 2
    failures: list[tuple[str, str]] = []
    passed = 0
    for r in rules:
        rid = str(r.get("id", "?"))
        yaml_text = r.get("yaml") or ""
        bad_ex = r.get("badExample") or ""
        good_ex = r.get("goodExample") or ""
        rule_path = _write_yaml(yaml_text)
        bad_path = _write_kt(bad_ex)
        good_path = _write_kt(good_ex)
        try:
            rc_b, n_bad, err_b = _scan(binary, rule_path, bad_path)
            if not _parses(err_b, rc_b):
                failures.append((rid, "rule does not parse"))
                continue
            _, n_good, _ = _scan(binary, rule_path, good_path)
            if n_bad < 1:
                failures.append((rid, "badExample NOT matched (rule too weak / wrong)"))
            elif n_good > 0:
                failures.append((rid, f"goodExample WRONGLY matched x{n_good} (false positive)"))
            else:
                passed += 1
        finally:
            for p in (rule_path, bad_path, good_path):
                try:
                    os.unlink(p)
                except OSError:
                    pass
    if failures:
        print(f"SEMANTIC FAIL: {len(failures)}/{len(rules)} rules rejected (drop before install)")
        for rid, why in failures:
            print(f"  - {rid}: {why}")
        print(f"({passed} passed)")
        return 1
    print(f"ok: all {len(rules)} rules match bad + skip good")
    return 0


def main() -> int:
    binary = ast_grep_binary()
    if binary is None:
        print("ERROR: ast-grep not found on PATH")
        return 2
    args = sys.argv[1:]
    if args and args[0] == "--manifest":
        if len(args) < 2:
            print("ERROR: --manifest requires a path")
            return 2
        return semantic_mode(binary, Path(args[1]))
    rules_dir = Path(args[0]) if args else Path(__file__).resolve().parent / "kotlin"
    if not rules_dir.is_dir():
        print(f"ERROR: rules dir not found: {rules_dir}")
        return 2
    return parse_mode(binary, rules_dir)


if __name__ == "__main__":
    raise SystemExit(main())
