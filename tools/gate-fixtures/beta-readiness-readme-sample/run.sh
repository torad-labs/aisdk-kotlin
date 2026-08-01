source "$GATE_FIXTURE_DIR/../beta-readiness-lib.sh"

if [ "$CASE_KIND" = "compliant" ]; then
  mkdir -p "$tmp/smoke-tests/jvm-consumer"
  printf '// compiled by the smoke build\n' > "$tmp/smoke-tests/jvm-consumer/ReadmeSampleSmoke.kt"
  cat > "$tmp/README.md" <<'MD'
# Fixture
<!-- beta-readiness:readme-sample:start -->
sample
<!-- beta-readiness:readme-sample:end -->
MD
  brc readme-sample
fi

# Violation: no *Readme*Sample* fixture anywhere (the hard failure; missing markers alone
# only warns outside --strict-readme, so they are not the planted defect here).
mkdir -p "$tmp/smoke-tests" "$tmp/src"
printf '# Fixture\n' > "$tmp/README.md"
brc readme-sample
