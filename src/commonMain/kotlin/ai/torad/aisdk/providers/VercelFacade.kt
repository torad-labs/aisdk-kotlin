package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import ai.torad.aisdk.providers.FacadeSupport.compatibleSettings
import ai.torad.aisdk.providers.VercelWire.toCompatible
import io.ktor.client.HttpClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

public const val VERCEL_VERSION: String = "2.0.50"

public typealias VercelErrorData = JsonElement

@Serializable
public data class VercelProviderSettings(
    val apiKey: String? = null,
    val baseURL: String = "https://api.v0.dev/v1",
    val headers: Map<String, String> = emptyMap(),
)

public class VercelProvider(
    client: HttpClient,
    settings: VercelProviderSettings,
) : Provider {
    private val compatible = OpenAICompatible(
        client,
        settings.toCompatible("vercel", VERCEL_VERSION),
    )
    override val providerId: String = "vercel"

    public operator fun invoke(modelId: ModelId): LanguageModel = languageModel(modelId.value)
    override fun languageModel(modelId: String): LanguageModel = compatible.chatModel(modelId)
    public fun textEmbeddingModel(modelId: String): Nothing = throw NoSuchModelError(providerId, "embeddingModel", modelId)
    override fun embeddingModel(modelId: String): EmbeddingModel = throw NoSuchModelError(providerId, "embeddingModel", modelId)
    override fun imageModel(modelId: String): ImageModel = throw NoSuchModelError(providerId, "imageModel", modelId)
}

public fun Vercel(
    client: HttpClient,
    settings: VercelProviderSettings = VercelProviderSettings(),
): VercelProvider = VercelProvider(client, settings)

internal object VercelWire {
    fun VercelProviderSettings.toCompatible(
        name: String,
        version: String,
        includeUsage: Boolean = false,
        supportsStructuredOutputs: Boolean = false,
    ): OpenAICompatibleProviderSettings =
        compatibleSettings(name, version, baseURL, apiKey, headers, includeUsage, supportsStructuredOutputs)
}
