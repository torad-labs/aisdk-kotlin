package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import io.ktor.client.HttpClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** @since 0.3.0-beta01 */
public class OpenAICompatibleProviderSettings internal constructor(
    /** @since 0.3.0-beta01 */
    public val name: String,
    /** @since 0.3.0-beta01 */
    public val baseUrl: String,
    /** @since 0.3.0-beta01 */
    public val apiKey: String? = null,
    /** @since 0.3.0-beta01 */
    public val headers: Map<String, String> = emptyMap(),
    /** @since 0.3.0-beta01 */
    public val queryParams: Map<String, String> = emptyMap(),
    /** @since 0.3.0-beta01 */
    public val includeUsage: Boolean = false,
    /** @since 0.3.0-beta01 */
    public val supportsStructuredOutputs: Boolean = false,
    /** @since 0.3.0-beta01 */
    public val supportedUrls: Map<String, List<String>> = emptyMap(),
    /** @since 0.3.0-beta01 */
    public val maxEmbeddingsPerCall: Int = 2048,
    /** @since 0.3.0-beta01 */
    public val authHeadersProvider: (suspend () -> Map<String, String>)? = null,
    /** @since 0.3.0-beta01 */
    public val urlBuilder: ((path: String, modelId: String) -> String)? = null,
    /** @since 0.3.0-beta01 */
    public val userAgentSuffix: String? = "ai-sdk/openai-compatible-kotlin",
    /** @since 0.3.0-beta01 */
    public val providerOptionsName: String? = null,
    /** @since 0.3.0-beta01 */
    public val chatMaxOutputTokensKey: String = "max_tokens",
    /** @since 0.3.0-beta01 */
    public val chatSeedKey: String = "seed",
    /** @since 0.3.0-beta01 */
    public val transformChatRequestBody: ((JsonObject) -> JsonObject)? = null,
    /** @since 0.3.0-beta01 */
    public val convertUsage: ((JsonElement?) -> Usage)? = null,
    /** @since 0.3.0-beta01 */
    public val transformChatResponse: ((JsonObject) -> JsonObject)? = null,
) {
    internal companion object {
        /** Build settings for an OpenAI-compatible provider facade (Groq, DeepSeek, …). */
        @Suppress("LongParameterList")
        fun forFacade(
            name: String,
            version: String,
            baseURL: String,
            apiKey: String?,
            headers: Map<String, String>,
            capabilities: ProviderCapabilities = ProviderCapabilities(),
            transformChatRequestBody: ((JsonObject) -> JsonObject)? = null,
            convertUsage: ((JsonElement?) -> Usage)? = null,
            transformChatResponse: ((JsonObject) -> JsonObject)? = null,
        ): OpenAICompatibleProviderSettings =
            OpenAICompatibleProviderSettings {
                name(name)
                baseUrl(baseURL.trimEnd('/'))
                apiKey(apiKey)
                headers(ProviderHeaders.withUserAgentSuffix(headers, "ai-sdk/$name/$version"))
                // UA already embedded in headers above — null out the default suffix so commonHeaders()
                // doesn't APPEND "ai-sdk/openai-compatible-kotlin" again (double User-Agent).
                userAgentSuffix(null)
                includeUsage(capabilities.includeUsage)
                supportsStructuredOutputs(capabilities.supportsStructuredOutputs)
                transformChatRequestBody(transformChatRequestBody)
                convertUsage(convertUsage)
                transformChatResponse(transformChatResponse)
            }
    }
}

/** @since 0.3.0-beta01 */
public class OpenAICompatibleProviderSettingsBuilder {
    private var name: String? = null
    private var baseUrl: String? = null
    private var apiKey: String? = null
    private var headers: Map<String, String> = emptyMap()
    private var queryParams: Map<String, String> = emptyMap()
    private var includeUsage: Boolean = false
    private var supportsStructuredOutputs: Boolean = false
    private var supportedUrls: Map<String, List<String>> = emptyMap()
    private var maxEmbeddingsPerCall: Int = 2048
    private var authHeadersProvider: (suspend () -> Map<String, String>)? = null
    private var urlBuilder: ((path: String, modelId: String) -> String)? = null
    private var userAgentSuffix: String? = "ai-sdk/openai-compatible-kotlin"
    private var providerOptionsName: String? = null
    private var chatMaxOutputTokensKey: String = "max_tokens"
    private var chatSeedKey: String = "seed"
    private var transformChatRequestBody: ((JsonObject) -> JsonObject)? = null
    private var convertUsage: ((JsonElement?) -> Usage)? = null
    private var transformChatResponse: ((JsonObject) -> JsonObject)? = null

    /** @since 0.3.0-beta01 */
    public fun name(value: String): OpenAICompatibleProviderSettingsBuilder {
        name = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun baseUrl(value: String): OpenAICompatibleProviderSettingsBuilder {
        baseUrl = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun apiKey(value: String?): OpenAICompatibleProviderSettingsBuilder {
        apiKey = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun headers(value: Map<String, String>): OpenAICompatibleProviderSettingsBuilder {
        headers = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun queryParams(value: Map<String, String>): OpenAICompatibleProviderSettingsBuilder {
        queryParams = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun includeUsage(value: Boolean): OpenAICompatibleProviderSettingsBuilder {
        includeUsage = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun supportsStructuredOutputs(value: Boolean): OpenAICompatibleProviderSettingsBuilder {
        supportsStructuredOutputs = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun supportedUrls(value: Map<String, List<String>>): OpenAICompatibleProviderSettingsBuilder {
        supportedUrls = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun maxEmbeddingsPerCall(value: Int): OpenAICompatibleProviderSettingsBuilder {
        maxEmbeddingsPerCall = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun authHeadersProvider(
        value: (suspend () -> Map<String, String>)?
    ): OpenAICompatibleProviderSettingsBuilder {
        authHeadersProvider = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun urlBuilder(
        value: ((path: String, modelId: String) -> String)?
    ): OpenAICompatibleProviderSettingsBuilder {
        urlBuilder = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun userAgentSuffix(value: String?): OpenAICompatibleProviderSettingsBuilder {
        userAgentSuffix = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun providerOptionsName(value: String?): OpenAICompatibleProviderSettingsBuilder {
        providerOptionsName = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun chatMaxOutputTokensKey(value: String): OpenAICompatibleProviderSettingsBuilder {
        chatMaxOutputTokensKey = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun chatSeedKey(value: String): OpenAICompatibleProviderSettingsBuilder {
        chatSeedKey = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun transformChatRequestBody(value: ((JsonObject) -> JsonObject)?): OpenAICompatibleProviderSettingsBuilder {
        transformChatRequestBody = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun convertUsage(value: ((JsonElement?) -> Usage)?): OpenAICompatibleProviderSettingsBuilder {
        convertUsage = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun transformChatResponse(value: ((JsonObject) -> JsonObject)?): OpenAICompatibleProviderSettingsBuilder {
        transformChatResponse = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun build(): OpenAICompatibleProviderSettings =
        OpenAICompatibleProviderSettings(
            name = requireNotNull(name) { "OpenAICompatibleProviderSettings.name is required" },
            baseUrl = requireNotNull(baseUrl) { "OpenAICompatibleProviderSettings.baseUrl is required" },
            apiKey = apiKey,
            headers = headers,
            queryParams = queryParams,
            includeUsage = includeUsage,
            supportsStructuredOutputs = supportsStructuredOutputs,
            supportedUrls = supportedUrls,
            maxEmbeddingsPerCall = maxEmbeddingsPerCall,
            authHeadersProvider = authHeadersProvider,
            urlBuilder = urlBuilder,
            userAgentSuffix = userAgentSuffix,
            providerOptionsName = providerOptionsName,
            chatMaxOutputTokensKey = chatMaxOutputTokensKey,
            chatSeedKey = chatSeedKey,
            transformChatRequestBody = transformChatRequestBody,
            convertUsage = convertUsage,
            transformChatResponse = transformChatResponse,
        )
}

/** @since 0.3.0-beta01 */
public fun OpenAICompatibleProviderSettings(
    block: OpenAICompatibleProviderSettingsBuilder.() -> Unit = {},
): OpenAICompatibleProviderSettings =
    OpenAICompatibleProviderSettingsBuilder().apply(block).build()

/** @since 0.3.0-beta01 */
public interface OpenAICompatibleProvider : Provider {
    /** @since 0.3.0-beta01 */
    public fun chatModel(modelId: String): LanguageModel

    /** @since 0.3.0-beta01 */
    public fun completionModel(modelId: String): LanguageModel

    /** @since 0.3.0-beta01 */
    public fun textEmbeddingModel(modelId: String): EmbeddingModel = embeddingModel(modelId)
}

/**
 * Builds a provider for OpenAI-shaped HTTP APIs.
 *
 * The caller owns the Ktor [HttpClient] and its engine, timeouts, TLS/proxy
 * settings, and lifecycle. [settings] supplies endpoint, auth, usage mapping,
 * structured-output support, and provider-specific request/response transforms.
 * Use [OpenAICompatibleProvider.chatModel] for chat-completions style models or
 * [OpenAICompatibleProvider.completionModel] for legacy completions.
 * @since 0.3.0-beta01
 */
public fun OpenAICompatible(
    client: HttpClient,
    settings: OpenAICompatibleProviderSettings,
    json: Json = aiSdkJson,
): OpenAICompatibleProvider = KtorOpenAICompatibleProvider(client, settings, json)

private class KtorOpenAICompatibleProvider(
    private val client: HttpClient,
    private val settings: OpenAICompatibleProviderSettings,
    private val json: Json,
) : OpenAICompatibleProvider {
    override val providerId: String = settings.name

    override fun languageModel(modelId: String): LanguageModel = chatModel(modelId)

    override fun chatModel(modelId: String): LanguageModel =
        OpenAICompatibleChatLanguageModel(client, settings, json, modelId)

    override fun completionModel(modelId: String): LanguageModel =
        OpenAICompatibleCompletionLanguageModel(client, settings, json, modelId)

    override fun embeddingModel(modelId: String): EmbeddingModel =
        OpenAICompatibleEmbeddingModel(client, settings, json, modelId)

    override fun imageModel(modelId: String): ImageModel =
        OpenAICompatibleImageModel(client, settings, json, modelId)

    override fun speechModel(modelId: String): SpeechModel =
        OpenAICompatibleSpeechModel(client, settings, json, modelId)

    override fun transcriptionModel(modelId: String): TranscriptionModel =
        OpenAICompatibleTranscriptionModel(client, settings, json, modelId)
}
