package ai.torad.aisdk

import io.ktor.client.HttpClient
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

const val VOYAGE_VERSION: String = "1.0.4"

typealias VoyageEmbeddingModelId = String
typealias VoyageRerankingModelId = String

@Serializable
data class VoyageEmbeddingModelOptions(
    val inputType: String? = null,
    val truncation: Boolean? = null,
    val outputDimension: Int? = null,
    val outputDtype: String? = null,
)

@Serializable
data class VoyageRerankingModelOptions(
    val returnDocuments: Boolean? = null,
    val truncation: Boolean? = null,
)

@Serializable
data class VoyageProviderSettings(
    val baseURL: String = "https://api.voyageai.com/v1",
    val apiKey: String? = null,
    val headers: Map<String, String> = emptyMap(),
)

interface VoyageProvider : Provider {
    fun embedding(modelId: VoyageEmbeddingModelId): EmbeddingModel
    fun textEmbedding(modelId: VoyageEmbeddingModelId): EmbeddingModel = embedding(modelId)
    fun textEmbeddingModel(modelId: VoyageEmbeddingModelId): EmbeddingModel = embedding(modelId)
    fun reranking(modelId: VoyageRerankingModelId): RerankingModel

    override fun embeddingModel(modelId: String): EmbeddingModel = embedding(modelId)
    override fun rerankingModel(modelId: String): RerankingModel = reranking(modelId)
}

fun createVoyage(
    client: HttpClient,
    settings: VoyageProviderSettings = VoyageProviderSettings(),
): VoyageProvider = DefaultVoyageProvider(client, settings)

val voyage: VoyageProvider = object : VoyageProvider {
    override val providerId: String = "voyage"
    override fun embedding(modelId: String): EmbeddingModel =
        throw AiSdkException("Voyage provider is not configured. Use createVoyage(client, settings).")
    override fun reranking(modelId: String): RerankingModel =
        throw AiSdkException("Voyage provider is not configured. Use createVoyage(client, settings).")
}

private class DefaultVoyageProvider(
    private val client: HttpClient,
    private val settings: VoyageProviderSettings,
) : VoyageProvider {
    override val providerId: String = "voyage"
    override fun embedding(modelId: String): EmbeddingModel = VoyageEmbeddingModel(client, settings, modelId)
    override fun reranking(modelId: String): RerankingModel = VoyageRerankingModel(client, settings, modelId)
    override fun languageModel(modelId: String): LanguageModel = throw NoSuchModelError(providerId, "languageModel", modelId)
    override fun imageModel(modelId: String): ImageModel = throw NoSuchModelError(providerId, "imageModel", modelId)
}

private class VoyageEmbeddingModel(
    private val client: HttpClient,
    private val settings: VoyageProviderSettings,
    override val modelId: String,
) : EmbeddingModel {
    override val provider: String = "voyage.embedding"

    override suspend fun embed(params: EmbeddingModelCallParams): EmbeddingModelResult {
        if (params.values.size > VOYAGE_MAX_EMBEDDINGS_PER_CALL) {
            throw InvalidArgumentError(
                "values",
                "embedding model voyage:$modelId supports at most $VOYAGE_MAX_EMBEDDINGS_PER_CALL values per call",
            )
        }
        val body = buildJsonObject {
            put("model", JsonPrimitive(modelId))
            put("input", JsonArray(params.values.map(::JsonPrimitive)))
            val options = voyageOptions(params.providerOptions)
            options["inputType"]?.let { put("input_type", it) }
            (options["truncation"] ?: params.truncate?.let(::JsonPrimitive))?.let { put("truncation", it) }
            options["outputDimension"]?.let { put("output_dimension", it) }
            options["outputDtype"]?.let { put("output_dtype", it) }
        }
        val response = voyagePostJson(
            client = client,
            url = "${settings.baseURL.trimEnd('/')}/embeddings",
            body = body,
            headers = voyageHeaders(settings, params.headers),
        )
        val value = response.value.jsonObject
        return EmbeddingModelResult(
            embeddings = value["data"]?.jsonArray.orEmpty()
                .sortedBy { it.jsonObject["index"]?.jsonPrimitive?.intOrNull ?: Int.MAX_VALUE }
                .map { item -> item.jsonObject["embedding"]?.jsonArray.orEmpty().map { embeddingFloat(it, provider) } },
            usage = EmbeddingUsage(
                tokens = value["usage"]?.jsonObject?.get("total_tokens")?.jsonPrimitive?.intOrNull ?: 0,
                raw = value["usage"],
            ),
            request = LanguageModelRequestMetadata(body = body),
            response = LanguageModelResponseMetadata(headers = response.headers, body = response.value),
        )
    }
}

private class VoyageRerankingModel(
    private val client: HttpClient,
    private val settings: VoyageProviderSettings,
    override val modelId: String,
) : RerankingModel {
    override val provider: String = "voyage.reranking"

    override suspend fun rerank(params: RerankingParams): RerankingModelResult {
        val response = voyagePostJson(
            client = client,
            url = "${settings.baseURL.trimEnd('/')}/rerank",
            body = buildJsonObject {
                put("model", JsonPrimitive(modelId))
                put("query", JsonPrimitive(params.query))
                put("documents", JsonArray(params.documents.map(::JsonPrimitive)))
                params.topN?.let { put("top_k", JsonPrimitive(it)) }
                val options = voyageOptions(params.providerOptions)
                options["returnDocuments"]?.let { put("return_documents", it) }
                options["truncation"]?.let { put("truncation", it) }
            },
            headers = voyageHeaders(settings, params.headers),
        )
        val value = response.value.jsonObject
        val results = value["data"]?.jsonArray.orEmpty().map { item ->
            val obj = item.jsonObject
            val index = obj["index"]?.jsonPrimitive?.intOrNull ?: 0
            RerankedItem(
                value = params.documents.getOrElse(index) { "" },
                score = obj["relevance_score"]?.jsonPrimitive?.floatOrNull ?: 0f,
                index = index,
            )
        }
        return RerankingModelResult(
            results = results,
            usage = Usage(promptTokens = value["usage"]?.jsonObject?.get("total_tokens")?.jsonPrimitive?.intOrNull ?: 0, completionTokens = 0),
            response = LanguageModelResponseMetadata(headers = response.headers, body = response.value),
        )
    }
}

private const val VOYAGE_MAX_EMBEDDINGS_PER_CALL: Int = 128


private suspend fun voyagePostJson(
    client: HttpClient,
    url: String,
    body: JsonObject,
    headers: Map<String, String>,
): HttpJsonResponse =
    requestJson(
        client = client,
        url = url,
        method = HttpMethod.Post,
        headers = headers,
        body = body,
        requestBodyValues = body,
        errorMessage = ::voyageErrorMessage,
    )

private fun voyageHeaders(settings: VoyageProviderSettings, callHeaders: Map<String, String>): Map<String, String> {
    val base = linkedMapOf<String, String>()
    settings.apiKey?.takeIf { it.isNotBlank() }?.let { base[HttpHeaders.Authorization] = "Bearer $it" }
    base.putAll(settings.headers)
    base.putAll(callHeaders)
    return withUserAgentSuffix(base, "ai-sdk/voyage/$VOYAGE_VERSION")
}

private fun voyageOptions(providerOptions: Map<String, JsonElement>): JsonObject =
    providerOptions["voyage"] as? JsonObject ?: JsonObject(emptyMap())

private fun voyageErrorMessage(statusCode: Int, parsed: JsonElement?, raw: String): String {
    val obj = parsed as? JsonObject
    val message = obj?.get("message")?.jsonPrimitive?.contentOrNull
        ?: obj?.get("detail")?.jsonPrimitive?.contentOrNull
        ?: obj?.get("error")?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
        ?: obj?.get("error")?.jsonPrimitive?.contentOrNull
        ?: raw.ifBlank { "request failed" }
    return "Voyage request failed ($statusCode): $message"
}
