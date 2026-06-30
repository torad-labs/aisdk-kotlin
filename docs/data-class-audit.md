# Public `data class` audit (BL-058/A1)

Policy and rationale: see [CLAUDE.md](../CLAUDE.md) → "Public value types".
Enforcement: the public data-class budget gate in `.claude/hooks/rules/ci-gate.sh`
(`detect-public-data-class-budget.py` + `data-class-budget.json`), a one-way
ratchet that blocks new public `data class`es and ratchets down as types migrate.

## Decision

- **Approach:** `@Poko class` for growable read-only types; builders for
  construct-types; `data class` only for genuinely-frozen small value types.
- **Scope:** audit all public `data class`es case-by-case.
- **Current budget:** `378` public `data class` declarations in commonMain (seed).

## D11 reclassification (owner-overridable) — 2026-06-30

Case-by-case review of D11 ("agent + state-machine snapshots") splits it: the
**result/leaf types** (`GenerateResult`, `GenerateTextResult`,
`GenerateObjectResult`, `LoopState`, `CompletionPhase` leaves,
`ToolLoopAgentState.Phase.Error`) are clean growable read-only types → **DEMOTE
to @Poko**. The **state containers** (`AgentSessionState`, `ToolLoopAgentState`,
`ChatState`, `CompletionState`) are updated via `MutableStateFlow.update{it.copy()}`
at **29 internal sites** — `copy()` is contractual for the StateFlow/MVI update
idiom, which is exactly CLAUDE.md's "keep `data class` where copy()/destructuring
is contractual" carve-out. Converting them would force 29 verbose full-constructor
rewrites (ToolLoopAgentState alone has 12 fields), degrading the state machine for
little evolvability benefit. **Decision: the 4 state containers stay `data class`
(KEEP); they remain in the budget legitimately.** Owner can override to demote.

## Classification

_Generated 2026-06-30 by an automated classification pass over `src/commonMain/kotlin` (mirrors the budget gate's detection regex). Initial counts: DEMOTE 178 · BUILDER 139 · KEEP 34 · REVIEW 10 · NON-PUBLIC 17 = 378. Subsequent owner decisions and KEEP-floor audit resolutions are recorded below._

### Summary

| Category | Count | Meaning |
|---|---:|---|
| **DEMOTE → `@Poko`** | 178 | Library-produced read-only types (results / responses / metadata / usage / event & message payloads / state snapshots) that grow by appending fields. |
| **BUILDER-front** | 139 | Consumer-built construct types (settings / options / params / config / credentials) — front with a builder/DSL; backing class becomes internal. |
| **KEEP `data class`** | 34 | Genuinely-frozen small value/ref/wire-fixed types (2D-point carve-out). |
| **REVIEW** | 10 | Genuine uncertainty — decide per-type before migrating (concern noted). |
| **NON-PUBLIC (gate artifact)** | 17 | Counted by the budget regex but nested in `internal`/`private`/function-local scope — **not real public ABI**. De-`data`'ing them reclaims budget at zero ABI risk. |
| **TOTAL** | 378 | Matches the `data-class-budget.json` seed. |

Legend in tables: **f** = primary-ctor arity (approx; lambda/arrow types may skew by ±1). **Ser** ✅ = `@Serializable`. **⟳** = its `copy()` is used internally, so demotion requires rewriting those call sites to fresh-construct.

> **Biggest risk — `@Poko` + `@Serializable` coexistence (77 DEMOTE types).** The poko plugin (`dev.drewhamilton.poko 0.23.0`) is applied but the only `@Poko` use is the trivial `ProbePoko.kt` — **the combination is unproven in this repo**. kotlinx.serialization synthesizes its serializer from the primary-constructor properties and does **not** depend on `copy()`/`componentN()`, so `@Poko` (which only drops those two) is compatible in principle. **Mitigation:** the first @Serializable batch (D1) must add a JSON round-trip test as a canary before the rest land.

### DEMOTE → `@Poko` (178) — grouped by migration batch

Each batch = one cohesive, reviewable codex change with its own `./gradlew updateKotlinAbi` regen. Order is roughly safest-first within risk tiers; **D2 / D6 / D13 are the safe warm-ups** (no @Serializable, little/no copy()), **D1 is the @Serializable canary**, **D11 is the state-machine copy()-rewrite cluster**.

| Batch | Subsystem | Types | @Ser | ⟳ | Notes |
|---|---|---:|---:|---:|---|
| D1 | Streaming events (`StreamEvent` sealed leaves) | 22 | 22 | 22 | canary |
| D2 | Lifecycle / `AgentEvent` payloads | 15 | 0 | 0 | safe warm-up |
| D3 | Model messages & token usage (`ContentPart`, `Usage`) | 12 | 12 | 12 |  |
| D4 | UI message parts (`UIMessagePart`, `UIMessage`) | 11 | 11 | 10 |  |
| D5 | MCP protocol results/capabilities | 18 | 18 | 0 |  |
| D6 | Media-model results (image/speech/transcription/video) | 15 | 0 | 0 | safe warm-up |
| D7 | Tool results & outputs | 15 | 7 | 3 |  |
| D8 | Gateway responses & specs | 10 | 0 | 0 |  |
| D9 | LanguageModel results / metadata / middleware ctx | 7 | 1 | 4 |  |
| D10 | Embedding & rerank results | 8 | 0 | 0 |  |
| D11 | Agent + state-machine snapshots | 12 | 0 | 5 | state copy() cluster |
| D12 | Structured-object & UI-stream results | 11 | 0 | 0 |  |
| D13 | Provider tool-namespace holders | 6 | 0 | 0 | safe warm-up |
| D14 | Errors, parsers, telemetry, OAuth, provider error payloads | 16 | 6 | 2 |  |

#### Batch D1 — Streaming events (`StreamEvent` sealed leaves)  ·  22 types  ·  @Serializable: 22  ·  copy()-coupled: 22

_All 22 @Serializable. **Heaviest copy()-churn**: rewrite ~25 `event.copy(...)` sites in `StreamEventTelemetryRedaction.kt` + finish-event copies in Alibaba/DeepInfra providers to fresh-construct. **Run as the @Poko+@Serializable canary** (add a round-trip serialization test)._

| File | Line | Type | f | Ser | ⟳ | Reason |
|---|---:|---|---:|:--:|:--:|---|
| `Streaming.kt` | 64 | `StreamStart` | 2 | ✅ | ⟳ | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `Streaming.kt` | 76 | `ResponseMetadata` | 6 | ✅ | ⟳ | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `Streaming.kt` | 116 | `StepStart` | 3 | ✅ | ⟳ | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `Streaming.kt` | 124 | `TextStart` | 3 | ✅ | ⟳ | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `Streaming.kt` | 132 | `TextDelta` | 4 | ✅ | ⟳ | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `Streaming.kt` | 141 | `TextEnd` | 3 | ✅ | ⟳ | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `Streaming.kt` | 149 | `ReasoningStart` | 3 | ✅ | ⟳ | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `Streaming.kt` | 157 | `ReasoningDelta` | 4 | ✅ | ⟳ | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `Streaming.kt` | 166 | `ReasoningEnd` | 3 | ✅ | ⟳ | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `Streaming.kt` | 175 | `SourcePart` | 7 | ✅ | ⟳ | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `Streaming.kt` | 191 | `FilePart` | 6 | ✅ | ⟳ | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `Streaming.kt` | 203 | `ToolInputStart` | 4 | ✅ | ⟳ | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `Streaming.kt` | 213 | `ToolInputDelta` | 4 | ✅ | ⟳ | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `Streaming.kt` | 223 | `ToolInputEnd` | 3 | ✅ | ⟳ | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `Streaming.kt` | 232 | `ToolCall` | 5 | ✅ | ⟳ | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `Streaming.kt` | 255 | `ToolResult` | 11 | ✅ | ⟳ | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `Streaming.kt` | 279 | `ToolError` | 6 | ✅ | ⟳ | produced sealed-leaf payload (event/message/output/state) |
| `Streaming.kt` | 323 | `ToolOutputDenied` | 6 | ✅ | ⟳ | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `Streaming.kt` | 339 | `StepFinish` | 5 | ✅ | ⟳ | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `Streaming.kt` | 352 | `Finish` | 14 | ✅ | ⟳ | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `Streaming.kt` | 383 | `Error` | 4 | ✅ | ⟳ | produced sealed-leaf payload (event/message/output/state) |
| `Streaming.kt` | 397 | `Raw` | 1 | ✅ | ⟳ | produced sealed-leaf payload (event/message/output/state) |

#### Batch D2 — Lifecycle / `AgentEvent` payloads  ·  15 types  ·  @Serializable: 0  ·  copy()-coupled: 0

_No @Serializable, no internal copy(). Safest large batch — good first landing._

| File | Line | Type | f | Ser | ⟳ | Reason |
|---|---:|---|---:|:--:|:--:|---|
| `Lifecycle.kt` | 24 | `Started` | 4 |  |  | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `Lifecycle.kt` | 37 | `StepStarted` | 5 |  |  | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `Lifecycle.kt` | 45 | `Chunk` | 3 |  |  | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `Lifecycle.kt` | 50 | `StepFinished` | 3 |  |  | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `Lifecycle.kt` | 59 | `ToolCallStarted` | 6 |  |  | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `Lifecycle.kt` | 71 | `ToolCallFinished` | 5 |  |  | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `Lifecycle.kt` | 78 | `Success` | 1 |  |  | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `Lifecycle.kt` | 79 | `Failure` | 1 |  |  | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `Lifecycle.kt` | 83 | `Errored` | 4 |  |  | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `Lifecycle.kt` | 95 | `Aborted` | 2 |  |  | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `Lifecycle.kt` | 105 | `Finished` | 7 |  |  | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `Lifecycle.kt` | 118 | `ModelCallStarted` | 4 |  |  | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `Lifecycle.kt` | 125 | `ModelCallFinished` | 7 |  |  | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `Lifecycle.kt` | 135 | `SpanEmitted` | 3 |  |  | produced sealed-leaf payload (event/message/output/state) |
| `Lifecycle.kt` | 145 | `StepResult` | 16 |  |  | produced read-only (result/response/meta/usage/event/state/wire payload) |

#### Batch D3 — Model messages & token usage (`ContentPart`, `Usage`)  ·  12 types  ·  @Serializable: 12  ·  copy()-coupled: 12

_All 12 @Serializable. Rewrite `message.copy()/part.copy()` in ConvertToLanguageModelPrompt/PruneMessages/Telemetry/ExtractReasoning/ExtractJson._

| File | Line | Type | f | Ser | ⟳ | Reason |
|---|---:|---|---:|:--:|:--:|---|
| `ModelMessage.kt` | 25 | `ModelMessage` | 3 | ✅ | ⟳ | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `ModelMessage.kt` | 77 | `Text` | 3 | ✅ | ⟳ | produced sealed-leaf payload (event/message/output/state) |
| `ModelMessage.kt` | 85 | `Reasoning` | 3 | ✅ | ⟳ | produced sealed-leaf payload (event/message/output/state) |
| `ModelMessage.kt` | 93 | `ToolCall` | 7 | ✅ | ⟳ | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `ModelMessage.kt` | 146 | `ToolResult` | 9 | ✅ | ⟳ | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `ModelMessage.kt` | 189 | `ToolApprovalResponse` | 5 | ✅ | ⟳ | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `ModelMessage.kt` | 198 | `Source` | 2 | ✅ | ⟳ | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `ModelMessage.kt` | 223 | `File` | 7 | ✅ | ⟳ | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `ModelMessage.kt` | 252 | `Image` | 6 | ✅ | ⟳ | produced sealed-leaf payload (event/message/output/state) |
| `ModelMessage.kt` | 284 | `Usage` | 4 | ✅ | ⟳ | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `ModelMessage.kt` | 433 | `InputTokenBreakdown` | 5 | ✅ | ⟳ | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `ModelMessage.kt` | 454 | `OutputTokenBreakdown` | 4 | ✅ | ⟳ | produced read-only (result/response/meta/usage/event/state/wire payload) |

#### Batch D4 — UI message parts (`UIMessagePart`, `UIMessage`)  ·  11 types  ·  @Serializable: 11  ·  copy()-coupled: 10

_All 11 @Serializable. Rewrite `existing.copy()` in `MessageStreamReader.kt`._

| File | Line | Type | f | Ser | ⟳ | Reason |
|---|---:|---|---:|:--:|:--:|---|
| `ui/UIMessage.kt` | 23 | `UIMessage` | 6 | ✅ |  | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `ui/UIMessagePart.kt` | 62 | `Text` | 4 | ✅ | ⟳ | produced sealed-leaf payload (event/message/output/state) |
| `ui/UIMessagePart.kt` | 82 | `ToolUI` | 12 | ✅ | ⟳ | produced sealed-leaf payload (event/message/output/state) |
| `ui/UIMessagePart.kt` | 144 | `Reasoning` | 4 | ✅ | ⟳ | produced sealed-leaf payload (event/message/output/state) |
| `ui/UIMessagePart.kt` | 161 | `SourceUrl` | 5 | ✅ | ⟳ | produced sealed-leaf payload (event/message/output/state) |
| `ui/UIMessagePart.kt` | 178 | `SourceDocument` | 6 | ✅ | ⟳ | produced sealed-leaf payload (event/message/output/state) |
| `ui/UIMessagePart.kt` | 190 | `File` | 6 | ✅ | ⟳ | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `ui/UIMessagePart.kt` | 201 | `Error` | 1 | ✅ | ⟳ | produced sealed-leaf payload (event/message/output/state) |
| `ui/UIMessagePart.kt` | 209 | `Data` | 6 | ✅ | ⟳ | produced sealed-leaf payload (event/message/output/state) |
| `ui/UIMessagePart.kt` | 242 | `StepStart` | 1 | ✅ | ⟳ | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `ui/UIMessagePart.kt` | 255 | `DynamicToolUI` | 9 | ✅ | ⟳ | produced sealed-leaf payload (event/message/output/state) |

#### Batch D5 — MCP protocol results/capabilities  ·  18 types  ·  @Serializable: 18  ·  copy()-coupled: 0

_All 18 @Serializable (MCP wire). JSON-RPC envelopes are KEEP, not here. Verify serializer round-trip._

| File | Line | Type | f | Ser | ⟳ | Reason |
|---|---:|---|---:|:--:|:--:|---|
| `McpProtocol.kt` | 69 | `MCPClientCapabilities` | 3 | ✅ |  | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `McpProtocol.kt` | 78 | `MCPServerCapabilities` | 7 | ✅ |  | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `McpProtocol.kt` | 93 | `InitializeResult` | 6 | ✅ |  | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `McpProtocol.kt` | 102 | `MCPToolDefinition` | 8 | ✅ |  | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `McpProtocol.kt` | 120 | `ListToolsResult` | 4 | ✅ |  | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `McpProtocol.kt` | 127 | `MCPToolSchema` | 3 | ✅ |  | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `McpProtocol.kt` | 135 | `CallToolResult` | 6 | ✅ |  | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `McpProtocol.kt` | 171 | `MCPResource` | 7 | ✅ |  | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `McpProtocol.kt` | 181 | `ListResourcesResult` | 4 | ✅ |  | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `McpProtocol.kt` | 188 | `MCPResourceTemplate` | 6 | ✅ |  | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `McpProtocol.kt` | 197 | `ListResourceTemplatesResult` | 4 | ✅ |  | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `McpProtocol.kt` | 204 | `ReadResourceResult` | 3 | ✅ |  | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `McpProtocol.kt` | 210 | `MCPPromptArgument` | 4 | ✅ |  | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `McpProtocol.kt` | 217 | `MCPPrompt` | 5 | ✅ |  | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `McpProtocol.kt` | 225 | `ListPromptsResult` | 4 | ✅ |  | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `McpProtocol.kt` | 232 | `MCPPromptMessage` | 3 | ✅ |  | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `McpProtocol.kt` | 238 | `GetPromptResult` | 4 | ✅ |  | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `McpProtocol.kt` | 261 | `ElicitResult` | 4 | ✅ |  | produced read-only (result/response/meta/usage/event/state/wire payload) |

#### Batch D6 — Media-model results (image/speech/transcription/video)  ·  15 types  ·  @Serializable: 0  ·  copy()-coupled: 0

_No @Serializable. `FileData`/`GeneratedFile` sealed leaves + result/usage holders._

| File | Line | Type | f | Ser | ⟳ | Reason |
|---|---:|---|---:|:--:|:--:|---|
| `MediaModels.kt` | 8 | `GeneratedFile` | 6 |  |  | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `MediaModels.kt` | 20 | `Base64` | 4 |  |  | produced sealed-leaf payload (event/message/output/state) |
| `MediaModels.kt` | 51 | `Url` | 4 |  |  | produced sealed-leaf payload (event/message/output/state) |
| `MediaModels.kt` | 213 | `ImageGenerationFile` | 5 |  |  | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `MediaModels.kt` | 221 | `ImageModelUsage` | 4 |  |  | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `MediaModels.kt` | 246 | `ImageModelResult` | 6 |  |  | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `MediaModels.kt` | 254 | `GenerateImageResult` | 8 |  |  | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `MediaModels.kt` | 351 | `ImageMiddlewareCallContext` | 3 |  |  | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `MediaModels.kt` | 424 | `SpeechModelResult` | 5 |  |  | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `MediaModels.kt` | 431 | `GenerateSpeechResult` | 6 |  |  | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `MediaModels.kt` | 517 | `TranscriptSegment` | 4 |  |  | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `MediaModels.kt` | 523 | `TranscriptionModelResult` | 10 |  |  | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `MediaModels.kt` | 535 | `TranscribeResult` | 11 |  |  | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `MediaModels.kt` | 624 | `VideoModelResult` | 5 |  |  | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `MediaModels.kt` | 631 | `GenerateVideoResult` | 7 |  |  | produced read-only (result/response/meta/usage/event/state/wire payload) |

#### Batch D7 — Tool results & outputs  ·  15 types  ·  @Serializable: 7  ·  copy()-coupled: 3

_6 @Serializable (`ToolResultOutput` leaves). Rewrite `remaining[i].copy(request=)` in ToolApprovalCoordinator._

| File | Line | Type | f | Ser | ⟳ | Reason |
|---|---:|---|---:|:--:|:--:|---|
| `Tool.kt` | 23 | `ToolSchema` | 8 |  |  | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `Tool.kt` | 41 | `Success` | 1 |  |  | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `Tool.kt` | 438 | `Schema` | 2 |  |  | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `Tool.kt` | 453 | `Success` | 3 |  |  | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `Tool.kt` | 458 | `Failure` | 3 |  |  | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `Tool.kt` | 561 | `Preliminary` | 1 |  |  | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `Tool.kt` | 562 | `Final` | 1 |  |  | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `Tool.kt` | 784 | `Specific` | 1 |  |  | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `Tool.kt` | 830 | `Text` | 1 | ✅ | ⟳ | produced sealed-leaf payload (event/message/output/state) |
| `Tool.kt` | 834 | `Json` | 1 | ✅ |  | produced sealed-leaf payload (event/message/output/state) |
| `Tool.kt` | 838 | `Error` | 1 | ✅ | ⟳ | produced sealed-leaf payload (event/message/output/state) |
| `Tool.kt` | 842 | `ErrorJson` | 1 | ✅ |  | produced sealed-leaf payload (event/message/output/state) |
| `Tool.kt` | 846 | `ExecutionDenied` | 1 | ✅ |  | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `Tool.kt` | 850 | `Content` | 3 | ✅ |  | produced sealed-leaf payload (event/message/output/state) |
| `ToolApproval.kt` | 18 | `PendingApproval` | 7 | ✅ | ⟳ | produced read-only (result/response/meta/usage/event/state/wire payload) |

#### Batch D8 — Gateway responses & specs  ·  10 types  ·  @Serializable: 0  ·  copy()-coupled: 0

_No @Serializable. `GatewayTools` holder rides along. (Gateway *settings/params/context are BUILDER, separate track.)_

| File | Line | Type | f | Ser | ⟳ | Reason |
|---|---:|---|---:|:--:|:--:|---|
| `Gateway.kt` | 82 | `GatewayRequestContext` | 3 |  |  | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `Gateway.kt` | 87 | `GatewayPricing` | 5 |  |  | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `Gateway.kt` | 94 | `GatewayLanguageModelSpecification` | 4 |  |  | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `Gateway.kt` | 120 | `GatewayLanguageModelEntry` | 7 |  |  | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `Gateway.kt` | 131 | `GatewayFetchMetadataResponse` | 2 |  |  | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `Gateway.kt` | 135 | `GatewayCreditsResponse` | 3 |  |  | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `Gateway.kt` | 180 | `GatewaySpendReportRow` | 16 |  |  | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `Gateway.kt` | 198 | `GatewaySpendReportResponse` | 2 |  |  | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `Gateway.kt` | 206 | `GatewayGenerationInfo` | 19 |  |  | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `Gateway.kt` | 425 | `GatewayTools` | 3 |  |  | provider tool-namespace holder (grows by appended tool versions) |

#### Batch D9 — LanguageModel results / metadata / middleware ctx  ·  7 types  ·  @Serializable: 1  ·  copy()-coupled: 4

_Rewrite `result.copy(usage=)` in Mistral/Alibaba/DeepInfra + `params`-adjacent `LanguageModelTool`. NOTE: `LanguageModelCallParams` was owner-resolved after the main D9 batch: DEMOTE via `@Poko` + public `toBuilder()`._

| File | Line | Type | f | Ser | ⟳ | Reason |
|---|---:|---|---:|:--:|:--:|---|
| `LanguageModel.kt` | 120 | `LanguageModelTool` | 8 |  | ⟳ | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `LanguageModel.kt` | 135 | `LanguageModelResult` | 11 |  | ⟳ | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `LanguageModel.kt` | 152 | `LanguageModelStreamResult` | 4 |  | ⟳ | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `LanguageModel.kt` | 164 | `CallWarning` | 4 | ✅ |  | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `LanguageModel.kt` | 175 | `LanguageModelRequestMetadata` | 2 |  |  | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `LanguageModel.kt` | 180 | `LanguageModelResponseMetadata` | 6 |  |  | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `Middleware.kt` | 85 | `MiddlewareCallContext` | 3 |  | ⟳ | produced read-only (result/response/meta/usage/event/state/wire payload) |

#### Batch D10 — Embedding & rerank results  ·  8 types  ·  @Serializable: 0  ·  copy()-coupled: 0

_`EmbeddingModelCallParams` was owner-resolved after the main D10 batch: DEMOTE via `@Poko` + public `toBuilder()`. Plain result/usage holders otherwise._

| File | Line | Type | f | Ser | ⟳ | Reason |
|---|---:|---|---:|:--:|:--:|---|
| `Embedding.kt` | 38 | `EmbeddingModelResult` | 7 |  |  | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `Embedding.kt` | 47 | `EmbeddingUsage` | 3 |  |  | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `Embedding.kt` | 52 | `EmbedResult` | 8 |  |  | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `Embedding.kt` | 62 | `EmbedManyResult` | 10 |  |  | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `Embedding.kt` | 179 | `EmbeddingMiddlewareCallContext` | 3 |  |  | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `Rerank.kt` | 21 | `RerankedItem` | 4 |  |  | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `Rerank.kt` | 27 | `RerankingModelResult` | 6 |  |  | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `Rerank.kt` | 35 | `RerankResult` | 8 |  |  | produced read-only (result/response/meta/usage/event/state/wire payload) |

#### Batch D11 — Agent + state-machine snapshots  ·  12 types  ·  @Serializable: 0  ·  copy()-coupled: 5

_**State-machine snapshots** (`AgentSessionState`, `ToolLoopAgentState`, `ChatState`, `CompletionState`, `LoopState`) drive `MutableStateFlow.update { it.copy(...) }` — rewrite every update lambda to fresh-construct. Highest copy()-rewrite density after D1._

| File | Line | Type | f | Ser | ⟳ | Reason |
|---|---:|---|---:|:--:|:--:|---|
| `Agent.kt` | 97 | `GenerateResult` | 9 |  |  | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `AgentSession.kt` | 26 | `AgentSessionState` | 8 |  | ⟳ | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `CompletionApi.kt` | 110 | `Streaming` | 1 |  |  | produced sealed-leaf payload (event/message/output/state) |
| `CompletionApi.kt` | 111 | `Done` | 1 |  |  | produced sealed-leaf payload (event/message/output/state) |
| `CompletionApi.kt` | 112 | `Failed` | 2 |  |  | produced sealed-leaf payload (event/message/output/state) |
| `CompletionApi.kt` | 115 | `CompletionState` | 3 |  | ⟳ | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `Generate.kt` | 20 | `GenerateTextResult` | 19 |  |  | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `Generate.kt` | 195 | `GenerateObjectResult` | 10 |  |  | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `StopCondition.kt` | 23 | `LoopState` | 8 |  |  | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `ToolLoopAgentEngine.kt` | 19 | `ToolLoopAgentState` | 12 |  | ⟳ | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `ToolLoopAgentEngine.kt` | 39 | `Error` | 1 |  | ⟳ | produced sealed-leaf payload (event/message/output/state) |
| `ui/ChatSession.kt` | 11 | `ChatState` | 5 |  | ⟳ | produced read-only (result/response/meta/usage/event/state/wire payload) |

#### Batch D12 — Structured-object & UI-stream results  ·  11 types  ·  @Serializable: 0  ·  copy()-coupled: 0

_No @Serializable. Structured-object phase/finish + UI safe-validate results + UI tool-invocation payloads._

| File | Line | Type | f | Ser | ⟳ | Reason |
|---|---:|---|---:|:--:|:--:|---|
| `StreamObjectResult.kt` | 300 | `StreamObjectFinish` | 6 |  |  | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `StructuredObjectApi.kt` | 26 | `DeepPartial` | 1 |  |  | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `StructuredObjectApi.kt` | 46 | `StructuredObjectFinish` | 4 |  |  | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `StructuredObjectApi.kt` | 65 | `Streaming` | 4 |  |  | produced sealed-leaf payload (event/message/output/state) |
| `StructuredObjectApi.kt` | 70 | `Done` | 4 |  |  | produced sealed-leaf payload (event/message/output/state) |
| `ui/InferAgentMessage.kt` | 51 | `UIToolInvocationPayload` | 4 |  |  | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `ui/InferAgentMessage.kt` | 57 | `UIToolInvocationMetadata` | 4 |  |  | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `ui/Streams.kt` | 14 | `TextStreamResponse` | 4 |  |  | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `ui/Streams.kt` | 20 | `UIMessageStreamResponse` | 4 |  |  | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `ui/Streams.kt` | 94 | `Success` | 1 |  |  | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `ui/Streams.kt` | 95 | `Failure` | 1 |  |  | produced read-only (result/response/meta/usage/event/state/wire payload) |

#### Batch D13 — Provider tool-namespace holders  ·  6 types  ·  @Serializable: 0  ·  copy()-coupled: 0

_`*Tools` holders grow by appended provider-tool versions — textbook @Poko (or plain object). Near-zero consumer copy() risk._

| File | Line | Type | f | Ser | ⟳ | Reason |
|---|---:|---|---:|:--:|:--:|---|
| `providers/AnthropicProvider.kt` | 430 | `AnthropicTools` | 21 |  |  | provider tool-namespace holder (grows by appended tool versions) |
| `providers/AzureProvider.kt` | 124 | `AzureOpenAITools` | 6 |  |  | provider tool-namespace holder (grows by appended tool versions) |
| `providers/GoogleProvider.kt` | 137 | `GoogleTools` | 8 |  |  | provider tool-namespace holder (grows by appended tool versions) |
| `providers/GroqFacade.kt` | 159 | `GroqTools` | 2 |  |  | provider tool-namespace holder (grows by appended tool versions) |
| `providers/OpenAIProvider.kt` | 140 | `OpenAITools` | 11 |  |  | provider tool-namespace holder (grows by appended tool versions) |
| `providers/XaiProvider.kt` | 254 | `XaiTools` | 8 |  |  | provider tool-namespace holder (grows by appended tool versions) |

#### Batch D14 — Errors, parsers, telemetry, OAuth, provider error payloads  ·  16 types  ·  @Serializable: 6  ·  copy()-coupled: 2

_Grab-bag of small produced types + 3 provider `*ErrorData` @Serializable payloads + 3 OAuth @Serializable. Low risk._

| File | Line | Type | f | Ser | ⟳ | Reason |
|---|---:|---|---:|:--:|:--:|---|
| `AiSdkError.kt` | 71 | `TypeValidationContext` | 4 |  |  | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `AiSdkError.kt` | 249 | `RetryAttemptDetail` | 5 |  |  | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `ConvertToLanguageModelPrompt.kt` | 14 | `DownloadedAsset` | 2 |  |  | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `DevTools.kt` | 13 | `DevToolsStep` | 9 |  |  | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `DevTools.kt` | 24 | `DevToolsStepResult` | 8 |  |  | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `EventStreamParser.kt` | 10 | `Success` | 1 |  |  | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `EventStreamParser.kt` | 11 | `Failure` | 2 |  |  | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `FixJson.kt` | 57 | `PartialJsonResult` | 2 |  |  | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `McpOAuth.kt` | 20 | `OAuthTokens` | 7 | ✅ | ⟳ | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `McpOAuth.kt` | 58 | `AuthorizationServerMetadata` | 9 | ✅ |  | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `McpOAuth.kt` | 70 | `OAuthProtectedResourceMetadata` | 7 | ✅ |  | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `Telemetry.kt` | 51 | `TelemetryCall` | 6 |  |  | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `TelemetryTracing.kt` | 120 | `Error` | 1 |  | ⟳ | produced sealed-leaf payload (event/message/output/state) |
| `providers/BasetenFacade.kt` | 17 | `BasetenErrorData` | 2 | ✅ |  | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `providers/CerebrasFacade.kt` | 24 | `CerebrasErrorData` | 5 | ✅ |  | produced read-only (result/response/meta/usage/event/state/wire payload) |
| `providers/FireworksFacade.kt` | 48 | `FireworksErrorData` | 2 | ✅ |  | produced read-only (result/response/meta/usage/event/state/wire payload) |

### BUILDER-front (139)

Construct types consumers build; front each with a builder/DSL (the `CallSettingsBuilder` / `TextGenerationRequestBuilder` pattern already in `KotlinApi.kt`). This is a **separate, heavier track** from the @Poko demotions — each needs a hand-written builder, so it is **not** part of the DEMOTE batch plan above. Already-fronted: `CallSettings`, `TextGenerationRequest`, `ProviderOptions`(builder), `ToolSet`(builder), `InferAgentMessage.Builder`.

| File | Line | Type | f | Ser | ⟳ | Reason |
|---|---:|---|---:|:--:|:--:|---|
| `CallConfig.kt` | 5 | `CallConfig` | 13 |  |  | config consumers build |
| `CompletionApi.kt` | 17 | `CompletionRequestOptions` | 3 |  |  | options consumers build |
| `CompletionApi.kt` | 22 | `CompletionRequest` | 8 |  |  | request consumers build |
| `CompletionApi.kt` | 42 | `UseCompletionOptions` | 9 |  |  | options consumers build |
| `CompletionApi.kt` | 55 | `CallCompletionApiOptions` | 9 |  |  | options consumers build |
| `Context.kt` | 37 | `AgentSettings` | 18 |  |  | settings consumers build |
| `Context.kt` | 91 | `StepSettings` | 19 |  |  | settings consumers build |
| `Gateway.kt` | 43 | `GatewayProviderSettings` | 6 |  |  | provider config/options consumers build (grows) |
| `Gateway.kt` | 168 | `GatewaySpendReportParams` | 10 |  |  | call params consumers build |
| `Gateway.kt` | 202 | `GatewayGenerationInfoParams` | 2 |  |  | call params consumers build |
| `KotlinApi.kt` | 14 | `CallSettings` | 13 |  |  | settings consumers build |
| `KotlinApi.kt` | 137 | `TextGenerationRequest` | 0 |  |  | request consumers build |
| `MCP.kt` | 180 | `MCPClientConfig` | 2 |  |  | config consumers build |
| `MCP.kt` | 904 | `MCPTransportConfig` | 7 |  |  | config consumers build |
| `MCP.kt` | 913 | `MCPReconnectionOptions` | 5 |  |  | options consumers build |
| `MCP.kt` | 1630 | `StdioConfig` | 5 | ✅ |  | config consumers build |
| `McpOAuth.kt` | 30 | `OAuthClientInformation` | 5 | ✅ |  | consumer-built sub-config/options |
| `McpOAuth.kt` | 38 | `OAuthClientMetadata` | 17 | ✅ |  | consumer-built sub-config/options |
| `McpOAuth.kt` | 86 | `AuthOptions` | 7 |  |  | options consumers build |
| `McpProtocol.kt` | 50 | `MCPRequestOptions` | 4 |  |  | consumer-built sub-config/options |
| `McpProtocol.kt` | 57 | `Configuration` | 4 | ✅ |  | consumer-built sub-config/options |
| `McpProtocol.kt` | 64 | `ElicitationCapability` | 2 | ✅ |  | consumer-built sub-config/options |
| `McpProtocol.kt` | 248 | `ElicitationRequestParams` | 4 | ✅ |  | call params consumers build |
| `McpProtocol.kt` | 255 | `ElicitationRequest` | 3 | ✅ |  | request consumers build |
| `MediaModels.kt` | 200 | `ImageGenerationParams` | 11 |  |  | call params consumers build |
| `MediaModels.kt` | 411 | `SpeechGenerationParams` | 10 |  |  | call params consumers build |
| `MediaModels.kt` | 508 | `TranscriptionParams` | 7 |  |  | call params consumers build |
| `MediaModels.kt` | 609 | `VideoGenerationParams` | 13 |  |  | call params consumers build |
| `ModelMessage.kt` | 163 | `ToolApprovalRequest` | 7 | ✅ | ⟳ | request consumers build |
| `Provider.kt` | 156 | `ProviderMiddleware` | 4 |  |  | consumer-built middleware config (grows by kind) |
| `Redactor.kt` | 16 | `RedactionOptions` | 4 |  |  | options consumers build |
| `Rerank.kt` | 12 | `RerankingParams` | 7 |  |  | call params consumers build |
| `RetryPolicy.kt` | 35 | `RetryPolicy` | 8 |  |  | policy consumers build |
| `Streaming.kt` | 297 | `ToolApprovalRequest` | 8 | ✅ | ⟳ | request consumers build |
| `StructuredObjectApi.kt` | 28 | `StructuredObjectRequest` | 6 |  |  | request consumers build |
| `StructuredObjectApi.kt` | 52 | `StructuredObjectOptions` | 7 |  |  | options consumers build |
| `Telemetry.kt` | 33 | `TelemetrySettings` | 8 |  |  | settings consumers build |
| `Tool.kt` | 34 | `ToolSchemaOptions` | 3 |  |  | consumer-built sub-config/options |
| `Tool.kt` | 592 | `ProviderToolFactoryOptions` | 7 |  |  | consumer-built sub-config/options |
| `Tool.kt` | 817 | `ToolPredicateOptions` | 4 |  |  | consumer-built sub-config/options |
| `ToolExecutionPolicy.kt` | 12 | `ToolExecutionPolicy` | 5 |  |  | policy consumers build |
| `middleware/Logging.kt` | 15 | `LoggingOptions` | 5 |  |  | options consumers build |
| `providers/AlibabaProvider.kt` | 34 | `AlibabaProviderSettings` | 8 | ✅ |  | provider config/options consumers build (grows) |
| `providers/AlibabaProvider.kt` | 93 | `AlibabaEmbeddingModelOptions` | 6 | ✅ |  | provider config/options consumers build (grows) |
| `providers/AlibabaProvider.kt` | 104 | `AlibabaLanguageModelOptions` | 4 | ✅ |  | provider config/options consumers build (grows) |
| `providers/AlibabaProvider.kt` | 111 | `AlibabaVideoModelOptions` | 10 | ✅ |  | provider config/options consumers build (grows) |
| `providers/AmazonBedrockProvider.kt` | 30 | `BedrockCredentials` | 5 | ✅ |  | credentials consumers build |
| `providers/AmazonBedrockProvider.kt` | 37 | `AmazonBedrockProviderSettings` | 6 |  |  | provider config/options consumers build (grows) |
| `providers/AnthropicAwsProvider.kt` | 16 | `AnthropicAwsProviderSettings` | 9 | ✅ |  | provider config/options consumers build (grows) |
| `providers/AnthropicProvider.kt` | 39 | `AnthropicProviderSettings` | 5 |  |  | provider config/options consumers build (grows) |
| `providers/AssemblyAIProvider.kt` | 28 | `AssemblyAICustomSpelling` | 3 | ✅ |  | consumer-built sub-config/options |
| `providers/AssemblyAIProvider.kt` | 34 | `AssemblyAITranscriptionModelOptions` | 35 | ✅ |  | provider config/options consumers build (grows) |
| `providers/AssemblyAIProvider.kt` | 72 | `AssemblyAIProviderSettings` | 5 | ✅ |  | provider config/options consumers build (grows) |
| `providers/AzureProvider.kt` | 11 | `AzureOpenAIProviderSettings` | 4 |  |  | provider config/options consumers build (grows) |
| `providers/BasetenFacade.kt` | 12 | `BasetenEmbeddingModelOptions` | 2 | ✅ |  | provider config/options consumers build (grows) |
| `providers/BasetenFacade.kt` | 22 | `BasetenProviderSettings` | 5 | ✅ |  | provider config/options consumers build (grows) |
| `providers/BlackForestLabsProvider.kt` | 31 | `BlackForestLabsImageModelOptions` | 25 | ✅ |  | provider config/options consumers build (grows) |
| `providers/BlackForestLabsProvider.kt` | 59 | `BlackForestLabsProviderSettings` | 6 | ✅ |  | provider config/options consumers build (grows) |
| `providers/ByteDanceProvider.kt` | 25 | `ByteDanceVideoProviderOptions` | 13 | ✅ |  | provider config/options consumers build (grows) |
| `providers/ByteDanceProvider.kt` | 41 | `ByteDanceProviderSettings` | 4 | ✅ |  | provider config/options consumers build (grows) |
| `providers/CerebrasFacade.kt` | 10 | `CerebrasProviderSettings` | 4 | ✅ |  | provider config/options consumers build (grows) |
| `providers/CohereProvider.kt` | 29 | `CohereProviderSettings` | 4 | ✅ |  | provider config/options consumers build (grows) |
| `providers/CohereProvider.kt` | 71 | `CohereLanguageModelOptions` | 2 | ✅ |  | provider config/options consumers build (grows) |
| `providers/CohereProvider.kt` | 76 | `CohereThinkingOptions` | 3 | ✅ |  | provider config/options consumers build (grows) |
| `providers/CohereProvider.kt` | 82 | `CohereEmbeddingModelOptions` | 4 | ✅ |  | provider config/options consumers build (grows) |
| `providers/CohereProvider.kt` | 89 | `CohereRerankingModelOptions` | 3 | ✅ |  | provider config/options consumers build (grows) |
| `providers/DeepInfraFacade.kt` | 28 | `DeepInfraProviderSettings` | 4 | ✅ |  | provider config/options consumers build (grows) |
| `providers/DeepSeekFacade.kt` | 19 | `DeepSeekProviderSettings` | 4 | ✅ |  | provider config/options consumers build (grows) |
| `providers/DeepSeekFacade.kt` | 118 | `DeepSeekLanguageModelOptions` | 2 | ✅ |  | provider config/options consumers build (grows) |
| `providers/DeepgramProvider.kt` | 32 | `DeepgramSpeechModelOptions` | 9 | ✅ |  | provider config/options consumers build (grows) |
| `providers/DeepgramProvider.kt` | 44 | `DeepgramTranscriptionModelOptions` | 19 | ✅ |  | provider config/options consumers build (grows) |
| `providers/DeepgramProvider.kt` | 66 | `DeepgramProviderSettings` | 3 | ✅ |  | provider config/options consumers build (grows) |
| `providers/ElevenLabsProvider.kt` | 35 | `ElevenLabsSpeechModelOptions` | 12 | ✅ |  | provider config/options consumers build (grows) |
| `providers/ElevenLabsProvider.kt` | 50 | `ElevenLabsTranscriptionModelOptions` | 7 | ✅ |  | provider config/options consumers build (grows) |
| `providers/ElevenLabsProvider.kt` | 60 | `ElevenLabsProviderSettings` | 3 | ✅ |  | provider config/options consumers build (grows) |
| `providers/FalProvider.kt` | 34 | `FalProviderSettings` | 8 | ✅ |  | provider config/options consumers build (grows) |
| `providers/FalProvider.kt` | 146 | `FalImageModelOptions` | 2 | ✅ |  | provider config/options consumers build (grows) |
| `providers/FalProvider.kt` | 151 | `FalSpeechModelOptions` | 5 | ✅ |  | provider config/options consumers build (grows) |
| `providers/FalProvider.kt` | 159 | `FalTranscriptionModelOptions` | 7 | ✅ |  | provider config/options consumers build (grows) |
| `providers/FalProvider.kt` | 169 | `FalVideoModelOptions` | 8 | ✅ |  | provider config/options consumers build (grows) |
| `providers/FireworksFacade.kt` | 26 | `FireworksThinkingOptions` | 3 | ✅ |  | provider config/options consumers build (grows) |
| `providers/FireworksFacade.kt` | 32 | `FireworksLanguageModelOptions` | 4 | ✅ |  | provider config/options consumers build (grows) |
| `providers/FireworksFacade.kt` | 41 | `FireworksEmbeddingModelOptions` | 2 | ✅ |  | provider config/options consumers build (grows) |
| `providers/FireworksFacade.kt` | 53 | `FireworksProviderSettings` | 4 | ✅ |  | provider config/options consumers build (grows) |
| `providers/GladiaProvider.kt` | 30 | `GladiaTranscriptionModelOptions` | 33 | ✅ |  | provider config/options consumers build (grows) |
| `providers/GladiaProvider.kt` | 66 | `GladiaProviderSettings` | 5 | ✅ |  | provider config/options consumers build (grows) |
| `providers/GoogleProvider.kt` | 65 | `GoogleGenerativeAIProviderSettings` | 4 | ✅ |  | provider config/options consumers build (grows) |
| `providers/GoogleVertexProvider.kt` | 27 | `GoogleVertexProviderSettings` | 7 | ✅ |  | provider config/options consumers build (grows) |
| `providers/GroqFacade.kt` | 18 | `GroqProviderSettings` | 4 | ✅ |  | provider config/options consumers build (grows) |
| `providers/GroqFacade.kt` | 111 | `GroqLanguageModelOptions` | 2 | ✅ |  | provider config/options consumers build (grows) |
| `providers/GroqFacade.kt` | 118 | `GroqTranscriptionModelOptions` | 5 | ✅ |  | provider config/options consumers build (grows) |
| `providers/HuggingFaceProvider.kt` | 40 | `HuggingFaceProviderSettings` | 4 |  |  | provider config/options consumers build (grows) |
| `providers/HuggingFaceProvider.kt` | 116 | `HuggingFaceResponsesSettings` | 5 | ✅ |  | provider config/options consumers build (grows) |
| `providers/HumeProvider.kt` | 29 | `HumeSpeechModelOptions` | 2 | ✅ |  | provider config/options consumers build (grows) |
| `providers/HumeProvider.kt` | 34 | `HumeProviderSettings` | 3 | ✅ |  | provider config/options consumers build (grows) |
| `providers/KlingAIProvider.kt` | 26 | `KlingAIProviderSettings` | 5 | ✅ |  | provider config/options consumers build (grows) |
| `providers/KlingAIProvider.kt` | 68 | `KlingAIVideoModelOptions` | 20 | ✅ |  | provider config/options consumers build (grows) |
| `providers/LMNTProvider.kt` | 31 | `LMNTSpeechModelOptions` | 10 | ✅ |  | provider config/options consumers build (grows) |
| `providers/LMNTProvider.kt` | 44 | `LMNTProviderSettings` | 3 | ✅ |  | provider config/options consumers build (grows) |
| `providers/LiteRTLanguageModel.kt` | 42 | `LiteRTSamplerConfig` | 5 |  |  | config consumers build |
| `providers/LiteRTLanguageModel.kt` | 87 | `LiteRTConversationRequest` | 11 |  |  | request consumers build |
| `providers/LiteRTLanguageModel.kt` | 114 | `LiteRTLanguageModelSettings` | 9 |  |  | settings consumers build |
| `providers/LumaProvider.kt` | 29 | `LumaImageModelOptions` | 5 | ✅ |  | provider config/options consumers build (grows) |
| `providers/LumaProvider.kt` | 37 | `LumaProviderSettings` | 4 | ✅ |  | provider config/options consumers build (grows) |
| `providers/MistralProvider.kt` | 24 | `MistralProviderSettings` | 4 | ✅ |  | provider config/options consumers build (grows) |
| `providers/MistralProvider.kt` | 206 | `MistralLanguageModelOptions` | 8 | ✅ |  | provider config/options consumers build (grows) |
| `providers/MockLanguageModel.kt` | 167 | `ScriptedResponse` | 9 |  |  | consumer-built sub-config/options |
| `providers/MoonshotAIFacade.kt` | 17 | `MoonshotAIProviderSettings` | 4 | ✅ |  | provider config/options consumers build (grows) |
| `providers/MoonshotAIFacade.kt` | 51 | `MoonshotAILanguageModelOptions` | 2 | ✅ |  | provider config/options consumers build (grows) |
| `providers/OpenAICompatibleProvider.kt` | 9 | `OpenAICompatibleProviderSettings` | 10 |  |  | provider config/options consumers build (grows) |
| `providers/OpenAIProvider.kt` | 14 | `OpenAIProviderSettings` | 9 |  |  | provider config/options consumers build (grows) |
| `providers/OpenResponsesProvider.kt` | 40 | `OpenResponsesProviderSettings` | 5 |  |  | provider config/options consumers build (grows) |
| `providers/OpenResponsesProvider.kt` | 53 | `OpenResponsesOptions` | 24 | ✅ |  | provider config/options consumers build (grows) |
| `providers/OpenResponsesProvider.kt` | 80 | `OpenResponsesAllowedTools` | 3 | ✅ |  | consumer-built tool allowlist config |
| `providers/PerplexityFacade.kt` | 18 | `PerplexityProviderSettings` | 4 | ✅ |  | provider config/options consumers build (grows) |
| `providers/ProdiaProvider.kt` | 42 | `ProdiaProviderSettings` | 4 | ✅ |  | provider config/options consumers build (grows) |
| `providers/ProdiaProvider.kt` | 248 | `ProdiaLanguageModelOptions` | 2 | ✅ |  | provider config/options consumers build (grows) |
| `providers/ProdiaProvider.kt` | 253 | `ProdiaImageModelOptions` | 7 | ✅ |  | provider config/options consumers build (grows) |
| `providers/ProdiaProvider.kt` | 263 | `ProdiaVideoModelOptions` | 2 | ✅ |  | provider config/options consumers build (grows) |
| `providers/QuiverAIProvider.kt` | 27 | `QuiverAIImageModelOptions` | 9 | ✅ |  | provider config/options consumers build (grows) |
| `providers/QuiverAIProvider.kt` | 39 | `QuiverAIProviderSettings` | 4 | ✅ |  | provider config/options consumers build (grows) |
| `providers/ReplicateProvider.kt` | 34 | `ReplicateImageModelOptions` | 8 | ✅ |  | provider config/options consumers build (grows) |
| `providers/ReplicateProvider.kt` | 45 | `ReplicateVideoModelOptions` | 13 | ✅ |  | provider config/options consumers build (grows) |
| `providers/ReplicateProvider.kt` | 61 | `ReplicateProviderSettings` | 4 | ✅ |  | provider config/options consumers build (grows) |
| `providers/RevaiProvider.kt` | 32 | `RevaiTranscriptionModelOptions` | 25 | ✅ |  | provider config/options consumers build (grows) |
| `providers/RevaiProvider.kt` | 60 | `RevaiProviderSettings` | 5 | ✅ |  | provider config/options consumers build (grows) |
| `providers/TogetherAIFacade.kt` | 26 | `TogetherAIImageModelOptions` | 6 | ✅ |  | provider config/options consumers build (grows) |
| `providers/TogetherAIFacade.kt` | 37 | `TogetherAIRerankingModelOptions` | 2 | ✅ |  | provider config/options consumers build (grows) |
| `providers/TogetherAIFacade.kt` | 44 | `TogetherAIProviderSettings` | 4 | ✅ |  | provider config/options consumers build (grows) |
| `providers/VercelFacade.kt` | 13 | `VercelProviderSettings` | 4 | ✅ |  | provider config/options consumers build (grows) |
| `providers/VoyageProvider.kt` | 22 | `VoyageEmbeddingModelOptions` | 5 | ✅ |  | provider config/options consumers build (grows) |
| `providers/VoyageProvider.kt` | 30 | `VoyageRerankingModelOptions` | 3 | ✅ |  | provider config/options consumers build (grows) |
| `providers/VoyageProvider.kt` | 36 | `VoyageProviderSettings` | 4 | ✅ |  | provider config/options consumers build (grows) |
| `providers/XaiProvider.kt` | 40 | `XaiProviderSettings` | 4 | ✅ |  | provider config/options consumers build (grows) |
| `providers/XaiProvider.kt` | 168 | `XaiLanguageModelChatOptions` | 6 | ✅ |  | provider config/options consumers build (grows) |
| `providers/XaiProvider.kt` | 177 | `XaiLanguageModelResponsesOptions` | 3 | ✅ |  | provider config/options consumers build (grows) |
| `providers/XaiProvider.kt` | 183 | `XaiImageModelOptions` | 7 | ✅ |  | provider config/options consumers build (grows) |
| `providers/XaiProvider.kt` | 193 | `XaiVideoModelOptions` | 7 | ✅ |  | provider config/options consumers build (grows) |
| `ui/Chat.kt` | 19 | `ChatRequest` | 4 |  |  | request consumers build |

### KEEP `data class` floor

Small, frozen, wire-fixed value/ref/sealed-leaf types (Jake Wharton 2D-point carve-out). Leave as `data class`.

| File | Line | Type | f | Ser | Reason |
|---|---:|---|---:|:--:|---|
| `DataUrl.kt` | 3 | `DataUrl` | 3 |  | small frozen data URL value; documented public utility, not an A4 visibility leak |
| `GenerationInput.kt` | 5 | `Prompt` | 1 |  | small frozen value/ref/sealed-leaf/wire-fixed |
| `GenerationInput.kt` | 7 | `Messages` | 1 |  | small frozen value/ref/sealed-leaf/wire-fixed |
| `GenerationInput.kt` | 9 | `MessagesWithPrompt` | 3 |  | small frozen value/ref/sealed-leaf/wire-fixed |
| `KotlinApi.kt` | 178 | `PromptText` | 2 |  | small frozen value/ref/sealed-leaf/wire-fixed |
| `KotlinApi.kt` | 185 | `MessageHistory` | 2 |  | small frozen value/ref/sealed-leaf/wire-fixed |
| `KotlinApi.kt` | 192 | `MessageHistoryWithPrompt` | 3 |  | small frozen value/ref/sealed-leaf/wire-fixed |
| `McpProtocol.kt` | 15 | `JSONRPCRequest` | 5 | ✅ | small frozen value/ref/sealed-leaf/wire-fixed |
| `McpProtocol.kt` | 23 | `JSONRPCNotification` | 4 | ✅ | small frozen value/ref/sealed-leaf/wire-fixed |
| `McpProtocol.kt` | 30 | `JSONRPCResponse` | 4 | ✅ | small frozen value/ref/sealed-leaf/wire-fixed |
| `McpProtocol.kt` | 37 | `JSONRPCError` | 4 | ✅ | small frozen value/ref/sealed-leaf/wire-fixed |
| `McpProtocol.kt` | 44 | `JSONRPCErrorData` | 4 | ✅ | small frozen value/ref/sealed-leaf/wire-fixed |
| `MediaModels.kt` | 502 | `AudioSource` | 4 |  | small frozen value/ref/sealed-leaf/wire-fixed |
| `ModelRef.kt` | 24 | `ModelRef` | 3 |  | small frozen value/ref/sealed-leaf/wire-fixed |
| `ProviderMetadata.kt` | 20 | `Raw` | 1 |  | small frozen value/ref/sealed-leaf/wire-fixed |
| `ProviderOptions.kt` | 22 | `Raw` | 1 |  | small frozen value/ref/sealed-leaf/wire-fixed |
| `PruneMessages.kt` | 18 | `BeforeLastMessages` | 1 |  | small frozen value/ref/sealed-leaf/wire-fixed |
| `PruneMessages.kt` | 19 | `Rules` | 1 |  | small frozen value/ref/sealed-leaf/wire-fixed |
| `PruneMessages.kt` | 22 | `PruneToolCallRule` | 3 |  | small frozen value/ref/sealed-leaf/wire-fixed |
| `PruneMessages.kt` | 30 | `BeforeLastMessages` | 1 |  | small frozen value/ref/sealed-leaf/wire-fixed |
| `SmoothStream.kt` | 29 | `Pattern` | 1 |  | small frozen value/ref/sealed-leaf/wire-fixed |
| `Tool.kt` | 565 | `ToolNameMapping` | 3 |  | small frozen value/ref/sealed-leaf/wire-fixed |
| `ToolLoopAgentEngine.kt` | 51 | `UserSubmitPrompt` | 3 |  | small frozen value/ref/sealed-leaf/wire-fixed |
| `ToolLoopAgentEngine.kt` | 57 | `ApproveToolCall` | 1 |  | small frozen value/ref/sealed-leaf/wire-fixed |
| `ToolLoopAgentEngine.kt` | 60 | `DenyToolCall` | 2 |  | small frozen value/ref/sealed-leaf/wire-fixed |
| `providers/GoogleProvider.kt` | 59 | `Model` | 1 |  | small frozen value/ref/sealed-leaf/wire-fixed |
| `providers/GoogleProvider.kt` | 60 | `Agent` | 1 |  | small frozen value/ref/sealed-leaf/wire-fixed |
| `providers/GoogleProvider.kt` | 61 | `ManagedAgent` | 1 |  | small frozen value/ref/sealed-leaf/wire-fixed |
| `providers/LiteRTLanguageModel.kt` | 64 | `Text` | 1 |  | small frozen value/ref/sealed-leaf/wire-fixed |
| `providers/LiteRTLanguageModel.kt` | 65 | `ImageBytes` | 2 |  | small frozen value/ref/sealed-leaf/wire-fixed |
| `providers/LiteRTLanguageModel.kt` | 66 | `ImageFile` | 2 |  | small frozen value/ref/sealed-leaf/wire-fixed |
| `providers/LiteRTLanguageModel.kt` | 67 | `AudioBytes` | 2 |  | small frozen value/ref/sealed-leaf/wire-fixed |
| `providers/LiteRTLanguageModel.kt` | 68 | `AudioFile` | 2 |  | small frozen value/ref/sealed-leaf/wire-fixed |

### Owner-resolved call params — DEMOTE via `@Poko` + public `toBuilder()`

| File | Line | Type | f | Ser | Decision |
|---|---:|---|---:|:--:|---|
| `Embedding.kt` | 29 | `EmbeddingModelCallParams` | 7 |  | framework-produced params with former `copy()` middleware idiom; demote and replace with `params.toBuilder().field(...).build()` |
| `LanguageModel.kt` | 81 | `LanguageModelCallParams` | 17 |  | framework-produced params with former `copy()` middleware/provider idiom; demote and replace with `params.toBuilder().field(...).build()` |

### KEEP-floor audit stragglers — BUILDER regular class

| File | Line | Type | f | Ser | Decision |
|---|---:|---|---:|:--:|---|
| `IdGenerator.kt` | 5 | `IdGenerator` | 6 |  | consumer-configured generator with `Random`; regular builder-backed class, straggler caught in KEEP-floor audit |
| `Provider.kt` | 28 | `CustomProvider` | 11 |  | consumer-configured provider over model object maps; regular builder-backed class, straggler caught in KEEP-floor audit |

### CONFIRMED KEEP after KEEP-floor audit

These rows were explicitly re-audited after the builder/demote migration. They
remain public `data class`es because they are frozen/wire/state/value surfaces,
not growable consumer construct types.

The audit also confirmed the existing KEEP floor: Google provider
`Model`/`Agent`/`ManagedAgent` leaves, `ToolNameMapping`, JSON-RPC/MCP wire
envelopes, `Elicitation*`, `ToolApprovalRequest`, the four state containers,
generation-input/prompt/content sealed leaves, `Raw`/`Json`/`Pattern` leaves,
`ScriptedResponse`, and `UIMessage`.

| File | Line | Type | f | Ser | Reason |
|---|---:|---|---:|:--:|---|
| `Gateway.kt` | 27 | `GatewayAuthToken` | 3 |  | confirmed KEEP token value in KEEP-floor audit |
| `McpProtocol.kt` | 88 | `MCPBaseParams` | 2 | ✅ | confirmed KEEP MCP protocol base params struct (`_meta` carrier) |
| `ResponseFormat.kt` | 49 | `Json` | 4 | ✅ | confirmed KEEP small sealed response-format leaf |
| `providers/LiteRTLanguageModel.kt` | 57 | `LiteRTChannel` | 4 |  | confirmed KEEP LiteRT channel holder |
| `providers/LiteRTLanguageModel.kt` | 69 | `ToolResponse` | 2 |  | confirmed KEEP LiteRT content leaf |
| `providers/LiteRTLanguageModel.kt` | 72 | `LiteRTToolCall` | 5 |  | confirmed KEEP LiteRT tool-call holder |
| `providers/LiteRTLanguageModel.kt` | 79 | `LiteRTMessage` | 6 |  | confirmed KEEP LiteRT message holder |

### REVIEW (0)

_No remaining REVIEW rows after the Batch 18 KEEP-floor audit._

### NON-PUBLIC gate artifacts (17) — not real public ABI

These match the budget regex but are nested in `internal`/`private`/function-local scope, so demoting them gives **zero ABI benefit** — but converting them off `data` (or `@Poko`) is a **free, risk-less budget reduction**. Optional pre-batch ("Batch D0").

| File | Line | Type | f | Why non-public |
|---|---:|---|---:|---|
| `Generate.kt` | 192 | `Error` | 1 | private sealed ReplayTerminal |
| `StreamObjectResult.kt` | 65 | `TextBlock` | 4 | function-local in flow{} |
| `TelemetryTracing.kt` | 113 | `Value` | 1 | internal sealed TelemetryAttribute |
| `TelemetryTracing.kt` | 114 | `Input` | 1 | internal sealed TelemetryAttribute |
| `TelemetryTracing.kt` | 115 | `Output` | 1 | internal sealed TelemetryAttribute |
| `ToolExecutionResult.kt` | 17 | `Success` | 6 | internal sealed ToolExecutionResult |
| `ToolExecutionResult.kt` | 27 | `Failure` | 3 | internal sealed ToolExecutionResult |
| `providers/BedrockResponse.kt` | 284 | `Tool` | 4 | internal sealed BedrockStreamBlock |
| `providers/GoogleInteractionsModel.kt` | 181 | `PendingModelOutput` | 1 | private sealed inside internal class |
| `providers/GoogleInteractionsModel.kt` | 182 | `Text` | 1 | private sealed inside internal class |
| `providers/GoogleInteractionsModel.kt` | 183 | `Image` | 5 | private sealed inside internal class |
| `providers/GoogleInteractionsModel.kt` | 203 | `Reasoning` | 3 | private sealed inside internal class |
| `providers/GoogleInteractionsModel.kt` | 213 | `FunctionCall` | 6 | private sealed inside internal class |
| `providers/GoogleInteractionsModel.kt` | 230 | `BuiltinToolCall` | 6 | private sealed inside internal class |
| `providers/GoogleInteractionsModel.kt` | 251 | `BuiltinToolResult` | 7 | private sealed inside internal class |
| `providers/GoogleInteractionsModel.kt` | 277 | `Unknown` | 1 | private sealed inside internal class |
| `ui/MessageStreamReader.kt` | 190 | `ToolUpsertOptions` | 2 | function-local in StreamToUiMessages |

### Tricky / flagged interactions

- **Sealed-leaf data classes (most of the DEMOTE set).** The event/message/result hierarchies — `StreamEvent`, `AgentEvent`(+`Outcome`), `ContentPart`, `UIMessagePart`, `ToolResultOutput`, `ValidationResult`, `ExecuteToolResult`, `CompletionPhase`, `StructuredObjectPhase`, `ParseResult`, `SafeValidateUIMessagesResult`, `FileData`, `ToolLoopAgentAction.Phase` — are consumed via exhaustive `when`. Demoting drops `componentN()`; **internal code does not destructure them** (the 18 `val (a,b)=` sites are all Pairs/Triples/helper returns — verified), so the only consumer-facing loss is positional destructuring in `when`, which is rare for these. Field access is by name and unaffected.
- **`copy()` used internally (58 flagged ⟳).** Demotion to `@Poko` removes `copy()`. Hot spots: `StreamEventTelemetryRedaction.kt` (25 calls on `StreamEvent` leaves), `MutableStateFlow.update { it.copy() }` in `ToolLoopAgent.kt`/`AgentSession.kt`/`CompletionApi.kt`/`ChatSession.kt` (state snapshots), and provider `result.copy(usage=)` / `event.copy(usage=)` shims (Mistral, Alibaba, DeepInfra). Each demotion batch must rewrite its `copy()` sites to fresh-construct — sized into the batch notes above.
- **`@Poko` + `@Serializable` (77 DEMOTE types).** See the risk callout above — unproven in-repo; D1 is the canary.
- **Call-params decision (`LanguageModelCallParams`, `EmbeddingModelCallParams`) → DEMOTE via `@Poko` + public `toBuilder()`.** Framework-produced params were formerly transformed by *consumer* middleware via `params.copy(...)` (DefaultSettings, AddToolInputExamples, provider-options shims). The owner decision is to remove public `copy()` / `componentN()` and expose a public seeded builder replacement: `params.toBuilder().field(...).build()`.
- **MCP JSON-RPC envelopes → KEEP, MCP results → DEMOTE.** `JSONRPCRequest/Notification/Response/Error/ErrorData` are frozen by JSON-RPC 2.0 (KEEP). The capability/result/resource/prompt structs follow the evolving MCP spec (DEMOTE, batch D5). `MCPBaseParams` was confirmed KEEP in the Batch 18 KEEP-floor audit as the `_meta` wire base carrier.
- **OAuth split.** `OAuthTokens` (response/persisted) + the two server-metadata structs → DEMOTE (D14). `OAuthClientInformation`/`OAuthClientMetadata` (consumer-supplied) → BUILDER.
