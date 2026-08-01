set -euo pipefail

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

# The checker imports the shared declaration regex, so the scratch tree needs both files
# at the same relative paths (tools/lib/ is the ONE definition the tagger also imports —
# testing a private copy here would un-share it).
mkdir -p "$tmp/tools/lib" "$tmp/src/commonMain/kotlin"
cp "$REPO_ROOT/tools/check-public-api-since-budget.mjs" "$tmp/tools/check-public-api-since-budget.mjs"
cp "$REPO_ROOT/tools/lib/public-api-declaration.mjs" "$tmp/tools/lib/public-api-declaration.mjs"
printf '{"schemaVersion":1,"totalMissingSince":0}\n' > "$tmp/api-since-budget.json"

gate() { (cd "$tmp" && node tools/check-public-api-since-budget.mjs); }

if [ "$CASE_KIND" = "compliant" ]; then
  # Tagged declarations across the once-invisible forms, plus the two DELIBERATE
  # exclusions (override, actual) left untagged — pinning both directions of the census.
  cat > "$tmp/src/commonMain/kotlin/Fixture.kt" <<'KT'
/**
 * Fixture API.
 *
 * @since 0.0.0
 */
public class Documented

/** @since 0.0.0 */
public suspend fun documentedSuspend(): Int = 1

/** @since 0.0.0 */
public fun <T> documentedGeneric(value: T): T = value

/** @since 0.0.0 */
public const val DOCUMENTED_VERSION: String = "1"

/** @since 0.0.0 */
public typealias DocumentedAlias = String

public override fun toString(): String = "excluded: @since lives on the overridden declaration"

public actual fun platformImpl(): Int = 1
KT
  gate
  exit 0
fi

# --- violation scenarios (self-judging: each once-invisible form must fail on its own;
# the script exits 0 the moment one slips through, which the harness reports as the
# census having gone blind to that form again) ---

forms=(
  'public class Undocumented'
  'public suspend fun undocumentedSuspend(): Int = 1'
  'public fun <T> undocumentedGeneric(value: T): T = value'
  'public const val UNDOCUMENTED_VERSION: String = "1"'
  'public typealias UndocumentedAlias = String'
  'public operator fun invoke(value: String): Int = 1'
  'public inline fun undocumentedInline(): Int = 1'
  'public fun interface UndocumentedSpi { public fun call(): Int }'
)
for form in "${forms[@]}"; do
  printf '%s\n' "$form" > "$tmp/src/commonMain/kotlin/Fixture.kt"
  if gate; then
    echo "FAIL-OPEN: census blind to declaration form: $form" >&2
    exit 0
  fi
done

exit 1
