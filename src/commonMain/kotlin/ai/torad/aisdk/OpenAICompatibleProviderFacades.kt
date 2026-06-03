package ai.torad.aisdk

import io.ktor.client.HttpClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

const val DEEPSEEK_VERSION: String = "2.0.35"
const val CEREBRAS_VERSION: String = "2.0.54"
const val GROQ_VERSION: String = "3.0.39"
const val MOONSHOTAI_VERSION: String = "2.0.23"
const val PERPLEXITY_VERSION: String = "3.0.33"

@Serializable
data class DeepSeekProviderSettings(
    val apiKey: String? = null,
    val baseURL: String = "https://api.deepseek.com",
    val headers: Map<String, String> = emptyMap(),
)

@Serializable
data class DeepSeekLanguageModelOptions(
    val raw: Map<String, JsonElement> = emptyMap(),
)

typealias DeepSeekChatOptions = DeepSeekLanguageModelOptions
typealias DeepSeekErrorData = JsonElement

interface DeepSeekProvider : Provider {
    operator fun invoke(modelId: String): LanguageModel = languageModel(modelId)
    fun chat(modelId: String): LanguageModel
    fun textEmbeddingModel(modelId: String): Nothing = throw NoSuchModelError(providerId, "embeddingModel", modelId)
}

fun createDeepSeek(
    client: HttpClient,
    settings: DeepSeekProviderSettings = DeepSeekProviderSettings(),
): DeepSeekProvider = DefaultDeepSeekProvider(client, settings)

val deepseek: DeepSeekProvider = providerNotConfigured("DeepSeek", "deepseek")

@Serializable
data class CerebrasProviderSettings(
    val apiKey: String? = null,
    val baseURL: String = "https://api.cerebras.ai/v1",
    val headers: Map<String, String> = emptyMap(),
)

@Serializable
data class CerebrasErrorData(
    val message: String,
    val type: String? = null,
    val param: String? = null,
    val code: String? = null,
)

interface CerebrasProvider : Provider {
    operator fun invoke(modelId: String): LanguageModel = languageModel(modelId)
    fun chat(modelId: String): LanguageModel
    fun textEmbeddingModel(modelId: String): Nothing = throw NoSuchModelError(providerId, "embeddingModel", modelId)
}

fun createCerebras(
    client: HttpClient,
    settings: CerebrasProviderSettings = CerebrasProviderSettings(),
): CerebrasProvider = DefaultCerebrasProvider(client, settings)

val cerebras: CerebrasProvider = providerNotConfigured("Cerebras", "cerebras")

@Serializable
data class PerplexityProviderSettings(
    val apiKey: String? = null,
    val baseURL: String = "https://api.perplexity.ai",
    val headers: Map<String, String> = emptyMap(),
)

interface PerplexityProvider : Provider {
    operator fun invoke(modelId: String): LanguageModel = languageModel(modelId)
    fun textEmbeddingModel(modelId: String): Nothing = throw NoSuchModelError(providerId, "embeddingModel", modelId)
}

fun createPerplexity(
    client: HttpClient,
    settings: PerplexityProviderSettings = PerplexityProviderSettings(),
): PerplexityProvider = DefaultPerplexityProvider(client, settings)

val perplexity: PerplexityProvider = providerNotConfigured("Perplexity", "perplexity")

typealias MoonshotAIChatModelId = String

@Serializable
data class MoonshotAIProviderSettings(
    val apiKey: String? = null,
    val baseURL: String = "https://api.moonshot.ai/v1",
    val headers: Map<String, String> = emptyMap(),
)

@Serializable
data class MoonshotAILanguageModelOptions(
    val raw: Map<String, JsonElement> = emptyMap(),
)

typealias MoonshotAIProviderOptions = MoonshotAILanguageModelOptions

interface MoonshotAIProvider : Provider {
    operator fun invoke(modelId: MoonshotAIChatModelId): LanguageModel = languageModel(modelId)
    fun chatModel(modelId: MoonshotAIChatModelId): LanguageModel
}

fun createMoonshotAI(
    client: HttpClient,
    settings: MoonshotAIProviderSettings = MoonshotAIProviderSettings(),
): MoonshotAIProvider = DefaultMoonshotAIProvider(client, settings)

val moonshotai: MoonshotAIProvider = providerNotConfigured("MoonshotAI", "moonshotai")

@Serializable
data class GroqProviderSettings(
    val apiKey: String? = null,
    val baseURL: String = "https://api.groq.com/openai/v1",
    val headers: Map<String, String> = emptyMap(),
)

@Serializable
data class GroqLanguageModelOptions(
    val raw: Map<String, JsonElement> = emptyMap(),
)

typealias GroqProviderOptions = GroqLanguageModelOptions

@Serializable
data class GroqTranscriptionModelOptions(
    val language: String? = null,
    val prompt: String? = null,
    val temperature: Float? = null,
    val responseFormat: String? = null,
)

interface GroqProvider : Provider {
    val tools: GroqTools

    operator fun invoke(modelId: String): LanguageModel = languageModel(modelId)
    fun chat(modelId: String): LanguageModel
    fun transcription(modelId: String): TranscriptionModel
    fun textEmbeddingModel(modelId: String): Nothing = throw NoSuchModelError(providerId, "embeddingModel", modelId)

    override fun transcriptionModel(modelId: String): TranscriptionModel = transcription(modelId)
}

fun createGroq(
    client: HttpClient,
    settings: GroqProviderSettings = GroqProviderSettings(),
): GroqProvider = DefaultGroqProvider(client, settings)

private val groqBrowserSearchTool: Tool<JsonElement, JsonElement, Any?> = providerExecutedTool(
    name = "browserSearch",
    description = "Browser search tool for Groq models.",
    inputSerializer = JsonElement.serializer(),
    outputSerializer = JsonElement.serializer(),
    metadata = mapOf("providerToolId" to JsonPrimitive("groq.browser_search")),
)

data class GroqTools(
    val browserSearch: Tool<JsonElement, JsonElement, Any?> = groqBrowserSearchTool,
)

val browserSearch: Tool<JsonElement, JsonElement, Any?> = groqBrowserSearchTool

val groq: GroqProvider = providerNotConfigured("Groq", "groq")

private class DefaultDeepSeekProvider(
    client: HttpClient,
    private val settings: DeepSeekProviderSettings,
) : DeepSeekProvider {
    private val compatible = createOpenAICompatible(
        client,
        settings.toCompatible("deepseek", DEEPSEEK_VERSION, supportsStructuredOutputs = false),
    )
    override val providerId: String = "deepseek"
    override fun languageModel(modelId: String): LanguageModel = chat(modelId)
    override fun chat(modelId: String): LanguageModel = compatible.chatModel(modelId)
    override fun embeddingModel(modelId: String): EmbeddingModel = throw NoSuchModelError(providerId, "embeddingModel", modelId)
    override fun imageModel(modelId: String): ImageModel = throw NoSuchModelError(providerId, "imageModel", modelId)
}

private class DefaultCerebrasProvider(
    client: HttpClient,
    private val settings: CerebrasProviderSettings,
) : CerebrasProvider {
    private val compatible = createOpenAICompatible(
        client,
        settings.toCompatible("cerebras", CEREBRAS_VERSION, supportsStructuredOutputs = true),
    )
    override val providerId: String = "cerebras"
    override fun languageModel(modelId: String): LanguageModel = chat(modelId)
    override fun chat(modelId: String): LanguageModel = compatible.chatModel(modelId)
    override fun embeddingModel(modelId: String): EmbeddingModel = throw NoSuchModelError(providerId, "embeddingModel", modelId)
    override fun imageModel(modelId: String): ImageModel = throw NoSuchModelError(providerId, "imageModel", modelId)
}

private class DefaultPerplexityProvider(
    client: HttpClient,
    private val settings: PerplexityProviderSettings,
) : PerplexityProvider {
    private val compatible = createOpenAICompatible(
        client,
        settings.toCompatible("perplexity", PERPLEXITY_VERSION, supportsStructuredOutputs = false),
    )
    override val providerId: String = "perplexity"
    override fun languageModel(modelId: String): LanguageModel = compatible.chatModel(modelId)
    override fun embeddingModel(modelId: String): EmbeddingModel = throw NoSuchModelError(providerId, "embeddingModel", modelId)
    override fun imageModel(modelId: String): ImageModel = throw NoSuchModelError(providerId, "imageModel", modelId)
}

private class DefaultMoonshotAIProvider(
    client: HttpClient,
    private val settings: MoonshotAIProviderSettings,
) : MoonshotAIProvider {
    private val compatible = createOpenAICompatible(
        client,
        settings.toCompatible("moonshotai", MOONSHOTAI_VERSION, includeUsage = true),
    )
    override val providerId: String = "moonshotai"
    override fun languageModel(modelId: String): LanguageModel = chatModel(modelId)
    override fun chatModel(modelId: String): LanguageModel = compatible.chatModel(modelId)
    override fun embeddingModel(modelId: String): EmbeddingModel = throw NoSuchModelError(providerId, "embeddingModel", modelId)
    override fun imageModel(modelId: String): ImageModel = throw NoSuchModelError(providerId, "imageModel", modelId)
}

private class DefaultGroqProvider(
    client: HttpClient,
    private val settings: GroqProviderSettings,
) : GroqProvider {
    private val compatible = createOpenAICompatible(
        client,
        settings.toCompatible("groq", GROQ_VERSION, includeUsage = true),
    )
    override val providerId: String = "groq"
    override val tools: GroqTools = GroqTools()
    override fun languageModel(modelId: String): LanguageModel = chat(modelId)
    override fun chat(modelId: String): LanguageModel = compatible.chatModel(modelId)
    override fun transcription(modelId: String): TranscriptionModel = compatible.transcriptionModel(modelId)
    override fun embeddingModel(modelId: String): EmbeddingModel = throw NoSuchModelError(providerId, "embeddingModel", modelId)
    override fun imageModel(modelId: String): ImageModel = throw NoSuchModelError(providerId, "imageModel", modelId)
}

private fun DeepSeekProviderSettings.toCompatible(
    name: String,
    version: String,
    includeUsage: Boolean = false,
    supportsStructuredOutputs: Boolean = false,
): OpenAICompatibleProviderSettings =
    compatibleSettings(name, version, baseURL, apiKey, headers, includeUsage, supportsStructuredOutputs)

private fun CerebrasProviderSettings.toCompatible(
    name: String,
    version: String,
    includeUsage: Boolean = false,
    supportsStructuredOutputs: Boolean = false,
): OpenAICompatibleProviderSettings =
    compatibleSettings(name, version, baseURL, apiKey, headers, includeUsage, supportsStructuredOutputs)

private fun PerplexityProviderSettings.toCompatible(
    name: String,
    version: String,
    includeUsage: Boolean = false,
    supportsStructuredOutputs: Boolean = false,
): OpenAICompatibleProviderSettings =
    compatibleSettings(name, version, baseURL, apiKey, headers, includeUsage, supportsStructuredOutputs)

private fun MoonshotAIProviderSettings.toCompatible(
    name: String,
    version: String,
    includeUsage: Boolean = false,
    supportsStructuredOutputs: Boolean = false,
): OpenAICompatibleProviderSettings =
    compatibleSettings(name, version, baseURL, apiKey, headers, includeUsage, supportsStructuredOutputs)

private fun GroqProviderSettings.toCompatible(
    name: String,
    version: String,
    includeUsage: Boolean = false,
    supportsStructuredOutputs: Boolean = false,
): OpenAICompatibleProviderSettings =
    compatibleSettings(name, version, baseURL, apiKey, headers, includeUsage, supportsStructuredOutputs)

private fun compatibleSettings(
    name: String,
    version: String,
    baseURL: String,
    apiKey: String?,
    headers: Map<String, String>,
    includeUsage: Boolean,
    supportsStructuredOutputs: Boolean,
): OpenAICompatibleProviderSettings =
    OpenAICompatibleProviderSettings(
        name = name,
        baseUrl = baseURL.trimEnd('/'),
        apiKey = apiKey,
        headers = withUserAgentSuffix(headers, "ai-sdk/$name/$version"),
        includeUsage = includeUsage,
        supportsStructuredOutputs = supportsStructuredOutputs,
    )

private inline fun <reified T : Provider> providerNotConfigured(
    providerName: String,
    providerId: String,
): T {
    val error = object : Provider {
        override val providerId: String = providerId
        override fun languageModel(modelId: String): LanguageModel =
            throw AiSdkException("$providerName provider is not configured. Use create$providerName(client, settings).")
    }
    @Suppress("UNCHECKED_CAST")
    return when (T::class) {
        DeepSeekProvider::class -> object : DeepSeekProvider, Provider by error {
            override fun chat(modelId: String): LanguageModel = error.languageModel(modelId)
        } as T
        CerebrasProvider::class -> object : CerebrasProvider, Provider by error {
            override fun chat(modelId: String): LanguageModel = error.languageModel(modelId)
        } as T
        PerplexityProvider::class -> object : PerplexityProvider, Provider by error {} as T
        MoonshotAIProvider::class -> object : MoonshotAIProvider, Provider by error {
            override fun chatModel(modelId: String): LanguageModel = error.languageModel(modelId)
        } as T
        GroqProvider::class -> object : GroqProvider, Provider by error {
            override val tools: GroqTools = GroqTools()
            override fun chat(modelId: String): LanguageModel = error.languageModel(modelId)
            override fun transcription(modelId: String): TranscriptionModel =
                throw AiSdkException("$providerName provider is not configured. Use create$providerName(client, settings).")
            override fun transcriptionModel(modelId: String): TranscriptionModel = transcription(modelId)
        } as T
        else -> error("Unsupported provider facade type: ${T::class}")
    }
}
