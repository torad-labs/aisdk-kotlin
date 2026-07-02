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
  traceable without archaeology (a careful analysis agent misclassified 6 CI-wired
  gates as orphaned because script-calls-script indirection hid the wiring).

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
