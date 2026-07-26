package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import dev.drewhamilton.poko.Poko
import io.ktor.client.HttpClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

public const val VERCEL_VERSION: String = "2.0.50"

/**
 * Default base URL for [Vercel] — the **v0** product API (`api.v0.dev`), not the
 * Vercel AI Gateway. For the AI Gateway use [ai.torad.aisdk.Gateway] /
 * [AI_GATEWAY_OPENAI_COMPAT_BASE_URL], or [VercelAIGateway].
 */
public const val VERCEL_V0_BASE_URL: String = "https://api.v0.dev/v1"

/**
 * OpenAI-compatible base URL for the Vercel AI Gateway.
 * Prefer [ai.torad.aisdk.Gateway] for full gateway features (OIDC, metadata, …);
 * this facade is the thin chat/completions path only.
 */
public const val AI_GATEWAY_OPENAI_COMPAT_BASE_URL: String = "https://ai-gateway.vercel.sh/v1"

public typealias VercelErrorData = JsonElement

@Serializable
@Poko
/**
 * Settings for the v0 / AI-Gateway OpenAI-compatible facades.
 *
 * Default [baseURL] is the **v0** API. Point it at [AI_GATEWAY_OPENAI_COMPAT_BASE_URL]
 * (or use [VercelAIGateway]) for the AI Gateway chat path.
 * @since 0.3.0-beta01
 */
public class VercelProviderSettings internal constructor(
    /** @since 0.3.0-beta01 */
    public val apiKey: String? = null,
    /** @since 0.3.0-beta01 */
    public val baseURL: String = VERCEL_V0_BASE_URL,
    /** @since 0.3.0-beta01 */
    public val headers: Map<String, String> = emptyMap(),
) {
    internal fun toCompatible(
        name: String,
        version: String,
        capabilities: ProviderCapabilities = ProviderCapabilities(),
    ): OpenAICompatibleProviderSettings =
        ForFacade(name, version, baseURL, apiKey, headers, capabilities)
}

/** @since 0.3.0-beta01 */
public class VercelProviderSettingsBuilder {
    private var apiKey: String? = null
    private var baseURL: String = VERCEL_V0_BASE_URL
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

/**
 * OpenAI-compatible facade for Vercel's **v0** API (`api.v0.dev`).
 *
 * This is **not** the Vercel AI Gateway. For gateway routing/auth use
 * [ai.torad.aisdk.Gateway], or [VercelAIGateway] for a thin OpenAI-compatible
 * client against `ai-gateway.vercel.sh`.
 * @since 0.3.0-beta01
 */
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
    public fun textEmbeddingModel(modelId: String): Nothing = throw NoSuchModelError(
        providerId,
        "embeddingModel",
        modelId
    )
    override fun embeddingModel(modelId: String): EmbeddingModel = throw NoSuchModelError(
        providerId,
        "embeddingModel",
        modelId
    )
    override fun imageModel(modelId: String): ImageModel = throw NoSuchModelError(providerId, "imageModel", modelId)
}

/** @since 0.3.0-beta01 */
public fun Vercel(
    client: HttpClient,
    settings: VercelProviderSettings = VercelProviderSettings(),
): VercelProvider = VercelProvider(client, settings)

/**
 * Thin OpenAI-compatible client for the **Vercel AI Gateway**
 * (`ai-gateway.vercel.sh/v1`).
 *
 * For OIDC, model metadata, and full gateway protocol features prefer
 * [ai.torad.aisdk.Gateway]. This factory only reuses the chat/completions
 * facade pointed at the gateway base URL.
 *
 * @since 0.3.0-beta01
 */
public fun VercelAIGateway(
    client: HttpClient,
    settings: VercelProviderSettings = VercelProviderSettings { baseURL(AI_GATEWAY_OPENAI_COMPAT_BASE_URL) },
): VercelProvider = VercelProvider(
    client,
    if (settings.baseURL == VERCEL_V0_BASE_URL) {
        VercelProviderSettings {
            apiKey(settings.apiKey)
            baseURL(AI_GATEWAY_OPENAI_COMPAT_BASE_URL)
            headers(settings.headers)
        }
    } else {
        settings
    },
)
