source "$GATE_FIXTURE_DIR/../beta-readiness-lib.sh"

# check_additional_gates delegates to six node tools, each of which owns (or should own)
# its detection fixture under tools/gate-fixtures/. Per the dedupe law this fixture tests
# only the AGGREGATION: every child exit code is consulted, one red child reds the gate,
# and a missing child cannot pass. The children here are stubs by design.

stubs=(
  "check-public-api-since-budget.mjs"
  "check-architecture-budget.mjs"
  "check-provider-golden-coverage.mjs"
  "check-provider-capabilities.mjs"
  "check-parity-claims.mjs"
  "check-api-review.mjs"
)
for stub in "${stubs[@]}"; do
  printf 'console.log("%s ok (fixture stub)");\nprocess.exit(0);\n' "$stub" > "$tmp/tools/$stub"
done

if [ "$CASE_KIND" = "compliant" ]; then
  brc additional-gates
  exit 0
fi

# --- violation scenarios (self-judging; see beta-readiness-lib.sh) ---

# 1. LAST child red: proves the loop consults every exit code, not just an early one.
printf 'console.error("api review red (fixture stub)");\nprocess.exit(1);\n' > "$tmp/tools/check-api-review.mjs"
if brc additional-gates; then
  echo "FAIL-OPEN: red final delegate swallowed" >&2
  exit 0
fi

# 2. Child missing entirely: node exits nonzero on a nonexistent script; the gate must
#    treat that as red, never as "nothing to check".
printf 'console.log("ok");\nprocess.exit(0);\n' > "$tmp/tools/check-api-review.mjs"
rm "$tmp/tools/check-parity-claims.mjs"
if brc additional-gates; then
  echo "FAIL-OPEN: missing delegate passed" >&2
  exit 0
fi

exit 1
