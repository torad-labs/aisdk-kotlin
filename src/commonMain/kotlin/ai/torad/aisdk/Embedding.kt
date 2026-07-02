package ai.torad.aisdk

import dev.drewhamilton.poko.Poko
import kotlinx.serialization.json.JsonElement

/** @since 0.3.0-beta01 */
public interface EmbeddingModel {
    /** @since 0.3.0-beta01 */
    public val modelId: String

    /** @since 0.3.0-beta01 */
    public val provider: String
        get() = "unknown"

    /**
     * How many values this model accepts in a single call, if limited.
     * `embedMany` consults this to auto-split large requests into batches when the
     * caller doesn't pass an explicit `maxEmbeddingsPerCall`. Null = no limit.
     * @since 0.3.0-beta01
     */
    public val maxEmbeddingsPerCall: Int?
        get() = null

    /**
     * Whether the model permits its embedding batches to run concurrently.
     * When true, `embedMany` fans batches out (bounded by `maxParallelCalls`)
     * instead of running them serially.
     * @since 0.3.0-beta01
     */
    public val supportsParallelCalls: Boolean
        get() = false

    public suspend fun embed(params: EmbeddingModelCallParams): EmbeddingModelResult
}

@Poko
/** @since 0.3.0-beta01 */
public class EmbeddingModelCallParams internal constructor(
    /** @since 0.3.0-beta01 */
    public val values: List<String>,
    /** @since 0.3.0-beta01 */
    public val maxEmbeddingsPerCall: Int? = null,
    /** @since 0.3.0-beta01 */
    public val truncate: Boolean? = null,
    /** @since 0.3.0-beta01 */
    public val providerOptions: ProviderOptions = ProviderOptions.None,
    /** @since 0.3.0-beta01 */
    public val abortSignal: AbortSignal = AbortSignalNever,
    /** @since 0.3.0-beta01 */
    public val headers: Map<String, String> = emptyMap(),
) {
    /** @since 0.3.0-beta01 */
    public fun toBuilder(): EmbeddingModelCallParamsBuilder =
        EmbeddingModelCallParamsBuilder().also {
            it.values(values)
            it.maxEmbeddingsPerCall(maxEmbeddingsPerCall)
            it.truncate(truncate)
            it.providerOptions(providerOptions)
            it.abortSignal(abortSignal)
            it.headers(headers)
        }
}

@AiSdkDsl
/** @since 0.3.0-beta01 */
public class EmbeddingModelCallParamsBuilder internal constructor() {
    private var values: List<String>? = null
    private var maxEmbeddingsPerCall: Int? = null
    private var truncate: Boolean? = null
    private var providerOptions: ProviderOptions = ProviderOptions.None
    private var abortSignal: AbortSignal = AbortSignalNever
    private var headers: Map<String, String> = emptyMap()

    /** @since 0.3.0-beta01 */
    public fun values(value: List<String>): EmbeddingModelCallParamsBuilder = apply {
        values = value
    }

    /** @since 0.3.0-beta01 */
    public fun maxEmbeddingsPerCall(value: Int?): EmbeddingModelCallParamsBuilder = apply {
        maxEmbeddingsPerCall = value
    }

    /** @since 0.3.0-beta01 */
    public fun truncate(value: Boolean?): EmbeddingModelCallParamsBuilder = apply {
        truncate = value
    }

    /** @since 0.3.0-beta01 */
    public fun providerOptions(value: ProviderOptions): EmbeddingModelCallParamsBuilder = apply {
        providerOptions = value
    }

    /** @since 0.3.0-beta01 */
    public fun abortSignal(value: AbortSignal): EmbeddingModelCallParamsBuilder = apply {
        abortSignal = value
    }

    /** @since 0.3.0-beta01 */
    public fun headers(value: Map<String, String>): EmbeddingModelCallParamsBuilder = apply {
        headers = value
    }

    /** @since 0.3.0-beta01 */
    public fun build(): EmbeddingModelCallParams =
        EmbeddingModelCallParams(
            values = requireNotNull(values) { "EmbeddingModelCallParams.values is required" },
            maxEmbeddingsPerCall = maxEmbeddingsPerCall,
            truncate = truncate,
            providerOptions = providerOptions,
            abortSignal = abortSignal,
            headers = headers,
        )
}

/** @since 0.3.0-beta01 */
public fun EmbeddingModelCallParams(
    block: EmbeddingModelCallParamsBuilder.() -> Unit,
): EmbeddingModelCallParams =
    EmbeddingModelCallParamsBuilder().apply(block).build()

@Poko
/** @since 0.3.0-beta01 */
public class EmbeddingModelResult(
    /** @since 0.3.0-beta01 */
    public val embeddings: List<List<Float>>,
    /** @since 0.3.0-beta01 */
    public val usage: EmbeddingUsage = EmbeddingUsage(),
    /** @since 0.3.0-beta01 */
    public val warnings: List<CallWarning> = emptyList(),
    /** @since 0.3.0-beta01 */
    public val request: LanguageModelRequestMetadata = LanguageModelRequestMetadata(),
    /** @since 0.3.0-beta01 */
    public val response: LanguageModelResponseMetadata = LanguageModelResponseMetadata(),
    /** @since 0.3.0-beta01 */
    public val providerMetadata: ProviderMetadata = ProviderMetadata.None,
)

@Poko
/** @since 0.3.0-beta01 */
public class EmbeddingUsage(
    /** @since 0.3.0-beta01 */
    public val tokens: Int = 0,
    /** @since 0.3.0-beta01 */
    public val raw: JsonElement? = null,
)

@Poko
/** @since 0.3.0-beta01 */
public class EmbedResult<TValue>(
    /** @since 0.3.0-beta01 */
    public val value: TValue,
    /** @since 0.3.0-beta01 */
    public val embedding: List<Float>,
    /** @since 0.3.0-beta01 */
    public val usage: EmbeddingUsage,
    /** @since 0.3.0-beta01 */
    public val warnings: List<CallWarning> = emptyList(),
    /** @since 0.3.0-beta01 */
    public val request: LanguageModelRequestMetadata = LanguageModelRequestMetadata(),
    /** @since 0.3.0-beta01 */
    public val response: LanguageModelResponseMetadata = LanguageModelResponseMetadata(),
    /** @since 0.3.0-beta01 */
    public val providerMetadata: ProviderMetadata = ProviderMetadata.None,
)

@Poko
/** @since 0.3.0-beta01 */
public class EmbedManyResult<TValue>(
    /** @since 0.3.0-beta01 */
    public val values: List<TValue>,
    /** @since 0.3.0-beta01 */
    public val embeddings: List<List<Float>>,
    /** @since 0.3.0-beta01 */
    public val usage: EmbeddingUsage,
    /** @since 0.3.0-beta01 */
    public val warnings: List<CallWarning> = emptyList(),
    /** @since 0.3.0-beta01 */
    public val request: LanguageModelRequestMetadata = LanguageModelRequestMetadata(),
    /** @since 0.3.0-beta01 */
    public val response: LanguageModelResponseMetadata = LanguageModelResponseMetadata(),
    /**
     * Per-batch response metadata, in batch order — one entry per underlying model call.
     * @since 0.3.0-beta01
     */
    public val responses: List<LanguageModelResponseMetadata> = emptyList(),
    /** @since 0.3.0-beta01 */
    public val providerMetadata: ProviderMetadata = ProviderMetadata.None,
)

internal val retryableApiError: (Throwable) -> Boolean = {
    (it as? APICallError)?.isRetryable == true || (it as? GatewayError)?.isRetryable == true
}

/** @since 0.3.0-beta01 */
public object Embedding {

    private fun <T> List<T>.splitArray(chunkSize: Int): List<List<T>> {
        require(chunkSize > 0) { "chunkSize must be > 0" }
        return chunked(chunkSize)
    }

    public suspend fun embed(
        model: EmbeddingModel,
        value: String,
        providerOptions: ProviderOptions = ProviderOptions.None,
        abortSignal: AbortSignal = AbortSignalNever,
        headers: Map<String, String> = emptyMap(),
        maxRetries: Int = 2,
    ): EmbedResult<String> {
        val result = RetryPolicy {
            maxRetries(maxRetries)
        }.execute(retryableApiError) {
            model.embed(
                EmbeddingModelCallParams {
                    values(listOf(value))
                    providerOptions(providerOptions)
                    abortSignal(abortSignal)
                    headers(headers)
                },
            )
        }
        val embedding = result.embeddings.singleOrNull()
            ?: throw NoOutputGeneratedError("Embedding model returned ${result.embeddings.size} embeddings for one value")
        return EmbedResult(
            value = value,
            embedding = embedding,
            usage = result.usage,
            warnings = result.warnings,
            request = result.request,
            response = result.response,
            providerMetadata = result.providerMetadata,
        )
    }

    @Suppress("LongParameterList")
    public suspend fun embedMany(
        model: EmbeddingModel,
        values: List<String>,
        maxEmbeddingsPerCall: Int? = null,
        maxParallelCalls: Int = DEFAULT_MAX_PARALLEL_CALLS,
        providerOptions: ProviderOptions = ProviderOptions.None,
        abortSignal: AbortSignal = AbortSignalNever,
        headers: Map<String, String> = emptyMap(),
        maxRetries: Int = 2,
    ): EmbedManyResult<String> {
        require(values.isNotEmpty()) { "embedMany: values must not be empty" }
        val batchSize = maxEmbeddingsPerCall ?: model.maxEmbeddingsPerCall
        val batches = batchSize?.let { values.splitArray(it) } ?: listOf(values)

        suspend fun embedBatch(batch: List<String>): EmbeddingModelResult {
            abortSignal.throwIfAborted()
            val result = RetryPolicy {
                maxRetries(maxRetries)
            }.execute(retryableApiError) {
                model.embed(
                    EmbeddingModelCallParams {
                        values(batch)
                        maxEmbeddingsPerCall(maxEmbeddingsPerCall)
                        providerOptions(providerOptions)
                        abortSignal(abortSignal)
                        headers(headers)
                    },
                )
            }
            require(result.embeddings.size == batch.size) {
                "Embedding model returned ${result.embeddings.size} embeddings for ${batch.size} values"
            }
            return result
        }

        val results: List<EmbeddingModelResult> = if (model.supportsParallelCalls && batches.size > 1) {
            BoundedParallel.map(batches, maxParallelCalls) { embedBatch(it) }
        } else {
            batches.map { embedBatch(it) }
        }

        val allEmbeddings = results.flatMap { it.embeddings }
        val usage = EmbeddingUsage(
            tokens = results.sumOf { it.usage.tokens },
            raw = results.firstNotNullOfOrNull { it.usage.raw },
        )
        return EmbedManyResult(
            values = values,
            embeddings = allEmbeddings,
            usage = usage,
            warnings = results.flatMap { it.warnings },
            request = results.firstOrNull()?.request ?: LanguageModelRequestMetadata(),
            response = results.lastOrNull()?.response ?: LanguageModelResponseMetadata(),
            responses = results.map { it.response },
            providerMetadata = results.fold<EmbeddingModelResult, ProviderMetadata>(
                ProviderMetadata.None
            ) { acc, r -> acc + r.providerMetadata },
        )
    }
}

/** @since 0.3.0-beta01 */
public interface EmbeddingModelMiddleware {
    public suspend fun wrapEmbed(context: EmbeddingMiddlewareCallContext): EmbeddingModelResult =
        context.doEmbed(context.params)
}

@Poko
/** @since 0.3.0-beta01 */
public class EmbeddingMiddlewareCallContext(
    /** @since 0.3.0-beta01 */
    public val params: EmbeddingModelCallParams,
    /** @since 0.3.0-beta01 */
    public val model: EmbeddingModel,
    /** @since 0.3.0-beta01 */
    public val doEmbed: suspend (EmbeddingModelCallParams) -> EmbeddingModelResult,
)

/** @since 0.3.0-beta01 */
public fun WrapEmbeddingModel(
    model: EmbeddingModel,
    middlewares: List<EmbeddingModelMiddleware>,
): EmbeddingModel {
    if (middlewares.isEmpty()) return model
    return WrappedEmbeddingModel(model, middlewares)
}

/** @since 0.3.0-beta01 */
public fun DefaultEmbeddingSettingsMiddleware(
    maxEmbeddingsPerCall: Int? = null,
    truncate: Boolean? = null,
    providerOptions: ProviderOptions = ProviderOptions.None,
    headers: Map<String, String> = emptyMap(),
): EmbeddingModelMiddleware = object : EmbeddingModelMiddleware {
    override suspend fun wrapEmbed(context: EmbeddingMiddlewareCallContext): EmbeddingModelResult =
        context.doEmbed(
            context.params.toBuilder()
                .maxEmbeddingsPerCall(context.params.maxEmbeddingsPerCall ?: maxEmbeddingsPerCall)
                .truncate(context.params.truncate ?: truncate)
                .providerOptions(providerOptions.mergedWith(context.params.providerOptions))
                .headers(headers + context.params.headers)
                .build(),
        )
}

private class WrappedEmbeddingModel(
    private val inner: EmbeddingModel,
    middlewares: List<EmbeddingModelMiddleware>,
) : EmbeddingModel {
    override val modelId: String = inner.modelId
    override val provider: String = inner.provider
    override val maxEmbeddingsPerCall: Int? = inner.maxEmbeddingsPerCall
    override val supportsParallelCalls: Boolean = inner.supportsParallelCalls
    private val chainEmbed: suspend (EmbeddingModelCallParams) -> EmbeddingModelResult

    init {
        chainEmbed = buildEmbedChain(middlewares)
    }

    private fun buildEmbedChain(
        middlewares: List<EmbeddingModelMiddleware>,
    ): suspend (EmbeddingModelCallParams) -> EmbeddingModelResult {
        var doEmbed: suspend (EmbeddingModelCallParams) -> EmbeddingModelResult = inner::embed
        for (middleware in middlewares.asReversed()) {
            val downstream = doEmbed
            doEmbed = { params ->
                middleware.wrapEmbed(
                    EmbeddingMiddlewareCallContext(
                        params = params,
                        model = this,
                        doEmbed = downstream,
                    ),
                )
            }
        }
        return doEmbed
    }

    override suspend fun embed(params: EmbeddingModelCallParams): EmbeddingModelResult =
        chainEmbed(params)
}
