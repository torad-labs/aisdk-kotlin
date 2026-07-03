set -euo pipefail

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

mkdir -p "$tmp/tools" "$tmp/api/jvm" "$tmp/docs/parity"
cp "$REPO_ROOT/tools/check-parity-claims.mjs" "$tmp/tools/check-parity-claims.mjs"
printf 'public final class ExistingSymbol\n' > "$tmp/api/torad-aisdk.klib.api"
printf 'public final class ExistingSymbol\n' > "$tmp/api/jvm/torad-aisdk.api"
if [ "$CASE_KIND" = "violation" ]; then
  printf 'Current parity status: ported: valibotSchema is represented as a Kotlin public API\n' > "$tmp/docs/parity/fixture.md"
else
  printf 'Current parity status: not-ported: valibotSchema is intentionally absent from the Kotlin public API\n' > "$tmp/docs/parity/fixture.md"
fi

(
  cd "$tmp"
  node tools/check-parity-claims.mjs
)
