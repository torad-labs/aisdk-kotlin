#!/usr/bin/env bash
# Repo architecture gate — runs the SAME ast-grep rules the Claude PreToolUse hook
# enforces, but on the whole tree at commit/CI time, so they apply to EVERY commit
# (human or agent), not just Claude's edits. Plus whole-program structural checks
# for the cross-file classes a per-file hook can't see.
#
# Used by .githooks/pre-commit (local) and ci.yml verify job (non-bypassable).
# Exit 0 = clean, 1 = violation. Pure ast-grep + python; no model.
# NOTE: no `pipefail` — `ast-grep scan` exits 1 when it FINDS matches (grep-style),
# which combined with a `|| fallback` would make a found-violation read as zero
# (fake-green). Keep scan and the JSON count as separate steps.
set -u

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
cd "$ROOT"
RULES_DIR=".claude/hooks/rules/kotlin"
if [ -z "${AG:-}" ]; then
  if [ -x "$ROOT/node_modules/.bin/ast-grep" ]; then
    AG="$ROOT/node_modules/.bin/ast-grep"
  else
    AG="$(command -v ast-grep || echo "$HOME/.local/bin/ast-grep")"
  fi
fi
[ -x "$AG" ] || { echo "ci-gate: ast-grep not found"; exit 2; }
expected_ag_version="$(node -e "const v = require('./package.json').devDependencies['@ast-grep/cli']; if (!v) process.exit(1); console.log(v)")" || {
  echo "ci-gate: cannot read package.json @ast-grep/cli version"
  exit 2
}
actual_ag_version="$("$AG" --version 2>/dev/null | tr ' ' '\n' | grep -E '^[0-9]+[.][0-9]+[.][0-9]+$' | tail -1)"
if [ "$actual_ag_version" != "$expected_ag_version" ]; then
  echo "ci-gate: ast-grep version mismatch: $AG reports ${actual_ag_version:-unknown}, package.json pins @ast-grep/cli=$expected_ag_version."
  echo "Use the GH-13-pinned ast-grep version locally (for example, run npm ci) or update package.json together with the CI AST_GREP_VERSION/AST_GREP_SHA256 pin."
  exit 1
fi

fail=0

count() {
  local json
  json="$("$AG" scan --rule "$1" $2 --json=compact 2>/dev/null || true)"
  [ -z "$json" ] && { echo 0; return; }
  printf '%s' "$json" | python3 -c 'import json,sys
try: print(len(json.load(sys.stdin)))
except Exception: print(0)'
}

echo "== architecture gate: error-severity ast-grep rules =="
for f in "$RULES_DIR"/*.yaml; do
  base=$(basename "$f" .yaml)
  # Honor the `disabled_` convention (same as the Claude PreToolUse policy): staged
  # rules are not enforced until renamed to activate.
  case "$base" in disabled_*) continue ;; esac
  sev=$(grep -m1 '^severity:' "$f" | cut -d' ' -f2)
  [ "$sev" = "error" ] || continue
  # JVM-platform rules are legitimate in JVM source sets — scope them out (matches the policy).
  case "$base" in
    no-java-import|no-thread-sleep|no-string-format|no-print-stack-trace)
      dirs="src/commonMain/kotlin src/nativeMain/kotlin" ;;
    no-camelcase-top-level-function)
      dirs="src/commonMain/kotlin src/jvmMain/kotlin src/jvmAndAndroidMain/kotlin src/nativeMain/kotlin src/commonTest/kotlin" ;;
    *)
      dirs="src/commonMain/kotlin src/jvmMain/kotlin src/jvmAndAndroidMain/kotlin src/nativeMain/kotlin" ;;
  esac
  n=$(count "$f" "$dirs")
  if [ "$n" -gt 0 ] 2>/dev/null; then
    echo "  FAIL $base: $n violation(s)"
    "$AG" scan --rule "$f" $dirs 2>/dev/null | head -8
    fail=1
  fi
done
[ "$fail" = 0 ] && echo "  ok: 0 error-rule violations"

echo "== cancellation correctness warning report =="
warning_dirs="src/commonMain/kotlin src/jvmMain/kotlin src/jvmAndAndroidMain/kotlin src/nativeMain/kotlin"
ag_name=AG
ag_path="${!ag_name}"
for warning_rule in no-throwable-catch-without-rethrow no-runcatching-in-suspend; do
  warning_file=".claude/hooks/rules/kotlin/$warning_rule.yaml"
  n=$(count "$warning_file" "$warning_dirs")
  echo "  $warning_rule: $n warning(s)"
  if [ "$n" -gt 0 ] 2>/dev/null; then
    "$ag_path" scan --rule "$warning_file" $warning_dirs 2>/dev/null | head -12
  fi
done

echo "== non-integrated (internal, cross-file) gate =="
python3 .claude/hooks/rules/detect-nonintegrated-kotlin.py src --check || fail=1

echo "== ast-grep rule self-test gate =="
python3 .claude/hooks/rules/validate_rules.py "$RULES_DIR" || fail=1
python3 .claude/hooks/rules/validate_rules.py --manifest .claude/hooks/rules/manifest.json --autofix-registry .claude/hooks/rules/autofix-registry.json || fail=1
python3 .claude/hooks/rules/validate_rules.py --hunk-mode .claude/hooks/rules/manifest.json || fail=1
echo "== ast-grep autofix pre-pass =="
python3 .claude/hooks/rules/validate_rules.py --apply-autofix .claude/hooks/rules/autofix-registry.json src/commonMain/kotlin src/commonTest/kotlin || exit 1
node tools/run-gate-fixtures.mjs || fail=1

echo "== consumer migration rule gate =="
python3 .claude/hooks/rules/validate_migration_rules.py docs/migrations || fail=1

echo "== python guard-rule gate =="
python3 .claude/hooks/rules/validate_python_guard_rules.py || fail=1

echo "== restated measurement warning report =="
python3 .claude/hooks/rules/detect-restated-measurements.py || fail=1

echo "== orphan gate detector =="
python3 .claude/hooks/rules/detect-orphan-gates.py || fail=1

echo "== tool occurrence identity gate =="
python3 .claude/hooks/rules/detect-tool-identity-regressions.py src/commonMain/kotlin --check || fail=1

echo "== public data-class budget gate =="
python3 .claude/hooks/rules/detect-public-data-class-budget.py src/commonMain/kotlin --check || fail=1

echo "== beta readiness gate =="
tools/beta-readiness-check || fail=1

echo "== release workflow trust gate =="
python3 .claude/hooks/rules/detect-release-workflow-trust.py .github/workflows/release.yml --check || fail=1

if [ "$fail" != 0 ]; then
  echo ""
  echo "ARCHITECTURE GATE FAILED — fix the violations above (do not bypass)."
  echo "See CLAUDE.md 'Gate misfires — fix the gate, not the result' for the repair protocol."
  exit 1
fi
echo "architecture gate: PASS"
