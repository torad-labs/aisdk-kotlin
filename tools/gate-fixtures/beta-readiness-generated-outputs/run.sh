source "$GATE_FIXTURE_DIR/../beta-readiness-lib.sh"

# The sub-check reads `git ls-files` from its ROOT, so the scratch tree needs to be a real
# repo. The harness already strips GIT_DIR/GIT_INDEX_FILE etc. so this cannot touch the
# live repo's index. `git add` is enough — ls-files reads the index, no commit needed.
git -C "$tmp" init -q
printf 'ok\n' > "$tmp/normal.txt"
git -C "$tmp" add normal.txt

if [ "$CASE_KIND" = "violation" ]; then
  mkdir -p "$tmp/build"
  printf 'artifact\n' > "$tmp/build/output.jar.txt"
  git -C "$tmp" add -f build/output.jar.txt
fi

brc generated-outputs
