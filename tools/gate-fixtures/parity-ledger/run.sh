set -euo pipefail

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

mkdir -p "$tmp/tools" "$tmp/.reference/vercel-ai-sdk-ai-6.0.208/packages/ai/src"
cp "$REPO_ROOT/tools/generate-parity-ledger.mjs" "$tmp/tools/generate-parity-ledger.mjs"
cat > "$tmp/.reference/vercel-ai-sdk-ai-6.0.208/packages/ai/package.json" <<'JSON'
{
  "name": "ai",
  "version": "6.0.208",
  "exports": {
    ".": {
      "types": "./dist/index.d.ts"
    }
  }
}
JSON
cat > "$tmp/.reference/vercel-ai-sdk-ai-6.0.208/packages/ai/src/index.ts" <<'TS'
export function generateText(): string {
  return "ok";
}
TS

(
  cd "$tmp"
  if [ "$CASE_KIND" = "compliant" ]; then
    node tools/generate-parity-ledger.mjs >/dev/null
  else
    mkdir -p docs/parity
    printf 'stale\n' > docs/parity/README.md
  fi
  node tools/generate-parity-ledger.mjs --check
)
