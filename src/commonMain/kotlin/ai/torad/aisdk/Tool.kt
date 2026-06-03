package ai.torad.aisdk

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * A tool the LLM can call — application code constructs these via the
 * top-level [tool] (single-value) or [streamingTool] (Flow) factories.
 *
 * Per invariant I-2, tools use [inputSchema] (v6) — never `parameters`
 * (v5, removed). Per I-8 tools are stateless: inputs in, outputs out, no
 * shared mutable state.
 *
 * @param TInput  Application-defined input type, must be `@Serializable`.
 *                The model produces JSON matching its schema; the agent
 *                deserializes via [inputSerializer] before calling [executor].
 * @param TOutput Application-defined output type, must be `@Serializable`.
 *                Serialized into the model's tool-result message via
 *                [outputSerializer], or summarized via [toModelOutput] if
 *                the raw output would be too verbose for the model.
 * @param TContext Caller-supplied typed context propagated through the
 *                whole loop (set via `callOptionsSchema`, accessed inside
 *                the executor as `this.context`).
 *
 * The internal [executor] always returns a [Flow] (the v6 alignment per
 * `tool.ts:68-71`'s `AsyncIterable<OUTPUT>` shape). Single-value tools
 * built via [tool] wrap their suspend `(TInput) -> TOutput` body in a
 * one-emission flow; streaming tools built via [streamingTool] pass
 * their Flow body straight through. The agent loop's tool dispatcher
 * collects with one-step lookahead — non-final emissions become
 * `StreamEvent.ToolResult(preliminary = true)`, the last emission is
 * the final result that lands in the model's message log.
 */
class Tool<TInput, TOutput, TContext>(
    val name: String,
    val description: String,
    val inputSerializer: KSerializer<TInput>,
    val outputSerializer: KSerializer<TOutput>,
    val executor: ToolExecutionContext<TContext>.(TInput) -> Flow<TOutput>,
    /**
     * Approval gate. Per AISDK_PORT_GAPS.md gap #17, the predicate
     * receives [ToolPredicateOptions] (toolCallId + messages +
     * experimental_context) — not just `context`. Lets the gate
     * decide based on conversation history or call identity.
     */
    val needsApproval: (suspend (input: TInput, options: ToolPredicateOptions<TContext>) -> Boolean)? = null,
    /**
     * Model-visible summary. Per AISDK_PORT_GAPS.md gaps #17 + #14,
     * the callback receives both the typed output AND
     * [ToolPredicateOptions]; returns a structured
     * [ToolResultOutput] (Text / Json / Error) instead of a bare
     * `String`. The agent loop bridges each variant to the
     * wire-format `ContentPart.ToolResult.modelVisible` (+ `isError`).
     */
    val toModelOutput: ((TOutput, ToolPredicateOptions<TContext>) -> ToolResultOutput)? = null,
    /**
     * Per best practice #9: strict JSON schema enforcement is opt-in per
     * tool, not global. Some providers reject schemas with format/regex
     * constraints under strict mode; mark such tools `strict = false`.
     */
    val strict: Boolean = true,
    /**
     * Few-shot example inputs encoded as JSON strings. Mirrors v6's
     * `tool.inputExamples` (per AISDK_PORT_GAPS.md gap #19). Disproportionate
     * quality lift on small models — Gemma 4 E2B's tool-call accuracy
     * notably improves with 2-3 in-tool examples. [ToolSet.descriptors]
     * inlines these into the description as `Example: <json>` so every
     * provider sees them (most don't expose a separate examples field).
     */
    val inputExamples: List<String> = emptyList(),
    /**
     * Lifecycle hook — fired when the model commits to calling this
     * tool (StreamEvent.ToolInputStart arrives). Mirrors v6's
     * `tool.onInputStart` (per AISDK_PORT_GAPS.md gap #18). Lets a
     * tool pre-warm: UI spinner, cache priming, etc.
     */
    val onInputStart: (suspend (streamingId: String) -> Unit)? = null,
    /**
     * Lifecycle hook — fired as the model streams the tool's input
     * JSON token-by-token (StreamEvent.ToolInputDelta). The accumulated
     * input isn't yet valid JSON; this is for raw-character pre-warm.
     */
    val onInputDelta: (suspend (streamingId: String, delta: String) -> Unit)? = null,
    /**
     * Lifecycle hook — fired after the streamed JSON has parsed
     * successfully into the typed [TInput], just before [executor]
     * runs. Mirrors v6's `tool.onInputAvailable`.
     */
    val onInputAvailable: (suspend (toolCallId: String, input: TInput) -> Unit)? = null,
    /**
     * Application-defined metadata bag. Mirrors v6's `tool.metadata`
     * (per AISDK_PORT_GAPS.md gap #34). Opaque to the loop —
     * consumers (logger middleware, telemetry, host-side gating) can
     * read it via [ToolSet.byName]. Examples: feature-flag key,
     * billing tier, owning team. Empty by default.
     */
    val metadata: Map<String, JsonElement> = emptyMap(),
    val providerExecuted: Boolean = false,
)

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
fun <TInput, TOutput, TContext> tool(
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
    executor: suspend ToolExecutionContext<TContext>.(TInput) -> TOutput,
): Tool<TInput, TOutput, TContext> = Tool(
    name = name,
    description = description,
    inputSerializer = inputSerializer,
    outputSerializer = outputSerializer,
    executor = { input ->
        val ctx = this
        flow { emit(executor.invoke(ctx, input)) }
    },
    needsApproval = needsApproval,
    toModelOutput = toModelOutput,
    strict = strict,
    inputExamples = inputExamples,
    onInputStart = onInputStart,
    onInputDelta = onInputDelta,
    onInputAvailable = onInputAvailable,
    metadata = metadata,
    providerExecuted = providerExecuted,
)

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
fun <TInput, TOutput, TContext> streamingTool(
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
): Tool<TInput, TOutput, TContext> = Tool(
    name = name,
    description = description,
    inputSerializer = inputSerializer,
    outputSerializer = outputSerializer,
    executor = executor,
    needsApproval = needsApproval,
    toModelOutput = toModelOutput,
    strict = strict,
    inputExamples = inputExamples,
    onInputStart = onInputStart,
    onInputDelta = onInputDelta,
    onInputAvailable = onInputAvailable,
    metadata = metadata,
    providerExecuted = providerExecuted,
)

/**
 * Erased map of tools indexed by name — what the agent loop actually
 * carries. Application code constructs via [toolSetOf].
 *
 * Erasure (using `*` projections) is required because a single agent
 * holds tools with different `TInput`/`TOutput` types. Type recovery
 * happens at the dispatch site via the tool's own serializers.
 */
class ToolSet<TContext>(
    val byName: Map<String, Tool<*, *, TContext>>,
) {
    val descriptors: List<LanguageModelTool> by lazy {
        byName.values.map { tool ->
            LanguageModelTool(
                name = tool.name,
                description = descriptionWithExamples(tool),
                parametersSchemaJson = jsonSchemaFor(tool),
                providerExecuted = tool.providerExecuted,
            )
        }
    }

    fun find(toolName: String): Tool<*, *, TContext>? = byName[toolName]

    /** Registered tool names — for [AgentError.NoSuchTool] diagnostics. */
    fun names(): List<String> = byName.keys.toList()

    operator fun plus(other: ToolSet<TContext>): ToolSet<TContext> =
        ToolSet(byName + other.byName)
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

/** Construct a [ToolSet] from individual tools. */
fun <TContext> toolSetOf(vararg tools: Tool<*, *, TContext>): ToolSet<TContext> =
    ToolSet(tools.associateBy { it.name })

/**
 * Runtime-typed tool equivalent to v6's `dynamicTool`. Inputs and
 * outputs are raw [JsonElement] values, so the tool can be registered
 * from external manifests or MCP-style runtime catalogs.
 */
fun <TContext> dynamicTool(
    name: String,
    description: String,
    inputSchemaJson: String = "{}",
    metadata: Map<String, JsonElement> = emptyMap(),
    executor: suspend ToolExecutionContext<TContext>.(JsonElement) -> JsonElement,
): Tool<JsonElement, JsonElement, TContext> = tool(
    name = name,
    description = description,
    inputSerializer = JsonElement.serializer(),
    outputSerializer = JsonElement.serializer(),
    metadata = metadata + ("inputSchema" to Json.parseToJsonElement(inputSchemaJson)),
    executor = executor,
)

fun <TInput, TOutput, TContext> providerExecutedTool(
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
    flow { throw AgentError.ToolExecution(name, toolCallId, UnsupportedOperationException("provider-executed tool has no local executor")) }
}

data class Schema<T>(
    val jsonSchema: JsonElement,
    val validate: ((JsonElement) -> T)? = null,
)

fun <T> jsonSchema(
    schema: JsonElement,
    validate: ((JsonElement) -> T)? = null,
): Schema<T> = Schema(schema, validate)

fun <T> asSchema(schema: Schema<T>): Schema<T> = schema

fun <T> zodSchema(
    schema: JsonElement,
    validate: ((JsonElement) -> T)? = null,
): Schema<T> = Schema(schema, validate)

internal fun jsonSchemaFor(tool: Tool<*, *, *>): String {
    tool.metadata["inputSchema"]?.let { return it.toString() }
    val descriptor = tool.inputSerializer.descriptor
    return descriptorToJsonSchema(descriptor).toString()
}

/**
 * Walk a kotlinx.serialization [kotlinx.serialization.descriptors.SerialDescriptor]
 * and produce a JSON Schema describing the shape. The output feeds two
 * downstream consumers:
 *  - Cloud providers (OpenAI / Anthropic / Gemini) that expect a strict
 *    OpenAPI-flavoured schema on `tools[].function.parameters`.
 *  - On-device providers (LiteRT-LM via [Tool.OpenApiTool]) whose
 *    chat-template renders the schema into Gemma 4 / FunctionGemma
 *    native `<|tool>declaration:...<tool|>` blocks. Without proper
 *    `properties` + `required` + `type` fields the model has no signal
 *    on what each tool expects, and routinely emits malformed calls or
 *    calls the wrong tool entirely.
 *
 * Handles the JSON-Schema subset every provider in the spec sheet
 * understands: string / integer / number / boolean primitives, nested
 * objects, arrays, and `required` lists. Pure function — no IO, no
 * side-effects — so it's testable and safe to call eagerly from tool
 * factories.
 */
private const val SCHEMA_TYPE_KEY = "type"
private const val SCHEMA_STRING = "string"
private const val SCHEMA_INTEGER = "integer"
private const val SCHEMA_NUMBER = "number"
private const val SCHEMA_BOOLEAN = "boolean"
private const val SCHEMA_OBJECT = "object"
private const val SCHEMA_ARRAY = "array"

private fun descriptorToJsonSchema(
    descriptor: kotlinx.serialization.descriptors.SerialDescriptor,
    seen: Set<String> = emptySet(),
): kotlinx.serialization.json.JsonElement =
    if (descriptor.isNullable) {
        nullableSchema(descriptorToNonNullJsonSchema(descriptor, seen))
    } else {
        descriptorToNonNullJsonSchema(descriptor, seen)
    }

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
private fun descriptorToNonNullJsonSchema(
    descriptor: kotlinx.serialization.descriptors.SerialDescriptor,
    seen: Set<String> = emptySet(),
): kotlinx.serialization.json.JsonElement {
    if (descriptor.serialName.startsWith("kotlinx.serialization.json.")) {
        return openJsonObjectSchema()
    }
    if (descriptor.serialName in seen) {
        return openJsonObjectSchema()
    }
    val nextSeen = seen + descriptor.serialName
    if (descriptor.isInline && descriptor.elementsCount == 1) {
        return descriptorToJsonSchema(descriptor.getElementDescriptor(0), nextSeen)
    }
    val kind = descriptor.kind
    primitiveKindToType(kind)?.let { return typeOnlySchema(it) }
    return when (kind) {
        kotlinx.serialization.descriptors.StructureKind.CLASS,
        kotlinx.serialization.descriptors.StructureKind.OBJECT,
        -> objectSchema(descriptor, nextSeen)
        kotlinx.serialization.descriptors.StructureKind.LIST -> listSchema(descriptor, nextSeen)
        kotlinx.serialization.descriptors.StructureKind.MAP -> mapSchema(descriptor, nextSeen)
        is kotlinx.serialization.descriptors.SerialKind.ENUM -> enumSchema(descriptor)
        is kotlinx.serialization.descriptors.PolymorphicKind.SEALED -> sealedSchema(descriptor, nextSeen)
        is kotlinx.serialization.descriptors.PolymorphicKind.OPEN -> jsonObj(
            SCHEMA_TYPE_KEY to kotlinx.serialization.json.JsonPrimitive(SCHEMA_OBJECT),
        )
        else -> typeOnlySchema(SCHEMA_STRING)
    }
}

/** Map a kotlinx.serialization primitive kind to its JSON Schema type
 *  name, or null if the kind is structural (object / list / map / enum). */
private fun primitiveKindToType(
    kind: kotlinx.serialization.descriptors.SerialKind,
): String? = when (kind) {
    kotlinx.serialization.descriptors.PrimitiveKind.STRING,
    kotlinx.serialization.descriptors.PrimitiveKind.CHAR,
    -> SCHEMA_STRING
    kotlinx.serialization.descriptors.PrimitiveKind.BOOLEAN -> SCHEMA_BOOLEAN
    kotlinx.serialization.descriptors.PrimitiveKind.BYTE,
    kotlinx.serialization.descriptors.PrimitiveKind.SHORT,
    kotlinx.serialization.descriptors.PrimitiveKind.INT,
    kotlinx.serialization.descriptors.PrimitiveKind.LONG,
    -> SCHEMA_INTEGER
    kotlinx.serialization.descriptors.PrimitiveKind.FLOAT,
    kotlinx.serialization.descriptors.PrimitiveKind.DOUBLE,
    -> SCHEMA_NUMBER
    else -> null
}

private fun typeOnlySchema(type: String): kotlinx.serialization.json.JsonObject = jsonObj(
    SCHEMA_TYPE_KEY to kotlinx.serialization.json.JsonPrimitive(type),
)

private fun objectSchema(
    descriptor: kotlinx.serialization.descriptors.SerialDescriptor,
    seen: Set<String>,
): kotlinx.serialization.json.JsonObject {
    val properties = mutableMapOf<String, kotlinx.serialization.json.JsonElement>()
    val required = mutableListOf<kotlinx.serialization.json.JsonElement>()
    for (i in 0 until descriptor.elementsCount) {
        val name = descriptor.getElementName(i)
        properties[name] = descriptorToJsonSchema(descriptor.getElementDescriptor(i), seen)
        if (!descriptor.isElementOptional(i)) {
            required.add(kotlinx.serialization.json.JsonPrimitive(name))
        }
    }
    val fields = mutableMapOf<String, kotlinx.serialization.json.JsonElement>(
        SCHEMA_TYPE_KEY to kotlinx.serialization.json.JsonPrimitive(SCHEMA_OBJECT),
        "properties" to kotlinx.serialization.json.JsonObject(properties),
        "additionalProperties" to kotlinx.serialization.json.JsonPrimitive(false),
    )
    if (required.isNotEmpty()) {
        fields["required"] = kotlinx.serialization.json.JsonArray(required)
    }
    return kotlinx.serialization.json.JsonObject(fields)
}

private fun listSchema(
    descriptor: kotlinx.serialization.descriptors.SerialDescriptor,
    seen: Set<String>,
): kotlinx.serialization.json.JsonObject {
    val items = if (descriptor.elementsCount > 0) {
        descriptorToJsonSchema(descriptor.getElementDescriptor(0), seen)
    } else {
        typeOnlySchema(SCHEMA_STRING)
    }
    return jsonObj(
        SCHEMA_TYPE_KEY to kotlinx.serialization.json.JsonPrimitive(SCHEMA_ARRAY),
        "items" to items,
    )
}

private fun mapSchema(
    descriptor: kotlinx.serialization.descriptors.SerialDescriptor,
    seen: Set<String>,
): kotlinx.serialization.json.JsonObject {
    val valueDescriptor = if (descriptor.elementsCount > 1) descriptor.getElementDescriptor(1) else null
    return jsonObj(
        SCHEMA_TYPE_KEY to kotlinx.serialization.json.JsonPrimitive(SCHEMA_OBJECT),
        "additionalProperties" to (valueDescriptor?.let { descriptorToJsonSchema(it, seen) }
            ?: kotlinx.serialization.json.JsonPrimitive(true)),
    )
}

private fun enumSchema(
    descriptor: kotlinx.serialization.descriptors.SerialDescriptor,
): kotlinx.serialization.json.JsonObject {
    val values = (0 until descriptor.elementsCount).map {
        kotlinx.serialization.json.JsonPrimitive(descriptor.getElementName(it))
    }
    return jsonObj(
        SCHEMA_TYPE_KEY to kotlinx.serialization.json.JsonPrimitive(SCHEMA_STRING),
        "enum" to kotlinx.serialization.json.JsonArray(values),
    )
}

private fun sealedSchema(
    descriptor: kotlinx.serialization.descriptors.SerialDescriptor,
    seen: Set<String>,
): kotlinx.serialization.json.JsonObject {
    val variants = (0 until descriptor.elementsCount)
        .map { descriptorToJsonSchema(descriptor.getElementDescriptor(it), seen) }
    return jsonObj(
        "oneOf" to kotlinx.serialization.json.JsonArray(variants),
    )
}

private fun nullableSchema(
    schema: kotlinx.serialization.json.JsonElement,
): kotlinx.serialization.json.JsonObject = jsonObj(
    "anyOf" to kotlinx.serialization.json.JsonArray(
        listOf(
            schema,
            jsonObj(SCHEMA_TYPE_KEY to kotlinx.serialization.json.JsonPrimitive("null")),
        ),
    ),
)

private fun openJsonObjectSchema(): kotlinx.serialization.json.JsonObject = jsonObj(
    SCHEMA_TYPE_KEY to kotlinx.serialization.json.JsonPrimitive(SCHEMA_OBJECT),
    "additionalProperties" to kotlinx.serialization.json.JsonPrimitive(true),
)

private fun jsonObj(
    vararg entries: Pair<String, kotlinx.serialization.json.JsonElement>,
): kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.JsonObject(entries.toMap())

/** Whether the LLM should call a tool, no tool, a specific tool, etc. */
@kotlinx.serialization.Serializable
sealed interface ToolChoice {
    @kotlinx.serialization.Serializable data object Auto : ToolChoice
    @kotlinx.serialization.Serializable data object None : ToolChoice
    @kotlinx.serialization.Serializable data object Required : ToolChoice
    @kotlinx.serialization.Serializable data class Specific(val toolName: String) : ToolChoice
}

/**
 * Lets a tool's executor write custom events into the agent's **active
 * output stream** — the port's equivalent of v6's `UIMessageStreamWriter`
 * (per AISDK_PORT_GAPS.md gap #21). Distinct from the `streamingTool`
 * mechanism (which surfaces progress as `ToolResult(preliminary = true)`):
 * the writer pushes ARBITRARY [StreamEvent]s — e.g. a transient status
 * line ([StreamEvent.TextDelta]) or a custom data payload
 * ([StreamEvent.Raw] via [writeData]) — that don't have to be
 * tool-result-shaped. Writes interleave with the tool's own emissions in
 * stream order. `streamToUiMessages` ignores `Raw` by default, so a
 * consumer that wants custom data intercepts the `Flow<StreamEvent>`
 * before UI conversion.
 */
interface ToolStreamWriter {
    /** Emit any event into the agent's output stream. */
    suspend fun write(event: StreamEvent)

    /** Convenience: emit [value] as a [StreamEvent.Raw] custom-data chunk. */
    suspend fun writeData(value: JsonElement)
}

/**
 * No-op [ToolStreamWriter] — the default on a [ToolExecutionContext]
 * constructed outside the loop (test fixtures, the approval-resume path
 * before the loop wires a real writer). Drops every write.
 */
object NoopToolStreamWriter : ToolStreamWriter {
    override suspend fun write(event: StreamEvent) = Unit
    override suspend fun writeData(value: JsonElement) = Unit
}

/**
 * What [Tool.executor] sees as `this`. Holds the typed application context
 * (whatever the agent's `callOptionsSchema` says) plus the abort signal
 * (invariant I-10: subagent tool handlers MUST forward this), the current
 * step number, and the running message list.
 */
class ToolExecutionContext<TContext>(
    val context: TContext?,
    val abortSignal: AbortSignal,
    val stepNumber: Int,
    val messages: List<ModelMessage>,
    /**
     * The current tool call's id. Mirrors v6's
     * `ToolExecutionOptions.toolCallId`. Lets a tool self-identify for tracing, telemetry, or
     * streaming-progress writes (paired with [writer]).
     */
    val toolCallId: String,
    /**
     * Writes custom events into the agent's active output stream
     * (gap #21). The loop supplies a real writer bound to the output
     * flow; defaults to [NoopToolStreamWriter] when a context is
     * constructed outside the loop.
     */
    val writer: ToolStreamWriter = NoopToolStreamWriter,
)

/**
 * Options bag passed to predicate callbacks ([Tool.needsApproval],
 * [Tool.toModelOutput]). Mirrors v6's `{toolCallId, messages,
 * experimental_context}` argument shape (per AISDK_PORT_GAPS.md
 * gap #17). Lets a predicate decide based on the conversation
 * history or the current call's identity, not just the input.
 *
 * Distinct from [ToolExecutionContext] — predicate options are
 * READ-ONLY metadata about the call, no abort signal because
 * predicates run synchronously before / after the executor.
 */
data class ToolPredicateOptions<TContext>(
    val toolCallId: String,
    val messages: List<ModelMessage>,
    val experimental_context: TContext? = null,
)

/**
 * Structured return type for [Tool.toModelOutput]. Replaces the
 * flat `String` return per AISDK_PORT_GAPS.md gap #14. Lets a tool
 * supply EITHER a short plain-text summary OR a structured JSON
 * payload OR a typed error to the model — the agent's loop bridges
 * each into the wire-format `ContentPart.ToolResult.modelVisible`
 * appropriately:
 *
 * - [Text] → `JsonPrimitive(text)`, `isError = false`.
 * - [Json] → the JsonElement as-is, `isError = false`.
 * - [Error] → `JsonPrimitive("Error: $message")`, `isError = true`.
 *
 * v6 also defines a `multipart` variant for content blocks; deferred
 * (no on-device use case for it yet).
 */
sealed class ToolResultOutput {
    data class Text(val text: String) : ToolResultOutput()
    data class Json(val json: JsonElement) : ToolResultOutput()
    data class Error(val message: String) : ToolResultOutput()
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
typealias ToolCallRepairFunction<TContext> = suspend (
    failedCall: ContentPart.ToolCall,
    error: Throwable,
    messages: List<ModelMessage>,
    tools: ToolSet<TContext>,
) -> ContentPart.ToolCall?
