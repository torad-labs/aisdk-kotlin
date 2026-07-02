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

- **DEC-1 (AR-06):** One-shot `Agent.generate` / `TextGenerator.generate` change from
  `Flow<Result>` (single emission) to `suspend fun`. Rationale: every call site ends in
  `.first()`; re-collection re-runs the generation *including tool side effects* (double
  billing); Kotlin idiom for one value is `suspend`. `stream`/`streamResult` stay Flow-based.
  Pre-beta is the last cheap moment.
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
| AR-01 | Fix `:detekt` red at HEAD (5,610 findings vs stale 3,687-entry baseline). Prefer `--auto-correct` for the formatting classes (ArgumentListWrapping 1427, Indentation 1401, Wrapping 599, NoUnusedImports 497); fix or legitimately re-baseline the remainder per DEC-3. Accept: `./gradlew check` green locally; baseline entries ≤ 3687. | OPEN |
| AR-02 | `verify-apple` dependency verification: add the 7 macOS-resolved artifacts missing from `gradle/verification-metadata.xml` (guava-parent-33.3.1-jre.pom, jackson-base-2.15.3.pom, junit-bom-5.9.2.pom/.module, junit-bom-5.10.2.module, junit-bom-5.11.0-M2.module, kotlinx-coroutines-bom-1.8.0.pom). sha256 obtainable from repo1.maven.org on Linux. Accept: `verify-apple` CI job passes. | OPEN |
| AR-03 | Reference gate per DEC-2: pin `AI_SDK_REFERENCE_VERSION=6.0.208`, demote `tools/check-ai-sdk-reference.mjs` newer-upstream failure to a warning (still hard-fail on *mismatched pin*), so the `verify` job proceeds to gate/detekt/tests. Accept: Linux `verify` job runs all steps. | OPEN |
| AR-04 | Acceptance gate for WP-V: one fully-green CI run (both `verify` and `verify-apple`) on PR #10 at the remediation head. | OPEN |

## WP-H — Ten-minute hygiene items (parallel with WP-V)

| ID | Task | Status |
|----|------|--------|
| AR-25 | Delete stale `v0.5-beta` tag from origin (points at a 0.1.0-SNAPSHOT commit; sorts above real versions). | OPEN |
| AR-26 | Replace short-form `LICENSE` (623 bytes) with the full Apache-2.0 text so GitHub/licensee detection works. | OPEN |
| AR-27 | Fix `SECURITY.md:5` false claim ("does not include network providers by default" — the artifact ships ~40 Ktor-backed providers). | OPEN |
| AR-28 | README targets: correct the iOS claim (Maven klibs via KMP, not a shipped framework — or document the XCFramework story if shipping it) and add the published `linuxX64` target to the list. | OPEN |

## WP-A — API/ABI regrets (before the dump freezes; blocked by WP-V)

| ID | Task | Status |
|----|------|--------|
| AR-05 | `ToolLoopAgent`: add a settings-based public constructor (reuse `AgentSettings<TContext>`, `Context.kt:41`) and demote the 26-param ctor (`ToolLoopAgent.kt:64-178`) from the frozen surface (internal or `@Deprecated(HIDDEN)` path). Regenerate ABI dumps; update CHANGELOG + INTERFACE_CONTRACT. | OPEN |
| AR-06 | Per DEC-1: one-shot `generate` becomes `suspend fun` on `Agent`, `ToolLoopAgent`, `TextGenerator` (+ `Output` overloads). Update all call sites, README, doc-snippet tests. `stream`/`streamResult` unchanged. | OPEN |
| AR-07 | De-`data-class` the 9 LiteRT wire types (`LiteRTChannel`, 6× `LiteRTContent`, `LiteRTToolCall`, `LiteRTMessage` — `LiteRTLanguageModel.kt:117-154`) → @Poko + builder per house pattern; re-seed `data-class-budget.json` downward. Include LiteRT API hygiene: `LiteRTSamplerConfig {}` empty-block runtime throw, `extraContext: Map<String, Any?>` in public KMP API, document the no-op `cancel()`/`close()` defaults and name-only tool-response correlation. | OPEN |
| AR-08 | Gate `experimental_repairToolCall` + `experimental_toolApprovalSecret` (`ToolLoopAgent.kt:138,151`) with `@ExperimentalAiSdkApi`. | OPEN |
| AR-09 | Remove TS-residue typealiases from the klib surface: `AlibabaUsage`, `AlibabaCacheControl` (`AlibabaProvider.kt:29-32`), `DeepSeekErrorData` (`DeepSeekFacade.kt:192`). | OPEN |
| AR-10 | `StreamTextResult`/`StreamObjectResult` lifecycle (`Generate.kt:149-216`): producer must not outlive consumers — cancel upstream when the last collector leaves without a terminal event, or add `close()`; stop collecting upstream on a foreign dispatcher (violates `LanguageModel.kt:73-75` contract); KDoc that `warnings`/`response` (`ensureCollected`) run the stream to completion. | OPEN |

## WP-T — Correctness fixes + test debt (blocked by WP-A where APIs move)

| ID | Task | Status |
|----|------|--------|
| AR-12 | LiteRT Cumulative-mode tool-call duplication: dedup re-emitted `toolCalls` across snapshots by id (`LiteRTLanguageModel.kt:494-501`) + regression test. | OPEN |
| AR-13 | LiteRT non-prefix cumulative fallback re-emits the full string as a delta (`:522-527`): fix or emit a warning + test. | OPEN |
| AR-14 | Wire `InjectJsonInstruction` (dead code, `InjectJsonInstruction.kt:85-91`) into the LiteRT path for `ResponseFormat.Json`, and surface `CallWarning`s through `StructuredObjectGenerator` (`StructuredObjectApi.kt:500-503` currently swallows them) + tests. | OPEN |
| AR-15 | LiteRT test expansion (currently 2 tests / ~873 lines): Delta stream mode (the default), media mapping paths (image/audio bytes, data-URL, file path, unsupported → typed error), abort-mid-stream, tool-choice branches (None/Required/Specific + providerExecuted filtering), empty-prompt error, close-on-error. | OPEN |
| AR-16 | Zero-coverage public entry points: instantiate-and-exercise tests for `Completion` (`CompletionApi.kt:503`) and `StructuredObject` (`StructuredObjectApi.kt:253`); edge tests for `EmbeddingMath.cosineSimilarity` (zero vector, dimension mismatch). | OPEN |
| AR-17 | `docs/provider-golden-coverage.json`: fix the 14 phantom `docs/parity/*.md` references and the null `FireworksProvider.streamGolden`; add a file-existence check to the gate so the manifest can't rot silently. | OPEN |

## WP-D — Documentation purge (blocked by WP-A: docs must show the FINAL API)

| ID | Task | Status |
|----|------|--------|
| AR-18 | Purge phantom TS-style APIs from the remaining 17 wiki pages (advanced-streaming, application-patterns, chatbots, completion-and-object-ui, cookbook, devtools, memory, middleware-and-telemetry, prompt-engineering, prompts-and-messages, provider-management, settings-and-provider-options, streaming, troubleshooting, ui-and-streams, ui-stream-protocols, utilities, workflow-patterns). Template: the already-landed `error-handling.md` fix + `ErrorHandlingDocSnippetTest`. Add compile guards at least for cookbook + streaming. Accept: residue scan = 0 across `docs/wiki/`. | OPEN |
| AR-19 | `llms.txt:87-97`: replace dead TS-style API teaching (`generateText`, `createGateway`, `createOpenAICompatible`, `createMCPClient`, `devToolsMiddleware`, …) with the real structured API. | OPEN |
| AR-20 | `docs/AISDK_PORT.md` migration tables (`:50-84`): map to the real entry points (`TextGenerator`, `Gateway()`, `CustomProvider{}`/`Provider()`, `ProviderRegistry.createProviderRegistry`, `StreamToUiMessages`). | OPEN |
| AR-21 | Lifecycle story: `INTERFACE_CONTRACT.md` drop internal `AgentCallHooks`, document `events()`/`collectAgentEvents`; fix `agents.md:205-217` prose and `lifecycle-and-events.md:9,42,119`. | OPEN |
| AR-22 | Real-provider on-ramp: copy-paste block with Ktor engine artifact + `HttpClient(...)` construction + `OpenAICompatible` + API key; LiteRT wiring example implementing `LiteRTConversationFactory`. | OPEN |
| AR-23 | Semantic KDoc (not bare `@since`) on headline entry points: `TextGenerator`, `GenerationInput`, `OpenAICompatible`, `Gateway()`, `APICallError`, LiteRT public types. Include dead-air SSE guidance: streaming has no default deadline — set `CallConfig.timeout` or engine socket timeouts (AR-11 folded here). | OPEN |
| AR-24 | `framework-facades.md`: documents a nonexistent `ai.torad.aisdk.react` package — rewrite honestly or delete. | OPEN |

## WP-X — Auditor extras (surfaced during audit, non-blocking; do last)

| ID | Task | Status |
|----|------|--------|
| AR-29 | CHANGELOG: backfill entries for already-published `0.2.0` and `0.3.0-alpha01`. | OPEN |
| AR-30 | `smoke-tests/local-staging/kmp-consumer`: add `iosArm64()` + `linuxX64()` so staged native variants are consumer-resolved before Central upload. | OPEN |
| AR-31 | End-to-end wire-level tool-loop test: TestServer-backed provider → tool_call → SDK executes tool → assert second wire request carries the tool result in provider format. | OPEN |
| AR-32 | Un-logged swallows: abort-callback exceptions (`AbortSignal.kt:90,119,127`) get a `Logger.warn`; synchronize or document DevTools `var runCreated/stepCounter` (`DevTools.kt:101-102`). | OPEN |
| AR-33 | INTERFACE_CONTRACT: state the Ktor major-version ABI-coupling policy and the "always include `else` in `when` over SDK sealed hierarchies" source-compat guidance. | OPEN |

## Completed before this backlog existed

- `docs/wiki/error-handling.md` phantom-API purge (5 fixes) + `ErrorHandlingDocSnippetTest.kt`
  compile guard (4 tests, green) — landed by the orchestrator session during the audit;
  serves as the template for AR-18.
