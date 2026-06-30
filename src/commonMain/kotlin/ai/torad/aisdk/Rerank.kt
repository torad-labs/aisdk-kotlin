package ai.torad.aisdk

import dev.drewhamilton.poko.Poko

public interface RerankingModel {
    public val modelId: String
    public val provider: String
        get() = "unknown"

    public suspend fun rerank(params: RerankingParams): RerankingModelResult
}

public class RerankingParams internal constructor(
    public val query: String,
    public val documents: List<String>,
    public val topN: Int? = null,
    public val providerOptions: ProviderOptions = ProviderOptions.None,
    public val headers: Map<String, String> = emptyMap(),
    public val abortSignal: AbortSignal = AbortSignalNever,
)

public class RerankingParamsBuilder internal constructor() {
    private var query: String? = null
    private var documents: List<String>? = null
    private var topN: Int? = null
    private var providerOptions: ProviderOptions = ProviderOptions.None
    private var headers: Map<String, String> = emptyMap()
    private var abortSignal: AbortSignal = AbortSignalNever

    public fun query(value: String) {
        query = value
    }

    public fun documents(value: List<String>) {
        documents = value
    }

    public fun topN(value: Int?) {
        topN = value
    }

    public fun providerOptions(value: ProviderOptions) {
        providerOptions = value
    }

    public fun headers(value: Map<String, String>) {
        headers = value
    }

    public fun abortSignal(value: AbortSignal) {
        abortSignal = value
    }

    internal fun build(): RerankingParams =
        RerankingParams(
            query = requireNotNull(query) { "RerankingParams.query is required" },
            documents = requireNotNull(documents) { "RerankingParams.documents is required" },
            topN = topN,
            providerOptions = providerOptions,
            headers = headers,
            abortSignal = abortSignal,
        )
}

public fun RerankingParams(
    block: RerankingParamsBuilder.() -> Unit = {},
): RerankingParams =
    RerankingParamsBuilder().apply(block).build()

@Poko
public class RerankedItem<T>(
    public val value: T,
    public val score: Float,
    public val index: Int,
)

@Poko
public class RerankingModelResult(
    public val results: List<RerankedItem<String>>,
    public val usage: Usage = Usage(),
    public val warnings: List<CallWarning> = emptyList(),
    public val response: LanguageModelResponseMetadata = LanguageModelResponseMetadata(),
    public val providerMetadata: ProviderMetadata = ProviderMetadata.None,
)

@Poko
public class RerankResult<T>(
    public val results: List<RerankedItem<T>>,
    /** The documents that were submitted for reranking, in their original order. */
    public val originalDocuments: List<T> = emptyList(),
    public val usage: Usage = Usage(),
    public val warnings: List<CallWarning> = emptyList(),
    public val response: LanguageModelResponseMetadata = LanguageModelResponseMetadata(),
    public val providerMetadata: ProviderMetadata = ProviderMetadata.None,
) {
    /**
     * The reranked documents in descending relevance order (fewer than
     * [originalDocuments] when a `topN` limit was applied). Convenience accessor
     * over [results]'s values — matches upstream's `rerankedDocuments`.
     */
    public val rerankedDocuments: List<T> get() = results.map { it.value }
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
            model.rerank(
                RerankingParams {
                    query(query)
                    documents(documents)
                    topN(topN)
                    providerOptions(providerOptions)
                    headers(headers)
                    abortSignal(abortSignal)
                },
            )
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
