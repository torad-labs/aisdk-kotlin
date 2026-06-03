# Port Gap Ledger

> **Status (2026-06-03): the port target is the full stable v6 `ai`
> package surface, adapted to Kotlin Multiplatform.** JS-only return
> types such as browser `Response`, Node `ServerResponse`, and React
> hooks are represented as Kotlin `Flow`, response value objects, and
> host-facing interfaces.
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
- **Top-level generation/output parity**: `generateText` / `streamText`
  forward penalties and response format, `Output.choice` / `Output.json`
  are restored, `Output.array` accepts the v6 `{ elements: [...] }` shape,
  deprecated `generateObject` / `streamObject` wrappers delegate through
  structured `generateText` / `streamText`, and `GenerateTextResult`
  carries warnings, request/response metadata, provider metadata, content,
  reasoning, files, sources, and raw finish reason.
- **UI types**: full 7-state `ToolCallState`, `DynamicToolUI`, `StepStart`,
  `UIMessage.metadata`, `SourceUrl`/`SourceDocument` split, `TextUIPartState`,
  `convertToModelMessages(ignoreIncompleteToolCalls)`, `preliminary`.
- **Directories**: `error/` (as `AgentError`), `logger/` (as `Logger` +
  `loggingMiddleware`), `util/` load-bearing helpers.
- **Full-package feature areas**: `embed`, `generate-image`,
  `generate-speech`, `generate-video`, `rerank`, `transcribe`,
  `registry`, `telemetry`, `text-stream`, `ui-message-stream`, and
  `ui` chat transports now have KMP surfaces and tests.
- **Gateway**: `createGateway`, `createGatewayProvider`, `gateway`,
  gateway model-id aliases, metadata calls, credit/spend/generation info
  types, gateway errors, and provider-executed gateway tools are present.
  The common module keeps the injected `GatewayTransport` seam and also
  ships `KtorGatewayTransport` for Android/iOS/backend callers that want
  an immediate HTTP implementation.
- **HTTP provider adapters**: `createOpenAICompatible` provides the v6
  OpenAI-compatible shape for chat, completion, embedding, image, speech,
  and transcription endpoints over Ktor.
- **Provider-utils exports**: `dynamicTool`, `jsonSchema`, `asSchema`,
  `zodSchema`, `generateId`, `createIdGenerator`,
  `parseJsonEventStream`, header normalization/user-agent helpers,
  base64 byte helpers, media-type helpers, URL support/download
  validation, and `DownloadError` are available in Kotlin form.
- **Compatibility exports**: `DefaultGeneratedFile`, experimental media
  aliases, `pruneMessages`, uppercase `validateUIMessages` /
  `safeValidateUIMessages`, and the root v6 error taxonomy are present.

## Deliberately deferred — forward-parity, no current core consumer

Each is real v6 surface the port could grow later; none blocks a current
core-library consumer, so porting now would add dead code. Listed so a future
dev knows the gap is *known and chosen*, not missed.

| Item | Why deferred | Partial substitute |
|---|---|---|
| `extractJsonMiddleware` *incremental* streaming (v6's 12-char-lookahead state machine) | Current structured-output consumers decode the whole object through `Output.decode`; token-by-token JSON rendering has no core consumer. | single-emit-at-`Finish` + truncation repair |
| `StreamTextResult` promise metadata (`.warnings`, `.response`) | KMP exposes cold `Flow<StreamEvent>` directly and keeps call metadata on `GenerateTextResult`; the text/UI stream facades are present. | `StreamTextResult.fullStream`, `.textStream`, and response helpers |
| Structured tool-result **stream** output (v6's discriminated `ToolResultOutput` on the wire) | `StreamEvent.ToolResult` carries `outputJson` + `modelVisible: JsonElement` — a deliberate divergence; `ToolResultOutput` already exists for the `toModelOutput` return. | `outputJson` + `modelVisible` |
| Agent-level persistent `activeTools` allowlist | `StepSettings.activeTools` gives per-step scoping, which covers the need. | per-step `activeTools` |
| Deep stream sub-field drift (`doStream` returning JS `{request, response}` promise fields) | Telemetry/cloud-routing fields with no on-device meaning in the common model stream. | `GenerateTextResult.request/response`, provider metadata |
| `callOptionsSchema` runtime validation | Low value; the schema is type-checked at construction. | — |

## Platform adapter surface

- **Server-shape helpers**: `createAgentUiStream*`,
  `pipeUIMessageStreamToResponse`, `createUIMessageStreamResponse`, and
  text-stream response helpers are represented as `Flow` value objects
  and `ServerResponseWriter`; HTTP frameworks bind these to concrete
  sockets/responses.
- **OTel telemetry**: represented through host-injected
  `TelemetryIntegration` and the KMP `TelemetryTracer` / `TelemetryActiveSpan`
  abstraction rather than a hard OpenTelemetry dependency.
- **Browser/server-specific transport concerns**: `headers`, retry
  policy, gateway request context, response helpers, Ktor Gateway transport,
  and OpenAI-compatible Ktor provider exist in common contracts; framework
  response binding still lives in host/platform modules.
- **Provider-executed tools** (`Tool.type = 'provider'`): represented by
  `providerExecutedTool`, `LanguageModelTool.providerExecuted`, and
  gateway `parallelSearch` / `perplexitySearch`; concrete hosted tools
  beyond gateway are provider-module work because execution is
  provider-specific.
- **React-binding plumbing**: `Chat` and transports exist in Kotlin;
  `useChat` remains a React hook concept and is expressed by collecting
  `Flow<UIMessage>` in the host UI layer.
- **Deprecated v5 aliases**: `isToolOrDynamicToolUIPart`, etc.

---

If a deferred item gains a real consumer, port it then and delete its row
here. Don't re-grow the audit — `INTERFACE_CONTRACT.md` is the surface of
record.
