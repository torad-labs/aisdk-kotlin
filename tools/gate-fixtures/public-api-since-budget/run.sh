set -euo pipefail

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

mkdir -p "$tmp/tools" "$tmp/src/commonMain/kotlin"
cp "$REPO_ROOT/tools/check-public-api-since-budget.mjs" "$tmp/tools/check-public-api-since-budget.mjs"
printf '{"schemaVersion":1,"totalMissingSince":0}\n' > "$tmp/api-since-budget.json"
if [ "$CASE_KIND" = "violation" ]; then
  printf 'public class Undocumented\n' > "$tmp/src/commonMain/kotlin/Fixture.kt"
else
  cat > "$tmp/src/commonMain/kotlin/Fixture.kt" <<'KT'
/**
 * Fixture API.
 *
 * @since 0.0.0
 */
public class Documented
KT
fi

(
  cd "$tmp"
  node tools/check-public-api-since-budget.mjs
)
