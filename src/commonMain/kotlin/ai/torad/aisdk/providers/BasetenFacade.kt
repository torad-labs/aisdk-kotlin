package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import dev.drewhamilton.poko.Poko
import io.ktor.client.HttpClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

public const val BASETEN_VERSION: String = "1.0.51"


@Serializable
@Poko
public class BasetenEmbeddingModelOptions internal constructor(
    public val raw: Map<String, JsonElement> = emptyMap(),
)

public class BasetenEmbeddingModelOptionsBuilder {
    private var raw: Map<String, JsonElement> = emptyMap()

    public fun raw(value: Map<String, JsonElement>): BasetenEmbeddingModelOptionsBuilder {
        raw = value
        return this
    }

    public fun build(): BasetenEmbeddingModelOptions =
        BasetenEmbeddingModelOptions(raw = raw)
}

public fun BasetenEmbeddingModelOptions(
    block: BasetenEmbeddingModelOptionsBuilder.() -> Unit = {},
): BasetenEmbeddingModelOptions =
    BasetenEmbeddingModelOptionsBuilder().apply(block).build()

@Serializable
@Poko
public class BasetenErrorData(
    public val error: String,
)

@Serializable
@Poko
public class BasetenProviderSettings internal constructor(
    public val apiKey: String? = null,
    public val baseURL: String = "https://inference.baseten.co/v1",
    public val modelURL: String? = null,
    public val headers: Map<String, String> = emptyMap(),
) {
    internal fun toCompatible(
        name: String,
        version: String,
        baseURL: String,
        capabilities: ProviderCapabilities = ProviderCapabilities(),
    ): OpenAICompatibleProviderSettings =
        OpenAICompatibleProviderSettings.forFacade(name, version, baseURL, apiKey, headers, capabilities)
}

public class BasetenProviderSettingsBuilder {
    private var apiKey: String? = null
    private var baseURL: String = "https://inference.baseten.co/v1"
    private var modelURL: String? = null
    private var headers: Map<String, String> = emptyMap()

    public fun apiKey(value: String?): BasetenProviderSettingsBuilder {
        apiKey = value
        return this
    }

    public fun baseURL(value: String): BasetenProviderSettingsBuilder {
        baseURL = value
        return this
    }

    public fun modelURL(value: String?): BasetenProviderSettingsBuilder {
        modelURL = value
        return this
    }

    public fun headers(value: Map<String, String>): BasetenProviderSettingsBuilder {
        headers = value
        return this
    }

    public fun build(): BasetenProviderSettings =
        BasetenProviderSettings(
            apiKey = apiKey,
            baseURL = baseURL,
            modelURL = modelURL,
            headers = headers,
        )
}

public fun BasetenProviderSettings(
    block: BasetenProviderSettingsBuilder.() -> Unit = {},
): BasetenProviderSettings =
    BasetenProviderSettingsBuilder().apply(block).build()

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
    public fun textEmbeddingModel(modelId: ModelId): EmbeddingModel = embeddingModel(modelId.value)
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
