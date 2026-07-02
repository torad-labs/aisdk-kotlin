# Audit remediation backlog (pre-beta)

Source: full-codebase beta-readiness audit, 2026-07-02 (5 criteria scored: API/ABI 7,
tests 7, robustness 8, docs 5, release 5). Every audit suggestion is tracked here.
No item ships to `main` unmerged or unverified.

- **Owner (implementation):** `aisdk-builder` (codex, tmux fleet session)
- **Orchestrator / validator / reviewer:** Claude (this session). Every work package
  is reviewed against acceptance criteria + gates before it counts as DONE.
- **Status legend:** `OPEN` · `DISPATCHED` · `IN REVIEW` · `DONE` · `VETOED`
- **Working agreement applies to every commit:** `bash .claude/hooks/rules/ci-gate.sh`
  green, no `--no-verify`, API changes regenerate ABI dumps + update `CHANGELOG.md`
  and `INTERFACE_CONTRACT.md`.

## Decision log (orchestrator decisions — veto window open until the owning WP is dispatched)

- **DEC-1 (AR-06): OVERRULED BY USER (2026-07-02).** Marcos chose to **keep Flow-of-one**
  for `Agent.generate` / `TextGenerator.generate`. AR-06 rescoped: no API change; instead
  ship prominent KDoc on every one-shot `generate` overload stating it is a cold flow,
  each collection re-runs the full generation *including tool side effects*, and the
  intended call shape is `.first()`.
- **DEC-2 (AR-03):** Pin the Vercel reference gate to `ai@6.0.208` for the beta and make
  the upstream-freshness check **non-blocking** (warning annotation, or separate
  non-required job). A hard-fail on an upstream event masked every downstream CI check
  for 8 days; a freshness signal must never again hide detekt/tests/ABI.
- **DEC-3 (AR-01):** Detekt red is fixed by **auto-correcting/fixing findings, not by
  growing the baseline**. `detekt-baseline-budget.json` is a ratchet: total entries must
  end **≤ 3687** (downward re-seed allowed, upward never).

---

## WP-V — Relight the verification chain (first; everything else validates through it)

| ID | Task | Status |
|----|------|--------|
| AR-01 | Fix `:detekt` red at HEAD (5,610 findings vs stale 3,687-entry baseline). Prefer `--auto-correct` for the formatting classes (ArgumentListWrapping 1427, Indentation 1401, Wrapping 599, NoUnusedImports 497); fix or legitimately re-baseline the remainder per DEC-3. Accept: `./gradlew check` green locally; baseline entries ≤ 3687. | DONE (eeb63e3 reviewed: baseline 3687→1947, budget re-seeded down, 288-file mechanical reformat, 0 deletions, `check` green) |
| AR-02 | `verify-apple` dependency verification: add the 7 macOS-resolved artifacts missing from `gradle/verification-metadata.xml` (guava-parent-33.3.1-jre.pom, jackson-base-2.15.3.pom, junit-bom-5.9.2.pom/.module, junit-bom-5.10.2.module, junit-bom-5.11.0-M2.module, kotlinx-coroutines-bom-1.8.0.pom). sha256 obtainable from repo1.maven.org on Linux. Accept: `verify-apple` CI job passes. | DONE (da3340b reviewed: all 7 entries present; every sha256 independently re-fetched from repo1.maven.org and matched; CI confirmation folds into AR-04) |
| AR-03 | Reference gate per DEC-2: pin `AI_SDK_REFERENCE_VERSION=6.0.208`, demote `tools/check-ai-sdk-reference.mjs` newer-upstream failure to a warning (still hard-fail on *mismatched pin*), so the `verify` job proceeds to gate/detekt/tests. Accept: Linux `verify` job runs all steps. | DONE (4b3569b reviewed: warn path exit 0 + ::warning:: annotation verified locally; pin-mismatch hard-fail verified; tool now also self-checks the reference commit) |
| AR-04 | Acceptance gate for WP-V: one fully-green CI run (both `verify` and `verify-apple`) on PR #10 at the remediation head. | **DONE — run 28616137989 at dc9ba8a: verify ✓ verify-apple ✓.** First fully-green CI since 2026-06-23, and the first ever with all hardening gates actually executing. Took 5 rounds; en-route discoveries fixed: mac prebuilt + coroutines-bom verification entries, fetch-depth base resolution, never-functional Swift smoke swiftc invocation, AR-34 metadata-compile regression (+ check now compiles common metadata as a permanent antibody). |

## WP-H — Ten-minute hygiene items (parallel with WP-V)

| ID | Task | Status |
|----|------|--------|
| AR-25 | Delete stale `v0.5-beta` tag from origin (points at a 0.1.0-SNAPSHOT commit; sorts above real versions). | DONE (orchestrator, 2026-07-02: verified target was 0.1.0-SNAPSHOT commit 3877a4a, deleted remotely + locally) |
| AR-26 | Replace short-form `LICENSE` (623 bytes) with the full Apache-2.0 text so GitHub/licensee detection works. | DONE (9c0ee0c reviewed: whitespace-insensitive diff vs canonical apache.org text = identical) |
| AR-27 | Fix `SECURITY.md:5` false claim ("does not include network providers by default" — the artifact ships ~40 Ktor-backed providers). | DONE (7c58492 reviewed: claim corrected, actionable secret-handling guidance added) |
| AR-28 | README targets: correct the iOS claim (Maven klibs via KMP, not a shipped framework — or document the XCFramework story if shipping it) and add the published `linuxX64` target to the list. | DONE (c6bc191 reviewed: accurate iOS klib/XCFramework wording, linuxX64 added, `--strict-readme` green) |

## WP-A — API/ABI regrets (before the dump freezes; blocked by WP-V)

| ID | Task | Status |
|----|------|--------|
| AR-05 | `ToolLoopAgent`: add a settings-based public constructor (reuse `AgentSettings<TContext>`, `Context.kt:41`) and demote the 26-param ctor (`ToolLoopAgent.kt:64-178`) from the frozen surface (internal or `@Deprecated(HIDDEN)` path). Regenerate ABI dumps; update CHANGELOG + INTERFACE_CONTRACT. | DONE (73b4e8d reviewed: 26-param ctor gone from JVM+klib dumps incl. $default; settings ctor + 5 convenience params; first version REJECTED for string-keyed-map builder gate-dodge, amended to typed methods + @Suppress justification; typed InvalidArgumentError construction failures; no-arg-ctor trap + precedence + Output<*> cast KDoc'd; pushed) |
| AR-06 | RESCOPED (user kept Flow-of-one): add prominent KDoc to every one-shot `generate` overload (`Agent`, `ToolLoopAgent`, `TextGenerator`, `Output` variants) — cold flow, re-collection re-runs generation + tool side effects, intended shape `.first()`. No signature change. | DONE (f528423 reviewed: all three semantics documented on every overload, api/ diff empty) |
| AR-07 | De-`data-class` the 9 LiteRT wire types (`LiteRTChannel`, 6× `LiteRTContent`, `LiteRTToolCall`, `LiteRTMessage` — `LiteRTLanguageModel.kt:117-154`) → @Poko + builder per house pattern; re-seed `data-class-budget.json` downward. Include LiteRT API hygiene: `LiteRTSamplerConfig {}` empty-block runtime throw, `extraContext: Map<String, Any?>` in public KMP API, document the no-op `cancel()`/`close()` defaults and name-only tool-response correlation. | DONE (74c9349 reviewed: 0 data classes left in LiteRT surface, budget re-seeded 66→57, internal ctors + builders per house pattern, copy() gone from ABI, extraContext→Map<String,JsonElement>, sampler empty-block→Default values, correlation + lifecycle KDocs present) |
| AR-08 | Gate `experimental_repairToolCall` + `experimental_toolApprovalSecret` (`ToolLoopAgent.kt:138,151`) with `@ExperimentalAiSdkApi`. | DONE (abbb20e reviewed: all public experimental_ members gated incl. AgentSettings/builder forward-ref + StepSettings.experimental_context; approval secret now defensively copyOf()'d; Agent.kt occurrences verified internal (AgentCallHooks); JVM dump unchanged as expected) |
| AR-09 | Remove TS-residue typealiases from the klib surface: `AlibabaUsage`, `AlibabaCacheControl` (`AlibabaProvider.kt:29-32`), `DeepSeekErrorData` (`DeepSeekFacade.kt:192`). | DONE (554d7bc reviewed: zero references remain in src/ + api/; parity ledgers regenerated; source-level removal, CHANGELOG'd) |
| AR-10 | `StreamTextResult`/`StreamObjectResult` lifecycle (`Generate.kt:149-216`): producer must not outlive consumers — cancel upstream when the last collector leaves without a terminal event, or add `close()`; stop collecting upstream on a foreign dispatcher (violates `LanguageModel.kt:73-75` contract); KDoc that `warnings`/`response` (`ensureCollected`) run the stream to completion. | DONE (3d76a56 reviewed: refcounted collectors + runId-guarded buffer; producer runs on first collector's dispatcher — foreign-context contract fixed; orchestrator-required NonCancellable finally-cleanup with race comment; fresh-run-after-abandonment KDoc'd; 24 stream tests green independently; api/ unchanged) — follow-up: the new concurrent-collector test raced schedule-vs-registration and failed CI run 28620342358 (:127, collections==2 under the legitimate abandonment-restart path); deterministic registration gate dispatched 2026-07-02 |

## WP-T — Correctness fixes + test debt (blocked by WP-A where APIs move)

| ID | Task | Status |
|----|------|--------|
| AR-12 | LiteRT Cumulative-mode tool-call duplication: dedup re-emitted `toolCalls` across snapshots by id (`LiteRTLanguageModel.kt:494-501`) + regression test. | DONE (0a2bfe1 reviewed: dedup keyed on (id,name,arguments) — re-sent snapshot duplicates collapse, distinct-id calls preserved; regression test asserts single event chain across duplicate snapshots; zero ABI) |
| AR-13 | LiteRT non-prefix cumulative fallback re-emits the full string as a delta (`:522-527`): fix or emit a warning + test. | DONE (f5073f5 reviewed: baseline advances, append-only best-effort suffix, `cumulativeRecovery` providerMetadata marker on recovered deltas for text+reasoning; prefix and non-prefix tests; zero ABI) |
| AR-14 | Wire `InjectJsonInstruction` (dead code, `InjectJsonInstruction.kt:85-91`) into the LiteRT path for `ResponseFormat.Json`, and surface `CallWarning`s through `StructuredObjectGenerator` (`StructuredObjectApi.kt:500-503` currently swallows them) + tests. | DONE (7c136e4 reviewed: Json format injects instruction+schema into system path, warn-and-drop removed; warnings exposed on StructuredObjectFinish/Streaming/Done and proven to reach callers; ABI additions dump/CHANGELOG/contract-recorded pre-beta) |
| AR-15 | LiteRT test expansion (currently 2 tests / ~873 lines): Delta stream mode (the default), media mapping paths (image/audio bytes, data-URL, file path, unsupported → typed error), abort-mid-stream, tool-choice branches (None/Required/Specific + providerExecuted filtering), empty-prompt error, close-on-error. | DONE (9fb2e3a reviewed: 7 new tests, all 6 areas covered, abort test asserts cancelCalls==1 — strong form preserved via the gate fix; file now 13 tests) |
| AR-16 | Zero-coverage public entry points: instantiate-and-exercise tests for `Completion` (`CompletionApi.kt:503`) and `StructuredObject` (`StructuredObjectApi.kt:253`); edge tests for `EmbeddingMath.cosineSimilarity` (zero vector, dimension mismatch). | DONE (bbd372c reviewed: PublicEntryPointCoverageTest with real state-transition assertions via public transports; zero-vector→0f pinned with contract comment; dimension mismatch IAE message pinned) |
| AR-17 | `docs/provider-golden-coverage.json`: fix the 14 phantom `docs/parity/*.md` references and the null `FireworksProvider.streamGolden`; add a file-existence check to the gate so the manifest can't rot silently. | DONE (3f076bf reviewed: all refs corrected to real ai-sdk-*.md paths, zero nulls, Fireworks filled with delegation note, gate validates existence of every requestGolden/streamGolden ref) |

## WP-D — Documentation purge (blocked by WP-A: docs must show the FINAL API)

| ID | Task | Status |
|----|------|--------|
| AR-18 | Purge phantom TS-style APIs from the remaining 17 wiki pages (advanced-streaming, application-patterns, chatbots, completion-and-object-ui, cookbook, devtools, memory, middleware-and-telemetry, prompt-engineering, prompts-and-messages, provider-management, settings-and-provider-options, streaming, troubleshooting, ui-and-streams, ui-stream-protocols, utilities, workflow-patterns). Template: the already-landed `error-handling.md` fix + `ErrorHandlingDocSnippetTest`. Add compile guards at least for cookbook + streaming. Accept: residue scan = 0 across `docs/wiki/`. | DONE (49b61bb+56fd8aa+3532cf4+9bd51a9+d8045bd reviewed batch-by-batch: full-wiki residue scan independently verified 0 across all 33 pages; Cookbook + Streaming executing guard classes added; one guard-caught doc bug fixed en route — TextStreamChatTransport named-param binding) |
| AR-19 | `llms.txt:87-97`: replace dead TS-style API teaching (`generateText`, `createGateway`, `createOpenAICompatible`, `createMCPClient`, `devToolsMiddleware`, …) with the real structured API. | DONE (07bfff0 reviewed: llms.txt teaches the real API; every named symbol independently verified in src/commonMain) |
| AR-20 | `docs/AISDK_PORT.md` migration tables (`:50-84`): map to the real entry points (`TextGenerator`, `Gateway()`, `CustomProvider{}`/`Provider()`, `ProviderRegistry.createProviderRegistry`, `StreamToUiMessages`). | DONE (3102dbe reviewed: migration tables map upstream TS names to verified Kotlin coordinates incl. generateObject→OutputObj and addToolResult→approval-response re-feed) |
| AR-21 | Lifecycle story: `INTERFACE_CONTRACT.md` drop internal `AgentCallHooks`, document `events()`/`collectAgentEvents`; fix `agents.md:205-217` prose and `lifecycle-and-events.md:9,42,119`. | DONE (c2bdb6c reviewed: AgentCallHooks gone from contract+wiki, events()/collectAgentEvents documented, TelemetryEvent purged) |
| AR-22 | Real-provider on-ramp: copy-paste block with Ktor engine artifact + `HttpClient(...)` construction + `OpenAICompatible` + API key; LiteRT wiring example implementing `LiteRTConversationFactory`. | DONE (ed6b863 reviewed: Ktor CIO on-ramp + LiteRT factory example, both compile-guarded; CIO dep confirmed jvmTest-scoped; verification metadata additions-only) |
| AR-23 | Semantic KDoc (not bare `@since`) on headline entry points: `TextGenerator`, `GenerationInput`, `OpenAICompatible`, `Gateway()`, `APICallError`, LiteRT public types. Include dead-air SSE guidance: streaming has no default deadline — set `CallConfig.timeout` or engine socket timeouts (AR-11 folded here). | DONE (8229ac2 reviewed: semantic KDoc on all stub-only headline entry points incl. folded AR-11 dead-air SSE deadline guidance; api/ diff empty; budget re-seeded in-commit) |
| AR-24 | `framework-facades.md`: documents a nonexistent `ai.torad.aisdk.react` package — rewrite honestly or delete. | DONE (46e0e5a reviewed: honest framework-host page, zero react/useChat refs, links repaired) |

## Discovered during remediation

| ID | Task | Status |
|----|------|--------|
| AR-34 | `:compileCommonMainKotlinMetadata` (publish-path-only compilation, never run by `check`) fails: `AbortError` not accepted as `CancellationException?` at `Job.cancel`/executionContext.cancel sites (`AbortSignal.kt:153`, `HttpTransport.kt:155`, `FalProvider.kt:128`, `GoogleMediaModels.kt:365`, `LumaProvider.kt:390`, `OpenAICompatibleFacadeSupport.kt:106`). Blocks CI's `publishToMavenLocal` step (would be CI round 4's failure). Platform compilations pass; only the metadata compiler rejects. CAUTION: `AbortError`'s supertype is frozen public ABI and moving it into/out of `CancellationException` changes consumer catch + coroutines-swallowing semantics — fix design requires orchestrator sign-off before implementation. | DONE (1500e93 reviewed: Option A internal expect/actual bridge, cause preserved, api/ diff empty; antibody: `check` now depends on `metadataMainClasses`; publishToMavenLocal green end-to-end, validated independently) |
| AR-35 | Deflake `McpHttpTransportTest."HTTP inbound SSE errors reconnect with capped exponential backoff"` (McpHttpTransportTest.kt:356-400): mixes `runTest` with real-time windows (`waitForRealTime`, `Dispatchers.Default` + `delay(15)`) racing a 30ms-backoff reconnect loop — failed on iosSimulatorArm64 in run 28618403235 (assertion :392, "still 1 GET after 15ms real time"), passed on identical code in the two prior Apple runs. Fix: inject a deterministic clock/delay seam into `MCPReconnectionOptions` (mirror `RetryPolicy`'s injected `RetryDelayGenerator`/clock) and assert on recorded delays, not wall-clock windows. **Second occurrence 2026-07-02: run 28619160117, Linux JVM leg (assertion :356) on a no-causal-path diff (AR-08/09 annotations + typealias removal) — cross-platform flake confirmed; PROMOTED to front of WP-T queue (immediately after AR-10 amendment). Both failed runs recovered green on rerun.** | DONE (115d2f4 reviewed: internal MCPReconnectDelayer fun-interface seam (zero ABI), test asserts recorded delays [30,100] incl. the cap, no timing-window assertions remain in the target test; residual: other tests in the file still use waitForRealTime — watch for future flakes) |

## WP-X — Auditor extras (surfaced during audit, non-blocking; do last)

| ID | Task | Status |
|----|------|--------|
| AR-29 | CHANGELOG: backfill entries for already-published `0.2.0` and `0.3.0-alpha01`. | DONE (279f305 reviewed: factual against git history, matching voice) |
| AR-30 | `smoke-tests/local-staging/kmp-consumer`: add `iosArm64()` + `linuxX64()` so staged native variants are consumer-resolved before Central upload. | DONE (e897446 reviewed: targets present; staging smoke run green incl. compileKotlinIosArm64 + compileKotlinLinuxX64 against staged repo) |
| AR-36 | (Discovered) Codex→Claude hook adapter synthesized a redundant context-less per-hunk Edit event alongside the MultiEdit(old+new) path — root cause of all snippet-scoped gate misfires (AR-15's rule hardening treated a symptom). | DONE (9f5fb9e reviewed: redundant branch deleted, updates still scanned via MultiEdit path, regression tests 3/3 under independent run; process note issued — gate changes are consult-first) |
| AR-31 | End-to-end wire-level tool-loop test: TestServer-backed provider → tool_call → SDK executes tool → assert second wire request carries the tool result in provider format. | DONE (ab5dd03 reviewed: asserts second request's role sequence, assistant tool_call echo with matching id, and tool message tool_call_id+content in provider format) |
| AR-32 | Un-logged swallows: abort-callback exceptions (`AbortSignal.kt:90,119,127`) get a `Logger.warn`; synchronize or document DevTools `var runCreated/stepCounter` (`DevTools.kt:101-102`). | DONE (2aaefc9 reviewed: injectable Logger seam on AbortController, SDK-internal constructions wired to agent logger, per-callback isolation preserved + tested both paths; DevTools state mutex-guarded; ctor change dumped/CHANGELOG'd) |
| AR-33 | INTERFACE_CONTRACT: state the Ktor major-version ABI-coupling policy and the "always include `else` in `when` over SDK sealed hierarchies" source-compat guidance. | DONE (acfe5ef reviewed: both policies precise — Ktor major bump versioned as breaking; else-branch guidance names StreamEvent/ContentPart/AgentEvent/errors) |

## Completed before this backlog existed

- `docs/wiki/error-handling.md` phantom-API purge (5 fixes) + `ErrorHandlingDocSnippetTest.kt`
  compile guard (4 tests, green) — landed by the orchestrator session during the audit;
  serves as the template for AR-18.
