source "$GATE_FIXTURE_DIR/../beta-readiness-lib.sh"

good_workflow() {
  mkdir -p "$tmp/.github/workflows"
  cat > "$tmp/.github/workflows/ci.yml" <<'YAML'
name: CI
on: push
permissions:
  contents: read
jobs:
  build:
    runs-on: ubuntu-latest
    timeout-minutes: 10
    steps:
      - uses: actions/checkout@df4cb1c069e1874edd31b4311f1884172cec0e10 # v6.0.3
        with:
          persist-credentials: false
      - run: echo ok
YAML
}

if [ "$CASE_KIND" = "compliant" ]; then
  good_workflow
  brc workflows
  exit 0
fi

# --- violation scenarios (self-judging; see beta-readiness-lib.sh) ---

# 1. Zero-scan: an empty workflows dir must FAIL, not report four oks. This is the exact
#    hole the hand-listed [ci.yml, release.yml] era had, generalized.
mkdir -p "$tmp/.github/workflows"
if brc workflows; then
  echo "FAIL-OPEN: zero workflows scanned but the sub-check passed" >&2
  exit 0
fi

# 2. Planted violations: unpinned action, no timeout-minutes, credential-persisting
#    checkout, global npm install — one workflow tripping all four checks.
cat > "$tmp/.github/workflows/ci.yml" <<'YAML'
name: CI
on: push
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - run: npm install -g something
YAML
if brc workflows; then
  echo "FAIL-OPEN: planted workflow violations passed" >&2
  exit 0
fi

exit 1
