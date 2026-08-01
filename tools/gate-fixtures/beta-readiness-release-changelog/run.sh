source "$GATE_FIXTURE_DIR/../beta-readiness-lib.sh"

printf 'VERSION_NAME=9.9.9-fixture\n' > "$tmp/gradle.properties"

if [ "$CASE_KIND" = "compliant" ]; then
  cat > "$tmp/CHANGELOG.md" <<'MD'
# Changelog

## 9.9.9-fixture

- fixture entry
MD
  brc release-changelog
  exit 0
fi

# --- violation scenarios (self-judging; see beta-readiness-lib.sh) ---

# 1. CHANGELOG.md missing entirely — historically this crashed the gate (FileNotFoundError)
#    instead of failing it; the site now reports it as a plain failure.
if brc release-changelog; then
  echo "FAIL-OPEN: missing CHANGELOG.md passed" >&2
  exit 0
fi

# 2. CHANGELOG present but with no entry for VERSION_NAME.
cat > "$tmp/CHANGELOG.md" <<'MD'
# Changelog

## 0.0.1

- unrelated release
MD
if brc release-changelog; then
  echo "FAIL-OPEN: version without a changelog entry passed" >&2
  exit 0
fi

# 3. Tag-context checks: a v* tag disagreeing with VERSION_NAME must fail even when the
#    changelog entry exists (the branch GITHUB_REF_NAME selects, invisible outside CI).
cat > "$tmp/CHANGELOG.md" <<'MD'
# Changelog

## 9.9.9-fixture

- fixture entry
MD
if GITHUB_REF_NAME=v1.2.3 brc release-changelog; then
  echo "FAIL-OPEN: tag/version mismatch passed" >&2
  exit 0
fi

exit 1
