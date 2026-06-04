package ai.torad.aisdk

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonElement
import kotlin.time.Clock

typealias GatewayModelId = String
typealias GatewayEmbeddingModelId = String
typealias GatewayImageModelId = String
typealias GatewayRerankingModelId = String
typealias GatewayVideoModelId = String

const val AI_GATEWAY_PROTOCOL_VERSION: String = "0.0.1"
const val AI_GATEWAY_DEFAULT_BASE_URL: String = "https://ai-gateway.vercel.sh/v3/ai"
const val GATEWAY_AUTH_METHOD_HEADER: String = "ai-gateway-auth-method"

enum class GatewayAuthMethod(val wireValue: String) {
    ApiKey("api-key"),
    Oidc("oidc"),
}

data class GatewayAuthToken(
    val token: String,
    val authMethod: GatewayAuthMethod,
)

data class GatewayProviderSettings(
    val baseUrl: String = AI_GATEWAY_DEFAULT_BASE_URL,
    val apiKey: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val transport: GatewayTransport = GatewayTransportNotConfigured,
    val metadataCacheRefreshMillis: Long = 5 * 60 * 1000L,
    val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    val authTokenProvider: (suspend () -> GatewayAuthToken?)? = null,
)

data class GatewayRequestContext(
    val baseUrl: String,
    val headers: Map<String, String>,
)

data class GatewayPricing(
    val input: String,
    val output: String,
    val cachedInputTokens: String? = null,
    val cacheCreationInputTokens: String? = null,
)

data class GatewayLanguageModelSpecification(
    val specificationVersion: String = "v3",
    val provider: String,
    val modelId: String,
)

enum class GatewayModelType {
    Embedding,
    Image,
    Language,
    Reranking,
    Video,
}

data class GatewayLanguageModelEntry(
    val id: String,
    val name: String,
    val description: String? = null,
    val pricing: GatewayPricing? = null,
    val specification: GatewayLanguageModelSpecification,
    val modelType: GatewayModelType? = null,
)

typealias GatewayModelEntry = GatewayLanguageModelEntry

data class GatewayFetchMetadataResponse(
    val models: List<GatewayLanguageModelEntry>,
)

data class GatewayCreditsResponse(
    val balance: String,
    val totalUsed: String,
)

enum class GatewaySpendReportGroupBy(val wireValue: String) {
    Day("day"),
    User("user"),
    Model("model"),
    Tag("tag"),
    Provider("provider"),
    CredentialType("credential_type"),
}

enum class GatewaySpendReportDatePart(val wireValue: String) {
    Day("day"),
    Hour("hour"),
}

enum class GatewayCredentialType(val wireValue: String) {
    Byok("byok"),
    System("system"),
}

data class GatewaySpendReportParams(
    val startDate: String,
    val endDate: String,
    val groupBy: GatewaySpendReportGroupBy? = null,
    val datePart: GatewaySpendReportDatePart? = null,
    val userId: String? = null,
    val model: String? = null,
    val provider: String? = null,
    val credentialType: GatewayCredentialType? = null,
    val tags: List<String> = emptyList(),
)

data class GatewaySpendReportRow(
    val day: String? = null,
    val hour: String? = null,
    val user: String? = null,
    val model: String? = null,
    val tag: String? = null,
    val provider: String? = null,
    val credentialType: GatewayCredentialType? = null,
    val totalCost: Double,
    val marketCost: Double? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val cachedInputTokens: Int? = null,
    val cacheCreationInputTokens: Int? = null,
    val reasoningTokens: Int? = null,
    val requestCount: Int? = null,
)

data class GatewaySpendReportResponse(
    val results: List<GatewaySpendReportRow>,
)

data class GatewayGenerationInfoParams(
    val id: String,
)

data class GatewayGenerationInfo(
    val id: String,
    val totalCost: Double,
    val upstreamInferenceCost: Double,
    val usage: Double,
    val createdAt: String,
    val model: String,
    val isByok: Boolean,
    val providerName: String,
    val streamed: Boolean,
    val finishReason: String,
    val latency: Int,
    val generationTime: Int,
    val promptTokens: Int,
    val completionTokens: Int,
    val reasoningTokens: Int,
    val cachedTokens: Int,
    val cacheCreationTokens: Int,
    val billableWebSearchCalls: Int,
)

interface GatewayTransport {
    suspend fun generateText(
        context: GatewayRequestContext,
        modelId: GatewayModelId,
        params: LanguageModelCallParams,
    ): LanguageModelResult = gatewayTransportMissing()

    fun streamText(
        context: GatewayRequestContext,
        modelId: GatewayModelId,
        params: LanguageModelCallParams,
    ): Flow<StreamEvent> = flow { throw GatewayTransportNotConfiguredError() }

    suspend fun embed(
        context: GatewayRequestContext,
        modelId: GatewayEmbeddingModelId,
        params: EmbeddingModelCallParams,
    ): EmbeddingModelResult = gatewayTransportMissing()

    suspend fun generateImage(
        context: GatewayRequestContext,
        modelId: GatewayImageModelId,
        params: ImageGenerationParams,
    ): ImageModelResult = gatewayTransportMissing()

    suspend fun generateVideo(
        context: GatewayRequestContext,
        modelId: GatewayVideoModelId,
        params: VideoGenerationParams,
    ): VideoModelResult = gatewayTransportMissing()

    suspend fun rerank(
        context: GatewayRequestContext,
        modelId: GatewayRerankingModelId,
        params: RerankingParams,
    ): RerankingModelResult = gatewayTransportMissing()

    suspend fun getAvailableModels(context: GatewayRequestContext): GatewayFetchMetadataResponse =
        gatewayTransportMissing()

    suspend fun getCredits(context: GatewayRequestContext): GatewayCreditsResponse =
        gatewayTransportMissing()

    suspend fun getSpendReport(
        context: GatewayRequestContext,
        params: GatewaySpendReportParams,
    ): GatewaySpendReportResponse = gatewayTransportMissing()

    suspend fun getGenerationInfo(
        context: GatewayRequestContext,
        params: GatewayGenerationInfoParams,
    ): GatewayGenerationInfo = gatewayTransportMissing()
}

data object GatewayTransportNotConfigured : GatewayTransport

class GatewayTransportNotConfiguredError :
    AiSdkException("Gateway transport is not configured. Provide GatewayProviderSettings.transport.")

private fun gatewayTransportMissing(): Nothing = throw GatewayTransportNotConfiguredError()

interface GatewayProvider : Provider {
    val settings: GatewayProviderSettings
    val tools: GatewayTools

    operator fun invoke(modelId: GatewayModelId): LanguageModel = languageModel(modelId)
    fun chat(modelId: GatewayModelId): LanguageModel = languageModel(modelId)
    fun embedding(modelId: GatewayEmbeddingModelId): EmbeddingModel = embeddingModel(modelId)
    fun image(modelId: GatewayImageModelId): ImageModel = imageModel(modelId)
    fun video(modelId: GatewayVideoModelId): VideoModel = videoModel(modelId)
    fun reranking(modelId: GatewayRerankingModelId): RerankingModel = rerankingModel(modelId)
    fun textEmbeddingModel(modelId: GatewayEmbeddingModelId): EmbeddingModel = embeddingModel(modelId)

    suspend fun getAvailableModels(): GatewayFetchMetadataResponse
    suspend fun getCredits(): GatewayCreditsResponse
    suspend fun getSpendReport(params: GatewaySpendReportParams): GatewaySpendReportResponse
    suspend fun getGenerationInfo(params: GatewayGenerationInfoParams): GatewayGenerationInfo
}

fun createGatewayProvider(settings: GatewayProviderSettings = GatewayProviderSettings()): GatewayProvider =
    DefaultGatewayProvider(settings)

fun createGateway(settings: GatewayProviderSettings = GatewayProviderSettings()): GatewayProvider =
    createGatewayProvider(settings)

val gateway: GatewayProvider = createGatewayProvider()

private class DefaultGatewayProvider(
    override val settings: GatewayProviderSettings,
) : GatewayProvider {
    override val providerId: String = "gateway"
    override val tools: GatewayTools = GatewayTools()
    private var pendingMetadata: GatewayFetchMetadataResponse? = null
    private var lastFetchTime: Long = Long.MIN_VALUE

    override fun languageModel(modelId: String): LanguageModel =
        GatewayLanguageModel(modelId, settings.transport, ::requestContext)

    override fun embeddingModel(modelId: String): EmbeddingModel =
        GatewayEmbeddingModel(modelId, settings.transport, ::requestContext)

    override fun imageModel(modelId: String): ImageModel =
        GatewayImageModel(modelId, settings.transport, ::requestContext)

    override fun videoModel(modelId: String): VideoModel =
        GatewayVideoModel(modelId, settings.transport, ::requestContext)

    override fun rerankingModel(modelId: String): RerankingModel =
        GatewayRerankingModel(modelId, settings.transport, ::requestContext)

    override suspend fun getAvailableModels(): GatewayFetchMetadataResponse {
        val now = settings.nowMillis()
        val cached = pendingMetadata
        if (cached != null &&
            settings.metadataCacheRefreshMillis > 0 &&
            now - lastFetchTime <= settings.metadataCacheRefreshMillis
        ) {
            return cached
        }
        return settings.transport.getAvailableModels(requestContext()).also {
            pendingMetadata = it
            lastFetchTime = now
        }
    }

    override suspend fun getCredits(): GatewayCreditsResponse =
        settings.transport.getCredits(requestContext())

    override suspend fun getSpendReport(params: GatewaySpendReportParams): GatewaySpendReportResponse =
        settings.transport.getSpendReport(requestContext(), params)

    override suspend fun getGenerationInfo(params: GatewayGenerationInfoParams): GatewayGenerationInfo =
        settings.transport.getGenerationInfo(requestContext(), params)

    private suspend fun requestContext(): GatewayRequestContext =
        GatewayRequestContext(
            baseUrl = withoutTrailingSlash(settings.baseUrl) ?: AI_GATEWAY_DEFAULT_BASE_URL,
            headers = gatewayHeaders(settings),
        )
}

private class GatewayLanguageModel(
    override val modelId: GatewayModelId,
    private val transport: GatewayTransport,
    private val context: suspend () -> GatewayRequestContext,
) : LanguageModel {
    override val provider: String = "gateway"

    override suspend fun generate(params: LanguageModelCallParams): LanguageModelResult =
        transport.generateText(context(), modelId, params)

    override fun stream(params: LanguageModelCallParams): Flow<StreamEvent> = flow {
        emitAll(transport.streamText(context(), modelId, params))
    }
}

private class GatewayEmbeddingModel(
    override val modelId: GatewayEmbeddingModelId,
    private val transport: GatewayTransport,
    private val context: suspend () -> GatewayRequestContext,
) : EmbeddingModel {
    override val provider: String = "gateway"

    override suspend fun embed(params: EmbeddingModelCallParams): EmbeddingModelResult =
        transport.embed(context(), modelId, params)
}

private class GatewayImageModel(
    override val modelId: GatewayImageModelId,
    private val transport: GatewayTransport,
    private val context: suspend () -> GatewayRequestContext,
) : ImageModel {
    override val provider: String = "gateway"

    override suspend fun generate(params: ImageGenerationParams): ImageModelResult =
        transport.generateImage(context(), modelId, params)
}

private class GatewayVideoModel(
    override val modelId: GatewayVideoModelId,
    private val transport: GatewayTransport,
    private val context: suspend () -> GatewayRequestContext,
) : VideoModel {
    override val provider: String = "gateway"

    override suspend fun generate(params: VideoGenerationParams): VideoModelResult =
        transport.generateVideo(context(), modelId, params)
}

private class GatewayRerankingModel(
    override val modelId: GatewayRerankingModelId,
    private val transport: GatewayTransport,
    private val context: suspend () -> GatewayRequestContext,
) : RerankingModel {
    override val provider: String = "gateway"

    override suspend fun rerank(params: RerankingParams): RerankingModelResult =
        transport.rerank(context(), modelId, params)
}

data class GatewayTools(
    val parallelSearch: Tool<JsonElement, JsonElement, Any?> = providerExecutedTool(
        name = "parallelSearch",
        description = "Search the web using Parallel AI's Search API for LLM-optimized excerpts.",
        inputSerializer = JsonElement.serializer(),
        outputSerializer = JsonElement.serializer(),
    ),
    val perplexitySearch: Tool<JsonElement, JsonElement, Any?> = providerExecutedTool(
        name = "perplexitySearch",
        description = "Search the web using Perplexity's Search API for real-time information.",
        inputSerializer = JsonElement.serializer(),
        outputSerializer = JsonElement.serializer(),
    ),
)

suspend fun getGatewayAuthToken(settings: GatewayProviderSettings): GatewayAuthToken? =
    settings.apiKey?.let { GatewayAuthToken(it, GatewayAuthMethod.ApiKey) } ?: settings.authTokenProvider?.invoke()

suspend fun gatewayHeaders(settings: GatewayProviderSettings): Map<String, String> {
    val auth = getGatewayAuthToken(settings)
    val base = linkedMapOf<String, String>()
    auth?.let {
        base["Authorization"] = "Bearer ${it.token}"
        base[GATEWAY_AUTH_METHOD_HEADER] = it.authMethod.wireValue
    }
    base["ai-gateway-protocol-version"] = AI_GATEWAY_PROTOCOL_VERSION
    base.putAll(settings.headers)
    return withUserAgentSuffix(base, "ai-sdk/gateway-kotlin")
}

fun parseGatewayAuthMethod(headers: Map<String, String?>): GatewayAuthMethod? =
    when (headers[GATEWAY_AUTH_METHOD_HEADER]) {
        GatewayAuthMethod.ApiKey.wireValue -> GatewayAuthMethod.ApiKey
        GatewayAuthMethod.Oidc.wireValue -> GatewayAuthMethod.Oidc
        else -> null
    }

open class GatewayError(
    message: String,
    val statusCode: Int = 500,
    val type: String = "gateway_error",
    val generationId: String? = null,
    cause: Throwable? = null,
    val isRetryable: Boolean = statusCode == 408 || statusCode == 409 || statusCode == 429 || statusCode >= 500,
) : AiSdkException(if (generationId == null) message else "$message [$generationId]", cause)

class GatewayAuthenticationError(
    message: String = "Authentication failed",
    statusCode: Int = 401,
    generationId: String? = null,
    cause: Throwable? = null,
) : GatewayError(message, statusCode, "authentication_error", generationId, cause)

class GatewayInvalidRequestError(
    message: String = "Invalid request",
    statusCode: Int = 400,
    generationId: String? = null,
    cause: Throwable? = null,
) : GatewayError(message, statusCode, "invalid_request_error", generationId, cause)

class GatewayRateLimitError(
    message: String = "Rate limit exceeded",
    statusCode: Int = 429,
    generationId: String? = null,
    cause: Throwable? = null,
) : GatewayError(message, statusCode, "rate_limit_exceeded", generationId, cause)

class GatewayModelNotFoundError(
    message: String = "Model not found",
    statusCode: Int = 404,
    val modelId: String? = null,
    generationId: String? = null,
    cause: Throwable? = null,
) : GatewayError(message, statusCode, "model_not_found", generationId, cause)

class GatewayInternalServerError(
    message: String = "Internal server error",
    statusCode: Int = 500,
    generationId: String? = null,
    cause: Throwable? = null,
) : GatewayError(message, statusCode, "internal_server_error", generationId, cause)

class GatewayResponseError(
    message: String = "Invalid response from Gateway",
    statusCode: Int = 502,
    val response: JsonElement? = null,
    generationId: String? = null,
    cause: Throwable? = null,
) : GatewayError(message, statusCode, "response_error", generationId, cause)

class GatewayTimeoutError(
    message: String = "Gateway request timed out",
    statusCode: Int = 408,
    generationId: String? = null,
    cause: Throwable? = null,
) : GatewayError(message, statusCode, "timeout_error", generationId, cause)
