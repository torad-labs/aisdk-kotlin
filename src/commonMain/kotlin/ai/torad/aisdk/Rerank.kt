package ai.torad.aisdk

import kotlinx.serialization.json.JsonElement

public interface RerankingModel {
    public val modelId: String
    public val provider: String
        get() = "unknown"

    public suspend fun rerank(params: RerankingParams): RerankingModelResult
}

public data class RerankingParams(
    val query: String,
    val documents: List<String>,
    val topN: Int? = null,
    val providerOptions: ProviderOptions = ProviderOptions.None,
    val headers: Map<String, String> = emptyMap(),
    val abortSignal: AbortSignal = AbortSignalNever,
)

public data class RerankedItem<T>(
    val value: T,
    val score: Float,
    val index: Int,
)

public data class RerankingModelResult(
    val results: List<RerankedItem<String>>,
    val usage: Usage = Usage(),
    val warnings: List<CallWarning> = emptyList(),
    val response: LanguageModelResponseMetadata = LanguageModelResponseMetadata(),
    val providerMetadata: Map<String, JsonElement> = emptyMap(),
)

public data class RerankResult<T>(
    val results: List<RerankedItem<T>>,
    /** The documents that were submitted for reranking, in their original order. */
    val originalDocuments: List<T> = emptyList(),
    val usage: Usage = Usage(),
    val warnings: List<CallWarning> = emptyList(),
    val response: LanguageModelResponseMetadata = LanguageModelResponseMetadata(),
    val providerMetadata: Map<String, JsonElement> = emptyMap(),
) {
    /**
     * The reranked documents in descending relevance order (fewer than
     * [originalDocuments] when a `topN` limit was applied). Convenience accessor
     * over [results]'s values — matches upstream's `rerankedDocuments`.
     */
    val rerankedDocuments: List<T> get() = results.map { it.value }
}

public object Reranking {

    public suspend fun rerank(
        model: RerankingModel,
        query: String,
        documents: List<String>,
        topN: Int? = null,
        providerOptions: ProviderOptions = ProviderOptions.None,
        headers: Map<String, String> = emptyMap(),
        abortSignal: AbortSignal = AbortSignalNever,
        maxRetries: Int = 2,
    ): RerankResult<String> {
        require(query.isNotBlank()) { "rerank: query must not be blank" }
        topN?.let { require(it > 0) { "rerank: topN must be > 0" } }
        if (documents.isEmpty()) return RerankResult(results = emptyList(), originalDocuments = emptyList())
        val result = RetryPolicy(maxRetries = maxRetries).execute(retryableApiError) {
            model.rerank(RerankingParams(query, documents, topN, providerOptions, headers, abortSignal))
        }
        return RerankResult(
            results = result.results,
            originalDocuments = documents,
            usage = result.usage,
            warnings = result.warnings,
            response = result.response,
            providerMetadata = result.providerMetadata,
        )
    }
}
