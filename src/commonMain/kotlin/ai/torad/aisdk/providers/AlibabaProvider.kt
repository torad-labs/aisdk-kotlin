@file:OptIn(ai.torad.aisdk.LowLevelLanguageModelApi::class)

package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import dev.drewhamilton.poko.Poko
import io.ktor.client.HttpClient
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlin.time.Clock

public const val ALIBABA_VERSION: String = "1.0.26"

public typealias AlibabaProviderOptions = AlibabaLanguageModelOptions
public typealias AlibabaVideoProviderOptions = AlibabaVideoModelOptions
public typealias AlibabaUsage = Usage
public typealias AlibabaCacheControl = JsonObject

@Serializable
@Poko
/** @since 0.3.0-beta01 */
public class AlibabaProviderSettings internal constructor(
    /** @since 0.3.0-beta01 */
    public val baseURL: String = "https://dashscope-intl.aliyuncs.com/compatible-mode/v1",
    /** @since 0.3.0-beta01 */
    public val videoBaseURL: String = "https://dashscope-intl.aliyuncs.com",
    // The embedding API uses the DashScope native endpoint, not the
    // OpenAI-compatible one (`compatible-mode/v1`).
    /** @since 0.3.0-beta01 */
    public val embeddingBaseURL: String = "https://dashscope-intl.aliyuncs.com/api/v1",
    /** @since 0.3.0-beta01 */
    public val apiKey: String? = null,
    /** @since 0.3.0-beta01 */
    public val headers: Map<String, String> = emptyMap(),
    /** @since 0.3.0-beta01 */
    public val includeUsage: Boolean = true,
) {
    internal suspend fun alibabaPostJson(
        client: HttpClient,
        url: String,
        body: JsonObject,
        headers: Map<String, String>,
    ): HttpJsonResponse =
        HttpTransport.requestJson(
            client = client,
            url = url,
            method = HttpMethod.Post,
            headers = headers,
            body = body,
            requestBodyValues = body,
            errorMessage = this::alibabaErrorMessage,
        )

    internal suspend fun alibabaGetJson(
        client: HttpClient,
        url: String,
        headers: Map<String, String>,
    ): HttpJsonResponse =
        HttpTransport.requestJson(
            client = client,
            url = url,
            method = HttpMethod.Get,
            headers = headers,
            errorMessage = this::alibabaErrorMessage,
        )

    internal fun alibabaHeaders(callHeaders: Map<String, String>): Map<String, String> =
        ProviderHeaders.build(headers, callHeaders, "ai-sdk/alibaba/$ALIBABA_VERSION") { base ->
            apiKey?.takeIf { it.isNotBlank() }?.let { base[HttpHeaders.Authorization] = "Bearer $it" }
        }

    internal fun alibabaOptions(providerOptions: ProviderOptions): JsonObject =
        JsonAccess.obj(providerOptions.toMap(), "alibaba") ?: JsonObject(emptyMap())

    private fun alibabaErrorMessage(statusCode: Int, parsed: JsonElement?, raw: String): String {
        val obj = parsed as? JsonObject
        val detail = (obj?.get("message") as? JsonPrimitive)?.contentOrNull
            ?: ((obj?.get("error") as? JsonObject)?.get("message") as? JsonPrimitive)?.contentOrNull
            ?: raw.ifBlank { "request failed" }
        return "Alibaba request failed ($statusCode): $detail"
    }
}

/** @since 0.3.0-beta01 */
public class AlibabaProviderSettingsBuilder {
    private var baseURL: String = "https://dashscope-intl.aliyuncs.com/compatible-mode/v1"
    private var videoBaseURL: String = "https://dashscope-intl.aliyuncs.com"
    private var embeddingBaseURL: String = "https://dashscope-intl.aliyuncs.com/api/v1"
    private var apiKey: String? = null
    private var headers: Map<String, String> = emptyMap()
    private var includeUsage: Boolean = true

    /** @since 0.3.0-beta01 */
    public fun baseURL(value: String): AlibabaProviderSettingsBuilder {
        baseURL = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun videoBaseURL(value: String): AlibabaProviderSettingsBuilder {
        videoBaseURL = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun embeddingBaseURL(value: String): AlibabaProviderSettingsBuilder {
        embeddingBaseURL = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun apiKey(value: String?): AlibabaProviderSettingsBuilder {
        apiKey = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun headers(value: Map<String, String>): AlibabaProviderSettingsBuilder {
        headers = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun includeUsage(value: Boolean): AlibabaProviderSettingsBuilder {
        includeUsage = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun build(): AlibabaProviderSettings =
        AlibabaProviderSettings(
            baseURL = baseURL,
            videoBaseURL = videoBaseURL,
            embeddingBaseURL = embeddingBaseURL,
            apiKey = apiKey,
            headers = headers,
            includeUsage = includeUsage,
        )
}

/** @since 0.3.0-beta01 */
public fun AlibabaProviderSettings(
    block: AlibabaProviderSettingsBuilder.() -> Unit = {},
): AlibabaProviderSettings =
    AlibabaProviderSettingsBuilder().apply(block).build()

/** Provider options for [AlibabaProvider.embeddingModel] — pass under the
  * @since 0.3.0-beta01
 *  `"alibaba"` key in [EmbeddingModelCallParams.providerOptions]. */
@Serializable
@Poko
public class AlibabaEmbeddingModelOptions internal constructor(
    /**
     * `"query"` or `"document"` (asymmetric retrieval); defaults to `document`.
     * @since 0.3.0-beta01
     */
    public val textType: String? = null,
    /**
     * Output vector dimension; defaults to 1024 (`text-embedding-v4` also allows 1536/2048).
     * @since 0.3.0-beta01
     */
    public val dimension: Int? = null,
    /** `"dense"` (default), `"sparse"`, or `"dense&sparse"`. `sparse` is rejected —
      * @since 0.3.0-beta01
     *  the embedding interface requires dense float arrays. */
    public val outputType: String? = null,
)

/** @since 0.3.0-beta01 */
public class AlibabaEmbeddingModelOptionsBuilder {
    private var textType: String? = null
    private var dimension: Int? = null
    private var outputType: String? = null

    /** @since 0.3.0-beta01 */
    public fun textType(value: String?): AlibabaEmbeddingModelOptionsBuilder {
        textType = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun dimension(value: Int?): AlibabaEmbeddingModelOptionsBuilder {
        dimension = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun outputType(value: String?): AlibabaEmbeddingModelOptionsBuilder {
        outputType = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun build(): AlibabaEmbeddingModelOptions =
        AlibabaEmbeddingModelOptions(
            textType = textType,
            dimension = dimension,
            outputType = outputType,
        )
}

/** @since 0.3.0-beta01 */
public fun AlibabaEmbeddingModelOptions(
    block: AlibabaEmbeddingModelOptionsBuilder.() -> Unit = {},
): AlibabaEmbeddingModelOptions =
    AlibabaEmbeddingModelOptionsBuilder().apply(block).build()

@Serializable
@Poko
/** @since 0.3.0-beta01 */
public class AlibabaLanguageModelOptions internal constructor(
    /** @since 0.3.0-beta01 */
    public val enableThinking: Boolean? = null,
    /** @since 0.3.0-beta01 */
    public val thinkingBudget: Int? = null,
    /** @since 0.3.0-beta01 */
    public val parallelToolCalls: Boolean? = null,
)

/** @since 0.3.0-beta01 */
public class AlibabaLanguageModelOptionsBuilder {
    private var enableThinking: Boolean? = null
    private var thinkingBudget: Int? = null
    private var parallelToolCalls: Boolean? = null

    /** @since 0.3.0-beta01 */
    public fun enableThinking(value: Boolean?): AlibabaLanguageModelOptionsBuilder {
        enableThinking = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun thinkingBudget(value: Int?): AlibabaLanguageModelOptionsBuilder {
        thinkingBudget = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun parallelToolCalls(value: Boolean?): AlibabaLanguageModelOptionsBuilder {
        parallelToolCalls = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun build(): AlibabaLanguageModelOptions =
        AlibabaLanguageModelOptions(
            enableThinking = enableThinking,
            thinkingBudget = thinkingBudget,
            parallelToolCalls = parallelToolCalls,
        )
}

/** @since 0.3.0-beta01 */
public fun AlibabaLanguageModelOptions(
    block: AlibabaLanguageModelOptionsBuilder.() -> Unit = {},
): AlibabaLanguageModelOptions =
    AlibabaLanguageModelOptionsBuilder().apply(block).build()

@Serializable
@Poko
/** @since 0.3.0-beta01 */
public class AlibabaVideoModelOptions internal constructor(
    /** @since 0.3.0-beta01 */
    public val negativePrompt: String? = null,
    /** @since 0.3.0-beta01 */
    public val audioUrl: String? = null,
    /** @since 0.3.0-beta01 */
    public val promptExtend: Boolean? = null,
    /** @since 0.3.0-beta01 */
    public val shotType: String? = null,
    /** @since 0.3.0-beta01 */
    public val watermark: Boolean? = null,
    /** @since 0.3.0-beta01 */
    public val audio: Boolean? = null,
    /** @since 0.3.0-beta01 */
    public val referenceUrls: List<String>? = null,
    /** @since 0.3.0-beta01 */
    public val pollIntervalMs: Long? = null,
    /** @since 0.3.0-beta01 */
    public val pollTimeoutMs: Long? = null,
)

/** @since 0.3.0-beta01 */
public class AlibabaVideoModelOptionsBuilder {
    private var negativePrompt: String? = null
    private var audioUrl: String? = null
    private var promptExtend: Boolean? = null
    private var shotType: String? = null
    private var watermark: Boolean? = null
    private var audio: Boolean? = null
    private var referenceUrls: List<String>? = null
    private var pollIntervalMs: Long? = null
    private var pollTimeoutMs: Long? = null

    /** @since 0.3.0-beta01 */
    public fun negativePrompt(value: String?): AlibabaVideoModelOptionsBuilder {
        negativePrompt = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun audioUrl(value: String?): AlibabaVideoModelOptionsBuilder {
        audioUrl = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun promptExtend(value: Boolean?): AlibabaVideoModelOptionsBuilder {
        promptExtend = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun shotType(value: String?): AlibabaVideoModelOptionsBuilder {
        shotType = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun watermark(value: Boolean?): AlibabaVideoModelOptionsBuilder {
        watermark = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun audio(value: Boolean?): AlibabaVideoModelOptionsBuilder {
        audio = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun referenceUrls(value: List<String>?): AlibabaVideoModelOptionsBuilder {
        referenceUrls = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun pollIntervalMs(value: Long?): AlibabaVideoModelOptionsBuilder {
        pollIntervalMs = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun pollTimeoutMs(value: Long?): AlibabaVideoModelOptionsBuilder {
        pollTimeoutMs = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun build(): AlibabaVideoModelOptions =
        AlibabaVideoModelOptions(
            negativePrompt = negativePrompt,
            audioUrl = audioUrl,
            promptExtend = promptExtend,
            shotType = shotType,
            watermark = watermark,
            audio = audio,
            referenceUrls = referenceUrls,
            pollIntervalMs = pollIntervalMs,
            pollTimeoutMs = pollTimeoutMs,
        )
}

/** @since 0.3.0-beta01 */
public fun AlibabaVideoModelOptions(
    block: AlibabaVideoModelOptionsBuilder.() -> Unit = {},
): AlibabaVideoModelOptions =
    AlibabaVideoModelOptionsBuilder().apply(block).build()

/** @since 0.3.0-beta01 */
public class AlibabaProvider(
    private val client: HttpClient,
    /** @since 0.3.0-beta01 */
    public val settings: AlibabaProviderSettings,
) : Provider {
    override val providerId: String = "alibaba"
    private val chatProvider: OpenAICompatibleProvider =
        OpenAICompatible(
            client,
            OpenAICompatibleProviderSettings {
                name("alibaba")
                baseUrl(settings.baseURL.trimEnd('/'))
                apiKey(settings.apiKey)
                headers(settings.headers)
                includeUsage(settings.includeUsage)
                supportedUrls(mapOf("image/*" to listOf("^https?://.*$")))
                userAgentSuffix("ai-sdk/alibaba/$ALIBABA_VERSION")
                providerOptionsName("alibaba")
            },
        )

    public operator fun invoke(modelId: ModelId): LanguageModel = languageModel(modelId.value)
    /** @since 0.3.0-beta01 */
    public fun chatModel(modelId: ModelId): LanguageModel = languageModel(modelId.value)
    /** @since 0.3.0-beta01 */
    public fun video(modelId: ModelId): VideoModel = videoModel(modelId.value)

    override fun languageModel(modelId: String): LanguageModel = AlibabaChatLanguageModel(chatProvider.chatModel(modelId))
    override fun videoModel(modelId: String): VideoModel = AlibabaVideoModel(client, settings, modelId)
    override fun imageModel(modelId: String): ImageModel = throw NoSuchModelError(providerId, "imageModel", modelId)
    override fun embeddingModel(modelId: String): EmbeddingModel = AlibabaEmbeddingModel(client, settings, modelId)
}

/**
 * PascalCase factory — mirrors `OpenAI(client, settings)`.
 * @since 0.3.0-beta01
 */
public fun Alibaba(
    client: HttpClient,
    settings: AlibabaProviderSettings = AlibabaProviderSettings(),
): AlibabaProvider = AlibabaProvider(client, settings)

private class AlibabaChatLanguageModel(
    private val delegate: LanguageModel,
) : LanguageModel {
    override val modelId: String = delegate.modelId
    override val provider: String = "alibaba.chat"
    override val supportedUrls: Map<String, List<String>> = delegate.supportedUrls

    override suspend fun generate(params: LanguageModelCallParams): LanguageModelResult {
        val result = delegate.generate(params.toBuilder().providerOptions(transformAlibabaChatOptions(params.providerOptions)).build())
        return LanguageModelResult(
            text = result.text,
            toolCalls = result.toolCalls,
            finishReason = result.finishReason,
            usage = alibabaUsage(result.usage),
            providerMetadata = result.providerMetadata,
            content = result.content,
            rawFinishReason = result.rawFinishReason,
            warnings = result.warnings,
            request = result.request,
            response = result.response,
        )
    }

    override fun stream(params: LanguageModelCallParams): Flow<StreamEvent> = flow {
        delegate.stream(params.toBuilder().providerOptions(transformAlibabaChatOptions(params.providerOptions)).build()).collect { event ->
            emit(
                if (event is StreamEvent.Finish) {
                    StreamEvent.Finish(
                        totalSteps = event.totalSteps,
                        finishReason = event.finishReason,
                        usage = alibabaUsage(event.usage),
                        providerMetadata = event.providerMetadata,
                        rawFinishReason = event.rawFinishReason,
                    )
                } else {
                    event
                },
            )
        }
    }

    private fun transformAlibabaChatOptions(providerOptions: ProviderOptions): ProviderOptions {
        val map = providerOptions.toMap()
        val options = JsonAccess.obj(map, "alibaba") ?: return providerOptions
        val transformed = buildJsonObject {
            options["enableThinking"]?.let { put("enable_thinking", it) }
            options["thinkingBudget"]?.let { put("thinking_budget", it) }
            options["parallelToolCalls"]?.let { put("parallel_tool_calls", it) }
            for ((key, value) in options) {
                if (key !in setOf("enableThinking", "thinkingBudget", "parallelToolCalls")) put(key, value)
            }
        }
        return ProviderOptions.Raw(JsonObject(map + ("alibaba" to (transformed as JsonElement))))
    }

    private fun alibabaUsage(usage: Usage): Usage {
        val raw = (usage.raw as? JsonObject)
        val cacheWriteElement = (raw?.get("prompt_tokens_details") as? JsonObject)?.get("cache_creation_input_tokens")
        val cacheWrite = (cacheWriteElement as? JsonPrimitive)?.intOrNull ?: 0
        val inputTotal = usage.inputTokens.total
        val cacheRead = usage.inputTokens.cacheRead
        val reasoning = usage.outputTokens.reasoning
        val outputTotal = usage.outputTokens.total
        return Usage(
            inputTokens = Usage.InputTokenBreakdown(
                total = usage.inputTokens.total,
                cacheWrite = cacheWrite,
                cacheRead = usage.inputTokens.cacheRead,
                noCache = (inputTotal - cacheRead - cacheWrite).coerceAtLeast(0),
            ),
            outputTokens = Usage.OutputTokenBreakdown(
                total = usage.outputTokens.total,
                text = (outputTotal - reasoning).coerceAtLeast(0),
                reasoning = usage.outputTokens.reasoning,
            ),
            raw = usage.raw,
        )
    }
}

private class AlibabaVideoModel(
    private val client: HttpClient,
    private val settings: AlibabaProviderSettings,
    override val modelId: String,
    private val clock: Clock = Clock.System,
) : VideoModel {
    override val provider: String = "alibaba.video"

    override suspend fun generate(params: VideoGenerationParams): VideoModelResult {
        params.abortSignal.throwIfAborted()
        val options = settings.alibabaOptions(params.providerOptions)
        val mode = alibabaVideoMode(modelId)
        val warnings = mutableListOf<CallWarning>()
        val body = buildJsonObject {
            put("model", JsonPrimitive(modelId))
            put("input", alibabaVideoInput(mode, params, options))
            put("parameters", alibabaVideoParameters(mode, params, options, warnings))
        }
        val create = settings.alibabaPostJson(
            client = client,
            url = "${settings.videoBaseURL.trimEnd('/')}/api/v1/services/aigc/video-generation/video-synthesis",
            body = body,
            headers = settings.alibabaHeaders(params.headers + mapOf("X-DashScope-Async" to "enable")),
        )
        val createOutput = JsonAccess.obj(create.value.jsonObject, "output")
        val taskId = (createOutput?.get("task_id") as? JsonPrimitive)?.contentOrNull
            ?: throw NoVideoGeneratedError("No task_id returned from Alibaba API. Response: ${create.value}")

        val pollIntervalMs = (options["pollIntervalMs"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 5_000L
        val pollTimeoutMs = (options["pollTimeoutMs"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 600_000L
        val started = clock.now().toEpochMilliseconds()
        var headers = create.headers
        while (true) {
            params.abortSignal.throwIfAborted()
            if (pollIntervalMs > 0) delay(pollIntervalMs)
            if (clock.now().toEpochMilliseconds() - started > pollTimeoutMs) {
                throw NoVideoGeneratedError("Video generation timed out after ${pollTimeoutMs}ms")
            }
            val status = settings.alibabaGetJson(
                client = client,
                url = "${settings.videoBaseURL.trimEnd('/')}/api/v1/tasks/$taskId",
                headers = settings.alibabaHeaders(params.headers),
            )
            headers = status.headers
            val output = (JsonAccess.obj(status.value.jsonObject, "output")) ?: JsonObject(emptyMap())
            when (val taskStatus = (output["task_status"] as? JsonPrimitive)?.contentOrNull) {
                "SUCCEEDED" -> {
                    val videoUrl = (output["video_url"] as? JsonPrimitive)?.contentOrNull
                        ?: throw NoVideoGeneratedError("No video URL in Alibaba response. Task ID: $taskId")
                    return VideoModelResult(
                        videos = listOf(GeneratedFile(mediaType = "video/mp4", base64 = "", url = videoUrl)),
                        warnings = warnings,
                        response = LanguageModelResponseMetadata(modelId = modelId, headers = headers, body = status.value),
                        providerMetadata = ProviderMetadata.Raw(JsonObject(mapOf("alibaba" to alibabaVideoMetadata(taskId, videoUrl, status.value.jsonObject)))),
                    )
                }
                "FAILED", "CANCELED" -> {
                    val detail = (output["message"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                    throw NoVideoGeneratedError("Video generation ${taskStatus.lowercase()}. Task ID: $taskId. $detail")
                }
                "PENDING", "RUNNING", "UNKNOWN", null -> Unit
                else -> throw NoVideoGeneratedError("Unknown Alibaba task status: $taskStatus")
            }
        }
    }

    private val alibabaVideoHandledOptions = setOf(
        "negativePrompt",
        "audioUrl",
        "promptExtend",
        "shotType",
        "watermark",
        "audio",
        "referenceUrls",
        "pollIntervalMs",
        "pollTimeoutMs",
    )

    private fun alibabaVideoMode(modelId: String): AlibabaVideoMode = when {
        modelId.contains("-i2v") -> AlibabaVideoMode.ImageToVideo
        modelId.contains("-r2v") -> AlibabaVideoMode.ReferenceToVideo
        else -> AlibabaVideoMode.TextToVideo
    }

    private fun alibabaVideoInput(mode: AlibabaVideoMode, params: VideoGenerationParams, options: JsonObject): JsonObject = buildJsonObject {
        params.prompt.takeIf { it.isNotBlank() }?.let { put("prompt", JsonPrimitive(it)) }
        options["negativePrompt"]?.let { put("negative_prompt", it) }
        options["audioUrl"]?.let { put("audio_url", it) }
        if (mode == AlibabaVideoMode.ImageToVideo) {
            params.image?.let {
                // img_url expects an HTTP URL; emitting raw base64 here (the old `?: it.base64`)
                // sends binary into a URL field and the i2v API rejects it. Fail explicitly.
                val url = it.url ?: throw UnsupportedFunctionalityError(
                    "imageToVideo-base64",
                    "Alibaba i2v requires an image URL; inline base64 is not supported.",
                )
                put("img_url", JsonPrimitive(url))
            }
        }
        if (mode == AlibabaVideoMode.ReferenceToVideo) {
            options["referenceUrls"]?.let { put("reference_urls", it) }
        }
    }

    private fun alibabaVideoParameters(
        mode: AlibabaVideoMode,
        params: VideoGenerationParams,
        options: JsonObject,
        warnings: MutableList<CallWarning>,
    ): JsonObject = buildJsonObject {
        params.durationSeconds?.let { put("duration", JsonPrimitive(it)) }
        params.seed?.let { put("seed", JsonPrimitive(it)) }
        params.resolution?.let { resolution ->
            if (mode == AlibabaVideoMode.ImageToVideo) {
                put("resolution", JsonPrimitive(alibabaI2VResolution(resolution)))
            } else {
                put("size", JsonPrimitive(resolution.replace('x', '*')))
            }
        }
        options["promptExtend"]?.let { put("prompt_extend", it) }
        options["shotType"]?.let { put("shot_type", it) }
        options["watermark"]?.let { put("watermark", it) }
        options["audio"]?.let { put("audio", it) }
        if (params.aspectRatio != null) warnings += CallWarning("unsupported", "Alibaba video models use explicit size/resolution dimensions. Use the resolution option or providerOptions.alibaba for size control.")
        if (params.fps != null) warnings += CallWarning("unsupported", "Alibaba video models do not support custom FPS.")
        if (params.n > 1) warnings += CallWarning("unsupported", "Alibaba video models only support generating 1 video per call.")
        alibabaVideoPassthroughOptions(this, options)
    }

    private fun alibabaVideoPassthroughOptions(builder: kotlinx.serialization.json.JsonObjectBuilder, options: JsonObject) {
        for ((key, value) in options) {
            if (key !in alibabaVideoHandledOptions) builder.put(key, value)
        }
    }

    private fun alibabaI2VResolution(value: String): String = when (value) {
        "1280x720", "720x1280", "960x960", "1088x832", "832x1088" -> "720P"
        "1920x1080", "1080x1920", "1440x1440", "1632x1248", "1248x1632" -> "1080P"
        "832x480", "480x832", "624x624" -> "480P"
        else -> value
    }

    private fun alibabaVideoMetadata(taskId: String, videoUrl: String, value: JsonObject): JsonObject = buildJsonObject {
        val output = (JsonAccess.obj(value, "output")) ?: JsonObject(emptyMap())
        put("taskId", JsonPrimitive(taskId))
        put("videoUrl", JsonPrimitive(videoUrl))
        (output["actual_prompt"] as? JsonPrimitive)?.contentOrNull?.let { put("actualPrompt", JsonPrimitive(it)) }
        (JsonAccess.obj(value, "usage"))?.let { usage ->
            put("usage", buildJsonObject {
                usage["duration"]?.let { put("duration", it) }
                usage["output_video_duration"]?.let { put("outputVideoDuration", it) }
                usage["SR"]?.let { put("resolution", it) }
                usage["size"]?.let { put("size", it) }
            })
        }
    }
}

internal enum class AlibabaVideoMode { TextToVideo, ImageToVideo, ReferenceToVideo }

private const val ALIBABA_MAX_EMBEDDINGS_PER_CALL = 10

/** DashScope text-embedding (text-embedding-v3/v4) model. Routes to the native
 *  DashScope endpoint (`embeddingBaseURL`), not the OpenAI-compatible chat base. */
private class AlibabaEmbeddingModel(
    private val client: HttpClient,
    private val settings: AlibabaProviderSettings,
    override val modelId: String,
) : EmbeddingModel {
    override val provider: String = "alibaba.embedding"
    override val maxEmbeddingsPerCall: Int = ALIBABA_MAX_EMBEDDINGS_PER_CALL
    override val supportsParallelCalls: Boolean = false

    override suspend fun embed(params: EmbeddingModelCallParams): EmbeddingModelResult {
        if (params.values.size > ALIBABA_MAX_EMBEDDINGS_PER_CALL) {
            throw TooManyEmbeddingValuesForCallError(
                provider = provider,
                modelId = modelId,
                maxEmbeddingsPerCall = ALIBABA_MAX_EMBEDDINGS_PER_CALL,
                values = params.values,
            )
        }
        val options = settings.alibabaOptions(params.providerOptions)
        if ((options["outputType"] as? JsonPrimitive)?.contentOrNull == "sparse") {
            throw UnsupportedFunctionalityError(
                "Alibaba embedding outputType 'sparse'",
                "Alibaba embedding outputType 'sparse' is not supported because embeddings require " +
                    "dense number arrays. Use 'dense' or 'dense&sparse' instead.",
            )
        }
        val body = buildJsonObject {
            put("model", JsonPrimitive(modelId))
            put("input", buildJsonObject { put("texts", JsonArray(params.values.map(::JsonPrimitive))) })
            put(
                "parameters",
                buildJsonObject {
                    options["textType"]?.let { put("text_type", it) }
                    options["dimension"]?.let { put("dimension", it) }
                    options["outputType"]?.let { put("output_type", it) }
                },
            )
        }
        val response = settings.alibabaPostJson(
            client = client,
            url = "${settings.embeddingBaseURL.trimEnd('/')}/services/embeddings/text-embedding/text-embedding",
            body = body,
            headers = settings.alibabaHeaders(params.headers),
        )
        val value = response.value.jsonObject
        val output = JsonAccess.obj(value, "output")
        val items = (output?.get("embeddings") as? JsonArray).orEmpty()
            .sortedBy { ((it as? JsonObject)?.get("text_index") as? JsonPrimitive)?.intOrNull ?: Int.MAX_VALUE }
        val usage = JsonAccess.obj(value, "usage")
        val usageTokens = (usage?.get("total_tokens") as? JsonPrimitive)?.intOrNull ?: 0
        return EmbeddingModelResult(
            embeddings = items.map { item ->
                // Decode each element strictly (like Cohere/Google) — the old `?: 0f` silently
                // substituted 0f for a null/non-numeric element, corrupting the embedding vector.
                val row = ((item as? JsonObject)?.get("embedding") as? JsonArray).orEmpty()
                row.map { WireDecoder.embeddingFloat(it, "alibaba") }
            },
            usage = EmbeddingUsage(
                tokens = usageTokens,
                raw = value["usage"],
            ),
            request = LanguageModelRequestMetadata(body = body),
            response = LanguageModelResponseMetadata(headers = response.headers, body = response.value),
        )
    }
}
