package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

public const val MISTRAL_VERSION: String = "3.0.37"

public typealias MistralChatModelId = String
public typealias MistralEmbeddingModelId = String
public typealias MistralProviderOptions = MistralLanguageModelOptions

@Serializable
public data class MistralProviderSettings(
    val baseURL: String = "https://api.mistral.ai/v1",
    val apiKey: String? = null,
    val headers: Map<String, String> = emptyMap(),
)

@Serializable
public data class MistralLanguageModelOptions(
    val safePrompt: Boolean? = null,
    val documentImageLimit: Int? = null,
    val documentPageLimit: Int? = null,
    val structuredOutputs: Boolean? = null,
    val strictJsonSchema: Boolean? = null,
    val parallelToolCalls: Boolean? = null,
    val reasoningEffort: String? = null,
)

public interface MistralProvider : Provider {
    public val settings: MistralProviderSettings

    public operator fun invoke(modelId: MistralChatModelId): LanguageModel = chat(modelId)
    public fun chat(modelId: MistralChatModelId): LanguageModel
    public fun embedding(modelId: MistralEmbeddingModelId): EmbeddingModel

    override fun languageModel(modelId: String): LanguageModel = chat(modelId)
    override fun embeddingModel(modelId: String): EmbeddingModel = embedding(modelId)
    public fun textEmbedding(modelId: MistralEmbeddingModelId): EmbeddingModel = embedding(modelId)
    public fun textEmbeddingModel(modelId: MistralEmbeddingModelId): EmbeddingModel = embedding(modelId)
}

public fun createMistral(
    client: HttpClient,
    settings: MistralProviderSettings = MistralProviderSettings(),
): MistralProvider = DefaultMistralProvider(client, settings)

public val mistral: MistralProvider = object : MistralProvider {
    override val providerId: String = "mistral"
    override val settings: MistralProviderSettings = MistralProviderSettings()
    override fun chat(modelId: String): LanguageModel =
        throw AiSdkException("Mistral provider is not configured. Use createMistral(client, settings).")
    override fun embedding(modelId: String): EmbeddingModel =
        throw AiSdkException("Mistral provider is not configured. Use createMistral(client, settings).")
}

private class DefaultMistralProvider(
    client: HttpClient,
    override val settings: MistralProviderSettings,
) : MistralProvider {
    private val compatible = createOpenAICompatible(client, settings.toCompatible())

    override val providerId: String = "mistral"

    override fun chat(modelId: MistralChatModelId): LanguageModel =
        MistralChatLanguageModel(compatible.chatModel(modelId))

    override fun embedding(modelId: MistralEmbeddingModelId): EmbeddingModel =
        compatible.embeddingModel(modelId)

    override fun imageModel(modelId: String): ImageModel =
        throw NoSuchModelError(providerId, "imageModel", modelId)
}

private class MistralChatLanguageModel(
    private val delegate: LanguageModel,
) : LanguageModel by delegate {
    override suspend fun generate(params: LanguageModelCallParams): LanguageModelResult =
        delegate.generate(params.copy(providerOptions = transformMistralProviderOptions(params.providerOptions)))

    override fun stream(params: LanguageModelCallParams): Flow<StreamEvent> =
        delegate.stream(params.copy(providerOptions = transformMistralProviderOptions(params.providerOptions)))

    override fun streamResult(params: LanguageModelCallParams): LanguageModelStreamResult =
        delegate.streamResult(params.copy(providerOptions = transformMistralProviderOptions(params.providerOptions))).let {
            it.copy(stream = it.stream.map { event -> event })
        }
}

private fun MistralProviderSettings.toCompatible(): OpenAICompatibleProviderSettings =
    OpenAICompatibleProviderSettings(
        name = "mistral",
        baseUrl = baseURL.trimEnd('/'),
        apiKey = apiKey,
        headers = withUserAgentSuffix(headers, "ai-sdk/mistral/$MISTRAL_VERSION"),
        providerOptionsName = "mistral",
        supportsStructuredOutputs = true,
        chatSeedKey = "random_seed",
    )

private fun transformMistralProviderOptions(options: Map<String, JsonElement>): Map<String, JsonElement> {
    val mistral = options["mistral"] as? JsonObject ?: return options
    val transformed = buildJsonObject {
        for ((key, value) in mistral) {
            when (key) {
                "safePrompt" -> put("safe_prompt", value)
                "documentImageLimit" -> put("document_image_limit", value)
                "documentPageLimit" -> put("document_page_limit", value)
                "parallelToolCalls" -> put("parallel_tool_calls", value)
                "reasoningEffort" -> put("reasoning_effort", value)
                "structuredOutputs" -> put("structured_outputs", value)
                else -> put(key, value)
            }
        }
    }
    return options + ("mistral" to transformed)
}
