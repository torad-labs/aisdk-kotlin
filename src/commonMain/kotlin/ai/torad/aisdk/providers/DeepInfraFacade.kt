package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import ai.torad.aisdk.providers.DeepInfraWire.fixDeepInfraUsage
import ai.torad.aisdk.providers.DeepInfraWire.fixDeepInfraUsageEvent
import ai.torad.aisdk.providers.FacadeHttp.postFacadeJson
import ai.torad.aisdk.providers.FacadeHttp.providerFacadeHeaders
import ai.torad.aisdk.providers.FacadeHttp.putProviderSpecificOptions
import ai.torad.aisdk.providers.FacadeHttp.stripDataUriPrefix
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

public const val DEEPINFRA_VERSION: String = "2.0.52"

public typealias DeepInfraErrorData = JsonElement

@Serializable
public data class DeepInfraProviderSettings(
    val apiKey: String? = null,
    val baseURL: String = "https://api.deepinfra.com/v1",
    val headers: Map<String, String> = emptyMap(),
)

public class DeepInfraProvider(
    private val client: HttpClient,
    private val settings: DeepInfraProviderSettings,
) : Provider {
    private val compatible = OpenAICompatible(
        client,
        OpenAICompatibleProviderSettings.forFacade(
            name = "deepinfra",
            version = DEEPINFRA_VERSION,
            baseURL = "${settings.baseURL.trimEnd('/')}/openai",
            apiKey = settings.apiKey,
            headers = settings.headers,
        ),
    )
    override val providerId: String = "deepinfra"

    public operator fun invoke(modelId: ModelId): LanguageModel = languageModel(modelId.value)
    override fun languageModel(modelId: String): LanguageModel = chatModel(ModelId(modelId))
    public fun chatModel(modelId: ModelId): LanguageModel = DeepInfraChatLanguageModel(compatible.chatModel(modelId.value))
    public fun completionModel(modelId: ModelId): LanguageModel = compatible.completionModel(modelId.value)
    public fun textEmbeddingModel(modelId: ModelId): EmbeddingModel = embeddingModel(modelId.value)
    public fun image(modelId: ModelId): ImageModel = imageModel(modelId.value)
    override fun embeddingModel(modelId: String): EmbeddingModel = compatible.embeddingModel(modelId)
    override fun imageModel(modelId: String): ImageModel = DeepInfraImageModel(client, settings, modelId)
}

public fun DeepInfra(
    client: HttpClient,
    settings: DeepInfraProviderSettings = DeepInfraProviderSettings(),
): DeepInfraProvider = DeepInfraProvider(client, settings)

private class DeepInfraChatLanguageModel(
    private val delegate: LanguageModel,
) : LanguageModel by delegate {
    override suspend fun generate(params: LanguageModelCallParams): LanguageModelResult =
        delegate.generate(params).let { result -> result.copy(usage = result.usage.fixDeepInfraUsage()) }

    override fun stream(params: LanguageModelCallParams): Flow<StreamEvent> =
        delegate.stream(params).map(::fixDeepInfraUsageEvent)

    override fun streamResult(params: LanguageModelCallParams): LanguageModelStreamResult =
        delegate.streamResult(params).let { result -> result.copy(stream = result.stream.map(::fixDeepInfraUsageEvent)) }
}

private class DeepInfraImageModel(
    private val client: HttpClient,
    private val settings: DeepInfraProviderSettings,
    override val modelId: String,
) : ImageModel {
    override val provider: String = "deepinfra.image"

    override suspend fun generate(params: ImageGenerationParams): ImageModelResult {
        val response = postFacadeJson(
            client = client,
            url = "${settings.baseURL.trimEnd('/')}/inference/$modelId",
            body = buildJsonObject {
                put("prompt", JsonPrimitive(params.prompt))
                put("num_images", JsonPrimitive(params.n))
                params.aspectRatio?.let { put("aspect_ratio", JsonPrimitive(it)) }
                params.size?.split('x')?.takeIf { it.size == 2 }?.let { parts ->
                    put("width", JsonPrimitive(parts[0]))
                    put("height", JsonPrimitive(parts[1]))
                }
                params.seed?.let { put("seed", JsonPrimitive(it)) }
                putProviderSpecificOptions(params.providerOptions.toMap(), "deepinfra")
            },
            headers = providerFacadeHeaders(
                apiKey = settings.apiKey,
                headers = settings.headers,
                callHeaders = params.headers,
                userAgent = "ai-sdk/deepinfra/$DEEPINFRA_VERSION",
            ),
        )
        val images = response.value.jsonObject["images"]?.jsonArray.orEmpty().mapIndexed { index, image ->
            // Require a real string payload; the old `.orEmpty()` produced a zero-byte PNG that
            // passed generateImage's empty-list guard and surfaced as a false success.
            val base64 = WireDecoder.stringValue(image, "deepinfra", "image generation response", "$.images[$index]")
            GeneratedFile(mediaType = "image/png", base64 = base64.stripDataUriPrefix())
        }
        return ImageModelResult(
            images = images,
            response = LanguageModelResponseMetadata(modelId = modelId, headers = response.headers, body = response.value),
        )
    }
}

internal object DeepInfraWire {
    fun fixDeepInfraUsageEvent(event: StreamEvent): StreamEvent =
        when (event) {
            is StreamEvent.StepFinish -> event.copy(usage = event.usage.fixDeepInfraUsage())
            is StreamEvent.Finish -> event.copy(usage = event.usage.fixDeepInfraUsage())
            is StreamEvent.StreamStart,
            is StreamEvent.ResponseMetadata,
            is StreamEvent.StepStart,
            is StreamEvent.TextStart,
            is StreamEvent.TextDelta,
            is StreamEvent.TextEnd,
            is StreamEvent.ReasoningStart,
            is StreamEvent.ReasoningDelta,
            is StreamEvent.ReasoningEnd,
            is StreamEvent.SourcePart,
            is StreamEvent.FilePart,
            is StreamEvent.ToolInputStart,
            is StreamEvent.ToolInputDelta,
            is StreamEvent.ToolInputEnd,
            is StreamEvent.ToolCall,
            is StreamEvent.ToolResult,
            is StreamEvent.ToolError,
            is StreamEvent.ToolApprovalRequest,
            is StreamEvent.ToolOutputDenied,
            StreamEvent.Abort,
            is StreamEvent.Error,
            is StreamEvent.Raw,
            -> event
        }

    fun Usage.fixDeepInfraUsage(): Usage {
        val rawObject = raw as? JsonObject ?: return this
        val reasoningTokens = rawObject["completion_tokens_details"]
            ?.jsonObject
            ?.get("reasoning_tokens")
            ?.jsonPrimitive
            ?.intOrNull
            ?: return this
        val completionTokens = rawObject["completion_tokens"]?.jsonPrimitive?.intOrNull ?: return this
        if (reasoningTokens <= completionTokens) return this

        val correctedCompletionTokens = completionTokens + reasoningTokens
        val correctedRaw = rawObject.toMutableMap().apply {
            put("completion_tokens", JsonPrimitive(correctedCompletionTokens))
            rawObject["total_tokens"]?.jsonPrimitive?.intOrNull?.let { total ->
                put("total_tokens", JsonPrimitive(total + reasoningTokens))
            }
        }
        return copy(
            outputTokens = outputTokens.copy(
                total = correctedCompletionTokens,
                text = correctedCompletionTokens - reasoningTokens,
                reasoning = reasoningTokens,
            ),
            raw = JsonObject(correctedRaw),
        )
    }
}
