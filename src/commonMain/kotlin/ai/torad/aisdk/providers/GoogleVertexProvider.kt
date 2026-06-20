package ai.torad.aisdk.providers

import ai.torad.aisdk.*
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

public const val GOOGLE_VERTEX_VERSION: String = "4.0.142"

public typealias GoogleVertexEmbeddingModelOptions = JsonObject
public typealias GoogleVertexImageModelOptions = JsonObject
public typealias GoogleVertexImageProviderOptions = JsonObject
public typealias GoogleVertexVideoModelOptions = JsonObject
public typealias GoogleVertexVideoProviderOptions = JsonObject

@Serializable
public data class GoogleVertexProviderSettings(
    val project: String? = null,
    val location: String = "us-central1",
    val baseURL: String? = null,
    val accessToken: String? = null,
    val apiKey: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val generateId: () -> String = { IdGenerator.generate() },
)

public class GoogleVertexProvider(
    client: HttpClient,
    public val settings: GoogleVertexProviderSettings,
) : Provider {
    override val providerId: String = "google-vertex"
    public val tools: GoogleTools = googleTools

    private val delegate: GoogleGenerativeAIProvider = GoogleGenerativeAI(
        client,
        GoogleGenerativeAIProviderSettings(
            baseURL = googleVertexPublisherBaseURL(settings),
            apiKey = settings.apiKey,
            headers = googleVertexHeaders(settings),
            generateId = settings.generateId,
            name = "google.vertex",
        ),
    )

    public operator fun invoke(modelId: ModelId): LanguageModel = languageModel(modelId)
    public fun chat(modelId: ModelId): LanguageModel = languageModel(modelId)
    public fun generativeAI(modelId: ModelId): LanguageModel = languageModel(modelId)

    public fun embedding(modelId: ModelId): EmbeddingModel = delegate.embedding(modelId)
    public fun textEmbedding(modelId: ModelId): EmbeddingModel = embedding(modelId)
    public fun textEmbeddingModel(modelId: ModelId): EmbeddingModel = embedding(modelId)
    public fun image(modelId: ModelId): ImageModel = delegate.image(modelId)
    public fun video(modelId: ModelId): VideoModel = delegate.video(modelId)

    // Vertex serves files by HTTP(S) and Google Cloud Storage (gs://) — NOT the
    // generative-AI files-API/YouTube set the underlying Google model advertises.
    override fun languageModel(modelId: String): LanguageModel =
        VertexSupportedUrlsModel(delegate.languageModel(modelId))
    override fun embeddingModel(modelId: String): EmbeddingModel = embedding(ModelId(modelId))
    override fun imageModel(modelId: String): ImageModel = image(ModelId(modelId))
    override fun videoModel(modelId: String): VideoModel = video(ModelId(modelId))
}

/** PascalCase factory — mirrors the OpenAI(...) reference pattern. */
public fun GoogleVertex(
    client: HttpClient,
    settings: GoogleVertexProviderSettings = GoogleVertexProviderSettings(),
): GoogleVertexProvider = GoogleVertexProvider(client, settings)

/** Overrides supportedUrls to the Vertex set (http(s) + gs://), delegating everything else. */
private class VertexSupportedUrlsModel(
    delegate: LanguageModel,
) : LanguageModel by delegate {
    override val supportedUrls: Map<String, List<String>> =
        mapOf("*" to listOf("^https?://.*$", "^gs://.*$"))
}

public typealias GoogleVertexAnthropicProviderSettings = GoogleVertexProviderSettings
public typealias GoogleVertexMaasProviderSettings = GoogleVertexProviderSettings
public typealias GoogleVertexXaiProviderSettings = GoogleVertexProviderSettings

public class GoogleVertexAnthropicProvider(
    client: HttpClient,
    public val settings: GoogleVertexAnthropicProviderSettings,
) : Provider {
    override val providerId: String = "google-vertex-anthropic"
    public val tools: AnthropicTools = anthropicTools

    private val delegate = Anthropic(
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

    public operator fun invoke(modelId: ModelId): LanguageModel = languageModel(modelId)
    public fun chat(modelId: ModelId): LanguageModel = languageModel(modelId)
    public fun messages(modelId: ModelId): LanguageModel = languageModel(modelId)
    public fun textEmbeddingModel(modelId: String): Nothing = throw NoSuchModelError(providerId, "embeddingModel", modelId)

    override fun languageModel(modelId: String): LanguageModel = delegate.languageModel(modelId)
    override fun embeddingModel(modelId: String): EmbeddingModel = throw NoSuchModelError(providerId, "embeddingModel", modelId)
    override fun imageModel(modelId: String): ImageModel = throw NoSuchModelError(providerId, "imageModel", modelId)
}

public class GoogleVertexMaasProvider(
    client: HttpClient,
    public val settings: GoogleVertexMaasProviderSettings,
) : Provider {
    override val providerId: String = "google-vertex-maas"

    private val delegate = OpenAICompatible(
        client,
        OpenAICompatibleProviderSettings(
            name = "google-vertex-maas",
            baseUrl = googleVertexOpenAIBaseURL(settings),
            headers = googleVertexHeaders(settings),
            userAgentSuffix = "ai-sdk/google-vertex/$GOOGLE_VERTEX_VERSION",
        ),
    )

    public operator fun invoke(modelId: ModelId): LanguageModel = languageModel(modelId)
    public fun chat(modelId: ModelId): LanguageModel = languageModel(modelId)

    override fun languageModel(modelId: String): LanguageModel = delegate.languageModel(modelId)
}

public class GoogleVertexXaiProvider(
    client: HttpClient,
    public val settings: GoogleVertexXaiProviderSettings,
) : Provider {
    override val providerId: String = "google-vertex-xai"

    private val delegate = OpenAICompatible(
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

    public operator fun invoke(modelId: ModelId): LanguageModel = languageModel(modelId)
    public fun chat(modelId: ModelId): LanguageModel = languageModel(modelId)
    public fun chatModel(modelId: ModelId): LanguageModel = languageModel(modelId)
    public fun textEmbeddingModel(modelId: String): Nothing = throw NoSuchModelError(providerId, "embeddingModel", modelId)

    override fun languageModel(modelId: String): LanguageModel =
        GoogleVertexXaiLanguageModel(delegate.languageModel(modelId))
    override fun embeddingModel(modelId: String): EmbeddingModel = throw NoSuchModelError(providerId, "embeddingModel", modelId)
    override fun imageModel(modelId: String): ImageModel = throw NoSuchModelError(providerId, "imageModel", modelId)
}

/** PascalCase factory — mirrors the OpenAI(...) reference pattern. */
public fun GoogleVertexAnthropic(
    client: HttpClient,
    settings: GoogleVertexAnthropicProviderSettings = GoogleVertexAnthropicProviderSettings(),
): GoogleVertexAnthropicProvider = GoogleVertexAnthropicProvider(client, settings)

/** PascalCase factory — mirrors the OpenAI(...) reference pattern. */
public fun GoogleVertexMaas(
    client: HttpClient,
    settings: GoogleVertexMaasProviderSettings = GoogleVertexMaasProviderSettings(),
): GoogleVertexMaasProvider = GoogleVertexMaasProvider(client, settings)

/** PascalCase factory — mirrors the OpenAI(...) reference pattern. */
public fun GoogleVertexXai(
    client: HttpClient,
    settings: GoogleVertexXaiProviderSettings = GoogleVertexXaiProviderSettings(),
): GoogleVertexXaiProvider = GoogleVertexXaiProvider(client, settings)

private fun googleVertexPublisherBaseURL(settings: GoogleVertexProviderSettings): String =
    settings.baseURL?.trimEnd('/')
        ?: if (!settings.apiKey.isNullOrBlank() && settings.project.isNullOrBlank()) {
            "https://aiplatform.googleapis.com/v1/publishers/google"
        } else {
            // Project-scoped Vertex publisher generateContent is served under v1beta1
            // (the generateContent surface is not on v1 for project paths).
            "https://${googleVertexApiHost(settings.location)}/v1beta1/projects/" +
                "${googleVertexProject(settings)}/locations/${settings.location}/publishers/google"
        }

private fun googleVertexOpenAIBaseURL(settings: GoogleVertexProviderSettings): String =
    settings.baseURL?.trimEnd('/')
        ?: "https://${googleVertexApiHost(settings.location)}/v1/projects/${googleVertexProject(settings)}/locations/${settings.location}/endpoints/openapi"

private fun googleVertexAnthropicBaseURL(settings: GoogleVertexProviderSettings): String =
    settings.baseURL?.trimEnd('/')
        ?: "https://${googleVertexApiHost(settings.location)}/v1/projects/${googleVertexProject(settings)}/locations/${settings.location}/publishers/anthropic/models"

private fun googleVertexProject(settings: GoogleVertexProviderSettings): String =
    settings.project ?: throw LoadSettingError("Google Vertex project is required.")

private fun googleVertexApiHost(location: String): String =
    when (location) {
        "global" -> "aiplatform.googleapis.com"
        "eu", "us" -> "aiplatform.$location.rep.googleapis.com"
        else -> "$location-aiplatform.googleapis.com"
    }

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

private fun googleVertexXaiProviderOptions(options: ProviderOptions): ProviderOptions {
    val map = options.toMap()
    val xai = map["xai"] as? JsonObject ?: return options
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
    return ProviderOptions.Raw(JsonObject(map + ("xai" to (transformed as JsonElement))))
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
