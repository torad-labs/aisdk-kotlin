@file:OptIn(LowLevelLanguageModelApi::class)

package ai.torad.aisdk

import dev.drewhamilton.poko.Poko
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlin.time.Clock


public const val AI_GATEWAY_PROTOCOL_VERSION: String = "0.0.1"
public const val AI_GATEWAY_DEFAULT_BASE_URL: String = "https://ai-gateway.vercel.sh/v3/ai"
public const val GATEWAY_AUTH_METHOD_HEADER: String = "ai-gateway-auth-method"

public enum class GatewayAuthMethod(public val wireValue: String) {
    ApiKey("api-key"),
    Oidc("oidc"),
}

public data class GatewayAuthToken(
    val token: String,
    val authMethod: GatewayAuthMethod,
) {
    internal companion object {
        internal suspend fun fromSettings(settings: GatewayProviderSettings): GatewayAuthToken? {
            val key = (settings.apiKey ?: settings.environment["AI_GATEWAY_API_KEY"])?.takeIf { it.isNotBlank() }
            if (key != null) return GatewayAuthToken(key, GatewayAuthMethod.ApiKey)
            // Then a custom token provider, else the OIDC fallback: VERCEL_OIDC_TOKEN from the
            // host environment (the KMP-idiomatic equivalent of upstream's getVercelOidcToken()).
            return settings.authTokenProvider?.invoke()
                ?: settings.environment["VERCEL_OIDC_TOKEN"]?.let { GatewayAuthToken(it, GatewayAuthMethod.Oidc) }
        }
    }
}

public class GatewayProviderSettings internal constructor(
    public val baseUrl: String = AI_GATEWAY_DEFAULT_BASE_URL,
    public val apiKey: String? = null,
    public val headers: Map<String, String> = emptyMap(),
    public val transport: GatewayTransport = GatewayTransportNotConfigured,
    public val metadataCacheRefreshMillis: Long = 5 * 60 * 1000L,
    public val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    public val authTokenProvider: (suspend () -> GatewayAuthToken?)? = null,
    /**
     * Host-supplied environment. When [apiKey] is null, `AI_GATEWAY_API_KEY` here
     * is used — the KMP-idiomatic equivalent of upstream's process.env lookup
     * (commonMain has no platform `getenv`; the host passes the map).
     */
    public val environment: Map<String, String> = emptyMap(),
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

public class GatewayProviderSettingsBuilder {
    private var baseUrl: String = AI_GATEWAY_DEFAULT_BASE_URL
    private var apiKey: String? = null
    private var headers: Map<String, String> = emptyMap()
    private var transport: GatewayTransport = GatewayTransportNotConfigured
    private var metadataCacheRefreshMillis: Long = 5 * 60 * 1000L
    private var nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() }
    private var authTokenProvider: (suspend () -> GatewayAuthToken?)? = null
    private var environment: Map<String, String> = emptyMap()

    public fun baseUrl(value: String): GatewayProviderSettingsBuilder {
        baseUrl = value
        return this
    }

    public fun apiKey(value: String?): GatewayProviderSettingsBuilder {
        apiKey = value
        return this
    }

    public fun headers(value: Map<String, String>): GatewayProviderSettingsBuilder {
        headers = value
        return this
    }

    public fun transport(value: GatewayTransport): GatewayProviderSettingsBuilder {
        transport = value
        return this
    }

    public fun metadataCacheRefreshMillis(value: Long): GatewayProviderSettingsBuilder {
        metadataCacheRefreshMillis = value
        return this
    }

    public fun nowMillis(value: () -> Long): GatewayProviderSettingsBuilder {
        nowMillis = value
        return this
    }

    public fun authTokenProvider(value: (suspend () -> GatewayAuthToken?)?): GatewayProviderSettingsBuilder {
        authTokenProvider = value
        return this
    }

    public fun environment(value: Map<String, String>): GatewayProviderSettingsBuilder {
        environment = value
        return this
    }

    public fun build(): GatewayProviderSettings =
        GatewayProviderSettings(
            baseUrl = baseUrl,
            apiKey = apiKey,
            headers = headers,
            transport = transport,
            metadataCacheRefreshMillis = metadataCacheRefreshMillis,
            nowMillis = nowMillis,
            authTokenProvider = authTokenProvider,
            environment = environment,
        )
}

public fun GatewayProviderSettings(
    block: GatewayProviderSettingsBuilder.() -> Unit = {},
): GatewayProviderSettings =
    GatewayProviderSettingsBuilder().apply(block).build()

@Poko
public class GatewayRequestContext(
    public val baseUrl: String,
    public val headers: Map<String, String>,
)

@Poko
public class GatewayPricing(
    public val input: String,
    public val output: String,
    public val cachedInputTokens: String? = null,
    public val cacheCreationInputTokens: String? = null,
)

@Poko
public class GatewayLanguageModelSpecification(
    public val specificationVersion: String = "v3",
    public val provider: String,
    public val modelId: String,
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

@Poko
public class GatewayLanguageModelEntry(
    public val id: String,
    public val name: String,
    public val description: String? = null,
    public val pricing: GatewayPricing? = null,
    public val specification: GatewayLanguageModelSpecification,
    public val modelType: GatewayModelType? = null,
)

public typealias GatewayModelEntry = GatewayLanguageModelEntry

@Poko
public class GatewayFetchMetadataResponse(
    public val models: List<GatewayLanguageModelEntry>,
)

@Poko
public class GatewayCreditsResponse(
    public val balance: String,
    public val totalUsed: String,
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

@Poko
public class GatewaySpendReportParams internal constructor(
    public val startDate: String,
    public val endDate: String,
    public val groupBy: GatewaySpendReportGroupBy? = null,
    public val datePart: GatewaySpendReportDatePart? = null,
    public val userId: String? = null,
    public val model: String? = null,
    public val provider: String? = null,
    public val credentialType: GatewayCredentialType? = null,
    public val tags: List<String> = emptyList(),
)

public class GatewaySpendReportParamsBuilder {
    private var startDate: String? = null
    private var endDate: String? = null
    private var groupBy: GatewaySpendReportGroupBy? = null
    private var datePart: GatewaySpendReportDatePart? = null
    private var userId: String? = null
    private var model: String? = null
    private var provider: String? = null
    private var credentialType: GatewayCredentialType? = null
    private var tags: List<String> = emptyList()

    public fun startDate(value: String): GatewaySpendReportParamsBuilder {
        startDate = value
        return this
    }

    public fun endDate(value: String): GatewaySpendReportParamsBuilder {
        endDate = value
        return this
    }

    public fun groupBy(value: GatewaySpendReportGroupBy?): GatewaySpendReportParamsBuilder {
        groupBy = value
        return this
    }

    public fun datePart(value: GatewaySpendReportDatePart?): GatewaySpendReportParamsBuilder {
        datePart = value
        return this
    }

    public fun userId(value: String?): GatewaySpendReportParamsBuilder {
        userId = value
        return this
    }

    public fun model(value: String?): GatewaySpendReportParamsBuilder {
        model = value
        return this
    }

    public fun provider(value: String?): GatewaySpendReportParamsBuilder {
        provider = value
        return this
    }

    public fun credentialType(value: GatewayCredentialType?): GatewaySpendReportParamsBuilder {
        credentialType = value
        return this
    }

    public fun tags(value: List<String>): GatewaySpendReportParamsBuilder {
        tags = value
        return this
    }

    public fun build(): GatewaySpendReportParams =
        GatewaySpendReportParams(
            startDate = requireNotNull(startDate) { "GatewaySpendReportParams.startDate is required" },
            endDate = requireNotNull(endDate) { "GatewaySpendReportParams.endDate is required" },
            groupBy = groupBy,
            datePart = datePart,
            userId = userId,
            model = model,
            provider = provider,
            credentialType = credentialType,
            tags = tags,
        )
}

public fun GatewaySpendReportParams(
    block: GatewaySpendReportParamsBuilder.() -> Unit = {},
): GatewaySpendReportParams =
    GatewaySpendReportParamsBuilder().apply(block).build()

@Poko
public class GatewaySpendReportRow(
    public val day: String? = null,
    public val hour: String? = null,
    public val user: String? = null,
    public val model: String? = null,
    public val tag: String? = null,
    public val provider: String? = null,
    public val credentialType: GatewayCredentialType? = null,
    public val totalCost: Double,
    public val marketCost: Double? = null,
    public val inputTokens: Int? = null,
    public val outputTokens: Int? = null,
    public val cachedInputTokens: Int? = null,
    public val cacheCreationInputTokens: Int? = null,
    public val reasoningTokens: Int? = null,
    public val requestCount: Int? = null,
)

@Poko
public class GatewaySpendReportResponse(
    public val results: List<GatewaySpendReportRow>,
)

@Poko
public class GatewayGenerationInfoParams internal constructor(
    public val id: String,
)

public class GatewayGenerationInfoParamsBuilder {
    private var id: String? = null

    public fun id(value: String): GatewayGenerationInfoParamsBuilder {
        id = value
        return this
    }

    public fun build(): GatewayGenerationInfoParams =
        GatewayGenerationInfoParams(
            id = requireNotNull(id) { "GatewayGenerationInfoParams.id is required" },
        )
}

public fun GatewayGenerationInfoParams(
    block: GatewayGenerationInfoParamsBuilder.() -> Unit = {},
): GatewayGenerationInfoParams =
    GatewayGenerationInfoParamsBuilder().apply(block).build()

@Poko
public class GatewayGenerationInfo(
    public val id: String,
    public val totalCost: Double,
    public val upstreamInferenceCost: Double,
    public val usage: Double,
    public val createdAt: String,
    public val model: String,
    public val isByok: Boolean,
    public val providerName: String,
    public val streamed: Boolean,
    public val finishReason: String,
    public val latency: Int,
    public val generationTime: Int,
    public val promptTokens: Int,
    public val completionTokens: Int,
    public val reasoningTokens: Int,
    public val cachedTokens: Int,
    public val cacheCreationTokens: Int,
    public val billableWebSearchCalls: Int,
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

@Poko
public class GatewayTools(
    public val parallelSearch: Tool<JsonElement, JsonElement, Any?> = ProviderExecutedTool(
        name = "parallelSearch",
        description = "Search the web using Parallel AI's Search API for LLM-optimized excerpts.",
        inputSerializer = JsonElement.serializer(),
        outputSerializer = JsonElement.serializer(),
    ),
    public val perplexitySearch: Tool<JsonElement, JsonElement, Any?> = ProviderExecutedTool(
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
            val error = (parsed?.get("error") as? JsonObject)
            val type = (error?.get("type") as? JsonPrimitive)?.contentOrNull
            val message = (error?.get("message") as? JsonPrimitive)?.contentOrNull
                ?: raw.ifBlank { "Gateway request failed" }
            val generationId = (parsed?.get("generationId") as? JsonPrimitive)?.contentOrNull
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
