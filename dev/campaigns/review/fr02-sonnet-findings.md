# claude-directive-exception: estimation_deferral verbatim quotation of campaign ledger notes for the FR review; deferral wording here is inventoried data, not deferred work

# FR-02 Campaign Findings (Sonnet) — analysis at cefc2ed

**Scope:** repo `/home/marcos/Documents/dev/opensource/aisdk-kotlin`, branch `beta-follow-ups`, tip `cefc2ed118589275c34f27f720e93206285d1c23`. Input: `dev/campaigns/review/fr01-haiku-inventory.md` (trusting its "Orchestrator corrections" section) + `dev/campaigns/gate-hardening.toml`. Working tree was clean and at tip both before and after this review; no tracked files were modified. All scratch reproduction used `/tmp`, a separate `git clone` under the scratchpad, or in-memory scratch copies — never the tracked repo files.

---

## Findings

### F-01 — `validate_rules.py --manifest` completeness check fails OPEN when the manifest is validated away from its canonical location
**Severity:** major
**File:line:** `.claude/hooks/rules/validate_rules.py:311-317` (`_missing_manifest_entries`), called from `:150` inside `semantic_mode` (the `--manifest` flag path, dispatched at `:367`)
**Task:** B (cross-slice interaction hunt, extending B.8) / C (GH-09 claim reproduction)

`_missing_manifest_entries` derives the "real" rules directory as `manifest_path.resolve(strict=False).parent / "kotlin"` — i.e. relative to wherever the manifest **argument** happens to resolve, not anchored to the script's own location. If that computed directory doesn't exist, the function returns `[]` (no missing entries) unconditionally — it does not error, warn, or fall back to a canonical path. GH-09's verify field is "removing an entry fails it," and GH-09's ledger note claims "64/64 both modes, removal probe exit 1." That is true only when the manifest is validated in place. Reproduced both directions:

```
# Isolated scratch copy (63/64 entries, no sibling kotlin/ dir) — FALSE PASS
$ python3 .claude/hooks/rules/validate_rules.py --manifest "$SCRATCH/manifest_missing_one.json" ; echo "EXIT_CODE=$?"
ok: all 63 rules match bad + skip good
EXIT_CODE=0

# Same 63-entry file, with a sibling kotlin/ symlink added (mimics the real .claude/hooks/rules/ layout) — CORRECT FAIL
$ python3 .claude/hooks/rules/validate_rules.py --manifest "$SCRATCH/rules-mimic/manifest.json" ; echo "EXIT_CODE=$?"
SEMANTIC FAIL: 1 rule files have no manifest entry
  - no-public-mutable-collection-val: missing manifest entry
EXIT_CODE=1
```
(Both runs captured exit codes without piping through `head`/`tail`, per the shell rule.)

The sole current production caller, `ci-gate.sh` (`python3 .claude/hooks/rules/validate_rules.py --manifest .claude/hooks/rules/manifest.json`, always run with CWD at repo root), always passes the canonical path, so today's pre-commit/CI gate is not exploited by this — that is why it is rated major, not blocker. But the completeness guarantee GH-09 was built to provide ("make validate_rules error on rules missing manifest entries") silently degrades to a no-op the moment anyone validates a copy of the manifest instead of the tracked file in place — exactly the kind of thing a reviewer, fixture author, or future CI refactor does naturally (I reached for a scratch copy on my first instinct, precisely to avoid touching the tracked file). This is the same bug *class* the orchestrator already flagged for the data-class budget detector (see F-02), but it fails in the more dangerous direction: silent pass instead of confusing-but-safe fail. For contrast, `detect-nonintegrated-kotlin.py:77` anchors its own companion file correctly — `Path(__file__).resolve().parent / "nonintegrated-baseline.txt"` — proving the codebase already knows the robust pattern; it just isn't applied consistently across the three detectors that need a companion file.

### F-02 — Public data-class budget CWD-relative path: both failure directions confirmed, neither is a silent pass
**Severity:** minor
**File:line:** `.claude/hooks/rules/detect-public-data-class-budget.py:30`
**Task:** B.8

`BUDGET_FILE = Path("data-class-budget.json")` is bare CWD-relative (confirmed by direct grep). FR-01's corrections already named this a residual; I ran the actual exploit both ways it can go wrong, so severity can be judged on evidence rather than a hypothesis:

```
# From /tmp, absolute src path — clean fail, not a false pass
$ cd /tmp && python3 .../detect-public-data-class-budget.py /home/.../src/commonMain/kotlin --check
data-class budget gate: missing data-class-budget.json (run with --update once to seed)
EXIT_CODE=1

# From inside tools/gate-fixtures/public-data-class-budget/compliant/ (has its own tiny data-class-budget.json), scanning the REAL src tree
$ cd tools/gate-fixtures/public-data-class-budget/compliant && python3 .../detect-public-data-class-budget.py /home/.../src/commonMain/kotlin --check
PUBLIC DATA-CLASS BUDGET EXCEEDED: 57 public `data class` in commonMain, budget is 1.
EXIT_CODE=1
```
Direction 1 (no budget file anywhere in CWD) fails closed with a message that doesn't reveal it's a path-resolution issue (mildly confusing but safe — never a false green). Direction 2 (CWD happens to contain an unrelated small `data-class-budget.json`, which literally exists today at `tools/gate-fixtures/public-data-class-budget/{violation,compliant}/data-class-budget.json`) produces a **wrong but still-failing** result — it reads the fixture's toy budget (1) against the real 57-class tree and reports a bogus "budget exceeded." Neither test produced a false pass, which is why this stays minor rather than major (contrast with F-01, which does false-pass). Same fix shape as F-01 would apply: anchor via `Path(__file__).resolve().parent`, matching `detect-nonintegrated-kotlin.py`'s existing pattern.

### F-03 — GH-13's npm-ci decoupling was not extended to release.yml's publish job
**Severity:** minor
**File:line:** `.github/workflows/release.yml:105-124` vs `.github/workflows/ci.yml:72-105`
**Task:** B (cross-slice interaction hunt)

GH-13 (`files = [".github/workflows/ci.yml"]`) downloads a checksum-pinned ast-grep binary (`.tools/ast-grep/${AST_GREP_VERSION}`) *before* `npm ci`, specifically so the architecture gate doesn't depend on the node toolchain. `release.yml`'s `publish` job has no equivalent step — its "Architecture gate" step (line 121-124) runs `./node_modules/.bin/ast-grep --version && ... ci-gate.sh`, and `node_modules` only exists after "Install locked Node tooling" (`npm ci`, line 119-120) succeeds. Additionally, neither that `npm ci` step nor the earlier "Fetch Vercel AI SDK v6 reference" step (line 105-110, a `git clone`) carries `continue-on-error: true`; both lack any `if:`. Since GitHub Actions implicitly ANDs a step's default execution with `success()` of prior required steps, a network or npm-registry hiccup during a real release aborts the whole `publish` job before the 5-gate aggregator even runs — none of `architecture_gate`/`beta_readiness`/`gradle_check` results get computed or reported that run. This is plausibly intentional (a release path failing closed on any infra hiccup is arguably the right default, unlike CI's verify job which GH-03/GH-04/GH-12/GH-13 deliberately hardened against exactly this kind of masking), but it means GH-13's "decouple the architecture gate from npm" principle is not uniformly applied across the two pipelines that both run the same `ci-gate.sh`.

### F-04 — GH-02's own verified-note test count is stale (34 vs current 36), explained not defective
**Severity:** minor
**File:line:** `.claude/hooks/tests/test_kotlin_antipattern_policy.py`; ledger note under `GH-02` in `dev/campaigns/gate-hardening.toml`
**Task:** C (claim reproduction)

GH-02's orchestrator-verified note reads "four suites green independently (34/5/26/6)." Running the suite today:
```
$ python3 .claude/hooks/tests/test_kotlin_antipattern_policy.py | tail -1
ok 36
```
36, not 34 — but this is fully explained, not a red flag: `git log --oneline 978502d..HEAD -- .claude/hooks/tests/test_kotlin_antipattern_policy.py` shows commit `549f667` ("fix(gate): consumer-tree exemption for kotlin anti-pattern policy; RR-05 merged"), which post-dates GH-02's verified SHA `978502d` and adds `+14` lines / 0 deletions to that exact file (`git show --stat 549f667 -- .claude/hooks/tests/test_kotlin_antipattern_policy.py`). The commit message says "Four-direction probes + suite regression cases" for the consumer-tree exemption — consistent with +2 test cases. The ledger note was accurate at the moment it was written; it is a point-in-time snapshot that the campaign's own later work correctly moved past. FR-01's inventory (36/26/6/5) matches today's reality exactly; GH-02's verified-note number does not, and nothing in the ledger flags that the count grew after verification.

### F-05 — No local enforcement of ast-grep version parity with CI
**Severity:** minor
**File:line:** `.claude/hooks/rules/ci-gate.sh:17` vs `.github/workflows/ci.yml:78-101`
**Task:** B.7

`ci-gate.sh:17`: `AG="$(command -v ast-grep || echo "$HOME/.local/bin/ast-grep")"` — whatever ast-grep is on PATH or in `~/.local/bin`, no version check. `ci.yml:78-101` ("Install ast-grep binary") explicitly compares `package.json`'s `@ast-grep/cli` devDependency against `AST_GREP_VERSION` and fails the step if they differ, then downloads and sha256-verifies the pinned binary. Today, on this machine, they happen to match exactly:
```
$ ~/.local/bin/ast-grep --version
ast-grep 0.42.1
$ grep ast-grep package.json
"@ast-grep/cli": "0.42.1"
```
— but that match is circumstantial, not enforced. A developer (or agent) that upgrades their local ast-grep via `uv tool upgrade` (the tool is a uv-managed symlink here: `~/.local/bin/ast-grep -> ~/.local/share/uv/tools/ast-grep-cli/bin/ast-grep`) would get silent local/CI rule-evaluation drift with nothing in the pre-commit path to catch it. `.claude/hooks/sgconfig.yml` only sets `ruleDirs`, no version pin either.

### F-06 — mavenLocal() + fixed non-SNAPSHOT version is a latent local dev-loop staleness risk (not manifested today)
**Severity:** info
**File:line:** `samples/jvm-chat-cli/settings.gradle.kts:11` (`mavenLocal()`), `gradle.properties:17` (`VERSION_NAME=0.3.0-beta01`, no `-SNAPSHOT`)
**Task:** A.3

Gradle documents that resolving a non-SNAPSHOT (release) coordinate from `mavenLocal()` is not guaranteed to pick up a later republish at the same version — Gradle may treat an already-resolved release version as immutable. This repo republishes `0.3.0-beta01` locally on every `./gradlew publishToMavenLocal` pre-tag (confirmed: `~/.m2/repository/ai/torad/torad-aisdk/0.3.0-beta01/` had file mtimes from Jun 24 before my run, and the version directory itself carries no SNAPSHOT marker to force Gradle to re-check). I verified my own run was not tainted: `find ~/.gradle/caches/modules-2/files-2.1/ai.torad -iname "*torad-aisdk-jvm*0.3.0-beta01*.jar"` returned nothing *before* the sample ran, meaning no stale cached copy existed for the JVM sample's dependency to silently reuse this session — the sample necessarily read the bytes I had just published. This is not a proven live bug, just a structural risk in the documented dev loop (`publishToMavenLocal` + `run`, no `--refresh-dependencies`) that a version bump or explicit refresh would close.

### F-07 — "Preflight trust gate" is not literally in the `preflight` job; it's embedded in `publish`'s own pre-gate aggregator
**Severity:** info
**File:line:** `.github/workflows/release.yml:24-52` (`preflight` job) vs `:119-155` (`publish` job's pre-gate steps + aggregator)
**Task:** A.4(iii)

The `preflight` job only runs "Verify release tag" and "Beta readiness gate" (`tools/beta-readiness-check`) — it does not invoke `ci-gate.sh` or `detect-release-workflow-trust.py` at all. The actual release-workflow-trust check runs *inside* the `publish` job as the `architecture_gate` step (line 121-124), aggregated by "Summarize pre-publish gates" (line 133-155). The gating is real: GitHub Actions implicitly ANDs a step's default condition with `success()` of prior non-`continue-on-error` steps whenever a custom `if:` doesn't reference a status-check function itself, so if the aggregator step fails (any of the 5 pre-publish gates non-success), the subsequent `if: ${{ github.event_name != 'workflow_dispatch' || inputs.dry_run != true }}` steps (Attest / Publish to Central / Publish to GH Packages) never run because that implicit `success()` is false. I could not dispatch a live workflow run to prove this end-to-end per the task constraints, so this rests on documented GHA `if:`-evaluation semantics plus GH-22's own "Central publish strictly fail-fast after aggregator (unreachable past red)" ledger claim, which I did not independently re-run. Functionally this holds; the job topology just doesn't match a literal reading of "preflight trust gate."

### F-08 — snapshots.yml has no concurrency group
**Severity:** info
**File:line:** `.github/workflows/snapshots.yml:1-36` (entire file — no `concurrency:` key anywhere)
**Task:** B.4

If two merges to `main` land close together after fold-in, both would trigger independent `publish-snapshot` runs with no `concurrency:` group to queue/cancel them, both racing `publishAllPublicationsToCentralSnapshotsRepository` against the same `-SNAPSHOT` coordinate. Given the campaign's documented merge cadence (squash, one-PR-at-a-time per the fleet protocol), this is low-probability, and Sonatype's Central Snapshots endpoint is built for repeated CI publishes, so this is informational rather than a real risk today.

---

## Holds

Verified-true claims and interactions, each with the evidence that backs it:

1. **`./gradlew check` passes at tip.** BUILD SUCCESSFUL, exit 0, 7s wall (39 actionable: 5 executed / 34 UP-TO-DATE). detekt clean, `checkKotlinAbi` ran, `koverVerify` passed, `detektBaselineBudgetCheck` 1947/1947.
2. **`./gradlew publishToMavenLocal` passes at tip.** BUILD SUCCESSFUL, exit 0, 861ms; artifacts for all 7 KMP publications written under `~/.m2/repository/ai/torad/torad-aisdk*/0.3.0-beta01/`, jar mtimes match the run window.
3. **Sample runs correctly against the just-published artifact, mock path only.** `generate:`/`stream:` both print `AI SDK Kotlin provides agents, tools, streaming, and provider adapters.`, exit 0. Confirmed BP-08's "provider path explicitly env-gated (no ambient network)" claim under an adversarial condition: the ambient shell already had `OPENAI_API_KEY` and `OPENAI_BASE_URL` set (for an unrelated tool) and the sample still took the mock path, because `Main.kt`'s gate checks `AISDK_SAMPLE_PROVIDER == "openai"` specifically, not mere presence of `OPENAI_API_KEY`.
4. **release.yml dry_run gating.** All Central-upload/attestation/GH-Packages steps carry `if: ${{ github.event_name != 'workflow_dispatch' || inputs.dry_run != true }}` (lines 189, 194, 238, 249); preflight's tag/version/ancestry checks short-circuit via an early `exit 0` under `DRY_RUN=true` (line 37-41).
5. **Attestation permissions are job-scoped.** `id-token: write` / `attestations: write` appear only under the `publish` job's `permissions:` block (lines 84-88); the workflow-level `permissions:` (line 15-16) stays `contents: read`.
6. **BP-02 language/API pin is correctly scoped.** `languageVersion.set(KotlinVersion.KOTLIN_2_3)` / `apiVersion.set(KotlinVersion.KOTLIN_2_3)` (`build.gradle.kts:52-53`) sit in the top-level KMP `kotlin{}` extension, applying to all of the library's own main+test compilations across all targets — confirmed empirically, since `./gradlew check` (which compiles commonMain/jvmMain/androidMain/nativeMain/commonTest/jvmTest under this pin) passed cleanly. It correctly does **not** extend to `samples/jvm-chat-cli` (plain `kotlin("jvm") version "2.4.0"`, no override) or `smoke-tests/local-staging/*` (separate root project via its own `settings.gradle.kts`, no override) — which is the intended behavior, since those simulate independent consumers who should be free to use their own Kotlin version.
7. **RR-01: zero public constructors on the named growable result types.** JVM ABI dump (`api/jvm/torad-aisdk.api`) shows no `<init>` line under `GenerateResult` (:2195), `GenerateTextResult` (:2222), `StepResult` (:4939), `StructuredObjectFinish` (:5781), or `StructuredObjectPhase`/`$Done`/`$Idle`/`$Streaming` (:5822-5849) — only getters/`equals`/`hashCode`/`toString`. `ResultConstruction` (the internal factory, `src/commonMain/kotlin/ai/torad/aisdk/ResultConstruction.kt:6`) is absent from both `api/jvm/torad-aisdk.api` and `api/torad-aisdk.klib.api`. It's used from `commonMain` production code (`StructuredObjectApi.kt`, `Telemetry.kt`, `TextGenerator.kt`, `ToolLoopAgent.kt`) and from same-module `commonTest` files via Kotlin's test-friend-path access. The sample never attempts direct construction of any of these types (grep across `samples/` for constructor-shaped calls: zero hits) — it only reads results through public factory entry points, so the demotion cost consumers nothing.
8. **Nothing heavy sneaks into the per-commit gate path.** Read `.githooks/pre-commit` (execs `ci-gate.sh`, nothing else) and `ci-gate.sh` end-to-end: no `./gradlew` invocation anywhere, no `npm install`/`npm ci`. Every `tools/*.mjs` script (including `run-gate-fixtures.mjs`) imports only `node:fs`/`node:path`/`node:child_process` — zero external npm package dependencies (`grep -n "^import\|require(" tools/*.mjs` shows only `node:`-prefixed imports).
9. **FR-01's "warm slower than cold" timing anomaly is load noise, not a gate property.** Re-ran `ci-gate.sh` three times myself: 13.107s / 10.704s / 11.089s — no consistent warm-vs-cold direction. `ps aux` at the time of the slow run showed a concurrent, unrelated project's `:core:concurrency:ktlintFormat` Gradle process consuming 385% CPU, plus several idle-but-resident Kotlin-daemon JVMs for this and other projects; `uptime` load average ranged 5.8-8.4 on a 32-core box throughout. This is exactly the kind of shared-machine contention that explains bidirectional variance.
10. **snapshots.yml cannot block a merge.** `gh api repos/torad-labs/aisdk-kotlin/branches/main/protection` shows `required_status_checks.contexts = ["verify", "verify-apple"]` only — `publish-snapshot` (snapshots.yml's job name) has no required-check role, so a failed snapshot publish cannot block anything.
11. **docs.yml name-match and squash-merge non-interaction.** `docs.yml:4-8` triggers on `workflow_run` for workflows named exactly `Release` (`release.yml:1`: `name: Release` — exact case-sensitive match) with `types: [completed]`, gated further by `github.event.workflow_run.conclusion == 'success' && github.event.workflow_run.event == 'push'` (`docs.yml:15`). Since `release.yml` itself only triggers on `workflow_dispatch` or `push: tags: v*` (never on a branch push), a squash-merge to `main` cannot produce a `Release` workflow run at all, so it cannot satisfy `event == 'push'` by way of `main`.
12. **The three newest workflows are entirely unarmed until fold-in.** `git diff origin/main -- .github/workflows/release.yml` and `.../ci.yml` are both empty (main is byte-identical to tip for these two — the earlier RR-05 squash-merge already carried everything through GH-22/BP-06/GH-17). But `gh api "repos/torad-labs/aisdk-kotlin/contents/.github/workflows?ref=main"` lists only `['ci.yml', 'release.yml']`, and `gh workflow list --all` shows GitHub has only `CI` and `Release` registered (`active`) — no `Consumer Canary`, `Docs`, or `Snapshot` entries exist yet. `schedule:`/`workflow_run` triggers only arm from the default branch's copy of a workflow file, so `consumer-canary.yml`'s Monday cron, `snapshots.yml`'s push-to-main trigger, and `docs.yml`'s `workflow_run` are all dark until the RR-06 fold-in PR lands.
13. **GH-13's checksum is real.** Independently downloaded `https://github.com/ast-grep/ast-grep/releases/download/0.42.1/app-x86_64-unknown-linux-gnu.zip` and computed `sha256sum` myself: `5de8b87cba67fc8dc3e239d54b6484802ad745a7ae3de76be4fe89661dc52657` — exact match to `ci.yml:19`'s pinned `AST_GREP_SHA256`.
14. **GH-16: inert detekt rule is gone.** `grep -n "SuspendFunSwallowedCancellation" detekt.yml` matches only an explanatory comment (lines 7, 10), no active rule config.
15. **GH-19: expected-gates counts match ci.yml exactly.** `verify` job has exactly 5 gate-bearing steps (`architecture_gate`, `hook_self_tests`, `beta_readiness`, `gradle_check`, `publish_to_maven_local`); `verify-apple` has exactly 2 (`apple_targets`, `swift_import_smoke`).
16. **GH-20: memberExamples is canonical, 0 singular remain.** `grep -o '"memberExample"' manifest.json` → 0; `grep -o '"memberExamples"' manifest.json` → 4. `validate_rules.py` (around :288-289) rejects the singular key explicitly: `if "memberExample" in rule: return [], "memberExample is deprecated; use memberExamples"`.
17. **GH-21: builder-lambda fixture is exactly "fire + two no-flag."** `CoroutineRulesTest.kt` has 5 total cases; the 3 relevant to the builder-lambda extension are `flags runCatching inside launch builder lambda` (fire), `does not flag ordinary nested non-suspend lambda`, and `does not flag pure runCatching inside coroutine builder lambda` (two no-flag).
18. **GH-24: bootstrap works in an actual fresh clone.** Cloned the repo fresh into scratch, checked out `beta-follow-ups`: `core.hooksPath` unset before, `tools/bootstrap` sets it to `.githooks` and prints confirmation, a second run detects the already-set state (idempotent), exit 0 both times.
19. **RR-03: zero real-time refs remain.** `grep -c "waitForRealTime\|Dispatchers.Default" src/commonTest/kotlin/ai/torad/aisdk/McpHttpTransportTest.kt` → 0.
20. **RR-04: branch protection matches the ledger exactly.** `gh api .../branches/main/protection` → `required_status_checks: {strict: true, contexts: ["verify","verify-apple"]}`, `required_pull_request_reviews.required_approving_review_count: 1`.
21. **BP-02: published POM declares the decoupled stdlib floor.** Freshly generated `torad-aisdk-jvm-0.3.0-beta01.pom` (from my own `publishToMavenLocal` run at tip) declares `<artifactId>kotlin-stdlib</artifactId><version>2.3.21</version>`.
22. **BP-06: attest-build-provenance pin is authentic.** `gh api repos/actions/attest-build-provenance/git/refs/tags/v4.1.1 --jq '.object.sha'` → `0f67c3f4856b2e3261c31976d6725780e5e4c373`, exact match to `release.yml:190`'s pinned SHA.
23. **`detect-nonintegrated-kotlin.py` uses the robust anchor pattern.** `Path(__file__).resolve().parent / "nonintegrated-baseline.txt"` (`:77`) — contrast evidence showing the fragile-path bug class (F-01, F-02) isn't universal in this codebase, just inconsistently applied.
24. **GH-01: pristine-tree pass is reproducible.** `ci-gate.sh` exited 0 on the untouched tree in every one of 4 separate runs this session (initial + 3 timing reruns), each showing all 7 gate sections green.

---

## Verification log (Task A)

| Command | Result | Duration | Notes |
|---|---|---|---|
| `./gradlew check` | **PASS**, exit 0 | 7s (BUILD SUCCESSFUL line); wall from timestamps ≈7.84s | 39 actionable tasks: 5 executed, 34 UP-TO-DATE. Config cache invalidated by `gradle/verification-metadata.xml` change, so this was a real (not fully-cached) evaluation. iOS simulator tests `SKIPPED` (expected on this Linux host — `verify-apple` job on macOS covers them in CI). No FAILED tasks anywhere in the log. |
| `./gradlew publishToMavenLocal` | **PASS**, exit 0 | 861ms | 70 actionable: 22 executed, 48 UP-TO-DATE. All 7 publications (root, jvm, android, iosArm64, iosX64, iosSimulatorArm64, linuxX64) written to `~/.m2`. |
| `./gradlew -p samples/jvm-chat-cli run --args="Explain the SDK in one sentence."` | **PASS**, exit 0 | 691ms | `generate:`/`stream:` sections both correct; mock path taken despite ambient (unrelated) `OPENAI_API_KEY`/`OPENAI_BASE_URL` in the shell, because gating is on `AISDK_SAMPLE_PROVIDER`, not key presence. |
| `bash .claude/hooks/rules/ci-gate.sh` (x4 total this session) | **PASS** all 4, exit 0 | 13.107s / 10.704s / 11.089s (+ the 3 recorded in FR-01: 10.848s, 12.467s) | All 7 gate sections green every run: architecture (0 violations), non-integrated (baseline 0), rule self-test (parse 64, match+skip 64, hunk-mode 64, fixture harness 26/13), tool-identity, data-class-budget (57/57 tracked 57), release-workflow-trust. Variance attributed to shared-machine contention (see F-holds #9), not a property of the gate. |
| `.github/workflows/release.yml`, `snapshots.yml`, `docs.yml`, `consumer-canary.yml`, `ci.yml` structural read | Done end-to-end | n/a | See Findings F-03, F-07, F-08 and Holds #4-6, #10-12. |

**Gradle check: PASS. publishToMavenLocal: PASS. Sample: PASS. Release-workflow structure: verified, gaps noted in F-03/F-07/F-08.**
