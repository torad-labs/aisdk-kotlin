package ai.torad.aisdk

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer

/**
 * A tool the LLM can call. There are two idiomatic ways to define one:
 *
 * **1. Extend it** — for a reusable, dependency-injected tool that deserves a
 * name (mirrors how a concrete agent extends [ToolLoopAgent]):
 * ```
 * class SearchDocsTool(private val repo: DocRepository) :
 *     Tool<SearchInput, List<SearchResult>, AppContext>(
 *         name = "searchDocs",
 *         description = "Search the product documentation",
 *         inputSerializer = serializer(),
 *         outputSerializer = serializer(),
 *     ) {
 *     override suspend fun ToolExecutionContext<AppContext>.execute(input: SearchInput) =
 *         repo.search(input.query)
 * }
 * // usage: toolSetOf(SearchDocsTool(repo))  — one class, many instances
 * ```
 * Override only the hooks you need ([needsApproval], [toModelOutput],
 * [onInputStart], [onInputDelta], [onInputAvailable]); they no-op by default.
 * For a tool that emits preliminary snapshots before its final value, extend
 * [StreamingTool] and override [StreamingTool.executeStream] instead.
 *
 * **2. Use the [tool] / [streamingTool] factories** — for trivial inline tools
 * where a named class is overkill. The factories build an anonymous subclass.
 *
 * Per invariant I-2, tools use `inputSchema` (v6) — never `parameters`
 * (v5, removed). Per I-8 tools are stateless: inputs in, outputs out, no
 * shared mutable state — so a single instance is safely reused across agents.
 *
 * @param TInput  Application-defined input type, must be `@Serializable`.
 *                The model produces JSON matching its schema; the agent
 *                deserializes via [inputSerializer] before calling [execute].
 * @param TOutput Application-defined output type, must be `@Serializable`.
 *                Serialized into the model's tool-result message via
 *                [outputSerializer], or summarized via [toModelOutput] if
 *                the raw output would be too verbose for the model.
 * @param TContext Caller-supplied typed context propagated through the
 *                whole loop (set via `callOptionsSchema`, accessed inside
 *                [execute] as `this.context`).
 *
 * Internally the agent loop always drives a [Flow] (the v6 alignment per
 * `tool.ts:68-71`'s `AsyncIterable<OUTPUT>` shape): a plain [Tool] wraps its
 * single [execute] value in a one-emission flow; a [StreamingTool] passes its
 * Flow straight through. The loop collects with one-step lookahead — non-final
 * emissions become `StreamEvent.ToolResult(preliminary = true)`, the last
 * emission is the final result that lands in the model's message log.
 */
@Suppress("LongParameterList")
public abstract class Tool<TInput, TOutput, TContext>(
    public val name: String,
    public val description: String,
    public val inputSerializer: KSerializer<TInput>,
    public val outputSerializer: KSerializer<TOutput>,
    /**
     * Per best practice #9: strict JSON schema enforcement is opt-in per
     * tool, not global. Some providers reject schemas with format/regex
     * constraints under strict mode; mark such tools `strict = false`.
     */
    public val strict: Boolean = true,
    /**
     * Few-shot example inputs encoded as JSON strings. Mirrors v6's
     * `tool.inputExamples` (per historical parity gap #19). Disproportionate
     * quality lift on small models — Gemma 4 E2B's tool-call accuracy
     * notably improves with 2-3 in-tool examples. [ToolSet.descriptors]
     * inlines these into the description as `Example: <json>` so every
     * provider sees them (most don't expose a separate examples field).
     */
    public val inputExamples: List<String> = emptyList(),
    /**
     * Application-defined metadata bag. Mirrors v6's `tool.metadata`
     * (per historical parity gap #34). Opaque to the loop —
     * consumers (logger middleware, telemetry, host-side gating) can
     * read it via [ToolSet.byName]. Examples: feature-flag key,
     * billing tier, owning team. Empty by default.
     */
    public val metadata: Map<String, JsonElement> = emptyMap(),
    public val providerExecuted: Boolean = false,
    /**
     * Provider-specific config for this tool, sent to the model on the wire
     * (e.g. Anthropic `cache_control`). Distinct from [metadata], which is
     * host-side only. Mirrors v6's `tool.providerOptions`. Empty by default.
     */
    public val providerOptions: Map<String, JsonElement> = emptyMap(),
) {
    /**
     * Run the tool — the common single-value case. The model's JSON input has
     * already been deserialized into [TInput]; return the typed output. `this`
     * is the [ToolExecutionContext], so the typed `context`, `abortSignal`,
     * `toolCallId`, `stepNumber`, `messages`, and `writer` are in scope.
     *
     * Override [StreamingTool.executeStream] instead to emit preliminary
     * progress snapshots before the final value.
     */
    public abstract suspend fun ToolExecutionContext<TContext>.execute(input: TInput): TOutput

    /**
     * Approval gate (parity gap #17). Return true to require host approval
     * before [execute] runs: the loop ends with the call surfaced in
     * `GenerateResult.pendingApprovals` until the host resumes. The options
     * carry `toolCallId` + `messages` + `experimental_context`, so the gate can
     * decide from conversation history or call identity. Default: never gates.
     */
    public open suspend fun needsApproval(
        input: TInput,
        options: ToolPredicateOptions<TContext>,
    ): Boolean = false

    /**
     * Model-visible summary (parity gaps #17 + #14). Return a structured
     * [ToolResultOutput] (Text / Json / Error / …) to send the model a short
     * summary in place of the full (possibly verbose) output; the loop bridges
     * it to `ContentPart.ToolResult.modelVisible` (+ `isError`). Return null
     * (default) to send the raw output as-is.
     */
    public open fun toModelOutput(
        output: TOutput,
        options: ToolPredicateOptions<TContext>,
    ): ToolResultOutput? = null

    /**
     * Lifecycle hook (gap #18) — fired when the model commits to calling this
     * tool (`StreamEvent.ToolInputStart`). Pre-warm here: UI spinner, cache
     * priming, etc. No-op by default.
     */
    public open suspend fun onInputStart(streamingId: String) {}

    /**
     * Lifecycle hook (gap #18) — fired as the input JSON streams in
     * token-by-token (`StreamEvent.ToolInputDelta`). The accumulated input
     * isn't yet valid JSON; this is for raw-character pre-warm. No-op by default.
     */
    public open suspend fun onInputDelta(streamingId: String, delta: String) {}

    /**
     * Lifecycle hook — fired after the streamed JSON parses into the typed
     * [TInput], just before [execute] runs. Mirrors v6's `tool.onInputAvailable`.
     * No-op by default.
     */
    public open suspend fun onInputAvailable(toolCallId: String, input: TInput) {}

    /**
     * Canonical executor the agent loop drives: the full emission stream (last
     * value = final result, earlier values = preliminary). A plain [Tool] wraps
     * [execute] in one emission; [StreamingTool] passes its Flow through.
     */
    internal open fun streamExecutor(scope: ToolExecutionContext<TContext>, input: TInput): Flow<TOutput> {
        val tool = this
        return flow { emit(with(tool) { scope.execute(input) }) }
    }
}

/**
 * A [Tool] whose executor is a [Flow] — it can emit preliminary snapshots
 * before the final value. The LAST emission is what feeds the model on
 * subsequent turns; earlier emissions surface as
 * `StreamEvent.ToolResult(preliminary = true)` for UI consumption only. Extend
 * this (instead of [Tool]) when a tool can produce a useful early snapshot —
 * a small summary, count, or status — before the full result is ready.
 *
 * ```
 * class LineupTool(private val repo: LineupRepo) :
 *     StreamingTool<LineupQuery, Lineup, AppContext>(
 *         name = "getLineup",
 *         description = "Get sets playing at a stage on a given day",
 *         inputSerializer = serializer(),
 *         outputSerializer = serializer(),
 *     ) {
 *     override fun ToolExecutionContext<AppContext>.executeStream(input: LineupQuery) = flow {
 *         emit(repo.fastSummary(input))   // preliminary — UI renders immediately
 *         emit(repo.fullDetails(input))   // final — feeds the model
 *     }
 * }
 * ```
 *
 * If the Flow emits zero values, the tool is treated as failed (the agent
 * emits `StreamEvent.ToolError`).
 */
@Suppress("LongParameterList")
public abstract class StreamingTool<TInput, TOutput, TContext>(
    name: String,
    description: String,
    inputSerializer: KSerializer<TInput>,
    outputSerializer: KSerializer<TOutput>,
    strict: Boolean = true,
    inputExamples: List<String> = emptyList(),
    metadata: Map<String, JsonElement> = emptyMap(),
    providerExecuted: Boolean = false,
    providerOptions: Map<String, JsonElement> = emptyMap(),
) : Tool<TInput, TOutput, TContext>(
    name = name,
    description = description,
    inputSerializer = inputSerializer,
    outputSerializer = outputSerializer,
    strict = strict,
    inputExamples = inputExamples,
    metadata = metadata,
    providerExecuted = providerExecuted,
    providerOptions = providerOptions,
) {
    /**
     * Run the tool, emitting preliminary snapshots; the LAST emission is the
     * final result. `this` is the [ToolExecutionContext].
     */
    public abstract fun ToolExecutionContext<TContext>.executeStream(input: TInput): Flow<TOutput>

    // A StreamingTool produces values via executeStream(); the single-value
    // execute() seam is sealed off and never reached (streamExecutor is final).
    final override suspend fun ToolExecutionContext<TContext>.execute(input: TInput): TOutput =
        error(
            "StreamingTool '$name' produces values via executeStream(), not execute(). " +
                "To drive it directly, use executeTool(tool, input, context).",
        )

    internal final override fun streamExecutor(
        scope: ToolExecutionContext<TContext>,
        input: TInput,
    ): Flow<TOutput> {
        val tool = this
        return with(tool) { scope.executeStream(input) }
    }
}

/**
 * Internal [Tool] backing the [tool] factory: holds the executor and the
 * optional callbacks as fields, so the factory (and the lifecycle hooks'
 * absent-callback handling) lives in ONE place instead of being re-spelled at
 * every factory call site. Not public — consumers extend [Tool] directly.
 */
@Suppress("LongParameterList")
internal class LambdaTool<TInput, TOutput, TContext>(
    name: String,
    description: String,
    inputSerializer: KSerializer<TInput>,
    outputSerializer: KSerializer<TOutput>,
    strict: Boolean,
    inputExamples: List<String>,
    metadata: Map<String, JsonElement>,
    providerExecuted: Boolean,
    providerOptions: Map<String, JsonElement>,
    private val executeFn: suspend ToolExecutionContext<TContext>.(TInput) -> TOutput,
    private val approvalFn: (suspend (TInput, ToolPredicateOptions<TContext>) -> Boolean)?,
    private val modelOutputFn: ((TOutput, ToolPredicateOptions<TContext>) -> ToolResultOutput)?,
    private val inputStartFn: (suspend (String) -> Unit)?,
    private val inputDeltaFn: (suspend (String, String) -> Unit)?,
    private val inputAvailableFn: (suspend (String, TInput) -> Unit)?,
) : Tool<TInput, TOutput, TContext>(
    name, description, inputSerializer, outputSerializer,
    strict, inputExamples, metadata, providerExecuted, providerOptions,
) {
    override suspend fun ToolExecutionContext<TContext>.execute(input: TInput): TOutput = executeFn(input)
    override suspend fun needsApproval(input: TInput, options: ToolPredicateOptions<TContext>): Boolean =
        approvalFn?.invoke(input, options) ?: false
    override fun toModelOutput(output: TOutput, options: ToolPredicateOptions<TContext>): ToolResultOutput? =
        modelOutputFn?.invoke(output, options)
    override suspend fun onInputStart(streamingId: String) {
        inputStartFn?.invoke(streamingId)
    }

    override suspend fun onInputDelta(streamingId: String, delta: String) {
        inputDeltaFn?.invoke(streamingId, delta)
    }

    override suspend fun onInputAvailable(toolCallId: String, input: TInput) {
        inputAvailableFn?.invoke(toolCallId, input)
    }
}

/**
 * Internal [StreamingTool] backing the [streamingTool] factory and the
 * provider-tool factories — same role as [LambdaTool] for the Flow-returning
 * executor. Not public — consumers extend [StreamingTool] directly.
 */
@Suppress("LongParameterList")
internal class LambdaStreamingTool<TInput, TOutput, TContext>(
    name: String,
    description: String,
    inputSerializer: KSerializer<TInput>,
    outputSerializer: KSerializer<TOutput>,
    strict: Boolean,
    inputExamples: List<String>,
    metadata: Map<String, JsonElement>,
    providerExecuted: Boolean,
    providerOptions: Map<String, JsonElement>,
    private val streamFn: ToolExecutionContext<TContext>.(TInput) -> Flow<TOutput>,
    private val approvalFn: (suspend (TInput, ToolPredicateOptions<TContext>) -> Boolean)?,
    private val modelOutputFn: ((TOutput, ToolPredicateOptions<TContext>) -> ToolResultOutput)?,
    private val inputStartFn: (suspend (String) -> Unit)?,
    private val inputDeltaFn: (suspend (String, String) -> Unit)?,
    private val inputAvailableFn: (suspend (String, TInput) -> Unit)?,
) : StreamingTool<TInput, TOutput, TContext>(
    name, description, inputSerializer, outputSerializer,
    strict, inputExamples, metadata, providerExecuted, providerOptions,
) {
    override fun ToolExecutionContext<TContext>.executeStream(input: TInput): Flow<TOutput> = streamFn(input)
    override suspend fun needsApproval(input: TInput, options: ToolPredicateOptions<TContext>): Boolean =
        approvalFn?.invoke(input, options) ?: false
    override fun toModelOutput(output: TOutput, options: ToolPredicateOptions<TContext>): ToolResultOutput? =
        modelOutputFn?.invoke(output, options)
    override suspend fun onInputStart(streamingId: String) {
        inputStartFn?.invoke(streamingId)
    }

    override suspend fun onInputDelta(streamingId: String, delta: String) {
        inputDeltaFn?.invoke(streamingId, delta)
    }

    override suspend fun onInputAvailable(toolCallId: String, input: TInput) {
        inputAvailableFn?.invoke(toolCallId, input)
    }
}

/**
 * Top-level factory for [Tool] with a single-value executor. Mirrors v6's
 * `tool({...})` ergonomics in an idiomatic common Kotlin shape.
 *
 * Idiomatic use:
 * ```
 * val searchDocsTool = tool<SearchInput, List<SearchResult>, AppContext>(
 *     name = "searchDocs",
 *     description = "Search the product documentation",
 *     inputSerializer = serializer(),
 *     outputSerializer = serializer(),
 * ) { query ->
 *     repository.search(query.query)
 * }
 * ```
 *
 * The user-supplied [executor] runs once per tool call. Use
 * [streamingTool] when the tool wants to emit preliminary progress
 * snapshots before the final value.
 */
@Suppress("LongParameterList")
public fun <TInput, TOutput, TContext> tool(
    name: String,
    description: String,
    inputSerializer: KSerializer<TInput>,
    outputSerializer: KSerializer<TOutput>,
    needsApproval: (suspend (input: TInput, options: ToolPredicateOptions<TContext>) -> Boolean)? = null,
    toModelOutput: ((TOutput, ToolPredicateOptions<TContext>) -> ToolResultOutput)? = null,
    strict: Boolean = true,
    inputExamples: List<String> = emptyList(),
    onInputStart: (suspend (streamingId: String) -> Unit)? = null,
    onInputDelta: (suspend (streamingId: String, delta: String) -> Unit)? = null,
    onInputAvailable: (suspend (toolCallId: String, input: TInput) -> Unit)? = null,
    metadata: Map<String, JsonElement> = emptyMap(),
    providerExecuted: Boolean = false,
    providerOptions: Map<String, JsonElement> = emptyMap(),
    executor: suspend ToolExecutionContext<TContext>.(TInput) -> TOutput,
): Tool<TInput, TOutput, TContext> {
    return LambdaTool(
        name = name,
        description = description,
        inputSerializer = inputSerializer,
        outputSerializer = outputSerializer,
        strict = strict,
        inputExamples = inputExamples,
        metadata = metadata,
        providerExecuted = providerExecuted,
        providerOptions = providerOptions,
        executeFn = executor,
        approvalFn = needsApproval,
        modelOutputFn = toModelOutput,
        inputStartFn = onInputStart,
        inputDeltaFn = onInputDelta,
        inputAvailableFn = onInputAvailable,
    )
}

/**
 * Top-level factory for [Tool] with a [Flow]-returning executor. Each
 * emission lands as a `StreamEvent.ToolResult`; the LAST emission is
 * the final tool result that feeds the model on subsequent turns, and
 * all earlier emissions are surfaced as `ToolResult(preliminary = true)`
 * for UI consumption only.
 *
 * Use this when a tool can produce a useful early snapshot (small
 * summary, count, status) before the full result is ready — UI renders
 * the partial card immediately and upgrades it as more data arrives.
 *
 * Idiomatic use:
 * ```
 * val getLineupTool = streamingTool<LineupQuery, Lineup, AppContext>(
 *     name = "getLineup",
 *     description = "Get sets playing at a stage on a given day",
 *     inputSerializer = serializer(),
 *     outputSerializer = serializer(),
 * ) { query ->
 *     flow {
 *         emit(lineupRepo.fastSummary(query))          // preliminary
 *         emit(lineupRepo.fullDetails(query))          // final — goes to model
 *     }
 * }
 * ```
 *
 * If the Flow emits zero values, the tool is treated as failed
 * (the agent emits `StreamEvent.ToolError`).
 */
@Suppress("LongParameterList")
public fun <TInput, TOutput, TContext> streamingTool(
    name: String,
    description: String,
    inputSerializer: KSerializer<TInput>,
    outputSerializer: KSerializer<TOutput>,
    needsApproval: (suspend (input: TInput, options: ToolPredicateOptions<TContext>) -> Boolean)? = null,
    toModelOutput: ((TOutput, ToolPredicateOptions<TContext>) -> ToolResultOutput)? = null,
    strict: Boolean = true,
    inputExamples: List<String> = emptyList(),
    onInputStart: (suspend (streamingId: String) -> Unit)? = null,
    onInputDelta: (suspend (streamingId: String, delta: String) -> Unit)? = null,
    onInputAvailable: (suspend (toolCallId: String, input: TInput) -> Unit)? = null,
    metadata: Map<String, JsonElement> = emptyMap(),
    providerExecuted: Boolean = false,
    executor: ToolExecutionContext<TContext>.(TInput) -> Flow<TOutput>,
): Tool<TInput, TOutput, TContext> {
    return LambdaStreamingTool(
        name = name,
        description = description,
        inputSerializer = inputSerializer,
        outputSerializer = outputSerializer,
        strict = strict,
        inputExamples = inputExamples,
        metadata = metadata,
        providerExecuted = providerExecuted,
        providerOptions = emptyMap(),
        streamFn = executor,
        approvalFn = needsApproval,
        modelOutputFn = toModelOutput,
        inputStartFn = onInputStart,
        inputDeltaFn = onInputDelta,
        inputAvailableFn = onInputAvailable,
    )
}

/**
 * Erased map of tools indexed by name — what the agent loop actually
 * carries. Application code constructs via [toolSetOf].
 *
 * Erasure (using `*` projections) is required because a single agent
 * holds tools with different `TInput`/`TOutput` types. Type recovery
 * happens at the dispatch site via the tool's own serializers.
 */
public class ToolSet<TContext>(
    public val byName: Map<String, Tool<*, *, TContext>>,
) {
    public val descriptors: List<LanguageModelTool> by lazy {
        byName.values.map { tool ->
            LanguageModelTool(
                name = tool.name,
                description = descriptionWithExamples(tool),
                parametersSchemaJson = jsonSchemaFor(tool),
                strict = tool.strict,
                providerExecuted = tool.providerExecuted,
                metadata = tool.metadata,
                providerOptions = tool.providerOptions,
            )
        }
    }

    public fun find(toolName: String): Tool<*, *, TContext>? = byName[toolName]

    /** Registered tool names — for [AgentError.NoSuchTool] diagnostics. */
    public fun names(): List<String> = byName.keys.toList()

    public operator fun plus(other: ToolSet<TContext>): ToolSet<TContext> {
        val overlap = byName.keys intersect other.byName.keys
        require(overlap.isEmpty()) {
            "Duplicate tool name(s) when combining tool sets: ${overlap.joinToString()}."
        }
        return ToolSet(byName + other.byName)
    }
}

/** Inline `tool.inputExamples` into the description so every provider
 *  sees the examples — most providers don't expose a separate
 *  examples field. Mirrors what `addToolInputExamplesMiddleware`
 *  does, but sourced from the tool's own field. */
private fun descriptionWithExamples(tool: Tool<*, *, *>): String {
    val examples = tool.inputExamples
    if (examples.isEmpty()) return tool.description
    val appendix = examples.joinToString(separator = "\n") { "Example: $it" }
    return "${tool.description}\n\n$appendix"
}

/**
 * Build the name→tool map, rejecting duplicates. A silent last-wins
 * `associateBy` drops a tool the caller registered; every tool-set
 * construction path funnels through here so the policy is uniform (H-8).
 */
internal fun <TContext> requireUniqueToolNames(
    tools: List<Tool<*, *, TContext>>,
): Map<String, Tool<*, *, TContext>> {
    val byName = linkedMapOf<String, Tool<*, *, TContext>>()
    for (tool in tools) {
        require(tool.name !in byName) { "Duplicate tool name `${tool.name}`." }
        byName[tool.name] = tool
    }
    return byName
}

/** Construct a [ToolSet] from individual tools. Throws on duplicate names. */
public fun <TContext> toolSetOf(vararg tools: Tool<*, *, TContext>): ToolSet<TContext> =
    ToolSet(requireUniqueToolNames(tools.toList()))

@AiSdkDsl
public class ToolSetBuilder<TContext> internal constructor() {
    private val tools = linkedMapOf<String, Tool<*, *, TContext>>()

    public fun add(tool: Tool<*, *, TContext>) {
        require(tool.name !in tools) { "Duplicate tool name `${tool.name}`." }
        tools[tool.name] = tool
    }

    @Suppress("LongParameterList")
    public inline fun <reified TInput, reified TOutput> tool(
        name: String,
        description: String,
        noinline needsApproval: (suspend (input: TInput, options: ToolPredicateOptions<TContext>) -> Boolean)? = null,
        noinline toModelOutput: ((TOutput, ToolPredicateOptions<TContext>) -> ToolResultOutput)? = null,
        strict: Boolean = true,
        inputExamples: List<String> = emptyList(),
        noinline onInputStart: (suspend (streamingId: String) -> Unit)? = null,
        noinline onInputDelta: (suspend (streamingId: String, delta: String) -> Unit)? = null,
        noinline onInputAvailable: (suspend (toolCallId: String, input: TInput) -> Unit)? = null,
        metadata: Map<String, JsonElement> = emptyMap(),
        providerExecuted: Boolean = false,
        noinline executor: suspend ToolExecutionContext<TContext>.(TInput) -> TOutput,
    ): Tool<TInput, TOutput, TContext> =
        ai.torad.aisdk.tool<TInput, TOutput, TContext>(
            name = name,
            description = description,
            inputSerializer = serializer<TInput>(),
            outputSerializer = serializer<TOutput>(),
            needsApproval = needsApproval,
            toModelOutput = toModelOutput,
            strict = strict,
            inputExamples = inputExamples,
            onInputStart = onInputStart,
            onInputDelta = onInputDelta,
            onInputAvailable = onInputAvailable,
            metadata = metadata,
            providerExecuted = providerExecuted,
            executor = executor,
        ).also(::add)

    @Suppress("LongParameterList")
    public inline fun <reified TInput, reified TOutput> streamingTool(
        name: String,
        description: String,
        noinline needsApproval: (suspend (input: TInput, options: ToolPredicateOptions<TContext>) -> Boolean)? = null,
        noinline toModelOutput: ((TOutput, ToolPredicateOptions<TContext>) -> ToolResultOutput)? = null,
        strict: Boolean = true,
        inputExamples: List<String> = emptyList(),
        noinline onInputStart: (suspend (streamingId: String) -> Unit)? = null,
        noinline onInputDelta: (suspend (streamingId: String, delta: String) -> Unit)? = null,
        noinline onInputAvailable: (suspend (toolCallId: String, input: TInput) -> Unit)? = null,
        metadata: Map<String, JsonElement> = emptyMap(),
        providerExecuted: Boolean = false,
        noinline executor: ToolExecutionContext<TContext>.(TInput) -> Flow<TOutput>,
    ): Tool<TInput, TOutput, TContext> =
        ai.torad.aisdk.streamingTool<TInput, TOutput, TContext>(
            name = name,
            description = description,
            inputSerializer = serializer<TInput>(),
            outputSerializer = serializer<TOutput>(),
            needsApproval = needsApproval,
            toModelOutput = toModelOutput,
            strict = strict,
            inputExamples = inputExamples,
            onInputStart = onInputStart,
            onInputDelta = onInputDelta,
            onInputAvailable = onInputAvailable,
            metadata = metadata,
            providerExecuted = providerExecuted,
            executor = executor,
        ).also(::add)

    internal fun build(): ToolSet<TContext> = ToolSet(tools.toMap())
}

public fun <TContext> toolSet(block: ToolSetBuilder<TContext>.() -> Unit): ToolSet<TContext> =
    ToolSetBuilder<TContext>().apply(block).build()

@Suppress("LongParameterList")
public inline fun <reified TInput, reified TOutput, TContext> tool(
    name: String,
    description: String,
    noinline needsApproval: (suspend (input: TInput, options: ToolPredicateOptions<TContext>) -> Boolean)? = null,
    noinline toModelOutput: ((TOutput, ToolPredicateOptions<TContext>) -> ToolResultOutput)? = null,
    strict: Boolean = true,
    inputExamples: List<String> = emptyList(),
    noinline onInputStart: (suspend (streamingId: String) -> Unit)? = null,
    noinline onInputDelta: (suspend (streamingId: String, delta: String) -> Unit)? = null,
    noinline onInputAvailable: (suspend (toolCallId: String, input: TInput) -> Unit)? = null,
    metadata: Map<String, JsonElement> = emptyMap(),
    providerExecuted: Boolean = false,
    noinline executor: suspend ToolExecutionContext<TContext>.(TInput) -> TOutput,
): Tool<TInput, TOutput, TContext> = tool(
    name = name,
    description = description,
    inputSerializer = serializer<TInput>(),
    outputSerializer = serializer<TOutput>(),
    needsApproval = needsApproval,
    toModelOutput = toModelOutput,
    strict = strict,
    inputExamples = inputExamples,
    onInputStart = onInputStart,
    onInputDelta = onInputDelta,
    onInputAvailable = onInputAvailable,
    metadata = metadata,
    providerExecuted = providerExecuted,
    executor = executor,
)

@Suppress("LongParameterList")
public inline fun <reified TInput, reified TOutput, TContext> streamingTool(
    name: String,
    description: String,
    noinline needsApproval: (suspend (input: TInput, options: ToolPredicateOptions<TContext>) -> Boolean)? = null,
    noinline toModelOutput: ((TOutput, ToolPredicateOptions<TContext>) -> ToolResultOutput)? = null,
    strict: Boolean = true,
    inputExamples: List<String> = emptyList(),
    noinline onInputStart: (suspend (streamingId: String) -> Unit)? = null,
    noinline onInputDelta: (suspend (streamingId: String, delta: String) -> Unit)? = null,
    noinline onInputAvailable: (suspend (toolCallId: String, input: TInput) -> Unit)? = null,
    metadata: Map<String, JsonElement> = emptyMap(),
    providerExecuted: Boolean = false,
    noinline executor: ToolExecutionContext<TContext>.(TInput) -> Flow<TOutput>,
): Tool<TInput, TOutput, TContext> = streamingTool(
    name = name,
    description = description,
    inputSerializer = serializer<TInput>(),
    outputSerializer = serializer<TOutput>(),
    needsApproval = needsApproval,
    toModelOutput = toModelOutput,
    strict = strict,
    inputExamples = inputExamples,
    onInputStart = onInputStart,
    onInputDelta = onInputDelta,
    onInputAvailable = onInputAvailable,
    metadata = metadata,
    providerExecuted = providerExecuted,
    executor = executor,
)

/**
 * Runtime-typed tool equivalent to v6's `dynamicTool`. Inputs and
 * outputs are raw [JsonElement] values, so the tool can be registered
 * from external manifests or MCP-style runtime catalogs.
 */
public fun <TContext> dynamicTool(
    name: String,
    description: String,
    inputSchemaJson: String = "{}",
    metadata: Map<String, JsonElement> = emptyMap(),
    toModelOutput: ((JsonElement, ToolPredicateOptions<TContext>) -> ToolResultOutput)? = null,
    executor: suspend ToolExecutionContext<TContext>.(JsonElement) -> JsonElement,
): Tool<JsonElement, JsonElement, TContext> = tool(
    name = name,
    description = description,
    inputSerializer = JsonElement.serializer(),
    outputSerializer = JsonElement.serializer(),
    metadata = metadata + (
        "inputSchema" to try {
            Json.parseToJsonElement(inputSchemaJson)
        } catch (e: kotlinx.serialization.SerializationException) {
            throw InvalidArgumentError(
                "inputSchemaJson",
                "dynamicTool `$name`: inputSchemaJson is not valid JSON — ${e.message}",
                e,
            )
        }
        ),
    toModelOutput = toModelOutput,
    executor = executor,
)

public fun <TInput, TOutput, TContext> providerExecutedTool(
    name: String,
    description: String,
    inputSerializer: KSerializer<TInput>,
    outputSerializer: KSerializer<TOutput>,
    metadata: Map<String, JsonElement> = emptyMap(),
): Tool<TInput, TOutput, TContext> = streamingTool(
    name = name,
    description = description,
    inputSerializer = inputSerializer,
    outputSerializer = outputSerializer,
    metadata = metadata,
    providerExecuted = true,
) {
    flow {
        throw AgentError.ToolExecution(
            name,
            toolCallId,
            UnsupportedOperationException("provider-executed tool has no local executor")
        )
    }
}

public data class Schema<T>(
    val jsonSchema: JsonElement,
    val validate: ((JsonElement) -> T)? = null,
)

public class LazySchema<T>(
    private val createSchema: () -> Schema<T>,
) {
    private var cached: Schema<T>? = null

    public operator fun invoke(): Schema<T> =
        cached ?: createSchema().also { cached = it }
}

public fun <T> lazySchema(createSchema: () -> Schema<T>): LazySchema<T> =
    LazySchema(createSchema)

public fun <T> jsonSchema(
    schema: JsonElement,
    validate: ((JsonElement) -> T)? = null,
): Schema<T> = Schema(schema, validate)

public fun <T> asSchema(schema: Schema<T>?): Schema<T> =
    schema ?: jsonSchema(
        buildJsonObject {
            put("properties", JsonObject(emptyMap()))
            put("additionalProperties", JsonPrimitive(false))
        }
    )

public fun <T> asSchema(schema: LazySchema<T>): Schema<T> = schema()

public sealed interface ValidationResult<out T> {
    public data class Success<T>(
        val value: T,
        val rawValue: JsonElement,
    ) : ValidationResult<T>

    public data class Failure(
        val error: TypeValidationError,
        val rawValue: JsonElement,
    ) : ValidationResult<Nothing>
}

public fun <T> safeValidateTypes(
    value: JsonElement,
    schema: Schema<T>,
    context: TypeValidationContext? = null,
): ValidationResult<T> =
    try {
        val validated = schema.validate?.invoke(value) ?: schemaFallbackValue(value, schema.jsonSchema)
        @Suppress("UNCHECKED_CAST")
        ValidationResult.Success(validated as T, value)
    } catch (error: Throwable) {
        ValidationResult.Failure(TypeValidationError.wrap(value, error, context), value)
    }

private fun schemaFallbackValue(value: JsonElement, schema: JsonElement): Any? {
    val schemaType = (schema as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull
    return when (schemaType) {
        null -> value
        "object" -> value as? JsonObject ?: throw IllegalArgumentException("Expected JSON object.")
        "array" -> value as? JsonArray ?: throw IllegalArgumentException("Expected JSON array.")
        "string" -> {
            val primitive = value as? JsonPrimitive ?: throw IllegalArgumentException("Expected JSON string.")
            if (!primitive.isJsonString()) throw IllegalArgumentException("Expected JSON string.")
            primitive.content
        }
        "integer" -> value.jsonPrimitive.intOrNull ?: throw IllegalArgumentException("Expected JSON integer.")
        "number" -> value.jsonPrimitive.doubleOrNull ?: throw IllegalArgumentException("Expected JSON number.")
        "boolean" -> value.jsonPrimitive.booleanOrNull ?: throw IllegalArgumentException("Expected JSON boolean.")
        else -> value
    }
}

private fun JsonPrimitive.isJsonString(): Boolean =
    toString().startsWith("\"")

public fun <T> safeValidateTypes(
    value: JsonElement,
    schema: LazySchema<T>,
    context: TypeValidationContext? = null,
): ValidationResult<T> = safeValidateTypes(value, schema(), context)

public fun <T> validateTypes(
    value: JsonElement,
    schema: Schema<T>,
    context: TypeValidationContext? = null,
): T = when (val result = safeValidateTypes(value, schema, context)) {
    is ValidationResult.Success -> result.value
    is ValidationResult.Failure -> throw result.error
}

public fun <T> validateTypes(
    value: JsonElement,
    schema: LazySchema<T>,
    context: TypeValidationContext? = null,
): T = validateTypes(value, schema(), context)

public fun <T> parseProviderOptions(
    provider: String,
    providerOptions: Map<String, JsonElement>,
    schema: Schema<T>,
): T? {
    val value = providerOptions[provider] ?: return null
    return when (val result = safeValidateTypes(value, schema)) {
        is ValidationResult.Success -> result.value
        is ValidationResult.Failure -> throw InvalidArgumentError(
            "providerOptions",
            "invalid $provider provider options",
            result.error,
        )
    }
}

public sealed interface ExecuteToolResult<out TOutput> {
    public data class Preliminary<TOutput>(val output: TOutput) : ExecuteToolResult<TOutput>
    public data class Final<TOutput>(val output: TOutput) : ExecuteToolResult<TOutput>
}

public fun <TInput, TOutput, TContext> executeTool(
    tool: Tool<TInput, TOutput, TContext>,
    input: TInput,
    options: ToolExecutionContext<TContext>,
): Flow<ExecuteToolResult<TOutput>> = flow {
    val outputs = mutableListOf<TOutput>()
    tool.streamExecutor(options, input).collect { output ->
        outputs += output
    }
    if (outputs.isEmpty()) {
        throw AgentError.ToolExecution(
            tool.name,
            options.toolCallId,
            NoOutputGeneratedError("Tool ${tool.name} produced no output")
        )
    }
    outputs.dropLast(1).forEach { emit(ExecuteToolResult.Preliminary(it)) }
    emit(ExecuteToolResult.Final(outputs.last()))
}

public data class ToolNameMapping(
    private val customToolNameToProviderToolName: Map<String, String>,
    private val providerToolNameToCustomToolName: Map<String, String>,
) {
    public fun toProviderToolName(customToolName: String): String =
        customToolNameToProviderToolName[customToolName] ?: customToolName

    public fun toCustomToolName(providerToolName: String): String =
        providerToolNameToCustomToolName[providerToolName] ?: providerToolName
}

public fun createToolNameMapping(
    tools: List<LanguageModelTool> = emptyList(),
    providerToolNames: Map<String, String>,
    resolveProviderToolName: ((LanguageModelTool) -> String?)? = null,
): ToolNameMapping {
    val customToProvider = linkedMapOf<String, String>()
    val providerToCustom = linkedMapOf<String, String>()
    for (tool in tools) {
        val providerToolId = tool.metadata["providerToolId"] as? JsonPrimitive ?: continue
        val providerName = resolveProviderToolName?.invoke(tool) ?: providerToolNames[providerToolId.contentOrNull] ?: continue
        customToProvider[tool.name] = providerName
        providerToCustom[providerName] = tool.name
    }
    return ToolNameMapping(customToProvider, providerToCustom)
}

public data class ProviderToolFactoryOptions<TInput, TOutput, TContext>(
    val outputSerializer: KSerializer<TOutput>,
    val outputSchema: Schema<TOutput>? = null,
    val args: Map<String, JsonElement> = emptyMap(),
    val name: String? = null,
    val description: String? = null,
    val metadata: Map<String, JsonElement> = emptyMap(),
    val execute: (suspend ToolExecutionContext<TContext>.(TInput) -> TOutput)? = null,
    val needsApproval: (suspend (input: TInput, options: ToolPredicateOptions<TContext>) -> Boolean)? = null,
    val toModelOutput: ((TOutput, ToolPredicateOptions<TContext>) -> ToolResultOutput)? = null,
    val onInputStart: (suspend (streamingId: String) -> Unit)? = null,
    val onInputDelta: (suspend (streamingId: String, delta: String) -> Unit)? = null,
    val onInputAvailable: (suspend (toolCallId: String, input: TInput) -> Unit)? = null,
)

public class ProviderToolFactory<TInput, TContext>(
    private val id: String,
    private val inputSerializer: KSerializer<TInput>,
    private val inputSchema: Schema<TInput>,
    private val defaultDescription: String = "Provider tool $id",
) {
    public operator fun <TOutput> invoke(
        options: ProviderToolFactoryOptions<TInput, TOutput, TContext>,
    ): Tool<TInput, TOutput, TContext> = create(options)

    public fun <TOutput> create(
        options: ProviderToolFactoryOptions<TInput, TOutput, TContext>,
    ): Tool<TInput, TOutput, TContext> = providerTool(
        id = id,
        inputSerializer = inputSerializer,
        inputSchema = inputSchema,
        defaultDescription = defaultDescription,
        supportsDeferredResults = false,
        options = options,
    )
}

public class ProviderToolFactoryWithOutputSchema<TInput, TOutput, TContext>(
    private val id: String,
    private val inputSerializer: KSerializer<TInput>,
    private val inputSchema: Schema<TInput>,
    private val outputSerializer: KSerializer<TOutput>,
    private val outputSchema: Schema<TOutput>,
    private val supportsDeferredResults: Boolean = false,
    private val defaultDescription: String = "Provider tool $id",
) {
    public operator fun invoke(
        options: ProviderToolFactoryOptions<TInput, TOutput, TContext> =
            ProviderToolFactoryOptions(outputSerializer = outputSerializer, outputSchema = outputSchema),
    ): Tool<TInput, TOutput, TContext> = create(options)

    public fun create(
        options: ProviderToolFactoryOptions<TInput, TOutput, TContext> =
            ProviderToolFactoryOptions(outputSerializer = outputSerializer, outputSchema = outputSchema),
    ): Tool<TInput, TOutput, TContext> = providerTool(
        id = id,
        inputSerializer = inputSerializer,
        inputSchema = inputSchema,
        defaultDescription = defaultDescription,
        supportsDeferredResults = supportsDeferredResults,
        options = options.copy(outputSerializer = outputSerializer, outputSchema = outputSchema),
    )
}

public fun <TInput, TContext> createProviderToolFactory(
    id: String,
    inputSerializer: KSerializer<TInput>,
    inputSchema: Schema<TInput>,
    description: String = "Provider tool $id",
): ProviderToolFactory<TInput, TContext> =
    ProviderToolFactory(id, inputSerializer, inputSchema, description)

public fun <TInput, TOutput, TContext> createProviderToolFactoryWithOutputSchema(
    id: String,
    inputSerializer: KSerializer<TInput>,
    inputSchema: Schema<TInput>,
    outputSerializer: KSerializer<TOutput>,
    outputSchema: Schema<TOutput>,
    supportsDeferredResults: Boolean = false,
    description: String = "Provider tool $id",
): ProviderToolFactoryWithOutputSchema<TInput, TOutput, TContext> =
    ProviderToolFactoryWithOutputSchema(
        id = id,
        inputSerializer = inputSerializer,
        inputSchema = inputSchema,
        outputSerializer = outputSerializer,
        outputSchema = outputSchema,
        supportsDeferredResults = supportsDeferredResults,
        defaultDescription = description,
    )

private fun <TInput, TOutput, TContext> providerTool(
    id: String,
    inputSerializer: KSerializer<TInput>,
    inputSchema: Schema<TInput>,
    defaultDescription: String,
    supportsDeferredResults: Boolean,
    options: ProviderToolFactoryOptions<TInput, TOutput, TContext>,
): Tool<TInput, TOutput, TContext> {
    val execute = options.execute
    val resolvedName = options.name ?: id.substringAfter('.')
    return LambdaStreamingTool(
        name = resolvedName,
        description = options.description ?: defaultDescription,
        inputSerializer = inputSerializer,
        outputSerializer = options.outputSerializer,
        strict = true,
        inputExamples = emptyList(),
        metadata = options.metadata + providerToolMetadata(
            id = id,
            args = options.args,
            inputSchema = inputSchema,
            outputSchema = options.outputSchema,
            supportsDeferredResults = supportsDeferredResults,
        ),
        providerExecuted = true,
        providerOptions = emptyMap(),
        streamFn = { input ->
            val context = this
            flow {
                if (execute == null) {
                    throw AgentError.ToolExecution(
                        resolvedName,
                        context.toolCallId,
                        UnsupportedOperationException("provider-executed tool has no local executor"),
                    )
                }
                emit(execute.invoke(context, input))
            }
        },
        approvalFn = options.needsApproval,
        modelOutputFn = options.toModelOutput,
        inputStartFn = options.onInputStart,
        inputDeltaFn = options.onInputDelta,
        inputAvailableFn = options.onInputAvailable,
    )
}

private fun providerToolMetadata(
    id: String,
    args: Map<String, JsonElement>,
    inputSchema: Schema<*>,
    outputSchema: Schema<*>?,
    supportsDeferredResults: Boolean,
): Map<String, JsonElement> = buildMap {
    put("providerToolId", JsonPrimitive(id))
    put("args", JsonObject(args))
    put("inputSchema", inputSchema.jsonSchema)
    outputSchema?.let { put("outputSchema", it.jsonSchema) }
    if (supportsDeferredResults) put("supportsDeferredResults", JsonPrimitive(true))
}

/** Whether the LLM should call a tool, no tool, a specific tool, etc. */
@kotlinx.serialization.Serializable
public sealed interface ToolChoice {
    @kotlinx.serialization.Serializable public data object Auto : ToolChoice

    @kotlinx.serialization.Serializable public data object None : ToolChoice

    @kotlinx.serialization.Serializable public data object Required : ToolChoice

    @kotlinx.serialization.Serializable public data class Specific(val toolName: String) : ToolChoice
}

/**
 * Lets a tool's executor write custom events into the agent's **active
 * output stream** — the port's equivalent of v6's `UIMessageStreamWriter`
 * (per historical parity gap #21). Distinct from the `streamingTool`
 * mechanism (which surfaces progress as `ToolResult(preliminary = true)`):
 * the writer pushes ARBITRARY [StreamEvent]s — e.g. a transient status
 * line ([StreamEvent.TextDelta]) or a custom data payload
 * ([StreamEvent.Raw] via [writeData]) — that don't have to be
 * tool-result-shaped. Writes interleave with the tool's own emissions in
 * stream order. `streamToUiMessages` ignores `Raw` by default, so a
 * consumer that wants custom data intercepts the `Flow<StreamEvent>`
 * before UI conversion.
 */
public interface ToolStreamWriter {
    /** Emit any event into the agent's output stream. */
    public suspend fun write(event: StreamEvent)

    /** Convenience: emit [value] as a [StreamEvent.Raw] custom-data chunk. */
    public suspend fun writeData(value: JsonElement)
}

/**
 * No-op [ToolStreamWriter] — the default on a [ToolExecutionContext]
 * constructed outside the loop (test fixtures, the approval-resume path
 * before the loop wires a real writer). Drops every write.
 */
public data object NoopToolStreamWriter : ToolStreamWriter {
    override suspend fun write(event: StreamEvent): Unit = Unit
    override suspend fun writeData(value: JsonElement): Unit = Unit
}

/**
 * What [Tool.execute] sees as `this`. Holds the typed application context
 * (whatever the agent's `callOptionsSchema` says) plus the abort signal
 * (invariant I-10: subagent tool handlers MUST forward this), the current
 * step number, and the running message list.
 */
public class ToolExecutionContext<TContext>(
    public val context: TContext?,
    public val abortSignal: AbortSignal,
    public val stepNumber: Int,
    public val messages: List<ModelMessage>,
    /**
     * The current tool call's id. Mirrors v6's
     * `ToolExecutionOptions.toolCallId`. Lets a tool self-identify for tracing, telemetry, or
     * streaming-progress writes (paired with [writer]).
     */
    public val toolCallId: String,
    /**
     * Writes custom events into the agent's active output stream
     * (gap #21). The loop supplies a real writer bound to the output
     * flow; defaults to [NoopToolStreamWriter] when a context is
     * constructed outside the loop.
     */
    public val writer: ToolStreamWriter = NoopToolStreamWriter,
)

/**
 * Options bag passed to predicate callbacks ([Tool.needsApproval],
 * [Tool.toModelOutput]). Mirrors v6's `{toolCallId, messages,
 * experimental_context}` argument shape (per historical parity gap #17). Lets
 * a predicate decide based on the conversation history or the current call's
 * identity, not just the input.
 *
 * Distinct from [ToolExecutionContext] — predicate options are
 * READ-ONLY metadata about the call, no abort signal because
 * predicates run synchronously before / after the executor.
 */
public data class ToolPredicateOptions<TContext>(
    val toolCallId: String,
    val messages: List<ModelMessage>,
    val experimental_context: TContext? = null,
)

/**
 * Structured return type for [Tool.toModelOutput]. Replaces the
 * flat `String` return per historical parity gap #14. Lets a tool
 * supply EITHER a short plain-text summary OR a structured JSON
 * payload OR a typed error to the model — the agent's loop bridges
 * each into the wire-format `ContentPart.ToolResult.modelVisible`
 * appropriately:
 *
 * - [Text] → `JsonPrimitive(text)`, `isError = false`.
 * - [Json] → the JsonElement as-is, `isError = false`.
 * - [Content] → a list of model-visible content parts, `isError = content.isError`.
 * - [Error] → `JsonPrimitive("Error: $message")`, `isError = true`.
 */
@Serializable
public sealed class ToolResultOutput {
    @Serializable
    public data class Text(val text: String) : ToolResultOutput()

    @Serializable
    public data class Json(val json: JsonElement) : ToolResultOutput()

    @Serializable
    public data class Error(val message: String) : ToolResultOutput()

    @Serializable
    public data class ErrorJson(val json: JsonElement) : ToolResultOutput()

    @Serializable
    public data class ExecutionDenied(val reason: String? = null) : ToolResultOutput()

    @Serializable
    public data class Content(
        val value: List<JsonElement>,
        val isError: Boolean = false,
    ) : ToolResultOutput()
}

internal fun toolResultOutputFromJson(json: JsonElement): ToolResultOutput =
    if (json is JsonPrimitive && json.isString) {
        // contentOrNull is null only when the primitive is JSON null (JsonNull),
        // which cannot reach this branch because isString is false for JsonNull.
        // Use content (non-null) to preserve the actual string value exactly,
        // including an explicit empty string, instead of silently coercing null→"".
        ToolResultOutput.Text(json.content)
    } else {
        ToolResultOutput.Json(json)
    }

internal fun toolResultOutputFromWire(json: JsonElement): ToolResultOutput {
    val obj = json as? JsonObject ?: return toolResultOutputFromJson(json)
    val type = WireDecoder.optionalString(obj, "type", "tool", "tool-result output")
        ?: return toolResultOutputFromJson(json)
    return when (type) {
        "text" -> ToolResultOutput.Text(
            WireDecoder.requiredString(obj, "value", "tool", "tool-result output"),
        )
        "json" -> ToolResultOutput.Json(
            WireDecoder.required(obj, "value", "tool", "tool-result output"),
        )
        "error-text" -> ToolResultOutput.Error(
            WireDecoder.requiredString(obj, "value", "tool", "tool-result output"),
        )
        "error-json" -> ToolResultOutput.ErrorJson(
            WireDecoder.required(obj, "value", "tool", "tool-result output"),
        )
        "execution-denied" -> ToolResultOutput.ExecutionDenied(
            WireDecoder.optionalString(obj, "reason", "tool", "tool-result output"),
        )
        "content" -> ToolResultOutput.Content(
            value = WireDecoder.requiredArray(obj, "value", "tool", "tool-result output").toList(),
            isError = WireDecoder.optionalBoolean(obj, "isError", "tool", "tool-result output") ?: false,
        )
        else -> toolResultOutputFromJson(json)
    }
}

public fun ToolResultOutput.isToolResultError(): Boolean = when (this) {
    is ToolResultOutput.Error,
    is ToolResultOutput.ErrorJson,
    is ToolResultOutput.ExecutionDenied -> true
    is ToolResultOutput.Content -> isError
    is ToolResultOutput.Text,
    is ToolResultOutput.Json -> false
}

public fun ToolResultOutput.toJsonElement(): JsonElement = when (this) {
    is ToolResultOutput.Text -> JsonPrimitive(text)
    is ToolResultOutput.Json -> json
    is ToolResultOutput.Error -> JsonPrimitive("Error: $message")
    is ToolResultOutput.ErrorJson -> json
    is ToolResultOutput.ExecutionDenied -> JsonPrimitive(reason ?: "Tool execution denied")
    is ToolResultOutput.Content -> buildJsonObject {
        put("type", JsonPrimitive("content"))
        put("value", JsonArray(value))
        if (isError) {
            put("isError", JsonPrimitive(true))
        }
    }
}

/**
 * Callback invoked when a tool call's JSON arguments fail to decode
 * (the model emitted JSON that doesn't match the tool's input schema,
 * or referenced an unknown tool). Return a corrected
 * [ContentPart.ToolCall] to retry execution, or null to give up (the
 * loop emits [StreamEvent.ToolError] for this call).
 *
 * Mirrors v6's `experimental_repairToolCall`
 * (`tool-call-repair-function.ts:20-27`). On-device targets like
 * Gemma 4 E2B hallucinate args at ~5%; at that rate a self-healing
 * pass is mandatory for reliability.
 *
 * The repair function MAY reroute to a different tool by returning a
 * `ContentPart.ToolCall` with a different `toolName` — the agent
 * re-resolves via [ToolSet.find] and retries decode + execute. A
 * single repair attempt per call (no recursive repair) to keep loops
 * bounded.
 */
public typealias ToolCallRepairFunction<TContext> = suspend (
    failedCall: ContentPart.ToolCall,
    error: Throwable,
    messages: List<ModelMessage>,
    tools: ToolSet<TContext>,
) -> ContentPart.ToolCall?
