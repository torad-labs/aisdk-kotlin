package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import dev.drewhamilton.poko.Poko
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
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

public const val GOOGLE_VERSION: String = "3.0.80"

public typealias GoogleLanguageModelOptions = JsonObject
public typealias GoogleGenerativeAIProviderOptions = JsonObject
public typealias GoogleEmbeddingModelOptions = JsonObject
public typealias GoogleGenerativeAIEmbeddingProviderOptions = JsonObject
public typealias GoogleImageModelOptions = JsonObject
public typealias GoogleGenerativeAIImageProviderOptions = JsonObject
public typealias GoogleVideoModelOptions = JsonObject
public typealias GoogleGenerativeAIVideoProviderOptions = JsonObject
public typealias GoogleLanguageModelInteractionsOptions = JsonObject
public typealias GoogleGenerativeAIProviderMetadata = JsonObject
public typealias GoogleInteractionsProviderMetadata = JsonObject
public typealias GoogleErrorData = JsonObject
public typealias GroundingMetadataSchema = JsonObject
public typealias UrlContextMetadataSchema = JsonObject
public typealias UsageMetadataSchema = JsonObject
public typealias SafetyRatingSchema = JsonObject
public typealias PromptFeedbackSchema = JsonObject

/** @since 0.3.0-beta01 */
public sealed class GoogleInteractionsModelInput {
    /** @since 0.3.0-beta01 */
    public abstract val name: String

    /** @since 0.3.0-beta01 */
    public data class Model(override val name: String) : GoogleInteractionsModelInput()
    /** @since 0.3.0-beta01 */
    public data class Agent(override val name: String) : GoogleInteractionsModelInput()
    /** @since 0.3.0-beta01 */
    public data class ManagedAgent(override val name: String) : GoogleInteractionsModelInput()
}

@Serializable
@Poko
/** @since 0.3.0-beta01 */
public class GoogleGenerativeAIProviderSettings internal constructor(
    /** @since 0.3.0-beta01 */
    public val baseURL: String = "https://generativelanguage.googleapis.com/v1beta",
    /** @since 0.3.0-beta01 */
    public val apiKey: String? = null,
    /** @since 0.3.0-beta01 */
    public val headers: Map<String, String> = emptyMap(),
    /** @since 0.3.0-beta01 */
    public val generateId: () -> String = { IdGenerator.generate() },
    /** @since 0.3.0-beta01 */
    public val name: String = "google.generative-ai",
    /** @since 0.3.0-beta01 */
    public val videoPollIntervalMillis: Long = 1_000L,
    /** @since 0.3.0-beta01 */
    public val videoMaxPollAttempts: Int = 120,
) {
    internal fun googleHeaders(extra: Map<String, String>): Map<String, String> {
        val headers = linkedMapOf<String, String>()
        apiKey?.let { headers["x-goog-api-key"] = it }
        headers.putAll(this.headers)
        headers.putAll(extra)
        headers[HttpHeaders.UserAgent] = GoogleHttp.appendGoogleUserAgent(headers[HttpHeaders.UserAgent], "ai-sdk/google/$GOOGLE_VERSION")
        return headers
    }

    internal fun googleInteractionsHeaders(extra: Map<String, String>): Map<String, String> =
        googleHeaders(extra) + ("Api-Revision" to "2026-05-20")
}

/** @since 0.3.0-beta01 */
public class GoogleGenerativeAIProviderSettingsBuilder {
    private var baseURL: String = "https://generativelanguage.googleapis.com/v1beta"
    private var apiKey: String? = null
    private var headers: Map<String, String> = emptyMap()
    private var generateId: () -> String = { IdGenerator.generate() }
    private var name: String = "google.generative-ai"
    private var videoPollIntervalMillis: Long = 1_000L
    private var videoMaxPollAttempts: Int = 120

    /** @since 0.3.0-beta01 */
    public fun baseURL(value: String): GoogleGenerativeAIProviderSettingsBuilder {
        baseURL = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun apiKey(value: String?): GoogleGenerativeAIProviderSettingsBuilder {
        apiKey = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun headers(value: Map<String, String>): GoogleGenerativeAIProviderSettingsBuilder {
        headers = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun generateId(value: () -> String): GoogleGenerativeAIProviderSettingsBuilder {
        generateId = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun name(value: String): GoogleGenerativeAIProviderSettingsBuilder {
        name = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun videoPollIntervalMillis(value: Long): GoogleGenerativeAIProviderSettingsBuilder {
        videoPollIntervalMillis = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun videoMaxPollAttempts(value: Int): GoogleGenerativeAIProviderSettingsBuilder {
        videoMaxPollAttempts = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun build(): GoogleGenerativeAIProviderSettings =
        GoogleGenerativeAIProviderSettings(
            baseURL = baseURL,
            apiKey = apiKey,
            headers = headers,
            generateId = generateId,
            name = name,
            videoPollIntervalMillis = videoPollIntervalMillis,
            videoMaxPollAttempts = videoMaxPollAttempts,
        )
}

/** @since 0.3.0-beta01 */
public fun GoogleGenerativeAIProviderSettings(
    block: GoogleGenerativeAIProviderSettingsBuilder.() -> Unit = {},
): GoogleGenerativeAIProviderSettings =
    GoogleGenerativeAIProviderSettingsBuilder().apply(block).build()

/** @since 0.3.0-beta01 */
public class GoogleGenerativeAIProvider(
    private val client: HttpClient,
    /** @since 0.3.0-beta01 */
    public val settings: GoogleGenerativeAIProviderSettings = GoogleGenerativeAIProviderSettings(),
) : Provider {
    override val providerId: String = "google"
    /** @since 0.3.0-beta01 */
    public val tools: GoogleTools = GoogleTools()

    public operator fun invoke(modelId: ModelId): LanguageModel = languageModel(modelId.value)

    override fun languageModel(modelId: String): LanguageModel =
        GoogleGenerativeAILanguageModel(client, settings, modelId)

    /** @since 0.3.0-beta01 */
    public fun chat(modelId: ModelId): LanguageModel = languageModel(modelId.value)
    /** @since 0.3.0-beta01 */
    public fun generativeAI(modelId: ModelId): LanguageModel = languageModel(modelId.value)

    /** @since 0.3.0-beta01 */
    public fun embedding(modelId: ModelId): EmbeddingModel =
        GoogleGenerativeAIEmbeddingModel(client, settings, modelId.value)
    /** @since 0.3.0-beta01 */
    public fun textEmbedding(modelId: ModelId): EmbeddingModel = embedding(modelId)
    /** @since 0.3.0-beta01 */
    public fun textEmbeddingModel(modelId: ModelId): EmbeddingModel = embedding(modelId)

    /** @since 0.3.0-beta01 */
    public fun image(modelId: ModelId): ImageModel =
        GoogleGenerativeAIImageModel(client, settings, modelId.value)

    /** @since 0.3.0-beta01 */
    public fun video(modelId: ModelId): VideoModel =
        GoogleGenerativeAIVideoModel(client, settings, modelId.value)

    /** @since 0.3.0-beta01 */
    public fun interactions(modelIdOrAgent: ModelId): LanguageModel =
        interactions(GoogleInteractionsModelInput.Model(modelIdOrAgent.value))
    /** @since 0.3.0-beta01 */
    public fun interactions(modelIdOrAgent: GoogleInteractionsModelInput): LanguageModel =
        GoogleInteractionsLanguageModel(client, settings, modelIdOrAgent)
    /** @since 0.3.0-beta01 */
    public fun agentInteraction(agentName: String): LanguageModel =
        interactions(GoogleInteractionsModelInput.Agent(agentName))
    /** @since 0.3.0-beta01 */
    public fun managedAgentInteraction(agentName: String): LanguageModel =
        interactions(GoogleInteractionsModelInput.ManagedAgent(agentName))

    override fun embeddingModel(modelId: String): EmbeddingModel = embedding(ModelId(modelId))
    override fun imageModel(modelId: String): ImageModel = image(ModelId(modelId))
    override fun videoModel(modelId: String): VideoModel = video(ModelId(modelId))
}

// Source/binary-compat alias for the factory below — the constructor now lives on the merged class.
private typealias DefaultGoogleGenerativeAIProvider = GoogleGenerativeAIProvider

/**
 * PascalCase factory — mirrors the OpenAI(...) reference faux-constructor.
 * @since 0.3.0-beta01
 */
public fun GoogleGenerativeAI(
    client: HttpClient,
    settings: GoogleGenerativeAIProviderSettings = GoogleGenerativeAIProviderSettings(),
): GoogleGenerativeAIProvider = GoogleGenerativeAIProvider(client, settings)


@Poko
/** @since 0.3.0-beta01 */
public class GoogleTools(
    /** @since 0.3.0-beta01 */
    public val googleSearch: Tool<JsonElement, JsonElement, Any?> =
        providerTool("google_search", "google.google_search", "Ground responses with Google Search."),
    /** @since 0.3.0-beta01 */
    public val enterpriseWebSearch: Tool<JsonElement, JsonElement, Any?> =
        providerTool("enterprise_web_search", "google.enterprise_web_search", "Ground responses with Enterprise Web Search."),
    /** @since 0.3.0-beta01 */
    public val googleMaps: Tool<JsonElement, JsonElement, Any?> =
        providerTool("google_maps", "google.google_maps", "Ground responses with Google Maps."),
    /** @since 0.3.0-beta01 */
    public val urlContext: Tool<JsonElement, JsonElement, Any?> =
        providerTool("url_context", "google.url_context", "Fetch URL context through Google."),
    /** @since 0.3.0-beta01 */
    public val fileSearch: Tool<JsonElement, JsonElement, Any?> =
        providerTool("file_search", "google.file_search", "Use Gemini File Search."),
    /** @since 0.3.0-beta01 */
    public val codeExecution: Tool<JsonElement, JsonElement, Any?> =
        providerTool("code_execution", "google.code_execution", "Use Google hosted code execution."),
    /** @since 0.3.0-beta01 */
    public val vertexRagStore: Tool<JsonElement, JsonElement, Any?> =
        providerTool("vertex_rag_store", "google.vertex_rag_store", "Use Vertex RAG Store."),
) {
    internal companion object {
        internal fun providerTool(
            name: String,
            id: String,
            description: String,
        ): Tool<JsonElement, JsonElement, Any?> =
            ProviderExecutedTool(
                name = name,
                description = description,
                inputSerializer = JsonElement.serializer(),
                outputSerializer = JsonElement.serializer(),
                metadata = mapOf("providerToolId" to JsonPrimitive(id)),
            )
    }
}

/** @since 0.3.0-beta01 */
public val googleTools: GoogleTools = GoogleTools()
