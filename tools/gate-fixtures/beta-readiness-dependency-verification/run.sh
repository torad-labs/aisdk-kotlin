source "$GATE_FIXTURE_DIR/../beta-readiness-lib.sh"

if [ "$CASE_KIND" = "compliant" ]; then
  mkdir -p "$tmp/gradle"
  cat > "$tmp/gradle/verification-metadata.xml" <<'XML'
<?xml version="1.0" encoding="UTF-8"?>
<verification-metadata>
  <components>
    <component group="g" name="a" version="1">
      <artifact name="a-1.jar">
        <sha256 value="0000000000000000000000000000000000000000000000000000000000000000"/>
      </artifact>
    </component>
  </components>
</verification-metadata>
XML
  brc dependency-verification
  exit 0
fi

# --- violation scenarios (self-judging; see beta-readiness-lib.sh) ---

# 1. Metadata file absent entirely.
if brc dependency-verification; then
  echo "FAIL-OPEN: missing verification-metadata.xml passed" >&2
  exit 0
fi

# 2. File present but carries no checksums — verification that verifies nothing.
mkdir -p "$tmp/gradle"
printf '<verification-metadata></verification-metadata>\n' > "$tmp/gradle/verification-metadata.xml"
if brc dependency-verification; then
  echo "FAIL-OPEN: checksum-free metadata passed" >&2
  exit 0
fi

exit 1
