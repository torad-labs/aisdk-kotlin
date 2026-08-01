set -euo pipefail

# Proves the workflow-lint gate's ANALYZER FLAGS still detect what the gate claims,
# independently of whether today's workflows happen to be clean.
#
# Both flags in that gate had been silently disabling an entire analyzer:
#   -shellcheck=            turned shellcheck OFF (empty string = disabled), including
#                           SC1072-class shell SYNTAX errors, while the step comment said
#                           infos "stay as annotations".
#   --min-confidence=high   dropped the dangerous-triggers audit (High severity / MEDIUM
#                           confidence) — the pull_request_target pwn-request class. A
#                           canonical pwn-request exited 0 and printed "No findings to
#                           report. Good job!" under those flags.
#
# Both tools are optional here: the gate itself pins checksummed binaries in CI, and a
# fixture that silently passes when a tool is missing would be the very fail-open shape
# this harness exists to prevent — so a MISSING tool fails the violation case loudly
# rather than skipping it.

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/.github/workflows"
# actionlint refuses to run outside a git repo ("no project was found in any parent
# directories"). Make the scratch tree one — it must NOT inherit the live repo.
git -C "$tmp" init -q

pwn_request() {
  cat > "$tmp/.github/workflows/probe.yml" <<'YAML'
name: probe
on: pull_request_target
permissions:
  contents: write
jobs:
  build:
    runs-on: ubuntu-latest
    timeout-minutes: 5
    steps:
      - uses: actions/checkout@df4cb1c069e1874edd31b4311f1884172cec0e10 # v6.0.3
        with:
          ref: ${{ github.event.pull_request.head.sha }}
      - run: ./gradlew build
YAML
}

shell_syntax_error() {
  cat > "$tmp/.github/workflows/probe.yml" <<'YAML'
name: probe
on: push
permissions:
  contents: read
jobs:
  build:
    runs-on: ubuntu-latest
    timeout-minutes: 5
    steps:
      - name: broken shell
        run: |
          if [ "$UNCLOSED" = x ; then
            echo never
          fi
YAML
}

clean_workflow() {
  cat > "$tmp/.github/workflows/probe.yml" <<'YAML'
name: probe
on: push
permissions:
  contents: read
jobs:
  build:
    runs-on: ubuntu-latest
    timeout-minutes: 5
    steps:
      - uses: actions/checkout@df4cb1c069e1874edd31b4311f1884172cec0e10 # v6.0.3
        with:
          persist-credentials: false
      - name: quoted and safe
        run: |
          set -euo pipefail
          target="${HOME}/x"
          echo "using ${target}"
YAML
}

# The exact commands the gate runs (kept in sync with .github/workflows/workflow-lint.yml).
run_actionlint() { (cd "$tmp" && actionlint -ignore 'SC2016:'); }
run_zizmor()     { zizmor --min-severity=high "$tmp/.github/workflows"; }

if [ "$CASE_KIND" = "compliant" ]; then
  clean_workflow
  command -v actionlint >/dev/null 2>&1 && run_actionlint
  command -v zizmor >/dev/null 2>&1 && run_zizmor
  exit 0
fi

# --- violation scenarios (self-judging) ---

if ! command -v zizmor >/dev/null 2>&1; then
  echo "zizmor not installed — cannot prove the dangerous-triggers audit is reachable" >&2
  exit 0
fi
pwn_request
if run_zizmor >/dev/null 2>&1; then
  echo "FAIL-OPEN: zizmor flags do not report a pull_request_target pwn-request" >&2
  exit 0
fi

if ! command -v actionlint >/dev/null 2>&1; then
  echo "actionlint not installed — cannot prove shellcheck is reachable" >&2
  exit 0
fi
shell_syntax_error
if run_actionlint >/dev/null 2>&1; then
  echo "FAIL-OPEN: actionlint flags do not report a shell syntax error (shellcheck off?)" >&2
  exit 0
fi

exit 1
