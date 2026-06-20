package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import ai.torad.aisdk.providers.FacadeHttp.getFacadeBinary
import ai.torad.aisdk.providers.FacadeHttp.postFacadeBinary
import ai.torad.aisdk.providers.FacadeHttp.postFacadeJson
import ai.torad.aisdk.providers.FacadeHttp.providerFacadeHeaders
import ai.torad.aisdk.providers.FacadeHttp.putProviderSpecificOptions
import ai.torad.aisdk.providers.FacadeSupport.compatibleSettings
import ai.torad.aisdk.providers.FireworksWire.fireworksImageBackend
import ai.torad.aisdk.providers.FireworksWire.fireworksImageUrl
import ai.torad.aisdk.providers.FireworksWire.fireworksImageWarnings
import ai.torad.aisdk.providers.FireworksWire.toCompatible
import ai.torad.aisdk.providers.FireworksWire.transformFireworksProviderOptions
import io.ktor.client.HttpClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

public const val FIREWORKS_VERSION: String = "2.0.53"


@Serializable
public data class FireworksThinkingOptions(
    val type: String? = null,
    val budgetTokens: Int? = null,
)

@Serializable
public data class FireworksLanguageModelOptions(
    val thinking: FireworksThinkingOptions? = null,
    val reasoningHistory: String? = null,
    val raw: Map<String, JsonElement> = emptyMap(),
)

public typealias FireworksProviderOptions = FireworksLanguageModelOptions

@Serializable
public data class FireworksEmbeddingModelOptions(
    val raw: Map<String, JsonElement> = emptyMap(),
)

public typealias FireworksEmbeddingProviderOptions = FireworksEmbeddingModelOptions

@Serializable
public data class FireworksErrorData(
    val error: String,
)

@Serializable
public data class FireworksProviderSettings(
    val apiKey: String? = null,
    val baseURL: String = "https://api.fireworks.ai/inference/v1",
    val headers: Map<String, String> = emptyMap(),
)

public class FireworksProvider(
    private val client: HttpClient,
    private val settings: FireworksProviderSettings,
) : Provider {
    private val compatible = OpenAICompatible(
        client,
        settings.toCompatible("fireworks", FIREWORKS_VERSION),
    )
    override val providerId: String = "fireworks"

    public operator fun invoke(modelId: ModelId): LanguageModel = languageModel(modelId)
    override fun languageModel(modelId: String): LanguageModel = chatModel(ModelId(modelId))
    public fun chatModel(modelId: ModelId): LanguageModel = FireworksLanguageModel(compatible.chatModel(modelId.value))
    public fun completionModel(modelId: ModelId): LanguageModel = compatible.completionModel(modelId.value)
    public fun textEmbeddingModel(modelId: ModelId): EmbeddingModel = embeddingModel(modelId)
    public fun image(modelId: ModelId): ImageModel = imageModel(modelId)
    override fun embeddingModel(modelId: String): EmbeddingModel = compatible.embeddingModel(modelId)
    override fun imageModel(modelId: String): ImageModel = FireworksImageModel(client, settings, modelId)
}

public fun Fireworks(
    client: HttpClient,
    settings: FireworksProviderSettings = FireworksProviderSettings(),
): FireworksProvider = FireworksProvider(client, settings)

private class FireworksLanguageModel(
    private val delegate: LanguageModel,
) : LanguageModel by delegate {
    override suspend fun generate(params: LanguageModelCallParams): LanguageModelResult =
        delegate.generate(params.copy(providerOptions = transformFireworksProviderOptions(params.providerOptions)))

    override fun stream(params: LanguageModelCallParams): Flow<StreamEvent> =
        delegate.stream(params.copy(providerOptions = transformFireworksProviderOptions(params.providerOptions)))

    override fun streamResult(params: LanguageModelCallParams): LanguageModelStreamResult =
        delegate.streamResult(params.copy(providerOptions = transformFireworksProviderOptions(params.providerOptions)))
}

public class FireworksImageModel(
    private val client: HttpClient,
    private val settings: FireworksProviderSettings,
    override val modelId: String,
) : ImageModel {
    override val provider: String = "fireworks.image"

    override suspend fun generate(params: ImageGenerationParams): ImageModelResult {
        val backend = fireworksImageBackend(modelId)
        val warnings = fireworksImageWarnings(params, backend)
        val body = buildJsonObject {
            put("prompt", JsonPrimitive(params.prompt))
            params.aspectRatio?.let { put("aspect_ratio", JsonPrimitive(it)) }
            params.seed?.let { put("seed", JsonPrimitive(it)) }
            put("samples", JsonPrimitive(params.n))
            params.size?.split('x')?.takeIf { it.size == 2 }?.let { parts ->
                put("width", JsonPrimitive(parts[0]))
                put("height", JsonPrimitive(parts[1]))
            }
            putProviderSpecificOptions(params.providerOptions.toMap(), "fireworks")
        }
        val requestHeaders = providerFacadeHeaders(
            apiKey = settings.apiKey,
            headers = settings.headers,
            callHeaders = params.headers,
            userAgent = "ai-sdk/fireworks/$FIREWORKS_VERSION",
        )
        return if (backend.urlFormat == FireworksImageUrlFormat.WorkflowsAsync) {
            generateAsync(body, requestHeaders, warnings)
        } else {
            val response = postFacadeBinary(
                client = client,
                url = fireworksImageUrl(settings.baseURL, modelId, backend),
                body = body,
                headers = requestHeaders,
            )
            ImageModelResult(
                images = listOf(response.toGeneratedFile(modelId)),
                warnings = warnings,
                response = LanguageModelResponseMetadata(modelId = modelId, headers = response.headers),
            )
        }
    }

    private suspend fun generateAsync(
        body: JsonObject,
        requestHeaders: Map<String, String>,
        warnings: List<CallWarning>,
    ): ImageModelResult {
        val submitResponse = postFacadeJson(
            client = client,
            url = fireworksImageUrl(settings.baseURL, modelId, fireworksImageBackend(modelId)),
            body = body,
            headers = requestHeaders,
        )
        val requestId = submitResponse.value.jsonObject["request_id"]?.jsonPrimitive?.contentOrNull
            ?: throw InvalidResponseDataError(
                submitResponse.value,
                "Fireworks image generation response is missing request_id",
            )
        val imageUrl = pollForImageUrl(requestId, requestHeaders)
        val imageResponse = getFacadeBinary(client, imageUrl, requestHeaders)
        return ImageModelResult(
            images = listOf(imageResponse.toGeneratedFile(modelId)),
            warnings = warnings,
            response = LanguageModelResponseMetadata(modelId = modelId, headers = imageResponse.headers),
        )
    }

    private suspend fun pollForImageUrl(requestId: String, requestHeaders: Map<String, String>): String {
        val pollUrl = "${settings.baseURL.trimEnd('/')}/workflows/$modelId/get_result"
        repeat(FIREWORKS_MAX_POLL_ATTEMPTS) { attempt ->
            val response = postFacadeJson(
                client = client,
                url = pollUrl,
                body = buildJsonObject { put("id", JsonPrimitive(requestId)) },
                headers = requestHeaders,
            )
            val status = response.value.jsonObject["status"]?.jsonPrimitive?.contentOrNull
            when (status) {
                "Ready" -> return response.value.jsonObject["result"]
                    ?.jsonObject
                    ?.get("sample")
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?: throw InvalidResponseDataError(
                        response.value,
                        "Fireworks poll response is Ready but missing result.sample",
                    )
                "Error", "Failed" -> throw APICallError(
                    message = "Fireworks image generation failed with status: $status",
                    url = pollUrl,
                )
            }
            if (attempt < FIREWORKS_MAX_POLL_ATTEMPTS - 1) delay(FIREWORKS_POLL_INTERVAL_MILLIS)
        }
        throw APICallError(
            message = "Fireworks image generation timed out after polling",
            url = pollUrl,
        )
    }
}

internal enum class FireworksImageUrlFormat {
    Workflows,
    WorkflowsAsync,
    ImageGeneration,
}

internal data class FireworksImageBackend(
    val urlFormat: FireworksImageUrlFormat,
    val supportsSize: Boolean = false,
)

private const val FIREWORKS_POLL_INTERVAL_MILLIS: Long = 500
private const val FIREWORKS_MAX_POLL_ATTEMPTS: Int = 240

internal object FireworksWire {
    fun FireworksProviderSettings.toCompatible(
        name: String,
        version: String,
        includeUsage: Boolean = false,
        supportsStructuredOutputs: Boolean = false,
    ): OpenAICompatibleProviderSettings =
        compatibleSettings(name, version, baseURL, apiKey, headers, includeUsage, supportsStructuredOutputs)

    fun transformFireworksProviderOptions(options: ProviderOptions): ProviderOptions {
        val map = options.toMap()
        val fireworksOptions = map["fireworks"] as? JsonObject ?: return options
        val transformed = buildJsonObject {
            for ((key, value) in fireworksOptions) {
                when (key) {
                    "thinking" -> put("thinking", transformFireworksThinking(value))
                    "reasoningHistory" -> put("reasoning_history", value)
                    else -> put(key, value)
                }
            }
        }
        return ProviderOptions.Raw(JsonObject(map + ("fireworks" to (transformed as JsonElement))))
    }

    fun transformFireworksThinking(value: JsonElement): JsonElement {
        val objectValue = value as? JsonObject ?: return value
        return buildJsonObject {
            for ((key, nestedValue) in objectValue) {
                when (key) {
                    "budgetTokens" -> put("budget_tokens", nestedValue)
                    else -> put(key, nestedValue)
                }
            }
        }
    }

    fun fireworksImageBackend(modelId: String): FireworksImageBackend =
        when (modelId) {
            "accounts/fireworks/models/flux-kontext-pro",
            "accounts/fireworks/models/flux-kontext-max",
            -> FireworksImageBackend(FireworksImageUrlFormat.WorkflowsAsync)
            "accounts/fireworks/models/playground-v2-5-1024px-aesthetic",
            "accounts/fireworks/models/japanese-stable-diffusion-xl",
            "accounts/fireworks/models/playground-v2-1024px-aesthetic",
            "accounts/fireworks/models/stable-diffusion-xl-1024-v1-0",
            "accounts/fireworks/models/SSD-1B",
            -> FireworksImageBackend(FireworksImageUrlFormat.ImageGeneration, supportsSize = true)
            else -> FireworksImageBackend(FireworksImageUrlFormat.Workflows)
        }

    fun fireworksImageUrl(baseURL: String, modelId: String, backend: FireworksImageBackend): String {
        val base = baseURL.trimEnd('/')
        return when (backend.urlFormat) {
            FireworksImageUrlFormat.ImageGeneration -> "$base/image_generation/$modelId"
            FireworksImageUrlFormat.WorkflowsAsync -> "$base/workflows/$modelId"
            FireworksImageUrlFormat.Workflows -> "$base/workflows/$modelId/text_to_image"
        }
    }

    fun fireworksImageWarnings(params: ImageGenerationParams, backend: FireworksImageBackend): List<CallWarning> =
        buildList {
            if (!backend.supportsSize && params.size != null) {
                add(CallWarning("unsupported", "This Fireworks model does not support size; use aspectRatio."))
            }
            if (backend.supportsSize && params.aspectRatio != null) {
                add(CallWarning("unsupported", "This Fireworks model does not support aspectRatio."))
            }
        }
}
