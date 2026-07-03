# claude-directive-exception: estimation_deferral verbatim quotation of campaign ledger notes for the FR review; deferral wording here is inventoried data, not deferred work

# ast-grep Kotlin rule package — discovery inventory and dedupe audit (HD-03)

Companion to [enforcement-layers.md](enforcement-layers.md) (the four-layer
model) and [data-class-audit.md](data-class-audit.md) (the public data-class
migration, a dedupe example this audit reuses rather than duplicates). This
document is the standing dedupe table CLAUDE.md's "Ast-grep rule authoring"
section points to: consult and extend it before adding a new rule to
`.claude/hooks/rules/kotlin/`.

## Method

Every hypothesis below was checked against the real `src/commonMain/kotlin`
tree with the ast-grep MCP tools (`find_code_by_rule` for real-tree false
positives, `test_match_code_rule` for bad/good proof, `dump_syntax_tree` to
confirm node kinds) — not asserted from reading code alone. grep/rg was used
only for locating files and non-code text (this doc, YAML, markdown), per the
repo's tool-usage law.

## Dedupe table — invariants considered, existing owner, verdict

One owner per invariant (CLAUDE.md). "Owner exists" entries are collisions
avoided: a rule was NOT added because an existing tool already owns the
check. "New edit-time layer" entries mirror an existing Konsist/detekt
invariant at an earlier layer, which `enforcement-layers.md`'s own four-layer
model treats as closing a gap, not duplicating.

| # | Invariant | Existing owner(s) | Layer(s) already covering it | Verdict |
|---|---|---|---|---|
| 1 | providers/ must not import ui | Konsist (`KonsistArchitectureTest.providers do not depend on ui or framework layer`) | 2 (gradle check only) | **Gap at layer 1** — no ast-grep/detekt edit-time mirror existed. Tier-1 rule added: `no-providers-import-ui`. |
| 2 | non-providers code must not import providers | Konsist (`core agent protocol and ui layers do not import provider implementations`) | 2 (gradle check only) | **Gap at layer 1.** Tier-1 rule added: `no-core-import-providers`. |
| 3 | protocol/ must not import ui or middleware | Konsist (`protocol codecs stay below agent runtime and ui layers`, import half) | 2 (gradle check only) | **Gap at layer 1.** Tier-1 rule added: `no-protocol-import-ui-or-middleware`. |
| 4 | protocol/ must not reference AgentEvent/AgentSession/ToolLoopAgent/ToolLoopAgentEngine | Konsist (same test, text-containment half) | 2 (gradle check only) | **Gap at layer 1.** Tier-1 rule added: `no-protocol-agent-runtime-reference` (narrower than Konsist's raw text-contains: identifier-node-scoped, so a comment mentioning these names is not flagged — consistent with this repo's node-scoped-regex-only convention). |
| 5 | `data class …Event` must belong to a sealed `AgentEvent` hierarchy | ast-grep `no-flat-lifecycle-event` (layer 1) + Konsist (layer 2, deliberate backstop) | 1 and 2 | **Collision avoided.** Already owned at both layers by design; no new rule. |
| 6 | no sealed interfaces in production except `@Serializable`/private | ast-grep `no-sealed-interface` (layer 1) + Konsist (layer 2, deliberate backstop — Konsist's own comment says it caught a real ast-grep pattern gap, since fixed) | 1 and 2 | **Collision avoided.** Already owned at both layers by design; no new rule. |
| 7 | every `expect` has an `actual` per compiled target | Kotlin compiler | 4 | **Owned by the compiler.** A missing actual is a compile error; not ast-grep's job. No rule. |
| 8 | `expect` declarations stay `internal`, never public ABI | nobody | none | **Real gap, no existing owner.** Tier-1 rule added: `no-public-expect-declaration`. All 4 existing seams (`SecureRandom`, `CreateMCPStdioProcess`, `AiSdkJvmStatic`, `AbortErrorCancellationBridge`) are internal, confirming the convention. |
| 9 | public `data class` ABI budget | `detect-public-data-class-budget.py` + `data-class-budget.json` (explicitly documented in CLAUDE.md "Public value types") | 3 (ci-gate, non-bypassable) | **Collision avoided — explicit instruction.** CLAUDE.md already names this the single owner; not duplicated. |
| 10 | construct-types get a builder/DSL, not a frozen positional ctor | data-class budget gate + manual case-by-case migration (`docs/data-class-audit.md`, BL-058/A1) | 3 + ongoing human review | **Collision avoided.** The migration is already tracked per-type with owner judgment calls (e.g. D11 KEEP overrides); a blanket structural ast-grep rule would fight an audit that already has documented exceptions. No rule. |
| 11 | `runCatching` inside a `suspend fun` swallows `CancellationException` | detekt custom rule `NoRunCatchingInSuspendFunction` (`CoroutineRules.kt`) + ast-grep mirror `no-runcatching-in-suspend` | 1 and 2 | **Gap closed in HD-07.** Operator approved the full detekt-semantics mirror; fixtures mirror all 5 detekt test cases and whole-tree comparison found zero unexplained deltas. |
| 12 | `catch (x: Throwable)` that never rethrows swallows `CancellationException` | nobody (`avoid-broad-catch-exception` only targets `catch (e: Exception)`; detekt's `SwallowedException` targets an unreferenced-variable shape, not this one) | none | **Real gap, no existing owner.** Tier-1 rule added (warning severity — see below): `no-throwable-catch-without-rethrow`. |
| 13 | `generate()`/`stream()` keep the Flow-of-one cold-Flow shape (DEC-1 / AR-06, user-locked 2026-07-02, "keep Flow-of-one") | ABI dumps (`api/*.api`, `api/*.klib.api`) + the API-review gate, for regressions to an *existing* signature | 3/4 (ABI diff) | **Substantially covered already; tier-2, not recommended** — see below. |
| 14 | MCP transport implementations go through one "doorway" | *(hypothesis explored, no crisp structural invariant found)* | — | **Explored, inconclusive.** `MCPTransport`/`HttpMCPTransport`/`SseMCPTransport`/`Experimental_StdioMCPTransport` and the sibling `CompletionTransport`/`GatewayTransport`/`StructuredObjectTransport` interfaces are a real, consistent naming pattern (interface + internal `Direct*` default + swappable impl), but there is no single "doorway" call site or anti-pattern to structurally forbid — the type system already enforces the interface boundary. No rule proposed. |
| 15 | provider request bodies use typed mapping, not raw JSON string assembly | *(hypothesis explored, found no violations)* | — | **Explored, no evidence of the anti-pattern.** Zero `"""{ ... }` raw-JSON-template hits anywhere under `providers/`. Some providers factor mapping into dedicated `*Mapping.kt`/`*RequestMapping.kt` files and others don't, but that split is organizational, not a violable invariant. No rule — nothing to protect against today. |
| 16 | JsonElement/JsonObject/JsonArray force-casts and unsafe container access | ast-grep `no-json-container-force-cast`, `no-keyed-json-container-cast`, `no-provideroptions-jsonobject-cast` (layer 1) + detekt `NoJsonContainerForceCast`, `NoInlineJsonInstance` (layer 2) | 1 and 2 | **Collision avoided.** Already thoroughly covered at two layers; no rule. |
| 17 | new/experimental public API should be marked `@ExperimentalAiSdkApi` | *(hypothesis explored, rejected)* | — | **Not ast-grep-enforceable at useful precision.** Whether a given new public declaration is "risky enough" to need the annotation is a design judgment ast-grep cannot infer from syntax; a rule here would be pure noise. No rule. |

**Collision count: 5** (rows 5, 6, 9, 10, 16) — invariants with a confirmed
existing owner where a duplicate rule was deliberately not added. Two further
rows (7, 17) were ruled out for different reasons (compiler-owned;
not structurally decidable) and are not counted as dedupe collisions since no
tool currently claims them.

## Tier 1 — implemented across HD-03 and HD-07 (7 rules, 64 → 71)

All 7 pass `validate_rules.py` parse mode, `--manifest` semantic mode
(matches badExample, skips goodExample), and `--hunk-mode`; all were probed
with `find_code_by_rule` against the full real `src/commonMain/kotlin` tree
before being assigned a severity (zero real matches for the 5 `error` rules;
`no-throwable-catch-without-rethrow` is `warning` specifically because it has
real, grandfathered matches — see its own rationale; HD-06 later reduced those
to the deliberate AbortSignal callback swallow only).

| Rule id | Invariant | Severity | Evidence (real file:line) | Why detekt/Konsist/ktlint can't own the edit-time layer | FP risk |
|---|---|---|---|---|---|
| `no-providers-import-ui` | providers/ must not import ai.torad.aisdk.ui | error | Konsist test at `KonsistArchitectureTest.kt:47-51` | Konsist only runs at `./gradlew check`; detekt has no cross-package-import primitive | None found — 0 real matches; rule fires only on the exact import+package combination |
| `no-core-import-providers` | non-providers code must not import ai.torad.aisdk.providers | error | Konsist test at `KonsistArchitectureTest.kt:53-58` | same as above | None found — 0 real matches |
| `no-protocol-import-ui-or-middleware` | protocol/ must not import ui or middleware | error | Konsist test at `KonsistArchitectureTest.kt:60-77` (import half) | same as above | None found — 0 real matches |
| `no-protocol-agent-runtime-reference` | protocol/ must not reference AgentEvent/AgentSession/ToolLoopAgent/ToolLoopAgentEngine | error | Konsist test at `KonsistArchitectureTest.kt:60-77` (text-containment half) | same as above; also stricter than Konsist (identifier-node-scoped, not raw text) | None found — 0 real matches |
| `no-public-expect-declaration` | `expect` declarations stay internal | error | All 4 existing seams internal: `SecureRandom.kt:14`, `MCP.kt:2049`, `JvmInteropAnnotations.kt:5`, `AbortSignal.kt:171` | No existing tool checks expect/actual visibility (the compiler only checks actual-exists, not visibility discipline) | None found — 0 real matches |
| `no-throwable-catch-without-rethrow` | `catch (x: Throwable)` must propagate (rethrow, wrapped-rethrow, or explicit `_`-discard idiom), not silently convert cancellation into a value | warning | ~20 existing non-conforming call sites, e.g. `AgentTelemetryDispatcher.kt:36`, `SmoothStream.kt:175`, `CompletionApi.kt:456`, `Telemetry.kt:271`, `Tool.kt:625`, `ui/Streams.kt:106,231`, `AgentSession.kt:484` | `avoid-broad-catch-exception` only targets `catch (e: Exception)`, not the broader/more dangerous `Throwable`; detekt's `SwallowedException` targets an unreferenced-variable shape, a different code smell | Real baseline exists (~20 sites) — this is exactly why severity is `warning`, not `error`: ci-gate.sh only enforces zero-violations for `error` severity, and the incremental PreToolUse hook grandfathers all pre-existing matches, nudging only on new ones |
| `no-runcatching-in-suspend` | stdlib `runCatching` in suspend/coroutine-builder code must not capture `CancellationException` into `Result` | warning | Custom detekt rule `NoRunCatchingInSuspendFunction` at `CoroutineRules.kt:32-105`; fixture mirror covers its 5 tests in `CoroutineRulesTest.kt` | Detekt remains the blocking owner, but it only fires at `./gradlew check`; ast-grep gives edit-time feedback for the same two branches: under a suspend named function, and inside coroutine builders when the body calls a same-file suspend function | Zero real matches in `src/`; the `ChatConcurrencyTest` `launch { runCatching { chat.sendMessage(...).collect {} } }` shape intentionally does not match because detekt's type-free heuristic only accepts same-file suspend declarations |

Fixture counts: 7/7 new manifest entries carry `badExample` + `goodExample`;
the 4 import/reference rules plus `no-runcatching-in-suspend` (relational,
using `inside:`) additionally declare `hunkExpectation: "same"` per the GH-20
dual-fixture contract; the other 2 (no `inside:`/`precedes:`/`follows:`) are
not relational under `validate_rules.py`'s own definition and use the default
hunk expectation. For `no-runcatching-in-suspend`, the semantic bad fixture is
the detekt builder-lambda positive case, `badHunkExample` is the suspend
function positive case, and the good fixture includes the three detekt negative
cases.

**Finding surfaced, not fixed (fenced from `src/`):** the ~20
`no-throwable-catch-without-rethrow` matches are a real, pre-existing
cancellation-safety gap spanning core agent-loop code (`ToolLoopAgent.kt`
alone has 6). They are not blocking today (warning severity, grandfathered),
but the count and file list above are handed to the operator as evidence for
a dedicated hardening item — this rule package's job was to find and encode
the invariant, not to rewrite ~20 catch blocks in production source.

## Tier 2 — operator dispositions

| Candidate | Invariant | Why it is real | Why it is tier 2, not tier 1 |
|---|---|---|---|
| `no-runcatching-in-suspend` | `runCatching { }` used where the wrapped call is suspend swallows `CancellationException`, mirroring detekt's `NoRunCatchingInSuspendFunction` | Detekt rule is active, tested (`CoroutineRulesTest.kt`), and was extended in GH-21 — real, current investment | **Approved and implemented in HD-07.** The ast-grep rule mirrors detekt's two branches and carries fixture coverage for all 5 detekt tests. |
| Flow-of-one `generate()`/`stream()` shape protection | Protect the cold-`Flow<GenerateTextResult<T>>` return shape of `generate`/`stream` overloads, per the user-locked DEC-1/AR-06 decision ("keep Flow-of-one", `docs/audit-remediation-backlog.md:17`) | The decision is real, explicit, and user-locked; a regression would be a meaningful design violation | **Killed by operator on 2026-07-03.** ABI dumps + the API-review gate remain the owner for this invariant; do not add an ast-grep mirror. |

Both tier-2 rows are reported per this campaign's Phase-1 instruction
(implement tier 1, report tier 2, do not implement tier 2 without operator
sign-off) — neither has been added to `rules/kotlin/` or `manifest.json`.

## utils/ DRY pass — reported, not implemented, tension flagged

The HD-03 ledger item (`dev/campaigns/gate-hardening.toml`) lists
`.claude/hooks/rules/utils` among files to touch and names "a utils/ DRY pass
over the 64 rules where composable" as part of the mission. The orchestrator
premise addendum's Phase 2–5 breakdown, which governs this session, does not
mention a utils/ pass in its lettered instructions and does not test for one
in its acceptance criteria. Several existing rules do share a recognizable
fragment (`not: has: { kind: visibility_modifier, regex: '^(private|internal|protected)$', stopBy: end }`
appears in `no-public-mutable-collection-val`, `no-public-mutable-collection-ctor-prop`,
`no-any-typed-public-property`, `no-mutable-collection-in-public-fun`, and
now this session's boundary rules use a similar `package_header` fragment) —
so a `utilDirs`-based extraction is plausible. It was not attempted in this
session: the addendum's explicit fences forbid changing the severity or
semantics of any existing rule, and rewriting 4+ existing rules to reference
a shared `matches:` utility — even if behavior-preserving in intent — is
exactly the kind of edit that could silently drift an existing rule's
behavior, which is a bigger risk than the DRY win is worth inside a session
already delivering 6 new rules and a doctrine merge. Reported per the
premise-check duty for operator judgment; not implemented.
