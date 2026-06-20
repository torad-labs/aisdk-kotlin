package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import io.ktor.client.HttpClient
import io.ktor.http.HttpHeaders
import kotlinx.serialization.json.JsonElement

public const val AZURE_VERSION: String = "3.0.69"

public typealias AzureOpenAIChatModelId = String
public typealias AzureOpenAICompletionModelId = String
public typealias AzureOpenAIEmbeddingModelId = String
public typealias AzureOpenAIImageModelId = String
public typealias AzureOpenAITranscriptionModelId = String
public typealias AzureOpenAISpeechModelId = String

public data class AzureOpenAIProviderSettings(
    val resourceName: String? = null,
    val baseURL: String? = null,
    val apiKey: String? = null,
    val tokenProvider: (suspend () -> String)? = null,
    val headers: Map<String, String> = emptyMap(),
    val apiVersion: String = "v1",
    val useDeploymentBasedUrls: Boolean = false,
)

public class AzureOpenAIProvider(
    private val client: HttpClient,
    public val settings: AzureOpenAIProviderSettings,
) : Provider {
    init {
        if (settings.apiKey != null && settings.tokenProvider != null) {
            throw InvalidArgumentError(
                "apiKey/tokenProvider",
                "Both apiKey and tokenProvider were provided. Please use only one authentication method.",
            )
        }
    }

    private val compatible = OpenAICompatible(client, compatibleSettings())

    override val providerId: String = "azure"
    public val tools: AzureOpenAITools = azureOpenaiTools

    public operator fun invoke(deploymentId: String): LanguageModel = responses(deploymentId)

    public fun responses(deploymentId: String): LanguageModel =
        OpenResponses(
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

    public fun chat(deploymentId: AzureOpenAIChatModelId): LanguageModel =
        compatible.chatModel(deploymentId)

    public fun completion(deploymentId: AzureOpenAICompletionModelId): LanguageModel =
        compatible.completionModel(deploymentId)

    public fun embedding(deploymentId: AzureOpenAIEmbeddingModelId): EmbeddingModel =
        AzureOpenAIEmbeddingModel(compatible.embeddingModel(deploymentId))

    public fun image(deploymentId: AzureOpenAIImageModelId): ImageModel =
        compatible.imageModel(deploymentId)

    public fun transcription(deploymentId: AzureOpenAITranscriptionModelId): TranscriptionModel =
        compatible.transcriptionModel(deploymentId)

    public fun speech(deploymentId: AzureOpenAISpeechModelId): SpeechModel =
        compatible.speechModel(deploymentId)

    override fun languageModel(modelId: String): LanguageModel = responses(modelId)
    override fun embeddingModel(modelId: String): EmbeddingModel = embedding(modelId)
    public fun textEmbedding(deploymentId: AzureOpenAIEmbeddingModelId): EmbeddingModel = embedding(deploymentId)
    public fun textEmbeddingModel(deploymentId: AzureOpenAIEmbeddingModelId): EmbeddingModel = embedding(deploymentId)
    override fun imageModel(modelId: String): ImageModel = image(modelId)
    override fun transcriptionModel(modelId: String): TranscriptionModel = transcription(modelId)
    override fun speechModel(modelId: String): SpeechModel = speech(modelId)

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
        return ProviderHeaders.withUserAgentSuffix(base, "ai-sdk/azure/$AZURE_VERSION")
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
        return "$endpoint${separator}api-version=${UrlOps.encode(settings.apiVersion)}"
    }
}

/** PascalCase factory — mirrors `OpenAI(...)`. */
public fun AzureOpenAI(
    client: HttpClient,
    settings: AzureOpenAIProviderSettings = AzureOpenAIProviderSettings(),
): AzureOpenAIProvider = AzureOpenAIProvider(client, settings)

public data class AzureOpenAITools(
    val codeInterpreter: Tool<JsonElement, JsonElement, Any?> = OpenAITools().codeInterpreter,
    val fileSearch: Tool<JsonElement, JsonElement, Any?> = OpenAITools().fileSearch,
    val imageGeneration: Tool<JsonElement, JsonElement, Any?> = OpenAITools().imageGeneration,
    val webSearch: Tool<JsonElement, JsonElement, Any?> = OpenAITools().webSearch,
    val webSearchPreview: Tool<JsonElement, JsonElement, Any?> = OpenAITools().webSearchPreview,
)

public val azureOpenaiTools: AzureOpenAITools = AzureOpenAITools()

private class AzureOpenAIEmbeddingModel(
    private val delegate: EmbeddingModel,
) : EmbeddingModel by delegate {
    override val provider: String = "azure.embeddings"
}
