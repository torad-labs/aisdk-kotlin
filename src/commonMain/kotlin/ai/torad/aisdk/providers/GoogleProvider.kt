package ai.torad.aisdk.providers

import ai.torad.aisdk.*
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
import kotlinx.serialization.json.JsonArray
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

public sealed interface GoogleInteractionsModelInput {
    public val name: String

    public data class Model(override val name: String) : GoogleInteractionsModelInput
    public data class Agent(override val name: String) : GoogleInteractionsModelInput
    public data class ManagedAgent(override val name: String) : GoogleInteractionsModelInput
}

@Serializable
public data class GoogleGenerativeAIProviderSettings(
    val baseURL: String = "https://generativelanguage.googleapis.com/v1beta",
    val apiKey: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val generateId: () -> String = { IdGenerator.generate() },
    val name: String = "google.generative-ai",
    val videoPollIntervalMillis: Long = 1_000L,
    val videoMaxPollAttempts: Int = 120,
)

public class GoogleGenerativeAIProvider(
    private val client: HttpClient,
    public val settings: GoogleGenerativeAIProviderSettings = GoogleGenerativeAIProviderSettings(),
) : Provider {
    override val providerId: String = "google"
    public val tools: GoogleTools = GoogleTools()

    public operator fun invoke(modelId: ModelId): LanguageModel = languageModel(modelId)

    override fun languageModel(modelId: String): LanguageModel =
        GoogleGenerativeAILanguageModel(client, settings, modelId)

    public fun chat(modelId: ModelId): LanguageModel = languageModel(modelId)
    public fun generativeAI(modelId: ModelId): LanguageModel = languageModel(modelId)

    public fun embedding(modelId: ModelId): EmbeddingModel =
        GoogleGenerativeAIEmbeddingModel(client, settings, modelId.value)
    public fun textEmbedding(modelId: ModelId): EmbeddingModel = embedding(modelId)
    public fun textEmbeddingModel(modelId: ModelId): EmbeddingModel = embedding(modelId)

    public fun image(modelId: ModelId): ImageModel =
        GoogleGenerativeAIImageModel(client, settings, modelId.value)

    public fun video(modelId: ModelId): VideoModel =
        GoogleGenerativeAIVideoModel(client, settings, modelId.value)

    public fun interactions(modelIdOrAgent: ModelId): LanguageModel =
        interactions(GoogleInteractionsModelInput.Model(modelIdOrAgent.value))
    public fun interactions(modelIdOrAgent: GoogleInteractionsModelInput): LanguageModel =
        GoogleInteractionsLanguageModel(client, settings, modelIdOrAgent)
    public fun agentInteraction(agentName: String): LanguageModel =
        interactions(GoogleInteractionsModelInput.Agent(agentName))
    public fun managedAgentInteraction(agentName: String): LanguageModel =
        interactions(GoogleInteractionsModelInput.ManagedAgent(agentName))

    override fun embeddingModel(modelId: String): EmbeddingModel = embedding(ModelId(modelId))
    override fun imageModel(modelId: String): ImageModel = image(ModelId(modelId))
    override fun videoModel(modelId: String): VideoModel = video(ModelId(modelId))
}

// Source/binary-compat alias for the factory below — the constructor now lives on the merged class.
private typealias DefaultGoogleGenerativeAIProvider = GoogleGenerativeAIProvider

/** PascalCase factory — mirrors the OpenAI(...) reference faux-constructor. */
public fun GoogleGenerativeAI(
    client: HttpClient,
    settings: GoogleGenerativeAIProviderSettings = GoogleGenerativeAIProviderSettings(),
): GoogleGenerativeAIProvider = GoogleGenerativeAIProvider(client, settings)


public data class GoogleTools(
    val googleSearch: Tool<JsonElement, JsonElement, Any?> =
        googleProviderTool("google_search", "google.google_search", "Ground responses with Google Search."),
    val enterpriseWebSearch: Tool<JsonElement, JsonElement, Any?> =
        googleProviderTool("enterprise_web_search", "google.enterprise_web_search", "Ground responses with Enterprise Web Search."),
    val googleMaps: Tool<JsonElement, JsonElement, Any?> =
        googleProviderTool("google_maps", "google.google_maps", "Ground responses with Google Maps."),
    val urlContext: Tool<JsonElement, JsonElement, Any?> =
        googleProviderTool("url_context", "google.url_context", "Fetch URL context through Google."),
    val fileSearch: Tool<JsonElement, JsonElement, Any?> =
        googleProviderTool("file_search", "google.file_search", "Use Gemini File Search."),
    val codeExecution: Tool<JsonElement, JsonElement, Any?> =
        googleProviderTool("code_execution", "google.code_execution", "Use Google hosted code execution."),
    val vertexRagStore: Tool<JsonElement, JsonElement, Any?> =
        googleProviderTool("vertex_rag_store", "google.vertex_rag_store", "Use Vertex RAG Store."),
)

public val googleTools: GoogleTools = GoogleTools()

private fun googleProviderTool(
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
