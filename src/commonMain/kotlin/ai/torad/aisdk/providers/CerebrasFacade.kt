package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import dev.drewhamilton.poko.Poko
import io.ktor.client.HttpClient
import kotlinx.serialization.Serializable

public const val CEREBRAS_VERSION: String = "2.0.54"

@Serializable
@Poko
public class CerebrasProviderSettings internal constructor(
    public val apiKey: String? = null,
    public val baseURL: String = "https://api.cerebras.ai/v1",
    public val headers: Map<String, String> = emptyMap(),
) {
    internal fun toCompatible(
        name: String,
        version: String,
        capabilities: ProviderCapabilities = ProviderCapabilities(),
    ): OpenAICompatibleProviderSettings =
        OpenAICompatibleProviderSettings.forFacade(name, version, baseURL, apiKey, headers, capabilities)
}

public class CerebrasProviderSettingsBuilder internal constructor() {
    private var apiKey: String? = null
    private var baseURL: String = "https://api.cerebras.ai/v1"
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

    internal fun build(): CerebrasProviderSettings =
        CerebrasProviderSettings(
            apiKey = apiKey,
            baseURL = baseURL,
            headers = headers,
        )
}

public fun CerebrasProviderSettings(
    block: CerebrasProviderSettingsBuilder.() -> Unit = {},
): CerebrasProviderSettings =
    CerebrasProviderSettingsBuilder().apply(block).build()

@Serializable
@Poko
public class CerebrasErrorData(
    public val message: String,
    public val type: String? = null,
    public val param: String? = null,
    public val code: String? = null,
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
