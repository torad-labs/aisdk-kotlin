package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import io.ktor.client.HttpClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

public data class OpenAICompatibleProviderSettings(
    val name: String,
    val baseUrl: String,
    val apiKey: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val queryParams: Map<String, String> = emptyMap(),
    val includeUsage: Boolean = false,
    val supportsStructuredOutputs: Boolean = false,
    val supportedUrls: Map<String, List<String>> = emptyMap(),
    val maxEmbeddingsPerCall: Int = 2048,
    val authHeadersProvider: (suspend () -> Map<String, String>)? = null,
    val urlBuilder: ((path: String, modelId: String) -> String)? = null,
    val userAgentSuffix: String? = "ai-sdk/openai-compatible-kotlin",
    val providerOptionsName: String? = null,
    val chatMaxOutputTokensKey: String = "max_tokens",
    val chatSeedKey: String = "seed",
    val transformChatRequestBody: ((JsonObject) -> JsonObject)? = null,
    val convertUsage: ((JsonElement?) -> Usage)? = null,
    val transformChatResponse: ((JsonObject) -> JsonObject)? = null,
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
            OpenAICompatibleProviderSettings(
                name = name,
                baseUrl = baseURL.trimEnd('/'),
                apiKey = apiKey,
                headers = ProviderHeaders.withUserAgentSuffix(headers, "ai-sdk/$name/$version"),
                // UA already embedded in headers above — null out the default suffix so commonHeaders()
                // doesn't APPEND "ai-sdk/openai-compatible-kotlin" again (double User-Agent).
                userAgentSuffix = null,
                includeUsage = capabilities.includeUsage,
                supportsStructuredOutputs = capabilities.supportsStructuredOutputs,
                transformChatRequestBody = transformChatRequestBody,
                convertUsage = convertUsage,
                transformChatResponse = transformChatResponse,
            )
    }
}

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
