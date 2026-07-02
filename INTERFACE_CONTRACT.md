# Interface Contract

> What consumers are entitled to import from `ai.torad.aisdk.*`. Anything not on this list
> is an implementation detail and may change without notice.
>
> Update this file when the public surface changes.

## Module: `aisdk-kotlin` — `ai.torad.aisdk`

### Java Interop

- JVM and Android bytecode expose boxed Java-callable overloads for SDK ID
  value classes via Kotlin's additive `-Xjvm-expose-boxed` mode. Java callers
  can use `ModelId.of("...")`, `ProviderId.of("...")`,
  `ToolCallId.of("...")`, `ToolName.of("...")`, and
  `ApprovalId.of("...")`, and public boxed accessors such as
  `ModelRef.getModelId()` / `ModelRef.copy(ModelId, ProviderId)`.
- Existing Kotlin/JVM mangled bridge methods remain in the bytecode for binary
  compatibility and are not the Java source API. Low-level coroutine bridge
  methods may still expose compiler bridge names; prefer the public boxed
  factories, provider/facade entrypoints, and high-level helpers from Java.
- Headline construction factories expose Java-callable `@JvmOverloads`
  telescoping overloads where Java cannot use Kotlin default parameters:
  `Tool`, `StreamingTool`, `DynamicTool`, `TextGenerator`, `Provider`,
  `GeneratedFile`, `DefaultSettingsMiddleware`, `DevToolsMiddleware`, and
  `ExtractReasoningMiddleware`.
- SDK DSL builders expose public constructors, fluent setter methods, and
  public `build()` methods for Java callers. Kotlin callers can keep using the
  top-level DSL factories; Java callers should use `new XBuilder().field(...).build()`.
- Known limitation: Kotlin cannot box-expose open/interface members
  (`-Xjvm-expose-boxed` errors on them by design). `GatewayProvider` and
  `AnthropicAwsProvider`'s `ModelId`-typed shorthand (`chat(ModelId)`,
  `image(ModelId)`, `video(ModelId)`, etc.) stay mangled when called through
  the interface type. This is not a functional gap: each has a
  never-mangled `String`-typed sibling on the same interface
  (`languageModel(String)`, `imageModel(String)`, `videoModel(String)`,
  etc.) that Java callers should use instead when holding an
  interface-typed reference. Concrete provider classes (e.g.
  `BlackForestLabsProvider`) still expose their own boxed `ModelId`
  overloads directly.

### Agent

- `interface Agent<TContext, TOutput>`
  - `fun generate(prompt?, messages = emptyList(), options?, abortSignal?): Flow<GenerateResult<TOutput>>`
  - `fun stream(prompt?, messages = emptyList(), options?, abortSignal?): Flow<StreamEvent>`
- `abstract class ToolLoopAgent<TContext, TOutput>(settings = AgentSettings<TContext>(), model = settings.model, instructions = settings.instructions, tools = settings.tools, output = settings.output, stopWhen = settings.stopWhen ?: StepCountIs(20)) : Agent<TContext, TOutput>` — extend-only default loop implementation; applications provide a named subclass
  - common subclassing parameters (`model`, `instructions`, `tools`, `output`, `stopWhen`) stay source-level named arguments; advanced construction knobs live in `AgentSettings<TContext>` so future settings can be added without freezing a long subclass `<init>` signature
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
- `interface ToolStreamWriter { suspend fun write(event: StreamEvent); suspend fun writeData(value: JsonElement) }` — v6's `UIMessageStreamWriter` (gap #21). `writeData` remains a low-level raw event escape hatch; named UI data chunks should be emitted as `StreamEvent.Data(name, data, id?, transient)`, which encodes to `data-*` UI-message chunks. `object NoopToolStreamWriter` is the off-loop default. Writes interleave with the tool's own emissions in stream order.
- `ToolPredicateOptions<TContext> { toolCallId(...); messages(...); experimental_context(...) }` — regular builder-backed class passed to `Tool.needsApproval` / `Tool.toModelOutput` (gap #17) so a predicate can decide on conversation history or call identity. The `experimental_context` setter/property requires `@ExperimentalAiSdkApi`; the positional constructor, `copy()`, and `componentN()` are not public.
- Tool lifecycle hooks (gap #18), all optional + loop-invoked, `runHook`-guarded: `onInputStart(streamingId)` on `ToolInputStart`, `onInputDelta(streamingId, delta)` on `ToolInputDelta`, `onInputAvailable(toolCallId, input)` just before the executor runs.
- `ToolExecutionPolicy { maxParallelToolCalls(8); maxToolCallsPerStep(128); progressBufferCapacity(64); toolExecutionTimeout(null) }` — `@Poko` explicit bounded policy for local tool execution inside one step. `ToolLoopAgent.maxParallelToolCalls` remains as shorthand for the policy parallelism cap. The positional constructor, `copy()`, and `componentN()` are not public.
- `typealias ToolCallRepairFunction<TContext> = suspend (failedCall, error, messages, tools) -> ContentPart.ToolCall?` — wired into `ToolLoopAgent` via `AgentSettings.experimental_repairToolCall` (requires `@ExperimentalAiSdkApi`). Called once when a tool call's args fail to decode (model emitted JSON that doesn't match the schema). Return a corrected `ContentPart.ToolCall` (possibly with a different `toolName`) to retry; return null to surface `StreamEvent.ToolError`. Single-attempt — no recursive repair. Targets Gemma 4 E2B's ~5% rate of malformed-args calls.
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
  resumes with `agent.generate(messages = result.messages + ToolApprovalResponseMessage(toolCallId, approved, reason?, approvalId?))`

### Output

- `sealed class Output<T>`
  - `Output.obj<T>(serializer, name?, description?): Output<T>`
  - `Output.array<T>(elementSerializer, name?, description?): Output<List<T>>`
  - `Output.choice(values: Iterable<String> | vararg String, name?, description?): Output<String>`
  - `Output.json(name?, description?): Output<JsonElement>`
- Top-level mirrors:
  - `OutputObj(serializer, name?, description?)`
  - `OutputArray(elementSerializer, name?, description?)`
  - `OutputChoice(values | vararg values, name?, description?)`
  - `OutputJson(name?, description?)`

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
- `AgentSettings<TContext> { instructions(...); model(...); tools(...); activeTools(...); providerOptions(...); temperature(...); topP(...); topK(...); maxOutputTokens(...); stopSequences(...); seed(...); presencePenalty(...); frequencyPenalty(...); responseFormat(...); maxRetries(...) }` — regular builder-backed class with identity equality because it can hold model/tool objects. The positional constructor, `copy()`, and `componentN()` are not public.
- `AgentSettings.experimental_repairToolCall(...)`, `AgentSettings.experimental_toolApprovalSecret(...)`, and matching `ToolLoopAgent` properties require `@ExperimentalAiSdkApi`.
- `class PrepareStepScope<TContext>(stepNumber, model, steps, messages, context)`
- `StepSettings<TContext> { model(...); activeTools(...); toolChoice(...); messages(...); system(...); providerOptions(...); temperature(...); topP(...); topK(...); maxOutputTokens(...); stopSequences(...); seed(...); presencePenalty(...); frequencyPenalty(...); responseFormat(...); maxRetries(...); experimental_context(...) }` — regular builder-backed class with identity equality because it can hold model/tool objects. The `experimental_context` setter/property requires `@ExperimentalAiSdkApi`; the positional constructor, `copy()`, and `componentN()` are not public.

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
- `@Poko class LanguageModelCallParams(messages, tools, toolChoice, temperature?, topP?, topK?, maxOutputTokens?, stopSequences, seed?, providerOptions, abortSignal, presencePenalty?, frequencyPenalty?, responseFormat, headers)` — field access and value equality remain; construct with `LanguageModelCallParams { messages(...); ... }`. Public `copy()` / `componentN()` ABI is intentionally absent; middleware/provider shims use `params.toBuilder().providerOptions(...).build()` for one-field overrides.
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
- `@Poko class MiddlewareCallContext(params, model, doGenerate, doStream)` — both `doGenerate` and `doStream` target the rest of the chain past THIS middleware. A middleware's `wrapStream` calling `context.doGenerate(...)` skips its own `wrapGenerate` and invokes the downstream chain's generate path. This is the v6 `{doGenerate, doStream, params, model}` shape — load-bearing for `SimulateStreamingMiddleware`, fallback / cache / retry middlewares, and anything that synthesizes one direction from the other.
- `fun WrapLanguageModel(model, middlewares): LanguageModel`
- Built-ins (`ai.torad.aisdk.middleware.*`):
  - `DefaultSettingsMiddleware(temperature?, topP?, topK?, maxOutputTokens?, stopSequences, seed?, providerOptions, presencePenalty?, frequencyPenalty?, responseFormat)`
  - `ExtractReasoningMiddleware(tagName = "reasoning", separator = "\n")`
  - `SimulateStreamingMiddleware()` — calls `doGenerate` then synthesizes `TextStart / TextDelta / TextEnd / ToolCall* / StepFinish / Finish`
  - `AddToolInputExamplesMiddleware(examplesByTool)`
  - `ExtractJsonMiddleware()`
  - `LoggingMiddleware(logger: Logger, tag = "agent")` — routes tool-call boundary events to `logger.debug` and errors to `logger.warn` (passing the `@Transient` typed `ToolError.error` as the throwable). Default logging records metadata/byte counts, not raw payloads.
  - `LoggingMiddleware(logger, options: LoggingOptions, tag = "agent")` — opt into redacted or raw input/output logging. `LoggingOptions { recordInputs(true); recordOutputs(true); allowRawValues(false); redactor(AiSdkDefaultRedactor) }` is a regular builder-backed class with identity equality because it holds a redactor object.
  - `interface Redactor`, `DefaultRedactor`, `RedactionOptions`, `AiSdkDefaultRedactor` — shared redaction seam for headers, text, and JSON payloads. `RedactionOptions { replacement("[REDACTED]") }` is an `@Poko` value-semantics class.
- `interface Logger { fun warn(message, throwable? = null); fun info(message); fun debug(message) }` — host-injected log sink. `object NoopLogger` (drop-everything default). Errors are NOT routed here — those ride `StreamEvent.Error` / `OnErrorEvent`.

### Embeddings

- `interface EmbeddingModel { val modelId; val provider; suspend fun embed(params): EmbeddingModelResult }`
- `@Poko class EmbeddingModelCallParams(values, maxEmbeddingsPerCall?, truncate?, providerOptions, abortSignal, headers)` — field access and value equality remain; construct with `EmbeddingModelCallParams { values(...); ... }`. Public `copy()` / `componentN()` ABI is intentionally absent; embedding middleware uses `params.toBuilder().providerOptions(...).build()` for one-field overrides.
- `@Poko class EmbeddingModelResult(embeddings, usage, warnings, request, response, providerMetadata)`
- `@Poko class EmbeddingUsage(tokens, raw?)`
- `suspend fun Embedding.embed(model, value, providerOptions?, abortSignal?, headers?): EmbedResult<String>`
- `suspend fun Embedding.embedMany(model, values, maxEmbeddingsPerCall?, maxParallelCalls = 8, providerOptions?, abortSignal?, headers?): EmbedManyResult<String>`
- `EmbedResult`, `EmbedManyResult`, and `EmbeddingMiddlewareCallContext` are
  `@Poko class` value-semantics result/context types; field access remains,
  but public `copy()` / `componentN()` ABI is intentionally absent.
- `interface EmbeddingModelMiddleware`; `wrapEmbeddingModel`; `defaultEmbeddingSettingsMiddleware`

### Media And Reranking

- Image: `ImageModel`, `ImageGenerationParams`, `ImageModelResult`, `GenerateImageResult`, `ImageGeneration.generateImage(..., maxParallelCalls = 8)`
- Speech: `SpeechModel`, `SpeechGenerationParams`, `SpeechModelResult`, `GenerateSpeechResult`, `SpeechGeneration.generateSpeech(...)`
- Transcription: `TranscriptionModel`, `AudioSource`, `TranscriptionParams`, `TranscriptSegment`, `TranscribeResult`, `Transcription.transcribe(...)`
  - Audio input is currently base64-backed in memory; providers decode the
    base64 payload before upload, so large inputs can briefly require roughly
    twice the audio size in memory. Streaming upload input is future work.
- Video: `VideoModel`, `VideoGenerationParams`, `VideoModelResult`, `GenerateVideoResult`, `VideoGeneration.generateVideo(..., maxParallelCalls = 8)`
- Rerank: `RerankingModel`, `RerankingParams`, `RerankedItem<T>`, `RerankResult<T>`, `Reranking.rerank(...)`
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
- `MCPReconnectionOptions { initialReconnectionDelayMillis(1000); reconnectionDelayGrowFactor(1.5); maxReconnectionDelayMillis(30000); maxRetries(2) }` — `@Poko` HTTP inbound SSE reconnect policy; `maxRetries = 0` disables automatic error reconnects. The positional constructor, `copy()`, and `componentN()` are not public.
- `MCPTransportConfig { reconnectionOptions(MCPReconnectionOptions { ... }) }`, `@InternalAiSdkApi HttpMCPTransport(..., reconnectionOptions = MCPReconnectionOptions { ... })`, `@InternalAiSdkApi SseMCPTransport(...)`, and `StdioConfig { command("..."); args([...]) }`. The concrete HTTP/SSE transports stay public for advanced custom transport work but are internal, unstable SDK surface that requires explicit opt-in. `StdioConfig` remains `@Serializable` and is an `@Poko` value-semantics class with no public positional constructor, `copy()`, or `componentN()`.
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
  absent. OAuth client information/metadata structs are `@Serializable @Poko`
  builder-backed value types.
- Experimental MCP aliases/functions require `@ExperimentalAiSdkApi`: `experimental_MCPClientConfig`, `experimental_MCPClient`, `experimental_MCPClientCapabilities`, `experimental_listPrompts`, `experimental_getPrompt`, `Experimental_CreateMCPClient`, `Experimental_StdioMCPTransport`.
- Experimental agent/tool surfaces require `@ExperimentalAiSdkApi`: `AgentSettings.experimental_repairToolCall`, `AgentSettings.experimental_toolApprovalSecret`, `ToolLoopAgent.experimental_repairToolCall`, `ToolLoopAgent.experimental_toolApprovalSecret`, `StepSettings.experimental_context`, and `ToolPredicateOptions.experimental_context`.

### Provider Registry

- `interface Provider` with `languageModel`, `embeddingModel`, `imageModel`, `speechModel`, `transcriptionModel`, `rerankingModel`, and `videoModel`.
- `Provider(providerId, languageModels, embeddingModels, imageModels, speechModels, transcriptionModels, rerankingModels, videoModels, fallbackProvider)`, `CustomProvider { providerId(...); languageModel(id, model); embeddingModel(id, model); imageModel(id, model); speechModel(id, model); transcriptionModel(id, model); rerankingModel(id, model); videoModel(id, model); fallbackProvider(...) }`, `ProviderRegistry`, `ProviderRegistry.createProviderRegistry(...)`, `WrapProvider(...)`, `ProviderMiddleware { languageModelMiddlewares(...); embeddingModelMiddlewares(...); imageModelMiddlewares(...) }`.
- `CustomProvider` is a regular builder-backed class with identity equality
  because it holds model objects; the positional constructor, `copy()`, and
  `componentN()` are not public.
- Gateway response/spec/metadata holders are `@Poko class` value-semantics
  types; field access remains, but public `copy()` / `componentN()` ABI is
  intentionally absent. Gateway settings and call params stay on the
  builder/data-class track.
- Provider tool-namespace holders such as `OpenAITools`, `AnthropicTools`,
  `GoogleTools`, `XaiTools`, `AzureOpenAITools`, and `GroqTools` are
  `@Poko class` value-semantics types; field access remains, but public
  `copy()` / `componentN()` ABI is intentionally absent.
- Provider settings on the construct-type builder track
  (`CohereProviderSettings`, `DeepgramProviderSettings`,
  `AssemblyAIProviderSettings`, `GladiaProviderSettings`,
  `RevaiProviderSettings`, `ElevenLabsProviderSettings`,
  `HumeProviderSettings`, `LMNTProviderSettings`, `LumaProviderSettings`,
  `FalProviderSettings`, `ReplicateProviderSettings`,
  `KlingAIProviderSettings`, `BlackForestLabsProviderSettings`,
  `ProdiaProviderSettings`, `ByteDanceProviderSettings`,
  `MistralProviderSettings`, `AlibabaProviderSettings`,
  `GroqProviderSettings`, `CerebrasProviderSettings`,
  `DeepInfraProviderSettings`, `DeepSeekProviderSettings`,
  `FireworksProviderSettings`, `TogetherAIProviderSettings`,
  `PerplexityProviderSettings`, `MoonshotAIProviderSettings`,
  `XaiProviderSettings`, `VoyageProviderSettings`,
  `QuiverAIProviderSettings`, `BasetenProviderSettings`,
  `VercelProviderSettings`, `OpenAIProviderSettings`,
  `AzureOpenAIProviderSettings`, `GoogleGenerativeAIProviderSettings`,
  `OpenAICompatibleProviderSettings`, `OpenResponsesProviderSettings`,
  `GatewayProviderSettings`, `AmazonBedrockProviderSettings`,
  `AnthropicAwsProviderSettings`, `GoogleVertexProviderSettings`,
  `HuggingFaceProviderSettings`, and `AnthropicProviderSettings`) expose field
  getters and are configured through public DSL factories and builder setter
  methods such as `CohereProviderSettings { apiKey("..."); baseURL("...") }`.
  Pure data-only settings are `@Poko class` value-semantics types. Settings
  that hold functions or transport objects are regular classes with identity
  equality. The positional constructors, `copy()`, and `componentN()` are not
  public.
- Provider model option construct types on the builder track
  (`CohereLanguageModelOptions`, `CohereThinkingOptions`,
  `CohereEmbeddingModelOptions`, `CohereRerankingModelOptions`,
  `VoyageEmbeddingModelOptions`, `VoyageRerankingModelOptions`,
  `BasetenEmbeddingModelOptions`, `TogetherAIRerankingModelOptions`,
  `AlibabaEmbeddingModelOptions`, `AlibabaLanguageModelOptions`,
  `AlibabaVideoModelOptions`, `AssemblyAITranscriptionModelOptions`,
  `BlackForestLabsImageModelOptions`, `ByteDanceVideoProviderOptions`,
  `DeepgramSpeechModelOptions`, `DeepgramTranscriptionModelOptions`,
  `DeepSeekLanguageModelOptions`, `ElevenLabsSpeechModelOptions`,
  `ElevenLabsTranscriptionModelOptions`, `FalImageModelOptions`,
  `FalSpeechModelOptions`, `FalTranscriptionModelOptions`,
  `FalVideoModelOptions`, `FireworksEmbeddingModelOptions`,
  `FireworksThinkingOptions`, `FireworksLanguageModelOptions`,
  `GladiaTranscriptionModelOptions`, `GroqLanguageModelOptions`,
  `GroqTranscriptionModelOptions`, `HumeSpeechModelOptions`,
  `KlingAIVideoModelOptions`, `LumaImageModelOptions`,
  `LMNTSpeechModelOptions`, `RevaiTranscriptionModelOptions`,
  `ReplicateImageModelOptions`, `ReplicateVideoModelOptions`,
  `ProdiaImageModelOptions`, `ProdiaVideoModelOptions`,
  `QuiverAIImageModelOptions`, `TogetherAIImageModelOptions`,
  `MistralLanguageModelOptions`, `MoonshotAILanguageModelOptions`,
  `ProdiaLanguageModelOptions`, `XaiImageModelOptions`, and
  `XaiVideoModelOptions`) expose field getters and are configured through
  public DSL factories and builder setter methods such as
  `CohereEmbeddingModelOptions { inputType("search") }`,
  `DeepgramTranscriptionModelOptions { language("en") }`,
  `FalVideoModelOptions { resolution("720p") }`,
  `KlingAIVideoModelOptions { mode("std") }`,
  `ReplicateImageModelOptions { output_format("webp") }`, or
  `XaiImageModelOptions { aspect_ratio("1:1") }`. These pure data options are
  `@Serializable @Poko class` value-semantics types; their positional
  constructors, `copy()`, and `componentN()` are not public.
- TypeScript-residue aliases that only renamed shared JSON/usage types are not
  public ABI: `AlibabaUsage`, `AlibabaCacheControl`, and `DeepSeekErrorData`.
- Simple construct params on the builder track (`GatewaySpendReportParams`,
  `GatewayGenerationInfoParams`, `AuthOptions`, `ImageGenerationParams`,
  `SpeechGenerationParams`, `TranscriptionParams`, `VideoGenerationParams`,
  `RerankingParams`, `CompletionRequestOptions`, `CallCompletionApiOptions`,
  `HuggingFaceResponsesSettings`, `TextGenerationRequest`,
  `CompletionRequest`, `StructuredObjectRequest`, `ChatRequest`,
  `TelemetrySettings`, `MCPClientConfig`, `MCPTransportConfig`,
  `MCPRequestOptions`, `UseCompletionOptions`, `StructuredObjectOptions`,
  `LoggingOptions`, `RedactionOptions`, `MCPReconnectionOptions`,
  `StdioConfig`, `ToolSchemaOptions`, `ProviderToolFactoryOptions`,
  `ToolPredicateOptions`, `BedrockCredentials`, `AssemblyAICustomSpelling`,
  `OpenResponsesOptions`, `OpenResponsesAllowedTools`,
  `XaiLanguageModelChatOptions`, `XaiLanguageModelResponsesOptions`,
  `LiteRTSamplerConfig`, `LiteRTChannel`, `LiteRTToolCall`, `LiteRTMessage`,
  `LiteRTConversationRequest`, and `LiteRTLanguageModelSettings`,
  `OAuthClientInformation`, `OAuthClientMetadata`, `Configuration`,
  `ElicitationCapability`,
  `ProviderMiddleware`, `RetryPolicy`, `ToolExecutionPolicy`, `CallSettings`,
  `CallConfig`, `AgentSettings`, and `StepSettings`) expose field
  getters and are configured through public DSL factories. The gateway params,
  `TextGenerationRequest`,
  `ChatRequest`, `CompletionRequestOptions`, `HuggingFaceResponsesSettings`,
  `RedactionOptions`, `MCPReconnectionOptions`, `StdioConfig`,
  `ToolSchemaOptions`, `BedrockCredentials`, `AssemblyAICustomSpelling`,
  `OpenResponsesOptions`, `OpenResponsesAllowedTools`,
  `XaiLanguageModelChatOptions`, `XaiLanguageModelResponsesOptions`,
  `LiteRTSamplerConfig`, `LiteRTChannel`, `LiteRTToolCall`,
  `LiteRTMessage`, and the six `LiteRTContent` leaf types,
  `OAuthClientInformation`, `OAuthClientMetadata`, `Configuration`,
  `ElicitationCapability`, `ToolExecutionPolicy`, `CallSettings`, and
  `CallConfig` are `@Poko` value-semantics types.
  `AuthOptions`,
  media/rerank params, `CompletionRequest`, `StructuredObjectRequest`,
  `TelemetrySettings`, `MCPClientConfig`, `MCPTransportConfig`,
  `MCPRequestOptions`, `CallCompletionApiOptions`, `UseCompletionOptions`,
  `StructuredObjectOptions`, `LoggingOptions`, `ProviderToolFactoryOptions`,
  `ToolPredicateOptions`, `LiteRTConversationRequest`, and
  `LiteRTLanguageModelSettings`, `ProviderMiddleware`, `RetryPolicy`,
  `AgentSettings`, and `StepSettings` are regular classes with identity
  equality because they may hold clients, abort signals, transports, callbacks,
  coroutine contexts, telemetry integrations, serializers, functions, tool
  definitions, middleware instances, retry delay generators, arbitrary context
  values, model input objects, language models, or tool sets. The positional
  constructors, `copy()`, and `componentN()` are not public.
- LiteRT on-device bridge types use typed JSON context at the public boundary:
  `LiteRTConversationRequest.extraContext`, `LiteRTLanguageModelSettings.extraContext`,
  and `LiteRTConversation.send/stream(..., extraContext = ...)` are
  `Map<String, JsonElement>`. `LiteRTSamplerConfig {}` returns
  `LiteRTSamplerConfig.Default`; `LiteRTConversation.cancel()` and `close()` are
  documented no-op defaults that abortable/resource-owning engines must
  override. `LiteRTContent.ToolResponse` correlates by tool name only and does
  not carry a tool-call id.
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
- `IdGenerator { prefix(...); size(...); alphabet(...); separator(...); random(...) }`, `IdGenerator.generate(prefix?, random?)`, and instance `generate()`. `IdGenerator` is a regular builder-backed class with identity equality because it holds a `Random`; the positional constructor, `copy()`, and `componentN()` are not public.

### General Utilities

- `cosineSimilarity`, `splitArray`, `asArray`, `mergeJsonObjects`, `isDeepEqualData`.
- `DataUrl`, `splitDataUrl`, `detectMediaType`, `prepareHeaders`. `DataUrl` remains public because data URL parsing is documented general utility surface and `DataUrl.parse(...)` returns the consumer-facing value.
- `RetryPolicy { maxRetries(2); baseDelayMs(100); maxDelayMs(2000); clock(Clock.System); delayGenerator(...); totalTimeoutMs(null); perAttemptTimeoutMs(null) }`, `RetryDelayGenerator`, `RetryAttemptDetail`, `retryWithExponentialBackoff`, `SerialJobExecutor`. Defaults retry only typed retryable `APICallError` / `GatewayError`, honor `Retry-After`, use full jitter, and preserve attempt history in `RetryError.attempts`. `RetryPolicy` is a regular builder-backed class because delay generators may be stateful; the positional constructor, `copy()`, and `componentN()` are not public.
- `mergeAbortSignals`, `abortSignalFromJobs`.

### DevTools

- `DevToolsStep` and `DevToolsStepResult` are `@Poko class`
  value-semantics types; field access remains, but public `copy()` /
  `componentN()` ABI is intentionally absent.

### Streaming helpers

- `sealed interface ChunkBy { Word; Line; Pattern(regex) }`
- `fun smoothStream(upstream, delayMs = 10L, chunkBy = Word): Flow<StreamEvent>`
- `fun SimulateReadableStream(chunks, delayMillis = 0L): Flow<T>`

### Structured generation entrypoints

- `CallSettings { ...; headers(mapOf("X-Trace" to "...")); timeout(30.seconds); maxRetries(2) }` / `CallConfig { ...; headers(mapOf("X-Trace" to "...")); timeout(30.seconds); maxRetries(2) }` — `@Poko` builder-backed value types used by high-level generation APIs. Non-streaming text/object generation retries typed retryable `APICallError` / `GatewayError` per model round-trip; per-call `headers` flow into `LanguageModelCallParams.headers`; `timeout` is a total high-level call deadline that cancels a non-streaming call or the full streaming collection with `CallTimeoutError` (it is not an idle-gap timeout between stream events); `maxRetries = 0` disables retries. The positional constructors, `copy()`, and `componentN()` are not public.
- `class TextGenerator(model, config = CallConfig())`
  - `fun generate(input: GenerationInput): Flow<GenerateTextResult<String>>`
  - `fun <T> generate(input: GenerationInput, output: Output<T>): Flow<GenerateTextResult<T>>`
  - `fun stream(input: GenerationInput): Flow<StreamEvent>`
  - `fun streamResult(input: GenerationInput): StreamTextResult` — terminal stream runs are memoized for replay. If all collectors leave before terminal completion, the upstream producer is cancelled and a later collector starts a fresh run. Upstream collection uses the first collector's coroutine context; `warnings` and `response` flows drain `fullStream` to terminal completion before emitting metadata.
- `fun <TOutput> StreamObjectResult(model, output, prompt?, messages = emptyList(), system?, temperature?, topP?, topK?, maxOutputTokens?, stopSequences, seed?, providerOptions, abortSignal, presencePenalty?, frequencyPenalty?, responseFormat?, maxRetries = 2): StreamObjectResult<TOutput>` — routes object/text/element accessors through the same memoized stream lifecycle as `StreamTextResult`.
- `@Poko class GenerateObjectResult<TOutput>` remains the structured-output result holder; there are no loose top-level object-generation shims.
- Completion helper state: `CompletionState` remains a data class for state
  updates, while `CompletionPhase.Streaming`, `CompletionPhase.Done`, and
  `CompletionPhase.Failed` are `@Poko class` value-semantics leaves.

### Messages

- `@Serializable @Poko class ModelMessage(role, content: List<ContentPart>)`
- Top-level factories: `SystemMessage(text)`, `UserMessage(text)`, `AssistantMessage(text)`, `ToolMessage(callId, name, output)`, `ToolApprovalResponseMessage(callId, approved, reason?, approvalId?)`
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
  - `@Serializable @Poko class Raw(rawValue)` — forward-compatible gateway/provider content escape hatch for unknown content-part types; gateway non-stream responses preserve these instead of dropping them.
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
  - `Data(name, data, id?, transient = false)` — custom UI data part; encodes to `{ type: "data-$name", id?, data, transient? }` for JS UI-message clients
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
  - `Data(type, data, id?, transient = false)` — typed custom `data-*` UI part; `StreamEvent.Data(name, ...)` is the encoder-side source for these chunks.
  - **+ `providerMetadata: Map<String, JsonElement>? = null`** on `Text`, `ToolUI`, `DynamicToolUI`, `Reasoning`, `SourceUrl`, `SourceDocument`, `File` (not `Error` / `StepStart` — terminal / boundary). gap #11.
- `enum ToolCallState { InputStreaming, InputAvailable, ApprovalRequested, ApprovalResponded, OutputAvailable, OutputError, OutputDenied }` — v6's full 7-state taxonomy. Renames: `ApprovalRequired → ApprovalRequested`, `Error → OutputError`. New states: `ApprovalResponded` (user answered, tool not yet run), `OutputDenied` (approval was denied).
- `UIMessagePart.ToolUI.outputAs(serializer)` / `inputAs(serializer)` plus reified overloads
- `UIMessagePart.DynamicToolUI.outputAs(serializer)` / `inputAs(serializer)` plus reified overloads
- `fun StreamToUiMessages(events: Flow<StreamEvent>, assistantMessageId): Flow<UIMessage>`
- `ModelMessageConversion.convertToModelMessages(messages: List<UIMessage>, ignoreIncompleteToolCalls: Boolean = false): List<ModelMessage>` — inverse of `StreamToUiMessages`. UI-shape history → model-shape history for replay / crash recovery / subagent continuation. Drops UI-only parts (`StepStart`, `Source`, `File`, `Error`). Tool calls in `OutputAvailable` state split into an `Assistant` message carrying `ToolCall` followed by a `Tool` message carrying `ToolResult`. Preliminary `ToolUI` outputs are dropped (only final emissions feed the model). `InputStreaming` / `InputAvailable` parts are incomplete — silently dropped if `ignoreIncompleteToolCalls`, otherwise throw.
- `class UIToolInvocation<TInput, TOutput>(toolCallId, toolName, state, input?, output?, error?)`; `UIToolInvocationPayload` and `UIToolInvocationMetadata` are `@Poko class` value-semantics holders with field access and no public `copy()` / `componentN()` ABI.
- `class ToolPartHandlerRegistry<TRenderResult>` — typed dispatch per tool name
- `fun buildToolPartHandlerRegistry(fallback) { register(tool, render) ... }`
