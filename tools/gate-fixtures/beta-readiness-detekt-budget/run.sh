source "$GATE_FIXTURE_DIR/../beta-readiness-lib.sh"

# This sub-check DELEGATES to tools/check-detekt-baseline-budget, which has its own
# fixture (tools/gate-fixtures/detekt-baseline-budget) proving its detection logic. Per
# the dedupe law that logic is not re-tested here; what THIS fixture proves is the
# delegation itself fails closed: a delegate that exits nonzero — or does not exist —
# must fail the gate, never crash it or pass it.

if [ "$CASE_KIND" = "compliant" ]; then
  cat > "$tmp/tools/check-detekt-baseline-budget" <<'SH'
#!/usr/bin/env bash
echo "detekt baseline budget OK (fixture stub)"
exit 0
SH
  chmod +x "$tmp/tools/check-detekt-baseline-budget"
  brc detekt-budget
  exit 0
fi

# --- violation scenarios (self-judging; see beta-readiness-lib.sh) ---

# 1. Missing delegate: this used to raise FileNotFoundError out of the whole gate,
#    aborting every later check — the run() guard now converts it to a plain failure.
if brc detekt-budget; then
  echo "FAIL-OPEN: missing delegate tool but the sub-check passed" >&2
  exit 0
fi

# 2. Delegate reports red: the gate must propagate it.
cat > "$tmp/tools/check-detekt-baseline-budget" <<'SH'
#!/usr/bin/env bash
echo "budget exceeded (fixture stub)" >&2
exit 1
SH
chmod +x "$tmp/tools/check-detekt-baseline-budget"
if brc detekt-budget; then
  echo "FAIL-OPEN: red delegate exit swallowed" >&2
  exit 0
fi

exit 1
