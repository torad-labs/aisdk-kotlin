package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import ai.torad.aisdk.providers.FacadeHttp.postFacadeJson
import ai.torad.aisdk.providers.FacadeHttp.providerFacadeHeaders
import ai.torad.aisdk.providers.FacadeHttp.providerSpecificOptions
import ai.torad.aisdk.providers.FacadeHttp.putProviderSpecificOptions
import ai.torad.aisdk.providers.FacadeSupport.compatibleSettings
import ai.torad.aisdk.providers.TogetherAIWire.toCompatible
import ai.torad.aisdk.providers.TogetherAIWire.togetherAIUsage
import io.ktor.client.HttpClient
import kotlinx.serialization.SerialName
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

public const val TOGETHERAI_VERSION: String = "2.0.53"

public typealias TogetherAIErrorData = JsonElement

@Serializable
public data class TogetherAIImageModelOptions(
    val steps: Int? = null,
    val guidance: Float? = null,
    @SerialName("negative_prompt") val negativePrompt: String? = null,
    @SerialName("disable_safety_checker") val disableSafetyChecker: Boolean? = null,
    val raw: Map<String, JsonElement> = emptyMap(),
)

public typealias TogetherAIImageProviderOptions = TogetherAIImageModelOptions

@Serializable
public data class TogetherAIRerankingModelOptions(
    val rankFields: List<String>? = null,
)

public typealias TogetherAIRerankingOptions = TogetherAIRerankingModelOptions

@Serializable
public data class TogetherAIProviderSettings(
    val apiKey: String? = null,
    val baseURL: String = "https://api.together.xyz/v1",
    val headers: Map<String, String> = emptyMap(),
)

public class TogetherAIProvider(
    private val client: HttpClient,
    private val settings: TogetherAIProviderSettings,
) : Provider {
    private val compatible = OpenAICompatible(
        client,
        settings.toCompatible("togetherai", TOGETHERAI_VERSION),
    )
    override val providerId: String = "togetherai"

    public operator fun invoke(modelId: ModelId): LanguageModel = languageModel(modelId.value)
    override fun languageModel(modelId: String): LanguageModel = chatModel(ModelId(modelId))
    public fun chatModel(modelId: ModelId): LanguageModel = compatible.chatModel(modelId.value)
    public fun completionModel(modelId: ModelId): LanguageModel = compatible.completionModel(modelId.value)
    public fun textEmbeddingModel(modelId: ModelId): EmbeddingModel = embeddingModel(modelId.value)
    public fun image(modelId: ModelId): ImageModel = imageModel(modelId.value)
    public fun reranking(modelId: ModelId): RerankingModel = rerankingModel(modelId.value)
    override fun embeddingModel(modelId: String): EmbeddingModel = compatible.embeddingModel(modelId)
    override fun imageModel(modelId: String): ImageModel = TogetherAIImageModel(client, settings, modelId)
    override fun rerankingModel(modelId: String): RerankingModel = TogetherAIRerankingModel(client, settings, modelId)
}

public fun TogetherAI(
    client: HttpClient,
    settings: TogetherAIProviderSettings = TogetherAIProviderSettings(),
): TogetherAIProvider = TogetherAIProvider(client, settings)

private class TogetherAIImageModel(
    private val client: HttpClient,
    private val settings: TogetherAIProviderSettings,
    override val modelId: String,
) : ImageModel {
    override val provider: String = "togetherai.image"

    override suspend fun generate(params: ImageGenerationParams): ImageModelResult {
        val warnings = buildList {
            if (params.aspectRatio != null) {
                add(CallWarning("unsupported", "TogetherAI image generation ignores aspectRatio; use size."))
            }
        }
        val response = postFacadeJson(
            client = client,
            url = "${settings.baseURL.trimEnd('/')}/images/generations",
            body = buildJsonObject {
                put("model", JsonPrimitive(modelId))
                put("prompt", JsonPrimitive(params.prompt))
                params.seed?.let { put("seed", JsonPrimitive(it)) }
                if (params.n > 1) put("n", JsonPrimitive(params.n))
                params.size?.split('x')?.takeIf { it.size == 2 }?.let { parts ->
                    put("width", JsonPrimitive(parts[0].toInt()))
                    put("height", JsonPrimitive(parts[1].toInt()))
                }
                put("response_format", JsonPrimitive("base64"))
                putProviderSpecificOptions(params.providerOptions.toMap(), "togetherai")
            },
            headers = providerFacadeHeaders(
                apiKey = settings.apiKey,
                headers = settings.headers,
                callHeaders = params.headers,
                userAgent = "ai-sdk/togetherai/$TOGETHERAI_VERSION",
            ),
        )
        val images = response.value.jsonObject["data"]?.jsonArray.orEmpty().map { item ->
            GeneratedFile(
                mediaType = "image/png",
                base64 = item.jsonObject["b64_json"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            )
        }
        return ImageModelResult(
            images = images,
            warnings = warnings,
            response = LanguageModelResponseMetadata(modelId = modelId, headers = response.headers, body = response.value),
        )
    }
}

public class TogetherAIRerankingModel(
    private val client: HttpClient,
    private val settings: TogetherAIProviderSettings,
    override val modelId: String,
) : RerankingModel {
    override val provider: String = "togetherai.reranking"

    override suspend fun rerank(params: RerankingParams): RerankingModelResult {
        val options = providerSpecificOptions(params.providerOptions.toMap(), "togetherai")
        val response = postFacadeJson(
            client = client,
            url = "${settings.baseURL.trimEnd('/')}/rerank",
            body = buildJsonObject {
                put("model", JsonPrimitive(modelId))
                put("documents", JsonArray(params.documents.map(::JsonPrimitive)))
                put("query", JsonPrimitive(params.query))
                params.topN?.let { put("top_n", JsonPrimitive(it)) }
                (options["rankFields"] as? JsonArray)?.let { put("rank_fields", it) }
                put("return_documents", JsonPrimitive(false))
            },
            headers = providerFacadeHeaders(
                apiKey = settings.apiKey,
                headers = settings.headers,
                callHeaders = params.headers,
                userAgent = "ai-sdk/togetherai/$TOGETHERAI_VERSION",
            ),
        )
        val value = response.value.jsonObject
        val ranking = value["results"]?.jsonArray.orEmpty().map { item ->
            val obj = item.jsonObject
            val index = obj["index"]?.jsonPrimitive?.intOrNull ?: 0
            RerankedItem(
                value = params.documents.getOrElse(index) { "" },
                score = obj["relevance_score"]?.jsonPrimitive?.floatOrNull ?: 0f,
                index = index,
            )
        }
        return RerankingModelResult(
            results = ranking,
            usage = togetherAIUsage(value["usage"]),
            response = LanguageModelResponseMetadata(
                id = value["id"]?.jsonPrimitive?.contentOrNull,
                modelId = value["model"]?.jsonPrimitive?.contentOrNull ?: modelId,
                headers = response.headers,
                body = response.value,
            ),
        )
    }
}

internal object TogetherAIWire {
    fun TogetherAIProviderSettings.toCompatible(
        name: String,
        version: String,
        includeUsage: Boolean = false,
        supportsStructuredOutputs: Boolean = false,
    ): OpenAICompatibleProviderSettings =
        compatibleSettings(name, version, baseURL, apiKey, headers, includeUsage, supportsStructuredOutputs)

    fun togetherAIUsage(value: JsonElement?): Usage {
        val obj = value as? JsonObject ?: return Usage()
        return Usage.of(
            promptTokens = obj["prompt_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            completionTokens = obj["completion_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
        )
    }
}
