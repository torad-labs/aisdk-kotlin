package ai.torad.aisdk

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
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
)

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
)

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
    ): LanguageModelResult = GatewayWire.gatewayTransportMissing()

    public fun streamText(
        context: GatewayRequestContext,
        modelId: ModelId,
        params: LanguageModelCallParams,
    ): Flow<StreamEvent> = flow { throw GatewayTransportNotConfiguredError() }

    public suspend fun embed(
        context: GatewayRequestContext,
        modelId: ModelId,
        params: EmbeddingModelCallParams,
    ): EmbeddingModelResult = GatewayWire.gatewayTransportMissing()

    public suspend fun generateImage(
        context: GatewayRequestContext,
        modelId: ModelId,
        params: ImageGenerationParams,
    ): ImageModelResult = GatewayWire.gatewayTransportMissing()

    public suspend fun generateVideo(
        context: GatewayRequestContext,
        modelId: ModelId,
        params: VideoGenerationParams,
    ): VideoModelResult = GatewayWire.gatewayTransportMissing()

    public suspend fun rerank(
        context: GatewayRequestContext,
        modelId: ModelId,
        params: RerankingParams,
    ): RerankingModelResult = GatewayWire.gatewayTransportMissing()

    public suspend fun getAvailableModels(context: GatewayRequestContext): GatewayFetchMetadataResponse =
        GatewayWire.gatewayTransportMissing()

    public suspend fun getCredits(context: GatewayRequestContext): GatewayCreditsResponse =
        GatewayWire.gatewayTransportMissing()

    public suspend fun getSpendReport(
        context: GatewayRequestContext,
        params: GatewaySpendReportParams,
    ): GatewaySpendReportResponse = GatewayWire.gatewayTransportMissing()

    public suspend fun getGenerationInfo(
        context: GatewayRequestContext,
        params: GatewayGenerationInfoParams,
    ): GatewayGenerationInfo = GatewayWire.gatewayTransportMissing()
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
            headers = GatewayWire.gatewayHeaders(settings),
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

internal object GatewayWire {
    fun gatewayTransportMissing(): Nothing = throw GatewayTransportNotConfiguredError()

    suspend fun getGatewayAuthToken(settings: GatewayProviderSettings): GatewayAuthToken? {
        val key = settings.apiKey ?: settings.environment["AI_GATEWAY_API_KEY"]
        if (key != null) return GatewayAuthToken(key, GatewayAuthMethod.ApiKey)
        // Then a custom token provider, else the OIDC fallback: VERCEL_OIDC_TOKEN from the
        // host environment (the KMP-idiomatic equivalent of upstream's getVercelOidcToken()).
        return settings.authTokenProvider?.invoke()
            ?: settings.environment["VERCEL_OIDC_TOKEN"]?.let { GatewayAuthToken(it, GatewayAuthMethod.Oidc) }
    }

    suspend fun gatewayHeaders(settings: GatewayProviderSettings): Map<String, String> {
        val auth = getGatewayAuthToken(settings)
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
            settings.environment[envVar]?.let { base[header] = it }
        }
        base.putAll(settings.headers)
        return ProviderHeaders.withUserAgentSuffix(base, "ai-sdk/gateway-kotlin")
    }

    fun parseGatewayAuthMethod(headers: Map<String, String?>): GatewayAuthMethod? =
        when (headers[GATEWAY_AUTH_METHOD_HEADER]) {
            GatewayAuthMethod.ApiKey.wireValue -> GatewayAuthMethod.ApiKey
            GatewayAuthMethod.Oidc.wireValue -> GatewayAuthMethod.Oidc
            else -> null
        }

    fun modelMessageJson(message: ModelMessage): JsonObject = buildJsonObject {
        put("role", JsonPrimitive(message.role.name.lowercase()))
        put("content", JsonArray(message.content.map(::contentPartJson)))
    }

    fun contentPartJson(part: ContentPart): JsonObject = buildJsonObject {
        when (part) {
            is ContentPart.Text -> {
                put("type", JsonPrimitive("text"))
                put("text", JsonPrimitive(part.text))
            }
            is ContentPart.Reasoning -> {
                put("type", JsonPrimitive("reasoning"))
                put("text", JsonPrimitive(part.text))
            }
            is ContentPart.ToolCall -> {
                put("type", JsonPrimitive("tool-call"))
                put("toolCallId", JsonPrimitive(part.toolCallId))
                put("toolName", JsonPrimitive(part.toolName))
                put("input", part.input)
            }
            is ContentPart.ToolResult -> {
                put("type", JsonPrimitive("tool-result"))
                put("toolCallId", JsonPrimitive(part.toolCallId))
                put("toolName", JsonPrimitive(part.toolName))
                put("output", part.modelVisible)
                put("isError", JsonPrimitive(part.isError))
            }
            is ContentPart.ToolApprovalRequest -> {
                put("type", JsonPrimitive("tool-approval-request"))
                put("toolCallId", JsonPrimitive(part.toolCallId))
                put("toolName", JsonPrimitive(part.toolName))
                put("input", part.input)
                part.approvalId?.let { put("approvalId", JsonPrimitive(it)) }
            }
            is ContentPart.ToolApprovalResponse -> {
                put("type", JsonPrimitive("tool-approval-response"))
                put("toolCallId", JsonPrimitive(part.toolCallId))
                put("approved", JsonPrimitive(part.approved))
                part.reason?.let { put("reason", JsonPrimitive(it)) }
                part.approvalId?.let { put("approvalId", JsonPrimitive(it)) }
            }
            is ContentPart.Source -> {
                val sourceType =
                    if (part.sourceType == StreamEvent.SourcePart.SourceType.Url) "source-url" else "source-document"
                put("type", JsonPrimitive(sourceType))
                part.url?.let { put("url", JsonPrimitive(it)) }
                part.title?.let { put("title", JsonPrimitive(it)) }
            }
            is ContentPart.File -> {
                put("type", JsonPrimitive("file"))
                put("mediaType", JsonPrimitive(part.mediaType))
                put("data", JsonPrimitive(part.base64))
                part.filename?.let { put("filename", JsonPrimitive(it)) }
            }
            is ContentPart.Image -> {
                put("type", JsonPrimitive("file"))
                put("mediaType", JsonPrimitive(part.mediaType))
                put("data", JsonPrimitive(part.base64))
            }
        }
        val pm = part.metadata
        if (pm is ProviderMetadata.Raw) put("providerMetadata", pm.metadata)
    }

    fun languageModelToolJson(tool: LanguageModelTool): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("function"))
        put("name", JsonPrimitive(tool.name))
        put("description", JsonPrimitive(tool.description))
        put("inputSchema", aiSdkJson.parseToJsonElement(tool.parametersSchemaJson))
        put("strict", JsonPrimitive(tool.strict))
        if (tool.providerExecuted) put("providerExecuted", JsonPrimitive(true))
        if (tool.providerOptions.toMap().isNotEmpty()) put("providerOptions", JsonObject(tool.providerOptions.toMap()))
    }

    fun toolChoiceJson(choice: ToolChoice): JsonElement = when (choice) {
        ToolChoice.Auto -> JsonPrimitive("auto")
        ToolChoice.None -> JsonPrimitive("none")
        ToolChoice.Required -> JsonPrimitive("required")
        is ToolChoice.Specific -> buildJsonObject {
            put("type", JsonPrimitive("tool"))
            put("toolName", JsonPrimitive(choice.toolName))
        }
    }

    fun responseFormatJson(format: ResponseFormat): JsonElement = when (format) {
        ResponseFormat.Text -> buildJsonObject { put("type", JsonPrimitive("text")) }
        is ResponseFormat.Json -> buildJsonObject {
            put("type", JsonPrimitive("json"))
            format.schemaJson?.let { put("schema", it) }
            format.schemaName?.let { put("name", JsonPrimitive(it)) }
            format.schemaDescription?.let { put("description", JsonPrimitive(it)) }
        }
    }

    fun contentParts(value: JsonElement?): List<ContentPart> =
        value?.let { WireDecoder.arrayValue(it, "gateway", "content parts").mapNotNull(::contentPartFromJson) }.orEmpty()

    fun contentPartFromJson(value: JsonElement): ContentPart? {
        val obj = WireDecoder.objectValue(value, "gateway", "content part")
        return when (WireDecoder.requiredString(obj, "type", "gateway", "content part")) {
            "text" -> ContentPart.Text(
                text = WireDecoder.requiredString(obj, "text", "gateway", "content part"),
                providerMetadata = obj["providerMetadata"].let { if (it is JsonObject) ProviderMetadata.Raw(it) else ProviderMetadata.None },
            )
            "reasoning" -> ContentPart.Reasoning(
                text = WireDecoder.requiredString(obj, "text", "gateway", "content part"),
                providerMetadata = obj["providerMetadata"].let { if (it is JsonObject) ProviderMetadata.Raw(it) else ProviderMetadata.None },
            )
            "tool-call" -> ContentPart.ToolCall(
                toolCallId = WireDecoder.requiredString(obj, "toolCallId", "gateway", "content part"),
                toolName = WireDecoder.requiredString(obj, "toolName", "gateway", "content part"),
                input = WireDecoder.required(obj, "input", "gateway", "content part"),
                providerMetadata = obj["providerMetadata"].let { if (it is JsonObject) ProviderMetadata.Raw(it) else ProviderMetadata.None },
            )
            "tool-result" -> ContentPart.ToolResult(
                toolCallId = WireDecoder.requiredString(obj, "toolCallId", "gateway", "content part"),
                toolName = WireDecoder.requiredString(obj, "toolName", "gateway", "content part"),
                output = WireDecoder.required(obj, "output", "gateway", "content part"),
                isError = WireDecoder.optionalBoolean(obj, "isError", "gateway", "content part") ?: false,
                providerMetadata = obj["providerMetadata"].let { if (it is JsonObject) ProviderMetadata.Raw(it) else ProviderMetadata.None },
            )
            "source-url" -> ContentPart.Source(
                sourceType = StreamEvent.SourcePart.SourceType.Url,
                url = WireDecoder.requiredString(obj, "url", "gateway", "content part"),
                title = WireDecoder.optionalString(obj, "title", "gateway", "content part"),
                providerMetadata = obj["providerMetadata"].let { if (it is JsonObject) ProviderMetadata.Raw(it) else ProviderMetadata.None },
            )
            "file" -> ContentPart.File(
                mediaType = WireDecoder.optionalString(obj, "mediaType", "gateway", "content part") ?: "application/octet-stream",
                base64 = requiredOneOfString(obj, "gateway", "content part", "data", "base64"),
                filename = WireDecoder.optionalString(obj, "filename", "gateway", "content part"),
                providerMetadata = obj["providerMetadata"].let { if (it is JsonObject) ProviderMetadata.Raw(it) else ProviderMetadata.None },
            )
            else -> null
        }
    }

    fun streamEventFromJson(value: JsonElement): StreamEvent {
        val obj = WireDecoder.objectValue(value, "gateway", "stream event")
        return when (val type = WireDecoder.requiredString(obj, "type", "gateway", "stream event")) {
            "stream-start" -> StreamEvent.StreamStart(callWarnings(obj["warnings"]))
            "response-metadata" -> StreamEvent.ResponseMetadata(
                id = WireDecoder.optionalString(obj, "id", "gateway", "stream event"),
                timestampMillis = obj["timestampMillis"]?.jsonPrimitive?.longOrNull
                    ?: obj["timestamp"]?.jsonPrimitive?.doubleOrNull?.let { (it * 1000).toLong() },
                modelId = WireDecoder.optionalString(obj, "modelId", "gateway", "stream event"),
                headers = (obj["headers"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.content }.orEmpty(),
                body = obj["body"],
            )
            "text-start" -> StreamEvent.TextStart(
                WireDecoder.optionalString(obj, "id", "gateway", "stream event") ?: "text"
            )
            "text-delta" -> StreamEvent.TextDelta(
                id = WireDecoder.optionalString(obj, "id", "gateway", "stream event") ?: "text",
                text = requiredOneOfString(obj, "gateway", "stream event", "delta", "text"),
            )
            "text-end" -> StreamEvent.TextEnd(WireDecoder.optionalString(obj, "id", "gateway", "stream event") ?: "text")
            "reasoning-start" -> StreamEvent.ReasoningStart(
                WireDecoder.optionalString(obj, "id", "gateway", "stream event") ?: "reasoning"
            )
            "reasoning-delta" -> StreamEvent.ReasoningDelta(
                id = WireDecoder.optionalString(obj, "id", "gateway", "stream event") ?: "reasoning",
                text = requiredOneOfString(obj, "gateway", "stream event", "delta", "text"),
            )
            "reasoning-end" -> StreamEvent.ReasoningEnd(
                WireDecoder.optionalString(obj, "id", "gateway", "stream event") ?: "reasoning"
            )
            "tool-input-start" -> StreamEvent.ToolInputStart(
                id = requiredOneOfString(obj, "gateway", "stream event", "id", "toolCallId"),
                toolName = WireDecoder.requiredString(obj, "toolName", "gateway", "stream event"),
            )
            "tool-input-delta" -> StreamEvent.ToolInputDelta(
                id = requiredOneOfString(obj, "gateway", "stream event", "id", "toolCallId"),
                delta = WireDecoder.requiredString(obj, "delta", "gateway", "stream event"),
            )
            "tool-input-end" -> StreamEvent.ToolInputEnd(
                id = requiredOneOfString(obj, "gateway", "stream event", "id", "toolCallId"),
            )
            "tool-call" -> StreamEvent.ToolCall(
                toolCallId = WireDecoder.requiredString(obj, "toolCallId", "gateway", "stream event"),
                toolName = WireDecoder.requiredString(obj, "toolName", "gateway", "stream event"),
                inputJson = WireDecoder.required(obj, "input", "gateway", "stream event"),
            )
            "tool-result" -> with(ToolResultOutputs) {
                val output = toolResultOutputFromWire(WireDecoder.required(obj, "output", "gateway", "stream event"))
                StreamEvent.ToolResult(
                    toolCallId = WireDecoder.requiredString(obj, "toolCallId", "gateway", "stream event"),
                    toolName = WireDecoder.requiredString(obj, "toolName", "gateway", "stream event"),
                    outputJson = output.toJsonElement(),
                    output = output,
                    modelOutput = output,
                    isError = output.isToolResultError(),
                )
            }
            "finish-step" -> StreamEvent.StepFinish(
                stepNumber = obj["stepNumber"]?.jsonPrimitive?.intOrNull ?: 1,
                finishReason = finishReason(obj["finishReason"]?.jsonPrimitive?.contentOrNull),
                usage = usageFromJson(obj["usage"]),
            )
            "finish" -> StreamEvent.Finish(
                totalSteps = obj["totalSteps"]?.jsonPrimitive?.intOrNull ?: 1,
                finishReason = finishReason(obj["finishReason"]?.jsonPrimitive?.contentOrNull),
                usage = usageFromJson(obj["usage"]),
            )
            "error" -> StreamEvent.Error(WireDecoder.requiredString(obj, "message", "gateway", "stream event"))
            "raw" -> StreamEvent.Raw(obj["rawValue"] ?: value)
            else -> StreamEvent.Raw(
                buildJsonObject {
                    put("type", JsonPrimitive(type))
                    put("data", value)
                }
            )
        }
    }

    fun requiredOneOfString(obj: JsonObject, provider: String, operation: String, vararg keys: String): String =
        keys.firstNotNullOfOrNull { key -> WireDecoder.optionalString(obj, key, provider, operation) }
            ?: WireDecoder.fail(
                provider = provider,
                operation = operation,
                path = "$",
                message = "missing one required field: ${keys.joinToString(" or ")}",
                value = obj,
            )

    fun finishReason(value: String?): FinishReason = when (value) {
        "stop" -> FinishReason.Stop
        "length" -> FinishReason.Length
        "tool-calls", "toolCalls" -> FinishReason.ToolCalls
        "content-filter", "contentFilter" -> FinishReason.ContentFilter
        "error" -> FinishReason.Error
        "tool-approval-requested", "toolApprovalRequested" -> FinishReason.ToolApprovalRequested
        else -> FinishReason.Other
    }

    fun usageFromJson(value: JsonElement?): Usage {
        val obj = value?.jsonObject ?: return Usage()
        val prompt = TypedJsonOps.jsonIntOrNull(obj, "promptTokens") ?: TypedJsonOps.jsonIntOrNull(obj, "inputTokens") ?: 0
        val completion = TypedJsonOps.jsonIntOrNull(obj, "completionTokens") ?: TypedJsonOps.jsonIntOrNull(obj, "outputTokens") ?: 0
        return Usage.of(promptTokens = prompt, completionTokens = completion)
    }

    fun callWarnings(value: JsonElement?): List<CallWarning> =
        (value as? JsonArray).orEmpty().map { warning ->
            val obj = warning.jsonObject
            CallWarning(
                type = obj["type"]?.jsonPrimitive?.contentOrNull ?: "other",
                message = obj["message"]?.jsonPrimitive?.contentOrNull
                    ?: obj["details"]?.jsonPrimitive?.contentOrNull,
                details = warning,
            )
        }

    fun gatewayModelType(value: String?): GatewayModelType? = when (value) {
        "embedding" -> GatewayModelType.Embedding
        "image" -> GatewayModelType.Image
        "language" -> GatewayModelType.Language
        "reranking" -> GatewayModelType.Reranking
        "video" -> GatewayModelType.Video
        else -> null
    }

    fun gatewayCredentialType(value: String?): GatewayCredentialType? = when (value) {
        "byok" -> GatewayCredentialType.Byok
        "system" -> GatewayCredentialType.System
        else -> null
    }

    fun gatewayOrigin(baseUrl: String): String =
        Regex("^(https?://[^/]+)").find(baseUrl)?.groupValues?.get(1) ?: baseUrl.trimEnd('/')

    fun gatewayErrorFromResponse(statusCode: Int, raw: String): GatewayError {
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
                generationId = generationId
            )
        }
    }
}

public open class GatewayError(
    message: String,
    public val statusCode: Int = 500,
    public val type: String = "gateway_error",
    public val generationId: String? = null,
    cause: Throwable? = null,
    public val isRetryable: Boolean = statusCode == 408 || statusCode == 409 || statusCode == 429 || statusCode >= 500,
) : AiSdkException(if (generationId == null) message else "$message [$generationId]", cause)

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
