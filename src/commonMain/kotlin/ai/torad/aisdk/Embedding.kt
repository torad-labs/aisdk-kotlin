package ai.torad.aisdk

import kotlinx.serialization.json.JsonElement

public interface EmbeddingModel {
    public val modelId: String
    public val provider: String
        get() = "unknown"

    public suspend fun embed(params: EmbeddingModelCallParams): EmbeddingModelResult
}

public data class EmbeddingModelCallParams(
    val values: List<String>,
    val maxEmbeddingsPerCall: Int? = null,
    val truncate: Boolean? = null,
    val providerOptions: Map<String, JsonElement> = emptyMap(),
    val abortSignal: AbortSignal = AbortSignalNever,
    val headers: Map<String, String> = emptyMap(),
)

public data class EmbeddingModelResult(
    val embeddings: List<List<Float>>,
    val usage: EmbeddingUsage = EmbeddingUsage(),
    val warnings: List<CallWarning> = emptyList(),
    val request: LanguageModelRequestMetadata = LanguageModelRequestMetadata(),
    val response: LanguageModelResponseMetadata = LanguageModelResponseMetadata(),
    val providerMetadata: Map<String, JsonElement> = emptyMap(),
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
    val providerMetadata: Map<String, JsonElement> = emptyMap(),
)

public data class EmbedManyResult<TValue>(
    val values: List<TValue>,
    val embeddings: List<List<Float>>,
    val usage: EmbeddingUsage,
    val warnings: List<CallWarning> = emptyList(),
    val request: LanguageModelRequestMetadata = LanguageModelRequestMetadata(),
    val response: LanguageModelResponseMetadata = LanguageModelResponseMetadata(),
    val providerMetadata: Map<String, JsonElement> = emptyMap(),
)

public suspend fun embed(
    model: EmbeddingModel,
    value: String,
    providerOptions: Map<String, JsonElement> = emptyMap(),
    abortSignal: AbortSignal = AbortSignalNever,
    headers: Map<String, String> = emptyMap(),
): EmbedResult<String> {
    val result = model.embed(
        EmbeddingModelCallParams(
            values = listOf(value),
            providerOptions = providerOptions,
            abortSignal = abortSignal,
            headers = headers,
        ),
    )
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
    providerOptions: Map<String, JsonElement> = emptyMap(),
    abortSignal: AbortSignal = AbortSignalNever,
    headers: Map<String, String> = emptyMap(),
): EmbedManyResult<String> {
    require(values.isNotEmpty()) { "embedMany: values must not be empty" }
    val batches = maxEmbeddingsPerCall?.let { splitArray(values, it) } ?: listOf(values)
    val allEmbeddings = mutableListOf<List<Float>>()
    var usage = EmbeddingUsage()
    val warnings = mutableListOf<CallWarning>()
    var request = LanguageModelRequestMetadata()
    var response = LanguageModelResponseMetadata()
    var metadata = emptyMap<String, JsonElement>()
    for (batch in batches) {
        abortSignal.throwIfAborted()
        val result = model.embed(
            EmbeddingModelCallParams(
                values = batch,
                maxEmbeddingsPerCall = maxEmbeddingsPerCall,
                providerOptions = providerOptions,
                abortSignal = abortSignal,
                headers = headers,
            ),
        )
        require(result.embeddings.size == batch.size) {
            "Embedding model returned ${result.embeddings.size} embeddings for ${batch.size} values"
        }
        allEmbeddings += result.embeddings
        usage = EmbeddingUsage(tokens = usage.tokens + result.usage.tokens, raw = result.usage.raw ?: usage.raw)
        warnings += result.warnings
        request = result.request
        response = result.response
        metadata = result.providerMetadata.ifEmpty { metadata }
    }
    return EmbedManyResult(values, allEmbeddings, usage, warnings, request, response, metadata)
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

public fun wrapEmbeddingModel(
    model: EmbeddingModel,
    middlewares: List<EmbeddingModelMiddleware>,
): EmbeddingModel {
    if (middlewares.isEmpty()) return model
    return WrappedEmbeddingModel(model, middlewares)
}

public fun defaultEmbeddingSettingsMiddleware(
    maxEmbeddingsPerCall: Int? = null,
    truncate: Boolean? = null,
    providerOptions: Map<String, JsonElement> = emptyMap(),
    headers: Map<String, String> = emptyMap(),
): EmbeddingModelMiddleware = object : EmbeddingModelMiddleware {
    override suspend fun wrapEmbed(context: EmbeddingMiddlewareCallContext): EmbeddingModelResult =
        context.doEmbed(
            context.params.copy(
                maxEmbeddingsPerCall = context.params.maxEmbeddingsPerCall ?: maxEmbeddingsPerCall,
                truncate = context.params.truncate ?: truncate,
                providerOptions = providerOptions + context.params.providerOptions,
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
    private val chainEmbed: suspend (EmbeddingModelCallParams) -> EmbeddingModelResult

    init {
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
        chainEmbed = doEmbed
    }

    override suspend fun embed(params: EmbeddingModelCallParams): EmbeddingModelResult =
        chainEmbed(params)
}
