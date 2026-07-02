package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import dev.drewhamilton.poko.Poko
import io.ktor.client.HttpClient
import io.ktor.http.HttpHeaders
import kotlinx.serialization.json.JsonElement

public const val AZURE_VERSION: String = "3.0.69"

@Poko
/** @since 0.3.0-beta01 */
public class AzureOpenAIProviderSettings internal constructor(
    /** @since 0.3.0-beta01 */
    public val resourceName: String? = null,
    /** @since 0.3.0-beta01 */
    public val baseURL: String? = null,
    /** @since 0.3.0-beta01 */
    public val apiKey: String? = null,
    /** @since 0.3.0-beta01 */
    public val tokenProvider: (suspend () -> String)? = null,
    /** @since 0.3.0-beta01 */
    public val headers: Map<String, String> = emptyMap(),
    /** @since 0.3.0-beta01 */
    public val apiVersion: String = "v1",
    /** @since 0.3.0-beta01 */
    public val useDeploymentBasedUrls: Boolean = false,
)

/** @since 0.3.0-beta01 */
public class AzureOpenAIProviderSettingsBuilder {
    private var resourceName: String? = null
    private var baseURL: String? = null
    private var apiKey: String? = null
    private var tokenProvider: (suspend () -> String)? = null
    private var headers: Map<String, String> = emptyMap()
    private var apiVersion: String = "v1"
    private var useDeploymentBasedUrls: Boolean = false

    /** @since 0.3.0-beta01 */
    public fun resourceName(value: String?): AzureOpenAIProviderSettingsBuilder {
        resourceName = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun baseURL(value: String?): AzureOpenAIProviderSettingsBuilder {
        baseURL = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun apiKey(value: String?): AzureOpenAIProviderSettingsBuilder {
        apiKey = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun tokenProvider(value: (suspend () -> String)?): AzureOpenAIProviderSettingsBuilder {
        tokenProvider = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun headers(value: Map<String, String>): AzureOpenAIProviderSettingsBuilder {
        headers = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun apiVersion(value: String): AzureOpenAIProviderSettingsBuilder {
        apiVersion = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun useDeploymentBasedUrls(value: Boolean): AzureOpenAIProviderSettingsBuilder {
        useDeploymentBasedUrls = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun build(): AzureOpenAIProviderSettings =
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

/** @since 0.3.0-beta01 */
public fun AzureOpenAIProviderSettings(
    block: AzureOpenAIProviderSettingsBuilder.() -> Unit = {},
): AzureOpenAIProviderSettings =
    AzureOpenAIProviderSettingsBuilder().apply(block).build()

/** @since 0.3.0-beta01 */
public class AzureOpenAIProvider(
    private val client: HttpClient,
    /** @since 0.3.0-beta01 */
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

    /** @since 0.3.0-beta01 */
    public val tools: AzureOpenAITools = azureOpenaiTools

    public operator fun invoke(deploymentId: String): LanguageModel = responses(deploymentId)

    /** @since 0.3.0-beta01 */
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

    /** @since 0.3.0-beta01 */
    public fun chat(deploymentId: ModelId): LanguageModel =
        compatible.chatModel(deploymentId.value)

    /** @since 0.3.0-beta01 */
    public fun completion(deploymentId: ModelId): LanguageModel =
        compatible.completionModel(deploymentId.value)

    /** @since 0.3.0-beta01 */
    public fun embedding(deploymentId: ModelId): EmbeddingModel =
        AzureOpenAIEmbeddingModel(compatible.embeddingModel(deploymentId.value))

    /** @since 0.3.0-beta01 */
    public fun image(deploymentId: ModelId): ImageModel =
        compatible.imageModel(deploymentId.value)

    /** @since 0.3.0-beta01 */
    public fun transcription(deploymentId: ModelId): TranscriptionModel =
        compatible.transcriptionModel(deploymentId.value)

    /** @since 0.3.0-beta01 */
    public fun speech(deploymentId: ModelId): SpeechModel =
        compatible.speechModel(deploymentId.value)

    override fun languageModel(modelId: String): LanguageModel = responses(modelId)
    override fun embeddingModel(modelId: String): EmbeddingModel = embedding(ModelId(modelId))

    /** @since 0.3.0-beta01 */
    public fun textEmbedding(deploymentId: ModelId): EmbeddingModel = embedding(deploymentId)

    /** @since 0.3.0-beta01 */
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

/**
 * PascalCase factory — mirrors `OpenAI(...)`.
 * @since 0.3.0-beta01
 */
public fun AzureOpenAI(
    client: HttpClient,
    settings: AzureOpenAIProviderSettings = AzureOpenAIProviderSettings(),
): AzureOpenAIProvider = AzureOpenAIProvider(client, settings)

@Poko
/** @since 0.3.0-beta01 */
public class AzureOpenAITools(
    /** @since 0.3.0-beta01 */
    public val codeInterpreter: Tool<JsonElement, JsonElement, Any?> = OpenAITools().codeInterpreter,
    /** @since 0.3.0-beta01 */
    public val fileSearch: Tool<JsonElement, JsonElement, Any?> = OpenAITools().fileSearch,
    /** @since 0.3.0-beta01 */
    public val imageGeneration: Tool<JsonElement, JsonElement, Any?> = OpenAITools().imageGeneration,
    /** @since 0.3.0-beta01 */
    public val webSearch: Tool<JsonElement, JsonElement, Any?> = OpenAITools().webSearch,
    /** @since 0.3.0-beta01 */
    public val webSearchPreview: Tool<JsonElement, JsonElement, Any?> = OpenAITools().webSearchPreview,
)

/** @since 0.3.0-beta01 */
public val azureOpenaiTools: AzureOpenAITools = AzureOpenAITools()

private class AzureOpenAIEmbeddingModel(
    private val delegate: EmbeddingModel,
) : EmbeddingModel by delegate {
    override val provider: String = "azure.embeddings"
}
