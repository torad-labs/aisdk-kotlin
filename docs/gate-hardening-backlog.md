# Gate-hardening backlog (post-remediation)

Derived from the three leverage points of the 2026-07-02 remediation campaign, via a
three-phase model sweep (Haiku ×3: gate inventory, execution topology, self-test
inventory → Sonnet ×2: LP-classification, de-serialization design → Opus ×1:
synthesis), reviewed and amended by the orchestrator. All statuses OPEN.

**The leverage points:**
- **LP-1 — An unexercised check is indistinguishable from a broken one.** Every gate
  must provably fire on a violation AND pass on compliant input, in a pipeline.
- **LP-2 — Serial verification masks.** Independent signals must fail and report
  independently; no external-state dependency may silence internal checks.
- **LP-3 — Gate pressure bends implementations when dodging is cheaper than repair.**
  Gate repair must be cheap, sanctioned, documented; gates must be robust to their
  scanning context.
- **LP-4 (new, accepted during this sweep) — Coverage must be provable, not
  inferred.** LP-1 proves a gate *can* fire; nothing today proves it *was invoked*.
  Dynamic half: a per-run execution ledger. Static half: what-runs-where must be
  traceable without archaeology.
  **Provenance — derived twice, independently, which is why it is accepted:**
  (1) from the repo data: healthy validators (`validate_rules.py`, the three hook
  self-test suites) simply were not invoked by any pipeline — a failure mode LP-1
  cannot detect; (2) from the *process of building this backlog*: a careful
  analysis agent, reading the actual workflows, misclassified 6 CI-wired gates as
  orphaned because script-calls-script indirection hid the wiring — if a
  best-effort audit cannot reliably enumerate what runs, neither can a maintainer.
  Operationalized by GH-19 (ledger + expected-set registry), GH-07 (liveness
  signal), GH-24 (the gate layer that silently isn't active on fresh clones).
  Corollary for future sweeps (process, not repo): multi-agent analysis of a
  verification system produces wrong inventories at every layer unless a
  claim-verification pass sits between phases — verification of claims beats
  volume of claims. Keep the reviewer in the loop.

**Ground-truth findings this backlog rests on (all independently verified):**
`validate_rules.py` (the ast-grep rule validator, parse + semantic fixture modes)
is wired into nothing; manifest fixtures cover 50/64 rules and the 14 uncovered
include the 4 scope-anchored rules most prone to hunk misfires; the three hook
self-test suites are invoked by no runner; `check-ai-sdk-reference.mjs` still
hard-fails on npm unreachability (only the newer-version case was demoted); the
`git clone` pin-check is a second unwrapped fatal network dependency; the
architecture gate depends on `npm ci` in CI; `SuspendFunSwallowedCancellation` is
enabled but structurally cannot fire; the pre-commit hook layer requires
`git config core.hooksPath .githooks`, which nothing sets up or documents —
fresh clones have no local gates at all.

---

## Phase 1 — this week (small, wire what already exists)

| ID | Item | Accept | LP |
|----|------|--------|----|
| GH-01 | Wire `validate_rules.py` (parse + `--manifest` semantic) into `ci-gate.sh` — two lines, same idiom as the four `detect-*.py` gates. Highest leverage in the backlog: activates 50 dormant fixtures + parse-checks all 64 rules on every commit. | A rule that mismatches its fixture, and a malformed rule file, each fail ci-gate in a pipeline run. | 1 |
| GH-02 | Run the three orphaned hook self-test suites (`test_kotlin_antipattern_policy.py`, `test_codex_hook_adapter.py`, `.codex/hooks/tests/test_claude_compat.py`) as a CI step (CI only, not pre-commit — keeps commit latency flat; GH-01 covers the per-commit layer). | A deliberately broken assertion in each suite fails the CI job. | 1 |
| GH-03 | Split reference-freshness (vercel/ai clone + npm query + parity check) into its own parallel non-blocking CI job — the 8-day-dark vector. | A PR with only the reference clone broken still shows architecture/tests/ABI/coverage results in the same run. | 2 |
| GH-04 | Make freshness *network failure* non-fatal (warn + continue) in `check-ai-sdk-reference.mjs` (`npm view` has no try/catch) AND the `git clone`/pin-test step; keep genuine pin/version mismatch fatal. | Simulated unreachability → warning + exit 0; corrupted pin → exit 1. | 2 |
| GH-05 | Regression-lock `run-ios-swift-smoke`: in the `verify-apple` context the skip branch must FAIL (e.g. `REQUIRE_REAL_SMOKE=1`), never silently pass. (The live hole is already closed; this locks it.) | verify-apple fails if the skip branch is taken. | 1 |
| GH-06 | "Gate misfire protocol" section in `CLAUDE.md` (diagnose → fix rule + fixture → re-run; never bypass), cross-linked from `ci-gate.sh`'s failure banner. | Section exists; gate failure output points at it. | 3 |
| GH-07 | Nightly cron run of the full pipeline on `main` — bounds any dark window to <24h regardless of push activity. | A scheduled verify+verify-apple run exists per 24h; an injected break on main surfaces within one cycle. | 2/4 |
| GH-08 | Fixture-authoring scaffold: `validate_rules.py --new <rule-id>` emits a fill-in-the-blanks manifest entry — makes repair cheaper than dodge. Do before GH-09. | Scaffold produces an entry accepted by `--manifest` once the two example bodies are filled. | 3 |
| GH-24 | Pre-commit gate is not active on fresh clones: nothing runs `git config core.hooksPath .githooks` and no doc mentions it. Add a bootstrap (one-liner script or gradle task) + CONTRIBUTING/README note. | A fresh clone following the documented setup has the pre-commit gate active; CONTRIBUTING documents it. | 4 |

## Phase 2 — next (medium, close coverage + de-serialize)

| ID | Item | Accept | LP |
|----|------|--------|----|
| GH-09 | Manifest fixtures for the 14 uncovered ast-grep rules (64/64 coverage); `validate_rules` errors on rules missing a manifest entry. | `--manifest` covers and passes 64/64. | 1 |
| GH-10 | Hunk/snippet-context probe mode in `validate_rules.py` — fixtures are currently always whole files; this week's real misfire was a member override scanned as an isolated hunk. | A rule misbehaving on bare-hunk input is flagged by the new mode. | 1/3 |
| GH-11 | Hunk fixtures for the 4 uncovered scope-anchored rules (`no-camelcase-top-level-function`, `no-mutable-companion-state`, `no-public-mutable-var`, `no-top-level-mutable-var`). | Each proven correct when scanned without its enclosing scope. | 3 |
| GH-12 | `continue-on-error` + step ids + final `if: always()` aggregator in the `verify` job — gates report independently, job fails via aggregator. | An early failing step no longer skips later gates; job status still red. | 2 |
| GH-13 | Decouple `ci-gate.sh` from `npm ci` in CI: pinned ast-grep binary via release + cache (npm -g is forbidden by beta-readiness), architecture gate runs before/independent of node steps. | Architecture gate passes with npm/registry stubbed broken. | 2 |
| GH-14 | ONE shared fixture-pair harness (per-gate `violation/` + `compliant/` dirs, single runner wired into CI) for the ~10 zero-self-test mjs/py gates. | Runner enforces violation→nonzero / compliant→zero per registered gate; new gates need only two directories. | 1 |
| GH-15 | First harness tenant: `detect-release-workflow-trust.py` — guards the release workflow, zero tests today. | Tampered release.yml fixture fails; compliant shape passes. | 1 |
| GH-16 | Resolve-or-remove the inert `SuspendFunSwallowedCancellation` detekt rule (enabled, cannot fire without type resolution). | Rule provably fires via fixture, or is removed; no enabled-but-inert rule remains. | 1 |
| GH-17 | Release `workflow_dispatch` dry-run: staging + signing + smoke, skip Sonatype/GH-Packages calls. Honest limit: cannot prove Central auth/state-machine. | dry_run=true completes green on a non-tag ref without contacting publish endpoints. | 1/3 |
| GH-23 | Budget ratchets must localize new debt, not just bound totals: this week 9 brand-new public data classes hid inside the grandfathered headroom of a count-based budget. Track the SET (like the detekt baseline does), so any NEW symbol entering a budgeted category is visible/acknowledged even when the total stays under budget. Apply to data-class budget first; evaluate for architecture/api-since budgets. | Adding a new public data class while total ≤ budget still surfaces the new symbol (explicit re-seed/acknowledge step); pure removals stay frictionless. | 1/3 |

## Phase 3 — structural (large)

| ID | Item | Accept | LP |
|----|------|--------|----|
| GH-18 | Harness rollout to the remaining CI-wired mjs/py gates, including two named regression-locks: golden-coverage dangling-reference fixture; api-review base-resolution fixture under CI-like shallow/detached checkout. | Every CI-wired mjs/py gate has a fixture pair; both regression fixtures fail-on-regression/pass-on-fix. | 1 |
| GH-19 | Gate execution ledger: every context emits `{gate, status}` to a manifest artifact; a final step asserts the EXPECTED gate set actually ran. The expected-set file doubles as the canonical what-runs-where registry (LP-4's static half). | Removing a gate invocation without updating the expected set fails CI; manifest artifact per run. | 4 |
| GH-20 | Mechanical dual-fixture contract: any rule using `inside:`/relational anchors must ship whole-file AND hunk fixtures; `validate_rules` fails on a missing pair. Backfill the 13 existing `inside:` rules. | New relational rule without a hunk fixture fails validation. | 3 |
| GH-21 | Extend `NoRunCatchingInSuspendFunction` to coroutine-builder lambdas (`launch { runCatching { … } }` is currently missed) or wire classpath-aware detekt; fixture proves it. | The builder-lambda fixture trips an active rule. | 1 |
| GH-22 | Apply GH-12's isolation pattern to `release.yml`'s publish job — but Maven Central publish stays strictly fail-fast on the aggregator. | Non-publish steps report independently; publish only runs on aggregated green. | 2 |

## Demoted (deliberate non-items)

- **Cache the pinned vercel/ai checkout** — pure latency; GH-03/04 already remove the correctness risk. Revisit if CI minutes bite.
- **Pre-push hook with fast detekt** — pre-commit already runs the architecture gate; CI backstops detekt/ABI. Keep as documented opt-in, not a commitment.

## RR — Beta release readiness (added 2026-07-02; the path from this branch to a shipped 0.3.0-beta01)

Ordered: ABI-last-chances → CI stability → merge mechanics → release execution → post-beta.

| ID | Item | Accept | Notes |
|----|------|--------|-------|
| RR-01 | Demote public positional constructors on growable result types (`GenerateResult`, `GenerateTextResult`, `StepResult`, `StructuredObjectFinish`/`Phase` leaves) to `internal`; sanctioned construction for test-fake authors goes through the shipped `Mock*` models or new factories. AR-14 already churned these ctors once this week — post-beta every field addition is a break for anyone constructing them. Last cheap moment is before the merge. | No growable result type exposes a public positional `<init>` in the JVM dump; test-fake construction path documented; dumps/CHANGELOG/contract updated. | ABI; pre-merge |
| RR-02 | **Dropped-in-translation item (audit ship-list → AR backlog):** LiteRT usage/finishReason seam. `LiteRTMessage` (builder-additive, ABI-safe) gains optional `usage` + `finishReason`; adapter stops fabricating `Stop` on truncation (which sends `decodeFinalOutput` parsing truncated JSON) and zero token usage. | A truncated engine response maps to `Length`, not `Stop`; reported usage reflects engine-provided counts when present; tests for both. | ABI-additive; pre-merge preferred |
| RR-03 | Roll the AR-35 deterministic-delayer pattern across the remaining real-time tests in `McpHttpTransportTest` (17 `waitForRealTime`/`Dispatchers.Default` refs remain) — pre-empt release-window CI flakes from the known-worst file. | Zero timing-window assertions remain in the file; bounded event-awaits only. | CI stability |
| RR-04 | **Main branch protection has NO required status checks** (verified via API: PR review required, but `required_status_checks` absent) — a red PR can merge with one approval, making every gate advisory at the merge boundary. Require `verify` + `verify-apple` (+ up-to-date branch) on main. | API shows both checks in `required_status_checks`; a red-CI PR is unmergeable. | Do before merging PR #10 |
| RR-05 | Merge PR #10 (the ~350-commit remediation branch). Recommend a merge commit over squash — the granular history is individually reviewed and referenced by two backlogs. Confirm the merge commit's CI on main is green. | PR merged; main CI green at the merge commit. | |
| RR-06 | Release execution: run the GH-17 dry-run first (once landed), then tag `v0.3.0-beta01` — the release workflow's first-ever real execution. After Central propagation, run a consumer canary: a fresh project resolving `ai.torad:torad-aisdk:0.3.0-beta01` from Maven Central (not staging) compiles and runs the README sample on JVM + one native target. | Central shows the artifact; canary green; release workflow run archived green. | The real AR-04 of releases |
| RR-07 | GitHub Release for the tag: CHANGELOG excerpt + a short beta stability promise (what may change before 1.0: `@ExperimentalAiSdkApi` surfaces, sealed hierarchies gain leaves per AR-33 policy, `else`-branch guidance; what won't: published ABI per the dumps without a version bump). | Release page exists with the promise; linked from README. | Sets consumer expectations |
| RR-08 | Schedule the upstream triage DEC-2 deferred: ai@6.0.208 → v7.x delta review as a post-beta milestone with an owner, so the freshness pin doesn't become permanent amnesia (the warning fires on every CI run today). | A tracked issue/milestone exists for the v7 parity triage. | Post-beta |

## BP — Ecosystem best practices (added 2026-07-02 from a live sweep: JetBrains library-author guidelines, klibs.io criteria/talk, Apollo Kotlin evolution policy, Okio practices, GitHub attestations)

| ID | Item | Source / accept | Notes |
|----|------|-----------------|-------|
| BP-01 | **Published evolution policy** (EVOLUTION.md or contract section): per-stage guarantees in Apollo's style — beta = "documented + full test suite, production-encouraged, API may change with minimized impact"; rc = frozen; stable = no binary breaks until major — plus a concrete deprecation timeline (e.g. ≥1 minor as WARNING → ≥1 as ERROR → removal at major) and a Kotlin-version update policy. Extends AR-33/RR-07 from scattered paragraphs into one referenceable page. | Apollo Kotlin `evolution.mdx` | Beta-window |
| BP-02 | **Kotlin consumer-compatibility policy, Okio-style**: document the supported consumer Kotlin range; evaluate building with the current compiler while depending on an older kotlin-stdlib (`apiVersion`/`languageVersion` + explicit stdlib coordinate) so consumers update Okio-independently. For klib consumers document the KGP floor separately (klib ABI is compiler-coupled). | Okio CHANGELOG 2026-03-11 | Evaluate; JVM/Android side is the easy win |
| BP-03 | **Snapshot channel**: publish `-SNAPSHOT` builds to Central Portal snapshots (`central.sonatype.com/repository/maven-snapshots/`) on push to main, so beta feedback loops don't wait for tags. Requires a deliberate carve-out from the current refuse-SNAPSHOT publish guard (keep the guard for the release path). | Okio + Apollo both ship this; kotlinlang publishing tutorial | Post-beta01 |
| BP-04 | **Browsable API docs site**: publish the already-generated Dokka HTML to GitHub Pages on release; configure `sourceLink` (docs→GitHub navigation) and `externalDocumentationLinks` for coroutines/serialization/ktor so their types resolve clickable (verified: neither is configured today). | Dokka v2 docs; square.github.io/okio pattern | The javadoc jar exists; this is distribution |
| BP-05 | **POM enrichment for discovery**: add `issueManagement` + `ciManagement` blocks (verified absent), ensure a unique artifact description — klibs.io and LLM indexing consume these; listing criteria (GitHub link in POM, tooling-metadata, Central artifact) are already met. Verify the library appears on klibs.io within ~a month of beta and the metadata reads correctly. | klibs.io FAQ + Beresnev KotlinConf talk | Cheap, do with RR-06 |
| BP-06 | **Artifact provenance attestations**: add `actions/attest` (SLSA build provenance, Sigstore-signed, free for public repos) over the staged release bundle in release.yml; document `gh attestation verify` for consumers. Closes the release-audit nit with now-mature tooling. | GitHub changelog 2025-02-18; actions/attest | Release-workflow addition — trust gate must stay green |
| BP-07 | **Target-matrix expansion evaluation**: JetBrains guidance is "publish for all targets you can support, tiered testing." Currently jvm/android/iOS×3/linuxX64. Evaluate macosArm64/X64 (near-free: toolchain already in CI), wasmJs + js (deps ktor/coroutines/serialization all support them; opens the web-adjacent KMP audience), mingwX64. Each added target = conformance surface; decide deliberately, not by default. | kotlinlang api-guidelines-build-for-multiplatform | Post-beta decision item |
| BP-08 | **Samples beyond the smoke consumers**: a user-facing `samples/` (JVM chat CLI first; Compose host later per BL-018) in Apollo's samples-repo spirit — the compile-guarded README/wiki snippets provide the seed content. | Apollo samples repo pattern | Post-beta |
| BP-09 | **Evaluated and rejected (recorded so they aren't re-litigated):** publishing a BOM (single-artifact library — nothing to align); migrating the hand-rolled Central Portal upload to the vanniktech plugin (ours is trust-gated and proven on two releases; revisit only if the Portal API churns). | Okio BOM; kotlinlang tutorial | — |

## Provenance & corrections applied during synthesis

- Haiku inventory misclassified 6 CI-wired gates as orphaned (indirection through
  `beta-readiness-check`) — corrected before analysis; the error itself became
  evidence for LP-4's static half.
- Sonnet's "all 4 inside:-anchored rules uncovered" corrected by Opus: 13 rules use
  `inside:`; the accurate claim is "the 4 *uncovered* scope-anchored rules."
- Swift-smoke reclassified from live hole to regression-lock (the macOS leg runs the
  real branch today; it did not for weeks).
- GH-23 and GH-24 added by the orchestrator from incidents the sweep prompts did not
  carry (budget-headroom debt hiding; hooksPath enablement gap).
