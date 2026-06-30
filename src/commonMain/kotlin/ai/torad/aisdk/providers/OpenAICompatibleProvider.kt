package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import io.ktor.client.HttpClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

public class OpenAICompatibleProviderSettings internal constructor(
    public val name: String,
    public val baseUrl: String,
    public val apiKey: String? = null,
    public val headers: Map<String, String> = emptyMap(),
    public val queryParams: Map<String, String> = emptyMap(),
    public val includeUsage: Boolean = false,
    public val supportsStructuredOutputs: Boolean = false,
    public val supportedUrls: Map<String, List<String>> = emptyMap(),
    public val maxEmbeddingsPerCall: Int = 2048,
    public val authHeadersProvider: (suspend () -> Map<String, String>)? = null,
    public val urlBuilder: ((path: String, modelId: String) -> String)? = null,
    public val userAgentSuffix: String? = "ai-sdk/openai-compatible-kotlin",
    public val providerOptionsName: String? = null,
    public val chatMaxOutputTokensKey: String = "max_tokens",
    public val chatSeedKey: String = "seed",
    public val transformChatRequestBody: ((JsonObject) -> JsonObject)? = null,
    public val convertUsage: ((JsonElement?) -> Usage)? = null,
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

public class OpenAICompatibleProviderSettingsBuilder internal constructor() {
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

    public fun name(value: String) {
        name = value
    }

    public fun baseUrl(value: String) {
        baseUrl = value
    }

    public fun apiKey(value: String?) {
        apiKey = value
    }

    public fun headers(value: Map<String, String>) {
        headers = value
    }

    public fun queryParams(value: Map<String, String>) {
        queryParams = value
    }

    public fun includeUsage(value: Boolean) {
        includeUsage = value
    }

    public fun supportsStructuredOutputs(value: Boolean) {
        supportsStructuredOutputs = value
    }

    public fun supportedUrls(value: Map<String, List<String>>) {
        supportedUrls = value
    }

    public fun maxEmbeddingsPerCall(value: Int) {
        maxEmbeddingsPerCall = value
    }

    public fun authHeadersProvider(value: (suspend () -> Map<String, String>)?) {
        authHeadersProvider = value
    }

    public fun urlBuilder(value: ((path: String, modelId: String) -> String)?) {
        urlBuilder = value
    }

    public fun userAgentSuffix(value: String?) {
        userAgentSuffix = value
    }

    public fun providerOptionsName(value: String?) {
        providerOptionsName = value
    }

    public fun chatMaxOutputTokensKey(value: String) {
        chatMaxOutputTokensKey = value
    }

    public fun chatSeedKey(value: String) {
        chatSeedKey = value
    }

    public fun transformChatRequestBody(value: ((JsonObject) -> JsonObject)?) {
        transformChatRequestBody = value
    }

    public fun convertUsage(value: ((JsonElement?) -> Usage)?) {
        convertUsage = value
    }

    public fun transformChatResponse(value: ((JsonObject) -> JsonObject)?) {
        transformChatResponse = value
    }

    internal fun build(): OpenAICompatibleProviderSettings =
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

public fun OpenAICompatibleProviderSettings(
    block: OpenAICompatibleProviderSettingsBuilder.() -> Unit = {},
): OpenAICompatibleProviderSettings =
    OpenAICompatibleProviderSettingsBuilder().apply(block).build()

public interface OpenAICompatibleProvider : Provider {
    public fun chatModel(modelId: String): LanguageModel
    public fun completionModel(modelId: String): LanguageModel
    public fun textEmbeddingModel(modelId: String): EmbeddingModel = embeddingModel(modelId)
}

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
