#!/usr/bin/env bash
# Open (or comment on) a GitHub issue when a scheduled CI job fails.
#
#   tools/open-ci-failure-issue.sh "ci/verify"
#
# Dedups by exact title match against open issues labeled `ci-failure`. Recurring
# failures get a comment with the new run URL instead of issue spam. Requires
# GH_TOKEN with issues:write and a repo that already has (or can create) the
# `ci` + `ci-failure` labels.
set -euo pipefail

scope="${1:?usage: open-ci-failure-issue.sh <scope-label>}"
: "${GH_TOKEN:?GH_TOKEN is required}"
: "${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is required}"
: "${GITHUB_SERVER_URL:?GITHUB_SERVER_URL is required}"
: "${GITHUB_RUN_ID:?GITHUB_RUN_ID is required}"
: "${GITHUB_WORKFLOW:?GITHUB_WORKFLOW is required}"
: "${GITHUB_SHA:?GITHUB_SHA is required}"

run_url="${GITHUB_SERVER_URL}/${GITHUB_REPOSITORY}/actions/runs/${GITHUB_RUN_ID}"
title="[ci] scheduled failure: ${scope}"
body="$(cat <<EOF
### Scheduled workflow failure

- **Workflow:** ${GITHUB_WORKFLOW}
- **Scope:** \`${scope}\`
- **Run:** ${run_url}
- **SHA:** \`${GITHUB_SHA}\`

This issue was opened automatically so a silent red schedule cannot go unnoticed.
Any comment / fix commit removes the need for a new issue; recurred failures comment here instead.
EOF
)"

# Ensure labels exist (ignore already-exists).
gh label create ci --color "6f42c1" --description "CI, gates, workflows" 2>/dev/null || true
gh label create ci-failure --color "b60205" --description "Scheduled CI failure (auto-opened)" 2>/dev/null || true

existing="$(
  gh issue list \
    --repo "$GITHUB_REPOSITORY" \
    --label ci-failure \
    --state open \
    --json number,title \
    --jq ".[] | select(.title == \"${title//\"/\\\"}\") | .number" \
    | head -n 1
)"

if [ -n "${existing}" ]; then
  gh issue comment "$existing" --repo "$GITHUB_REPOSITORY" --body "Recurred: ${run_url}"
  echo "commented on existing issue #${existing}"
  exit 0
fi

url="$(
  gh issue create \
    --repo "$GITHUB_REPOSITORY" \
    --title "$title" \
    --label "ci,ci-failure" \
    --body "$body"
)"
echo "opened ${url}"
