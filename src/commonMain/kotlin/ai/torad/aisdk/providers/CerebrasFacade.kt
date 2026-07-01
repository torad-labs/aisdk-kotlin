package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import dev.drewhamilton.poko.Poko
import io.ktor.client.HttpClient
import kotlinx.serialization.Serializable

public const val CEREBRAS_VERSION: String = "2.0.54"

@Serializable
@Poko
/** @since 0.3.0-beta01 */
public class CerebrasProviderSettings internal constructor(
    /** @since 0.3.0-beta01 */
    public val apiKey: String? = null,
    /** @since 0.3.0-beta01 */
    public val baseURL: String = "https://api.cerebras.ai/v1",
    /** @since 0.3.0-beta01 */
    public val headers: Map<String, String> = emptyMap(),
) {
    internal fun toCompatible(
        name: String,
        version: String,
        capabilities: ProviderCapabilities = ProviderCapabilities(),
    ): OpenAICompatibleProviderSettings =
        OpenAICompatibleProviderSettings.forFacade(name, version, baseURL, apiKey, headers, capabilities)
}

/** @since 0.3.0-beta01 */
public class CerebrasProviderSettingsBuilder {
    private var apiKey: String? = null
    private var baseURL: String = "https://api.cerebras.ai/v1"
    private var headers: Map<String, String> = emptyMap()

    /** @since 0.3.0-beta01 */
    public fun apiKey(value: String?): CerebrasProviderSettingsBuilder {
        apiKey = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun baseURL(value: String): CerebrasProviderSettingsBuilder {
        baseURL = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun headers(value: Map<String, String>): CerebrasProviderSettingsBuilder {
        headers = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun build(): CerebrasProviderSettings =
        CerebrasProviderSettings(
            apiKey = apiKey,
            baseURL = baseURL,
            headers = headers,
        )
}

/** @since 0.3.0-beta01 */
public fun CerebrasProviderSettings(
    block: CerebrasProviderSettingsBuilder.() -> Unit = {},
): CerebrasProviderSettings =
    CerebrasProviderSettingsBuilder().apply(block).build()

@Serializable
@Poko
/** @since 0.3.0-beta01 */
public class CerebrasErrorData(
    /** @since 0.3.0-beta01 */
    public val message: String,
    /** @since 0.3.0-beta01 */
    public val type: String? = null,
    /** @since 0.3.0-beta01 */
    public val param: String? = null,
    /** @since 0.3.0-beta01 */
    public val code: String? = null,
)

/** @since 0.3.0-beta01 */
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
    /** @since 0.3.0-beta01 */
    public fun chat(modelId: String): LanguageModel = compatible.chatModel(modelId)
    /** @since 0.3.0-beta01 */
    public fun textEmbeddingModel(modelId: String): Nothing = throw NoSuchModelError(providerId, "embeddingModel", modelId)
    override fun embeddingModel(modelId: String): EmbeddingModel = throw NoSuchModelError(providerId, "embeddingModel", modelId)
    override fun imageModel(modelId: String): ImageModel = throw NoSuchModelError(providerId, "imageModel", modelId)
}

/** @since 0.3.0-beta01 */
public fun Cerebras(
    client: HttpClient,
    settings: CerebrasProviderSettings = CerebrasProviderSettings(),
): CerebrasProvider = CerebrasProvider(client, settings)
