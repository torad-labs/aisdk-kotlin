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
RULES_ROOT=".rules/kotlin/ast-grep"
RULES_DIR="$RULES_ROOT/rules"           # LAW rules (blocking, severity: error)
STYLE_DIR="$RULES_ROOT/rules-style"     # opt-in tenets (non-blocking, severity: warning)
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

echo "== architecture gate: error-severity ast-grep rules (LAW) =="
# Scan rules/ directory (LAW rules - blocking)
for f in "$RULES_DIR"/*.yaml; do
  [ -f "$f" ] || continue
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
    # Build-script rule: its subject IS build.gradle.kts, which no src/ dir contains. Without
    # this case the rule parses, validates against its fixture, reports "ok" — and scans
    # nothing. An inert rule is worse than an absent one because it reads as coverage.
    kmp-manual-refines-needs-hierarchy-template)
      dirs="build.gradle.kts" ;;
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
# A warning-severity rule that nothing prints is inert: it parses, validates against its
# fixture, and reports nothing about the real tree — coverage in name only.
#
# This loop used to hardcode FIVE rule names, so 5 of 77 style rules were ever scanned and the
# other 72 were exactly the "coverage in name only" the paragraph above forbids — three rules
# added the same day this was found were inert on arrival, and one of them had a live hit
# (MCP.kt's second setReader) that ci-gate reported as PASS. Iterate the directory instead: a
# rule cannot now be added to this lane and stay invisible.
#
# Counts are ratcheted by style-rule-count-budget.json so a warning rule BLOCKS on regression
# without red-gating pre-existing debt. A rule with no budget entry fails closed.
style_budget=".claude/hooks/rules/style-rule-count-budget.json"
python3 .claude/hooks/rules/check-style-rule-counts.py "$warning_dirs" || fail=1

echo "== non-integrated (internal, cross-file) gate =="
python3 .claude/hooks/rules/detect-nonintegrated-kotlin.py src --check || fail=1

echo "== ast-grep rule self-test gate =="
# Validate both LAW rules and style rules
python3 .claude/hooks/rules/validate_rules.py "$RULES_DIR" || fail=1
python3 .claude/hooks/rules/validate_rules.py "$STYLE_DIR" || fail=1
python3 .claude/hooks/rules/validate_rules.py --manifest .claude/hooks/rules/manifest.json --autofix-registry "$RULES_ROOT/registry.json" || fail=1
python3 .claude/hooks/rules/validate_rules.py --hunk-mode .claude/hooks/rules/manifest.json || fail=1
echo "== ast-grep autofix pre-pass =="
python3 .claude/hooks/rules/validate_rules.py --apply-autofix "$RULES_ROOT/registry.json" src/commonMain/kotlin src/commonTest/kotlin || exit 1

echo "== sealed-when codemod =="
# --check, like the sibling codemods: report violations and RESTORE the tree. Running it in
# applying mode here wrote rewrites into the working tree and then told the operator to stage
# whatever it produced — and a rewrite this codemod declines to make by hand is exactly the case
# where its output should not be blessed sight-unseen.
if [ -f "dev/codemods/fix_sealed_when.py" ]; then
  if ! output=$(python3 dev/codemods/fix_sealed_when.py --check src/commonMain 2>&1); then
    echo "$output" | grep -E "^(Would apply|  Replace|  SKIP|  UNRESOLVED)"
    echo "sealed-when codemod found else branches in sealed whens — expand them by review"
    fail=1
  fi
  # Coverage gaps are reported even on a passing run: a violation the codemod cannot resolve must
  # not look like an absence of violations.
  echo "$output" | grep -E "^  (SKIP|UNRESOLVED)" || true
fi
echo "sealed-when codemod OK: no fixes needed"

node tools/run-gate-fixtures.mjs || fail=1

echo "== consumer migration rule gate =="
python3 .claude/hooks/rules/validate_migration_rules.py docs/migrations || fail=1

echo "== python guard-rule gate =="
python3 .claude/hooks/rules/validate_python_guard_rules.py || fail=1

echo "== hook policy regression suite =="
# The PreToolUse policies ARE the edit-time enforcement layer, and nothing ran their tests at
# commit time. A manifest-drift check added to validate_rules broke test_rule_selfcheck_policy
# the moment it landed — it short-circuited the synthetic manifests those tests build — and the
# suite stayed red across three commits because only a human running it by hand would have seen
# it. Same-checker-twice: the gate now re-runs what the write-time layer relies on.
hook_suite_fail=0
hook_suite_count=0
for hook_test in .claude/hooks/tests/test_*.py; do
  [ -f "$hook_test" ] || continue
  hook_suite_count=$((hook_suite_count + 1))
  if ! output=$(python3 "$hook_test" 2>&1); then
    echo "  FAIL $(basename "$hook_test")"
    echo "$output" | tail -5
    hook_suite_fail=1
    fail=1
  fi
done
# Own status variable, not the shared `fail`: gating the OK line on `fail` meant an unrelated
# earlier failure silenced this section entirely, so a passing suite looked like a skipped one.
if [ "$hook_suite_fail" = 0 ]; then
  echo "hook policy suite OK: $hook_suite_count suite(s)"
fi

echo "== restated measurement detector =="
python3 .claude/hooks/rules/detect-restated-measurements.py --check || fail=1

echo "== orphan gate detector =="
python3 .claude/hooks/rules/detect-orphan-gates.py || fail=1

echo "== provider asset-download credential gate =="
python3 .claude/hooks/rules/detect-provider-asset-credential-leak.py || fail=1

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
