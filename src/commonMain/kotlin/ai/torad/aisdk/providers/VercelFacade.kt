package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import dev.drewhamilton.poko.Poko
import io.ktor.client.HttpClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

public const val VERCEL_VERSION: String = "2.0.50"

public typealias VercelErrorData = JsonElement

@Serializable
@Poko
public class VercelProviderSettings internal constructor(
    public val apiKey: String? = null,
    public val baseURL: String = "https://api.v0.dev/v1",
    public val headers: Map<String, String> = emptyMap(),
) {
    internal fun toCompatible(
        name: String,
        version: String,
        capabilities: ProviderCapabilities = ProviderCapabilities(),
    ): OpenAICompatibleProviderSettings =
        OpenAICompatibleProviderSettings.forFacade(name, version, baseURL, apiKey, headers, capabilities)
}

public class VercelProviderSettingsBuilder internal constructor() {
    private var apiKey: String? = null
    private var baseURL: String = "https://api.v0.dev/v1"
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

    internal fun build(): VercelProviderSettings =
        VercelProviderSettings(
            apiKey = apiKey,
            baseURL = baseURL,
            headers = headers,
        )
}

public fun VercelProviderSettings(
    block: VercelProviderSettingsBuilder.() -> Unit = {},
): VercelProviderSettings =
    VercelProviderSettingsBuilder().apply(block).build()

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
