# Shared setup for the beta-readiness-* gate fixtures (sourced, not executed — the
# harness only iterates DIRECTORIES under gate-fixtures/, so this file is invisible to it,
# same as README.md).
#
# Why these exist: tools/beta-readiness-check is one gate wrapping nine sub-checks, and
# for weeks none of them had a fixture — during which check_workflows scanned a hand-listed
# 2 of 10 workflows while printing ok. Per the repo's own LP-1 ("an unexercised check is
# indistinguishable from a broken one"), every sub-check now has a compliant/violation pair
# driven through `beta-readiness-check --only <name>`.
#
# Pattern: copy the real script into a scratch tree so its ROOT (parents[1] of __file__)
# resolves THERE, plant per-case content, run one sub-check. Never point it at the live repo.
#
# Violation scripts with several scenarios are SELF-JUDGING: each planted violation must
# make the gate exit nonzero; the script exits 0 the moment one slips through (the harness
# then reports "expected nonzero, got 0" — i.e. the gate has gone fail-open), and exits 1
# only after every scenario was correctly detected.

set -euo pipefail

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

mkdir -p "$tmp/tools"
cp "$REPO_ROOT/tools/beta-readiness-check" "$tmp/tools/beta-readiness-check"

# CI exports GITHUB_REF_NAME on tag builds; release-changelog branches on it. Fixtures
# must behave identically on a laptop and inside a v*-tag workflow run.
unset GITHUB_REF_NAME || true

brc() { # brc <only-name> — run one sub-check against the scratch tree
  python3 "$tmp/tools/beta-readiness-check" --only "$1"
}
