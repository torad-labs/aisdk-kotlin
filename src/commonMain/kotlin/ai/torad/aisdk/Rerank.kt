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
    val providerOptions: Map<String, JsonElement> = emptyMap(),
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
    val usage: Usage = Usage(),
    val warnings: List<CallWarning> = emptyList(),
    val response: LanguageModelResponseMetadata = LanguageModelResponseMetadata(),
    val providerMetadata: Map<String, JsonElement> = emptyMap(),
)

public suspend fun rerank(
    model: RerankingModel,
    query: String,
    documents: List<String>,
    topN: Int? = null,
    providerOptions: Map<String, JsonElement> = emptyMap(),
    headers: Map<String, String> = emptyMap(),
    abortSignal: AbortSignal = AbortSignalNever,
): RerankResult<String> {
    require(query.isNotBlank()) { "rerank: query must not be blank" }
    require(documents.isNotEmpty()) { "rerank: documents must not be empty" }
    topN?.let { require(it > 0) { "rerank: topN must be > 0" } }
    val result = model.rerank(
        RerankingParams(query, documents, topN, providerOptions, headers, abortSignal),
    )
    val ordered = result.results.sortedByDescending { it.score }
        .let { if (topN == null) it else it.take(topN) }
    return RerankResult(ordered, result.usage, result.warnings, result.response, result.providerMetadata)
}
