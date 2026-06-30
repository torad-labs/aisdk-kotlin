# Interface Contract

> What consumers are entitled to import from `ai.torad.aisdk.*`. Anything not on this list
> is an implementation detail and may change without notice.
>
> Update this file when the public surface changes.

## Module: `aisdk-kotlin` — `ai.torad.aisdk`

### Agent

- `interface Agent<TContext, TOutput>`
  - `suspend fun generate(prompt?, messages = emptyList(), options?, abortSignal?, hooks?): GenerateResult<TOutput>`
  - `fun stream(prompt?, messages = emptyList(), options?, abortSignal?, hooks?): Flow<StreamEvent>`
- `abstract class ToolLoopAgent<TContext, TOutput>(...) : Agent<TContext, TOutput>` — extend-only default loop implementation; applications provide a named subclass
  - `val engineState: StateFlow<ToolLoopAgentState>`
  - `fun dispatchEngineAction(action: ToolLoopAgentAction<TContext>)`
  - `fun close()`
- `data class ToolLoopAgentState(messages, streamingAssistantText, currentToolCalls, pendingApprovals, phase, totalSteps, lastFinishReason)` — state-holder surface for long-lived hosts; remains a data class for `StateFlow.update { it.copy(...) }` MVI usage. `ToolLoopAgentState.Phase.Error` is an `@Poko class` value-semantics leaf.
- `sealed class ToolLoopAgentAction<TContext>` — `UserSubmitPrompt`, `ApproveToolCall`, `DenyToolCall`, `Cancel`, `Reset`
- `@Poko class GenerateResult<TOutput>(output, text, steps, finishReason, usage, pendingApprovals = [], messages = [])`
- `data class AgentCallHooks(onStart?, onStepStart?, onStepFinish?, onFinish?, onError?, onChunk?)` — per-call hook surface

### Tool definition

- `abstract class Tool<TInput, TOutput, TContext>` — extend it for named tools, or construct via PascalCase factories:
  - `val schema: ToolSchema` where `strict: Boolean? = null` means provider-default tool strictness; explicit `true` / `false` is forwarded only by providers that support tool strict mode. `ToolSchema` is an `@Poko class` value-semantics type; field access remains, but public `copy()` / `componentN()` ABI is intentionally absent.
  - `fun Tool(...): Tool<...>` — single-value executor `suspend ToolExecutionContext<TContext>.(TInput) -> TOutput`. Factory wraps in a one-emission flow. Use for the common case where the tool produces exactly one result.
  - `fun StreamingTool(...): Tool<...>` — `Flow<TOutput>`-returning executor. Each emission becomes a `StreamEvent.ToolResult`; the LAST emission is final (feeds the model on subsequent turns), earlier emissions are `preliminary = true` (UI-only progress). Empty flow → `StreamEvent.ToolError("tool emitted no values")`. Use when a tool can produce a useful early snapshot before the full result is ready.
  - `fun DynamicTool(...): Tool<JsonElement, JsonElement, TContext>` — runtime-typed JSON tool.
- `class ToolSet<TContext>(...)` — `find(name): Tool?`, `names(): List<String>`, `descriptors`, `plus(other)`
- `fun ToolSet<TContext>(vararg tools: Tool<*, *, TContext>): ToolSet<TContext>`
- `class ToolExecutionContext<TContext>(context, abortSignal, stepNumber, messages, toolCallId, writer: ToolStreamWriter = NoopToolStreamWriter)` — `this` inside tool executor. `context` is the running typed context (a `prepareStep.experimental_context` override flows in here, gap #16). `writer` writes back into the active stream (gap #21).
- `interface ToolStreamWriter { suspend fun write(event: StreamEvent); suspend fun writeData(value: JsonElement) }` — v6's `UIMessageStreamWriter` (gap #21). `writeData` emits `StreamEvent.Raw(value)`. `object NoopToolStreamWriter` is the off-loop default. Writes interleave with the tool's own emissions in stream order; `streamToUiMessages` ignores `Raw`, so a consumer that wants the custom data intercepts the `Flow<StreamEvent>` pre-conversion.
- `data class ToolPredicateOptions<TContext>(toolCallId, messages, experimental_context: TContext? = null)` — passed to `Tool.needsApproval` / `Tool.toModelOutput` (gap #17) so a predicate can decide on conversation history or call identity.
- Tool lifecycle hooks (gap #18), all optional + loop-invoked, `runHook`-guarded: `onInputStart(streamingId)` on `ToolInputStart`, `onInputDelta(streamingId, delta)` on `ToolInputDelta`, `onInputAvailable(toolCallId, input)` just before the executor runs.
- `data class ToolExecutionPolicy(maxParallelToolCalls = 8, maxToolCallsPerStep = 128, progressBufferCapacity = 64, toolExecutionTimeout? = null)` — explicit bounded policy for local tool execution inside one step. `ToolLoopAgent.maxParallelToolCalls` remains as shorthand for the policy parallelism cap.
- `typealias ToolCallRepairFunction<TContext> = suspend (failedCall, error, messages, tools) -> ContentPart.ToolCall?` — wired into `ToolLoopAgent` via the optional `experimental_repairToolCall` constructor param. Called once when a tool call's args fail to decode (model emitted JSON that doesn't match the schema). Return a corrected `ContentPart.ToolCall` (possibly with a different `toolName`) to retry; return null to surface `StreamEvent.ToolError`. Single-attempt — no recursive repair. Targets Gemma 4 E2B's ~5% rate of malformed-args calls.
- `sealed class AgentError(message, cause?) : RuntimeException` — tool-loop error taxonomy: `NoSuchTool(toolName, availableTools)`, `InvalidToolInput(toolName, rawArgs, parseError)`, `ToolExecution(toolName, toolCallId, executorError)`, `ToolCallRepairFailed(toolName, originalError, repairError?)`, `InvalidApprovalResponse(toolCallId, knownPendingIds)`, `InvalidToolApprovalSignature(approvalId, toolCallId, reason)`, `InvalidCallOptions(validationError)`, `MaxStepsReached(stepCount)`, `MaxToolCallsPerStepExceeded(toolCallCount, maxToolCallsPerStep)`, `ToolExecutionTimedOut(toolName, toolCallId, timeout)`. The loop populates `StreamEvent.ToolError.error` with these so consumers `when` on the type instead of substring-matching messages.

### Tool approval (RPC return-then-resume)

- `@Serializable @Poko class PendingApproval(toolCallId, toolName, input, approvalId: String? = null)` — surfaced via `GenerateResult.pendingApprovals`. `approvalId` distinct from `toolCallId` per v6 (two approvals can share a tool-call id). Field access and JSON wire shape remain; public `copy()` / `componentN()` ABI is intentionally absent.
- `fun effectiveApprovalId(approval: PendingApproval): String` — top-level helper returning the explicit `approvalId` or falling back to `toolCallId`.

Tool result/output holders (`ToolResult.Success`, `ValidationResult.Success` /
`Failure`, `ExecuteToolResult.Preliminary` / `Final`, `ToolChoice.Specific`,
and `ToolResultOutput` leaves) are `@Poko class` value-semantics types; sealed
parents and serialization wire names remain unchanged, while public `copy()` /
`componentN()` ABI is intentionally absent.
- Approval flow: tool calls `needsApproval` → loop ends → host inspects `pendingApprovals` →
  resumes with `agent.generate(messages = result.messages + toolApprovalResponseMessage(toolCallId, approved, reason?, approvalId?))`

### Output

- `sealed class Output<T>`
  - `Output.obj<T>(serializer, name?, description?): Output<T>`
  - `Output.array<T>(elementSerializer, name?, description?): Output<List<T>>`
  - `Output.choice(values: Iterable<String> | vararg String, name?, description?): Output<String>`
  - `Output.json(name?, description?): Output<JsonElement>`
- Top-level mirrors:
  - `outputObj(serializer, name?, description?)`
  - `outputArray(elementSerializer, name?, description?)`
  - `outputChoice(values | vararg values, name?, description?)`
  - `outputJson(name?, description?)`

### Structured-output utilities

Free functions for models without a native JSON mode (on-device Gemma).
The splice half (`injectJsonInstruction`) seeds the prompt with the schema;
the repair half (`fixJson` / `parsePartialJson`) recovers a usable value
from truncated streaming output. Both ported verbatim from v6 so prompts
and repaired values are byte-identical to the JS SDK.

- Structured-object result/phase holders (`DeepPartial`,
  `StructuredObjectFinish`, `StructuredObjectPhase.Streaming` / `Done`, and
  `StreamObjectFinish`) are `@Poko class` value-semantics types; field access
  remains, but public `copy()` / `componentN()` ABI is intentionally absent.
- `fun fixJson(input: String): String` — close a truncated JSON fragment
  (drains the open-frame stack, completes literals/numbers/strings).
- `fun parsePartialJson(jsonText: String?): PartialJsonResult`
  - `@Poko class PartialJsonResult(value: JsonElement?, state: PartialJsonState)`
  - `enum PartialJsonState { UndefinedInput, SuccessfulParse, RepairedParse, FailedParse }`
- Parser/result helpers (`DownloadedAsset`, `ParseResult.Success` /
  `Failure`, and `PartialJsonResult`) are `@Poko class` value-semantics
  types; field access remains, but public `copy()` / `componentN()` ABI is
  intentionally absent.
- `fun injectJsonInstruction(prompt? = null, schema? = null, schemaPrefix? = …, schemaSuffix? = …): String`
- `fun injectJsonInstructionIntoMessages(messages, schema? = null, schemaPrefix? = …, schemaSuffix? = …): List<ModelMessage>`

### Stop conditions

- `fun interface StopCondition`
- `@Poko class LoopState(stepNumber, totalSteps, lastFinishReason, toolCallsThisStep, toolCallsAllSteps)`
- `fun StepCountIs(n: Int): StopCondition`
- `fun HasToolCall(toolName: String): StopCondition`
- `fun AnyOf(vararg conditions): StopCondition`
- `fun AllOf(vararg conditions): StopCondition`

### Lifecycle hooks

- `data class OnStartEvent(prompt?, priorMessages, options)`
- `data class OnStepStartEvent(stepNumber, messages)`
- `data class OnStepFinishEvent(stepNumber, step)`
- `data class OnFinishEvent(finalOutput, totalSteps, usage, pendingApprovals = [], messages = [])`
- `data class OnErrorEvent(error, stepNumber, source: ErrorSource)`
  - `enum ErrorSource { Hook, Tool, PrepareStep, PrepareCall, Model, Unknown }`
- `data class OnChunkEvent(event: StreamEvent, stepNumber)`
- `data class OnToolCallStartEvent(toolCallId, toolName, input, stepNumber, messages)`
- `data class OnToolCallFinishEvent(toolCallId, toolName, outputJson?, errorMessage?, stepNumber)`
- `@Poko class StepResult(stepNumber, text, reasoning, toolCalls, toolResults, toolApprovalRequests, finishReason, usage)`
- `AgentEvent` leaves, nested `ToolCallFinished.Outcome` leaves, and
  `StepResult` are `@Poko class` value-semantics types; field access remains,
  but public `copy()` / `componentN()` ABI is intentionally absent.

### Prepare scopes

- `class PrepareCallScope<TContext>(options, instructions, model, tools)`
- `data class AgentSettings<TContext>(instructions?, model?, tools?, providerOptions?, temperature?, topP?, topK?, maxOutputTokens?, stopSequences?, seed?, presencePenalty?, frequencyPenalty?, responseFormat?, maxRetries?)`
- `class PrepareStepScope<TContext>(stepNumber, model, steps, messages, context)`
- `data class StepSettings(model?, activeTools?, toolChoice?, messages?, system?, providerOptions?, temperature?, topP?, topK?, maxOutputTokens?, stopSequences?, seed?, presencePenalty?, frequencyPenalty?, responseFormat?, maxRetries?)`

Penalty, response-format, and retry fields participate in the `Step ?: Agent ?: agent-default ?: provider-default` resolution chain — same as the existing sampler params. `maxRetries` applies to non-streaming model round-trips only.

### Cancellation

- `interface AbortSignal { val isAborted; fun throwIfAborted(); fun register(onAbort): Registration }`
- `val AbortSignalNever: AbortSignal`
- `class AbortController { val signal; fun abort() }`
- `class AbortError`
- `fun abortSignalFromJob(job: Job): AbortSignal`

### Provider abstraction

- `interface LanguageModel { val modelId; @LowLevelLanguageModelApi suspend fun generate(...); @LowLevelLanguageModelApi fun stream(...); @LowLevelLanguageModelApi fun streamResult(...) }`
- `annotation class LowLevelLanguageModelApi` — `@RequiresOptIn(ERROR)` marker for direct language-model execution. Use agents or high-level generation helpers for application prompts; opt in only for provider implementations, tests, and deliberate low-level calls.
- `data class LanguageModelCallParams(messages, tools, toolChoice, temperature?, topP?, topK?, maxOutputTokens?, stopSequences, seed?, providerOptions, abortSignal, presencePenalty?, frequencyPenalty?, responseFormat)`
- `@Poko class LanguageModelTool(name, description, parametersSchemaJson, ..., strict: Boolean? = null, ...)`
- `@Poko class LanguageModelResult(text, toolCalls, finishReason, usage, providerMetadata, content, rawFinishReason?, warnings, request, response)`
- `@Poko class CallWarning(type, message?, details?)` — value semantics and
  serialization remain; public `copy()` / `componentN()` ABI is intentionally
  absent for result/metadata value types.
- `@Poko class LanguageModelRequestMetadata(body?)`
- `@Poko class LanguageModelResponseMetadata(id?, timestampMillis?, modelId?, headers, body?)`
- `class ai.torad.aisdk.providers.MockLanguageModel(...)` — for tests only

### Middleware

- `interface LanguageModelMiddleware`
  - `suspend fun wrapGenerate(context: MiddlewareCallContext): LanguageModelResult` — default passes through to `context.doGenerate(context.params)`
  - `fun wrapStream(context: MiddlewareCallContext): Flow<StreamEvent>` — default passes through to `context.doStream(context.params)`
- `@Poko class MiddlewareCallContext(params, model, doGenerate, doStream)` — both `doGenerate` and `doStream` target the rest of the chain past THIS middleware. A middleware's `wrapStream` calling `context.doGenerate(...)` skips its own `wrapGenerate` and invokes the downstream chain's generate path. This is the v6 `{doGenerate, doStream, params, model}` shape — load-bearing for `simulateStreamingMiddleware`, fallback / cache / retry middlewares, and anything that synthesizes one direction from the other.
- `fun wrapLanguageModel(model, middlewares): LanguageModel`
- Built-ins (`ai.torad.aisdk.middleware.*`):
  - `defaultSettingsMiddleware(temperature?, topP?, topK?, maxOutputTokens?, stopSequences, seed?, providerOptions, presencePenalty?, frequencyPenalty?, responseFormat)`
  - `extractReasoningMiddleware(tagName = "reasoning", separator = "\n")`
  - `simulateStreamingMiddleware()` — calls `doGenerate` then synthesizes `TextStart / TextDelta / TextEnd / ToolCall* / StepFinish / Finish`
  - `addToolInputExamplesMiddleware(examplesByTool)`
  - `extractJsonMiddleware()`
  - `LoggingMiddleware(logger: Logger, tag = "agent")` — routes tool-call boundary events to `logger.debug` and errors to `logger.warn` (passing the `@Transient` typed `ToolError.error` as the throwable). Default logging records metadata/byte counts, not raw payloads.
  - `LoggingMiddleware(logger, options: LoggingOptions, tag = "agent")` — opt into redacted or raw input/output logging. `LoggingOptions(recordInputs=false, recordOutputs=false, allowRawValues=false, redactor=AiSdkDefaultRedactor)`.
  - `interface Redactor`, `DefaultRedactor`, `RedactionOptions`, `AiSdkDefaultRedactor` — shared redaction seam for headers, text, and JSON payloads.
- `interface Logger { fun warn(message, throwable? = null); fun info(message); fun debug(message) }` — host-injected log sink. `object NoopLogger` (drop-everything default). Errors are NOT routed here — those ride `StreamEvent.Error` / `OnErrorEvent`.

### Embeddings

- `interface EmbeddingModel { val modelId; val provider; suspend fun embed(params): EmbeddingModelResult }`
- `data class EmbeddingModelCallParams(values, maxEmbeddingsPerCall?, truncate?, providerOptions, abortSignal, headers)`
- `@Poko class EmbeddingModelResult(embeddings, usage, warnings, request, response, providerMetadata)`
- `@Poko class EmbeddingUsage(tokens, raw?)`
- `suspend fun embed(model, value, providerOptions?, abortSignal?, headers?): EmbedResult<String>`
- `suspend fun embedMany(model, values, maxEmbeddingsPerCall?, maxParallelCalls = 8, providerOptions?, abortSignal?, headers?): EmbedManyResult<String>`
- `EmbedResult`, `EmbedManyResult`, and `EmbeddingMiddlewareCallContext` are
  `@Poko class` value-semantics result/context types; field access remains,
  but public `copy()` / `componentN()` ABI is intentionally absent.
- `interface EmbeddingModelMiddleware`; `wrapEmbeddingModel`; `defaultEmbeddingSettingsMiddleware`

### Media And Reranking

- Image: `ImageModel`, `ImageGenerationParams`, `ImageModelResult`, `GenerateImageResult`, `generateImage(..., maxParallelCalls = 8)`
- Speech: `SpeechModel`, `SpeechGenerationParams`, `SpeechModelResult`, `GenerateSpeechResult`, `generateSpeech(...)`
- Transcription: `TranscriptionModel`, `AudioSource`, `TranscriptionParams`, `TranscriptSegment`, `TranscribeResult`, `transcribe(...)`
- Video: `VideoModel`, `VideoGenerationParams`, `VideoModelResult`, `GenerateVideoResult`, `generateVideo(..., maxParallelCalls = 8)`
- Rerank: `RerankingModel`, `RerankingParams`, `RerankedItem<T>`, `RerankResult<T>`, `rerank(...)`
  - Rerank result holders are `@Poko class` value-semantics types; field
    access remains, but public `copy()` / `componentN()` ABI is intentionally
    absent. `RerankingParams` stays on the builder/data-class track.
- Shared file payload: `GeneratedFile(mediaType, base64, filename?, providerMetadata)`, `FileData.Base64`, `FileData.Bytes.toByteArray()` (copy-returning), `FileData.Url`, `DefaultGeneratedFile.fromBase64/fromBytes` (`byteArray` is copy-returning).
- Media result/metadata holders are `@Poko class` value-semantics types;
  field access remains, but public `copy()` / `componentN()` ABI is
  intentionally absent. Construct params remain builder-track data classes.
- Experimental media aliases/functions require `@ExperimentalAiSdkApi`: `Experimental_GeneratedImage`, `Experimental_GenerateImageResult`, `Experimental_SpeechResult`, `Experimental_TranscriptionResult`, `experimental_generateImage`, `experimental_generateSpeech`, `experimental_transcribe`, `experimental_generateVideo`.
- `ImageModelMiddleware`, `ImageMiddlewareCallContext`, `wrapImageModel`

### MCP

- `MCPTransport`, `MCPClientConfig`, `MCPClient`, `CreateMCPClient(config)`.
- `MCPReconnectionOptions(initialReconnectionDelayMillis = 1000, reconnectionDelayGrowFactor = 1.5, maxReconnectionDelayMillis = 30000, maxRetries = 2)` — HTTP inbound SSE reconnect policy; `maxRetries = 0` disables automatic error reconnects.
- `MCPTransportConfig(..., reconnectionOptions = MCPReconnectionOptions())` and `HttpMCPTransport(..., reconnectionOptions = MCPReconnectionOptions())`.
- `MCPClient` resource/tool APIs: `tools`, `toolsFromDefinitions`, `listTools`, `listResources`, `readResource`, `listResourceTemplates`, `onElicitationRequest`, `close`.
- MCP protocol result/capability holders are `@Serializable @Poko class`
  value-semantics types; JSON field names and `_meta` wire names remain
  unchanged, while public `copy()` / `componentN()` ABI is intentionally
  absent. JSON-RPC envelopes and request/params structs stay on their existing
  KEEP/builder tracks.
- OAuth response/server-metadata holders (`OAuthTokens`,
  `AuthorizationServerMetadata`, `OAuthProtectedResourceMetadata`) are
  `@Serializable @Poko class` value-semantics types; JSON field names remain
  unchanged, while public `copy()` / `componentN()` ABI is intentionally
  absent. OAuth client information/metadata structs stay on the builder track.
- Experimental MCP aliases/functions require `@ExperimentalAiSdkApi`: `experimental_MCPClientConfig`, `experimental_MCPClient`, `experimental_MCPClientCapabilities`, `experimental_listPrompts`, `experimental_getPrompt`, `Experimental_CreateMCPClient`, `Experimental_StdioMCPTransport`.

### Provider Registry

- `interface Provider` with `languageModel`, `embeddingModel`, `imageModel`, `speechModel`, `transcriptionModel`, `rerankingModel`, and `videoModel`.
- `customProvider(...)`, `CustomProvider`, `ProviderRegistry`, `createProviderRegistry(...)`, `wrapProvider(...)`, `ProviderMiddleware`.
- Gateway response/spec/metadata holders are `@Poko class` value-semantics
  types; field access remains, but public `copy()` / `componentN()` ABI is
  intentionally absent. Gateway settings and call params stay on the
  builder/data-class track.
- Provider tool-namespace holders such as `OpenAITools`, `AnthropicTools`,
  `GoogleTools`, `XaiTools`, `AzureOpenAITools`, and `GroqTools` are
  `@Poko class` value-semantics types; field access remains, but public
  `copy()` / `componentN()` ABI is intentionally absent.
- Provider error payloads (`BasetenErrorData`, `CerebrasErrorData`,
  `FireworksErrorData`) are `@Serializable @Poko class` value-semantics types;
  JSON field names remain unchanged, while public `copy()` / `componentN()`
  ABI is intentionally absent.
- Errors: `AiSdkException`, `InvalidArgumentError`, `UnsupportedModelVersionError`, `NoSuchProviderError`, `NoSuchModelError`, `NoOutputGeneratedError`, `NoObjectGeneratedError`, `NoImageGeneratedError`, `NoSpeechGeneratedError`, `NoTranscriptGeneratedError`, `NoVideoGeneratedError`, `UiMessageStreamError`.
  Error helper payloads (`TypeValidationContext`, `RetryAttemptDetail`) are
  `@Poko class` value-semantics types; field access remains, but public
  `copy()` / `componentN()` ABI is intentionally absent.

### Telemetry

- `TelemetrySettings`, `TelemetryCall`, `Telemetry`, `registerTelemetry`, `clearGlobalTelemetry`, `globalTelemetry`, `NoopTelemetry`.
- Tracing helpers: `TelemetryTracer`, `TelemetryActiveSpan`, `NoopTelemetryTracer`, `NoopTelemetryActiveSpan`, `InMemoryTelemetryTracer`, `MutableTelemetrySpan(name, initialAttributes: Map<String, JsonElement> = emptyMap())`, `TelemetryTracing.withActiveSpan`, `TelemetryTracing.recordErrorOnSpan`, `TelemetryTracing.selectTelemetryAttributes`.
- `TelemetryCall` and `TelemetrySpanStatus.Error` are `@Poko class`
  value-semantics types; field access remains, but public `copy()` /
  `componentN()` ABI is intentionally absent.

### Provider-Utils Helpers

- `DynamicTool(name, description, inputSchemaJson?, metadata?, executor)`
- `Schema<T>`, `jsonSchema`, `asSchema`, `zodSchema`
- `IdGenerator`, `createIdGenerator`, `generateId`

### General Utilities

- `cosineSimilarity`, `splitArray`, `asArray`, `mergeJsonObjects`, `isDeepEqualData`.
- `DataUrl`, `splitDataUrl`, `detectMediaType`, `prepareHeaders`.
- `RetryPolicy(maxRetries, baseDelayMs, maxDelayMs, clock, delayGenerator, totalTimeoutMs?, perAttemptTimeoutMs?)`, `RetryDelayGenerator`, `RetryAttemptDetail`, `retryWithExponentialBackoff`, `SerialJobExecutor`. Defaults retry only typed retryable `APICallError` / `GatewayError`, honor `Retry-After`, use full jitter, and preserve attempt history in `RetryError.attempts`.
- `mergeAbortSignals`, `abortSignalFromJobs`.

### DevTools

- `DevToolsStep` and `DevToolsStepResult` are `@Poko class`
  value-semantics types; field access remains, but public `copy()` /
  `componentN()` ABI is intentionally absent.

### Streaming helpers

- `sealed interface ChunkBy { Word; Line; Pattern(regex) }`
- `fun smoothStream(upstream, delayMs = 10L, chunkBy = Word): Flow<StreamEvent>`
- `fun simulateReadableStream(events, initialDelayMs = 0L, chunkDelayMs = 10L): Flow<StreamEvent>`

### Top-level inference

- `data class CallSettings(..., maxRetries = 2)` / `data class CallConfig(..., maxRetries = 2)` — non-streaming text/object generation retries typed retryable `APICallError` / `GatewayError` per model round-trip; `maxRetries = 0` disables retries.
- `suspend fun <TOutput> generateText(model, prompt? | messages?, system?, output?, temperature?, ..., abortSignal?, presencePenalty?, frequencyPenalty?, responseFormat?, maxRetries = 2): @Poko GenerateTextResult<TOutput>`
- `fun streamText(model, prompt? | messages?, system?, ..., abortSignal?, output?, presencePenalty?, frequencyPenalty?, responseFormat?): Flow<StreamEvent>`
- `@Deprecated suspend fun <TOutput> generateObject(model, output, prompt? | messages?, ..., maxRetries = 2): @Poko GenerateObjectResult<TOutput>`
- `@Deprecated fun <TOutput> streamObject(model, output, prompt? | messages?, ...): Flow<StreamEvent>`
- Completion helper state: `CompletionState` remains a data class for state
  updates, while `CompletionPhase.Streaming`, `CompletionPhase.Done`, and
  `CompletionPhase.Failed` are `@Poko class` value-semantics leaves.

### Messages

- `@Serializable @Poko class ModelMessage(role, content: List<ContentPart>)`
- Top-level factories: `systemMessage(text)`, `userMessage(text)`, `assistantMessage(text)`, `toolMessage(callId, name, output)`, `toolApprovalResponseMessage(callId, approved, reason?, approvalId?)`
- `enum MessageRole { System, User, Assistant, Tool }`
- `sealed class ContentPart`
  - `@Serializable @Poko class Text(text)`
  - `@Serializable @Poko class Reasoning(text)`
  - `@Serializable @Poko class ToolCall(callId, name, input)`
  - `@Serializable @Poko class ToolResult(callId, name, output, isError = false)`
  - `ToolApprovalRequest(toolCallId, toolName, input, approvalId: String? = null)` — assistant content
  - `@Serializable @Poko class ToolApprovalResponse(toolCallId, approved, reason?, approvalId: String? = null)` — tool content (resume signal)
  - `@Serializable @Poko class Source(sourceType, url?, title?)`
  - `@Serializable @Poko class File(mediaType, base64)`
  - **+ `providerMetadata: Map<String, JsonElement>? = null`** on every variant above EXCEPT `ToolApprovalResponse` (a host decision — no provider produced it). gap #11.
  - `@Serializable @Poko class Image(mediaType, base64)` — multimodal forward-parity (gap #39)
- `@Serializable @Poko class Usage(promptTokens, completionTokens, totalTokens)`
  - `@Serializable @Poko class Usage.InputTokenBreakdown(...)`
  - `@Serializable @Poko class Usage.OutputTokenBreakdown(...)`
  - JSON field names and `ContentPart` discriminators remain unchanged, while
    public `copy()` / `componentN()` ABI is intentionally absent for these
    message/content/usage value types.
- `enum FinishReason { Stop, Length, ToolCalls, ContentFilter, Error, ToolApprovalRequested, Other }`
- `enum ToolChoice { Auto, None, Required, Specific(toolName) }` (sealed)

### Streaming events — v6 block-aware

- `sealed class StreamEvent`
  - Public leaves are `@Poko class` value-semantics types; serialization and
    field access remain, but public `copy()` / `componentN()` ABI is
    intentionally absent.
  - `StreamStart`
  - `StepStart(stepNumber)`
  - `TextStart(id) / TextDelta(id, text) / TextEnd(id)`
  - `ReasoningStart(id) / ReasoningDelta(id, text) / ReasoningEnd(id)`
  - `SourcePart(id, sourceType, url?, title?, mediaType?)`
  - `FilePart(id, mediaType, base64)`
  - `ToolInputStart(id, toolName) / ToolInputDelta(id, delta) / ToolInputEnd(id)`
  - `ToolCall(toolCallId, toolName, inputJson)` — final, parsed
  - `ToolResult(toolCallId, toolName, outputJson, preliminary = false)` — `preliminary = true` for intermediate snapshots from a `StreamingTool` executor; the final emission (and any tool built via the single-value `Tool(...)` factory) carries `preliminary = false` and feeds the model on subsequent turns
  - `ToolError(toolCallId, toolName, message, error: AgentError? = null)` — `error` is a `@Transient` typed `AgentError` (dropped on serialization; `message` stays the wire-stable text) so in-process consumers `when (event.error)` instead of substring-matching. Loop-populated: `NoSuchTool` / `InvalidToolInput` / `ToolCallRepairFailed` / `ToolExecution`.
  - `ToolApprovalRequest(toolCallId, toolName, inputJson, approvalId: String? = null)` — loop ends after this
  - `ToolOutputDenied(toolCallId, toolName, approvalId, reason: String? = null)` — host denied a previously requested approval (distinct from `ToolError` — denial is a CHOICE, not a failure)
  - `StepFinish(stepNumber, finishReason, usage)`
  - `Finish(totalSteps, finishReason, usage)`
  - `Abort`
  - `Error(message)`
  - `Raw(rawValue)` — provider-specific escape hatch
  - **+ `providerMetadata: Map<String, JsonElement>? = null`** on all content + tool-lifecycle variants — everything above EXCEPT `StreamStart`, `Abort`, `Error`, and `Raw` (whose `rawValue` already IS the provider payload). `StepFinish` / `Finish` carried it pre-sweep. gap #11.

### UI types — `ai.torad.aisdk.ui.*`

- Text/UI stream helpers: `TextStreamResponse`, `UIMessageStreamResponse`, `ServerResponseWriter`, `TextStreamFromEvents`, `CreateTextStreamResponse`, `CreateUiMessageStream`, `CreateUiMessageStreamResponse`, `ReadUiMessageStream`, `TransformTextToUiMessageStream`, `UiMessageStreams.pipeTextStreamToResponse`, `UiMessageStreams.pipeUiMessageStreamToResponse`.
  `TextStreamResponse`, `UIMessageStreamResponse`, and
  `SafeValidateUIMessagesResult` leaves are `@Poko class` value-semantics
  types; field access remains, but public `copy()` / `componentN()` ABI is
  intentionally absent.
- Chat: `ChatRequest`, `ChatTransport`, `DirectChatTransport`, `DefaultChatTransport`, `TextStreamChatTransport`, `Chat`.
- Validation/completion helpers: `UiMessageStreams.validateUiMessages`, `UiMessageStreams.validateUIMessages`, `UiMessageStreams.safeValidateUIMessages`, `UiMessageStreams.getResponseUiMessageId`, `UiMessageStreams.handleUiMessageStreamFinish`, `UiMessageStreams.lastAssistantMessageIsCompleteWithToolCalls`, `UiMessageStreams.lastAssistantMessageIsCompleteWithApprovalResponses`.

- `data class UIMessage(id, role, parts: List<UIMessagePart>, createdAtMs?, metadata: Map<String, JsonElement>? = null)` — `metadata` is the monomorphic substitute for v6's `<METADATA, DATA_PARTS, TOOLS>` generics; apps can attach source-agent identity or routing metadata under their own namespaced keys.
- `enum UIMessageRole { System, User, Assistant }`
- `sealed interface UIMessagePart { Text; ToolUI; DynamicToolUI; Reasoning; SourceUrl; SourceDocument; File; Error; Data; StepStart }`
  - Public leaves are `@Poko class` value-semantics types; serialization and
    field access remain, but public `copy()` / `componentN()` ABI is
    intentionally absent.
  - `ToolUI(toolCallId, toolName, state, input?, output?, error?, preliminary = false)` — `preliminary = true` while a `StreamingTool` is still mid-flight (state stays `OutputAvailable`, UI shows "loading more" affordance)
  - `DynamicToolUI(toolCallId, toolName, state, input?, output?, error?, preliminary = false)` — runtime-typed tool variant (subagent prep — parent's static handler registry can't dispatch the subagent's tools)
  - `StepStart(stepNumber)` — multi-step boundary; emitted on `StreamEvent.StepStart` for step 2+ so multi-tool flows / subagent handoffs render a visible divider
  - `SourceUrl(sourceId, url, title?)` / `SourceDocument(sourceId, mediaType, title, filename?)` — split per gap #29
  - `File(mediaType, base64)`
  - **+ `providerMetadata: Map<String, JsonElement>? = null`** on `Text`, `ToolUI`, `DynamicToolUI`, `Reasoning`, `SourceUrl`, `SourceDocument`, `File` (not `Error` / `StepStart` — terminal / boundary). gap #11.
- `enum ToolCallState { InputStreaming, InputAvailable, ApprovalRequested, ApprovalResponded, OutputAvailable, OutputError, OutputDenied }` — v6's full 7-state taxonomy. Renames: `ApprovalRequired → ApprovalRequested`, `Error → OutputError`. New states: `ApprovalResponded` (user answered, tool not yet run), `OutputDenied` (approval was denied).
- `UIMessagePart.ToolUI.outputAs(serializer)` / `inputAs(serializer)` plus reified overloads
- `UIMessagePart.DynamicToolUI.outputAs(serializer)` / `inputAs(serializer)` plus reified overloads
- `fun StreamToUiMessages(events: Flow<StreamEvent>, assistantMessageId): Flow<UIMessage>`
- `ModelMessageConversion.convertToModelMessages(messages: List<UIMessage>, ignoreIncompleteToolCalls: Boolean = false): List<ModelMessage>` — inverse of `StreamToUiMessages`. UI-shape history → model-shape history for replay / crash recovery / subagent continuation. Drops UI-only parts (`StepStart`, `Source`, `File`, `Error`). Tool calls in `OutputAvailable` state split into an `Assistant` message carrying `ToolCall` followed by a `Tool` message carrying `ToolResult`. Preliminary `ToolUI` outputs are dropped (only final emissions feed the model). `InputStreaming` / `InputAvailable` parts are incomplete — silently dropped if `ignoreIncompleteToolCalls`, otherwise throw.
- `class UIToolInvocation<TInput, TOutput>(toolCallId, toolName, state, input?, output?, error?)`; `UIToolInvocationPayload` and `UIToolInvocationMetadata` are `@Poko class` value-semantics holders with field access and no public `copy()` / `componentN()` ABI.
- `class ToolPartHandlerRegistry<TRenderResult>` — typed dispatch per tool name
- `fun buildToolPartHandlerRegistry(fallback) { register(tool, render) ... }`
