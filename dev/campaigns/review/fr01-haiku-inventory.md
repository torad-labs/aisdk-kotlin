# claude-directive-exception: estimation_deferral verbatim quotation of campaign ledger notes for the FR review; deferral wording here is inventoried data, not deferred work

# FR-01 Campaign Inventory (Haiku) — da3d7d2..HEAD

**Campaign scope:** gate-hardening-beta-release (14 commits, 820 files changed, 123,362 insertions, 52,699 deletions)

---

## 1. Changed subsystems (da3d7d2..HEAD)

### By subsystem and file count

| Subsystem | Files | Insertions | Deletions | Notes |
|-----------|-------|-----------|-----------|-------|
| `.claude/hooks/rules/kotlin/` | 64 | 479 | 0 | All ast-grep rules, one per YAML file |
| `.claude/hooks/modules/pretooluse/` | 3 | 1,306 | 0 | fleet_protocol_policy.py (691), kotlin_antipattern_policy.py (367), rule_selfcheck_policy.py (248) |
| `.claude/hooks/rules/*.py` | 4 | 543 | 0 | detect-nonintegrated-kotlin (108), detect-public-data-class-budget (173), detect-release-workflow-trust (145), detect-tool-identity-regressions (117) |
| `.claude/hooks/orchestrator/` | 2 | 132 | 0 | pretooluse.py (118), result.py (14) |
| `.claude/hooks/tests/` | 4 | 750 | 0 | test_kotlin_antipattern_policy (211), test_fleet_protocol_policy (291), test_rule_selfcheck_policy (118), test_codex_hook_adapter (124) |
| `.claude/hooks/rules/manifest.json` | 1 | 479 | 0 | 64 fixture sets (badExample+goodExample pairs) |
| `.claude/hooks/rules/validate_rules.py` | 1 | 385 | 0 | Manifest semantic validation + --manifest mode + --hunk-mode |
| `.claude/hooks/rules/ci-gate.sh` | 1 | 81 | 0 | Wire validate_rules, hook self-tests, fixture harness |
| `.github/workflows/` | 5 | 502 | 0 | ci.yml (+162), release.yml (+162), consumer-canary.yml (+191), docs.yml (+53), snapshots.yml (+36) |
| `.claude/hooks/` (config) | 3 | 19 | 0 | sgconfig.yml (+2), settings.json (+16) |
| `.codex/` | 3 | 411 | 0 | hooks.json (+17), hooks/claude_compat.py (+272), hooks/tests/test_claude_compat.py (+122) |
| `.githooks/` | 1 | 11 | 0 | pre-commit hook installed |
| `tools/` | 13 | 263 | 16 | run-gate-fixtures.mjs (+96), gate-fixtures/ fixture dirs, bootstrap scripts, expected-gates.txt |
| `detekt-rules/` | 3 | 52 | 25 | Removed inert SuspendFunSwallowedCancellation, kept custom-rule stopgap |
| `docs/` | 15 | 1,246 | 180 | gate-hardening-backlog.md, evolution policy, architecture docs, parity docs |
| `src/commonMain/kotlin/ai/torad/aisdk/` | ~35 | 3,800+ | 2,100+ | Provider/protocol refactors, result type demotions, LiteRT finishReason/usage |
| `src/commonTest/kotlin/ai/torad/aisdk/` | ~40 | 4,200+ | 1,800+ | New test fixtures, deterministic-delayer, McpHttpTransportTest flake fixes |
| `CHANGELOG.md` | 1 | 270 | 0 | Release notes + per-item reconciliations |
| `INTERFACE_CONTRACT.md` | 1 | 492 | 0 | API/ABI surface + evolution policy + consumer compatibility guarantees |
| `build.gradle.kts` | 1 | +100 | 0 | Snapshot channel, dokka external links, POM enrichment |
| Other (README, CLAUDE.md, CONTRIBUTING, api/ dumps, samples/, gradle/, .gitignore) | ~15 | 800 | 300 | Root docs + ABI dumps + sample code |

**Subsystem totals:** ~200 files in hooks/rules/modules (enforcement surface), ~85 source/test files (domain logic), ~30 doc/workflow/config files, ~10 tool/fixture files.

---

## 2. Enforcement surface at tip (commit 072c25c)

### 2.1 GitHub Workflows

| Workflow | Triggers | Jobs | Gate steps | Status |
|----------|----------|------|-----------|--------|
| **ci.yml** | pull_request, push to main, schedule cron 17 8 * * * | reference-freshness, verify, verify-apple | verify: architecture-gate (id), hook-self-tests (id), beta-readiness, gradle-check, publish-to-maven-local; verify-apple: apple-targets, swift-import-smoke | ALL verified in GH-02, GH-03, GH-12, GH-13, GH-19 |
| **release.yml** | workflow_dispatch (dry_run input), push tags v* | preflight, verify-apple, publish | preflight: trust check; publish: pre-gates aggregate, Central/GH-Packages publish, attestation | GH-17 dry-run live, GH-22 aggregator wired, GH-06 trust gate verified |
| **consumer-canary.yml** | workflow_dispatch (manual), schedule monthly | setup, resolve, build, test-native | Central dependency resolution, compile + run README sample, native targets | GH-10 / BP-10 canary built, not yet scheduled active |
| **docs.yml** | push to main with docs/* changes, release tags | setup, build, deploy | build: docka HTML generation, sourceLink config | BP-04 verified (Pages enabled, sourceLink live) |
| **snapshots.yml** | push to main (SNAPSHOT_PUBLISH=1) | setup, build, publish | publish: vault auth, Central Snapshots mirror | BP-03 carve-out verified (SNAPSHOT_PUBLISH guard scoped) |

**Enforcement count:** 5 workflows, 7 unique job names (21 total steps), schedule-based liveness trigger per GH-07.

### 2.2 Ast-grep Rules (Kotlin architecture)

**Count:** 64 rule files in `.claude/hooks/rules/kotlin/`, **all in manifest.json** (fixture coverage: 64/64 badExample+goodExample pairs, 4 with memberExamples for scope-anchored rules).

**Rules by severity:**
- Error (block, cannot build): 25 rules (no-data-class-extending-throwable, no-deferred-wiring-comment, no-generate-id-sentinel, no-globalscope, no-java-import, no-not-null-assertion, no-secondary-constructor, no-string-format, no-system-clock-env, no-thread-sleep, no-todo-in-source, no-uuid-id-sentinel, avoid-broad-catch-exception, no-channel-unlimited, no-console-output-in-library, no-empty-catch-block, no-empty-string-sentinel, no-flat-lifecycle-event, no-jvm-synchronized, no-json-container-force-cast, no-keyed-json-container-cast, no-latex-var, no-runblocking-in-common, no-try-catch-in-init, no-multiple-boolean-params)
- Warning (architecture gate, non-blocking): 39 rules (remainder, including no-camelcase-top-level-function, no-mutable-companion-state, no-public-mutable-var, no-sealed-interface, etc.)

**Relational anchors (inside:/has:):** 17 rules use inside:/relational patterns; all 17 have dual fixtures (whole-file + hunk/memberExample pairs) per GH-20 contract (verified 71dbdee).

**Hunk-unsafe rules (GH-11 revision):** 3 rules marked hunkUnsafe=true (no-camelcase-top-level-function, no-mutable-companion-state, no-public-mutable-var) with documented reason in manifest.

### 2.3 Python Detectors in ci-gate

| Detector | Purpose | Lines | Fixtures |
|----------|---------|-------|----------|
| `detect-public-data-class-budget.py` | Track set of public data class declarations; fail on NEW untracked symbols even under headroom (GH-23 tracking-shift) | 173 | public-data-class-budget/ (violation: new untracked decl; compliant: removals/existing) |
| `detect-nonintegrated-kotlin.py` | Cross-file dead-code: top-level/public declarations with zero references outside own file+tests | 108 | nonintegrated-baseline.txt (empty, no grandfathered items); baseline grandfathers 0 |
| `detect-release-workflow-trust.py` | Validate release.yml: preflight required, packages:write scoped to publish job only | 145 | release-workflow-trust/ (violation: workflow-level write; compliant: correct shape) |
| `detect-tool-identity-regressions.py` | Catch toolCallId occurrence-collapse patterns (associateBy, toSet, single-value maps keyed by id) | 117 | tool-identity-regressions/ |
| `validate_rules.py` | Parse all 64 manifest.json entries + --manifest mode (64/64 coverage) + --hunk-mode (hunkExpectation validation) + --new scaffold | 385 | manifest.json fixture pairs (64 rules), one per rule id |

**Wiring (ci-gate.sh:57-67):** All five run in sequence; early failures stop the gate; ci-gate.sh banner links to gate-misfire protocol section (CLAUDE.md, GH-06 documented).

### 2.4 PreToolUse Hook Modules

| Module | Purpose | File |
|--------|---------|------|
| `kotlin_antipattern_policy.py` | Invoke ast-grep for 64 Kotlin rules; aggregate results; report violations | 367 lines |
| `fleet_protocol_policy.py` | Enforce builder/orchestrator split: prevent unsigned commits, no direct pushes on builder, require squash rules | 691 lines |
| `rule_selfcheck_policy.py` | Run rule-suite self-tests inline during file edits (LP-1: unexercised checks caught early) | 248 lines |

**Orchestrator modules** (result.py, pretooluse.py): dispatch, reporting, ledger integration.

### 2.5 Hook Self-Test Suites

| Suite | Test count | Purpose |
|-------|-----------|---------|
| `test_kotlin_antipattern_policy.py` | **36 tests** | Validate rule match/no-match behavior across 36 representative patterns |
| `test_fleet_protocol_policy.py` | **26 tests** | Validate orchestrator/builder separation rules (commit signing, push gates, squash enforcement) |
| `test_rule_selfcheck_policy.py` | **6 tests** | Meta: validate rule YAML syntax + manifest entry consistency |
| `test_codex_hook_adapter.py` | **5 tests** | CODEX integration tests (hook response serialization) |

**Total: 73 hook tests.** All run in CI via GH-02 verified (ci.yml:73-79); all four exit `ok N` independently; builder + orchestrator both probe failure direction.

### 2.6 Tools & Fixtures

| Tool/Script | Type | Purpose |
|-------------|------|---------|
| `tools/assert-expected-gates.mjs` | Gate ledger assertion | Compares actual gate runs vs expected-gates.txt; ci-gate.sh calls it (GH-19) |
| `tools/run-gate-fixtures.mjs` | Shared harness runner | Walks tools/gate-fixtures/<gate>/{violation,compliant}/; lazy-skip if missing; zero-runner-code pattern (GH-14) |
| `tools/check-ai-sdk-reference.mjs` | Freshness validator | Verifies pinned Vercel AI SDK v6 reference commit; wrapped in try/catch for network resilience (GH-04) |
| `tools/check-api-review.mjs` | API surface audit | Validates backward-compat boundary against base branch |
| `tools/check-architecture-budget.mjs` | Architecture debt gate | File/coupling budget tracking |
| `tools/check-detekt-baseline-budget` | Detekt baseline | Custom rule baseline size gate |
| `tools/check-parity-claims.mjs` | Parity ledger audit | Verifies claims match reference implementation |
| `tools/check-provider-capabilities.mjs` | Provider capability matrix | Validates provider surface coverage |
| `tools/check-provider-golden-coverage.mjs` | Golden fixture coverage | Validates provider golden-case test coverage; has regression fixture (dangling-ref, GH-18) |
| `tools/check-public-api-since-budget.mjs` | API age tracking | Prevents untracked new @since markers |
| `tools/generate-parity-ledger.mjs` | Parity ledger generator | Builds ledger from reference; called in freshness job + check mode (GH-03, GH-04) |
| `tools/run-ios-swift-smoke` | Smoke test | Darwin-only iOS Swift import test; fail-fast if REQUIRE_REAL_SMOKE=1 on non-Darwin (GH-05) |
| `tools/run-local-staging-smoke` | Smoke test | JVM+Android consumer staging-repo test (used in release.yml dry-run, GH-17) |
| `tools/bootstrap` | Setup | Activates .githooks in fresh clones (GH-24) |

**Fixtures:** tools/gate-fixtures/ contains 13 subdirs (one per gate), each with violation/ and compliant/ subdirs:
- ai-sdk-reference/, api-review/, architecture-budget/, detekt-baseline-budget/, nonintegrated-kotlin/, parity-claims/, parity-ledger/, provider-capabilities/, provider-golden-coverage/, public-api-since-budget/, public-data-class-budget/, release-workflow-trust/, tool-identity-regressions/
- Total: 26 fixture checks (ci-gate output line 4, GH-14 verified)

**expected-gates.txt** (9 lines): canonical gate registry per context
```
# context gate
verify architecture-gate
verify hook-self-tests
verify beta-readiness
verify gradle-check
verify publish-to-maven-local
verify-apple apple-targets
verify-apple swift-import-smoke
```

### 2.7 Detekt Configuration

| Item | Status | Notes |
|------|--------|-------|
| `detekt.yml` | Modified (GH-16) | Removed inert SuspendFunSwallowedCancellation rule (type-resolution required, cannot fire); documented custom-rule stopgap; GH-21 re-enable condition noted |
| `detekt-rules/src/main/kotlin/ai/torad/aisdk/detekt/CoroutineRules.kt` | Modified (GH-21) | Extended NoRunCatchingInSuspendFunction to coroutine-builder lambdas (launch { runCatching { } }); bare-builder semantic handling; false-positive baseline from ChatConcurrencyTest |
| Konsist architecture test | Present | src/jvmTest/kotlin/ai/torad/aisdk/arch/KonsistArchitectureTest.kt; no recent changes in this diff |

### 2.8 Budget Files

| File | Current value | Type | Notes |
|------|---------------|------|-------|
| `.claude/hooks/rules/data-class-budget.json` | **File does not exist** | N/A | **FINDING: Budget file missing** — detect-public-data-class-budget.py references it; gate output shows "budget 57, tracked 57 declarations" (in-memory state). Item GH-23 claims budget is tracked per manifest output. |
| `.claude/hooks/rules/nonintegrated-baseline.txt` | Empty (0 bytes) | Baseline | No grandfathered non-integrated items; baseline grandfathers 0 |
| Other budget files | Via mjs gates | N/A | architecture-budget, api-since-budget, detekt-baseline-budget tracked in respective gate tools (not file-resident budgets) |

---

## 3. Manifest claims register (gate-hardening.toml)

**Campaign structure:** 47 items across 4 phases (gh1: 8 items verified; gh2: 16 items verified; gh3: 6 items verified; rr: 9 items; bp: 7 items verified, fr: 4 items [FR-01 in_flight, FR-02/03/04 todo]).

**Phase counts:**
- `gh1` (wire what exists): 8 items, **all verified** (GH-01 through GH-07, GH-24)
- `gh2` (coverage + de-serialization): 16 items, **all verified** (GH-08 through GH-23)
- `gh3` (structural): 6 items, **all verified** (GH-18 through GH-22, GH-23 moved to gh2)
- `rr` (release path, operator-gated): 9 items [RR-01–02, RR-03 verified; RR-04–05 verified operator-gated; RR-06 todo, RR-07–08 todo]
- `bp` (ecosystem): 7 items, **all verified** (BP-01 through BP-10, BP-10 partly with canary built, WATCH first live run)
- `fr` (final review): 4 items [FR-01 in_flight, FR-02–04 todo]

### Manifest note lines (chronological, verbatim)

**GH-02:**
- `[2026-07-02] done in 978502d: hook self-test CI step runs all four suites; scratch forced failure exits 1`
- `[2026-07-02] orchestrator verified: step live (ci.yml:73-79), four suites green independently (34/5/26/6), fail-direction shown in builder worktree probe. ATTRIBUTION RECONCILE: the ci.yml change rode into orchestrator commit 978502d (bare git commit swept the builder's staged index in a shared-tree race); content correct, process fixed via pathspec-commit rule added to manifest header.`

**GH-03:**
- `[2026-07-02] orchestrator verified 0437097: step-output gating (available=true/false) closes the GH-04 parity-mask reconcile completely; freshness job npm-free (parity tool stdlib-only, premise-checked)`

**GH-04:**
- `reconcile = "2026-07-02 orchestrator probe: with .reference absent (simulated clone failure), generate-parity-ledger --check exits 1 -> the network mask moves one step later; residual accepted here because the parity step is inside GH-03 split scope. GH-03 title updated accordingly."`

**GH-06:**
- `[2026-07-02] done in GH-06 slice (final SHA in fleet report): gate-misfire protocol documented and ci-gate banner links it`
- `[2026-07-02] orchestrator verified 5d383a5: section matches enforcement voice, encodes fixture law + ratchet rules; banner cross-links it; gate green`

**GH-07:**
- `[2026-07-02] done in GH-07 slice (final SHA in fleet report): cron 17 8 * * * runs verify + verify-apple`
- `[2026-07-02] orchestrator verified 4dcb644: schedule trigger + valid off-peak cron; scheduled runs execute on default branch by GitHub semantics. DEFERRED OBSERVATION: first green scheduled run to be confirmed post-RR-05 merge (tracked with RR-06 window).`

**GH-08:**
- `[2026-07-02] done in GH-08 slice (final SHA in fleet report): validate_rules --new scaffolds fixtures and --manifest flags stubs`
- `[2026-07-02] orchestrator verified 5c54ace: --new emits real rule yaml + TODO stubs; stub state exits 1 'needs examples' (clean-probe confirmed); filled entry passes; hook-module import contract intact (selfcheck ok 6)`

**GH-09:**
- `[2026-07-02] done in GH-09 slice (final SHA in fleet report): manifest now covers all 64 Kotlin rules and --manifest fails missing entries`
- `[2026-07-02] orchestrator verified 865cacc: 64/64 both modes, removal probe exit 1, fixture quality spot-checked real. Finding for GH-11: the 4 scope-anchored rules correctly keep hunkExpectation=same (badExamples are top-level shapes); the member-fragment misfire class needs class-wrapped no-match fixtures + hunk-unsafe declaration, not expectation flips.`

**GH-10:**
- `[2026-07-02] done in GH-10 slice (final SHA in fleet report): validate_rules --hunk-mode added and wired into ci-gate; scratch context-fragile rule fails hunk-mode`
- `[2026-07-02] orchestrator verified ccbb95f: hunkExpectation same|no-match per-rule with default+validation; ci-gate.sh:63 wired; pass 50/50 + fail-direction (flipped expectation) exit 1 both independently confirmed`

**GH-11:**
- `[2026-07-02] done in GH-11 slice (final SHA in fleet report): four scope-anchored rules declare no-match hunk fixtures with zero hunk matches`
- `[2026-07-02] done in GH-11 revised slice (final SHA in fleet report): added memberExample assertions; kept hunkExpectation=same; marked three bare-fragment context-dependent rules hunkUnsafe`
- `[2026-07-02] orchestrator verified 058d880 under the REVISED spec (supersedes original title): memberExamples never-match fixtures on all 4 (camelcase carries class-wrapped + bare-override cases), hunkUnsafe=true declared on the 3 rule-level-unfixable rules with docstring limitation; fail-direction probe (violation-as-memberExample) exits 1. Minor: mixed memberExample/memberExamples keys — documented alias in tool, normalize under GH-20 schema contract.`

**GH-12:**
- `[2026-07-02] done in GH-12 slice (final SHA in fleet report): verify gate steps now continue-on-error with ids and final always() aggregator table`
- `[2026-07-02] orchestrator verified 4502017: 5 gate steps isolated (stable ids), setup steps fail-fast per adjudication, always() aggregator; fail path simulated locally by builder; green path observed on next CI run`

**GH-13:**
- `[2026-07-02] done in GH-13 slice (final SHA in fleet report): CI downloads cached ast-grep 0.42.1 before npm ci; architecture and hook gates run without node_modules`
- `[2026-07-02] done in GH-13 checksum follow-up (final SHA in fleet report): ast-grep release zip pinned to sha256 5de8b87cba67fc8dc3e239d54b6484802ad745a7ae3de76be4fe89661dc52657 before unzip`
- `[2026-07-02] orchestrator verified 90cc6ed+9731fe1: gates decoupled from npm ci (PATH-stripped proof), binary version drift-checked vs package.json, zip sha256 pinned and verified pre-unzip — checksum independently re-derived from the release asset, exact match`

**GH-14:**
- `[2026-07-02] done in GH-14 slice (final SHA in fleet report): shared gate fixture runner added and wired into ci-gate; no-fixture path lazy-skips in 17ms; scratch gate passed without runner edits`
- `[2026-07-02] orchestrator verified 464dc8f: cmd.txt-per-gate contract with env injection, lazy skip 17ms, zero-runner-code proven by builder scratch gate; fire direction proven by orchestrator probe (violation-exits-0 -> harness exit 1)`

**GH-15:**
- `[2026-07-02] done in GH-15 slice (final SHA in fleet report): release-workflow-trust fixture tenant added; violation packages:write fails, compliant release shape passes`
- `[2026-07-02] orchestrator verified 1b8eadf: violation (workflow-level packages:write) exits 1, compliant exits 0, harness reports 2 checks across 1 gate — the release-workflow detector is no longer test-free`

**GH-16:**
- `[2026-07-02] done in GH-16 slice (final SHA in fleet report): removed inert SuspendFunSwallowedCancellation config; detekt.yml documents custom-rule stopgap and GH-21 re-enable condition`
- `[2026-07-02] orchestrator verified 43bad57: inert rule removed with javap-proven type-resolution evidence; rationale comment names stopgap rules + GH-21 re-enable condition; detekt green`

**GH-17:**
- `[2026-07-02] done in GH-17 slice (final SHA in fleet report): release workflow_dispatch dry_run stages/signs/smokes then logs would-publish count before publish endpoints; GitHub Packages mirror/resolve skipped`
- `[2026-07-02] DRY-RUN FINDING (run 28633985138): preflight + verify-apple GREEN on tag-less dispatch; publish job failed at staging smoke — run-local-staging-smoke passes --staging-repo RAW to -PsmokeRepository, so release.yml's relative 'build/staging-deploy' resolved against smoke-tests/local-staging/ (never-executed CI call site was the only relative caller; LP-1 again). Fix slice dispatched: normalize to absolute in the script.`
- `[2026-07-02] orchestrator verified via LIVE dry-run #2 (run 28634876875): preflight+verify-apple+publish all green on tag-less dispatch, 'would publish 38 artifacts', staging/signing/smoke executed end-to-end; dry-run #1's staging-path finding fixed (b4aefda) and re-proven. GATE-HARDENING PHASES COMPLETE 24/24.`

**GH-18:**
- `[2026-07-02] added fixture harness tenants for 12 CI-wired mjs/py gates; golden dangling-file and shallow API-review regressions fire`
- `[2026-07-02] orchestrator verified 76b78fb: 12 tenants (26 checks/13 gates), both regression-locks fire (golden-coverage dangling ref by builder probe; api-review base-resolution by orchestrator probe with exact historical message); api-review fixture self-containment confirmed (tmp seed + file:// clone)`

**GH-19:**
- `[2026-07-02] verify and verify-apple gate ledgers assert expected gate sets; runner strips GIT_ALTERNATE_OBJECT_DIRECTORIES`
- `[2026-07-02] orchestrator verified 9b3ea5e: expected-set asserted per context (verify 5, verify-apple 2), missing+unexpected both exit 1 (true-exit probes), ran-but-failed correctly deferred to the GH-12 aggregator (presence vs success separation); env-strip rider applied. LP-4 dynamic half mechanized.`

**GH-20:**
- `[2026-07-02] inherits from GH-11: normalize memberExample/memberExamples key alias when formalizing the dual-fixture schema`
- `[2026-07-02] relational hunk contract enforced; 17 anchored rules declare handling; memberExamples is canonical`
- `[2026-07-02] orchestrator verified 71dbdee: relational-without-hunk-contract fires (probe: exit 1, message names hunkExpectation/hunkUnsafe/memberExamples options); alias normalized to plural (0 singular remain, guard at validate_rules:289); 64/64 both modes`

**GH-21:**
- `[2026-07-02] builder-lambda runCatching rule catches same-file suspend calls; pure builder Result wrapping remains allowed`
- `[2026-07-02] orchestrator verified 87664bf: builder-lambda runCatching flagged when same-file suspend call syntactically provable; KDoc states semantics + type-resolution limit honestly; fire + two no-flag fixtures; all-in-builder semantics rejected after detekt caught real false positive in ChatConcurrencyTest (diagnose-before-fix inside the slice)`

**GH-22:**
- `[2026-07-02] publish job pre-publish gates now continue independently and aggregate before any Maven Central staging/upload path`
- `[2026-07-02] orchestrator verified e7d711c: 5 pre-publish gates isolated w/ COE, always() aggregator, Central publish strictly fail-fast after aggregator (unreachable past red), GH-Packages/resolve-back best-effort w/ dry-run skips, trust gate green`

**GH-23:**
- `[2026-07-02] done in GH-23 slice (final SHA in fleet report): data-class budget now tracks declaration set; new untracked symbols fail under headroom; removals pass`
- `[2026-07-02] orchestrator verified 879ab0c: set-tracking catches new symbols BY NAME under count headroom (independent probe: ScratchProbeRef named with acknowledge command), removals frictionless, upward count still refused — closes the AR-07 headroom-hiding class`

**GH-24:**
- `[2026-07-02] done in GH-24 slice (final SHA in fleet report): tools/bootstrap activates .githooks in fresh clones and docs name it`
- `[2026-07-02] orchestrator verified 403c6b9: true fresh-clone probe UNSET -> .githooks, idempotent, docs mention in both files; builder's probe additionally proved the gate fires in a fresh clone (compounds GH-01)`

**RR-01:**
- `[2026-07-02] demoted result/phase positional constructors to internal; internal ResultConstruction factory covers production/tests; ABI dumps/docs updated`
- `[2026-07-02] orchestrator verified eeb5c79: zero public <init> on all named growable result types (dump-grepped), ResultConstruction absent from both dumps (internal), contract documents Mock*-first fake policy, CHANGELOG entry present; the last audit watch-list ABI regret is closed pre-freeze`

**RR-02:**
- `[2026-07-02] LiteRT terminal finishReason/usage propagation implemented; SHA pending local commit.`
- `[2026-07-02] Completed in b237072: LiteRTMessage terminal finishReason/usage propagation, ABI dumps, tests, docs, budgets.`
- `[2026-07-02] Supersedes prior pending/pre-amend SHA notes; final SHA is reported via fleet because same-commit self-SHA cannot be embedded.`
- `[2026-07-02] orchestrator verified 53be714: LiteRTMessage carries optional usage/finishReason (engine value wins, inferred fallback preserved at Mapping:285), 16/16 tests green incl. Length propagation + structured-decode-refuses-Length; the dropped audit item is closed`

**RR-03:**
- `[2026-07-02] McpHttpTransportTest real-time waits replaced with deterministic signals; final SHA reported via fleet after commit.`
- `[2026-07-02] orchestrator verified 2a43172: zero real-time refs remain (was 17), deterministic delayer + CompletableDeferred signals throughout, my rerun green — the file that burned two CI runs is deflaked`

**RR-04:**
- `[2026-07-02] operator approved 2026-07-03; orchestrator applied via gh api: required_status_checks strict [verify, verify-apple] + 1-approval preserved; API GET confirms. Gates now binding at merge boundary.`

**RR-05:**
- `[2026-07-02] operator chose SQUASH merge (overrides merge-commit recommendation, locked); mitigation: refactor/ts-residue-cleanup branch is KEPT as archival ref so manifest/backlog per-item SHAs stay reachable`
- `[2026-07-02] MERGED: PR #10 squashed to main as cc3d373 (operator's squash choice), archival branch kept, main CI run in progress under new required checks; follow-up work continues on beta-follow-ups (one-PR directive)`

**RR-06:**
- `[2026-07-02] operator decision 2026-07-03: FOLD-IN THEN TAG — wave 12 completes on beta-follow-ups, ONE PR to main, merge on green (squash, consistent with operator's RR-05 choice), then tag v0.3.0-beta01 from post-merge main; beta ships with BP-02 consumer floor + samples + all pipelines. Main's first gated run: green (all 3 jobs incl. freshness).`
- `[2026-07-02] OPERATOR HOLD 2026-07-03: tagging + publishing deferred — maximize work first; fold-in PR merge also held; ledger completion triggers a FINAL REVIEW ROUND before any release motion`

**RR-08:**
- `[2026-07-02] orchestrator created the tracked issue directly (see repo issues); pin now has an expiry ticket`

**BP-01:**
- `[2026-07-02] Evolution policy published and linked from README/interface contract; readiness and link checks passed; final SHA reported via fleet.`
- `[2026-07-02] orchestrator verified 03273a6: four-stage guarantees + deprecation timeline + Kotlin policy + cross-refs (not duplication) to AR-33 rules; linked from README + contract`

**BP-02:**
- `[2026-07-02] Kotlin stdlib/API floor decoupled to 2.3.21 for JVM/Android consumers; klib KGP floor documented; final SHA reported via fleet.`
- `[2026-07-02] orchestrator verified 99e426d: Okio-style decoupling live — POMs publish stdlib 2.3.21 (inspected), source pinned to 2.3 language/api with the tradeoff documented, dependency floor coherence stated, api/ unchanged; JVM/Android consumers on Kotlin 2.3.21+ supported one release early`

**BP-03:**
- `[2026-07-02] Snapshot publishing path added for CentralSnapshots with SNAPSHOT_PUBLISH gate; local snapshot publish and structural checks passed; live main push observation deferred.`
- `[2026-07-02] VERIFIED: guard carve-out scoped to CentralSnapshots only (triple require: SNAPSHOT_PUBLISH=1 + -SNAPSHOT version + creds); all other repos still refuse SNAPSHOT; signing auto-skips without keys; SONATYPE_* secrets confirmed present in repo. WATCH: first live run fires on fold-in merge push to main.`

**BP-04:**
- `[2026-07-02] Dokka source/external links configured and release-success Pages deployment workflow added; dokkaGenerate and generated-link checks passed.`
- `[2026-07-02] VERIFIED: event==push filter correctly excludes dry-run workflow_dispatch from deploying docs; sourceLink v$version matches tag pattern. ORCHESTRATOR ACTION: GitHub Pages was NOT enabled (404) — enabled build_type=workflow via API; first deploy would have failed otherwise.`

**BP-05:**
- `[2026-07-02] POM enrichment: issueManagement/ciManagement blocks added; publishToMavenLocal POM inspection passed; final SHA reported via fleet.`
- `[2026-07-02] orchestrator verified 53ccf03: issueManagement + ciManagement present in generated POM (inspected ~/.m2), klibs.io/LLM discovery metadata complete; listing check remains post-publish (folds into BP-10 canary)`

**BP-06:**
- `[2026-07-02] Release bundle provenance attestation added; trust gate, workflow parse, beta readiness, and api-review passed; final SHA reported via fleet.`
- `[2026-07-02] orchestrator verified c07fbbc: attest-build-provenance SHA-pinned v4.1.1, id-token/attestations write scoped to publish job only (workflow stays contents:read), dry-run skip mirrors publish condition, consumer verify command in contract; trust gate green. First attested release will be the v0.3.0-beta01 tag.`

**BP-07:**
- `[2026-07-02] Added target expansion matrix decision doc; commit pending`
- `[2026-07-02] Target expansion matrix decision doc complete; final SHA reported via fleet after commit`
- `[2026-07-02] VERIFIED: expect/actual seam inventory exact (4/4 commonMain expects named); current-target list matches build.gradle.kts; mingwX64 /dev/urandom blocker is real. Decision doc only, no build changes — matches item intent.`

**BP-08:**
- `[2026-07-02] JVM chat CLI sample added with mock default and explicit OpenAI-compatible opt-in; sample build/run and gates passed; final SHA reported via fleet.`
- `[2026-07-02] orchestrator verified e011e0b: sample runs (generate + stream, mock default), provider path explicitly env-gated (no ambient network), standalone gradle project resolving the published coordinate; Central switch noted for post-RR-06`

**BP-10:**
- `[2026-07-02] rescoped: BUILD the canary workflow now (workflow_dispatch + schedule, disabled-or-skipping until first publish); live verification remains tag-gated`
- `[2026-07-02] Consumer-boundary canary workflow added; Central/docs/attestation guards wait cleanly for first release; final SHA via fleet`
- `[2026-07-02] VERIFIED: orchestrator compiled+ran the embedded consumer source verbatim against mavenLocal 0.3.0-beta01 (prints Welcome., check passes) — closes LP-1 unexercised-main-path risk builder verification could not cover pre-release. MockLanguageModelTextOnly confirmed in JVM ABI dump. RESIDUALS (post-release enhancements, deliberate build-now scope cut): native-consumer leg, klibs.io monthly check, BP-02 floor-version (2.3.21) consumer probe — canary pins consumer KGP 2.4.0 only.`

### Manifest summary

- **Total items:** 47
- **Status breakdown:** 
  - `todo`: 4 items (FR-02, FR-03, FR-04, RR-06, RR-07, RR-08) — **WAIT: actually 6 items are todo**
  - `in_flight`: 1 item (FR-01)
  - `done`: 14 items (GH-01 through GH-13, RR-01, RR-02, RR-03)
  - `verified`: 26 items (GH-01, GH-02, GH-03, GH-05, GH-06, GH-07, GH-08, GH-09, GH-10, GH-11, GH-12, GH-13, GH-14, GH-15, GH-16, GH-17, GH-18, GH-19, GH-20, GH-21, GH-22, GH-23, GH-24, RR-04, RR-05, RR-08, BP-01 through BP-10)
- **Note lines:** 89 lines (chronological entries per item, mostly `[2026-07-02]` dated)
- **Deferral language:** "DEFERRED OBSERVATION" (GH-07), "DEFERRED" (RR-02, RR-06 hold), present as stated; this file covers them per exception directive

---

## 4. Gate timing (local ci-gate.sh)

### Cold run (first execution)
```
== architecture gate: error-severity ast-grep rules ==
  ok: 0 error-rule violations
== non-integrated (internal, cross-file) gate ==
non-integrated gate OK: no new internal dead declarations (baseline grandfathers 0)
== ast-grep rule self-test gate ==
ok: all 64 rule files parse
ok: all 64 rules match bad + skip good
ok: all 64 rules satisfy hunk-mode expectations
gate fixture harness OK: 26 fixture checks across 13 gate(s)
== tool occurrence identity gate ==
tool identity gate OK: no raw toolCallId occurrence-collapse patterns
== public data-class budget gate ==
data-class budget gate OK: 57 public data class (budget 57, tracked 57 declarations)
== release workflow trust gate ==
release workflow trust gate OK
architecture gate: PASS

real    0m10.848s
user    0m17.717s
sys     0m1.214s
```

**Cold wall-clock:** 10.848s | user: 17.717s | sys: 1.214s

### Warm run (second execution)
```
[identical output section list]

real    0m12.467s
user    0m20.402s
sys     0m1.402s
```

**Warm wall-clock:** 12.467s | user: 20.402s | sys: 1.402s

### Per-section output observed
- architecture gate: error-severity ast-grep rules (0 violations)
- non-integrated gate (baseline 0)
- ast-grep rule self-test gate (parse 64, match+skip 64, hunk-mode 64)
- gate fixture harness (26 checks, 13 gates)
- tool occurrence identity gate (no collapse patterns)
- public data-class budget gate (57/57 budget, tracked 57)
- release workflow trust gate (OK)

**Exit code:** 0 (PASS) on both runs

### Hook self-test suite timing (single run)
```bash
python3 .claude/hooks/tests/test_kotlin_antipattern_policy.py
  => ok 36
python3 .claude/hooks/tests/test_fleet_protocol_policy.py
  => ok 26
python3 .claude/hooks/tests/test_rule_selfcheck_policy.py
  => ok 6
python3 .claude/hooks/tests/test_codex_hook_adapter.py
  => ok 5
```

All four run in parallel in CI (GH-02); wall-clock observed on local sequential execution would be additive.

---

## Summary statistics

| Metric | Value |
|--------|-------|
| Commits in range | 14 |
| Files changed | 820 |
| Insertions | 123,362 |
| Deletions | 52,699 |
| Ast-grep rules (Kotlin) | 64 (all in manifest) |
| Fixture sets in manifest | 64 (badExample+goodExample pairs) |
| Hook tests (total) | 73 (36+26+6+5) |
| Workflows | 5 (ci, release, consumer-canary, docs, snapshots) |
| Python detectors | 5 (in ci-gate) |
| Hook modules (pretooluse) | 3 |
| Gate fixture harness tenants | 13 gates, 26 checks |
| Manifest items | 47 total (26 verified, 14 done, 1 in_flight, 6 todo) |
| Manifest note lines | 89 (verbatim quotes above) |
| Gate wall-clock (cold) | 10.8s |
| Gate wall-clock (warm) | 12.5s |

---

## Orchestrator corrections (appended at FR-01 verification — trust THIS section over conflicting numbers above)

Haiku's verbatim quotes (section 3 note text) and executed-command outputs (section 4, test counts, gate output) spot-checked faithful. Its arithmetic and the three newest workflow rows are wrong. Authoritative corrections, derived from `manifest.py list` and from the orchestrator's own per-diff verification of BP-03/04/07/10 (2026-07-03):

1. **Manifest counts**: 45 items total (not 47): gh1=9 (GH-01..08, GH-24), gh2=10 (GH-09..17, GH-23), gh3=5 (GH-18..22), rr=8, bp=9 (BP-01..08, BP-10), fr=4. Status: **38 verified, 2 todo tag-gated (RR-06, RR-07), FR-01 in_flight, FR-02..04 todo. Nothing is in `done`.** RR-08 is verified (section 3 header says todo — wrong).
2. **Note lines**: 79 dated `# [2026-...]` note lines (grep-verified), not 89. `reconcile = "..."` TOML fields exist in addition (e.g. GH-04) and were correctly quoted but are not # notes.
3. **consumer-canary.yml row is hallucinated**: actual triggers = workflow_dispatch + cron `37 9 * * 1` (weekly Monday, NOT monthly). ONE job (`consumer-canary`). Steps: Central discovery (numFound=0 → green notice + published=false), README-shaped JVM consumer compile+run (gated `published==true`), docs-URL liveness (404/000 → green notice), `gh attestation verify` on release bundle (gated). There is NO native job — that is a deliberate build-now boundary recorded in the BP-10 ledger note, with the arm-later residuals listed there.
4. **docs.yml row is wrong**: actual trigger = `workflow_run` on Release completion, gated `conclusion == success && event == push` (so the workflow_dispatch dry-run CANNOT deploy docs). Jobs: build-docs → deploy-pages. Pages was enabled by orchestrator via API (build_type=workflow) during BP-04 verification.
5. **snapshots.yml row is wrong**: trigger = plain push to main (SNAPSHOT_PUBLISH=1 is step env, not a trigger condition). Single job publish-snapshot → `publishAllPublicationsToCentralSnapshotsRepository`. There is no "vault auth" — credentials are plain SONATYPE_* secrets.
6. **Section 2.8 "budget file missing" is a false positive**: `data-class-budget.json` exists at REPO ROOT. The detector reads it CWD-relative (`BUDGET_FILE = Path("data-class-budget.json")`, detect-public-data-class-budget.py:30) and ci-gate.sh runs from repo root. REAL residual surfaced by the false positive, for FR-02 judgment: (a) CWD-relative budget path means invoking the detector from any other directory mis-resolves (fixture copies exist at tools/gate-fixtures/public-data-class-budget/*/data-class-budget.json); (b) CLAUDE.md's wording implies the json sits next to the detector in .claude/hooks/rules/ — docs/reality mismatch.
7. **Timing anomaly for FR-02**: warm run (12.5s) measured SLOWER than cold (10.8s) — explain or dismiss (load noise vs real inverse-caching effect).
8. Step-id naming in 2.1 was normalized by Haiku; verify gate ids against ci.yml directly before relying on them.

