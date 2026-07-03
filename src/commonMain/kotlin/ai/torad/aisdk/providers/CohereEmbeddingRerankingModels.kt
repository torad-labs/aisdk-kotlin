@file:OptIn(ai.torad.aisdk.LowLevelLanguageModelApi::class)

package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import io.ktor.client.HttpClient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

private const val COHERE_MAX_EMBEDDINGS_PER_CALL: Int = 96

internal class CohereEmbeddingModel(
    private val client: HttpClient,
    private val settings: CohereProviderSettings,
    override val modelId: String,
) : EmbeddingModel {
    override val provider: String = "cohere.textEmbedding"
    override val maxEmbeddingsPerCall: Int = COHERE_MAX_EMBEDDINGS_PER_CALL
    override val supportsParallelCalls: Boolean = true

    override suspend fun embed(params: EmbeddingModelCallParams): EmbeddingModelResult {
        if (params.values.size > COHERE_MAX_EMBEDDINGS_PER_CALL) {
            throw TooManyEmbeddingValuesForCallError(
                provider = provider,
                modelId = modelId,
                maxEmbeddingsPerCall = COHERE_MAX_EMBEDDINGS_PER_CALL,
                values = params.values,
            )
        }
        val options = settings.cohereOptions(params.providerOptions)
        val body = buildJsonObject {
            put("model", JsonPrimitive(modelId))
            put("texts", JsonArray(params.values.map(::JsonPrimitive)))
            put("embedding_types", JsonArray(listOf(JsonPrimitive("float"))))
            put("input_type", options["inputType"] ?: JsonPrimitive("search_query"))
            (options["truncate"] ?: params.truncate?.let { JsonPrimitive(if (it) "END" else "NONE") })?.let {
                put(
                    "truncate",
                    it
                )
            }
            options["outputDimension"]?.let { put("output_dimension", it) }
        }
        val response = settings.coherePostJson(
            client = client,
            url = "${settings.baseURL.trimEnd('/')}/embed",
            body = body,
            headers = settings.cohereHeaders(params.headers),
        )
        val value = response.value.jsonObject
        val embeddings = ((JsonAccess.obj(value, "embeddings"))?.get("float") as? JsonArray).orEmpty()
            .map { row -> (row as? JsonArray).orEmpty().map { WireDecoder.embeddingFloat(it, provider) } }
        val billedUnits = ((JsonAccess.obj(value, "meta"))?.get("billed_units") as? JsonObject)
        val usage = (billedUnits?.get("input_tokens") as? JsonPrimitive)?.intOrNull ?: 0
        return EmbeddingModelResult(
            embeddings = embeddings,
            usage = EmbeddingUsage(tokens = usage, raw = value["meta"]),
            request = LanguageModelRequestMetadata(body),
            response = LanguageModelResponseMetadata(
                id = (value["id"] as? JsonPrimitive)?.contentOrNull,
                headers = response.headers,
                body = response.value,
            ),
        )
    }
}

internal class CohereRerankingModel(
    private val client: HttpClient,
    private val settings: CohereProviderSettings,
    override val modelId: String,
) : RerankingModel {
    override val provider: String = "cohere.reranking"

    override suspend fun rerank(params: RerankingParams): RerankingModelResult {
        val options = settings.cohereOptions(params.providerOptions)
        val body = buildJsonObject {
            put("model", JsonPrimitive(modelId))
            put("query", JsonPrimitive(params.query))
            put("documents", JsonArray(params.documents.map(::JsonPrimitive)))
            params.topN?.let { put("top_n", JsonPrimitive(it)) }
            options["maxTokensPerDoc"]?.let { put("max_tokens_per_doc", it) }
            options["priority"]?.let { put("priority", it) }
        }
        val response = settings.coherePostJson(
            client = client,
            url = "${settings.baseURL.trimEnd('/')}/rerank",
            body = body,
            headers = settings.cohereHeaders(params.headers),
        )
        val value = response.value.jsonObject
        val results = (JsonAccess.arr(value, "results")).orEmpty().mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val index = (obj["index"] as? JsonPrimitive)?.intOrNull ?: 0
            RerankedItem(
                value = params.documents.getOrElse(index) { "" },
                score = (obj["relevance_score"] as? JsonPrimitive)?.floatOrNull ?: 0f,
                index = index,
            )
        }
        val billedUnits = ((JsonAccess.obj(value, "meta"))?.get("billed_units") as? JsonObject)
        val searchUnits = (billedUnits?.get("search_units") as? JsonPrimitive)?.intOrNull ?: 0
        return RerankingModelResult(
            results = results,
            usage = Usage.of(promptTokens = searchUnits, completionTokens = 0),
            response = LanguageModelResponseMetadata(
                id = (value["id"] as? JsonPrimitive)?.contentOrNull,
                headers = response.headers,
                body = response.value,
            ),
        )
    }
}
