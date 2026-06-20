package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import ai.torad.aisdk.providers.BasetenWire.toCompatible
import ai.torad.aisdk.providers.FacadeSupport.compatibleSettings
import io.ktor.client.HttpClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

public const val BASETEN_VERSION: String = "1.0.51"


@Serializable
public data class BasetenEmbeddingModelOptions(
    val raw: Map<String, JsonElement> = emptyMap(),
)

@Serializable
public data class BasetenErrorData(
    val error: String,
)

@Serializable
public data class BasetenProviderSettings(
    val apiKey: String? = null,
    val baseURL: String = "https://inference.baseten.co/v1",
    val modelURL: String? = null,
    val headers: Map<String, String> = emptyMap(),
)

public class BasetenProvider(
    private val client: HttpClient,
    private val settings: BasetenProviderSettings,
) : Provider {
    override val providerId: String = "baseten"

    public operator fun invoke(): LanguageModel = chatModel()
    public operator fun invoke(modelId: ModelId): LanguageModel = chatModel(modelId)
    public fun chatModel(): LanguageModel = createChatModel(null)
    public fun chatModel(modelId: ModelId): LanguageModel = createChatModel(modelId.value)
    public fun languageModel(): LanguageModel = chatModel()
    override fun languageModel(modelId: String): LanguageModel = chatModel(ModelId(modelId))
    public fun embeddingModel(): EmbeddingModel = createEmbeddingModel(null)
    override fun embeddingModel(modelId: String): EmbeddingModel = createEmbeddingModel(modelId)
    public fun textEmbeddingModel(): EmbeddingModel = embeddingModel()
    public fun textEmbeddingModel(modelId: ModelId): EmbeddingModel = embeddingModel(modelId)
    override fun imageModel(modelId: String): ImageModel = throw NoSuchModelError(providerId, "imageModel", modelId)

    private fun createChatModel(modelId: String?): LanguageModel {
        val customURL = settings.modelURL?.trimEnd('/')
        val baseURL = when {
            customURL?.contains("/sync/v1") == true -> customURL
            customURL?.contains("/predict") == true -> throw UnsupportedFunctionalityError(
                "baseten.chatModel",
                "Not supported. You must use a /sync/v1 endpoint for chat models.",
            )
            else -> settings.baseURL.trimEnd('/')
        }
        val resolvedModelId = if (customURL?.contains("/sync/v1") == true) {
            modelId ?: "placeholder"
        } else {
            modelId ?: "chat"
        }
        return OpenAICompatible(client, settings.toCompatible("baseten", BASETEN_VERSION, baseURL)).chatModel(resolvedModelId)
    }

    private fun createEmbeddingModel(modelId: String?): EmbeddingModel {
        val customURL = settings.modelURL?.trimEnd('/')
            ?: throw InvalidArgumentError(
                "modelURL",
                "No model URL provided for embeddings. Please set modelURL option for embeddings.",
            )
        if (!customURL.contains("/sync")) {
            throw UnsupportedFunctionalityError(
                "baseten.embeddingModel",
                "Not supported. You must use a /sync or /sync/v1 endpoint for embeddings.",
            )
        }
        val baseURL = if (customURL.contains("/sync/v1")) customURL else "$customURL/v1"
        return OpenAICompatible(client, settings.toCompatible("baseten", BASETEN_VERSION, baseURL)).embeddingModel(modelId ?: "embeddings")
    }
}

public fun Baseten(
    client: HttpClient,
    settings: BasetenProviderSettings = BasetenProviderSettings(),
): BasetenProvider = BasetenProvider(client, settings)

internal object BasetenWire {
    fun BasetenProviderSettings.toCompatible(
        name: String,
        version: String,
        baseURL: String,
        includeUsage: Boolean = false,
        supportsStructuredOutputs: Boolean = false,
    ): OpenAICompatibleProviderSettings =
        compatibleSettings(name, version, baseURL, apiKey, headers, includeUsage, supportsStructuredOutputs)
}
