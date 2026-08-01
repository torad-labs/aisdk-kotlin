source "$GATE_FIXTURE_DIR/../beta-readiness-lib.sh"

if [ "$CASE_KIND" = "compliant" ]; then
  mkdir -p "$tmp/src/commonMain/kotlin"
  cat > "$tmp/src/commonMain/kotlin/Wire.kt" <<'KT'
internal fun encode(value: Thing): String = aiSdkOutputJson.encodeToString(Thing.serializer(), value)
KT
  brc lenient-codec
  exit 0
fi

# --- violation scenarios (self-judging; see beta-readiness-lib.sh) ---

# 1. Zero-scan: no Kotlin sources at all must FAIL — "no offenders in nothing" is the
#    wrong-tree failure mode, not a clean tree.
if brc lenient-codec; then
  echo "FAIL-OPEN: zero Kotlin files scanned but the sub-check passed" >&2
  exit 0
fi

# 2. Planted offender: the decode-lenient instance used on the encode path.
mkdir -p "$tmp/src/commonMain/kotlin"
cat > "$tmp/src/commonMain/kotlin/Wire.kt" <<'KT'
internal fun encode(value: Thing): String = aiSdkJson.encodeToString(Thing.serializer(), value)
KT
if brc lenient-codec; then
  echo "FAIL-OPEN: aiSdkJson.encode offender passed" >&2
  exit 0
fi

exit 1
