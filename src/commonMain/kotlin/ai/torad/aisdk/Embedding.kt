package ai.torad.aisdk

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonElement

public interface EmbeddingModel {
    public val modelId: String
    public val provider: String
        get() = "unknown"

    /**
     * How many values this model accepts in a single call, if limited.
     * [embedMany] consults this to auto-split large requests into batches when the
     * caller doesn't pass an explicit `maxEmbeddingsPerCall`. Null = no limit.
     */
    public val maxEmbeddingsPerCall: Int?
        get() = null

    /**
     * Whether the model permits its embedding batches to run concurrently.
     * When true, [embedMany] fans batches out (bounded by `maxParallelCalls`)
     * instead of running them serially.
     */
    public val supportsParallelCalls: Boolean
        get() = false

    public suspend fun embed(params: EmbeddingModelCallParams): EmbeddingModelResult
}

public data class EmbeddingModelCallParams(
    val values: List<String>,
    val maxEmbeddingsPerCall: Int? = null,
    val truncate: Boolean? = null,
    val providerOptions: ProviderOptions = ProviderOptions.None,
    val abortSignal: AbortSignal = AbortSignalNever,
    val headers: Map<String, String> = emptyMap(),
)

public data class EmbeddingModelResult(
    val embeddings: List<List<Float>>,
    val usage: EmbeddingUsage = EmbeddingUsage(),
    val warnings: List<CallWarning> = emptyList(),
    val request: LanguageModelRequestMetadata = LanguageModelRequestMetadata(),
    val response: LanguageModelResponseMetadata = LanguageModelResponseMetadata(),
    val providerMetadata: ProviderMetadata = ProviderMetadata.None,
)

public data class EmbeddingUsage(
    val tokens: Int = 0,
    val raw: JsonElement? = null,
)

public data class EmbedResult<TValue>(
    val value: TValue,
    val embedding: List<Float>,
    val usage: EmbeddingUsage,
    val warnings: List<CallWarning> = emptyList(),
    val request: LanguageModelRequestMetadata = LanguageModelRequestMetadata(),
    val response: LanguageModelResponseMetadata = LanguageModelResponseMetadata(),
    val providerMetadata: ProviderMetadata = ProviderMetadata.None,
)

public data class EmbedManyResult<TValue>(
    val values: List<TValue>,
    val embeddings: List<List<Float>>,
    val usage: EmbeddingUsage,
    val warnings: List<CallWarning> = emptyList(),
    val request: LanguageModelRequestMetadata = LanguageModelRequestMetadata(),
    val response: LanguageModelResponseMetadata = LanguageModelResponseMetadata(),
    /** Per-batch response metadata, in batch order — one entry per underlying model call. */
    val responses: List<LanguageModelResponseMetadata> = emptyList(),
    val providerMetadata: ProviderMetadata = ProviderMetadata.None,
)

internal val retryableApiError: (Throwable) -> Boolean = {
    (it as? APICallError)?.isRetryable == true || (it as? GatewayError)?.isRetryable == true
}

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
        val result = RetryPolicy(maxRetries = maxRetries).execute(retryableApiError) {
            model.embed(
                EmbeddingModelCallParams(
                    values = listOf(value),
                    providerOptions = providerOptions,
                    abortSignal = abortSignal,
                    headers = headers,
                ),
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

    public suspend fun embedMany(
        model: EmbeddingModel,
        values: List<String>,
        maxEmbeddingsPerCall: Int? = null,
        maxParallelCalls: Int = Int.MAX_VALUE,
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
            val result = RetryPolicy(maxRetries = maxRetries).execute(retryableApiError) {
                model.embed(
                    EmbeddingModelCallParams(
                        values = batch,
                        maxEmbeddingsPerCall = maxEmbeddingsPerCall,
                        providerOptions = providerOptions,
                        abortSignal = abortSignal,
                        headers = headers,
                    ),
                )
            }
            require(result.embeddings.size == batch.size) {
                "Embedding model returned ${result.embeddings.size} embeddings for ${batch.size} values"
            }
            return result
        }

        val results: List<EmbeddingModelResult> = if (model.supportsParallelCalls && batches.size > 1) {
            val permits = Semaphore(maxParallelCalls.coerceAtLeast(1))
            coroutineScope {
                batches.map { batch -> async { permits.withPermit { embedBatch(batch) } } }.awaitAll()
            }
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
            providerMetadata = results.fold<EmbeddingModelResult, ProviderMetadata>(ProviderMetadata.None) { acc, r -> acc + r.providerMetadata },
        )
    }
}

public interface EmbeddingModelMiddleware {
    public suspend fun wrapEmbed(context: EmbeddingMiddlewareCallContext): EmbeddingModelResult =
        context.doEmbed(context.params)
}

public data class EmbeddingMiddlewareCallContext(
    val params: EmbeddingModelCallParams,
    val model: EmbeddingModel,
    val doEmbed: suspend (EmbeddingModelCallParams) -> EmbeddingModelResult,
)

public fun WrapEmbeddingModel(
    model: EmbeddingModel,
    middlewares: List<EmbeddingModelMiddleware>,
): EmbeddingModel {
    if (middlewares.isEmpty()) return model
    return WrappedEmbeddingModel(model, middlewares)
}

public fun DefaultEmbeddingSettingsMiddleware(
    maxEmbeddingsPerCall: Int? = null,
    truncate: Boolean? = null,
    providerOptions: ProviderOptions = ProviderOptions.None,
    headers: Map<String, String> = emptyMap(),
): EmbeddingModelMiddleware = object : EmbeddingModelMiddleware {
    override suspend fun wrapEmbed(context: EmbeddingMiddlewareCallContext): EmbeddingModelResult =
        context.doEmbed(
            context.params.copy(
                maxEmbeddingsPerCall = context.params.maxEmbeddingsPerCall ?: maxEmbeddingsPerCall,
                truncate = context.params.truncate ?: truncate,
                providerOptions = providerOptions.mergedWith(context.params.providerOptions),
                headers = headers + context.params.headers,
            ),
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
