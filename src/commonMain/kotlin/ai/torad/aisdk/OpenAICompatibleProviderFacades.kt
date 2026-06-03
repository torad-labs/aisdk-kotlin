package ai.torad.aisdk

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

const val DEEPSEEK_VERSION: String = "2.0.35"
const val CEREBRAS_VERSION: String = "2.0.54"
const val DEEPINFRA_VERSION: String = "2.0.52"
const val FIREWORKS_VERSION: String = "2.0.53"
const val GROQ_VERSION: String = "3.0.39"
const val MOONSHOTAI_VERSION: String = "2.0.23"
const val PERPLEXITY_VERSION: String = "3.0.33"
const val TOGETHERAI_VERSION: String = "2.0.53"

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

typealias DeepInfraChatModelId = String
typealias DeepInfraCompletionModelId = String
typealias DeepInfraEmbeddingModelId = String
typealias DeepInfraImageModelId = String
typealias DeepInfraErrorData = JsonElement

@Serializable
data class DeepInfraProviderSettings(
    val apiKey: String? = null,
    val baseURL: String = "https://api.deepinfra.com/v1",
    val headers: Map<String, String> = emptyMap(),
)

interface DeepInfraProvider : Provider {
    operator fun invoke(modelId: DeepInfraChatModelId): LanguageModel = languageModel(modelId)
    fun chatModel(modelId: DeepInfraChatModelId): LanguageModel
    fun completionModel(modelId: DeepInfraCompletionModelId): LanguageModel
    fun textEmbeddingModel(modelId: DeepInfraEmbeddingModelId): EmbeddingModel = embeddingModel(modelId)
    fun image(modelId: DeepInfraImageModelId): ImageModel = imageModel(modelId)
}

fun createDeepInfra(
    client: HttpClient,
    settings: DeepInfraProviderSettings = DeepInfraProviderSettings(),
): DeepInfraProvider = DefaultDeepInfraProvider(client, settings)

val deepinfra: DeepInfraProvider = providerNotConfigured("DeepInfra", "deepinfra")

typealias FireworksChatModelId = String
typealias FireworksCompletionModelId = String
typealias FireworksEmbeddingModelId = String
typealias FireworksImageModelId = String

@Serializable
data class FireworksThinkingOptions(
    val type: String? = null,
    val budgetTokens: Int? = null,
)

@Serializable
data class FireworksLanguageModelOptions(
    val thinking: FireworksThinkingOptions? = null,
    val reasoningHistory: String? = null,
    val raw: Map<String, JsonElement> = emptyMap(),
)

typealias FireworksProviderOptions = FireworksLanguageModelOptions

@Serializable
data class FireworksEmbeddingModelOptions(
    val raw: Map<String, JsonElement> = emptyMap(),
)

typealias FireworksEmbeddingProviderOptions = FireworksEmbeddingModelOptions

@Serializable
data class FireworksErrorData(
    val error: String,
)

@Serializable
data class FireworksProviderSettings(
    val apiKey: String? = null,
    val baseURL: String = "https://api.fireworks.ai/inference/v1",
    val headers: Map<String, String> = emptyMap(),
)

interface FireworksProvider : Provider {
    operator fun invoke(modelId: FireworksChatModelId): LanguageModel = languageModel(modelId)
    fun chatModel(modelId: FireworksChatModelId): LanguageModel
    fun completionModel(modelId: FireworksCompletionModelId): LanguageModel
    fun textEmbeddingModel(modelId: FireworksEmbeddingModelId): EmbeddingModel = embeddingModel(modelId)
    fun image(modelId: FireworksImageModelId): ImageModel = imageModel(modelId)
}

fun createFireworks(
    client: HttpClient,
    settings: FireworksProviderSettings = FireworksProviderSettings(),
): FireworksProvider = DefaultFireworksProvider(client, settings)

val fireworks: FireworksProvider = providerNotConfigured("Fireworks", "fireworks")

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

typealias TogetherAIChatModelId = String
typealias TogetherAICompletionModelId = String
typealias TogetherAIEmbeddingModelId = String
typealias TogetherAIImageModelId = String
typealias TogetherAIRerankingModelId = String
typealias TogetherAIErrorData = JsonElement

@Serializable
data class TogetherAIImageModelOptions(
    val steps: Int? = null,
    val guidance: Float? = null,
    @SerialName("negative_prompt") val negativePrompt: String? = null,
    @SerialName("disable_safety_checker") val disableSafetyChecker: Boolean? = null,
    val raw: Map<String, JsonElement> = emptyMap(),
)

typealias TogetherAIImageProviderOptions = TogetherAIImageModelOptions

@Serializable
data class TogetherAIRerankingModelOptions(
    val rankFields: List<String>? = null,
)

typealias TogetherAIRerankingOptions = TogetherAIRerankingModelOptions

@Serializable
data class TogetherAIProviderSettings(
    val apiKey: String? = null,
    val baseURL: String = "https://api.together.xyz/v1",
    val headers: Map<String, String> = emptyMap(),
)

interface TogetherAIProvider : Provider {
    operator fun invoke(modelId: TogetherAIChatModelId): LanguageModel = languageModel(modelId)
    fun chatModel(modelId: TogetherAIChatModelId): LanguageModel
    fun completionModel(modelId: TogetherAICompletionModelId): LanguageModel
    fun textEmbeddingModel(modelId: TogetherAIEmbeddingModelId): EmbeddingModel = embeddingModel(modelId)
    fun image(modelId: TogetherAIImageModelId): ImageModel = imageModel(modelId)
    fun reranking(modelId: TogetherAIRerankingModelId): RerankingModel = rerankingModel(modelId)
}

fun createTogetherAI(
    client: HttpClient,
    settings: TogetherAIProviderSettings = TogetherAIProviderSettings(),
): TogetherAIProvider = DefaultTogetherAIProvider(client, settings)

val togetherai: TogetherAIProvider = providerNotConfigured("TogetherAI", "togetherai")

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

private class DefaultDeepInfraProvider(
    private val client: HttpClient,
    private val settings: DeepInfraProviderSettings,
) : DeepInfraProvider {
    private val compatible = createOpenAICompatible(
        client,
        compatibleSettings(
            name = "deepinfra",
            version = DEEPINFRA_VERSION,
            baseURL = "${settings.baseURL.trimEnd('/')}/openai",
            apiKey = settings.apiKey,
            headers = settings.headers,
            includeUsage = false,
            supportsStructuredOutputs = false,
        ),
    )
    override val providerId: String = "deepinfra"
    override fun languageModel(modelId: String): LanguageModel = chatModel(modelId)
    override fun chatModel(modelId: String): LanguageModel = DeepInfraChatLanguageModel(compatible.chatModel(modelId))
    override fun completionModel(modelId: String): LanguageModel = compatible.completionModel(modelId)
    override fun embeddingModel(modelId: String): EmbeddingModel = compatible.embeddingModel(modelId)
    override fun imageModel(modelId: String): ImageModel = DeepInfraImageModel(client, settings, modelId)
}

private class DefaultFireworksProvider(
    private val client: HttpClient,
    private val settings: FireworksProviderSettings,
) : FireworksProvider {
    private val compatible = createOpenAICompatible(
        client,
        settings.toCompatible("fireworks", FIREWORKS_VERSION),
    )
    override val providerId: String = "fireworks"
    override fun languageModel(modelId: String): LanguageModel = chatModel(modelId)
    override fun chatModel(modelId: String): LanguageModel = FireworksLanguageModel(compatible.chatModel(modelId))
    override fun completionModel(modelId: String): LanguageModel = compatible.completionModel(modelId)
    override fun embeddingModel(modelId: String): EmbeddingModel = compatible.embeddingModel(modelId)
    override fun imageModel(modelId: String): ImageModel = FireworksImageModel(client, settings, modelId)
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

private class DefaultTogetherAIProvider(
    private val client: HttpClient,
    private val settings: TogetherAIProviderSettings,
) : TogetherAIProvider {
    private val compatible = createOpenAICompatible(
        client,
        settings.toCompatible("togetherai", TOGETHERAI_VERSION),
    )
    override val providerId: String = "togetherai"
    override fun languageModel(modelId: String): LanguageModel = chatModel(modelId)
    override fun chatModel(modelId: String): LanguageModel = compatible.chatModel(modelId)
    override fun completionModel(modelId: String): LanguageModel = compatible.completionModel(modelId)
    override fun embeddingModel(modelId: String): EmbeddingModel = compatible.embeddingModel(modelId)
    override fun imageModel(modelId: String): ImageModel = TogetherAIImageModel(client, settings, modelId)
    override fun rerankingModel(modelId: String): RerankingModel = TogetherAIRerankingModel(client, settings, modelId)
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

private fun FireworksProviderSettings.toCompatible(
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

private fun TogetherAIProviderSettings.toCompatible(
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
        DeepInfraProvider::class -> object : DeepInfraProvider, Provider by error {
            override fun chatModel(modelId: String): LanguageModel = error.languageModel(modelId)
            override fun completionModel(modelId: String): LanguageModel = error.languageModel(modelId)
            override fun image(modelId: String): ImageModel =
                throw AiSdkException("$providerName provider is not configured. Use create$providerName(client, settings).")
        } as T
        FireworksProvider::class -> object : FireworksProvider, Provider by error {
            override fun chatModel(modelId: String): LanguageModel = error.languageModel(modelId)
            override fun completionModel(modelId: String): LanguageModel = error.languageModel(modelId)
            override fun image(modelId: String): ImageModel =
                throw AiSdkException("$providerName provider is not configured. Use create$providerName(client, settings).")
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
        TogetherAIProvider::class -> object : TogetherAIProvider, Provider by error {
            override fun chatModel(modelId: String): LanguageModel = error.languageModel(modelId)
            override fun completionModel(modelId: String): LanguageModel = error.languageModel(modelId)
            override fun image(modelId: String): ImageModel =
                throw AiSdkException("$providerName provider is not configured. Use create$providerName(client, settings).")
            override fun reranking(modelId: String): RerankingModel =
                throw AiSdkException("$providerName provider is not configured. Use create$providerName(client, settings).")
        } as T
        else -> error("Unsupported provider facade type: ${T::class}")
    }
}

private class DeepInfraChatLanguageModel(
    private val delegate: LanguageModel,
) : LanguageModel by delegate {
    override suspend fun generate(params: LanguageModelCallParams): LanguageModelResult =
        delegate.generate(params).let { result -> result.copy(usage = result.usage.fixDeepInfraUsage()) }

    override fun stream(params: LanguageModelCallParams): Flow<StreamEvent> =
        delegate.stream(params).map(::fixDeepInfraUsageEvent)

    override fun streamResult(params: LanguageModelCallParams): LanguageModelStreamResult =
        delegate.streamResult(params).let { result -> result.copy(stream = result.stream.map(::fixDeepInfraUsageEvent)) }
}

private fun fixDeepInfraUsageEvent(event: StreamEvent): StreamEvent =
    when (event) {
        is StreamEvent.StepFinish -> event.copy(usage = event.usage.fixDeepInfraUsage())
        is StreamEvent.Finish -> event.copy(usage = event.usage.fixDeepInfraUsage())
        else -> event
    }

private fun Usage.fixDeepInfraUsage(): Usage {
    val rawObject = raw as? JsonObject ?: return this
    val reasoningTokens = rawObject["completion_tokens_details"]
        ?.jsonObject
        ?.get("reasoning_tokens")
        ?.jsonPrimitive
        ?.intOrNull
        ?: return this
    val completionTokens = rawObject["completion_tokens"]?.jsonPrimitive?.intOrNull ?: return this
    if (reasoningTokens <= completionTokens) return this

    val correctedCompletionTokens = completionTokens + reasoningTokens
    val correctedRaw = rawObject.toMutableMap().apply {
        put("completion_tokens", JsonPrimitive(correctedCompletionTokens))
        rawObject["total_tokens"]?.jsonPrimitive?.intOrNull?.let { total ->
            put("total_tokens", JsonPrimitive(total + reasoningTokens))
        }
    }
    return copy(
        outputTokens = outputTokens.copy(
            total = correctedCompletionTokens,
            text = correctedCompletionTokens - reasoningTokens,
            reasoning = reasoningTokens,
        ),
        raw = JsonObject(correctedRaw),
    )
}

private class FireworksLanguageModel(
    private val delegate: LanguageModel,
) : LanguageModel by delegate {
    override suspend fun generate(params: LanguageModelCallParams): LanguageModelResult =
        delegate.generate(params.copy(providerOptions = transformFireworksProviderOptions(params.providerOptions)))

    override fun stream(params: LanguageModelCallParams): Flow<StreamEvent> =
        delegate.stream(params.copy(providerOptions = transformFireworksProviderOptions(params.providerOptions)))

    override fun streamResult(params: LanguageModelCallParams): LanguageModelStreamResult =
        delegate.streamResult(params.copy(providerOptions = transformFireworksProviderOptions(params.providerOptions)))
}

private fun transformFireworksProviderOptions(options: Map<String, JsonElement>): Map<String, JsonElement> {
    val fireworksOptions = options["fireworks"] as? JsonObject ?: return options
    val transformed = buildJsonObject {
        for ((key, value) in fireworksOptions) {
            when (key) {
                "thinking" -> put("thinking", transformFireworksThinking(value))
                "reasoningHistory" -> put("reasoning_history", value)
                else -> put(key, value)
            }
        }
    }
    return options + ("fireworks" to transformed)
}

private fun transformFireworksThinking(value: JsonElement): JsonElement {
    val objectValue = value as? JsonObject ?: return value
    return buildJsonObject {
        for ((key, nestedValue) in objectValue) {
            when (key) {
                "budgetTokens" -> put("budget_tokens", nestedValue)
                else -> put(key, nestedValue)
            }
        }
    }
}

private class DeepInfraImageModel(
    private val client: HttpClient,
    private val settings: DeepInfraProviderSettings,
    override val modelId: String,
) : ImageModel {
    override val provider: String = "deepinfra.image"

    override suspend fun generate(params: ImageGenerationParams): ImageModelResult {
        val response = postFacadeJson(
            client = client,
            url = "${settings.baseURL.trimEnd('/')}/inference/$modelId",
            body = buildJsonObject {
                put("prompt", JsonPrimitive(params.prompt))
                put("num_images", JsonPrimitive(params.n))
                params.aspectRatio?.let { put("aspect_ratio", JsonPrimitive(it)) }
                params.size?.split('x')?.takeIf { it.size == 2 }?.let { parts ->
                    put("width", JsonPrimitive(parts[0]))
                    put("height", JsonPrimitive(parts[1]))
                }
                params.seed?.let { put("seed", JsonPrimitive(it)) }
                putProviderSpecificOptions(params.providerOptions, "deepinfra")
            },
            headers = providerFacadeHeaders(
                apiKey = settings.apiKey,
                headers = settings.headers,
                callHeaders = params.headers,
                userAgent = "ai-sdk/deepinfra/$DEEPINFRA_VERSION",
            ),
        )
        val images = response.value.jsonObject["images"]?.jsonArray.orEmpty().map { image ->
            GeneratedFile(mediaType = "image/png", base64 = image.jsonPrimitive.contentOrNull.orEmpty().stripDataUriPrefix())
        }
        return ImageModelResult(
            images = images,
            response = LanguageModelResponseMetadata(modelId = modelId, headers = response.headers, body = response.value),
        )
    }
}

class FireworksImageModel(
    private val client: HttpClient,
    private val settings: FireworksProviderSettings,
    override val modelId: FireworksImageModelId,
) : ImageModel {
    override val provider: String = "fireworks.image"

    override suspend fun generate(params: ImageGenerationParams): ImageModelResult {
        val backend = fireworksImageBackend(modelId)
        val warnings = fireworksImageWarnings(params, backend)
        val body = buildJsonObject {
            put("prompt", JsonPrimitive(params.prompt))
            params.aspectRatio?.let { put("aspect_ratio", JsonPrimitive(it)) }
            params.seed?.let { put("seed", JsonPrimitive(it)) }
            put("samples", JsonPrimitive(params.n))
            params.size?.split('x')?.takeIf { it.size == 2 }?.let { parts ->
                put("width", JsonPrimitive(parts[0]))
                put("height", JsonPrimitive(parts[1]))
            }
            putProviderSpecificOptions(params.providerOptions, "fireworks")
        }
        val requestHeaders = providerFacadeHeaders(
            apiKey = settings.apiKey,
            headers = settings.headers,
            callHeaders = params.headers,
            userAgent = "ai-sdk/fireworks/$FIREWORKS_VERSION",
        )
        return if (backend.urlFormat == FireworksImageUrlFormat.WorkflowsAsync) {
            generateAsync(body, requestHeaders, warnings)
        } else {
            val response = postFacadeBinary(
                client = client,
                url = fireworksImageUrl(settings.baseURL, modelId, backend),
                body = body,
                headers = requestHeaders,
            )
            ImageModelResult(
                images = listOf(response.toGeneratedFile(modelId)),
                warnings = warnings,
                response = LanguageModelResponseMetadata(modelId = modelId, headers = response.headers),
            )
        }
    }

    private suspend fun generateAsync(
        body: JsonObject,
        requestHeaders: Map<String, String>,
        warnings: List<CallWarning>,
    ): ImageModelResult {
        val submitResponse = postFacadeJson(
            client = client,
            url = fireworksImageUrl(settings.baseURL, modelId, fireworksImageBackend(modelId)),
            body = body,
            headers = requestHeaders,
        )
        val requestId = submitResponse.value.jsonObject["request_id"]?.jsonPrimitive?.contentOrNull
            ?: throw AiSdkException("Fireworks image generation response is missing request_id")
        val imageUrl = pollForImageUrl(requestId, requestHeaders)
        val imageResponse = getFacadeBinary(client, imageUrl, requestHeaders)
        return ImageModelResult(
            images = listOf(imageResponse.toGeneratedFile(modelId)),
            warnings = warnings,
            response = LanguageModelResponseMetadata(modelId = modelId, headers = imageResponse.headers),
        )
    }

    private suspend fun pollForImageUrl(requestId: String, requestHeaders: Map<String, String>): String {
        val pollUrl = "${settings.baseURL.trimEnd('/')}/workflows/$modelId/get_result"
        repeat(FIREWORKS_MAX_POLL_ATTEMPTS) { attempt ->
            val response = postFacadeJson(
                client = client,
                url = pollUrl,
                body = buildJsonObject { put("id", JsonPrimitive(requestId)) },
                headers = requestHeaders,
            )
            val status = response.value.jsonObject["status"]?.jsonPrimitive?.contentOrNull
            when (status) {
                "Ready" -> return response.value.jsonObject["result"]
                    ?.jsonObject
                    ?.get("sample")
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?: throw AiSdkException("Fireworks poll response is Ready but missing result.sample")
                "Error", "Failed" -> throw AiSdkException("Fireworks image generation failed with status: $status")
            }
            if (attempt < FIREWORKS_MAX_POLL_ATTEMPTS - 1) delay(FIREWORKS_POLL_INTERVAL_MILLIS)
        }
        throw AiSdkException("Fireworks image generation timed out after polling")
    }
}

private class TogetherAIImageModel(
    private val client: HttpClient,
    private val settings: TogetherAIProviderSettings,
    override val modelId: TogetherAIImageModelId,
) : ImageModel {
    override val provider: String = "togetherai.image"

    override suspend fun generate(params: ImageGenerationParams): ImageModelResult {
        val warnings = buildList {
            if (params.aspectRatio != null) {
                add(CallWarning("unsupported", "TogetherAI image generation ignores aspectRatio; use size."))
            }
        }
        val response = postFacadeJson(
            client = client,
            url = "${settings.baseURL.trimEnd('/')}/images/generations",
            body = buildJsonObject {
                put("model", JsonPrimitive(modelId))
                put("prompt", JsonPrimitive(params.prompt))
                params.seed?.let { put("seed", JsonPrimitive(it)) }
                if (params.n > 1) put("n", JsonPrimitive(params.n))
                params.size?.split('x')?.takeIf { it.size == 2 }?.let { parts ->
                    put("width", JsonPrimitive(parts[0].toInt()))
                    put("height", JsonPrimitive(parts[1].toInt()))
                }
                put("response_format", JsonPrimitive("base64"))
                putProviderSpecificOptions(params.providerOptions, "togetherai")
            },
            headers = providerFacadeHeaders(
                apiKey = settings.apiKey,
                headers = settings.headers,
                callHeaders = params.headers,
                userAgent = "ai-sdk/togetherai/$TOGETHERAI_VERSION",
            ),
        )
        val images = response.value.jsonObject["data"]?.jsonArray.orEmpty().map { item ->
            GeneratedFile(
                mediaType = "image/png",
                base64 = item.jsonObject["b64_json"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            )
        }
        return ImageModelResult(
            images = images,
            warnings = warnings,
            response = LanguageModelResponseMetadata(modelId = modelId, headers = response.headers, body = response.value),
        )
    }
}

class TogetherAIRerankingModel(
    private val client: HttpClient,
    private val settings: TogetherAIProviderSettings,
    override val modelId: TogetherAIRerankingModelId,
) : RerankingModel {
    override val provider: String = "togetherai.reranking"

    override suspend fun rerank(params: RerankingParams): RerankingModelResult {
        val options = providerSpecificOptions(params.providerOptions, "togetherai")
        val response = postFacadeJson(
            client = client,
            url = "${settings.baseURL.trimEnd('/')}/rerank",
            body = buildJsonObject {
                put("model", JsonPrimitive(modelId))
                put("documents", JsonArray(params.documents.map(::JsonPrimitive)))
                put("query", JsonPrimitive(params.query))
                params.topN?.let { put("top_n", JsonPrimitive(it)) }
                (options["rankFields"] as? JsonArray)?.let { put("rank_fields", it) }
                put("return_documents", JsonPrimitive(false))
            },
            headers = providerFacadeHeaders(
                apiKey = settings.apiKey,
                headers = settings.headers,
                callHeaders = params.headers,
                userAgent = "ai-sdk/togetherai/$TOGETHERAI_VERSION",
            ),
        )
        val value = response.value.jsonObject
        val ranking = value["results"]?.jsonArray.orEmpty().map { item ->
            val obj = item.jsonObject
            val index = obj["index"]?.jsonPrimitive?.intOrNull ?: 0
            RerankedItem(
                value = params.documents.getOrElse(index) { "" },
                score = obj["relevance_score"]?.jsonPrimitive?.floatOrNull ?: 0f,
                index = index,
            )
        }
        return RerankingModelResult(
            results = ranking,
            usage = togetherAIUsage(value["usage"]),
            response = LanguageModelResponseMetadata(
                id = value["id"]?.jsonPrimitive?.contentOrNull,
                modelId = value["model"]?.jsonPrimitive?.contentOrNull ?: modelId,
                headers = response.headers,
                body = response.value,
            ),
        )
    }
}

private enum class FireworksImageUrlFormat {
    Workflows,
    WorkflowsAsync,
    ImageGeneration,
}

private data class FireworksImageBackend(
    val urlFormat: FireworksImageUrlFormat,
    val supportsSize: Boolean = false,
)

private const val FIREWORKS_POLL_INTERVAL_MILLIS: Long = 500
private const val FIREWORKS_MAX_POLL_ATTEMPTS: Int = 240

private fun fireworksImageBackend(modelId: String): FireworksImageBackend =
    when (modelId) {
        "accounts/fireworks/models/flux-kontext-pro",
        "accounts/fireworks/models/flux-kontext-max",
        -> FireworksImageBackend(FireworksImageUrlFormat.WorkflowsAsync)
        "accounts/fireworks/models/playground-v2-5-1024px-aesthetic",
        "accounts/fireworks/models/japanese-stable-diffusion-xl",
        "accounts/fireworks/models/playground-v2-1024px-aesthetic",
        "accounts/fireworks/models/stable-diffusion-xl-1024-v1-0",
        "accounts/fireworks/models/SSD-1B",
        -> FireworksImageBackend(FireworksImageUrlFormat.ImageGeneration, supportsSize = true)
        else -> FireworksImageBackend(FireworksImageUrlFormat.Workflows)
    }

private fun fireworksImageUrl(baseURL: String, modelId: String, backend: FireworksImageBackend): String {
    val base = baseURL.trimEnd('/')
    return when (backend.urlFormat) {
        FireworksImageUrlFormat.ImageGeneration -> "$base/image_generation/$modelId"
        FireworksImageUrlFormat.WorkflowsAsync -> "$base/workflows/$modelId"
        FireworksImageUrlFormat.Workflows -> "$base/workflows/$modelId/text_to_image"
    }
}

private fun fireworksImageWarnings(params: ImageGenerationParams, backend: FireworksImageBackend): List<CallWarning> =
    buildList {
        if (!backend.supportsSize && params.size != null) {
            add(CallWarning("unsupported", "This Fireworks model does not support size; use aspectRatio."))
        }
        if (backend.supportsSize && params.aspectRatio != null) {
            add(CallWarning("unsupported", "This Fireworks model does not support aspectRatio."))
        }
    }

private val providerFacadeJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
}

private data class ProviderFacadeJsonResponse(
    val value: JsonElement,
    val headers: Map<String, String>,
)

private data class ProviderFacadeBinaryResponse(
    val bytes: ByteArray,
    val headers: Map<String, String>,
) {
    fun toGeneratedFile(modelId: String): GeneratedFile =
        GeneratedFile(
            mediaType = headers.headerValue(HttpHeaders.ContentType) ?: "image/png",
            base64 = convertByteArrayToBase64(bytes),
            filename = "$modelId.png",
        )
}

private suspend fun postFacadeJson(
    client: HttpClient,
    url: String,
    body: JsonElement,
    headers: Map<String, String>,
): ProviderFacadeJsonResponse {
    val response = client.request(url) {
        method = HttpMethod.Post
        contentType(ContentType.Application.Json)
        headers.forEach { (name, value) -> header(name, value) }
        setBody(providerFacadeJson.encodeToString(JsonElement.serializer(), body))
    }
    return response.parseFacadeJson()
}

private suspend fun postFacadeBinary(
    client: HttpClient,
    url: String,
    body: JsonElement,
    headers: Map<String, String>,
): ProviderFacadeBinaryResponse {
    val response = client.request(url) {
        method = HttpMethod.Post
        contentType(ContentType.Application.Json)
        headers.forEach { (name, value) -> header(name, value) }
        setBody(providerFacadeJson.encodeToString(JsonElement.serializer(), body))
    }
    return response.parseFacadeBinary()
}

private suspend fun getFacadeBinary(
    client: HttpClient,
    url: String,
    headers: Map<String, String>,
): ProviderFacadeBinaryResponse {
    val response = client.request(url) {
        method = HttpMethod.Get
        headers.forEach { (name, value) -> header(name, value) }
    }
    return response.parseFacadeBinary()
}

private suspend fun HttpResponse.parseFacadeJson(): ProviderFacadeJsonResponse {
    val raw = bodyAsText()
    if (status.value !in 200..299) {
        throw AiSdkException("Provider request failed (${status.value}): ${providerFacadeErrorMessage(raw)}")
    }
    return ProviderFacadeJsonResponse(
        value = if (raw.isBlank()) JsonObject(emptyMap()) else providerFacadeJson.parseToJsonElement(raw),
        headers = responseHeaders(),
    )
}

private suspend fun HttpResponse.parseFacadeBinary(): ProviderFacadeBinaryResponse {
    val bytes = bodyAsBytes()
    if (status.value !in 200..299) {
        throw AiSdkException("Provider request failed (${status.value}): ${providerFacadeErrorMessage(bytes.decodeToString())}")
    }
    return ProviderFacadeBinaryResponse(bytes = bytes, headers = responseHeaders())
}

private fun HttpResponse.responseHeaders(): Map<String, String> =
    headers.entries().associate { it.key to it.value.joinToString(",") }

private fun providerFacadeHeaders(
    apiKey: String?,
    headers: Map<String, String>,
    callHeaders: Map<String, String> = emptyMap(),
    userAgent: String,
): Map<String, String> {
    val base = linkedMapOf<String, String>()
    apiKey?.takeIf { it.isNotBlank() }?.let { base[HttpHeaders.Authorization] = "Bearer $it" }
    base.putAll(headers)
    base.putAll(callHeaders)
    return withUserAgentSuffix(base, userAgent)
}

private fun providerSpecificOptions(options: Map<String, JsonElement>, provider: String): JsonObject =
    options[provider] as? JsonObject ?: JsonObject(emptyMap())

private fun JsonObjectBuilder.putProviderSpecificOptions(options: Map<String, JsonElement>, provider: String) {
    for ((key, value) in providerSpecificOptions(options, provider)) {
        put(key, value)
    }
}

private fun togetherAIUsage(value: JsonElement?): Usage {
    val obj = value as? JsonObject ?: return Usage()
    return Usage(
        promptTokens = obj["prompt_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
        completionTokens = obj["completion_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
    )
}

private fun String.stripDataUriPrefix(): String =
    replace(Regex("^data:[^;]+;base64,"), "")

private fun providerFacadeErrorMessage(raw: String): String {
    val obj = runCatching { providerFacadeJson.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return raw.ifBlank { "request failed" }
    val error = obj["error"]
    if (error is JsonPrimitive) return error.contentOrNull ?: raw
    if (error is JsonObject) return error["message"]?.jsonPrimitive?.contentOrNull ?: error.toString()
    val detail = obj["detail"]
    if (detail is JsonObject) return detail["error"]?.jsonPrimitive?.contentOrNull ?: detail.toString()
    return raw.ifBlank { "request failed" }
}

private fun Map<String, String>.headerValue(name: String): String? =
    entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
