set -euo pipefail

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

seed="$tmp/seed"
root="$tmp/root"
git init --quiet --initial-branch=main "$seed"
git -C "$seed" config user.email fixture@example.invalid
git -C "$seed" config user.name Fixture
mkdir -p "$seed/tools" "$seed/src/commonMain/kotlin"
cp "$REPO_ROOT/tools/check-api-review.mjs" "$seed/tools/check-api-review.mjs"
cat > "$seed/src/commonMain/kotlin/Fixture.kt" <<'KT'
public class Fixture
KT
git -C "$seed" add .
git -C "$seed" commit --quiet -m "base"
git -C "$seed" checkout --quiet -b feature
printf '\npublic class Feature\n' >> "$seed/src/commonMain/kotlin/Fixture.kt"
git -C "$seed" add src/commonMain/kotlin/Fixture.kt
git -C "$seed" commit --quiet -m "feature"

if [ "$CASE_KIND" = "violation" ]; then
  git clone --quiet --depth 1 --branch feature "file://$seed" "$root"
  git -C "$root" fetch --quiet --depth=1 origin main:refs/remotes/origin/main
else
  git clone --quiet --branch feature "file://$seed" "$root"
  git -C "$root" fetch --quiet origin main:refs/remotes/origin/main
fi
git -C "$root" checkout --quiet --detach HEAD

(
  cd "$root"
  GITHUB_BASE_REF=main node tools/check-api-review.mjs
)
