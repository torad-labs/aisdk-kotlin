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
/** @since 0.3.0-beta01 */
public class VercelProviderSettings internal constructor(
    /** @since 0.3.0-beta01 */
    public val apiKey: String? = null,
    /** @since 0.3.0-beta01 */
    public val baseURL: String = "https://api.v0.dev/v1",
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
public class VercelProviderSettingsBuilder {
    private var apiKey: String? = null
    private var baseURL: String = "https://api.v0.dev/v1"
    private var headers: Map<String, String> = emptyMap()

    /** @since 0.3.0-beta01 */
    public fun apiKey(value: String?): VercelProviderSettingsBuilder {
        apiKey = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun baseURL(value: String): VercelProviderSettingsBuilder {
        baseURL = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun headers(value: Map<String, String>): VercelProviderSettingsBuilder {
        headers = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun build(): VercelProviderSettings =
        VercelProviderSettings(
            apiKey = apiKey,
            baseURL = baseURL,
            headers = headers,
        )
}

/** @since 0.3.0-beta01 */
public fun VercelProviderSettings(
    block: VercelProviderSettingsBuilder.() -> Unit = {},
): VercelProviderSettings =
    VercelProviderSettingsBuilder().apply(block).build()

/** @since 0.3.0-beta01 */
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
    /** @since 0.3.0-beta01 */
    public fun textEmbeddingModel(modelId: String): Nothing = throw NoSuchModelError(providerId, "embeddingModel", modelId)
    override fun embeddingModel(modelId: String): EmbeddingModel = throw NoSuchModelError(providerId, "embeddingModel", modelId)
    override fun imageModel(modelId: String): ImageModel = throw NoSuchModelError(providerId, "imageModel", modelId)
}

/** @since 0.3.0-beta01 */
public fun Vercel(
    client: HttpClient,
    settings: VercelProviderSettings = VercelProviderSettings(),
): VercelProvider = VercelProvider(client, settings)
