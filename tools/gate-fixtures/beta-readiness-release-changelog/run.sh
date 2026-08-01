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

# 3. Release-LINE checks: the promoted branch must agree with the kind of version, even
#    when the changelog entry exists (the GITHUB_REF_NAME branch, invisible outside CI).
#    Replaced the old v*-tag/version-mismatch scenario when releases moved from tags to
#    promotion (git push origin main:beta / merge main -> prod) — that scenario tested a
#    check that no longer exists, so it would have passed vacuously forever.
cat > "$tmp/CHANGELOG.md" <<'MD'
# Changelog

## 9.9.9-fixture

- fixture entry
MD
# VERSION_NAME here is 9.9.9-fixture: not a prerelease, so promoting it to `beta` is wrong.
if GITHUB_REF_NAME=beta brc release-changelog; then
  echo "FAIL-OPEN: a final version promoted to the beta line passed" >&2
  exit 0
fi

# ...and the mirror: a prerelease promoted to `prod`.
printf 'VERSION_NAME=9.9.9-rc1\n' > "$tmp/gradle.properties"
cat > "$tmp/CHANGELOG.md" <<'MD'
# Changelog

## 9.9.9-rc1

- fixture entry
MD
if GITHUB_REF_NAME=prod brc release-changelog; then
  echo "FAIL-OPEN: a prerelease promoted to the prod line passed" >&2
  exit 0
fi

exit 1
