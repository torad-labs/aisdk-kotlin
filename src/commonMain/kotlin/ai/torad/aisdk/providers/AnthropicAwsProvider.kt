@file:OptIn(ai.torad.aisdk.LowLevelLanguageModelApi::class)

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
) {
    internal fun anthropicAwsBaseURL(): String =
        baseURL?.trimEnd('/')
            ?: "https://aws-external-anthropic.${region ?: "us-east-1"}.api.aws/v1"

    internal fun anthropicAwsHeaders(): Map<String, String> {
        val workspaceId = this.workspaceId
            ?: throw LoadSettingError("Anthropic AWS workspaceId is required. Provide workspaceId or ANTHROPIC_AWS_WORKSPACE_ID-style configuration.")
        return linkedMapOf<String, String>().apply {
            put("anthropic-workspace-id", workspaceId)
            put(HttpHeaders.UserAgent, "ai-sdk/anthropic-aws/$ANTHROPIC_AWS_VERSION")
            putAll(headers)
        }
    }

    internal suspend fun anthropicAwsSigV4Headers(
        url: String,
        body: String,
        headers: Map<String, String>,
        amzDate: String = AwsSigV4.currentAwsAmzDate(),
    ): Map<String, String> {
        val credentials = credentialProvider?.invoke()
            ?: AnthropicAwsCredentials(
                accessKeyId = accessKeyId.orEmpty(),
                secretAccessKey = secretAccessKey.orEmpty(),
                sessionToken = sessionToken,
                region = region,
            )
        if (credentials.accessKeyId.isBlank() || credentials.secretAccessKey.isBlank()) {
            throw LoadAPIKeyError("AWS SigV4 authentication requires both accessKeyId and secretAccessKey.")
        }
        return AwsSigV4.awsSigV4SignedHeaders(method = "POST",
        url = url,
        service = "aws-external-anthropic",
        region = credentials.region ?: region ?: "us-east-1",
        headers = headers + (HttpHeaders.ContentType to "application/json"),
        body = body,
        credentials = AwsSigV4Credentials(
            accessKeyId = credentials.accessKeyId,
            secretAccessKey = credentials.secretAccessKey,
            sessionToken = credentials.sessionToken,
        ),
        amzDate = amzDate,)
    }
}

public interface AnthropicAwsProvider : Provider {
    public val settings: AnthropicAwsProviderSettings
    public val tools: AnthropicTools

    public operator fun invoke(modelId: ModelId): LanguageModel = languageModel(modelId.value)
    public fun chat(modelId: ModelId): LanguageModel = languageModel(modelId.value)
    public fun messages(modelId: ModelId): LanguageModel = languageModel(modelId.value)
    public fun textEmbeddingModel(modelId: String): Nothing = throw NoSuchModelError(providerId, "embeddingModel", modelId)
}

public fun AnthropicAws(
    client: HttpClient,
    settings: AnthropicAwsProviderSettings = AnthropicAwsProviderSettings(),
): AnthropicAwsProvider = DefaultAnthropicAwsProvider(client, settings)

public val anthropicAws: AnthropicAwsProvider = object : AnthropicAwsProvider {
    override val providerId: String = "anthropic-aws"
    override val settings: AnthropicAwsProviderSettings = AnthropicAwsProviderSettings()
    override val tools: AnthropicTools = anthropicTools
    override fun languageModel(modelId: String): LanguageModel =
        throw UnsupportedFunctionalityError("anthropic-aws", "Anthropic AWS provider is not configured. Use AnthropicAws(client, settings).")
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
            baseURL = settings.anthropicAwsBaseURL(),
            apiKey = settings.apiKey,
            headers = settings.anthropicAwsHeaders(),
            requestHeadersProvider = if (settings.apiKey.isNullOrBlank()) {
                { url, body, headers -> settings.anthropicAwsSigV4Headers(url, body, headers) }
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
