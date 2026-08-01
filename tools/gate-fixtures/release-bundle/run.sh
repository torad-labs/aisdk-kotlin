set -euo pipefail

# Proves tools/check-release-bundle detects an incomplete or unsigned publication set.
#
# The fixture builds a MINIMAL staged tree by hand rather than running a real Gradle
# publish: the checker's contract is about the shape of build/staging-deploy, and a
# fixture that needed a signing key and a 4-target Kotlin/Native build could not run in
# the harness at all. The real end-to-end shape was verified separately against an
# actual signed `publishAllPublicationsToLocalStagingRepository` run (7 modules, 38
# artifacts) before this gate was wired in.

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

mkdir -p "$tmp/tools"
cp "$REPO_ROOT/tools/check-release-bundle" "$tmp/tools/check-release-bundle"
cp "$REPO_ROOT/release-modules.json" "$tmp/release-modules.json"

VERSION="9.9.9-fixture"
GROUP_DIR="$tmp/staging/ai/torad"

# Build a complete, signed-looking staged tree for every expected module.
seed_module() {
  local module="$1" primary="$2"
  local dir="$GROUP_DIR/$module/$VERSION"
  mkdir -p "$dir"
  local files=("$module-$VERSION.pom" "$module-$VERSION.module")
  [ -n "$primary" ] && files+=("$module-$VERSION.$primary")
  for f in "${files[@]}"; do
    printf 'fixture\n' > "$dir/$f"
    printf 'signature\n' > "$dir/$f.asc"
  done
}

seed_all() {
  rm -rf "$GROUP_DIR"
  # Root metadata module ships no primary artifact of its own — the checker exempts it.
  seed_module "torad-aisdk" ""
  seed_module "torad-aisdk-android" "aar"
  seed_module "torad-aisdk-jvm" "jar"
  seed_module "torad-aisdk-linuxx64" "klib"
  seed_module "torad-aisdk-iosarm64" "klib"
  seed_module "torad-aisdk-iossimulatorarm64" "klib"
  seed_module "torad-aisdk-iosx64" "klib"
}

check() { (cd "$tmp" && tools/check-release-bundle staging); }

seed_all
if [ "$CASE_KIND" = "compliant" ]; then
  check
  exit 0
fi

# --- violation scenarios (self-judging) ---

# 1. A target silently dropped — the ignoreDisabledTargets shape this gate exists for.
rm -rf "$GROUP_DIR/torad-aisdk-iosarm64"
if check >/dev/null 2>&1; then
  echo "FAIL-OPEN: a missing iOS publication passed" >&2
  exit 0
fi

# 2. An artifact loses its detached signature (Central rejects unsigned).
seed_all
rm "$GROUP_DIR/torad-aisdk-jvm/$VERSION/torad-aisdk-jvm-$VERSION.jar.asc"
if check >/dev/null 2>&1; then
  echo "FAIL-OPEN: an unsigned artifact passed" >&2
  exit 0
fi

# 3. Empty staging tree — "no missing modules among zero modules" must not read as OK.
seed_all
rm -rf "$GROUP_DIR"/*
if check >/dev/null 2>&1; then
  echo "FAIL-OPEN: an empty staging tree passed" >&2
  exit 0
fi

# 4. An UNEXPECTED module: release-shape drift must be decided, not discovered.
seed_all
seed_module "torad-aisdk-surprise" "klib"
if check >/dev/null 2>&1; then
  echo "FAIL-OPEN: an unexpected publication passed" >&2
  exit 0
fi

exit 1
