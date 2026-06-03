package ai.torad.aisdk

import io.ktor.client.HttpClient
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

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
    operator fun invoke(modelId: AnthropicMessagesModelId): LanguageModel = languageModel(modelId)
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
}

fun createVertexAnthropic(
    client: HttpClient,
    settings: GoogleVertexAnthropicProviderSettings = GoogleVertexAnthropicProviderSettings(),
): GoogleVertexAnthropicProvider = DefaultVertexAnthropicProvider(settings)

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
    override val settings: GoogleVertexAnthropicProviderSettings,
) : GoogleVertexAnthropicProvider {
    override val providerId: String = "google-vertex-anthropic"
    override fun languageModel(modelId: String): LanguageModel =
        GoogleVertexSubproviderPlaceholderLanguageModel(modelId, "google.vertex.anthropic")
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
            name = "google-vertex-xai",
            baseUrl = googleVertexOpenAIBaseURL(settings),
            headers = googleVertexHeaders(settings),
            userAgentSuffix = "ai-sdk/google-vertex/$GOOGLE_VERTEX_VERSION",
        ),
    )
    override fun languageModel(modelId: String): LanguageModel = delegate.languageModel(modelId)
}

private class GoogleVertexSubproviderPlaceholderLanguageModel(
    override val modelId: String,
    override val provider: String,
) : LanguageModel {
    override suspend fun generate(params: LanguageModelCallParams): LanguageModelResult =
        throw AiSdkException("$provider is surfaced but its native request adapter is not implemented in this facade yet.")
    override fun stream(params: LanguageModelCallParams): Flow<StreamEvent> = flow {
        emit(StreamEvent.Error("$provider is surfaced but its native request adapter is not implemented in this facade yet."))
    }
}

private fun googleVertexPublisherBaseURL(settings: GoogleVertexProviderSettings): String =
    settings.baseURL?.trimEnd('/')
        ?: "https://${settings.location}-aiplatform.googleapis.com/v1/projects/${googleVertexProject(settings)}/locations/${settings.location}/publishers/google"

private fun googleVertexOpenAIBaseURL(settings: GoogleVertexProviderSettings): String =
    settings.baseURL?.trimEnd('/')
        ?: "https://${settings.location}-aiplatform.googleapis.com/v1/projects/${googleVertexProject(settings)}/locations/${settings.location}/endpoints/openapi"

private fun googleVertexProject(settings: GoogleVertexProviderSettings): String =
    settings.project ?: throw AiSdkException("Google Vertex project is required.")

private fun googleVertexHeaders(settings: GoogleVertexProviderSettings): Map<String, String> {
    val headers = linkedMapOf<String, String>()
    settings.accessToken?.takeIf { it.isNotBlank() }?.let { headers[HttpHeaders.Authorization] = "Bearer $it" }
    headers[HttpHeaders.UserAgent] = "ai-sdk/google-vertex/$GOOGLE_VERTEX_VERSION"
    headers.putAll(settings.headers)
    return headers
}
