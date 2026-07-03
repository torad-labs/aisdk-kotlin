set -euo pipefail

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

mkdir -p "$tmp/tools" "$tmp/src/commonMain/kotlin/ai/torad/aisdk/providers" "$tmp/docs"
cp "$REPO_ROOT/tools/check-provider-capabilities.mjs" "$tmp/tools/check-provider-capabilities.mjs"
printf 'public class FixtureProvider\n' > "$tmp/src/commonMain/kotlin/ai/torad/aisdk/providers/FixtureProvider.kt"
tools_value=no
if [ "$CASE_KIND" = "violation" ]; then
  tools_value=maybe
fi
cat > "$tmp/docs/provider-capability-matrix.md" <<EOF
| Provider class | Factory/package surface | Language generate/stream | Tools | Structured output | Images/files | Embeddings | Speech/transcription/video | Provider-executed tools | Response metadata/usage | Retry/error envelope | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| FixtureProvider | Fixture() | yes | $tools_value | partial | n/a | no | no | n/a | yes | yes | fixture |
EOF

(
  cd "$tmp"
  node tools/check-provider-capabilities.mjs
)
