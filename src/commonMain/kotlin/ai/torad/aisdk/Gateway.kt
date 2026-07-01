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

/** @since 0.3.0-beta01 */
public enum class GatewayAuthMethod(public val wireValue: String) {
    ApiKey("api-key"),
    Oidc("oidc"),
}

/** @since 0.3.0-beta01 */
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

/** @since 0.3.0-beta01 */
public class GatewayProviderSettings internal constructor(
    /** @since 0.3.0-beta01 */
    public val baseUrl: String = AI_GATEWAY_DEFAULT_BASE_URL,
    /** @since 0.3.0-beta01 */
    public val apiKey: String? = null,
    /** @since 0.3.0-beta01 */
    public val headers: Map<String, String> = emptyMap(),
    /** @since 0.3.0-beta01 */
    public val transport: GatewayTransport = GatewayTransportNotConfigured,
    /** @since 0.3.0-beta01 */
    public val metadataCacheRefreshMillis: Long = 5 * 60 * 1000L,
    /** @since 0.3.0-beta01 */
    public val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    /** @since 0.3.0-beta01 */
    public val authTokenProvider: (suspend () -> GatewayAuthToken?)? = null,
    /**
     * Host-supplied environment. When [apiKey] is null, `AI_GATEWAY_API_KEY` here
     * is used — the KMP-idiomatic equivalent of upstream's process.env lookup
     * (commonMain has no platform `getenv`; the host passes the map).
      * @since 0.3.0-beta01
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

/** @since 0.3.0-beta01 */
public class GatewayProviderSettingsBuilder {
    private var baseUrl: String = AI_GATEWAY_DEFAULT_BASE_URL
    private var apiKey: String? = null
    private var headers: Map<String, String> = emptyMap()
    private var transport: GatewayTransport = GatewayTransportNotConfigured
    private var metadataCacheRefreshMillis: Long = 5 * 60 * 1000L
    private var nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() }
    private var authTokenProvider: (suspend () -> GatewayAuthToken?)? = null
    private var environment: Map<String, String> = emptyMap()

    /** @since 0.3.0-beta01 */
    public fun baseUrl(value: String): GatewayProviderSettingsBuilder {
        baseUrl = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun apiKey(value: String?): GatewayProviderSettingsBuilder {
        apiKey = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun headers(value: Map<String, String>): GatewayProviderSettingsBuilder {
        headers = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun transport(value: GatewayTransport): GatewayProviderSettingsBuilder {
        transport = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun metadataCacheRefreshMillis(value: Long): GatewayProviderSettingsBuilder {
        metadataCacheRefreshMillis = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun nowMillis(value: () -> Long): GatewayProviderSettingsBuilder {
        nowMillis = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun authTokenProvider(value: (suspend () -> GatewayAuthToken?)?): GatewayProviderSettingsBuilder {
        authTokenProvider = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun environment(value: Map<String, String>): GatewayProviderSettingsBuilder {
        environment = value
        return this
    }

    /** @since 0.3.0-beta01 */
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

/** @since 0.3.0-beta01 */
public fun GatewayProviderSettings(
    block: GatewayProviderSettingsBuilder.() -> Unit = {},
): GatewayProviderSettings =
    GatewayProviderSettingsBuilder().apply(block).build()

@Poko
/** @since 0.3.0-beta01 */
public class GatewayRequestContext(
    /** @since 0.3.0-beta01 */
    public val baseUrl: String,
    /** @since 0.3.0-beta01 */
    public val headers: Map<String, String>,
)

@Poko
/** @since 0.3.0-beta01 */
public class GatewayPricing(
    /** @since 0.3.0-beta01 */
    public val input: String,
    /** @since 0.3.0-beta01 */
    public val output: String,
    /** @since 0.3.0-beta01 */
    public val cachedInputTokens: String? = null,
    /** @since 0.3.0-beta01 */
    public val cacheCreationInputTokens: String? = null,
)

@Poko
/** @since 0.3.0-beta01 */
public class GatewayLanguageModelSpecification(
    /** @since 0.3.0-beta01 */
    public val specificationVersion: String = "v3",
    /** @since 0.3.0-beta01 */
    public val provider: String,
    /** @since 0.3.0-beta01 */
    public val modelId: String,
)

/** @since 0.3.0-beta01 */
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
/** @since 0.3.0-beta01 */
public class GatewayLanguageModelEntry(
    /** @since 0.3.0-beta01 */
    public val id: String,
    /** @since 0.3.0-beta01 */
    public val name: String,
    /** @since 0.3.0-beta01 */
    public val description: String? = null,
    /** @since 0.3.0-beta01 */
    public val pricing: GatewayPricing? = null,
    /** @since 0.3.0-beta01 */
    public val specification: GatewayLanguageModelSpecification,
    /** @since 0.3.0-beta01 */
    public val modelType: GatewayModelType? = null,
)

public typealias GatewayModelEntry = GatewayLanguageModelEntry

@Poko
/** @since 0.3.0-beta01 */
public class GatewayFetchMetadataResponse(
    /** @since 0.3.0-beta01 */
    public val models: List<GatewayLanguageModelEntry>,
)

@Poko
/** @since 0.3.0-beta01 */
public class GatewayCreditsResponse(
    /** @since 0.3.0-beta01 */
    public val balance: String,
    /** @since 0.3.0-beta01 */
    public val totalUsed: String,
)

/** @since 0.3.0-beta01 */
public enum class GatewaySpendReportGroupBy(public val wireValue: String) {
    Day("day"),
    User("user"),
    Model("model"),
    Tag("tag"),
    Provider("provider"),
    CredentialType("credential_type"),
}

/** @since 0.3.0-beta01 */
public enum class GatewaySpendReportDatePart(public val wireValue: String) {
    Day("day"),
    Hour("hour"),
}

/** @since 0.3.0-beta01 */
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
/** @since 0.3.0-beta01 */
public class GatewaySpendReportParams internal constructor(
    /** @since 0.3.0-beta01 */
    public val startDate: String,
    /** @since 0.3.0-beta01 */
    public val endDate: String,
    /** @since 0.3.0-beta01 */
    public val groupBy: GatewaySpendReportGroupBy? = null,
    /** @since 0.3.0-beta01 */
    public val datePart: GatewaySpendReportDatePart? = null,
    /** @since 0.3.0-beta01 */
    public val userId: String? = null,
    /** @since 0.3.0-beta01 */
    public val model: String? = null,
    /** @since 0.3.0-beta01 */
    public val provider: String? = null,
    /** @since 0.3.0-beta01 */
    public val credentialType: GatewayCredentialType? = null,
    /** @since 0.3.0-beta01 */
    public val tags: List<String> = emptyList(),
)

/** @since 0.3.0-beta01 */
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

    /** @since 0.3.0-beta01 */
    public fun startDate(value: String): GatewaySpendReportParamsBuilder {
        startDate = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun endDate(value: String): GatewaySpendReportParamsBuilder {
        endDate = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun groupBy(value: GatewaySpendReportGroupBy?): GatewaySpendReportParamsBuilder {
        groupBy = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun datePart(value: GatewaySpendReportDatePart?): GatewaySpendReportParamsBuilder {
        datePart = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun userId(value: String?): GatewaySpendReportParamsBuilder {
        userId = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun model(value: String?): GatewaySpendReportParamsBuilder {
        model = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun provider(value: String?): GatewaySpendReportParamsBuilder {
        provider = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun credentialType(value: GatewayCredentialType?): GatewaySpendReportParamsBuilder {
        credentialType = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun tags(value: List<String>): GatewaySpendReportParamsBuilder {
        tags = value
        return this
    }

    /** @since 0.3.0-beta01 */
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

/** @since 0.3.0-beta01 */
public fun GatewaySpendReportParams(
    block: GatewaySpendReportParamsBuilder.() -> Unit = {},
): GatewaySpendReportParams =
    GatewaySpendReportParamsBuilder().apply(block).build()

@Poko
/** @since 0.3.0-beta01 */
public class GatewaySpendReportRow(
    /** @since 0.3.0-beta01 */
    public val day: String? = null,
    /** @since 0.3.0-beta01 */
    public val hour: String? = null,
    /** @since 0.3.0-beta01 */
    public val user: String? = null,
    /** @since 0.3.0-beta01 */
    public val model: String? = null,
    /** @since 0.3.0-beta01 */
    public val tag: String? = null,
    /** @since 0.3.0-beta01 */
    public val provider: String? = null,
    /** @since 0.3.0-beta01 */
    public val credentialType: GatewayCredentialType? = null,
    /** @since 0.3.0-beta01 */
    public val totalCost: Double,
    /** @since 0.3.0-beta01 */
    public val marketCost: Double? = null,
    /** @since 0.3.0-beta01 */
    public val inputTokens: Int? = null,
    /** @since 0.3.0-beta01 */
    public val outputTokens: Int? = null,
    /** @since 0.3.0-beta01 */
    public val cachedInputTokens: Int? = null,
    /** @since 0.3.0-beta01 */
    public val cacheCreationInputTokens: Int? = null,
    /** @since 0.3.0-beta01 */
    public val reasoningTokens: Int? = null,
    /** @since 0.3.0-beta01 */
    public val requestCount: Int? = null,
)

@Poko
/** @since 0.3.0-beta01 */
public class GatewaySpendReportResponse(
    /** @since 0.3.0-beta01 */
    public val results: List<GatewaySpendReportRow>,
)

@Poko
/** @since 0.3.0-beta01 */
public class GatewayGenerationInfoParams internal constructor(
    /** @since 0.3.0-beta01 */
    public val id: String,
)

/** @since 0.3.0-beta01 */
public class GatewayGenerationInfoParamsBuilder {
    private var id: String? = null

    /** @since 0.3.0-beta01 */
    public fun id(value: String): GatewayGenerationInfoParamsBuilder {
        id = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun build(): GatewayGenerationInfoParams =
        GatewayGenerationInfoParams(
            id = requireNotNull(id) { "GatewayGenerationInfoParams.id is required" },
        )
}

/** @since 0.3.0-beta01 */
public fun GatewayGenerationInfoParams(
    block: GatewayGenerationInfoParamsBuilder.() -> Unit = {},
): GatewayGenerationInfoParams =
    GatewayGenerationInfoParamsBuilder().apply(block).build()

@Poko
/** @since 0.3.0-beta01 */
public class GatewayGenerationInfo(
    /** @since 0.3.0-beta01 */
    public val id: String,
    /** @since 0.3.0-beta01 */
    public val totalCost: Double,
    /** @since 0.3.0-beta01 */
    public val upstreamInferenceCost: Double,
    /** @since 0.3.0-beta01 */
    public val usage: Double,
    /** @since 0.3.0-beta01 */
    public val createdAt: String,
    /** @since 0.3.0-beta01 */
    public val model: String,
    /** @since 0.3.0-beta01 */
    public val isByok: Boolean,
    /** @since 0.3.0-beta01 */
    public val providerName: String,
    /** @since 0.3.0-beta01 */
    public val streamed: Boolean,
    /** @since 0.3.0-beta01 */
    public val finishReason: String,
    /** @since 0.3.0-beta01 */
    public val latency: Int,
    /** @since 0.3.0-beta01 */
    public val generationTime: Int,
    /** @since 0.3.0-beta01 */
    public val promptTokens: Int,
    /** @since 0.3.0-beta01 */
    public val completionTokens: Int,
    /** @since 0.3.0-beta01 */
    public val reasoningTokens: Int,
    /** @since 0.3.0-beta01 */
    public val cachedTokens: Int,
    /** @since 0.3.0-beta01 */
    public val cacheCreationTokens: Int,
    /** @since 0.3.0-beta01 */
    public val billableWebSearchCalls: Int,
)

/** @since 0.3.0-beta01 */
public interface GatewayTransport {
    public suspend fun generateText(
        context: GatewayRequestContext,
        modelId: ModelId,
        params: LanguageModelCallParams,
    ): LanguageModelResult = gatewayTransportMissing()

    /** @since 0.3.0-beta01 */
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

/** @since 0.3.0-beta01 */
public data object GatewayTransportNotConfigured : GatewayTransport

/** @since 0.3.0-beta01 */
public class GatewayTransportNotConfiguredError :
    AiSdkException("Gateway transport is not configured. Provide GatewayProviderSettings.transport.")

/** @since 0.3.0-beta01 */
public interface GatewayProvider : Provider {
    /** @since 0.3.0-beta01 */
    public val settings: GatewayProviderSettings
    /** @since 0.3.0-beta01 */
    public val tools: GatewayTools

    public operator fun invoke(modelId: ModelId): LanguageModel = languageModel(modelId.value)
    /** @since 0.3.0-beta01 */
    public fun chat(modelId: ModelId): LanguageModel = languageModel(modelId.value)
    /** @since 0.3.0-beta01 */
    public fun embedding(modelId: ModelId): EmbeddingModel = embeddingModel(modelId.value)
    /** @since 0.3.0-beta01 */
    public fun image(modelId: ModelId): ImageModel = imageModel(modelId.value)
    /** @since 0.3.0-beta01 */
    public fun video(modelId: ModelId): VideoModel = videoModel(modelId.value)
    /** @since 0.3.0-beta01 */
    public fun reranking(modelId: ModelId): RerankingModel = rerankingModel(modelId.value)
    /** @since 0.3.0-beta01 */
    public fun textEmbeddingModel(modelId: ModelId): EmbeddingModel = embeddingModel(modelId.value)

    public suspend fun getAvailableModels(): GatewayFetchMetadataResponse
    public suspend fun getCredits(): GatewayCreditsResponse
    public suspend fun getSpendReport(params: GatewaySpendReportParams): GatewaySpendReportResponse
    public suspend fun getGenerationInfo(params: GatewayGenerationInfoParams): GatewayGenerationInfo
}

/** @since 0.3.0-beta01 */
public fun GatewayProvider(settings: GatewayProviderSettings = GatewayProviderSettings()): GatewayProvider =
    DefaultGatewayProvider(settings)

/** @since 0.3.0-beta01 */
public fun Gateway(settings: GatewayProviderSettings = GatewayProviderSettings()): GatewayProvider =
    GatewayProvider(settings)

/** @since 0.3.0-beta01 */
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
/** @since 0.3.0-beta01 */
public class GatewayTools(
    /** @since 0.3.0-beta01 */
    public val parallelSearch: Tool<JsonElement, JsonElement, Any?> = ProviderExecutedTool(
        name = "parallelSearch",
        description = "Search the web using Parallel AI's Search API for LLM-optimized excerpts.",
        inputSerializer = JsonElement.serializer(),
        outputSerializer = JsonElement.serializer(),
    ),
    /** @since 0.3.0-beta01 */
    public val perplexitySearch: Tool<JsonElement, JsonElement, Any?> = ProviderExecutedTool(
        name = "perplexitySearch",
        description = "Search the web using Perplexity's Search API for real-time information.",
        inputSerializer = JsonElement.serializer(),
        outputSerializer = JsonElement.serializer(),
    ),
)

/** @since 0.3.0-beta01 */
public open class GatewayError(
    message: String,
    /** @since 0.3.0-beta01 */
    public val statusCode: Int = 500,
    /** @since 0.3.0-beta01 */
    public val type: String = "gateway_error",
    /** @since 0.3.0-beta01 */
    public val generationId: String? = null,
    cause: Throwable? = null,
    /** @since 0.3.0-beta01 */
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

/** @since 0.3.0-beta01 */
public class GatewayAuthenticationError(
    message: String = "Authentication failed",
    statusCode: Int = 401,
    generationId: String? = null,
    cause: Throwable? = null,
) : GatewayError(message, statusCode, "authentication_error", generationId, cause)

/** @since 0.3.0-beta01 */
public class GatewayInvalidRequestError(
    message: String = "Invalid request",
    statusCode: Int = 400,
    generationId: String? = null,
    cause: Throwable? = null,
) : GatewayError(message, statusCode, "invalid_request_error", generationId, cause)

/** @since 0.3.0-beta01 */
public class GatewayRateLimitError(
    message: String = "Rate limit exceeded",
    statusCode: Int = 429,
    generationId: String? = null,
    cause: Throwable? = null,
) : GatewayError(message, statusCode, "rate_limit_exceeded", generationId, cause)

/** @since 0.3.0-beta01 */
public class GatewayModelNotFoundError(
    message: String = "Model not found",
    statusCode: Int = 404,
    /** @since 0.3.0-beta01 */
    public val modelId: String? = null,
    generationId: String? = null,
    cause: Throwable? = null,
) : GatewayError(message, statusCode, "model_not_found", generationId, cause)

/** @since 0.3.0-beta01 */
public class GatewayInternalServerError(
    message: String = "Internal server error",
    statusCode: Int = 500,
    generationId: String? = null,
    cause: Throwable? = null,
) : GatewayError(message, statusCode, "internal_server_error", generationId, cause)

/** @since 0.3.0-beta01 */
public class GatewayResponseError(
    message: String = "Invalid response from Gateway",
    statusCode: Int = 502,
    /** @since 0.3.0-beta01 */
    public val response: JsonElement? = null,
    generationId: String? = null,
    cause: Throwable? = null,
) : GatewayError(message, statusCode, "response_error", generationId, cause)

/** @since 0.3.0-beta01 */
public class GatewayTimeoutError(
    message: String = "Gateway request timed out",
    statusCode: Int = 408,
    generationId: String? = null,
    cause: Throwable? = null,
) : GatewayError(message, statusCode, "timeout_error", generationId, cause)
