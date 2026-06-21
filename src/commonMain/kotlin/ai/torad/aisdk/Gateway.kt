package ai.torad.aisdk

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Clock


public const val AI_GATEWAY_PROTOCOL_VERSION: String = "0.0.1"
public const val AI_GATEWAY_DEFAULT_BASE_URL: String = "https://ai-gateway.vercel.sh/v3/ai"
public const val GATEWAY_AUTH_METHOD_HEADER: String = "ai-gateway-auth-method"

public enum class GatewayAuthMethod(public val wireValue: String) {
    ApiKey("api-key"),
    Oidc("oidc"),
    ;

    internal companion object {
        internal fun fromHeaders(headers: Map<String, String?>): GatewayAuthMethod? =
            when (headers[GATEWAY_AUTH_METHOD_HEADER]) {
                ApiKey.wireValue -> ApiKey
                Oidc.wireValue -> Oidc
                else -> null
            }
    }
}

public data class GatewayAuthToken(
    val token: String,
    val authMethod: GatewayAuthMethod,
) {
    internal companion object {
        internal suspend fun fromSettings(settings: GatewayProviderSettings): GatewayAuthToken? {
            val key = settings.apiKey ?: settings.environment["AI_GATEWAY_API_KEY"]
            if (key != null) return GatewayAuthToken(key, GatewayAuthMethod.ApiKey)
            // Then a custom token provider, else the OIDC fallback: VERCEL_OIDC_TOKEN from the
            // host environment (the KMP-idiomatic equivalent of upstream's getVercelOidcToken()).
            return settings.authTokenProvider?.invoke()
                ?: settings.environment["VERCEL_OIDC_TOKEN"]?.let { GatewayAuthToken(it, GatewayAuthMethod.Oidc) }
        }
    }
}

public data class GatewayProviderSettings(
    val baseUrl: String = AI_GATEWAY_DEFAULT_BASE_URL,
    val apiKey: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val transport: GatewayTransport = GatewayTransportNotConfigured,
    val metadataCacheRefreshMillis: Long = 5 * 60 * 1000L,
    val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    val authTokenProvider: (suspend () -> GatewayAuthToken?)? = null,
    /**
     * Host-supplied environment. When [apiKey] is null, `AI_GATEWAY_API_KEY` here
     * is used — the KMP-idiomatic equivalent of upstream's process.env lookup
     * (commonMain has no platform `getenv`; the host passes the map).
     */
    val environment: Map<String, String> = emptyMap(),
) {
    internal suspend fun gatewayHeaders(): Map<String, String> {
        val auth = GatewayAuthToken.fromSettings(this)
        val base = linkedMapOf<String, String>()
        auth?.let {
            base["Authorization"] = "Bearer ${it.token}"
            base[GATEWAY_AUTH_METHOD_HEADER] = it.authMethod.wireValue
        }
        base["ai-gateway-protocol-version"] = AI_GATEWAY_PROTOCOL_VERSION
        // Observability headers from the host environment (the KMP equivalent of
        // upstream's getVercelObservabilityHeaders). request-id is runtime-only and omitted.
        val o11y = mapOf(
            "ai-o11y-deployment-id" to "VERCEL_DEPLOYMENT_ID",
            "ai-o11y-environment" to "VERCEL_ENV",
            "ai-o11y-region" to "VERCEL_REGION",
            "ai-o11y-project-id" to "VERCEL_PROJECT_ID",
        )
        for ((header, envVar) in o11y) {
            environment[envVar]?.let { base[header] = it }
        }
        base.putAll(headers)
        return ProviderHeaders.withUserAgentSuffix(base, "ai-sdk/gateway-kotlin")
    }
}

public data class GatewayRequestContext(
    val baseUrl: String,
    val headers: Map<String, String>,
)

public data class GatewayPricing(
    val input: String,
    val output: String,
    val cachedInputTokens: String? = null,
    val cacheCreationInputTokens: String? = null,
)

public data class GatewayLanguageModelSpecification(
    val specificationVersion: String = "v3",
    val provider: String,
    val modelId: String,
)

public enum class GatewayModelType {
    Embedding,
    Image,
    Language,
    Reranking,
    Video,
    ;

    internal companion object {
        internal fun fromWire(value: String?): GatewayModelType? = when (value) {
            "embedding" -> Embedding
            "image" -> Image
            "language" -> Language
            "reranking" -> Reranking
            "video" -> Video
            else -> null
        }
    }
}

public data class GatewayLanguageModelEntry(
    val id: String,
    val name: String,
    val description: String? = null,
    val pricing: GatewayPricing? = null,
    val specification: GatewayLanguageModelSpecification,
    val modelType: GatewayModelType? = null,
)

public typealias GatewayModelEntry = GatewayLanguageModelEntry

public data class GatewayFetchMetadataResponse(
    val models: List<GatewayLanguageModelEntry>,
)

public data class GatewayCreditsResponse(
    val balance: String,
    val totalUsed: String,
)

public enum class GatewaySpendReportGroupBy(public val wireValue: String) {
    Day("day"),
    User("user"),
    Model("model"),
    Tag("tag"),
    Provider("provider"),
    CredentialType("credential_type"),
}

public enum class GatewaySpendReportDatePart(public val wireValue: String) {
    Day("day"),
    Hour("hour"),
}

public enum class GatewayCredentialType(public val wireValue: String) {
    Byok("byok"),
    System("system"),
    ;

    internal companion object {
        internal fun fromWire(value: String?): GatewayCredentialType? = when (value) {
            "byok" -> Byok
            "system" -> System
            else -> null
        }
    }
}

public data class GatewaySpendReportParams(
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

public data class GatewaySpendReportRow(
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

public data class GatewaySpendReportResponse(
    val results: List<GatewaySpendReportRow>,
)

public data class GatewayGenerationInfoParams(
    val id: String,
)

public data class GatewayGenerationInfo(
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

public interface GatewayTransport {
    public suspend fun generateText(
        context: GatewayRequestContext,
        modelId: ModelId,
        params: LanguageModelCallParams,
    ): LanguageModelResult = gatewayTransportMissing()

    public fun streamText(
        context: GatewayRequestContext,
        modelId: ModelId,
        params: LanguageModelCallParams,
    ): Flow<StreamEvent> = flow { throw GatewayTransportNotConfiguredError() }

    public suspend fun embed(
        context: GatewayRequestContext,
        modelId: ModelId,
        params: EmbeddingModelCallParams,
    ): EmbeddingModelResult = gatewayTransportMissing()

    public suspend fun generateImage(
        context: GatewayRequestContext,
        modelId: ModelId,
        params: ImageGenerationParams,
    ): ImageModelResult = gatewayTransportMissing()

    public suspend fun generateVideo(
        context: GatewayRequestContext,
        modelId: ModelId,
        params: VideoGenerationParams,
    ): VideoModelResult = gatewayTransportMissing()

    public suspend fun rerank(
        context: GatewayRequestContext,
        modelId: ModelId,
        params: RerankingParams,
    ): RerankingModelResult = gatewayTransportMissing()

    public suspend fun getAvailableModels(context: GatewayRequestContext): GatewayFetchMetadataResponse =
        gatewayTransportMissing()

    public suspend fun getCredits(context: GatewayRequestContext): GatewayCreditsResponse =
        gatewayTransportMissing()

    public suspend fun getSpendReport(
        context: GatewayRequestContext,
        params: GatewaySpendReportParams,
    ): GatewaySpendReportResponse = gatewayTransportMissing()

    public suspend fun getGenerationInfo(
        context: GatewayRequestContext,
        params: GatewayGenerationInfoParams,
    ): GatewayGenerationInfo = gatewayTransportMissing()

    public companion object {
        internal fun gatewayTransportMissing(): Nothing = throw GatewayTransportNotConfiguredError()
    }
}

public data object GatewayTransportNotConfigured : GatewayTransport

public class GatewayTransportNotConfiguredError :
    AiSdkException("Gateway transport is not configured. Provide GatewayProviderSettings.transport.")

public interface GatewayProvider : Provider {
    public val settings: GatewayProviderSettings
    public val tools: GatewayTools

    public operator fun invoke(modelId: ModelId): LanguageModel = languageModel(modelId.value)
    public fun chat(modelId: ModelId): LanguageModel = languageModel(modelId.value)
    public fun embedding(modelId: ModelId): EmbeddingModel = embeddingModel(modelId.value)
    public fun image(modelId: ModelId): ImageModel = imageModel(modelId.value)
    public fun video(modelId: ModelId): VideoModel = videoModel(modelId.value)
    public fun reranking(modelId: ModelId): RerankingModel = rerankingModel(modelId.value)
    public fun textEmbeddingModel(modelId: ModelId): EmbeddingModel = embeddingModel(modelId.value)

    public suspend fun getAvailableModels(): GatewayFetchMetadataResponse
    public suspend fun getCredits(): GatewayCreditsResponse
    public suspend fun getSpendReport(params: GatewaySpendReportParams): GatewaySpendReportResponse
    public suspend fun getGenerationInfo(params: GatewayGenerationInfoParams): GatewayGenerationInfo
}

public fun GatewayProvider(settings: GatewayProviderSettings = GatewayProviderSettings()): GatewayProvider =
    DefaultGatewayProvider(settings)

public fun Gateway(settings: GatewayProviderSettings = GatewayProviderSettings()): GatewayProvider =
    GatewayProvider(settings)

public val gateway: GatewayProvider = GatewayProvider()

private class DefaultGatewayProvider(
    override val settings: GatewayProviderSettings,
) : GatewayProvider {
    override val providerId: String = "gateway"
    override val tools: GatewayTools = GatewayTools()

    private data class MetadataCache(val response: GatewayFetchMetadataResponse, val fetchedAtMillis: Long)

    private val metadataMutex = Mutex()
    private var metadataCache: MetadataCache? = null

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

    override suspend fun getAvailableModels(): GatewayFetchMetadataResponse = metadataMutex.withLock {
        val now = settings.nowMillis()
        metadataCache
            ?.takeIf { settings.metadataCacheRefreshMillis > 0 && now - it.fetchedAtMillis <= settings.metadataCacheRefreshMillis }
            ?.response
            ?: settings.transport.getAvailableModels(requestContext()).also { metadataCache = MetadataCache(it, now) }
    }

    override suspend fun getCredits(): GatewayCreditsResponse =
        settings.transport.getCredits(requestContext())

    override suspend fun getSpendReport(params: GatewaySpendReportParams): GatewaySpendReportResponse =
        settings.transport.getSpendReport(requestContext(), params)

    override suspend fun getGenerationInfo(params: GatewayGenerationInfoParams): GatewayGenerationInfo =
        settings.transport.getGenerationInfo(requestContext(), params)

    private suspend fun requestContext(): GatewayRequestContext =
        GatewayRequestContext(
            baseUrl = UrlOps.withoutTrailingSlash(settings.baseUrl) ?: AI_GATEWAY_DEFAULT_BASE_URL,
            headers = settings.gatewayHeaders(),
        )
}

private class GatewayLanguageModel(
    override val modelId: String,
    private val transport: GatewayTransport,
    private val context: suspend () -> GatewayRequestContext,
) : LanguageModel {
    override val provider: String = "gateway"

    override suspend fun generate(params: LanguageModelCallParams): LanguageModelResult =
        transport.generateText(context(), ModelId(modelId), params)

    override fun stream(params: LanguageModelCallParams): Flow<StreamEvent> = flow {
        emitAll(transport.streamText(context(), ModelId(modelId), params))
    }
}

private class GatewayEmbeddingModel(
    override val modelId: String,
    private val transport: GatewayTransport,
    private val context: suspend () -> GatewayRequestContext,
) : EmbeddingModel {
    override val provider: String = "gateway"

    override suspend fun embed(params: EmbeddingModelCallParams): EmbeddingModelResult =
        transport.embed(context(), ModelId(modelId), params)
}

private class GatewayImageModel(
    override val modelId: String,
    private val transport: GatewayTransport,
    private val context: suspend () -> GatewayRequestContext,
) : ImageModel {
    override val provider: String = "gateway"

    override suspend fun generate(params: ImageGenerationParams): ImageModelResult =
        transport.generateImage(context(), ModelId(modelId), params)
}

private class GatewayVideoModel(
    override val modelId: String,
    private val transport: GatewayTransport,
    private val context: suspend () -> GatewayRequestContext,
) : VideoModel {
    override val provider: String = "gateway"

    override suspend fun generate(params: VideoGenerationParams): VideoModelResult =
        transport.generateVideo(context(), ModelId(modelId), params)
}

private class GatewayRerankingModel(
    override val modelId: String,
    private val transport: GatewayTransport,
    private val context: suspend () -> GatewayRequestContext,
) : RerankingModel {
    override val provider: String = "gateway"

    override suspend fun rerank(params: RerankingParams): RerankingModelResult =
        transport.rerank(context(), ModelId(modelId), params)
}

public data class GatewayTools(
    val parallelSearch: Tool<JsonElement, JsonElement, Any?> = ProviderExecutedTool(
        name = "parallelSearch",
        description = "Search the web using Parallel AI's Search API for LLM-optimized excerpts.",
        inputSerializer = JsonElement.serializer(),
        outputSerializer = JsonElement.serializer(),
    ),
    val perplexitySearch: Tool<JsonElement, JsonElement, Any?> = ProviderExecutedTool(
        name = "perplexitySearch",
        description = "Search the web using Perplexity's Search API for real-time information.",
        inputSerializer = JsonElement.serializer(),
        outputSerializer = JsonElement.serializer(),
    ),
)

public open class GatewayError(
    message: String,
    public val statusCode: Int = 500,
    public val type: String = "gateway_error",
    public val generationId: String? = null,
    cause: Throwable? = null,
    public val isRetryable: Boolean = statusCode == 408 || statusCode == 409 || statusCode == 429 || statusCode >= 500,
) : AiSdkException(if (generationId == null) message else "$message [$generationId]", cause) {
    internal companion object {
        internal fun fromResponse(statusCode: Int, raw: String): GatewayError {
            val parsed = runCatching { aiSdkJson.parseToJsonElement(raw).jsonObject }.getOrNull()
            val error = parsed?.get("error")?.jsonObject
            val type = error?.get("type")?.jsonPrimitive?.contentOrNull
            val message = error?.get("message")?.jsonPrimitive?.contentOrNull ?: raw.ifBlank { "Gateway request failed" }
            val generationId = parsed?.get("generationId")?.jsonPrimitive?.contentOrNull
            return when (type) {
                "authentication_error" -> GatewayAuthenticationError(message, statusCode, generationId)
                "invalid_request_error" -> GatewayInvalidRequestError(message, statusCode, generationId)
                "rate_limit_exceeded" -> GatewayRateLimitError(message, statusCode, generationId)
                "model_not_found" -> GatewayModelNotFoundError(message, statusCode, generationId = generationId)
                "internal_server_error" -> GatewayInternalServerError(message, statusCode, generationId)
                else -> GatewayResponseError(
                    message = message,
                    statusCode = statusCode,
                    response = parsed,
                    generationId = generationId,
                )
            }
        }
    }
}

public class GatewayAuthenticationError(
    message: String = "Authentication failed",
    statusCode: Int = 401,
    generationId: String? = null,
    cause: Throwable? = null,
) : GatewayError(message, statusCode, "authentication_error", generationId, cause)

public class GatewayInvalidRequestError(
    message: String = "Invalid request",
    statusCode: Int = 400,
    generationId: String? = null,
    cause: Throwable? = null,
) : GatewayError(message, statusCode, "invalid_request_error", generationId, cause)

public class GatewayRateLimitError(
    message: String = "Rate limit exceeded",
    statusCode: Int = 429,
    generationId: String? = null,
    cause: Throwable? = null,
) : GatewayError(message, statusCode, "rate_limit_exceeded", generationId, cause)

public class GatewayModelNotFoundError(
    message: String = "Model not found",
    statusCode: Int = 404,
    public val modelId: String? = null,
    generationId: String? = null,
    cause: Throwable? = null,
) : GatewayError(message, statusCode, "model_not_found", generationId, cause)

public class GatewayInternalServerError(
    message: String = "Internal server error",
    statusCode: Int = 500,
    generationId: String? = null,
    cause: Throwable? = null,
) : GatewayError(message, statusCode, "internal_server_error", generationId, cause)

public class GatewayResponseError(
    message: String = "Invalid response from Gateway",
    statusCode: Int = 502,
    public val response: JsonElement? = null,
    generationId: String? = null,
    cause: Throwable? = null,
) : GatewayError(message, statusCode, "response_error", generationId, cause)

public class GatewayTimeoutError(
    message: String = "Gateway request timed out",
    statusCode: Int = 408,
    generationId: String? = null,
    cause: Throwable? = null,
) : GatewayError(message, statusCode, "timeout_error", generationId, cause)
