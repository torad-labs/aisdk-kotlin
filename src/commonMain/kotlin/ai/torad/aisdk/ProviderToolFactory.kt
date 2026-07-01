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

/** @since 0.3.0-beta01 */
public class ProviderToolFactoryOptions<TInput, TOutput, TContext> internal constructor(
    /** @since 0.3.0-beta01 */
    public val outputSerializer: KSerializer<TOutput>,
    /** @since 0.3.0-beta01 */
    public val outputSchema: Schema<TOutput>? = null,
    /** @since 0.3.0-beta01 */
    public val args: Map<String, JsonElement> = emptyMap(),
    /** @since 0.3.0-beta01 */
    public val name: String? = null,
    /** @since 0.3.0-beta01 */
    public val description: String? = null,
    /** @since 0.3.0-beta01 */
    public val metadata: Map<String, JsonElement> = emptyMap(),
    /** @since 0.3.0-beta01 */
    public val execute: (suspend ToolExecutionContext<TContext>.(TInput) -> TOutput)? = null,
    /** @since 0.3.0-beta01 */
    public val needsApproval: (suspend (input: TInput, options: ToolPredicateOptions<TContext>) -> Boolean)? = null,
    /** @since 0.3.0-beta01 */
    public val toModelOutput: ((TOutput, ToolPredicateOptions<TContext>) -> ToolResultOutput)? = null,
    /** @since 0.3.0-beta01 */
    public val onInputStart: (suspend (streamingId: String) -> Unit)? = null,
    /** @since 0.3.0-beta01 */
    public val onInputDelta: (suspend (streamingId: String, delta: String) -> Unit)? = null,
    /** @since 0.3.0-beta01 */
    public val onInputAvailable: (suspend (toolCallId: String, input: TInput) -> Unit)? = null,
)

/** @since 0.3.0-beta01 */
public class ProviderToolFactoryOptionsBuilder<TInput, TOutput, TContext> {
    private var outputSerializer: KSerializer<TOutput>? = null
    private var outputSchema: Schema<TOutput>? = null
    private var args: Map<String, JsonElement> = emptyMap()
    private var name: String? = null
    private var description: String? = null
    private var metadata: Map<String, JsonElement> = emptyMap()
    private var execute: (suspend ToolExecutionContext<TContext>.(TInput) -> TOutput)? = null
    private var needsApproval: (suspend (input: TInput, options: ToolPredicateOptions<TContext>) -> Boolean)? = null
    private var toModelOutput: ((TOutput, ToolPredicateOptions<TContext>) -> ToolResultOutput)? = null
    private var onInputStart: (suspend (streamingId: String) -> Unit)? = null
    private var onInputDelta: (suspend (streamingId: String, delta: String) -> Unit)? = null
    private var onInputAvailable: (suspend (toolCallId: String, input: TInput) -> Unit)? = null

    /** @since 0.3.0-beta01 */
    public fun outputSerializer(value: KSerializer<TOutput>): ProviderToolFactoryOptionsBuilder<TInput, TOutput, TContext> {
        outputSerializer = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun outputSchema(value: Schema<TOutput>?): ProviderToolFactoryOptionsBuilder<TInput, TOutput, TContext> {
        outputSchema = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun args(value: Map<String, JsonElement>): ProviderToolFactoryOptionsBuilder<TInput, TOutput, TContext> {
        args = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun name(value: String?): ProviderToolFactoryOptionsBuilder<TInput, TOutput, TContext> {
        name = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun description(value: String?): ProviderToolFactoryOptionsBuilder<TInput, TOutput, TContext> {
        description = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun metadata(value: Map<String, JsonElement>): ProviderToolFactoryOptionsBuilder<TInput, TOutput, TContext> {
        metadata = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun execute(value: (suspend ToolExecutionContext<TContext>.(TInput) -> TOutput)?): ProviderToolFactoryOptionsBuilder<TInput, TOutput, TContext> {
        execute = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun needsApproval(value: (suspend (input: TInput, options: ToolPredicateOptions<TContext>) -> Boolean)?): ProviderToolFactoryOptionsBuilder<TInput, TOutput, TContext> {
        needsApproval = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun toModelOutput(value: ((TOutput, ToolPredicateOptions<TContext>) -> ToolResultOutput)?): ProviderToolFactoryOptionsBuilder<TInput, TOutput, TContext> {
        toModelOutput = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun onInputStart(value: (suspend (streamingId: String) -> Unit)?): ProviderToolFactoryOptionsBuilder<TInput, TOutput, TContext> {
        onInputStart = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun onInputDelta(value: (suspend (streamingId: String, delta: String) -> Unit)?): ProviderToolFactoryOptionsBuilder<TInput, TOutput, TContext> {
        onInputDelta = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun onInputAvailable(value: (suspend (toolCallId: String, input: TInput) -> Unit)?): ProviderToolFactoryOptionsBuilder<TInput, TOutput, TContext> {
        onInputAvailable = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun build(): ProviderToolFactoryOptions<TInput, TOutput, TContext> =
        ProviderToolFactoryOptions(
            outputSerializer = requireNotNull(outputSerializer) {
                "ProviderToolFactoryOptions.outputSerializer is required"
            },
            outputSchema = outputSchema,
            args = args,
            name = name,
            description = description,
            metadata = metadata,
            execute = execute,
            needsApproval = needsApproval,
            toModelOutput = toModelOutput,
            onInputStart = onInputStart,
            onInputDelta = onInputDelta,
            onInputAvailable = onInputAvailable,
        )
}


/** @since 0.3.0-beta01 */
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
    ): Tool<TInput, TOutput, TContext> = ProviderTools.buildProviderTool(
        id = id,
        inputSerializer = inputSerializer,
        inputSchema = inputSchema,
        defaultDescription = defaultDescription,
        supportsDeferredResults = false,
        options = options,
    )
}

/** @since 0.3.0-beta01 */
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
            ProviderToolFactoryOptions<TInput, TOutput, TContext> {
                outputSerializer(outputSerializer)
                outputSchema(outputSchema)
            },
    ): Tool<TInput, TOutput, TContext> = create(options)

    /** @since 0.3.0-beta01 */
    public fun create(
        options: ProviderToolFactoryOptions<TInput, TOutput, TContext> =
            ProviderToolFactoryOptions<TInput, TOutput, TContext> {
                outputSerializer(outputSerializer)
                outputSchema(outputSchema)
            },
    ): Tool<TInput, TOutput, TContext> = ProviderTools.buildProviderTool(
        id = id,
        inputSerializer = inputSerializer,
        inputSchema = inputSchema,
        defaultDescription = defaultDescription,
        supportsDeferredResults = supportsDeferredResults,
        options = ProviderToolFactoryOptions<TInput, TOutput, TContext> {
            outputSerializer(outputSerializer)
            outputSchema(outputSchema)
            args(options.args)
            name(options.name)
            description(options.description)
            metadata(options.metadata)
            execute(options.execute)
            needsApproval(options.needsApproval)
            toModelOutput(options.toModelOutput)
            onInputStart(options.onInputStart)
            onInputDelta(options.onInputDelta)
            onInputAvailable(options.onInputAvailable)
        },
    )
}

/** @since 0.3.0-beta01 */
public object ProviderTools {
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

    internal fun <TInput, TOutput, TContext> buildProviderTool(
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
            schema = ToolSchema(
                name = resolvedName,
                description = options.description ?: defaultDescription,
                inputExamples = emptyList(),
                metadata = options.metadata + buildProviderToolMetadata(
                    id = id,
                    args = options.args,
                    inputSchema = inputSchema,
                    outputSchema = options.outputSchema,
                    supportsDeferredResults = supportsDeferredResults,
                ),
                providerExecuted = true,
                providerOptions = ProviderOptions.None,
            ),
            inputSerializer = inputSerializer,
            outputSerializer = options.outputSerializer,
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

    private fun buildProviderToolMetadata(
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
}

