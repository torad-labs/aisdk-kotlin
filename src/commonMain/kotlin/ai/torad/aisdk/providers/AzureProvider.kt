package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import dev.drewhamilton.poko.Poko
import io.ktor.client.HttpClient
import io.ktor.http.HttpHeaders
import kotlinx.serialization.json.JsonElement

public const val AZURE_VERSION: String = "3.0.69"


@Poko
public class AzureOpenAIProviderSettings internal constructor(
    public val resourceName: String? = null,
    public val baseURL: String? = null,
    public val apiKey: String? = null,
    public val tokenProvider: (suspend () -> String)? = null,
    public val headers: Map<String, String> = emptyMap(),
    public val apiVersion: String = "v1",
    public val useDeploymentBasedUrls: Boolean = false,
)

public class AzureOpenAIProviderSettingsBuilder internal constructor() {
    private var resourceName: String? = null
    private var baseURL: String? = null
    private var apiKey: String? = null
    private var tokenProvider: (suspend () -> String)? = null
    private var headers: Map<String, String> = emptyMap()
    private var apiVersion: String = "v1"
    private var useDeploymentBasedUrls: Boolean = false

    public fun resourceName(value: String?) {
        resourceName = value
    }

    public fun baseURL(value: String?) {
        baseURL = value
    }

    public fun apiKey(value: String?) {
        apiKey = value
    }

    public fun tokenProvider(value: (suspend () -> String)?) {
        tokenProvider = value
    }

    public fun headers(value: Map<String, String>) {
        headers = value
    }

    public fun apiVersion(value: String) {
        apiVersion = value
    }

    public fun useDeploymentBasedUrls(value: Boolean) {
        useDeploymentBasedUrls = value
    }

    internal fun build(): AzureOpenAIProviderSettings =
        AzureOpenAIProviderSettings(
            resourceName = resourceName,
            baseURL = baseURL,
            apiKey = apiKey,
            tokenProvider = tokenProvider,
            headers = headers,
            apiVersion = apiVersion,
            useDeploymentBasedUrls = useDeploymentBasedUrls,
        )
}

public fun AzureOpenAIProviderSettings(
    block: AzureOpenAIProviderSettingsBuilder.() -> Unit = {},
): AzureOpenAIProviderSettings =
    AzureOpenAIProviderSettingsBuilder().apply(block).build()

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
            OpenResponsesProviderSettings {
                url(azureUrl("/responses", deploymentId))
                name("azure")
                authHeadersProvider { azureHeaders() }
                userAgentSuffix(null)
                providerOptionsName("openai")
                supportedUrls(OPENAI_RESPONSES_SUPPORTED_URLS)
                fileIdPrefixes(listOf("assistant-"))
            },
        ).responses(deploymentId)

    public fun chat(deploymentId: ModelId): LanguageModel =
        compatible.chatModel(deploymentId.value)

    public fun completion(deploymentId: ModelId): LanguageModel =
        compatible.completionModel(deploymentId.value)

    public fun embedding(deploymentId: ModelId): EmbeddingModel =
        AzureOpenAIEmbeddingModel(compatible.embeddingModel(deploymentId.value))

    public fun image(deploymentId: ModelId): ImageModel =
        compatible.imageModel(deploymentId.value)

    public fun transcription(deploymentId: ModelId): TranscriptionModel =
        compatible.transcriptionModel(deploymentId.value)

    public fun speech(deploymentId: ModelId): SpeechModel =
        compatible.speechModel(deploymentId.value)

    override fun languageModel(modelId: String): LanguageModel = responses(modelId)
    override fun embeddingModel(modelId: String): EmbeddingModel = embedding(ModelId(modelId))
    public fun textEmbedding(deploymentId: ModelId): EmbeddingModel = embedding(deploymentId)
    public fun textEmbeddingModel(deploymentId: ModelId): EmbeddingModel = embedding(deploymentId)
    override fun imageModel(modelId: String): ImageModel = image(ModelId(modelId))
    override fun transcriptionModel(modelId: String): TranscriptionModel = transcription(ModelId(modelId))
    override fun speechModel(modelId: String): SpeechModel = speech(ModelId(modelId))

    private fun compatibleSettings(): OpenAICompatibleProviderSettings =
        OpenAICompatibleProviderSettings {
            name("azure")
            baseUrl("https://azure.openai.invalid/openai/v1")
            authHeadersProvider { azureHeaders() }
            urlBuilder(::azureUrl)
            userAgentSuffix(null)
            providerOptionsName("openai")
            supportsStructuredOutputs(true)
        }

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

@Poko
public class AzureOpenAITools(
    public val codeInterpreter: Tool<JsonElement, JsonElement, Any?> = OpenAITools().codeInterpreter,
    public val fileSearch: Tool<JsonElement, JsonElement, Any?> = OpenAITools().fileSearch,
    public val imageGeneration: Tool<JsonElement, JsonElement, Any?> = OpenAITools().imageGeneration,
    public val webSearch: Tool<JsonElement, JsonElement, Any?> = OpenAITools().webSearch,
    public val webSearchPreview: Tool<JsonElement, JsonElement, Any?> = OpenAITools().webSearchPreview,
)

public val azureOpenaiTools: AzureOpenAITools = AzureOpenAITools()

private class AzureOpenAIEmbeddingModel(
    private val delegate: EmbeddingModel,
) : EmbeddingModel by delegate {
    override val provider: String = "azure.embeddings"
}
