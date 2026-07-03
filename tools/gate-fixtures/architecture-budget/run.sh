set -euo pipefail

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

mkdir -p "$tmp/tools" "$tmp/src/commonMain/kotlin" "$tmp/src/commonTest/kotlin"
cp "$REPO_ROOT/tools/check-architecture-budget.mjs" "$tmp/tools/check-architecture-budget.mjs"
printf '{"schemaVersion":1,"maxLinesByFile":{}}\n' > "$tmp/architecture-budget.json"
if [ "$CASE_KIND" = "violation" ]; then
  for i in $(seq 1 500); do printf '// line %s\n' "$i"; done > "$tmp/src/commonMain/kotlin/Large.kt"
else
  printf 'public class Small\n' > "$tmp/src/commonMain/kotlin/Small.kt"
fi

(
  cd "$tmp"
  node tools/check-architecture-budget.mjs
)
