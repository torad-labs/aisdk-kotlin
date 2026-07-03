set -euo pipefail

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

mkdir -p "$tmp/tools" "$tmp/src/commonMain/kotlin/ai/torad/aisdk/providers" "$tmp/src/commonTest/kotlin/ai/torad/aisdk" "$tmp/docs"
cp "$REPO_ROOT/tools/check-provider-golden-coverage.mjs" "$tmp/tools/check-provider-golden-coverage.mjs"
printf 'public class FixtureProvider\n' > "$tmp/src/commonMain/kotlin/ai/torad/aisdk/providers/FixtureProvider.kt"
printf 'class FixtureProviderTest\n' > "$tmp/src/commonTest/kotlin/ai/torad/aisdk/FixtureProviderTest.kt"
stream_reference=FixtureProviderTest.kt
if [ "$CASE_KIND" = "violation" ]; then
  stream_reference=MissingProviderTest.kt
fi
cat > "$tmp/docs/provider-golden-coverage.json" <<EOF
{
  "schemaVersion": 1,
  "providers": {
    "FixtureProvider": {
      "requestGolden": "FixtureProviderTest.kt",
      "streamGolden": "$stream_reference"
    }
  }
}
EOF

(
  cd "$tmp"
  node tools/check-provider-golden-coverage.mjs
)
