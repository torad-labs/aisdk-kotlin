package ai.torad.aisdk.providers

import ai.torad.aisdk.*
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
import kotlinx.serialization.json.jsonObject

public const val VOYAGE_VERSION: String = "1.0.4"


@Serializable
public data class VoyageEmbeddingModelOptions(
    val inputType: String? = null,
    val truncation: Boolean? = null,
    val outputDimension: Int? = null,
    val outputDtype: String? = null,
)

@Serializable
public data class VoyageRerankingModelOptions(
    val returnDocuments: Boolean? = null,
    val truncation: Boolean? = null,
)

@Serializable
public data class VoyageProviderSettings(
    val baseURL: String = "https://api.voyageai.com/v1",
    val apiKey: String? = null,
    val headers: Map<String, String> = emptyMap(),
) {
    internal suspend fun voyagePostJson(
        client: HttpClient,
        url: String,
        body: JsonObject,
        headers: Map<String, String>,
    ): HttpJsonResponse =
        HttpTransport.requestJson(
            client = client,
            url = url,
            method = HttpMethod.Post,
            headers = headers,
            body = body,
            requestBodyValues = body,
            errorMessage = ::voyageErrorMessage,
        )

    internal fun voyageHeaders(callHeaders: Map<String, String>): Map<String, String> {
        val base = linkedMapOf<String, String>()
        apiKey?.takeIf { it.isNotBlank() }?.let { base[HttpHeaders.Authorization] = "Bearer $it" }
        base.putAll(headers)
        base.putAll(callHeaders)
        return ProviderHeaders.withUserAgentSuffix(base, "ai-sdk/voyage/$VOYAGE_VERSION")
    }

    internal fun voyageOptions(providerOptions: ProviderOptions): JsonObject =
        providerOptions.toMap()["voyage"] as? JsonObject ?: JsonObject(emptyMap())

    private fun voyageErrorMessage(statusCode: Int, parsed: JsonElement?, raw: String): String {
        val obj = parsed as? JsonObject
        val message = (obj?.get("message") as? JsonPrimitive)?.contentOrNull
            ?: (obj?.get("detail") as? JsonPrimitive)?.contentOrNull
            ?: ((obj?.get("error") as? JsonObject)?.get("message") as? JsonPrimitive)?.contentOrNull
            ?: (obj?.get("error") as? JsonPrimitive)?.contentOrNull
            ?: raw.ifBlank { "request failed" }
        return "Voyage request failed ($statusCode): $message"
    }
}

public class VoyageProvider(
    private val client: HttpClient,
    public val settings: VoyageProviderSettings,
) : Provider {
    override val providerId: String = "voyage"

    public fun embedding(modelId: ModelId): EmbeddingModel = VoyageEmbeddingModel(client, settings, modelId.value)
    public fun textEmbedding(modelId: ModelId): EmbeddingModel = embedding(modelId)
    public fun textEmbeddingModel(modelId: ModelId): EmbeddingModel = embedding(modelId)
    public fun reranking(modelId: ModelId): RerankingModel = VoyageRerankingModel(client, settings, modelId.value)

    override fun embeddingModel(modelId: String): EmbeddingModel = embedding(ModelId(modelId))
    override fun rerankingModel(modelId: String): RerankingModel = reranking(ModelId(modelId))
}

/** PascalCase factory — mirrors the OpenAI reference pattern. */
public fun Voyage(
    client: HttpClient,
    settings: VoyageProviderSettings = VoyageProviderSettings(),
): VoyageProvider = VoyageProvider(client, settings)

private class VoyageEmbeddingModel(
    private val client: HttpClient,
    private val settings: VoyageProviderSettings,
    override val modelId: String,
) : EmbeddingModel {
    override val provider: String = "voyage.embedding"
    override val maxEmbeddingsPerCall: Int = VOYAGE_MAX_EMBEDDINGS_PER_CALL
    override val supportsParallelCalls: Boolean = true

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
            val options = settings.voyageOptions(params.providerOptions)
            options["inputType"]?.let { put("input_type", it) }
            (options["truncation"] ?: params.truncate?.let(::JsonPrimitive))?.let { put("truncation", it) }
            options["outputDimension"]?.let { put("output_dimension", it) }
            options["outputDtype"]?.let { put("output_dtype", it) }
        }
        val response = settings.voyagePostJson(
            client = client,
            url = "${settings.baseURL.trimEnd('/')}/embeddings",
            body = body,
            headers = settings.voyageHeaders(params.headers),
        )
        val value = response.value.jsonObject
        return EmbeddingModelResult(
            embeddings = (value["data"] as? JsonArray).orEmpty()
                .map { item ->
                    val row = (item.jsonObject["embedding"] as? JsonArray).orEmpty()
                    row.map { WireDecoder.embeddingFloat(it, provider) }
                },
            usage = EmbeddingUsage(
                tokens = ((value["usage"] as? JsonObject)?.get("total_tokens") as? JsonPrimitive)?.intOrNull ?: 0,
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
        val response = settings.voyagePostJson(
            client = client,
            url = "${settings.baseURL.trimEnd('/')}/rerank",
            body = buildJsonObject {
                put("model", JsonPrimitive(modelId))
                put("query", JsonPrimitive(params.query))
                put("documents", JsonArray(params.documents.map(::JsonPrimitive)))
                params.topN?.let { put("top_k", JsonPrimitive(it)) }
                val options = settings.voyageOptions(params.providerOptions)
                options["returnDocuments"]?.let { put("return_documents", it) }
                options["truncation"]?.let { put("truncation", it) }
            },
            headers = settings.voyageHeaders(params.headers),
        )
        val value = response.value.jsonObject
        val results = (value["data"] as? JsonArray).orEmpty().map { item ->
            val obj = item.jsonObject
            val index = (obj["index"] as? JsonPrimitive)?.intOrNull ?: 0
            RerankedItem(
                value = params.documents.getOrElse(index) { "" },
                score = (obj["relevance_score"] as? JsonPrimitive)?.floatOrNull ?: 0f,
                index = index,
            )
        }
        val totalTokens = ((value["usage"] as? JsonObject)?.get("total_tokens") as? JsonPrimitive)?.intOrNull ?: 0
        return RerankingModelResult(
            results = results,
            usage = Usage.of(promptTokens = totalTokens, completionTokens = 0),
            response = LanguageModelResponseMetadata(headers = response.headers, body = response.value),
        )
    }
}

private const val VOYAGE_MAX_EMBEDDINGS_PER_CALL: Int = 128
