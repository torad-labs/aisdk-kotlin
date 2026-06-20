package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import io.ktor.client.HttpClient
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

public const val ANTHROPIC_AWS_VERSION: String = "1.0.3"

public typealias AnthropicAwsCredentials = BedrockCredentials

@Serializable
public data class AnthropicAwsProviderSettings(
    val region: String? = null,
    val workspaceId: String? = null,
    val apiKey: String? = null,
    val accessKeyId: String? = null,
    val secretAccessKey: String? = null,
    val sessionToken: String? = null,
    val baseURL: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val credentialProvider: (suspend () -> AnthropicAwsCredentials)? = null,
    val generateId: () -> String = { IdGenerator.generate() },
)

public interface AnthropicAwsProvider : Provider {
    public val settings: AnthropicAwsProviderSettings
    public val tools: AnthropicTools

    public operator fun invoke(modelId: AnthropicMessagesModelId): LanguageModel = languageModel(modelId)
    public fun chat(modelId: AnthropicMessagesModelId): LanguageModel = languageModel(modelId)
    public fun messages(modelId: AnthropicMessagesModelId): LanguageModel = languageModel(modelId)
    public fun textEmbeddingModel(modelId: String): Nothing = throw NoSuchModelError(providerId, "embeddingModel", modelId)
}

public fun createAnthropicAws(
    client: HttpClient,
    settings: AnthropicAwsProviderSettings = AnthropicAwsProviderSettings(),
): AnthropicAwsProvider = DefaultAnthropicAwsProvider(client, settings)

public val anthropicAws: AnthropicAwsProvider = object : AnthropicAwsProvider {
    override val providerId: String = "anthropic-aws"
    override val settings: AnthropicAwsProviderSettings = AnthropicAwsProviderSettings()
    override val tools: AnthropicTools = anthropicTools
    override fun languageModel(modelId: String): LanguageModel =
        throw AiSdkRuntimeException("Anthropic AWS provider is not configured. Use createAnthropicAws(client, settings).")
    override fun embeddingModel(modelId: String): EmbeddingModel = throw NoSuchModelError(providerId, "embeddingModel", modelId)
    override fun imageModel(modelId: String): ImageModel = throw NoSuchModelError(providerId, "imageModel", modelId)
}

private class DefaultAnthropicAwsProvider(
    private val client: HttpClient,
    override val settings: AnthropicAwsProviderSettings,
) : AnthropicAwsProvider {
    override val providerId: String = "anthropic-aws"
    override val tools: AnthropicTools = anthropicTools

    override fun languageModel(modelId: String): LanguageModel =
        AnthropicAwsMessagesLanguageModel(client, settings, modelId)

    override fun embeddingModel(modelId: String): EmbeddingModel = throw NoSuchModelError(providerId, "embeddingModel", modelId)
    override fun imageModel(modelId: String): ImageModel = throw NoSuchModelError(providerId, "imageModel", modelId)
}

private class AnthropicAwsMessagesLanguageModel(
    client: HttpClient,
    private val settings: AnthropicAwsProviderSettings,
    override val modelId: String,
) : LanguageModel {
    private val delegate = AnthropicMessagesLanguageModel(
        client = client,
        settings = AnthropicProviderSettings(
            baseURL = anthropicAwsBaseURL(settings),
            apiKey = settings.apiKey,
            headers = anthropicAwsHeaders(settings),
            requestHeadersProvider = if (settings.apiKey.isNullOrBlank()) {
                { url, body, headers -> anthropicAwsSigV4Headers(settings, url, body, headers) }
            } else {
                null
            },
            generateId = settings.generateId,
            name = "anthropic-aws.messages",
        ),
        modelId = modelId,
    )

    override val provider: String = "anthropic-aws.messages"
    override val supportedUrls: Map<String, List<String>> = delegate.supportedUrls

    override suspend fun generate(params: LanguageModelCallParams): LanguageModelResult {
        return delegate.generate(params)
    }

    override fun stream(params: LanguageModelCallParams): Flow<StreamEvent> {
        return delegate.stream(params)
    }

    override fun streamResult(params: LanguageModelCallParams): LanguageModelStreamResult {
        return delegate.streamResult(params)
    }
}

private fun anthropicAwsBaseURL(settings: AnthropicAwsProviderSettings): String =
    settings.baseURL?.trimEnd('/')
        ?: "https://aws-external-anthropic.${settings.region ?: "us-east-1"}.api.aws/v1"

private fun anthropicAwsHeaders(settings: AnthropicAwsProviderSettings): Map<String, String> {
    val workspaceId = settings.workspaceId
        ?: throw AiSdkRuntimeException("Anthropic AWS workspaceId is required. Provide workspaceId or ANTHROPIC_AWS_WORKSPACE_ID-style configuration.")
    return linkedMapOf<String, String>().apply {
        put("anthropic-workspace-id", workspaceId)
        put(HttpHeaders.UserAgent, "ai-sdk/anthropic-aws/$ANTHROPIC_AWS_VERSION")
        putAll(settings.headers)
    }
}

internal suspend fun anthropicAwsSigV4Headers(
    settings: AnthropicAwsProviderSettings,
    url: String,
    body: String,
    headers: Map<String, String>,
    amzDate: String = currentAwsAmzDate(),
): Map<String, String> {
    val credentials = settings.credentialProvider?.invoke()
        ?: AnthropicAwsCredentials(
            accessKeyId = settings.accessKeyId.orEmpty(),
            secretAccessKey = settings.secretAccessKey.orEmpty(),
            sessionToken = settings.sessionToken,
            region = settings.region,
        )
    if (credentials.accessKeyId.isBlank() || credentials.secretAccessKey.isBlank()) {
        throw AiSdkRuntimeException("AWS SigV4 authentication requires both accessKeyId and secretAccessKey.")
    }
    return awsSigV4SignedHeaders(
        method = "POST",
        url = url,
        service = "aws-external-anthropic",
        region = credentials.region ?: settings.region ?: "us-east-1",
        headers = headers + (HttpHeaders.ContentType to "application/json"),
        body = body,
        credentials = AwsSigV4Credentials(
            accessKeyId = credentials.accessKeyId,
            secretAccessKey = credentials.secretAccessKey,
            sessionToken = credentials.sessionToken,
        ),
        amzDate = amzDate,
    )
}
