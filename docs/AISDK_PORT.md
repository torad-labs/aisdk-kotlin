# AI SDK v6 To KMP Port

This document records how the Kotlin API maps to Vercel AI SDK v6 concepts.
The implementation is a green-room Kotlin rewrite of the architectural
contracts, not a TypeScript source translation and not an official Vercel
package.

## What this is

A KMP library that ports the **architectural
patterns** of Vercel AI SDK v6 into idiomatic Kotlin. Not a literal
TypeScript translation — the patterns, contracts, lifecycle, and composition
model translated into the language Kotlin actually wants to use.

## What's in scope vs not

| Layer | Status |
|---|---|
| AI SDK Core (`generateText`, `streamText`, `Output`, `Tool()`, `DynamicTool()`, `Agent` + `ToolLoopAgent`, `wrapLanguageModel`, `stopWhen`, `LanguageModel`, lifecycle hooks, `prepareCall` / `prepareStep`) | ✅ ported |
| AI SDK UI patterns (message parts, tool call states, `InferAgentUIMessage` equivalent, message stream reader, chat transports) | ✅ ported as Kotlin types/flows — Compose components live elsewhere |
| `useChat` React hook | ✅ Kotlin equivalent is `Chat` + `ChatTransport` + `Flow<UIMessage>` collection |
| `DefaultChatTransport`, text/UI stream response helpers | ✅ ported as `ChatTransport`, `TextStreamResponse`, `UIMessageStreamResponse`, and `ServerResponseWriter` |
| `@vercel/ai-elements` components | ❌ not ported — Compose, SwiftUI, and server renderers have different idioms |
| RSC integration | ✅ ported as Kotlin server/UI stream primitives, without React Server Components runtime bindings |
| Embeddings, reranking, image gen, speech, transcription, video | ✅ ported as provider-neutral model contracts + helpers |
| Registry and provider routing | ✅ `Provider`, `customProvider`, `createProviderRegistry`, `wrapProvider` |
| Telemetry | ✅ host-injected `TelemetryIntegration` primitives plus KMP tracer/span support |
| Gateway/OpenAI-compatible HTTP providers | ✅ Ktor-backed common adapters |
| Provider-specific facades (Anthropic, Google, Bedrock, etc.) | ✅ folded into the root artifact for now; future `aisdk-provider-*` artifacts can split publication boundaries |

## v5 → v6 deltas this port respects

The validator references make these renames non-negotiable:

| v5 (DEAD) | v6 (LIVE) |
|---|---|
| `parameters: z.object({...})` | `inputSerializer = serializer<T>()` (Kotlin) / `inputSchema: z.object()` (TS) |
| `maxSteps: 5` | `stopWhen = StepCountIs(5)` |
| `maxTokens: 512` | `maxOutputTokens = 512` |
| `generateObject({schema})` | Prefer `generateText(output = Output.obj(serializer<T>()))`; deprecated `generateObject(output = ...)` exists for compatibility |
| `streamObject(...)` | Prefer `streamText(output = Output.obj(...))`; deprecated `streamObject(output = ...)` exists for compatibility |
| `CoreMessage` | `ModelMessage` |
| `agent.generateText()` | `agent.generate()` |
| `agent.streamText()` | `agent.stream()` |
| `tool-invocation` part type | `tool-{toolName}` discriminated by `toolName` field |
| `partial-call` / `call` / `result` | `InputStreaming` / `InputAvailable` / `OutputAvailable` |
| `addToolResult({result})` | `toolApprovalResponseMessage(...)` re-fed to `generate(messages = ...)`; `ToolResult` event for execution |
| `part.args`, `part.result` | `part.input`, `part.output` |

## How the port maps to v6 — file-by-file

| TypeScript v6 | Kotlin port file |
|---|---|
| `Agent` interface | `Agent.kt` |
| `ToolLoopAgent` class | `ToolLoopAgent.kt` |
| `tool({...})` factory | `Tool.kt` (top-level `Tool()` function + `Tool` class + `ToolSet`) |
| `needsApproval`, `addToolOutput` | `ToolApproval.kt` (`PendingApproval`) + `ContentPart.ToolApprovalRequest` / `ToolApprovalResponse` (RPC return-then-resume) |
| `LanguageModel`, `LanguageModelV3` | `LanguageModel.kt` (interface + `LanguageModelCallParams`) |
| `wrapLanguageModel`, `LanguageModelMiddleware` | `Middleware.kt` |
| `Output.object`, `Output.array`, `Output.choice`, `Output.json` | `Output.kt` (`Output.obj` instead of `object` — Kotlin keyword) |
| `stepCountIs`, `hasToolCall`, `StopCondition` | `StopCondition.kt` (`StepCountIs`, `HasToolCall`) |
| `onStart`, `onStepFinish`, `onFinish`, `onError` | `Lifecycle.kt` |
| `prepareCall`, `prepareStep` scopes | `Context.kt` |
| `ToolExecutionContext` | `Context.kt` (member of) |
| `generateText`, `streamText` | `Generate.kt` (top-level functions) |
| `generateObject`, `streamObject` | `Generate.kt` (deprecated compatibility wrappers over structured `generateText` / `streamText`) |
| `embed`, `embedMany` | `Embedding.kt` |
| `generateImage`, `generateSpeech`, `generateVideo`, `transcribe` | `MediaModels.kt` |
| `rerank` | `Rerank.kt` |
| `customProvider`, `createProviderRegistry`, `wrapProvider` | `Provider.kt` |
| `createGateway`, `gateway`, gateway metadata APIs | `Gateway.kt` |
| Gateway HTTP transport | `KtorGatewayTransport.kt` |
| OpenAI-compatible provider | `OpenAICompatibleProvider.kt` |
| `parseJsonEventStream`, ID/header/base64/download helpers | `Util.kt` |
| `DefaultGeneratedFile`, experimental media aliases | `MediaModels.kt` |
| `pruneMessages` | `PruneMessages.kt` |
| `createTextStreamResponse`, `createUiMessageStreamResponse`, `Chat` | `ui/Streams.kt`, `ui/Chat.kt` |
| `AbortSignal`, `AbortController` | `AbortSignal.kt` (custom interface wrapping coroutines `Job`) |
| `CoreMessage` → `ModelMessage`, `Usage`, `FinishReason` | `ModelMessage.kt` |
| Stream events (`text`, `tool-call`, `tool-result`, etc.) | `Streaming.kt` (sealed `StreamEvent`) |
| `UIMessage` + parts | `ui/UIMessage.kt`, `ui/UIMessagePart.kt`, `ui/ToolCallState.kt` |
| `InferAgentUIMessage`, `UIToolInvocation` | `ui/InferAgentMessage.kt` |
| Stream → UIMessage aggregation (internal to `useChat`) | `ui/MessageStreamReader.kt` (`streamToUiMessages` top-level) |
| Mock provider | `providers/MockLanguageModel.kt` |

## Where the port had to deviate from v6

A few v6 patterns don't survive the language jump cleanly. The port
diverges intentionally in these spots:

1. **`Output.object` → `Output.obj`.** `object` is a Kotlin keyword
   (singleton declaration). The port uses `obj` consistently.
2. **No type-level `InferAgentUIMessage<typeof agent>`.** Kotlin lacks
   TypeScript's literal-type narrowing on string discriminants. The
   substitute is the explicit `ToolPartHandlerRegistry` (see
   `ui/InferAgentMessage.kt`) which gives runtime-typed dispatch.
3. **Top-level extraction helpers.**
   v6's `part.outputAs<T>()` becomes top-level `outputAs(part, serializer)`.
   This keeps the public API explicit at the UI seam and works consistently
   across common Kotlin, Android, iOS, and JVM callers.
4. **`AbortSignal` is a custom interface, not the JS `AbortSignal`.**
   Internally wraps a coroutine `Job` for ergonomics that match v6.
   See DECISION 3 in `AISDK_PORT_DECISIONS.md`.
5. **HTTP helpers are value objects and writer interfaces.** v6 ships
   browser/Node response helpers. The KMP port exposes response metadata,
   stream value objects, and `ServerResponseWriter` so Android, iOS, JVM,
   and server hosts bind them to their own networking boundary.
6. **`streamObject` returns `Flow<StreamEvent>`.** The TypeScript SDK returns
   a browser/Node stream facade with promise properties. The KMP core uses
   the same cold `Flow<StreamEvent>` contract as `streamText`; object typing
   is expressed by `Output<T>` and final decoding.
7. **Gateway uses injected transport.** The v6 JS package uses `fetch`.
   This common Kotlin library exposes `GatewayTransport` so platform or
   provider modules can bind Ktor, OkHttp, NSURLSession, server frameworks,
   or test fakes. The module also ships `KtorGatewayTransport` for callers
   that want a ready KMP HTTP implementation.
