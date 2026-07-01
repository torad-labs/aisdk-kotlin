package ai.torad.aisdk

import dev.drewhamilton.poko.Poko

/** @since 0.3.0-beta01 */
public interface RerankingModel {
    /** @since 0.3.0-beta01 */
    public val modelId: String
    /** @since 0.3.0-beta01 */
    public val provider: String
        get() = "unknown"

    public suspend fun rerank(params: RerankingParams): RerankingModelResult
}

/** @since 0.3.0-beta01 */
public class RerankingParams internal constructor(
    /** @since 0.3.0-beta01 */
    public val query: String,
    /** @since 0.3.0-beta01 */
    public val documents: List<String>,
    /** @since 0.3.0-beta01 */
    public val topN: Int? = null,
    /** @since 0.3.0-beta01 */
    public val providerOptions: ProviderOptions = ProviderOptions.None,
    /** @since 0.3.0-beta01 */
    public val headers: Map<String, String> = emptyMap(),
    /** @since 0.3.0-beta01 */
    public val abortSignal: AbortSignal = AbortSignalNever,
)

/** @since 0.3.0-beta01 */
public class RerankingParamsBuilder {
    private var query: String? = null
    private var documents: List<String>? = null
    private var topN: Int? = null
    private var providerOptions: ProviderOptions = ProviderOptions.None
    private var headers: Map<String, String> = emptyMap()
    private var abortSignal: AbortSignal = AbortSignalNever

    /** @since 0.3.0-beta01 */
    public fun query(value: String): RerankingParamsBuilder {
        query = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun documents(value: List<String>): RerankingParamsBuilder {
        documents = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun topN(value: Int?): RerankingParamsBuilder {
        topN = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun providerOptions(value: ProviderOptions): RerankingParamsBuilder {
        providerOptions = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun headers(value: Map<String, String>): RerankingParamsBuilder {
        headers = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun abortSignal(value: AbortSignal): RerankingParamsBuilder {
        abortSignal = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun build(): RerankingParams =
        RerankingParams(
            query = requireNotNull(query) { "RerankingParams.query is required" },
            documents = requireNotNull(documents) { "RerankingParams.documents is required" },
            topN = topN,
            providerOptions = providerOptions,
            headers = headers,
            abortSignal = abortSignal,
        )
}

/** @since 0.3.0-beta01 */
public fun RerankingParams(
    block: RerankingParamsBuilder.() -> Unit = {},
): RerankingParams =
    RerankingParamsBuilder().apply(block).build()

@Poko
/** @since 0.3.0-beta01 */
public class RerankedItem<T>(
    /** @since 0.3.0-beta01 */
    public val value: T,
    /** @since 0.3.0-beta01 */
    public val score: Float,
    /** @since 0.3.0-beta01 */
    public val index: Int,
)

@Poko
/** @since 0.3.0-beta01 */
public class RerankingModelResult(
    /** @since 0.3.0-beta01 */
    public val results: List<RerankedItem<String>>,
    /** @since 0.3.0-beta01 */
    public val usage: Usage = Usage(),
    /** @since 0.3.0-beta01 */
    public val warnings: List<CallWarning> = emptyList(),
    /** @since 0.3.0-beta01 */
    public val response: LanguageModelResponseMetadata = LanguageModelResponseMetadata(),
    /** @since 0.3.0-beta01 */
    public val providerMetadata: ProviderMetadata = ProviderMetadata.None,
)

@Poko
/** @since 0.3.0-beta01 */
public class RerankResult<T>(
    /** @since 0.3.0-beta01 */
    public val results: List<RerankedItem<T>>,
    /**
     * The documents that were submitted for reranking, in their original order.
     * @since 0.3.0-beta01
     */
    public val originalDocuments: List<T> = emptyList(),
    /** @since 0.3.0-beta01 */
    public val usage: Usage = Usage(),
    /** @since 0.3.0-beta01 */
    public val warnings: List<CallWarning> = emptyList(),
    /** @since 0.3.0-beta01 */
    public val response: LanguageModelResponseMetadata = LanguageModelResponseMetadata(),
    /** @since 0.3.0-beta01 */
    public val providerMetadata: ProviderMetadata = ProviderMetadata.None,
) {
    /**
     * The reranked documents in descending relevance order (fewer than
     * [originalDocuments] when a `topN` limit was applied). Convenience accessor
     * over [results]'s values — matches upstream's `rerankedDocuments`.
      * @since 0.3.0-beta01
     */
    public val rerankedDocuments: List<T> get() = results.map { it.value }
}

/** @since 0.3.0-beta01 */
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
        val result = RetryPolicy {
            maxRetries(maxRetries)
        }.execute(retryableApiError) {
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
