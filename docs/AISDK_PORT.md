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
| AI SDK Core (`TextGenerator.generate` / `stream` / `streamResult`, `Output`, `Tool()`, `DynamicTool()`, `Agent` + `ToolLoopAgent`, `WrapLanguageModel`, `StopCondition`, `LanguageModel`, `AgentEvent`, `prepareCall` / `prepareStep`) | ✅ ported |
| AI SDK UI patterns (message parts, tool call states, `InferAgentUIMessage` equivalent, message stream reader, chat transports) | ✅ ported as Kotlin types/flows — Compose components live elsewhere |
| `useChat` React hook | ✅ Kotlin equivalent is `Chat` + `ChatTransport` + `Flow<UIMessage>` collection |
| `DefaultChatTransport`, text/UI stream response helpers | ✅ ported as `ChatTransport`, `TextStreamResponse`, `UIMessageStreamResponse`, and `ServerResponseWriter` |
| `@vercel/ai-elements` components | ❌ not ported — Compose, SwiftUI, and server renderers have different idioms |
| RSC integration | ✅ ported as Kotlin server/UI stream primitives, without React Server Components runtime bindings |
| Embeddings, reranking, image gen, speech, transcription, video | ✅ ported as provider-neutral model contracts + helpers |
| Registry and provider routing | ✅ `Provider`, `CustomProvider`, `ProviderRegistry.createProviderRegistry`, `WrapProvider` |
| Telemetry | ✅ host-injected `Telemetry`, `TelemetrySettings`, and `TelemetryTracer` primitives |
| Gateway/OpenAI-compatible HTTP providers | ✅ Ktor-backed common adapters |
| Provider-specific facades (Anthropic, Google, Bedrock, etc.) | ✅ folded into the root artifact for now; future `aisdk-provider-*` artifacts can split publication boundaries |

## v5 → v6 deltas this port respects

The validator references make these renames non-negotiable:

| v5 (DEAD) | v6 (LIVE) |
|---|---|
| `parameters: z.object({...})` | `inputSerializer = serializer<T>()` (Kotlin) / `inputSchema: z.object()` (TS) |
| `maxSteps: 5` | `stopWhen = StepCountIs(5)` |
| `maxTokens: 512` | `maxOutputTokens = 512` |
| `generateObject({schema})` | `TextGenerator(model).generate(input, OutputObj(serializer<T>())).first().output` |
| `streamObject(...)` | `StructuredObjectGenerator(model, schema).stream(input)` for partial object phases, or `.generate(input)` for the final value |
| `CoreMessage` | `ModelMessage` |
| `agent.generateText()` | `agent.generate()` |
| `agent.streamText()` | `agent.stream()` |
| `tool-invocation` part type | `tool-{toolName}` discriminated by `toolName` field |
| `partial-call` / `call` / `result` | `InputStreaming` / `InputAvailable` / `OutputAvailable` |
| `addToolResult({result})` | `ToolApprovalResponseMessage(...)` re-fed to `generate(messages = ...)`; `ToolResult` event for execution |
| `part.args`, `part.result` | `part.input`, `part.output` |

## How the port maps to v6 — file-by-file

| TypeScript v6 | Kotlin port file |
|---|---|
| `Agent` interface | `Agent.kt` |
| `ToolLoopAgent` class | `ToolLoopAgent.kt` |
| `tool({...})` factory | `Tool.kt` (top-level `Tool()` function + `Tool` class + `ToolSet`) |
| `needsApproval`, `addToolOutput` | `ToolApproval.kt` (`PendingApproval`) + `ContentPart.ToolApprovalRequest` / `ToolApprovalResponse` (RPC return-then-resume) |
| `LanguageModel`, `LanguageModelV3` | `LanguageModel.kt` (interface + `LanguageModelCallParams`) |
| `wrapLanguageModel`, `LanguageModelMiddleware` | `Middleware.kt` (`WrapLanguageModel`, `LanguageModelMiddleware`) |
| `Output.object`, `Output.array`, `Output.choice`, `Output.json` | `Output.kt` (`Output.obj` instead of `object` — Kotlin keyword) |
| `stepCountIs`, `hasToolCall`, `StopCondition` | `StopCondition.kt` (`StepCountIs`, `HasToolCall`) |
| `onStart`, `onStepFinish`, `onFinish`, `onError` | `Lifecycle.kt` |
| `prepareCall`, `prepareStep` scopes | `Context.kt` |
| `ToolExecutionContext` | `Context.kt` (member of) |
| `generateText`, `streamText` | `TextGenerator.kt` (`TextGenerator(model).generate(...)`, `.stream(...)`, `.streamResult(...)`) |
| `generateObject`, `streamObject` | `TextGenerator.generate(..., OutputObj(...))` for final values; `StructuredObjectGenerator` for object streaming |
| `embed`, `embedMany` | `Embedding.kt` (`Embedding.embed`, `Embedding.embedMany`) |
| `generateImage`, `generateSpeech`, `generateVideo`, `transcribe` | `ImageModels.kt`, `SpeechModels.kt`, `VideoModels.kt`, `TranscriptionModels.kt` (`ImageGeneration`, `SpeechGeneration`, `VideoGeneration`, `Transcription`) |
| `rerank` | `Rerank.kt` (`Reranking.rerank`) |
| `customProvider`, `createProviderRegistry`, `wrapProvider` | `Provider.kt` (`CustomProvider`, `ProviderRegistry.createProviderRegistry`, `WrapProvider`) |
| `createGateway`, `gateway`, gateway metadata APIs | `Gateway.kt` (`Gateway`, `GatewayProviderSettings`) |
| Gateway HTTP transport | `KtorGatewayTransport.kt` |
| OpenAI-compatible provider | `OpenAICompatibleProvider.kt` |
| `parseJsonEventStream`, ID/header/data-url/download helpers | `HttpTransport.kt`, `IdGenerator.kt`, `DataUrl.kt`, `ConvertToLanguageModelPrompt.kt`, `ProviderHeaders.kt` |
| `DefaultGeneratedFile`, experimental media aliases | `MediaModels.kt` |
| `pruneMessages` | `PruneMessages.kt` (`MessagePruning.pruneMessages`) |
| `createTextStreamResponse`, `createUiMessageStreamResponse`, `Chat` | `ui/Streams.kt` (`CreateTextStreamResponse`, `CreateUiMessageStreamResponse`) and `ui/Chat.kt` |
| `AbortSignal`, `AbortController` | `AbortSignal.kt` (custom interface wrapping coroutines `Job`) |
| `CoreMessage` → `ModelMessage`, `Usage`, `FinishReason` | `ModelMessage.kt` |
| Stream events (`text`, `tool-call`, `tool-result`, etc.) | `Streaming.kt` (sealed `StreamEvent`) |
| `UIMessage` + parts | `ui/UIMessage.kt`, `ui/UIMessagePart.kt`, `ui/ToolCallState.kt` |
| `InferAgentUIMessage`, `UIToolInvocation` | `ui/InferAgentMessage.kt` |
| Stream → UIMessage aggregation (used by chat transports) | `ui/MessageStreamReader.kt` (`StreamToUiMessages`) and `ui/Streams.kt` (`UiMessageStreams`) |
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
6. **Structured object streaming is Kotlin-shaped.** The TypeScript SDK
   returns a browser/Node stream facade with promise properties. The KMP core
   exposes `StructuredObjectGenerator.stream(input)` as a cold Flow of object
   phases; final typed values come from `StructuredObjectGenerator.generate`
   or `TextGenerator.generate(input, Output<T>)`.
7. **Gateway uses injected transport.** The v6 JS package uses `fetch`.
   This common Kotlin library exposes `GatewayTransport` so platform or
   provider modules can bind Ktor, OkHttp, NSURLSession, server frameworks,
   or test fakes. The module also ships `KtorGatewayTransport` for callers
   that want a ready KMP HTTP implementation.
