set -euo pipefail

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

mkdir -p "$tmp/tools" "$tmp/bin"
cp "$REPO_ROOT/tools/check-ai-sdk-reference.mjs" "$tmp/tools/check-ai-sdk-reference.mjs"
cat > "$tmp/bin/npm" <<'SH'
#!/usr/bin/env bash
printf '"0.0.0-fixture"\n'
SH
chmod +x "$tmp/bin/npm"

ref="$tmp/.reference/vercel-ai-sdk-ai-0.0.0-fixture"
mkdir -p "$ref/packages/ai"
printf '{"version":"0.0.0-fixture"}\n' > "$ref/packages/ai/package.json"
git -C "$ref" init --quiet
git -C "$ref" config user.email fixture@example.invalid
git -C "$ref" config user.name Fixture
git -C "$ref" add packages/ai/package.json
git -C "$ref" commit --quiet -m "reference"
actual_commit=$(git -C "$ref" rev-parse HEAD)

expected_commit="$actual_commit"
if [ "$CASE_KIND" = "violation" ]; then
  expected_commit=0000000000000000000000000000000000000000
fi

(
  cd "$tmp"
  PATH="$tmp/bin:$PATH" \
    AI_SDK_REFERENCE_VERSION=0.0.0-fixture \
    AI_SDK_REFERENCE_COMMIT="$expected_commit" \
    node tools/check-ai-sdk-reference.mjs
)
