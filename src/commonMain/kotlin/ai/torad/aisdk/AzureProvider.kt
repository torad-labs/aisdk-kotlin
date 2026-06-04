package ai.torad.aisdk

import io.ktor.client.HttpClient
import io.ktor.http.HttpHeaders
import kotlinx.serialization.json.JsonElement

const val AZURE_VERSION: String = "3.0.69"

typealias AzureOpenAIChatModelId = String
typealias AzureOpenAICompletionModelId = String
typealias AzureOpenAIEmbeddingModelId = String
typealias AzureOpenAIImageModelId = String
typealias AzureOpenAITranscriptionModelId = String
typealias AzureOpenAISpeechModelId = String

data class AzureOpenAIProviderSettings(
    val resourceName: String? = null,
    val baseURL: String? = null,
    val apiKey: String? = null,
    val tokenProvider: (suspend () -> String)? = null,
    val headers: Map<String, String> = emptyMap(),
    val apiVersion: String = "v1",
    val useDeploymentBasedUrls: Boolean = false,
)

interface AzureOpenAIProvider : Provider {
    val settings: AzureOpenAIProviderSettings
    val tools: AzureOpenAITools

    operator fun invoke(deploymentId: String): LanguageModel = responses(deploymentId)
    fun responses(deploymentId: String): LanguageModel
    fun chat(deploymentId: AzureOpenAIChatModelId): LanguageModel
    fun completion(deploymentId: AzureOpenAICompletionModelId): LanguageModel
    fun embedding(deploymentId: AzureOpenAIEmbeddingModelId): EmbeddingModel
    fun image(deploymentId: AzureOpenAIImageModelId): ImageModel
    fun transcription(deploymentId: AzureOpenAITranscriptionModelId): TranscriptionModel
    fun speech(deploymentId: AzureOpenAISpeechModelId): SpeechModel

    override fun languageModel(modelId: String): LanguageModel = responses(modelId)
    override fun embeddingModel(modelId: String): EmbeddingModel = embedding(modelId)
    fun textEmbedding(deploymentId: AzureOpenAIEmbeddingModelId): EmbeddingModel = embedding(deploymentId)
    fun textEmbeddingModel(deploymentId: AzureOpenAIEmbeddingModelId): EmbeddingModel = embedding(deploymentId)
    override fun imageModel(modelId: String): ImageModel = image(modelId)
    override fun transcriptionModel(modelId: String): TranscriptionModel = transcription(modelId)
    override fun speechModel(modelId: String): SpeechModel = speech(modelId)
}

data class AzureOpenAITools(
    val codeInterpreter: Tool<JsonElement, JsonElement, Any?> = OpenAITools().codeInterpreter,
    val fileSearch: Tool<JsonElement, JsonElement, Any?> = OpenAITools().fileSearch,
    val imageGeneration: Tool<JsonElement, JsonElement, Any?> = OpenAITools().imageGeneration,
    val webSearch: Tool<JsonElement, JsonElement, Any?> = OpenAITools().webSearch,
    val webSearchPreview: Tool<JsonElement, JsonElement, Any?> = OpenAITools().webSearchPreview,
)

val azureOpenaiTools: AzureOpenAITools = AzureOpenAITools()

fun createAzure(
    client: HttpClient,
    settings: AzureOpenAIProviderSettings = AzureOpenAIProviderSettings(),
): AzureOpenAIProvider {
    if (settings.apiKey != null && settings.tokenProvider != null) {
        throw InvalidArgumentError(
            "apiKey/tokenProvider",
            "Both apiKey and tokenProvider were provided. Please use only one authentication method.",
        )
    }
    return DefaultAzureOpenAIProvider(client, settings)
}

val azure: AzureOpenAIProvider = AzureOpenAIProviderNotConfigured

private object AzureOpenAIProviderNotConfigured : AzureOpenAIProvider {
    override val settings: AzureOpenAIProviderSettings = AzureOpenAIProviderSettings()
    override val providerId: String = "azure"
    override val tools: AzureOpenAITools = azureOpenaiTools

    override fun responses(deploymentId: String): LanguageModel = missing()
    override fun chat(deploymentId: AzureOpenAIChatModelId): LanguageModel = missing()
    override fun completion(deploymentId: AzureOpenAICompletionModelId): LanguageModel = missing()
    override fun embedding(deploymentId: AzureOpenAIEmbeddingModelId): EmbeddingModel = missing()
    override fun image(deploymentId: AzureOpenAIImageModelId): ImageModel = missing()
    override fun transcription(deploymentId: AzureOpenAITranscriptionModelId): TranscriptionModel = missing()
    override fun speech(deploymentId: AzureOpenAISpeechModelId): SpeechModel = missing()

    private fun missing(): Nothing = throw AiSdkException("Azure OpenAI provider is not configured. Use createAzure(client, settings).")
}

private class DefaultAzureOpenAIProvider(
    private val client: HttpClient,
    override val settings: AzureOpenAIProviderSettings,
) : AzureOpenAIProvider {
    private val compatible = createOpenAICompatible(client, compatibleSettings())

    override val providerId: String = "azure"
    override val tools: AzureOpenAITools = azureOpenaiTools

    override fun responses(deploymentId: String): LanguageModel =
        createOpenResponses(
            client,
            OpenResponsesProviderSettings(
                url = azureUrl("/responses", deploymentId),
                name = "azure",
                authHeadersProvider = { azureHeaders() },
                userAgentSuffix = null,
                providerOptionsName = "openai",
                supportedUrls = OPENAI_RESPONSES_SUPPORTED_URLS,
                fileIdPrefixes = listOf("assistant-"),
            ),
        ).responses(deploymentId)

    override fun chat(deploymentId: AzureOpenAIChatModelId): LanguageModel =
        compatible.chatModel(deploymentId)

    override fun completion(deploymentId: AzureOpenAICompletionModelId): LanguageModel =
        compatible.completionModel(deploymentId)

    override fun embedding(deploymentId: AzureOpenAIEmbeddingModelId): EmbeddingModel =
        AzureOpenAIEmbeddingModel(compatible.embeddingModel(deploymentId))

    override fun image(deploymentId: AzureOpenAIImageModelId): ImageModel =
        compatible.imageModel(deploymentId)

    override fun transcription(deploymentId: AzureOpenAITranscriptionModelId): TranscriptionModel =
        compatible.transcriptionModel(deploymentId)

    override fun speech(deploymentId: AzureOpenAISpeechModelId): SpeechModel =
        compatible.speechModel(deploymentId)

    private fun compatibleSettings(): OpenAICompatibleProviderSettings =
        OpenAICompatibleProviderSettings(
            name = "azure",
            baseUrl = "https://azure.openai.invalid/openai/v1",
            authHeadersProvider = { azureHeaders() },
            urlBuilder = ::azureUrl,
            userAgentSuffix = null,
            providerOptionsName = "openai",
            supportsStructuredOutputs = true,
        )

    private suspend fun azureHeaders(): Map<String, String> {
        val base = linkedMapOf<String, String>()
        val tokenProvider = settings.tokenProvider
        if (tokenProvider != null) {
            base[HttpHeaders.Authorization] = "Bearer ${tokenProvider()}"
        } else {
            settings.apiKey?.takeIf { it.isNotBlank() }?.let { base["api-key"] = it }
        }
        base.putAll(settings.headers)
        return withUserAgentSuffix(base, "ai-sdk/azure/$AZURE_VERSION")
    }

    private fun azureUrl(path: String, modelId: String): String {
        val baseUrlPrefix = settings.baseURL?.trimEnd('/')
            ?: settings.resourceName?.takeIf { it.isNotBlank() }?.let { "https://$it.openai.azure.com/openai" }
            ?: throw InvalidArgumentError("resourceName", "Azure OpenAI resourceName or baseURL must be provided")
        val endpoint = if (settings.useDeploymentBasedUrls) {
            "$baseUrlPrefix/deployments/$modelId$path"
        } else {
            "$baseUrlPrefix/v1$path"
        }
        val separator = if ('?' in endpoint) "&" else "?"
        return "$endpoint${separator}api-version=${urlEncode(settings.apiVersion)}"
    }
}

private class AzureOpenAIEmbeddingModel(
    private val delegate: EmbeddingModel,
) : EmbeddingModel by delegate {
    override val provider: String = "azure.embeddings"
}

