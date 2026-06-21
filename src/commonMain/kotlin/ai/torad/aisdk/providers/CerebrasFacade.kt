package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import io.ktor.client.HttpClient
import kotlinx.serialization.Serializable

public const val CEREBRAS_VERSION: String = "2.0.54"

@Serializable
public data class CerebrasProviderSettings(
    val apiKey: String? = null,
    val baseURL: String = "https://api.cerebras.ai/v1",
    val headers: Map<String, String> = emptyMap(),
) {
    internal fun toCompatible(
        name: String,
        version: String,
        capabilities: ProviderCapabilities = ProviderCapabilities(),
    ): OpenAICompatibleProviderSettings =
        OpenAICompatibleProviderSettings.forFacade(name, version, baseURL, apiKey, headers, capabilities)
}

@Serializable
public data class CerebrasErrorData(
    val message: String,
    val type: String? = null,
    val param: String? = null,
    val code: String? = null,
)

public class CerebrasProvider(
    client: HttpClient,
    settings: CerebrasProviderSettings,
) : Provider {
    private val compatible = OpenAICompatible(
        client,
        settings.toCompatible("cerebras", CEREBRAS_VERSION, capabilities = ProviderCapabilities(supportsStructuredOutputs = true)),
    )
    override val providerId: String = "cerebras"

    public operator fun invoke(modelId: String): LanguageModel = languageModel(modelId)
    override fun languageModel(modelId: String): LanguageModel = chat(modelId)
    public fun chat(modelId: String): LanguageModel = compatible.chatModel(modelId)
    public fun textEmbeddingModel(modelId: String): Nothing = throw NoSuchModelError(providerId, "embeddingModel", modelId)
    override fun embeddingModel(modelId: String): EmbeddingModel = throw NoSuchModelError(providerId, "embeddingModel", modelId)
    override fun imageModel(modelId: String): ImageModel = throw NoSuchModelError(providerId, "imageModel", modelId)
}

public fun Cerebras(
    client: HttpClient,
    settings: CerebrasProviderSettings = CerebrasProviderSettings(),
): CerebrasProvider = CerebrasProvider(client, settings)
