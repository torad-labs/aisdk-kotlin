package ai.torad.aisdk

import dev.drewhamilton.poko.Poko
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
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
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.serializer
import kotlin.jvm.JvmOverloads

/**
 * LLM-visible metadata for a [Tool]. Separates schema from executor.
 * @since 0.3.0-beta01
 */
@Poko
public class ToolSchema(
    /** @since 0.3.0-beta01 */
    public val name: String,
    /** @since 0.3.0-beta01 */
    public val description: String,
    /** @since 0.3.0-beta01 */
    public val strict: Boolean? = null,
    /** @since 0.3.0-beta01 */
    public val inputExamples: List<String> = emptyList(),
    /** @since 0.3.0-beta01 */
    public val metadata: Map<String, JsonElement> = emptyMap(),
    /** @since 0.3.0-beta01 */
    public val providerExecuted: Boolean = false,
    /** @since 0.3.0-beta01 */
    public val providerOptions: ProviderOptions = ProviderOptions.None,
)

/**
 * Bundles selected [ToolSchema] flags for factory functions.
 * @since 0.3.0-beta01
 */
@Poko
public class ToolSchemaOptions internal constructor(
    /** @since 0.3.0-beta01 */
    public val strict: Boolean? = null,
    /** @since 0.3.0-beta01 */
    public val providerExecuted: Boolean = false,
)

/** @since 0.3.0-beta01 */
public class ToolSchemaOptionsBuilder {
    private var strict: Boolean? = null
    private var providerExecuted: Boolean = false

    /** @since 0.3.0-beta01 */
    public fun strict(value: Boolean?): ToolSchemaOptionsBuilder {
        strict = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun providerExecuted(value: Boolean): ToolSchemaOptionsBuilder {
        providerExecuted = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun build(): ToolSchemaOptions =
        ToolSchemaOptions(
            strict = strict,
            providerExecuted = providerExecuted,
        )
}

/** @since 0.3.0-beta01 */
public fun ToolSchemaOptions(
    block: ToolSchemaOptionsBuilder.() -> Unit = {},
): ToolSchemaOptions =
    ToolSchemaOptionsBuilder().apply(block).build()

/**
 * Wrapper emitted by [Tool.execute] — currently only [Success]; sealed for future variants.
 * @since 0.3.0-beta01
 */
public sealed class ToolResult<out O> {
    @Poko
    /** @since 0.3.0-beta01 */
    public class Success<out O>(public val value: O) : ToolResult<O>()
}

/**
 * A tool the LLM can call. Two idiomatic patterns:
 *
 * **1. Extend it** — named subclass carrying both schema and executor:
 * ```
 * class SearchDocsTool(private val repo: DocRepository) : Tool<SearchInput, List<SearchResult>, AppContext>() {
 *     override val schema = ToolSchema("searchDocs", "Search the product documentation")
 *     override val inputSerializer = serializer<SearchInput>()
 *     override val outputSerializer = serializer<List<SearchResult>>()
 *     override fun execute(input: SearchInput, ctx: ToolExecutionContext<AppContext>) = flow {
 *         emit(ToolResult.Success(repo.search(input.query)))
 *     }
 * }
 * ```
 *
 * **2. Use the [Tool] / [StreamingTool] PascalCase factories** — anonymous subclass for inline tools.
 */
/** @since 0.3.0-beta01 */
public abstract class Tool<TInput, TOutput, TContext> {
    /** @since 0.3.0-beta01 */
    public abstract val schema: ToolSchema
    /** @since 0.3.0-beta01 */
    public abstract val inputSerializer: KSerializer<TInput>
    /** @since 0.3.0-beta01 */
    public abstract val outputSerializer: KSerializer<TOutput>

    /**
     * Flow-first executor: emit one [ToolResult.Success] per value. The LAST emission is the
     * final result that feeds the model; earlier emissions surface as preliminary tool results.
     * Extend [StreamingTool] and override [StreamingTool.executeStream] for the streaming case.
      * @since 0.3.0-beta01
     */
    public abstract fun execute(input: TInput, ctx: ToolExecutionContext<TContext>): Flow<ToolResult<TOutput>>

    // Backward-compat properties so ToolLoopAgent compiles unchanged.
    /** @since 0.3.0-beta01 */
    public val name: String get() = schema.name
    /** @since 0.3.0-beta01 */
    public val description: String get() = schema.description
    /** @since 0.3.0-beta01 */
    public val strict: Boolean? get() = schema.strict
    /** @since 0.3.0-beta01 */
    public val inputExamples: List<String> get() = schema.inputExamples
    /** @since 0.3.0-beta01 */
    public val metadata: Map<String, JsonElement> get() = schema.metadata
    /** @since 0.3.0-beta01 */
    public val providerExecuted: Boolean get() = schema.providerExecuted
    /** @since 0.3.0-beta01 */
    public val providerOptions: ProviderOptions get() = schema.providerOptions

    /** Approval gate — return true to pause the loop for host approval. Default: never gates. */
    public open suspend fun needsApproval(input: TInput, options: ToolPredicateOptions<TContext>): Boolean = false

    /**
     * Model-visible summary. Return null to send raw output as-is.
     * @since 0.3.0-beta01
     */
    public open fun toModelOutput(output: TOutput, options: ToolPredicateOptions<TContext>): ToolResultOutput? = null

    public open suspend fun onInputStart(streamingId: String): Unit = Unit
    public open suspend fun onInputDelta(streamingId: String, delta: String): Unit = Unit
    public open suspend fun onInputAvailable(toolCallId: String, input: TInput): Unit = Unit

    /** Internal bridge for ToolLoopAgent — returns raw Flow<TOutput> via execute() unwrap. */
    internal open fun streamExecutor(scope: ToolExecutionContext<TContext>, input: TInput): Flow<TOutput> =
        execute(input, scope).map { result -> when (result) { is ToolResult.Success -> result.value } }

    internal fun descriptionWithExamples(): String {
        val examples = inputExamples
        if (examples.isEmpty()) return description
        val appendix = examples.joinToString(separator = "\n") { "Example: $it" }
        return "$description\n\n$appendix"
    }
}

/**
 * A [Tool] whose executor emits preliminary snapshots before the final value. The LAST
 * emission is the final result; earlier emissions surface as ToolResult(preliminary = true).
 *
 * ```
 * class LineupTool(private val repo: LineupRepo) : StreamingTool<LineupQuery, Lineup, AppContext>() {
 *     override val schema = ToolSchema("getLineup", "Get sets playing at a stage on a given day")
 *     override val inputSerializer = serializer<LineupQuery>()
 *     override val outputSerializer = serializer<Lineup>()
 *     override fun ToolExecutionContext<AppContext>.executeStream(input: LineupQuery) = flow {
 *         emit(repo.fastSummary(input))  // preliminary
 *         emit(repo.fullDetails(input))  // final — goes to model
 *     }
 * }
 * ```
 */
/** @since 0.3.0-beta01 */
public abstract class StreamingTool<TInput, TOutput, TContext> : Tool<TInput, TOutput, TContext>() {
    /** @since 0.3.0-beta01 */
    public abstract fun ToolExecutionContext<TContext>.executeStream(input: TInput): Flow<TOutput>

    final override fun execute(input: TInput, ctx: ToolExecutionContext<TContext>): Flow<ToolResult<TOutput>> {
        val self = this
        return with(self) { ctx.executeStream(input) }.map { ToolResult.Success(it) }
    }

    internal final override fun streamExecutor(scope: ToolExecutionContext<TContext>, input: TInput): Flow<TOutput> {
        val self = this
        return with(self) { scope.executeStream(input) }
    }
}

@Suppress("LongParameterList")
internal class LambdaTool<TInput, TOutput, TContext>(
    override val schema: ToolSchema,
    override val inputSerializer: KSerializer<TInput>,
    override val outputSerializer: KSerializer<TOutput>,
    private val executeFn: suspend ToolExecutionContext<TContext>.(TInput) -> TOutput,
    private val approvalFn: (suspend (TInput, ToolPredicateOptions<TContext>) -> Boolean)?,
    private val modelOutputFn: ((TOutput, ToolPredicateOptions<TContext>) -> ToolResultOutput)?,
    private val inputStartFn: (suspend (String) -> Unit)?,
    private val inputDeltaFn: (suspend (String, String) -> Unit)?,
    private val inputAvailableFn: (suspend (String, TInput) -> Unit)?,
) : Tool<TInput, TOutput, TContext>() {
    override fun execute(input: TInput, ctx: ToolExecutionContext<TContext>): Flow<ToolResult<TOutput>> = flow {
        emit(ToolResult.Success(executeFn.invoke(ctx, input)))
    }
    override suspend fun needsApproval(input: TInput, options: ToolPredicateOptions<TContext>): Boolean =
        approvalFn?.invoke(input, options) ?: false
    override fun toModelOutput(output: TOutput, options: ToolPredicateOptions<TContext>): ToolResultOutput? =
        modelOutputFn?.invoke(output, options)
    override suspend fun onInputStart(streamingId: String) { inputStartFn?.invoke(streamingId) }
    override suspend fun onInputDelta(streamingId: String, delta: String) { inputDeltaFn?.invoke(streamingId, delta) }
    override suspend fun onInputAvailable(toolCallId: String, input: TInput) { inputAvailableFn?.invoke(toolCallId, input) }
}

@Suppress("LongParameterList")
internal class LambdaStreamingTool<TInput, TOutput, TContext>(
    override val schema: ToolSchema,
    override val inputSerializer: KSerializer<TInput>,
    override val outputSerializer: KSerializer<TOutput>,
    private val streamFn: ToolExecutionContext<TContext>.(TInput) -> Flow<TOutput>,
    private val approvalFn: (suspend (TInput, ToolPredicateOptions<TContext>) -> Boolean)?,
    private val modelOutputFn: ((TOutput, ToolPredicateOptions<TContext>) -> ToolResultOutput)?,
    private val inputStartFn: (suspend (String) -> Unit)?,
    private val inputDeltaFn: (suspend (String, String) -> Unit)?,
    private val inputAvailableFn: (suspend (String, TInput) -> Unit)?,
) : StreamingTool<TInput, TOutput, TContext>() {
    override fun ToolExecutionContext<TContext>.executeStream(input: TInput): Flow<TOutput> = streamFn(input)
    override suspend fun needsApproval(input: TInput, options: ToolPredicateOptions<TContext>): Boolean =
        approvalFn?.invoke(input, options) ?: false
    override fun toModelOutput(output: TOutput, options: ToolPredicateOptions<TContext>): ToolResultOutput? =
        modelOutputFn?.invoke(output, options)
    override suspend fun onInputStart(streamingId: String) { inputStartFn?.invoke(streamingId) }
    override suspend fun onInputDelta(streamingId: String, delta: String) { inputDeltaFn?.invoke(streamingId, delta) }
    override suspend fun onInputAvailable(toolCallId: String, input: TInput) { inputAvailableFn?.invoke(toolCallId, input) }
}

/**
 * Erased map of tools indexed by name. Application code constructs via the [ToolSet] factory.
  * @since 0.3.0-beta01
 */
public class ToolSet<TContext>(
    /** @since 0.3.0-beta01 */
    public val byName: Map<String, Tool<*, *, TContext>>,
) {
    /** @since 0.3.0-beta01 */
    public val descriptors: List<LanguageModelTool> by lazy {
        byName.values.map { tool ->
            LanguageModelTool(
                name = tool.name,
                description = tool.descriptionWithExamples(),
                parametersSchemaJson = ToolJsonSchema.jsonSchemaFor(tool),
                strict = tool.strict,
                providerExecuted = tool.providerExecuted,
                metadata = tool.metadata,
                providerOptions = tool.providerOptions,
            )
        }
    }

    /** @since 0.3.0-beta01 */
    public fun find(toolName: String): Tool<*, *, TContext>? = byName[toolName]

    /** @since 0.3.0-beta01 */
    public fun names(): List<String> = byName.keys.toList()

    public operator fun plus(other: ToolSet<TContext>): ToolSet<TContext> {
        val overlap = byName.keys intersect other.byName.keys
        require(overlap.isEmpty()) {
            "Duplicate tool name(s) when combining tool sets: ${overlap.joinToString()}."
        }
        return ToolSet(byName + other.byName)
    }

    public companion object {
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
    }
}

/** Construct a [ToolSet] from individual tools. Throws on duplicate names. */
public fun <TContext> ToolSet(vararg tools: Tool<*, *, TContext>): ToolSet<TContext> =
    ToolSet(ToolSet.requireUniqueToolNames(tools.toList()))

@AiSdkDsl
/** @since 0.3.0-beta01 */
public class ToolSetBuilder<TContext> {
    private val tools = linkedMapOf<String, Tool<*, *, TContext>>()

    /** @since 0.3.0-beta01 */
    public fun add(tool: Tool<*, *, TContext>): ToolSetBuilder<TContext> {
        require(tool.name !in tools) { "Duplicate tool name `${tool.name}`." }
        tools[tool.name] = tool
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun build(): ToolSet<TContext> = ToolSet(tools.toMap())
}

/** Single-value [Tool] factory — executor is a suspend function returning [TOutput]. */
@Suppress("LongParameterList")
@JvmOverloads
public fun <TInput, TOutput, TContext> Tool(
    name: String,
    description: String,
    inputSerializer: KSerializer<TInput>,
    outputSerializer: KSerializer<TOutput>,
    needsApproval: (suspend (input: TInput, options: ToolPredicateOptions<TContext>) -> Boolean)? = null,
    toModelOutput: ((TOutput, ToolPredicateOptions<TContext>) -> ToolResultOutput)? = null,
    schemaOptions: ToolSchemaOptions = ToolSchemaOptions {},
    inputExamples: List<String> = emptyList(),
    onInputStart: (suspend (streamingId: String) -> Unit)? = null,
    onInputDelta: (suspend (streamingId: String, delta: String) -> Unit)? = null,
    onInputAvailable: (suspend (toolCallId: String, input: TInput) -> Unit)? = null,
    metadata: Map<String, JsonElement> = emptyMap(),
    providerOptions: ProviderOptions = ProviderOptions.None,
    executor: suspend ToolExecutionContext<TContext>.(TInput) -> TOutput,
): Tool<TInput, TOutput, TContext> = LambdaTool(
    schema = ToolSchema(name, description, schemaOptions.strict, inputExamples, metadata, schemaOptions.providerExecuted, providerOptions),
    inputSerializer = inputSerializer,
    outputSerializer = outputSerializer,
    executeFn = executor,
    approvalFn = needsApproval,
    modelOutputFn = toModelOutput,
    inputStartFn = onInputStart,
    inputDeltaFn = onInputDelta,
    inputAvailableFn = onInputAvailable,
)

/** Reified convenience overload — infers serializers from type parameters. */
@Suppress("LongParameterList")
public inline fun <reified TInput, reified TOutput, TContext> Tool(
    name: String,
    description: String,
    noinline needsApproval: (suspend (input: TInput, options: ToolPredicateOptions<TContext>) -> Boolean)? = null,
    noinline toModelOutput: ((TOutput, ToolPredicateOptions<TContext>) -> ToolResultOutput)? = null,
    schemaOptions: ToolSchemaOptions = ToolSchemaOptions {},
    inputExamples: List<String> = emptyList(),
    noinline onInputStart: (suspend (streamingId: String) -> Unit)? = null,
    noinline onInputDelta: (suspend (streamingId: String, delta: String) -> Unit)? = null,
    noinline onInputAvailable: (suspend (toolCallId: String, input: TInput) -> Unit)? = null,
    metadata: Map<String, JsonElement> = emptyMap(),
    providerOptions: ProviderOptions = ProviderOptions.None,
    noinline executor: suspend ToolExecutionContext<TContext>.(TInput) -> TOutput,
): Tool<TInput, TOutput, TContext> = Tool(
    name = name,
    description = description,
    inputSerializer = serializer<TInput>(),
    outputSerializer = serializer<TOutput>(),
    needsApproval = needsApproval,
    toModelOutput = toModelOutput,
    schemaOptions = schemaOptions,
    inputExamples = inputExamples,
    onInputStart = onInputStart,
    onInputDelta = onInputDelta,
    onInputAvailable = onInputAvailable,
    metadata = metadata,
    providerOptions = providerOptions,
    executor = executor,
)

/** Streaming [Tool] factory — executor returns Flow<TOutput>, each emission is a value. */
@Suppress("LongParameterList")
@JvmOverloads
public fun <TInput, TOutput, TContext> StreamingTool(
    name: String,
    description: String,
    inputSerializer: KSerializer<TInput>,
    outputSerializer: KSerializer<TOutput>,
    needsApproval: (suspend (input: TInput, options: ToolPredicateOptions<TContext>) -> Boolean)? = null,
    toModelOutput: ((TOutput, ToolPredicateOptions<TContext>) -> ToolResultOutput)? = null,
    schemaOptions: ToolSchemaOptions = ToolSchemaOptions {},
    inputExamples: List<String> = emptyList(),
    onInputStart: (suspend (streamingId: String) -> Unit)? = null,
    onInputDelta: (suspend (streamingId: String, delta: String) -> Unit)? = null,
    onInputAvailable: (suspend (toolCallId: String, input: TInput) -> Unit)? = null,
    metadata: Map<String, JsonElement> = emptyMap(),
    providerOptions: ProviderOptions = ProviderOptions.None,
    executor: ToolExecutionContext<TContext>.(TInput) -> Flow<TOutput>,
): Tool<TInput, TOutput, TContext> = LambdaStreamingTool(
    schema = ToolSchema(name, description, schemaOptions.strict, inputExamples, metadata, schemaOptions.providerExecuted, providerOptions),
    inputSerializer = inputSerializer,
    outputSerializer = outputSerializer,
    streamFn = executor,
    approvalFn = needsApproval,
    modelOutputFn = toModelOutput,
    inputStartFn = onInputStart,
    inputDeltaFn = onInputDelta,
    inputAvailableFn = onInputAvailable,
)

/** Reified streaming factory — infers serializers from type parameters. */
@Suppress("LongParameterList")
public inline fun <reified TInput, reified TOutput, TContext> StreamingTool(
    name: String,
    description: String,
    noinline needsApproval: (suspend (input: TInput, options: ToolPredicateOptions<TContext>) -> Boolean)? = null,
    noinline toModelOutput: ((TOutput, ToolPredicateOptions<TContext>) -> ToolResultOutput)? = null,
    schemaOptions: ToolSchemaOptions = ToolSchemaOptions {},
    inputExamples: List<String> = emptyList(),
    noinline onInputStart: (suspend (streamingId: String) -> Unit)? = null,
    noinline onInputDelta: (suspend (streamingId: String, delta: String) -> Unit)? = null,
    noinline onInputAvailable: (suspend (toolCallId: String, input: TInput) -> Unit)? = null,
    metadata: Map<String, JsonElement> = emptyMap(),
    providerOptions: ProviderOptions = ProviderOptions.None,
    noinline executor: ToolExecutionContext<TContext>.(TInput) -> Flow<TOutput>,
): Tool<TInput, TOutput, TContext> = StreamingTool(
    name = name,
    description = description,
    inputSerializer = serializer<TInput>(),
    outputSerializer = serializer<TOutput>(),
    needsApproval = needsApproval,
    toModelOutput = toModelOutput,
    schemaOptions = schemaOptions,
    inputExamples = inputExamples,
    onInputStart = onInputStart,
    onInputDelta = onInputDelta,
    onInputAvailable = onInputAvailable,
    metadata = metadata,
    providerOptions = providerOptions,
    executor = executor,
)

/**
 * Runtime-typed tool — inputs and outputs are raw [JsonElement]. Schema is
 * supplied as a JSON string and stored in [ToolSchema.metadata] under "inputSchema".
 */
@JvmOverloads
public fun <TContext> DynamicTool(
    name: String,
    description: String,
    inputSchemaJson: String = "{}",
    metadata: Map<String, JsonElement> = emptyMap(),
    toModelOutput: ((JsonElement, ToolPredicateOptions<TContext>) -> ToolResultOutput)? = null,
    executor: suspend ToolExecutionContext<TContext>.(JsonElement) -> JsonElement,
): Tool<JsonElement, JsonElement, TContext> = Tool(
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
                "DynamicTool `$name`: inputSchemaJson is not valid JSON — ${e.message}",
                e,
            )
        }
        ),
    toModelOutput = toModelOutput,
    executor = executor,
)

/** Provider-executed tool: advertises itself to the model but has no local executor. */
public fun <TInput, TOutput, TContext> ProviderExecutedTool(
    name: String,
    description: String,
    inputSerializer: KSerializer<TInput>,
    outputSerializer: KSerializer<TOutput>,
    metadata: Map<String, JsonElement> = emptyMap(),
): Tool<TInput, TOutput, TContext> = StreamingTool(
    name = name,
    description = description,
    inputSerializer = inputSerializer,
    outputSerializer = outputSerializer,
    metadata = metadata,
    schemaOptions = ToolSchemaOptions {
        providerExecuted(true)
    },
) { _ ->
    flow {
        throw AgentError.ToolExecution(
            name,
            toolCallId,
            UnsupportedOperationException("provider-executed tool has no local executor"),
        )
    }
}

/** Execute a tool outside the agent loop with one-step lookahead semantics. */
public fun <TInput, TOutput, TContext> ExecuteTool(
    tool: Tool<TInput, TOutput, TContext>,
    input: TInput,
    options: ToolExecutionContext<TContext>,
): Flow<ExecuteToolResult<TOutput>> = flow {
    var pendingFinal: ExecuteToolResult.Final<TOutput>? = null
    tool.streamExecutor(options, input).collect { output ->
        pendingFinal?.let { emit(ExecuteToolResult.Preliminary(it.output)) }
        pendingFinal = ExecuteToolResult.Final(output)
    }
    val final = pendingFinal ?: throw AgentError.ToolExecution(
        tool.name,
        options.toolCallId,
        NoOutputGeneratedError("Tool ${tool.name} produced no output"),
    )
    emit(final)
}

@Poko
/** @since 0.3.0-beta01 */
public class Schema<T>(
    /** @since 0.3.0-beta01 */
    public val jsonSchema: JsonElement,
    /** @since 0.3.0-beta01 */
    public val validate: ((JsonElement) -> T)? = null,
)

/** @since 0.3.0-beta01 */
public class LazySchema<T>(
    private val createSchema: () -> Schema<T>,
) {
    private var cached: Schema<T>? = null

    public operator fun invoke(): Schema<T> =
        cached ?: createSchema().also { cached = it }
}

/** @since 0.3.0-beta01 */
public sealed class ValidationResult<out T> {
    @Poko
    /** @since 0.3.0-beta01 */
    public class Success<T>(
        /** @since 0.3.0-beta01 */
        public val value: T,
        /** @since 0.3.0-beta01 */
        public val rawValue: JsonElement,
    ) : ValidationResult<T>()

    @Poko
    /** @since 0.3.0-beta01 */
    public class Failure(
        /** @since 0.3.0-beta01 */
        public val error: TypeValidationError,
        /** @since 0.3.0-beta01 */
        public val rawValue: JsonElement,
    ) : ValidationResult<Nothing>()
}

/** @since 0.3.0-beta01 */
public object Schemas {
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
        val schemaType = ((schema as? JsonObject)?.get("type") as? JsonPrimitive)?.contentOrNull
        return when (schemaType) {
            null -> value
            "object" -> value as? JsonObject ?: throw IllegalArgumentException("Expected JSON object.")
            "array" -> value as? JsonArray ?: throw IllegalArgumentException("Expected JSON array.")
            "string" -> {
                val primitive = value as? JsonPrimitive ?: throw IllegalArgumentException("Expected JSON string.")
                if (!primitive.isJsonString()) throw IllegalArgumentException("Expected JSON string.")
                primitive.content
            }
            // longOrNull (not intOrNull): JSON Schema "integer" is not 32-bit — a ms timestamp or
            // 64-bit id is a valid integer that intOrNull would reject as null.
            "integer" -> (value as? JsonPrimitive)?.longOrNull
                ?: throw IllegalArgumentException("Expected JSON integer.")
            "number" -> (value as? JsonPrimitive)?.doubleOrNull
                ?: throw IllegalArgumentException("Expected JSON number.")
            "boolean" -> (value as? JsonPrimitive)?.booleanOrNull
                ?: throw IllegalArgumentException("Expected JSON boolean.")
            else -> value
        }
    }

    private fun JsonPrimitive.isJsonString(): Boolean =
        toString().startsWith("\"")

    public fun <T> safeValidateTypes(
        value: JsonElement,
        schema: LazySchema<T>,
        context: TypeValidationContext? = null,
    ): ValidationResult<T> = Schemas.safeValidateTypes(value, schema(), context)

    public fun <T> validateTypes(
        value: JsonElement,
        schema: Schema<T>,
        context: TypeValidationContext? = null,
    ): T = when (val result = Schemas.safeValidateTypes(value, schema, context)) {
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
        providerOptions: ProviderOptions,
        schema: Schema<T>,
    ): T? {
        val value = providerOptions.toMap()[provider] ?: return null
        return when (val result = Schemas.safeValidateTypes(value, schema)) {
            is ValidationResult.Success -> result.value
            is ValidationResult.Failure -> throw InvalidArgumentError(
                "providerOptions",
                "invalid $provider provider options",
                result.error,
            )
        }
    }
}

/** @since 0.3.0-beta01 */
public sealed class ExecuteToolResult<out TOutput> {
    @Poko
    /** @since 0.3.0-beta01 */
    public class Preliminary<TOutput>(public val output: TOutput) : ExecuteToolResult<TOutput>()

    @Poko
    /** @since 0.3.0-beta01 */
    public class Final<TOutput>(public val output: TOutput) : ExecuteToolResult<TOutput>()
}

/** @since 0.3.0-beta01 */
public data class ToolNameMapping(
    private val customToolNameToProviderToolName: Map<String, String>,
    private val providerToolNameToCustomToolName: Map<String, String>,
) {
    /** @since 0.3.0-beta01 */
    public fun toProviderToolName(customToolName: String): String =
        customToolNameToProviderToolName[customToolName] ?: customToolName

    /** @since 0.3.0-beta01 */
    public fun toCustomToolName(providerToolName: String): String =
        providerToolNameToCustomToolName[providerToolName] ?: providerToolName
}

/** @since 0.3.0-beta01 */
public fun ToolNameMapping(
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

public fun <TInput, TOutput, TContext> ProviderToolFactoryOptions(
    block: ProviderToolFactoryOptionsBuilder<TInput, TOutput, TContext>.() -> Unit = {},
): ProviderToolFactoryOptions<TInput, TOutput, TContext> =
    ProviderToolFactoryOptionsBuilder<TInput, TOutput, TContext>().apply(block).build()

/**
 * Whether the LLM should call a tool, no tool, a specific tool, etc.
 * @since 0.3.0-beta01
 */
@kotlinx.serialization.Serializable
public sealed class ToolChoice {
    /**
     * Let the provider/model decide whether to call a tool.
     *
     * @since 0.3.0-beta01
     */
    @kotlinx.serialization.Serializable
    @SerialName("auto")
    public data object Auto : ToolChoice()

    /**
     * Prevent tool calls for this request.
     *
     * @since 0.3.0-beta01
     */
    @kotlinx.serialization.Serializable
    @SerialName("none")
    public data object None : ToolChoice()

    /**
     * Require at least one tool call when the provider supports this mode.
     *
     * @since 0.3.0-beta01
     */
    @kotlinx.serialization.Serializable
    @SerialName("required")
    public data object Required : ToolChoice()

    /**
     * Request a specific named tool.
     *
     * @since 0.3.0-beta01
     */
    @kotlinx.serialization.Serializable
    @SerialName("specific")
    @Poko
    public class Specific(public val toolName: String) : ToolChoice()
}

/**
 * Lets a tool's executor write custom events into the agent's active output stream.
 * Distinct from the streaming-tool mechanism — pushes arbitrary [StreamEvent]s.
 */
public interface ToolStreamWriter {
    public suspend fun write(event: StreamEvent)
    public suspend fun writeData(value: JsonElement)
}

/** @since 0.3.0-beta01 */
public data object NoopToolStreamWriter : ToolStreamWriter {
    override suspend fun write(event: StreamEvent): Unit = Unit
    override suspend fun writeData(value: JsonElement): Unit = Unit
}

/**
 * What [Tool.execute] receives as its context. Holds the typed application context,
 * abort signal, step number, running message list, and a writer for custom events.
  * @since 0.3.0-beta01
 */
public class ToolExecutionContext<TContext>(
    /** @since 0.3.0-beta01 */
    public val context: TContext?,
    /** @since 0.3.0-beta01 */
    public val abortSignal: AbortSignal,
    /** @since 0.3.0-beta01 */
    public val stepNumber: Int,
    /** @since 0.3.0-beta01 */
    public val messages: List<ModelMessage>,
    /** @since 0.3.0-beta01 */
    public val toolCallId: String,
    /** @since 0.3.0-beta01 */
    public val writer: ToolStreamWriter = NoopToolStreamWriter,
)

/**
 * Options bag passed to predicate callbacks ([Tool.needsApproval], [Tool.toModelOutput]).
  * @since 0.3.0-beta01
 */
public class ToolPredicateOptions<TContext> internal constructor(
    /** @since 0.3.0-beta01 */
    public val toolCallId: String,
    /** @since 0.3.0-beta01 */
    public val messages: List<ModelMessage>,
    /** @since 0.3.0-beta01 */
    public val experimental_context: TContext? = null,
)

/** @since 0.3.0-beta01 */
public class ToolPredicateOptionsBuilder<TContext> {
    private var toolCallId: String? = null
    private var messages: List<ModelMessage>? = null
    private var experimentalContext: TContext? = null

    /** @since 0.3.0-beta01 */
    public fun toolCallId(value: String): ToolPredicateOptionsBuilder<TContext> {
        toolCallId = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun messages(value: List<ModelMessage>): ToolPredicateOptionsBuilder<TContext> {
        messages = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun experimental_context(value: TContext?): ToolPredicateOptionsBuilder<TContext> {
        experimentalContext = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun build(): ToolPredicateOptions<TContext> =
        ToolPredicateOptions(
            toolCallId = requireNotNull(toolCallId) { "ToolPredicateOptions.toolCallId is required" },
            messages = requireNotNull(messages) { "ToolPredicateOptions.messages is required" },
            experimental_context = experimentalContext,
        )
}

public fun <TContext> ToolPredicateOptions(
    block: ToolPredicateOptionsBuilder<TContext>.() -> Unit = {},
): ToolPredicateOptions<TContext> =
    ToolPredicateOptionsBuilder<TContext>().apply(block).build()

/**
 * Callback invoked when a tool call's JSON arguments fail to decode. Return a corrected
 * [ContentPart.ToolCall] to retry, or null to give up. Mirrors v6's experimental_repairToolCall.
 */
public typealias ToolCallRepairFunction<TContext> = suspend (
    failedCall: ContentPart.ToolCall,
    error: Throwable,
    messages: List<ModelMessage>,
    tools: ToolSet<TContext>,
) -> ContentPart.ToolCall?
