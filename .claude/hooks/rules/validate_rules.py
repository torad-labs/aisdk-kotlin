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

  HUNK mode — re-scan each fixture as an edit hunk/snippet, without package or
  enclosing scope. Entries default to hunkExpectation="same"; scope-anchored
  rules may declare hunkExpectation="no-match" and optional badHunkExample /
  goodHunkExample fragments. Entries may also declare memberExamples fragments
  that must never match when scanned as isolated class-member context probes.
  hunkUnsafe=true documents rules that require adapter-preserved enclosing
  context because tree-sitter cannot distinguish some bare class-body fragments
  from real source_file declarations. Rules using ast-grep relational anchors
  (inside:/precedes:/follows:) must explicitly declare hunk handling through
  hunkExpectation, hunkUnsafe=true, or memberExamples, so future hunk-scanning
  consumers cannot accidentally rely on whole-file-only behavior.
      python3 validate_rules.py --hunk-mode <rules.json>

  AUTOFIX mode — validate the explicit autofix registry and its fix fixtures,
  or apply registered fixes to a target tree and fail for review/restage.
      python3 validate_rules.py --manifest <rules.json> --autofix-registry <registry.json>
      python3 validate_rules.py --apply-autofix <registry.json> <path>...

  SCAFFOLD mode — emit a fill-in manifest entry for an existing rule file.
      python3 validate_rules.py --new <rule-id>
      python3 validate_rules.py --new <rule-id> --fix

Exit 0 if all checks pass; 1 listing offenders; 2 on environment error.
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

PARSE_ERROR_MARKERS = ("Cannot parse", "not a valid ast-grep rule", "Rule must specify", "is not a valid")
PROBE = "package probe\n\npublic class Probe {\n    public fun run(): Int = 0\n}\n"
STUB_BAD_EXAMPLE = "TODO: replace with code that MUST match this rule"
STUB_GOOD_EXAMPLE = "TODO: replace with code that MUST NOT match this rule"
STUB_FIX_TEMPLATE = "TODO: replace with an ast-grep fix template"
STUB_FIX_INPUT = "TODO: replace with code BEFORE applying this fix"
STUB_FIX_OUTPUT = "TODO: replace with code AFTER applying this fix"
RELATIONAL_ANCHOR_RE = re.compile(r"(?m)^\s*-?\s*(inside|precedes|follows):")
FIX_RE = re.compile(r"(?m)^\s*fix\s*:")


def ast_grep_binary() -> str | None:
    for candidate in ("ast-grep", str(Path.home() / ".local" / "bin" / "ast-grep")):
        found = shutil.which(candidate) if os.sep not in candidate else candidate
        if found and Path(found).is_file():
            return found
    return None


def _scan(
    binary: str,
    rule_path: str,
    code_path: str,
    timeout: float = 10.0,
) -> tuple[int, int, str]:
    """Return (returncode, match_count, stderr) for one rule against one file."""
    cp = subprocess.run(
        [binary, "scan", "--rule", rule_path, code_path, "--json=compact"],
        capture_output=True, text=True, timeout=timeout,
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


def _write_kt_hunk(text: str) -> str:
    with tempfile.NamedTemporaryFile(mode="w", suffix=".kt", delete=False, encoding="utf-8") as f:
        f.write(text)
        return f.name


def _write_yaml(text: str) -> str:
    with tempfile.NamedTemporaryFile(mode="w", suffix=".yaml", delete=False, encoding="utf-8") as f:
        f.write(text)
        return f.name


def _read_manifest(manifest_path: Path) -> list[dict[str, object]] | None:
    try:
        rules = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        print(f"ERROR: cannot read manifest: {exc}")
        return None
    if not isinstance(rules, list) or not rules:
        print("ERROR: manifest must be a non-empty JSON list")
        return None
    if not all(isinstance(r, dict) for r in rules):
        print("ERROR: manifest entries must be JSON objects")
        return None
    return rules


def semantic_mode(binary: str, manifest_path: Path, registry_path: Path | None = None) -> int:
    rules = _read_manifest(manifest_path)
    if rules is None:
        return 2
    try:
        missing = _missing_manifest_entries(manifest_path, rules)
    except RuntimeError as exc:
        print(f"SEMANTIC FAIL: {exc}")
        return 1
    if missing:
        print(f"SEMANTIC FAIL: {len(missing)} rule files have no manifest entry")
        for rid in missing:
            print(f"  - {rid}: missing manifest entry")
        return 1
    failures: list[tuple[str, str]] = []
    passed = 0
    for r in rules:
        rid = str(r.get("id", "?"))
        yaml_text = r.get("yaml") or ""
        bad_ex = r.get("badExample") or ""
        good_ex = r.get("goodExample") or ""
        if _needs_examples(bad_ex, good_ex):
            failures.append((rid, "needs examples"))
            continue
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
    registry_failures = _validate_autofix_registry(binary, manifest_path, rules, registry_path)
    if registry_failures is None:
        return 2
    if registry_failures:
        print(f"SEMANTIC FAIL: {len(registry_failures)} autofix registry issue(s)")
        for rid, why in registry_failures:
            print(f"  - {rid}: {why}")
        return 1
    print(f"ok: all {len(rules)} rules match bad + skip good")
    return 0


def hunk_mode(binary: str, manifest_path: Path) -> int:
    rules = _read_manifest(manifest_path)
    if rules is None:
        return 2
    failures: list[tuple[str, str]] = []
    passed = 0
    for r in rules:
        rid = str(r.get("id", "?"))
        yaml_text = r.get("yaml") or ""
        bad_ex = r.get("badExample") or ""
        good_ex = r.get("goodExample") or ""
        bad_hunk = r.get("badHunkExample", bad_ex)
        good_hunk = r.get("goodHunkExample", good_ex)
        expectation = str(r.get("hunkExpectation") or "same")
        if expectation not in {"same", "no-match"}:
            failures.append((rid, f"invalid hunkExpectation {expectation!r}"))
            continue
        hunk_unsafe = r.get("hunkUnsafe", False)
        if not isinstance(hunk_unsafe, bool):
            failures.append((rid, "hunkUnsafe must be a boolean"))
            continue
        member_examples, member_error = _member_examples(r)
        if member_error:
            failures.append((rid, member_error))
            continue
        if _uses_relational_anchor(yaml_text) and not _has_declared_hunk_handling(r, member_examples):
            failures.append(
                (
                    rid,
                    "relational rule must declare hunk handling "
                    "(hunkExpectation, hunkUnsafe=true, or memberExamples)",
                )
            )
            continue
        if _needs_examples(bad_ex, good_ex):
            failures.append((rid, "needs examples"))
            continue
        if not isinstance(bad_hunk, str) or not isinstance(good_hunk, str):
            failures.append((rid, "hunk examples must be strings"))
            continue

        rule_path = _write_yaml(yaml_text)
        bad_path = _write_kt_hunk(bad_hunk)
        good_path = _write_kt_hunk(good_hunk)
        try:
            rc_b, n_bad, err_b = _scan(binary, rule_path, bad_path)
            if not _parses(err_b, rc_b):
                failures.append((rid, "rule does not parse in bad hunk scan"))
                continue
            rc_g, n_good, err_g = _scan(binary, rule_path, good_path)
            if not _parses(err_g, rc_g):
                failures.append((rid, "rule does not parse in good hunk scan"))
                continue
            if expectation == "same":
                if n_bad < 1:
                    failures.append((rid, "badExample hunk NOT matched (context-fragile rule)"))
                elif n_good > 0:
                    failures.append((rid, f"goodExample hunk WRONGLY matched x{n_good}"))
            elif n_bad > 0 or n_good > 0:
                failures.append((rid, f"hunkExpectation=no-match but hunk matched bad={n_bad} good={n_good}"))
            for index, member_example in enumerate(member_examples, start=1):
                member_path = _write_kt_hunk(member_example)
                try:
                    rc_m, n_member, err_m = _scan(binary, rule_path, member_path)
                finally:
                    os.unlink(member_path)
                if not _parses(err_m, rc_m):
                    failures.append((rid, f"memberExample[{index}] does not parse in hunk scan"))
                    break
                if n_member > 0:
                    failures.append((rid, f"memberExample[{index}] WRONGLY matched x{n_member}"))
                    break
            else:
                if not any(existing_rid == rid for existing_rid, _ in failures):
                    passed += 1
        finally:
            for p in (rule_path, bad_path, good_path):
                try:
                    os.unlink(p)
                except OSError:
                    pass
    if failures:
        print(f"HUNK FAIL: {len(failures)}/{len(rules)} rules rejected")
        for rid, why in failures:
            print(f"  - {rid}: {why}")
        print(f"({passed} passed)")
        return 1
    print(f"ok: all {len(rules)} rules satisfy hunk-mode expectations")
    return 0


def _member_examples(rule: dict[str, object]) -> tuple[list[str], str | None]:
    if "memberExample" in rule:
        return [], "memberExample is deprecated; use memberExamples"
    member_examples = rule.get("memberExamples")
    if member_examples is None:
        return [], None
    if isinstance(member_examples, list) and all(isinstance(item, str) for item in member_examples):
        return member_examples, None
    return [], "memberExamples must be a list of strings"


def _uses_relational_anchor(yaml_text: object) -> bool:
    return isinstance(yaml_text, str) and bool(RELATIONAL_ANCHOR_RE.search(yaml_text))


def _has_declared_hunk_handling(rule: dict[str, object], member_examples: list[str]) -> bool:
    return (
        "hunkExpectation" in rule
        or rule.get("hunkUnsafe") is True
        or bool(member_examples)
    )


def _missing_manifest_entries(manifest_path: Path, rules: list[dict[str, object]]) -> list[str]:
    rules_dir = Path(__file__).resolve().parent / "kotlin"
    if not rules_dir.is_dir():
        raise RuntimeError(f"canonical rules dir not found: {rules_dir}")
    manifest_ids = {str(r.get("id")) for r in rules}
    rule_ids = {p.stem for p in rules_dir.glob("*.yaml")}
    return sorted(rule_ids - manifest_ids)


def _needs_examples(bad_ex: object, good_ex: object) -> bool:
    return (
        not isinstance(bad_ex, str)
        or not isinstance(good_ex, str)
        or bad_ex.strip() in {"", STUB_BAD_EXAMPLE}
        or good_ex.strip() in {"", STUB_GOOD_EXAMPLE}
    )


def _default_registry_path(manifest_path: Path) -> Path:
    return manifest_path.resolve().parent / "autofix-registry.json"


def _read_registry(registry_path: Path) -> tuple[list[str] | None, str | None]:
    try:
        payload = json.loads(registry_path.read_text(encoding="utf-8"))
    except FileNotFoundError:
        return [], None
    except (OSError, json.JSONDecodeError) as exc:
        return None, f"cannot read autofix registry: {exc}"
    if not isinstance(payload, list):
        return None, "autofix registry must be a JSON list"
    ids: list[str] = []
    for index, entry in enumerate(payload, start=1):
        if isinstance(entry, str):
            rid = entry
        elif isinstance(entry, dict) and isinstance(entry.get("id"), str):
            rid = str(entry["id"])
        else:
            return None, f"entry {index} must be a rule id string or object with string id"
        if not rid.strip():
            return None, f"entry {index} has empty rule id"
        ids.append(rid)
    duplicates = sorted({rid for rid in ids if ids.count(rid) > 1})
    if duplicates:
        return None, "duplicate autofix rule id(s): " + ", ".join(duplicates)
    return ids, None


def _validate_autofix_registry(
    binary: str,
    manifest_path: Path,
    rules: list[dict[str, object]],
    registry_path: Path | None,
) -> list[tuple[str, str]] | None:
    registry = registry_path or _default_registry_path(manifest_path)
    ids, error = _read_registry(registry)
    if error:
        return [("<registry>", error)]
    if ids is None:
        return None
    by_id = {str(rule.get("id")): rule for rule in rules}
    failures: list[tuple[str, str]] = []
    for rid in ids:
        entry = by_id.get(rid)
        if entry is None:
            failures.append((rid, "registered rule has no manifest entry"))
            continue
        yaml_text = entry.get("yaml")
        if not isinstance(yaml_text, str) or not FIX_RE.search(yaml_text):
            failures.append((rid, "registered rule must include ast-grep fix:"))
            continue
        examples = entry.get("fixExamples")
        if not isinstance(examples, list) or not examples:
            failures.append((rid, "registered rule must declare non-empty fixExamples"))
            continue
        rule_path = _write_yaml(yaml_text)
        try:
            for index, example in enumerate(examples, start=1):
                if not isinstance(example, dict):
                    failures.append((rid, f"fixExamples[{index}] must be an object"))
                    continue
                before = example.get("input")
                after = example.get("output")
                if not isinstance(before, str) or not isinstance(after, str):
                    failures.append((rid, f"fixExamples[{index}] needs string input and output"))
                    continue
                if _needs_fix_examples(before, after):
                    failures.append((rid, f"fixExamples[{index}] needs examples"))
                    continue
                reason = _check_fix_example(binary, rule_path, before, after)
                if reason:
                    failures.append((rid, f"fixExamples[{index}] {reason}"))
        finally:
            try:
                os.unlink(rule_path)
            except OSError:
                pass
    return failures


def _kt_fixture_text(text: str) -> str:
    return text if text.strip().startswith("package") else "package probe\n\n" + text


def _check_fix_example(binary: str, rule_path: str, before: str, after: str) -> str | None:
    code_path = _write_kt(before)
    try:
        completed = _apply_fix(binary, rule_path, code_path)
        if completed.returncode not in (0, 1):
            return "fix command failed"
        fixed = Path(code_path).read_text(encoding="utf-8")
        expected = _kt_fixture_text(after)
        if fixed != expected:
            return "output did not match expected fixed code"
        second = _apply_fix(binary, rule_path, code_path)
        if second.returncode not in (0, 1):
            return "second fix command failed"
        fixed_again = Path(code_path).read_text(encoding="utf-8")
        if fixed_again != fixed:
            return "fix is not idempotent"
        rc, count, err = _scan(binary, rule_path, code_path)
        if not _parses(err, rc):
            return "fixed output did not parse for clean re-scan"
        if count:
            return f"fixed output still matches x{count}"
        return None
    finally:
        try:
            os.unlink(code_path)
        except OSError:
            pass


def _apply_fix(binary: str, rule_path: str, code_path: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [binary, "scan", "--rule", rule_path, "--update-all", code_path],
        capture_output=True,
        text=True,
        timeout=10,
    )


def apply_autofix_mode(binary: str, registry_path: Path, targets: list[str]) -> int:
    ids, error = _read_registry(registry_path)
    if error:
        print(f"AUTOFIX FAIL: {error}")
        return 2
    if ids is None:
        return 2
    if not ids:
        print("autofix registry OK: no registered rules")
        return 0
    if not targets:
        print("ERROR: --apply-autofix requires at least one target path")
        return 2

    rules_dir = registry_path.resolve().parent / "kotlin"
    applied: list[tuple[str, int]] = []
    failures: list[tuple[str, str]] = []
    for rid in ids:
        rule_path = rules_dir / f"{rid}.yaml"
        if not rule_path.is_file():
            failures.append((rid, f"rule file not found: {rule_path}"))
            continue
        before = _count_tree_hits(binary, rule_path, targets)
        if before < 1:
            continue
        completed = subprocess.run(
            [binary, "scan", "--rule", str(rule_path), "--update-all", *targets],
            capture_output=True,
            text=True,
            timeout=30,
        )
        if completed.returncode not in (0, 1):
            failures.append((rid, "fix command failed"))
            continue
        after = _count_tree_hits(binary, rule_path, targets)
        if after:
            failures.append((rid, f"fix left {after} matching site(s)"))
        else:
            applied.append((rid, before))
    if failures:
        print(f"AUTOFIX FAIL: {len(failures)} issue(s)")
        for rid, why in failures:
            print(f"  - {rid}: {why}")
        return 1
    if applied:
        print("AUTOFIX APPLIED — review and restage the changed files before committing:")
        for rid, count in applied:
            print(f"  - {rid}: fixed {count} site(s)")
        return 1
    print("autofix registry OK: no fixes needed")
    return 0


def _count_tree_hits(binary: str, rule_path: Path, targets: list[str]) -> int:
    completed = subprocess.run(
        [binary, "scan", "--rule", str(rule_path), *targets, "--json=compact"],
        capture_output=True,
        text=True,
        timeout=30,
    )
    raw = (completed.stdout or "").strip()
    if not raw:
        return 0
    try:
        payload = json.loads(raw)
    except json.JSONDecodeError:
        return 0
    return len(payload) if isinstance(payload, list) else 0


def _needs_fix_examples(before: object, after: object) -> bool:
    return (
        not isinstance(before, str)
        or not isinstance(after, str)
        or before.strip() in {"", STUB_FIX_INPUT}
        or after.strip() in {"", STUB_FIX_OUTPUT}
    )


def new_entry_mode(rule_id: str, rules_dir: Path, include_fix: bool = False) -> int:
    rule_path = rules_dir / f"{rule_id}.yaml"
    try:
        yaml_text = rule_path.read_text(encoding="utf-8")
    except OSError as exc:
        print(f"ERROR: cannot read rule file {rule_path}: {exc}", file=sys.stderr)
        return 2
    entry = {
        "id": rule_id,
        "severity": _severity_for_rule(yaml_text),
        "yaml": _yaml_with_fix_stub(yaml_text) if include_fix else yaml_text,
        "badExample": STUB_BAD_EXAMPLE,
        "goodExample": STUB_GOOD_EXAMPLE,
    }
    if include_fix:
        entry["fixExamples"] = [
            {
                "input": STUB_FIX_INPUT,
                "output": STUB_FIX_OUTPUT,
            }
        ]
    print(json.dumps(entry, indent=2))
    return 0


def _yaml_with_fix_stub(yaml_text: str) -> str:
    if FIX_RE.search(yaml_text):
        return yaml_text
    separator = "" if yaml_text.endswith("\n") else "\n"
    return f"{yaml_text}{separator}fix: |-\n  {STUB_FIX_TEMPLATE}\n"


def _severity_for_rule(yaml_text: str) -> str:
    for line in yaml_text.splitlines():
        stripped = line.strip()
        if stripped.startswith("severity:"):
            return stripped.split(":", 1)[1].strip().strip("\"'")
    return "warning"


def main() -> int:
    args = sys.argv[1:]
    if args and args[0] == "--new":
        if len(args) < 2:
            print("ERROR: --new requires a rule id")
            return 2
        return new_entry_mode(args[1], Path(__file__).resolve().parent / "kotlin", "--fix" in args[2:])

    binary = ast_grep_binary()
    if binary is None:
        print("ERROR: ast-grep not found on PATH")
        return 2
    if args and args[0] == "--manifest":
        if len(args) < 2:
            print("ERROR: --manifest requires a path")
            return 2
        registry_path = _option_path(args[2:], "--autofix-registry")
        return semantic_mode(binary, Path(args[1]), registry_path)
    if args and args[0] == "--hunk-mode":
        if len(args) < 2:
            print("ERROR: --hunk-mode requires a path")
            return 2
        return hunk_mode(binary, Path(args[1]))
    if args and args[0] == "--apply-autofix":
        if len(args) < 2:
            print("ERROR: --apply-autofix requires a registry path")
            return 2
        return apply_autofix_mode(binary, Path(args[1]), args[2:])
    rules_dir = Path(args[0]) if args else Path(__file__).resolve().parent / "kotlin"
    if not rules_dir.is_dir():
        print(f"ERROR: rules dir not found: {rules_dir}")
        return 2
    return parse_mode(binary, rules_dir)


def _option_path(args: list[str], option: str) -> Path | None:
    if option not in args:
        return None
    index = args.index(option)
    try:
        return Path(args[index + 1])
    except IndexError:
        print(f"ERROR: {option} requires a path")
        raise SystemExit(2)


if __name__ == "__main__":
    raise SystemExit(main())
