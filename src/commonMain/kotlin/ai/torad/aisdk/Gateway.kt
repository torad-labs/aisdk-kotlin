@file:OptIn(LowLevelLanguageModelApi::class)

package ai.torad.aisdk

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.jvm.JvmSynthetic
import kotlin.time.Clock

public const val AI_GATEWAY_PROTOCOL_VERSION: String = "0.0.1"
public const val AI_GATEWAY_DEFAULT_BASE_URL: String = "https://ai-gateway.vercel.sh/v3/ai"
public const val GATEWAY_AUTH_METHOD_HEADER: String = "ai-gateway-auth-method"

/** @since 0.3.0-beta01 */
public enum class GatewayAuthMethod(public val wireValue: String) {
    ApiKey("api-key"),
    Oidc("oidc"),
}

internal suspend fun GatewayAuthTokenFromSettings(settings: GatewayProviderSettings): GatewayAuthToken? {
    val key = (settings.apiKey ?: settings.environment["AI_GATEWAY_API_KEY"])?.takeIf { it.isNotBlank() }
    if (key != null) return GatewayAuthToken(key, GatewayAuthMethod.ApiKey)
    // Then a custom token provider, else the OIDC fallback: VERCEL_OIDC_TOKEN from the
    // host environment (the KMP-idiomatic equivalent of upstream's getVercelOidcToken()).
    return settings.authTokenProvider?.invoke()
        ?: settings.environment["VERCEL_OIDC_TOKEN"]
            ?.takeIf { it.isNotBlank() }
            ?.let { GatewayAuthToken(it, GatewayAuthMethod.Oidc) }
}

/** @since 0.3.0-beta01 */
public data class GatewayAuthToken(
    val token: String,
    val authMethod: GatewayAuthMethod,
)

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
        val auth = GatewayAuthTokenFromSettings(this)
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

public typealias GatewayModelEntry = GatewayLanguageModelEntry

/** @since 0.3.0-beta01 */
public fun GatewaySpendReportParams(
    block: GatewaySpendReportParamsBuilder.() -> Unit = {},
): GatewaySpendReportParams =
    GatewaySpendReportParamsBuilder().apply(block).build()

/** @since 0.3.0-beta01 */
public fun GatewayGenerationInfoParams(
    block: GatewayGenerationInfoParamsBuilder.() -> Unit = {},
): GatewayGenerationInfoParams =
    GatewayGenerationInfoParamsBuilder().apply(block).build()

internal fun GatewayTransportMissing(): Nothing = throw GatewayTransportNotConfiguredError()

/** @since 0.3.0-beta01 */
public interface GatewayTransport {
    /** @since 0.3.0-beta01 */
    @JvmSynthetic
    public suspend fun generateText(
        context: GatewayRequestContext,
        modelId: ModelId,
        params: LanguageModelCallParams,
    ): LanguageModelResult = GatewayTransportMissing()

    /** @since 0.3.0-beta01 */
    @JvmSynthetic public fun streamText(
        context: GatewayRequestContext,
        modelId: ModelId,
        params: LanguageModelCallParams,
    ): Flow<StreamEvent> = flow { throw GatewayTransportNotConfiguredError() }

    /** @since 0.3.0-beta01 */
    @JvmSynthetic
    public suspend fun embed(
        context: GatewayRequestContext,
        modelId: ModelId,
        params: EmbeddingModelCallParams,
    ): EmbeddingModelResult = GatewayTransportMissing()

    /** @since 0.3.0-beta01 */
    @JvmSynthetic
    public suspend fun generateImage(
        context: GatewayRequestContext,
        modelId: ModelId,
        params: ImageGenerationParams,
    ): ImageModelResult = GatewayTransportMissing()

    /** @since 0.3.0-beta01 */
    @JvmSynthetic
    public suspend fun generateVideo(
        context: GatewayRequestContext,
        modelId: ModelId,
        params: VideoGenerationParams,
    ): VideoModelResult = GatewayTransportMissing()

    /** @since 0.3.0-beta01 */
    @JvmSynthetic
    public suspend fun rerank(
        context: GatewayRequestContext,
        modelId: ModelId,
        params: RerankingParams,
    ): RerankingModelResult = GatewayTransportMissing()

    /** @since 0.3.0-beta01 */
    @JvmSynthetic
    public suspend fun getAvailableModels(context: GatewayRequestContext): GatewayFetchMetadataResponse =
        GatewayTransportMissing()

    /** @since 0.3.0-beta01 */
    @JvmSynthetic
    public suspend fun getCredits(context: GatewayRequestContext): GatewayCreditsResponse =
        GatewayTransportMissing()

    /** @since 0.3.0-beta01 */
    @JvmSynthetic
    public suspend fun getSpendReport(
        context: GatewayRequestContext,
        params: GatewaySpendReportParams,
    ): GatewaySpendReportResponse = GatewayTransportMissing()

    /** @since 0.3.0-beta01 */
    @JvmSynthetic
    public suspend fun getGenerationInfo(
        context: GatewayRequestContext,
        params: GatewayGenerationInfoParams,
    ): GatewayGenerationInfo = GatewayTransportMissing()
}

/** @since 0.3.0-beta01 */
public data object GatewayTransportNotConfigured : GatewayTransport

/** @since 0.3.0-beta01 */
public class GatewayTransportNotConfiguredError :
    AiSdkException("Gateway transport is not configured. Provide GatewayProviderSettings.transport.")

/**
 * Sealed (not `sealed interface`; see the repo's `no-sealed-interface` tenet — this
 * type is a single-implementation service facade, not a `@Serializable` wire type or
 * a private state machine, so the class form is what stays compliant) so the SDK
 * keeps the freedom to add members without breaking an external implementer.
 * @since 0.3.0-beta01
 */
public sealed class GatewayProvider : Provider {
    /** @since 0.3.0-beta01 */
    public abstract val settings: GatewayProviderSettings

    /** @since 0.3.0-beta01 */
    public abstract val tools: GatewayTools

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

    /** @since 0.3.0-beta01 */
    @JvmSynthetic
    public abstract suspend fun getAvailableModels(): GatewayFetchMetadataResponse

    /** @since 0.3.0-beta01 */
    @JvmSynthetic
    public abstract suspend fun getCredits(): GatewayCreditsResponse

    /** @since 0.3.0-beta01 */
    @JvmSynthetic
    public abstract suspend fun getSpendReport(params: GatewaySpendReportParams): GatewaySpendReportResponse

    /** @since 0.3.0-beta01 */
    @JvmSynthetic
    public abstract suspend fun getGenerationInfo(params: GatewayGenerationInfoParams): GatewayGenerationInfo
}

/** @since 0.3.0-beta01 */
public fun GatewayProvider(settings: GatewayProviderSettings = GatewayProviderSettings()): GatewayProvider =
    DefaultGatewayProvider(settings)

/**
 * Creates a Vercel AI Gateway provider.
 *
 * The returned provider resolves language, embedding, image, video, and
 * reranking models by Gateway model id. [settings] supplies credentials,
 * transport, metadata cache timing, and host environment values. The caller
 * owns the transport's HTTP client and timeout behavior.
 * @since 0.3.0-beta01
 */
public fun Gateway(settings: GatewayProviderSettings = GatewayProviderSettings()): GatewayProvider =
    GatewayProvider(settings)

/** @since 0.3.0-beta01 */
public val gateway: GatewayProvider = GatewayProvider()

private class DefaultGatewayProvider(
    override val settings: GatewayProviderSettings,
) : GatewayProvider() {
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
