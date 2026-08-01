source "$GATE_FIXTURE_DIR/../beta-readiness-lib.sh"

required=(
  "tools/run-local-staging-smoke"
  "smoke-tests/local-staging/settings.gradle.kts"
  "smoke-tests/local-staging/jvm-consumer/build.gradle.kts"
  "smoke-tests/local-staging/kmp-consumer/build.gradle.kts"
  "smoke-tests/local-staging/android-consumer/build.gradle.kts"
  "smoke-tests/ios-swift/AiSdkSmoke.swift"
  "tools/run-ios-swift-smoke"
)
for path in "${required[@]}"; do
  mkdir -p "$tmp/$(dirname "$path")"
  printf '# fixture placeholder\n' > "$tmp/$path"
done

if [ "$CASE_KIND" = "violation" ]; then
  # The release smoke DRIVER going missing is the dangerous drop: the consumer builds
  # would silently stop being exercised while every individual build file still exists.
  rm "$tmp/tools/run-local-staging-smoke"
fi

brc staging-fixture
