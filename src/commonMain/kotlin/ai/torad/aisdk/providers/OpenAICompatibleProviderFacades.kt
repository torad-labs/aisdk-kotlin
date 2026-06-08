package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
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

public const val DEEPSEEK_VERSION: String = "2.0.35"
public const val CEREBRAS_VERSION: String = "2.0.54"
public const val DEEPINFRA_VERSION: String = "2.0.52"
public const val FIREWORKS_VERSION: String = "2.0.53"
public const val GROQ_VERSION: String = "3.0.39"
public const val MOONSHOTAI_VERSION: String = "2.0.23"
public const val PERPLEXITY_VERSION: String = "3.0.33"
public const val TOGETHERAI_VERSION: String = "2.0.53"
public const val VERCEL_VERSION: String = "2.0.50"
public const val BASETEN_VERSION: String = "1.0.51"

@Serializable
public data class DeepSeekProviderSettings(
    val apiKey: String? = null,
    val baseURL: String = "https://api.deepseek.com",
    val headers: Map<String, String> = emptyMap(),
)

@Serializable
public data class DeepSeekLanguageModelOptions(
    val raw: Map<String, JsonElement> = emptyMap(),
)

public typealias DeepSeekChatOptions = DeepSeekLanguageModelOptions
public typealias DeepSeekErrorData = JsonElement

public interface DeepSeekProvider : Provider {
    public operator fun invoke(modelId: String): LanguageModel = languageModel(modelId)
    public fun chat(modelId: String): LanguageModel
    public fun textEmbeddingModel(modelId: String): Nothing = throw NoSuchModelError(providerId, "embeddingModel", modelId)
}

public fun createDeepSeek(
    client: HttpClient,
    settings: DeepSeekProviderSettings = DeepSeekProviderSettings(),
): DeepSeekProvider = DefaultDeepSeekProvider(client, settings)

public val deepseek: DeepSeekProvider = providerNotConfigured("DeepSeek", "deepseek")

@Serializable
public data class CerebrasProviderSettings(
    val apiKey: String? = null,
    val baseURL: String = "https://api.cerebras.ai/v1",
    val headers: Map<String, String> = emptyMap(),
)

@Serializable
public data class CerebrasErrorData(
    val message: String,
    val type: String? = null,
    val param: String? = null,
    val code: String? = null,
)

public interface CerebrasProvider : Provider {
    public operator fun invoke(modelId: String): LanguageModel = languageModel(modelId)
    public fun chat(modelId: String): LanguageModel
    public fun textEmbeddingModel(modelId: String): Nothing = throw NoSuchModelError(providerId, "embeddingModel", modelId)
}

public fun createCerebras(
    client: HttpClient,
    settings: CerebrasProviderSettings = CerebrasProviderSettings(),
): CerebrasProvider = DefaultCerebrasProvider(client, settings)

public val cerebras: CerebrasProvider = providerNotConfigured("Cerebras", "cerebras")

public typealias DeepInfraChatModelId = String
public typealias DeepInfraCompletionModelId = String
public typealias DeepInfraEmbeddingModelId = String
public typealias DeepInfraImageModelId = String
public typealias DeepInfraErrorData = JsonElement

@Serializable
public data class DeepInfraProviderSettings(
    val apiKey: String? = null,
    val baseURL: String = "https://api.deepinfra.com/v1",
    val headers: Map<String, String> = emptyMap(),
)

public interface DeepInfraProvider : Provider {
    public operator fun invoke(modelId: DeepInfraChatModelId): LanguageModel = languageModel(modelId)
    public fun chatModel(modelId: DeepInfraChatModelId): LanguageModel
    public fun completionModel(modelId: DeepInfraCompletionModelId): LanguageModel
    public fun textEmbeddingModel(modelId: DeepInfraEmbeddingModelId): EmbeddingModel = embeddingModel(modelId)
    public fun image(modelId: DeepInfraImageModelId): ImageModel = imageModel(modelId)
}

public fun createDeepInfra(
    client: HttpClient,
    settings: DeepInfraProviderSettings = DeepInfraProviderSettings(),
): DeepInfraProvider = DefaultDeepInfraProvider(client, settings)

public val deepinfra: DeepInfraProvider = providerNotConfigured("DeepInfra", "deepinfra")

public typealias FireworksChatModelId = String
public typealias FireworksCompletionModelId = String
public typealias FireworksEmbeddingModelId = String
public typealias FireworksImageModelId = String

@Serializable
public data class FireworksThinkingOptions(
    val type: String? = null,
    val budgetTokens: Int? = null,
)

@Serializable
public data class FireworksLanguageModelOptions(
    val thinking: FireworksThinkingOptions? = null,
    val reasoningHistory: String? = null,
    val raw: Map<String, JsonElement> = emptyMap(),
)

public typealias FireworksProviderOptions = FireworksLanguageModelOptions

@Serializable
public data class FireworksEmbeddingModelOptions(
    val raw: Map<String, JsonElement> = emptyMap(),
)

public typealias FireworksEmbeddingProviderOptions = FireworksEmbeddingModelOptions

@Serializable
public data class FireworksErrorData(
    val error: String,
)

@Serializable
public data class FireworksProviderSettings(
    val apiKey: String? = null,
    val baseURL: String = "https://api.fireworks.ai/inference/v1",
    val headers: Map<String, String> = emptyMap(),
)

public interface FireworksProvider : Provider {
    public operator fun invoke(modelId: FireworksChatModelId): LanguageModel = languageModel(modelId)
    public fun chatModel(modelId: FireworksChatModelId): LanguageModel
    public fun completionModel(modelId: FireworksCompletionModelId): LanguageModel
    public fun textEmbeddingModel(modelId: FireworksEmbeddingModelId): EmbeddingModel = embeddingModel(modelId)
    public fun image(modelId: FireworksImageModelId): ImageModel = imageModel(modelId)
}

public fun createFireworks(
    client: HttpClient,
    settings: FireworksProviderSettings = FireworksProviderSettings(),
): FireworksProvider = DefaultFireworksProvider(client, settings)

public val fireworks: FireworksProvider = providerNotConfigured("Fireworks", "fireworks")

@Serializable
public data class PerplexityProviderSettings(
    val apiKey: String? = null,
    val baseURL: String = "https://api.perplexity.ai",
    val headers: Map<String, String> = emptyMap(),
)

public interface PerplexityProvider : Provider {
    public operator fun invoke(modelId: String): LanguageModel = languageModel(modelId)
    public fun textEmbeddingModel(modelId: String): Nothing = throw NoSuchModelError(providerId, "embeddingModel", modelId)
}

public fun createPerplexity(
    client: HttpClient,
    settings: PerplexityProviderSettings = PerplexityProviderSettings(),
): PerplexityProvider = DefaultPerplexityProvider(client, settings)

public val perplexity: PerplexityProvider = providerNotConfigured("Perplexity", "perplexity")

public typealias MoonshotAIChatModelId = String

@Serializable
public data class MoonshotAIProviderSettings(
    val apiKey: String? = null,
    val baseURL: String = "https://api.moonshot.ai/v1",
    val headers: Map<String, String> = emptyMap(),
)

@Serializable
public data class MoonshotAILanguageModelOptions(
    val raw: Map<String, JsonElement> = emptyMap(),
)

public typealias MoonshotAIProviderOptions = MoonshotAILanguageModelOptions

public interface MoonshotAIProvider : Provider {
    public operator fun invoke(modelId: MoonshotAIChatModelId): LanguageModel = languageModel(modelId)
    public fun chatModel(modelId: MoonshotAIChatModelId): LanguageModel
}

public fun createMoonshotAI(
    client: HttpClient,
    settings: MoonshotAIProviderSettings = MoonshotAIProviderSettings(),
): MoonshotAIProvider = DefaultMoonshotAIProvider(client, settings)

public val moonshotai: MoonshotAIProvider = providerNotConfigured("MoonshotAI", "moonshotai")

@Serializable
public data class GroqProviderSettings(
    val apiKey: String? = null,
    val baseURL: String = "https://api.groq.com/openai/v1",
    val headers: Map<String, String> = emptyMap(),
)

@Serializable
public data class GroqLanguageModelOptions(
    val raw: Map<String, JsonElement> = emptyMap(),
)

public typealias GroqProviderOptions = GroqLanguageModelOptions

@Serializable
public data class GroqTranscriptionModelOptions(
    val language: String? = null,
    val prompt: String? = null,
    val temperature: Float? = null,
    val responseFormat: String? = null,
)

public interface GroqProvider : Provider {
    public val tools: GroqTools

    public operator fun invoke(modelId: String): LanguageModel = languageModel(modelId)
    public fun chat(modelId: String): LanguageModel
    public fun transcription(modelId: String): TranscriptionModel
    public fun textEmbeddingModel(modelId: String): Nothing = throw NoSuchModelError(providerId, "embeddingModel", modelId)

    override fun transcriptionModel(modelId: String): TranscriptionModel = transcription(modelId)
}

public fun createGroq(
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

public data class GroqTools(
    val browserSearch: Tool<JsonElement, JsonElement, Any?> = groqBrowserSearchTool,
)

public val browserSearch: Tool<JsonElement, JsonElement, Any?> = groqBrowserSearchTool

public val groq: GroqProvider = providerNotConfigured("Groq", "groq")

public typealias TogetherAIChatModelId = String
public typealias TogetherAICompletionModelId = String
public typealias TogetherAIEmbeddingModelId = String
public typealias TogetherAIImageModelId = String
public typealias TogetherAIRerankingModelId = String
public typealias TogetherAIErrorData = JsonElement

@Serializable
public data class TogetherAIImageModelOptions(
    val steps: Int? = null,
    val guidance: Float? = null,
    @SerialName("negative_prompt") val negativePrompt: String? = null,
    @SerialName("disable_safety_checker") val disableSafetyChecker: Boolean? = null,
    val raw: Map<String, JsonElement> = emptyMap(),
)

public typealias TogetherAIImageProviderOptions = TogetherAIImageModelOptions

@Serializable
public data class TogetherAIRerankingModelOptions(
    val rankFields: List<String>? = null,
)

public typealias TogetherAIRerankingOptions = TogetherAIRerankingModelOptions

@Serializable
public data class TogetherAIProviderSettings(
    val apiKey: String? = null,
    val baseURL: String = "https://api.together.xyz/v1",
    val headers: Map<String, String> = emptyMap(),
)

public interface TogetherAIProvider : Provider {
    public operator fun invoke(modelId: TogetherAIChatModelId): LanguageModel = languageModel(modelId)
    public fun chatModel(modelId: TogetherAIChatModelId): LanguageModel
    public fun completionModel(modelId: TogetherAICompletionModelId): LanguageModel
    public fun textEmbeddingModel(modelId: TogetherAIEmbeddingModelId): EmbeddingModel = embeddingModel(modelId)
    public fun image(modelId: TogetherAIImageModelId): ImageModel = imageModel(modelId)
    public fun reranking(modelId: TogetherAIRerankingModelId): RerankingModel = rerankingModel(modelId)
}

public fun createTogetherAI(
    client: HttpClient,
    settings: TogetherAIProviderSettings = TogetherAIProviderSettings(),
): TogetherAIProvider = DefaultTogetherAIProvider(client, settings)

public val togetherai: TogetherAIProvider = providerNotConfigured("TogetherAI", "togetherai")

public typealias VercelChatModelId = String
public typealias VercelErrorData = JsonElement

@Serializable
public data class VercelProviderSettings(
    val apiKey: String? = null,
    val baseURL: String = "https://api.v0.dev/v1",
    val headers: Map<String, String> = emptyMap(),
)

public interface VercelProvider : Provider {
    public operator fun invoke(modelId: VercelChatModelId): LanguageModel = languageModel(modelId)
    public fun textEmbeddingModel(modelId: String): Nothing = throw NoSuchModelError(providerId, "embeddingModel", modelId)
}

public fun createVercel(
    client: HttpClient,
    settings: VercelProviderSettings = VercelProviderSettings(),
): VercelProvider = DefaultVercelProvider(client, settings)

public val vercel: VercelProvider = providerNotConfigured("Vercel", "vercel")

public typealias BasetenChatModelId = String
public typealias BasetenEmbeddingModelId = String

@Serializable
public data class BasetenEmbeddingModelOptions(
    val raw: Map<String, JsonElement> = emptyMap(),
)

@Serializable
public data class BasetenErrorData(
    val error: String,
)

@Serializable
public data class BasetenProviderSettings(
    val apiKey: String? = null,
    val baseURL: String = "https://inference.baseten.co/v1",
    val modelURL: String? = null,
    val headers: Map<String, String> = emptyMap(),
)

public interface BasetenProvider : Provider {
    public operator fun invoke(): LanguageModel = chatModel()
    public operator fun invoke(modelId: BasetenChatModelId): LanguageModel = chatModel(modelId)
    public fun chatModel(): LanguageModel
    public fun chatModel(modelId: BasetenChatModelId): LanguageModel
    public fun languageModel(): LanguageModel = chatModel()
    override fun languageModel(modelId: String): LanguageModel = chatModel(modelId)
    public fun embeddingModel(): EmbeddingModel
    override fun embeddingModel(modelId: String): EmbeddingModel
    public fun textEmbeddingModel(): EmbeddingModel = embeddingModel()
    public fun textEmbeddingModel(modelId: BasetenEmbeddingModelId): EmbeddingModel = embeddingModel(modelId)
}

public fun createBaseten(
    client: HttpClient,
    settings: BasetenProviderSettings = BasetenProviderSettings(),
): BasetenProvider = DefaultBasetenProvider(client, settings)

public val baseten: BasetenProvider = object : BasetenProvider {
    override val providerId: String = "baseten"
    override fun chatModel(): LanguageModel =
        throw AiSdkException("Baseten provider is not configured. Use createBaseten(client, settings).")
    override fun chatModel(modelId: String): LanguageModel = chatModel()
    override fun embeddingModel(): EmbeddingModel =
        throw AiSdkException("Baseten provider is not configured. Use createBaseten(client, settings).")
    override fun embeddingModel(modelId: String): EmbeddingModel = embeddingModel()
    override fun imageModel(modelId: String): ImageModel = throw NoSuchModelError(providerId, "imageModel", modelId)
}

private class DefaultDeepSeekProvider(
    client: HttpClient,
    private val settings: DeepSeekProviderSettings,
) : DeepSeekProvider {
    private val compatible = createOpenAICompatible(
        client,
        settings.toCompatible("deepseek", DEEPSEEK_VERSION, supportsStructuredOutputs = true),
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
        settings.toCompatible("perplexity", PERPLEXITY_VERSION, supportsStructuredOutputs = true),
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

private class DefaultVercelProvider(
    client: HttpClient,
    private val settings: VercelProviderSettings,
) : VercelProvider {
    private val compatible = createOpenAICompatible(
        client,
        settings.toCompatible("vercel", VERCEL_VERSION),
    )
    override val providerId: String = "vercel"
    override fun languageModel(modelId: String): LanguageModel = compatible.chatModel(modelId)
    override fun embeddingModel(modelId: String): EmbeddingModel = throw NoSuchModelError(providerId, "embeddingModel", modelId)
    override fun imageModel(modelId: String): ImageModel = throw NoSuchModelError(providerId, "imageModel", modelId)
}

private class DefaultBasetenProvider(
    private val client: HttpClient,
    private val settings: BasetenProviderSettings,
) : BasetenProvider {
    override val providerId: String = "baseten"

    override fun chatModel(): LanguageModel = createChatModel(null)
    override fun chatModel(modelId: String): LanguageModel = createChatModel(modelId)
    override fun embeddingModel(): EmbeddingModel = createEmbeddingModel(null)
    override fun embeddingModel(modelId: String): EmbeddingModel = createEmbeddingModel(modelId)
    override fun imageModel(modelId: String): ImageModel = throw NoSuchModelError(providerId, "imageModel", modelId)

    private fun createChatModel(modelId: String?): LanguageModel {
        val customURL = settings.modelURL?.trimEnd('/')
        val baseURL = when {
            customURL?.contains("/sync/v1") == true -> customURL
            customURL?.contains("/predict") == true -> throw AiSdkException("Not supported. You must use a /sync/v1 endpoint for chat models.")
            else -> settings.baseURL.trimEnd('/')
        }
        val resolvedModelId = if (customURL?.contains("/sync/v1") == true) {
            modelId ?: "placeholder"
        } else {
            modelId ?: "chat"
        }
        return createOpenAICompatible(client, settings.toCompatible("baseten", BASETEN_VERSION, baseURL)).chatModel(resolvedModelId)
    }

    private fun createEmbeddingModel(modelId: String?): EmbeddingModel {
        val customURL = settings.modelURL?.trimEnd('/')
            ?: throw AiSdkException("No model URL provided for embeddings. Please set modelURL option for embeddings.")
        if (!customURL.contains("/sync")) {
            throw AiSdkException("Not supported. You must use a /sync or /sync/v1 endpoint for embeddings.")
        }
        val baseURL = if (customURL.contains("/sync/v1")) customURL else "$customURL/v1"
        return createOpenAICompatible(client, settings.toCompatible("baseten", BASETEN_VERSION, baseURL)).embeddingModel(modelId ?: "embeddings")
    }
}

private fun DeepSeekProviderSettings.toCompatible(
    name: String,
    version: String,
    includeUsage: Boolean = false,
    supportsStructuredOutputs: Boolean = false,
): OpenAICompatibleProviderSettings =
    compatibleSettings(
        name = name,
        version = version,
        baseURL = baseURL,
        apiKey = apiKey,
        headers = headers,
        includeUsage = includeUsage,
        supportsStructuredOutputs = supportsStructuredOutputs,
        transformChatRequestBody = ::deepSeekTransformChatBody,
        convertUsage = ::deepSeekUsage,
    )

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
    compatibleSettings(
        name = name,
        version = version,
        baseURL = baseURL,
        apiKey = apiKey,
        headers = headers,
        includeUsage = includeUsage,
        supportsStructuredOutputs = supportsStructuredOutputs,
        transformChatRequestBody = ::perplexityTransformChatBody,
        convertUsage = ::perplexityUsage,
    )

private fun MoonshotAIProviderSettings.toCompatible(
    name: String,
    version: String,
    includeUsage: Boolean = false,
    supportsStructuredOutputs: Boolean = false,
): OpenAICompatibleProviderSettings =
    compatibleSettings(
        name = name,
        version = version,
        baseURL = baseURL,
        apiKey = apiKey,
        headers = headers,
        includeUsage = includeUsage,
        supportsStructuredOutputs = supportsStructuredOutputs,
        convertUsage = ::moonshotAIUsage,
    )

private fun GroqProviderSettings.toCompatible(
    name: String,
    version: String,
    includeUsage: Boolean = false,
    supportsStructuredOutputs: Boolean = false,
): OpenAICompatibleProviderSettings =
    compatibleSettings(
        name = name,
        version = version,
        baseURL = baseURL,
        apiKey = apiKey,
        headers = headers,
        includeUsage = includeUsage,
        supportsStructuredOutputs = supportsStructuredOutputs,
        transformChatRequestBody = ::groqTransformChatBody,
        transformChatResponse = ::groqTransformChatResponse,
        convertUsage = ::groqUsage,
    )

private fun TogetherAIProviderSettings.toCompatible(
    name: String,
    version: String,
    includeUsage: Boolean = false,
    supportsStructuredOutputs: Boolean = false,
): OpenAICompatibleProviderSettings =
    compatibleSettings(name, version, baseURL, apiKey, headers, includeUsage, supportsStructuredOutputs)

private fun VercelProviderSettings.toCompatible(
    name: String,
    version: String,
    includeUsage: Boolean = false,
    supportsStructuredOutputs: Boolean = false,
): OpenAICompatibleProviderSettings =
    compatibleSettings(name, version, baseURL, apiKey, headers, includeUsage, supportsStructuredOutputs)

private fun BasetenProviderSettings.toCompatible(
    name: String,
    version: String,
    baseURL: String,
    includeUsage: Boolean = false,
    supportsStructuredOutputs: Boolean = false,
): OpenAICompatibleProviderSettings =
    compatibleSettings(name, version, baseURL, apiKey, headers, includeUsage, supportsStructuredOutputs)

@Suppress("LongParameterList")
private fun compatibleSettings(
    name: String,
    version: String,
    baseURL: String,
    apiKey: String?,
    headers: Map<String, String>,
    includeUsage: Boolean,
    supportsStructuredOutputs: Boolean,
    transformChatRequestBody: ((JsonObject) -> JsonObject)? = null,
    convertUsage: ((JsonElement?) -> Usage)? = null,
    transformChatResponse: ((JsonObject) -> JsonObject)? = null,
): OpenAICompatibleProviderSettings =
    OpenAICompatibleProviderSettings(
        name = name,
        baseUrl = baseURL.trimEnd('/'),
        apiKey = apiKey,
        headers = withUserAgentSuffix(headers, "ai-sdk/$name/$version"),
        includeUsage = includeUsage,
        supportsStructuredOutputs = supportsStructuredOutputs,
        transformChatRequestBody = transformChatRequestBody,
        convertUsage = convertUsage,
        transformChatResponse = transformChatResponse,
    )

private fun deepSeekTransformChatBody(body: JsonObject): JsonObject {
    val responseFormat = body["response_format"] as? JsonObject
    val responseFormatType = responseFormat?.get("type")?.jsonPrimitive?.contentOrNull
    val schema = (responseFormat?.get("json_schema") as? JsonObject)?.get("schema")
    val jsonRequested = responseFormatType == "json_object" || responseFormatType == "json_schema"
    return buildJsonObject {
        for ((key, value) in body) {
            when (key) {
                "messages" -> put(
                    "messages",
                    deepSeekMessages(
                        messages = value as? JsonArray,
                        jsonRequested = jsonRequested,
                        schema = schema,
                    ),
                )
                "response_format" -> if (jsonRequested) {
                    put("response_format", buildJsonObject { put("type", JsonPrimitive("json_object")) })
                } else {
                    put(key, value)
                }
                "seed" -> Unit
                else -> put(key, value)
            }
        }
    }
}

private fun deepSeekMessages(
    messages: JsonArray?,
    jsonRequested: Boolean,
    schema: JsonElement?,
): JsonArray = JsonArray(
    buildList {
        if (jsonRequested) {
            add(
                buildJsonObject {
                    put("role", JsonPrimitive("system"))
                    put(
                        "content",
                        JsonPrimitive(
                            if (schema == null) {
                                "Return JSON."
                            } else {
                                "Return JSON that conforms to the following schema: $schema"
                            },
                        ),
                    )
                },
            )
        }
        messages.orEmpty().forEach { message ->
            add(deepSeekMessage(message))
        }
    },
)

private fun deepSeekMessage(message: JsonElement): JsonElement {
    val obj = message as? JsonObject
    val content = obj?.get("content") as? JsonArray
    return if (obj?.get("role")?.jsonPrimitive?.contentOrNull == "user" && content != null) {
        JsonObject(obj + ("content" to JsonPrimitive(textFromContentParts(content))))
    } else {
        message
    }
}

private fun perplexityTransformChatBody(body: JsonObject): JsonObject = buildJsonObject {
    for ((key, value) in body) {
        when (key) {
            "messages" -> put("messages", perplexityMessages(value as? JsonArray))
            "stop", "seed", "tools", "tool_choice" -> Unit
            else -> put(key, value)
        }
    }
}

private fun perplexityMessages(messages: JsonArray?): JsonArray = JsonArray(
    messages.orEmpty().mapNotNull { message ->
        val obj = message as? JsonObject ?: return@mapNotNull message
        when (obj["role"]?.jsonPrimitive?.contentOrNull) {
            "tool" -> null
            "assistant" -> perplexityAssistantMessage(obj)
            "user" -> perplexityTextMessage(obj)
            else -> obj
        }
    },
)

private fun perplexityAssistantMessage(message: JsonObject): JsonObject {
    val transformed = perplexityTextMessage(message).toMutableMap()
    transformed.remove("tool_calls")
    transformed.remove("reasoning_content")
    transformed["content"] = JsonPrimitive((transformed["content"] as? JsonPrimitive)?.contentOrNull.orEmpty())
    return JsonObject(transformed)
}

private fun perplexityTextMessage(message: JsonObject): JsonObject {
    val content = message["content"] as? JsonArray
    val textOnly = content?.all {
        (it as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull == "text"
    } == true
    return if (content != null && textOnly) {
        JsonObject(message + ("content" to JsonPrimitive(textFromContentParts(content))))
    } else {
        message
    }
}

// Groq supports the browser_search tool only on these models; elsewhere it is dropped.
private val GROQ_BROWSER_SEARCH_MODELS = setOf("openai/gpt-oss-20b", "openai/gpt-oss-120b")

private fun groqTransformChatBody(body: JsonObject): JsonObject {
    val modelId = body["model"]?.jsonPrimitive?.contentOrNull
    val tools = groqTools(body["tools"] as? JsonArray, modelId)
    return buildJsonObject {
        for ((key, value) in body) {
            when (key) {
                "messages" -> put("messages", groqMessages(value as? JsonArray))
                // browser_search may have been filtered out — drop the tools key if now empty
                // (an empty tools array is invalid, same as sending tool_choice without tools).
                "tools" -> if (tools.isNotEmpty()) put("tools", tools)
                else -> put(key, value)
            }
        }
    }
}

private fun groqMessages(messages: JsonArray?): JsonArray = JsonArray(
    messages.orEmpty().map { message ->
        val obj = message as? JsonObject ?: return@map message
        if (obj["role"]?.jsonPrimitive?.contentOrNull != "assistant") return@map obj
        val reasoning = obj["reasoning_content"] ?: return@map obj
        val transformed = obj.toMutableMap()
        transformed.remove("reasoning_content")
        transformed["reasoning"] = reasoning
        JsonObject(transformed)
    },
)

private fun groqTools(tools: JsonArray?, modelId: String?): JsonArray = JsonArray(
    tools.orEmpty().mapNotNull { tool ->
        val obj = tool as? JsonObject ?: return@mapNotNull tool
        val function = obj["function"] as? JsonObject ?: return@mapNotNull tool
        val name = function["name"]?.jsonPrimitive?.contentOrNull
        if (name == "browserSearch" || name == "browser_search") {
            // Gate the browser_search tool to the models that support it; drop it elsewhere
            // (upstream emits an unsupported warning and omits the tool).
            if (modelId in GROQ_BROWSER_SEARCH_MODELS) {
                buildJsonObject { put("type", JsonPrimitive("browser_search")) }
            } else {
                null
            }
        } else {
            tool
        }
    },
)

private fun groqTransformChatResponse(body: JsonObject): JsonObject {
    val usage = (body["x_groq"] as? JsonObject)?.get("usage")
    return if (body["usage"] == null && usage != null) {
        JsonObject(body + ("usage" to usage))
    } else {
        body
    }
}

private fun textFromContentParts(content: JsonArray): String =
    content.mapNotNull { part ->
        val obj = part as? JsonObject ?: return@mapNotNull null
        obj.takeIf { it["type"]?.jsonPrimitive?.contentOrNull == "text" }
            ?.get("text")
            ?.jsonPrimitive
            ?.contentOrNull
    }.joinToString("")

private fun deepSeekUsage(value: JsonElement?): Usage {
    val obj = value as? JsonObject ?: return Usage(raw = value)
    val promptTokens = obj.intField("prompt_tokens")
    val completionTokens = obj.intField("completion_tokens")
    val cacheRead = obj.intField("prompt_cache_hit_tokens").coerceAtMost(promptTokens)
    val reasoning = obj.nestedIntField("completion_tokens_details", "reasoning_tokens").coerceAtMost(completionTokens)
    return usageFromParts(promptTokens, completionTokens, cacheRead, reasoning, obj)
}

private fun perplexityUsage(value: JsonElement?): Usage {
    val obj = value as? JsonObject ?: return Usage(raw = value)
    val promptTokens = obj.intField("prompt_tokens")
    val completionTokens = obj.intField("completion_tokens")
    val reasoning = obj.intField("reasoning_tokens").coerceAtMost(completionTokens)
    return usageFromParts(promptTokens, completionTokens, cacheRead = 0, reasoningTokens = reasoning, raw = obj)
}

private fun moonshotAIUsage(value: JsonElement?): Usage {
    val obj = value as? JsonObject ?: return Usage(raw = value)
    val promptTokens = obj.intField("prompt_tokens")
    val completionTokens = obj.intField("completion_tokens")
    val cacheRead = (
        obj["cached_tokens"]?.jsonPrimitive?.intOrNull
            ?: obj.nestedIntField("prompt_tokens_details", "cached_tokens")
        ).coerceAtMost(promptTokens)
    val reasoning = obj.nestedIntField("completion_tokens_details", "reasoning_tokens").coerceAtMost(completionTokens)
    return usageFromParts(promptTokens, completionTokens, cacheRead, reasoning, obj)
}

private fun groqUsage(value: JsonElement?): Usage {
    val obj = value as? JsonObject ?: return Usage(raw = value)
    val promptTokens = obj.intField("prompt_tokens")
    val completionTokens = obj.intField("completion_tokens")
    val reasoning = obj.nestedIntField("completion_tokens_details", "reasoning_tokens").coerceAtMost(completionTokens)
    return usageFromParts(promptTokens, completionTokens, cacheRead = 0, reasoningTokens = reasoning, raw = obj)
}

private fun usageFromParts(
    promptTokens: Int,
    completionTokens: Int,
    cacheRead: Int,
    reasoningTokens: Int,
    raw: JsonElement?,
): Usage = Usage(
    inputTokens = Usage.InputTokenBreakdown(
        total = promptTokens,
        noCache = promptTokens - cacheRead,
        cacheRead = cacheRead,
    ),
    outputTokens = Usage.OutputTokenBreakdown(
        total = completionTokens,
        text = completionTokens - reasoningTokens,
        reasoning = reasoningTokens,
    ),
    raw = raw,
)

private fun JsonObject.intField(name: String): Int =
    this[name]?.jsonPrimitive?.intOrNull ?: 0

private fun JsonObject.nestedIntField(objectName: String, fieldName: String): Int =
    (this[objectName] as? JsonObject)?.intField(fieldName) ?: 0

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
        VercelProvider::class -> object : VercelProvider, Provider by error {} as T
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

public class FireworksImageModel(
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

public class TogetherAIRerankingModel(
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
): HttpJsonResponse =
    requestJson(
        client = client,
        url = url,
        method = HttpMethod.Post,
        headers = headers,
        body = body,
        requestBodyValues = body,
        errorMessage = ::providerFacadeErrorMessage,
    )

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
        setBody(aiSdkJson.encodeToString(JsonElement.serializer(), body))
    }
    return response.parseFacadeBinary(url)
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
    return response.parseFacadeBinary(url)
}

private suspend fun HttpResponse.parseFacadeBinary(url: String): ProviderFacadeBinaryResponse {
    val bytes = bodyAsBytes()
    val flattened = flattenedHeaders()
    if (status.value !in 200..299) {
        val raw = bytes.decodeToString()
        val parsed = runCatching { aiSdkJson.parseToJsonElement(raw) }.getOrNull()
        throw apiCallError(
            url = url,
            statusCode = status.value,
            rawBody = raw,
            headers = flattened,
            message = providerFacadeErrorMessage(status.value, parsed, raw),
        )
    }
    return ProviderFacadeBinaryResponse(bytes = bytes, headers = flattened)
}

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

private fun providerFacadeErrorMessage(statusCode: Int, parsed: JsonElement?, raw: String): String {
    val obj = parsed as? JsonObject
        ?: return "Provider request failed ($statusCode): ${raw.ifBlank { "request failed" }}"
    val error = obj["error"]
    val detail = obj["detail"]
    val message = when {
        error is JsonPrimitive -> error.contentOrNull ?: raw
        error is JsonObject -> error["message"]?.jsonPrimitive?.contentOrNull ?: error.toString()
        detail is JsonObject -> detail["error"]?.jsonPrimitive?.contentOrNull ?: detail.toString()
        else -> raw.ifBlank { "request failed" }
    }
    return "Provider request failed ($statusCode): $message"
}

private fun Map<String, String>.headerValue(name: String): String? =
    entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
