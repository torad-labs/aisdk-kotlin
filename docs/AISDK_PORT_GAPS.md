# Port Gap Ledger

> **Status (2026-05-30): the port is feature-complete for the extracted
> core agent/tool/UI-message surface.** Every gap from the original audit
> that had, or could plausibly get, a real core-library consumer is closed.
> What remains is
> forward-parity surface with no current consumer (deferred, below) and
> cloud/server/multi-provider surface that does not belong in this package
> (N/A, below).
>
> This file used to be a 390-line live audit (a numbered punch list + a
> phased implementation plan + per-area prose). That audit did its job —
> it drove the port to completeness — and is now collapsed. The
> authoritative public surface is **`INTERFACE_CONTRACT.md`**; the
> what/how/commit detail for each closed gap lives in **git history**
> (search the log for `gap #N` or the `feat(aisdk)` / `Phase 4` commits).

## Closed

The audit tracked ~40 deltas across Agent/Tool/Loop, Streaming/Provider/
Middleware, UI types, and missing v6 directories. The load-bearing ones —
and everything the core package consumes directly — are done:

- **Tool/loop contract**: executor-as-`Flow` (preliminary results),
  `experimental_repairToolCall` (+ typed `AgentError` taxonomy wired into
  the loop), `inputExamples`, `onInput{Start,Delta,Available}` hooks,
  `ToolPredicateOptions` (toolCallId + messages), structured
  `ToolResultOutput`, `ToolExecutionContext.{toolCallId, writer}`,
  `prepareStep.experimental_context` override, `metadata`, `ToolStreamWriter`.
- **Streaming/provider/middleware**: the `{doGenerate, doStream}`
  middleware shape (fixed `simulateStreamingMiddleware`), `providerMetadata`
  swept across all content + tool-lifecycle variants, rich `Usage` tree,
  `ResponseFormat`, `presence/frequencyPenalty`, `tool-output-denied` +
  `approvalId`, `fixJson` / `parsePartialJson`, `injectJsonInstruction`,
  truncation-repair in `extractJsonMiddleware`, `loggingMiddleware` over
  the `Logger` primitive, `provider` + `supportedUrls`, CJK `smoothStream`.
- **UI types**: full 7-state `ToolCallState`, `DynamicToolUI`, `StepStart`,
  `UIMessage.metadata`, `SourceUrl`/`SourceDocument` split, `TextUIPartState`,
  `convertToModelMessages(ignoreIncompleteToolCalls)`, `preliminary`.
- **Directories**: `error/` (as `AgentError`), `logger/` (as `Logger` +
  `loggingMiddleware`), `util/` load-bearing helpers.

## Deliberately deferred — forward-parity, no current core consumer

Each is real v6 surface the port could grow later; none blocks a current
core-library consumer, so porting now would add dead code. Listed so a future
dev knows the gap is *known and chosen*, not missed.

| Item | Why deferred | Partial substitute |
|---|---|---|
| Standalone `generateObject` / `streamObject` | Structured output goes through `Output.{obj,array,json}` + `generateText(output = …)`; a parallel entry point has no caller. | `Output<T>` + `injectJsonInstruction` |
| `extractJsonMiddleware` *incremental* streaming (v6's 12-char-lookahead state machine) | Current structured-output consumers decode the whole object through `Output.decode`; token-by-token JSON rendering has no core consumer. | single-emit-at-`Finish` + truncation repair |
| `DataUIPart<DATA_TYPES>` (typed `data-*` parts) | No feature streams custom typed data parts yet. | `ToolStreamWriter.writeData(JsonElement)` → `StreamEvent.Raw` |
| `dynamicTool` helper (type-erased, MCP-style) | No external/MCP tool integration on-device. | `tool(...)` / `streamingTool(...)` |
| `GenerateTextResult` / `StreamTextResult` rich aggregates (`.warnings`, `.staticToolCalls`, `.dynamicToolCalls`, `.response`) | Consumers read the `Flow<StreamEvent>` + minimal result struct directly. | `Flow<StreamEvent>` + `GenerateResult` |
| Structured tool-result **stream** output (v6's discriminated `ToolResultOutput` on the wire) | `StreamEvent.ToolResult` carries `outputJson` + `modelVisible: JsonElement` — a deliberate divergence; `ToolResultOutput` already exists for the `toModelOutput` return. | `outputJson` + `modelVisible` |
| Agent-level persistent `activeTools` allowlist | `StepSettings.activeTools` gives per-step scoping, which covers the need. | per-step `activeTools` |
| Deep stream sub-field drift (`dynamic` / `providerExecuted` flags, `rawFinishReason`, `doStream` returning `{request, response}`) | Telemetry/cloud-routing fields with no on-device meaning. | — |
| `callOptionsSchema` runtime validation | Low value; the schema is type-checked at construction. | — |

## What stays N/A (will not port)

- **Cloud API surfaces**: `embed`, `generate-image`, `generate-speech`,
  `generate-video`, `rerank`, `transcribe`.
- **Multi-provider routing**: `registry`, `customProvider`,
  `createProviderRegistry`.
- **Server-shape helpers**: `createAgentUiStream*`,
  `pipeUIMessageStreamToResponse`, `createUIMessageStreamResponse`,
  `processToolCalls` (Next.js / Hono).
- **OTel telemetry**: the entire `telemetry/` dir. Hosts should provide
  telemetry through middleware or provider-specific packages.
- **Browser-specific**: `experimental_download`, `headers`, `maxRetries`,
  `seed`.
- **Provider-executed tools** (`Tool.type = 'provider'`): OpenAI Code
  Interpreter, Anthropic web search.
- **React-binding plumbing**: `Chat` class, `useChat`,
  `addToolApprovalResponse`, `addToolOutput` (the Kotlin VM consumes the
  Flow directly).
- **Deprecated v5 aliases**: `isToolOrDynamicToolUIPart`, etc.

---

If a deferred item gains a real consumer, port it then and delete its row
here. Don't re-grow the audit — `INTERFACE_CONTRACT.md` is the surface of
record.
