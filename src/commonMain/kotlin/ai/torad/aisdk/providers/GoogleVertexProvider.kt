@file:OptIn(ai.torad.aisdk.LowLevelLanguageModelApi::class)

package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import io.ktor.client.HttpClient
import io.ktor.http.HttpHeaders
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

public const val GOOGLE_VERTEX_VERSION: String = "4.0.142"

public typealias GoogleVertexEmbeddingModelOptions = JsonObject
public typealias GoogleVertexImageModelOptions = JsonObject
public typealias GoogleVertexImageProviderOptions = JsonObject
public typealias GoogleVertexVideoModelOptions = JsonObject
public typealias GoogleVertexVideoProviderOptions = JsonObject

@Serializable
public class GoogleVertexProviderSettings internal constructor(
    public val project: String? = null,
    public val location: String = "us-central1",
    public val baseURL: String? = null,
    public val accessToken: String? = null,
    public val apiKey: String? = null,
    public val headers: Map<String, String> = emptyMap(),
    public val generateId: () -> String = { IdGenerator.generate() },
) {
    internal fun googleVertexPublisherBaseURL(): String =
        baseURL?.trimEnd('/')
            ?: if (!apiKey.isNullOrBlank() && project.isNullOrBlank()) {
                "https://aiplatform.googleapis.com/v1/publishers/google"
            } else {
                // Project-scoped Vertex publisher generateContent is served under v1beta1
                // (the generateContent surface is not on v1 for project paths).
                "https://${googleVertexApiHost(location)}/v1beta1/projects/" +
                    "${googleVertexProject()}/locations/$location/publishers/google"
            }

    internal fun googleVertexOpenAIBaseURL(): String =
        baseURL?.trimEnd('/')
            ?: "https://${googleVertexApiHost(location)}/v1/projects/${googleVertexProject()}/locations/$location/endpoints/openapi"

    internal fun googleVertexAnthropicBaseURL(): String =
        baseURL?.trimEnd('/')
            ?: "https://${googleVertexApiHost(location)}/v1/projects/${googleVertexProject()}/locations/$location/publishers/anthropic/models"

    private fun googleVertexProject(): String =
        project ?: throw LoadSettingError("Google Vertex project is required.")

    private fun googleVertexApiHost(location: String): String =
        when (location) {
            "global" -> "aiplatform.googleapis.com"
            "eu", "us" -> "aiplatform.$location.rep.googleapis.com"
            else -> "$location-aiplatform.googleapis.com"
        }

    internal fun googleVertexHeaders(): Map<String, String> {
        val headers = linkedMapOf<String, String>()
        accessToken?.takeIf { it.isNotBlank() }?.let { headers[HttpHeaders.Authorization] = "Bearer $it" }
        headers[HttpHeaders.UserAgent] = "ai-sdk/google-vertex/$GOOGLE_VERTEX_VERSION"
        headers.putAll(this.headers)
        return headers
    }

    internal fun googleVertexAnthropicHeaders(baseHeaders: Map<String, String>): Map<String, String> {
        val blocked = setOf(
            "anthropic-version",
            "anthropic-beta",
            "x-api-key",
            HttpHeaders.Authorization.lowercase(),
            HttpHeaders.UserAgent.lowercase(),
        )
        val passthrough = baseHeaders.filterKeys { key -> key.lowercase() !in blocked }
        return googleVertexHeaders() + passthrough
    }
}

public class GoogleVertexProviderSettingsBuilder internal constructor() {
    private var project: String? = null
    private var location: String = "us-central1"
    private var baseURL: String? = null
    private var accessToken: String? = null
    private var apiKey: String? = null
    private var headers: Map<String, String> = emptyMap()
    private var generateId: () -> String = { IdGenerator.generate() }

    public fun project(value: String?) {
        project = value
    }

    public fun location(value: String) {
        location = value
    }

    public fun baseURL(value: String?) {
        baseURL = value
    }

    public fun accessToken(value: String?) {
        accessToken = value
    }

    public fun apiKey(value: String?) {
        apiKey = value
    }

    public fun headers(value: Map<String, String>) {
        headers = value
    }

    public fun generateId(value: () -> String) {
        generateId = value
    }

    internal fun build(): GoogleVertexProviderSettings =
        GoogleVertexProviderSettings(
            project = project,
            location = location,
            baseURL = baseURL,
            accessToken = accessToken,
            apiKey = apiKey,
            headers = headers,
            generateId = generateId,
        )
}

public fun GoogleVertexProviderSettings(
    block: GoogleVertexProviderSettingsBuilder.() -> Unit = {},
): GoogleVertexProviderSettings =
    GoogleVertexProviderSettingsBuilder().apply(block).build()

public class GoogleVertexProvider(
    client: HttpClient,
    public val settings: GoogleVertexProviderSettings,
) : Provider {
    override val providerId: String = "google-vertex"
    public val tools: GoogleTools = googleTools

    private val delegate: GoogleGenerativeAIProvider = GoogleGenerativeAI(
        client,
        GoogleGenerativeAIProviderSettings {
            baseURL(settings.googleVertexPublisherBaseURL())
            apiKey(settings.apiKey)
            headers(settings.googleVertexHeaders())
            generateId(settings.generateId)
            name("google.vertex")
        },
    )

    public operator fun invoke(modelId: ModelId): LanguageModel = languageModel(modelId.value)
    public fun chat(modelId: ModelId): LanguageModel = languageModel(modelId.value)
    public fun generativeAI(modelId: ModelId): LanguageModel = languageModel(modelId.value)

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
        AnthropicProviderSettings {
            baseURL(settings.googleVertexAnthropicBaseURL())
            headers(emptyMap())
            requestHeadersProvider { _, _, headers -> settings.googleVertexAnthropicHeaders(headers) }
            buildRequestUrl { baseURL, modelId, isStreaming ->
                "$baseURL/$modelId:${if (isStreaming) "streamRawPredict" else "rawPredict"}"
            }
            transformRequestBody { _, body, _ -> googleVertexAnthropicBody(body) }
            supportedUrls(emptyMap())
            generateId(settings.generateId)
            name("vertex.anthropic.messages")
        },
    )

    public operator fun invoke(modelId: ModelId): LanguageModel = languageModel(modelId.value)
    public fun chat(modelId: ModelId): LanguageModel = languageModel(modelId.value)
    public fun messages(modelId: ModelId): LanguageModel = languageModel(modelId.value)
    public fun textEmbeddingModel(modelId: String): Nothing = throw NoSuchModelError(providerId, "embeddingModel", modelId)

    override fun languageModel(modelId: String): LanguageModel = delegate.languageModel(modelId)
    override fun embeddingModel(modelId: String): EmbeddingModel = throw NoSuchModelError(providerId, "embeddingModel", modelId)
    override fun imageModel(modelId: String): ImageModel = throw NoSuchModelError(providerId, "imageModel", modelId)

    private fun googleVertexAnthropicBody(body: JsonObject): JsonObject = buildJsonObject {
        body.forEach { (key, value) ->
            if (key != "model" && key != "anthropic_version") put(key, value)
        }
        put("anthropic_version", JsonPrimitive("vertex-2023-10-16"))
    }
}

public class GoogleVertexMaasProvider(
    client: HttpClient,
    public val settings: GoogleVertexMaasProviderSettings,
) : Provider {
    override val providerId: String = "google-vertex-maas"

    private val delegate = OpenAICompatible(
        client,
        OpenAICompatibleProviderSettings {
            name("google-vertex-maas")
            baseUrl(settings.googleVertexOpenAIBaseURL())
            headers(settings.googleVertexHeaders())
            userAgentSuffix("ai-sdk/google-vertex/$GOOGLE_VERTEX_VERSION")
        },
    )

    public operator fun invoke(modelId: ModelId): LanguageModel = languageModel(modelId.value)
    public fun chat(modelId: ModelId): LanguageModel = languageModel(modelId.value)

    override fun languageModel(modelId: String): LanguageModel = delegate.languageModel(modelId)
}

public class GoogleVertexXaiProvider(
    client: HttpClient,
    public val settings: GoogleVertexXaiProviderSettings,
) : Provider {
    override val providerId: String = "google-vertex-xai"

    private val delegate = OpenAICompatible(
        client,
        OpenAICompatibleProviderSettings {
            name("googleVertex.xai")
            baseUrl(settings.googleVertexOpenAIBaseURL())
            headers(settings.googleVertexHeaders())
            includeUsage(true)
            supportsStructuredOutputs(true)
            supportedUrls(mapOf("image/*" to listOf("^https?://.*$")))
            userAgentSuffix("ai-sdk/google-vertex/$GOOGLE_VERTEX_VERSION")
            providerOptionsName("xai")
            transformChatRequestBody(this@GoogleVertexXaiProvider::googleVertexXaiRequestBody)
            convertUsage(this@GoogleVertexXaiProvider::googleVertexXaiUsage)
        },
    )

    public operator fun invoke(modelId: ModelId): LanguageModel = languageModel(modelId.value)
    public fun chat(modelId: ModelId): LanguageModel = languageModel(modelId.value)
    public fun chatModel(modelId: ModelId): LanguageModel = languageModel(modelId.value)
    public fun textEmbeddingModel(modelId: String): Nothing = throw NoSuchModelError(providerId, "embeddingModel", modelId)

    override fun languageModel(modelId: String): LanguageModel =
        GoogleVertexXaiLanguageModel(delegate.languageModel(modelId))
    override fun embeddingModel(modelId: String): EmbeddingModel = throw NoSuchModelError(providerId, "embeddingModel", modelId)
    override fun imageModel(modelId: String): ImageModel = throw NoSuchModelError(providerId, "imageModel", modelId)

    private fun googleVertexXaiRequestBody(body: JsonObject): JsonObject = buildJsonObject {
        body.forEach { (key, value) ->
            if (key != "reasoning_effort") put(key, value)
        }
    }

    private fun googleVertexXaiUsage(value: JsonElement?): Usage {
        val obj = value as? JsonObject ?: return Usage()
        val promptTokens = (obj["prompt_tokens"] as? JsonPrimitive)?.intOrNull ?: 0
        val completionTokens = (obj["completion_tokens"] as? JsonPrimitive)?.intOrNull ?: 0
        val cacheReadTokens = (((JsonAccess.obj(obj, "prompt_tokens_details"))?.get("cached_tokens") as? JsonPrimitive)?.intOrNull ?: 0)
            .coerceIn(0, promptTokens)
        val reasoningTokens = (((JsonAccess.obj(obj, "completion_tokens_details"))?.get("reasoning_tokens") as? JsonPrimitive)?.intOrNull ?: 0)
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

private class GoogleVertexXaiLanguageModel(
    private val delegate: LanguageModel,
) : LanguageModel by delegate {
    override val supportedUrls: Map<String, List<String>> = mapOf("image/*" to listOf("^https?://.*$"))

    override suspend fun generate(params: LanguageModelCallParams): LanguageModelResult =
        delegate.generate(params.toBuilder().providerOptions(googleVertexXaiProviderOptions(params.providerOptions)).build())

    override fun stream(params: LanguageModelCallParams) =
        delegate.stream(params.toBuilder().providerOptions(googleVertexXaiProviderOptions(params.providerOptions)).build())

    override fun streamResult(params: LanguageModelCallParams): LanguageModelStreamResult =
        delegate.streamResult(params.toBuilder().providerOptions(googleVertexXaiProviderOptions(params.providerOptions)).build())

    // Snake-cases xAI searchParameters via XaiProviderSettings.xaiSnakeCaseJson (single source of
    // truth); the former local copy drifted, lacking the `xHandles` -> `included_x_handles`
    // special-case and the `index > 0` leading-underscore guard.
    private fun googleVertexXaiProviderOptions(options: ProviderOptions): ProviderOptions {
        val map = options.toMap()
        val xai = JsonAccess.obj(map, "xai") ?: return options
        val transformed = buildJsonObject {
            for ((key, value) in xai) {
                when (key) {
                    "reasoningEffort" -> put("reasoning_effort", value)
                    "topLogprobs" -> {
                        put("top_logprobs", value)
                        if ("logprobs" !in xai) put("logprobs", JsonPrimitive(true))
                    }
                    "searchParameters" -> put("search_parameters", XaiProviderSettings.xaiSnakeCaseJson(value))
                    else -> put(key, value)
                }
            }
        }
        return ProviderOptions.Raw(JsonObject(map + ("xai" to (transformed as JsonElement))))
    }
}
