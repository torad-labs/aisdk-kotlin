package ai.torad.aisdk

import io.ktor.client.HttpClient
import io.ktor.http.HttpHeaders
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

const val GOOGLE_VERTEX_VERSION: String = "4.0.140"

typealias GoogleVertexEmbeddingModelOptions = JsonObject
typealias GoogleVertexImageModelOptions = JsonObject
typealias GoogleVertexImageProviderOptions = JsonObject
typealias GoogleVertexVideoModelId = String
typealias GoogleVertexVideoModelOptions = JsonObject
typealias GoogleVertexVideoProviderOptions = JsonObject
typealias GoogleVertexMaasModelId = String
typealias GoogleVertexXaiModelId = String

@Serializable
data class GoogleVertexProviderSettings(
    val project: String? = null,
    val location: String = "us-central1",
    val baseURL: String? = null,
    val accessToken: String? = null,
    val apiKey: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val generateId: () -> String = { ai.torad.aisdk.generateId() },
)

interface GoogleVertexProvider : Provider {
    val settings: GoogleVertexProviderSettings
    val tools: GoogleTools

    operator fun invoke(modelId: GoogleGenerativeAIModelId): LanguageModel = languageModel(modelId)
    fun chat(modelId: GoogleGenerativeAIModelId): LanguageModel = languageModel(modelId)
    fun generativeAI(modelId: GoogleGenerativeAIModelId): LanguageModel = languageModel(modelId)
    fun embedding(modelId: GoogleGenerativeAIEmbeddingModelId): EmbeddingModel
    fun textEmbedding(modelId: GoogleGenerativeAIEmbeddingModelId): EmbeddingModel = embedding(modelId)
    fun textEmbeddingModel(modelId: GoogleGenerativeAIEmbeddingModelId): EmbeddingModel = embedding(modelId)
    fun image(modelId: GoogleGenerativeAIImageModelId): ImageModel
    fun video(modelId: GoogleVertexVideoModelId): VideoModel

    override fun embeddingModel(modelId: String): EmbeddingModel = embedding(modelId)
    override fun imageModel(modelId: String): ImageModel = image(modelId)
    override fun videoModel(modelId: String): VideoModel = video(modelId)
}

fun createVertex(
    client: HttpClient,
    settings: GoogleVertexProviderSettings = GoogleVertexProviderSettings(),
): GoogleVertexProvider = DefaultGoogleVertexProvider(client, settings)

val vertex: GoogleVertexProvider = object : GoogleVertexProvider {
    override val providerId: String = "google-vertex"
    override val settings: GoogleVertexProviderSettings = GoogleVertexProviderSettings()
    override val tools: GoogleTools = googleTools
    override fun languageModel(modelId: String): LanguageModel =
        throw AiSdkException("Google Vertex provider is not configured. Use createVertex(client, settings).")
    override fun embedding(modelId: String): EmbeddingModel =
        throw AiSdkException("Google Vertex provider is not configured. Use createVertex(client, settings).")
    override fun image(modelId: String): ImageModel =
        throw AiSdkException("Google Vertex provider is not configured. Use createVertex(client, settings).")
    override fun video(modelId: String): VideoModel =
        throw AiSdkException("Google Vertex provider is not configured. Use createVertex(client, settings).")
}

private class DefaultGoogleVertexProvider(
    client: HttpClient,
    override val settings: GoogleVertexProviderSettings,
) : GoogleVertexProvider {
    override val providerId: String = "google-vertex"
    override val tools: GoogleTools = googleTools
    private val delegate: GoogleGenerativeAIProvider = createGoogleGenerativeAI(
        client,
        GoogleGenerativeAIProviderSettings(
            baseURL = googleVertexPublisherBaseURL(settings),
            apiKey = settings.apiKey,
            headers = googleVertexHeaders(settings),
            generateId = settings.generateId,
            name = "google.vertex",
        ),
    )

    override fun languageModel(modelId: String): LanguageModel = delegate.languageModel(modelId)
    override fun embedding(modelId: String): EmbeddingModel = delegate.embeddingModel(modelId)
    override fun image(modelId: String): ImageModel = delegate.imageModel(modelId)
    override fun video(modelId: String): VideoModel = delegate.videoModel(modelId)
}

typealias GoogleVertexAnthropicProviderSettings = GoogleVertexProviderSettings
typealias GoogleVertexMaasProviderSettings = GoogleVertexProviderSettings
typealias GoogleVertexXaiProviderSettings = GoogleVertexProviderSettings

interface GoogleVertexAnthropicProvider : Provider {
    val settings: GoogleVertexAnthropicProviderSettings
    val tools: AnthropicTools
    operator fun invoke(modelId: AnthropicMessagesModelId): LanguageModel = languageModel(modelId)
    fun chat(modelId: AnthropicMessagesModelId): LanguageModel = languageModel(modelId)
    fun messages(modelId: AnthropicMessagesModelId): LanguageModel = languageModel(modelId)
    fun textEmbeddingModel(modelId: String): Nothing = throw NoSuchModelError(providerId, "embeddingModel", modelId)
}

interface GoogleVertexMaasProvider : Provider {
    val settings: GoogleVertexMaasProviderSettings
    operator fun invoke(modelId: GoogleVertexMaasModelId): LanguageModel = languageModel(modelId)
    fun chat(modelId: GoogleVertexMaasModelId): LanguageModel = languageModel(modelId)
}

interface GoogleVertexXaiProvider : Provider {
    val settings: GoogleVertexXaiProviderSettings
    operator fun invoke(modelId: GoogleVertexXaiModelId): LanguageModel = languageModel(modelId)
    fun chat(modelId: GoogleVertexXaiModelId): LanguageModel = languageModel(modelId)
    fun chatModel(modelId: GoogleVertexXaiModelId): LanguageModel = languageModel(modelId)
    fun textEmbeddingModel(modelId: String): Nothing = throw NoSuchModelError(providerId, "embeddingModel", modelId)
}

fun createVertexAnthropic(
    client: HttpClient,
    settings: GoogleVertexAnthropicProviderSettings = GoogleVertexAnthropicProviderSettings(),
): GoogleVertexAnthropicProvider = DefaultVertexAnthropicProvider(client, settings)

fun createVertexMaas(
    client: HttpClient,
    settings: GoogleVertexMaasProviderSettings = GoogleVertexMaasProviderSettings(),
): GoogleVertexMaasProvider = DefaultVertexMaasProvider(client, settings)

fun createGoogleVertexXai(
    client: HttpClient,
    settings: GoogleVertexXaiProviderSettings = GoogleVertexXaiProviderSettings(),
): GoogleVertexXaiProvider = DefaultVertexXaiProvider(client, settings)

val vertexAnthropic: GoogleVertexAnthropicProvider = object : GoogleVertexAnthropicProvider {
    override val providerId: String = "google-vertex-anthropic"
    override val settings: GoogleVertexProviderSettings = GoogleVertexProviderSettings()
    override val tools: AnthropicTools = anthropicTools
    override fun languageModel(modelId: String): LanguageModel =
        throw AiSdkException("Google Vertex Anthropic provider is not configured. Use createVertexAnthropic(client, settings).")
}

val vertexMaas: GoogleVertexMaasProvider = object : GoogleVertexMaasProvider {
    override val providerId: String = "google-vertex-maas"
    override val settings: GoogleVertexProviderSettings = GoogleVertexProviderSettings()
    override fun languageModel(modelId: String): LanguageModel =
        throw AiSdkException("Google Vertex MaAS provider is not configured. Use createVertexMaas(client, settings).")
}

val googleVertexXai: GoogleVertexXaiProvider = object : GoogleVertexXaiProvider {
    override val providerId: String = "google-vertex-xai"
    override val settings: GoogleVertexProviderSettings = GoogleVertexProviderSettings()
    override fun languageModel(modelId: String): LanguageModel =
        throw AiSdkException("Google Vertex xAI provider is not configured. Use createGoogleVertexXai(client, settings).")
}

private class DefaultVertexAnthropicProvider(
    client: HttpClient,
    override val settings: GoogleVertexAnthropicProviderSettings,
) : GoogleVertexAnthropicProvider {
    override val providerId: String = "google-vertex-anthropic"
    override val tools: AnthropicTools = anthropicTools
    private val delegate = createAnthropic(
        client,
        AnthropicProviderSettings(
            baseURL = googleVertexAnthropicBaseURL(settings),
            headers = emptyMap(),
            requestHeadersProvider = { _, _, headers -> googleVertexAnthropicHeaders(settings, headers) },
            buildRequestUrl = { baseURL, modelId, isStreaming ->
                "$baseURL/$modelId:${if (isStreaming) "streamRawPredict" else "rawPredict"}"
            },
            transformRequestBody = { _, body, _ -> googleVertexAnthropicBody(body) },
            supportedUrls = emptyMap(),
            generateId = settings.generateId,
            name = "vertex.anthropic.messages",
        ),
    )

    override fun languageModel(modelId: String): LanguageModel =
        delegate.languageModel(modelId)

    override fun embeddingModel(modelId: String): EmbeddingModel = throw NoSuchModelError(providerId, "embeddingModel", modelId)
    override fun imageModel(modelId: String): ImageModel = throw NoSuchModelError(providerId, "imageModel", modelId)
}

private class DefaultVertexMaasProvider(
    client: HttpClient,
    override val settings: GoogleVertexMaasProviderSettings,
) : GoogleVertexMaasProvider {
    override val providerId: String = "google-vertex-maas"
    private val delegate = createOpenAICompatible(
        client,
        OpenAICompatibleProviderSettings(
            name = "google-vertex-maas",
            baseUrl = googleVertexOpenAIBaseURL(settings),
            headers = googleVertexHeaders(settings),
            userAgentSuffix = "ai-sdk/google-vertex/$GOOGLE_VERTEX_VERSION",
        ),
    )
    override fun languageModel(modelId: String): LanguageModel = delegate.languageModel(modelId)
}

private class DefaultVertexXaiProvider(
    client: HttpClient,
    override val settings: GoogleVertexXaiProviderSettings,
) : GoogleVertexXaiProvider {
    override val providerId: String = "google-vertex-xai"
    private val delegate = createOpenAICompatible(
        client,
        OpenAICompatibleProviderSettings(
            name = "googleVertex.xai",
            baseUrl = googleVertexOpenAIBaseURL(settings),
            headers = googleVertexHeaders(settings),
            includeUsage = true,
            supportsStructuredOutputs = true,
            supportedUrls = mapOf("image/*" to listOf("^https?://.*$")),
            userAgentSuffix = "ai-sdk/google-vertex/$GOOGLE_VERTEX_VERSION",
            providerOptionsName = "xai",
            transformChatRequestBody = ::googleVertexXaiRequestBody,
            convertUsage = ::googleVertexXaiUsage,
        ),
    )
    override fun languageModel(modelId: String): LanguageModel =
        GoogleVertexXaiLanguageModel(delegate.languageModel(modelId))

    override fun embeddingModel(modelId: String): EmbeddingModel = throw NoSuchModelError(providerId, "embeddingModel", modelId)
    override fun imageModel(modelId: String): ImageModel = throw NoSuchModelError(providerId, "imageModel", modelId)
}

private fun googleVertexPublisherBaseURL(settings: GoogleVertexProviderSettings): String =
    settings.baseURL?.trimEnd('/')
        ?: if (!settings.apiKey.isNullOrBlank() && settings.project.isNullOrBlank()) {
            "https://aiplatform.googleapis.com/v1/publishers/google"
        } else {
            "https://${googleVertexApiHost(settings.location)}/v1/projects/${googleVertexProject(settings)}/locations/${settings.location}/publishers/google"
        }

private fun googleVertexOpenAIBaseURL(settings: GoogleVertexProviderSettings): String =
    settings.baseURL?.trimEnd('/')
        ?: "https://aiplatform.googleapis.com/v1/projects/${googleVertexProject(settings)}/locations/${settings.location}/endpoints/openapi"

private fun googleVertexAnthropicBaseURL(settings: GoogleVertexProviderSettings): String =
    settings.baseURL?.trimEnd('/')
        ?: "https://${googleVertexApiHost(settings.location)}/v1/projects/${googleVertexProject(settings)}/locations/${settings.location}/publishers/anthropic/models"

private fun googleVertexProject(settings: GoogleVertexProviderSettings): String =
    settings.project ?: throw AiSdkException("Google Vertex project is required.")

private fun googleVertexApiHost(location: String): String =
    if (location == "global") "aiplatform.googleapis.com" else "$location-aiplatform.googleapis.com"

private fun googleVertexHeaders(settings: GoogleVertexProviderSettings): Map<String, String> {
    val headers = linkedMapOf<String, String>()
    settings.accessToken?.takeIf { it.isNotBlank() }?.let { headers[HttpHeaders.Authorization] = "Bearer $it" }
    headers[HttpHeaders.UserAgent] = "ai-sdk/google-vertex/$GOOGLE_VERTEX_VERSION"
    headers.putAll(settings.headers)
    return headers
}

private fun googleVertexAnthropicHeaders(
    settings: GoogleVertexProviderSettings,
    baseHeaders: Map<String, String>,
): Map<String, String> {
    val blocked = setOf(
        "anthropic-version",
        "anthropic-beta",
        "x-api-key",
        HttpHeaders.Authorization.lowercase(),
        HttpHeaders.UserAgent.lowercase(),
    )
    val passthrough = baseHeaders.filterKeys { key -> key.lowercase() !in blocked }
    return googleVertexHeaders(settings) + passthrough
}

private fun googleVertexAnthropicBody(body: JsonObject): JsonObject = buildJsonObject {
    body.forEach { (key, value) ->
        if (key != "model" && key != "anthropic_version") put(key, value)
    }
    put("anthropic_version", JsonPrimitive("vertex-2023-10-16"))
}

private class GoogleVertexXaiLanguageModel(
    private val delegate: LanguageModel,
) : LanguageModel by delegate {
    override val supportedUrls: Map<String, List<String>> = mapOf("image/*" to listOf("^https?://.*$"))

    override suspend fun generate(params: LanguageModelCallParams): LanguageModelResult =
        delegate.generate(params.copy(providerOptions = googleVertexXaiProviderOptions(params.providerOptions)))

    override fun stream(params: LanguageModelCallParams) =
        delegate.stream(params.copy(providerOptions = googleVertexXaiProviderOptions(params.providerOptions)))

    override fun streamResult(params: LanguageModelCallParams): LanguageModelStreamResult =
        delegate.streamResult(params.copy(providerOptions = googleVertexXaiProviderOptions(params.providerOptions)))
}

private fun googleVertexXaiProviderOptions(options: Map<String, JsonElement>): Map<String, JsonElement> {
    val xai = options["xai"] as? JsonObject ?: return options
    val transformed = buildJsonObject {
        for ((key, value) in xai) {
            when (key) {
                "reasoningEffort" -> put("reasoning_effort", value)
                "topLogprobs" -> {
                    put("top_logprobs", value)
                    if ("logprobs" !in xai) put("logprobs", JsonPrimitive(true))
                }
                "searchParameters" -> put("search_parameters", googleVertexXaiSnakeCase(value))
                else -> put(key, value)
            }
        }
    }
    return options + ("xai" to transformed)
}

private fun googleVertexXaiRequestBody(body: JsonObject): JsonObject = buildJsonObject {
    body.forEach { (key, value) ->
        if (key != "reasoning_effort") put(key, value)
    }
}

private fun googleVertexXaiUsage(value: JsonElement?): Usage {
    val obj = value as? JsonObject ?: return Usage()
    val promptTokens = obj["prompt_tokens"]?.jsonPrimitive?.intOrNull ?: 0
    val completionTokens = obj["completion_tokens"]?.jsonPrimitive?.intOrNull ?: 0
    val cacheReadTokens = (obj["prompt_tokens_details"]?.jsonObject?.get("cached_tokens")?.jsonPrimitive?.intOrNull ?: 0)
        .coerceIn(0, promptTokens)
    val reasoningTokens = (obj["completion_tokens_details"]?.jsonObject?.get("reasoning_tokens")?.jsonPrimitive?.intOrNull ?: 0)
        .coerceAtLeast(0)
    return Usage(
        inputTokens = Usage.InputTokenBreakdown(
            total = promptTokens,
            noCache = (promptTokens - cacheReadTokens).coerceAtLeast(0),
            cacheRead = cacheReadTokens,
        ),
        outputTokens = Usage.OutputTokenBreakdown(
            total = completionTokens + reasoningTokens,
            text = completionTokens,
            reasoning = reasoningTokens,
        ),
        raw = value,
    )
}

private fun googleVertexXaiSnakeCase(value: JsonElement): JsonElement =
    when (value) {
        is JsonObject -> buildJsonObject {
            value.forEach { (key, nested) -> put(googleVertexXaiSnakeCaseKey(key), googleVertexXaiSnakeCase(nested)) }
        }
        is JsonArray -> JsonArray(value.map(::googleVertexXaiSnakeCase))
        else -> value
    }

private fun googleVertexXaiSnakeCaseKey(value: String): String =
    buildString {
        value.forEach { char ->
            if (char.isUpperCase()) {
                append('_')
                append(char.lowercaseChar())
            } else {
                append(char)
            }
        }
    }
