package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import ai.torad.aisdk.providers.FacadeHttp.postFacadeJson
import ai.torad.aisdk.providers.FacadeHttp.providerFacadeHeaders
import ai.torad.aisdk.providers.FacadeHttp.providerSpecificOptions
import ai.torad.aisdk.providers.FacadeHttp.putProviderSpecificOptions
import dev.drewhamilton.poko.Poko
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
import kotlinx.serialization.json.jsonObject

public const val TOGETHERAI_VERSION: String = "2.0.53"

public typealias TogetherAIErrorData = JsonElement

@Serializable
@Poko
public class TogetherAIImageModelOptions internal constructor(
    public val steps: Int? = null,
    public val guidance: Float? = null,
    @SerialName("negative_prompt") public val negativePrompt: String? = null,
    @SerialName("disable_safety_checker") public val disableSafetyChecker: Boolean? = null,
    public val raw: Map<String, JsonElement> = emptyMap(),
)

public class TogetherAIImageModelOptionsBuilder internal constructor() {
    private var steps: Int? = null
    private var guidance: Float? = null
    private var negativePrompt: String? = null
    private var disableSafetyChecker: Boolean? = null
    private var raw: Map<String, JsonElement> = emptyMap()

    public fun steps(value: Int?) {
        steps = value
    }

    public fun guidance(value: Float?) {
        guidance = value
    }

    public fun negativePrompt(value: String?) {
        negativePrompt = value
    }

    public fun disableSafetyChecker(value: Boolean?) {
        disableSafetyChecker = value
    }

    public fun raw(value: Map<String, JsonElement>) {
        raw = value
    }

    internal fun build(): TogetherAIImageModelOptions =
        TogetherAIImageModelOptions(
            steps = steps,
            guidance = guidance,
            negativePrompt = negativePrompt,
            disableSafetyChecker = disableSafetyChecker,
            raw = raw,
        )
}

public fun TogetherAIImageModelOptions(
    block: TogetherAIImageModelOptionsBuilder.() -> Unit = {},
): TogetherAIImageModelOptions =
    TogetherAIImageModelOptionsBuilder().apply(block).build()

public typealias TogetherAIImageProviderOptions = TogetherAIImageModelOptions

@Serializable
@Poko
public class TogetherAIRerankingModelOptions internal constructor(
    public val rankFields: List<String>? = null,
)

public class TogetherAIRerankingModelOptionsBuilder internal constructor() {
    private var rankFields: List<String>? = null

    public fun rankFields(value: List<String>?) {
        rankFields = value
    }

    internal fun build(): TogetherAIRerankingModelOptions =
        TogetherAIRerankingModelOptions(rankFields = rankFields)
}

public fun TogetherAIRerankingModelOptions(
    block: TogetherAIRerankingModelOptionsBuilder.() -> Unit = {},
): TogetherAIRerankingModelOptions =
    TogetherAIRerankingModelOptionsBuilder().apply(block).build()

public typealias TogetherAIRerankingOptions = TogetherAIRerankingModelOptions

@Serializable
@Poko
public class TogetherAIProviderSettings internal constructor(
    public val apiKey: String? = null,
    public val baseURL: String = "https://api.together.xyz/v1",
    public val headers: Map<String, String> = emptyMap(),
) {
    internal fun toCompatible(
        name: String,
        version: String,
        capabilities: ProviderCapabilities = ProviderCapabilities(),
    ): OpenAICompatibleProviderSettings =
        OpenAICompatibleProviderSettings.forFacade(name, version, baseURL, apiKey, headers, capabilities)
}

public class TogetherAIProviderSettingsBuilder internal constructor() {
    private var apiKey: String? = null
    private var baseURL: String = "https://api.together.xyz/v1"
    private var headers: Map<String, String> = emptyMap()

    public fun apiKey(value: String?) {
        apiKey = value
    }

    public fun baseURL(value: String) {
        baseURL = value
    }

    public fun headers(value: Map<String, String>) {
        headers = value
    }

    internal fun build(): TogetherAIProviderSettings =
        TogetherAIProviderSettings(
            apiKey = apiKey,
            baseURL = baseURL,
            headers = headers,
        )
}

public fun TogetherAIProviderSettings(
    block: TogetherAIProviderSettingsBuilder.() -> Unit = {},
): TogetherAIProviderSettings =
    TogetherAIProviderSettingsBuilder().apply(block).build()

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
        val images = (JsonAccess.arr(response.value.jsonObject, "data")).orEmpty().mapIndexed { index, item ->
            val obj = WireDecoder.objectValue(item, "togetherai", "image generation response", "$.data[$index]")
            GeneratedFile(
                mediaType = "image/png",
                // Fail loudly on a missing image payload instead of emitting base64="" — a zero-byte
                // PNG slips past generateImage's empty-list guard and reaches the caller as success.
                base64 = WireDecoder.requiredString(obj, "b64_json", "togetherai", "image generation response", "$.data[$index]"),
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
                (JsonAccess.arr(options, "rankFields"))?.let { put("rank_fields", it) }
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
        val ranking = (JsonAccess.arr(value, "results")).orEmpty().mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val index = (obj["index"] as? JsonPrimitive)?.intOrNull ?: 0
            RerankedItem(
                value = params.documents.getOrElse(index) { "" },
                score = (obj["relevance_score"] as? JsonPrimitive)?.floatOrNull ?: 0f,
                index = index,
            )
        }
        return RerankingModelResult(
            results = ranking,
            usage = togetherAIUsage(value["usage"]),
            response = LanguageModelResponseMetadata(
                id = (value["id"] as? JsonPrimitive)?.contentOrNull,
                modelId = (value["model"] as? JsonPrimitive)?.contentOrNull ?: modelId,
                headers = response.headers,
                body = response.value,
            ),
        )
    }

    private fun togetherAIUsage(value: JsonElement?): Usage {
        val obj = value as? JsonObject ?: return Usage()
        return Usage.of(
            promptTokens = (obj["prompt_tokens"] as? JsonPrimitive)?.intOrNull ?: 0,
            completionTokens = (obj["completion_tokens"] as? JsonPrimitive)?.intOrNull ?: 0,
        )
    }
}
