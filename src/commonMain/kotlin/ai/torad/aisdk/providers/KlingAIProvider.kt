package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import ai.torad.aisdk.ProviderMetadata
import dev.drewhamilton.poko.Poko
import io.ktor.client.HttpClient
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlin.math.ceil
import kotlin.time.Clock

public const val KLINGAI_VERSION: String = "3.0.18"

public typealias KlingAIVideoProviderOptions = KlingAIVideoModelOptions

@Serializable
@Poko
/** @since 0.3.0-beta01 */
public class KlingAIProviderSettings internal constructor(
    /** @since 0.3.0-beta01 */
    public val accessKey: String? = null,
    /** @since 0.3.0-beta01 */
    public val secretKey: String? = null,
    /** @since 0.3.0-beta01 */
    public val baseURL: String = "https://api-singapore.klingai.com",
    /** @since 0.3.0-beta01 */
    public val headers: Map<String, String> = emptyMap(),
) {
    internal fun klingAIHeaders(
        callHeaders: Map<String, String>,
        clock: Clock = Clock.System,
    ): Map<String, String> {
        val token = generateKlingAIAuthToken(
            accessKey = accessKey ?: throw LoadAPIKeyError("KlingAI access key is required."),
            secretKey = secretKey ?: throw LoadAPIKeyError("KlingAI secret key is required."),
            clock = clock,
        )
        return ProviderHeaders.build(headers, callHeaders, "ai-sdk/klingai/$KLINGAI_VERSION") { base ->
            base[HttpHeaders.Authorization] = "Bearer $token"
        }
    }

    private fun generateKlingAIAuthToken(accessKey: String, secretKey: String, clock: Clock = Clock.System): String {
        val now = clock.now().epochSeconds
        val header = buildJsonObject {
            put("alg", JsonPrimitive("HS256"))
            put("typ", JsonPrimitive("JWT"))
        }
        val payload = buildJsonObject {
            put("iss", JsonPrimitive(accessKey))
            put("exp", JsonPrimitive(now + 1800))
            put("nbf", JsonPrimitive(now - 5))
        }
        val signingInput = "${base64Url(aiSdkOutputJson.encodeToString(JsonElement.serializer(), header).encodeToByteArray())}." +
            base64Url(aiSdkOutputJson.encodeToString(JsonElement.serializer(), payload).encodeToByteArray())
        val signature = CryptoPrimitives.hmacSha256(secretKey.encodeToByteArray(), signingInput.encodeToByteArray())
        return "$signingInput.${base64Url(signature)}"
    }

    private fun base64Url(bytes: ByteArray): String =
        Base64Codec.encode(bytes).replace('+', '-').replace('/', '_').trimEnd('=')
}

/** @since 0.3.0-beta01 */
public class KlingAIProviderSettingsBuilder {
    private var accessKey: String? = null
    private var secretKey: String? = null
    private var baseURL: String = "https://api-singapore.klingai.com"
    private var headers: Map<String, String> = emptyMap()

    /** @since 0.3.0-beta01 */
    public fun accessKey(value: String?): KlingAIProviderSettingsBuilder {
        accessKey = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun secretKey(value: String?): KlingAIProviderSettingsBuilder {
        secretKey = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun baseURL(value: String): KlingAIProviderSettingsBuilder {
        baseURL = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun headers(value: Map<String, String>): KlingAIProviderSettingsBuilder {
        headers = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun build(): KlingAIProviderSettings =
        KlingAIProviderSettings(
            accessKey = accessKey,
            secretKey = secretKey,
            baseURL = baseURL,
            headers = headers,
        )
}

/** @since 0.3.0-beta01 */
public fun KlingAIProviderSettings(
    block: KlingAIProviderSettingsBuilder.() -> Unit = {},
): KlingAIProviderSettings =
    KlingAIProviderSettingsBuilder().apply(block).build()

@Serializable
@Poko
/** @since 0.3.0-beta01 */
public class KlingAIVideoModelOptions internal constructor(
    /** @since 0.3.0-beta01 */
    public val mode: String? = null,
    /** @since 0.3.0-beta01 */
    public val pollIntervalMs: Long? = null,
    /** @since 0.3.0-beta01 */
    public val pollTimeoutMs: Long? = null,
    /** @since 0.3.0-beta01 */
    public val negativePrompt: String? = null,
    /** @since 0.3.0-beta01 */
    public val sound: String? = null,
    /** @since 0.3.0-beta01 */
    public val cfgScale: Float? = null,
    /** @since 0.3.0-beta01 */
    public val cameraControl: JsonObject? = null,
    /** @since 0.3.0-beta01 */
    public val imageTail: String? = null,
    /** @since 0.3.0-beta01 */
    public val staticMask: String? = null,
    /** @since 0.3.0-beta01 */
    public val dynamicMasks: JsonArray? = null,
    /** @since 0.3.0-beta01 */
    public val multiShot: Boolean? = null,
    /** @since 0.3.0-beta01 */
    public val shotType: String? = null,
    /** @since 0.3.0-beta01 */
    public val multiPrompt: JsonArray? = null,
    /** @since 0.3.0-beta01 */
    public val elementList: JsonArray? = null,
    /** @since 0.3.0-beta01 */
    public val voiceList: JsonArray? = null,
    /** @since 0.3.0-beta01 */
    public val watermarkEnabled: Boolean? = null,
    /** @since 0.3.0-beta01 */
    public val videoUrl: String? = null,
    /** @since 0.3.0-beta01 */
    public val characterOrientation: String? = null,
    /** @since 0.3.0-beta01 */
    public val keepOriginalSound: String? = null,
)

/** @since 0.3.0-beta01 */
public class KlingAIVideoModelOptionsBuilder {
    private var mode: String? = null
    private var pollIntervalMs: Long? = null
    private var pollTimeoutMs: Long? = null
    private var negativePrompt: String? = null
    private var sound: String? = null
    private var cfgScale: Float? = null
    private var cameraControl: JsonObject? = null
    private var imageTail: String? = null
    private var staticMask: String? = null
    private var dynamicMasks: JsonArray? = null
    private var multiShot: Boolean? = null
    private var shotType: String? = null
    private var multiPrompt: JsonArray? = null
    private var elementList: JsonArray? = null
    private var voiceList: JsonArray? = null
    private var watermarkEnabled: Boolean? = null
    private var videoUrl: String? = null
    private var characterOrientation: String? = null
    private var keepOriginalSound: String? = null

    /** @since 0.3.0-beta01 */
    public fun mode(value: String?): KlingAIVideoModelOptionsBuilder {
        mode = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun pollIntervalMs(value: Long?): KlingAIVideoModelOptionsBuilder {
        pollIntervalMs = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun pollTimeoutMs(value: Long?): KlingAIVideoModelOptionsBuilder {
        pollTimeoutMs = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun negativePrompt(value: String?): KlingAIVideoModelOptionsBuilder {
        negativePrompt = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun sound(value: String?): KlingAIVideoModelOptionsBuilder {
        sound = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun cfgScale(value: Float?): KlingAIVideoModelOptionsBuilder {
        cfgScale = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun cameraControl(value: JsonObject?): KlingAIVideoModelOptionsBuilder {
        cameraControl = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun imageTail(value: String?): KlingAIVideoModelOptionsBuilder {
        imageTail = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun staticMask(value: String?): KlingAIVideoModelOptionsBuilder {
        staticMask = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun dynamicMasks(value: JsonArray?): KlingAIVideoModelOptionsBuilder {
        dynamicMasks = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun multiShot(value: Boolean?): KlingAIVideoModelOptionsBuilder {
        multiShot = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun shotType(value: String?): KlingAIVideoModelOptionsBuilder {
        shotType = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun multiPrompt(value: JsonArray?): KlingAIVideoModelOptionsBuilder {
        multiPrompt = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun elementList(value: JsonArray?): KlingAIVideoModelOptionsBuilder {
        elementList = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun voiceList(value: JsonArray?): KlingAIVideoModelOptionsBuilder {
        voiceList = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun watermarkEnabled(value: Boolean?): KlingAIVideoModelOptionsBuilder {
        watermarkEnabled = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun videoUrl(value: String?): KlingAIVideoModelOptionsBuilder {
        videoUrl = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun characterOrientation(value: String?): KlingAIVideoModelOptionsBuilder {
        characterOrientation = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun keepOriginalSound(value: String?): KlingAIVideoModelOptionsBuilder {
        keepOriginalSound = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun build(): KlingAIVideoModelOptions =
        KlingAIVideoModelOptions(
            mode = mode,
            pollIntervalMs = pollIntervalMs,
            pollTimeoutMs = pollTimeoutMs,
            negativePrompt = negativePrompt,
            sound = sound,
            cfgScale = cfgScale,
            cameraControl = cameraControl,
            imageTail = imageTail,
            staticMask = staticMask,
            dynamicMasks = dynamicMasks,
            multiShot = multiShot,
            shotType = shotType,
            multiPrompt = multiPrompt,
            elementList = elementList,
            voiceList = voiceList,
            watermarkEnabled = watermarkEnabled,
            videoUrl = videoUrl,
            characterOrientation = characterOrientation,
            keepOriginalSound = keepOriginalSound,
        )
}

/** @since 0.3.0-beta01 */
public fun KlingAIVideoModelOptions(
    block: KlingAIVideoModelOptionsBuilder.() -> Unit = {},
): KlingAIVideoModelOptions =
    KlingAIVideoModelOptionsBuilder().apply(block).build()

/** @since 0.3.0-beta01 */
public class KlingAIProvider(
    private val client: HttpClient,
    /** @since 0.3.0-beta01 */
    public val settings: KlingAIProviderSettings,
) : Provider {
    override val providerId: String = "klingai"

    /** @since 0.3.0-beta01 */
    public fun video(modelId: ModelId): VideoModel = videoModel(modelId.value)

    override fun videoModel(modelId: String): VideoModel = KlingAIVideoModel(client, settings, modelId)
    override fun languageModel(modelId: String): LanguageModel = throw NoSuchModelError(
        providerId,
        "languageModel",
        modelId
    )
    override fun embeddingModel(modelId: String): EmbeddingModel = throw NoSuchModelError(
        providerId,
        "embeddingModel",
        modelId
    )
    override fun imageModel(modelId: String): ImageModel = throw NoSuchModelError(providerId, "imageModel", modelId)
}

/**
 * PascalCase factory — mirrors the OpenAI(...) reference pattern.
 * @since 0.3.0-beta01
 */
public fun KlingAI(
    client: HttpClient,
    settings: KlingAIProviderSettings = KlingAIProviderSettings(),
): KlingAIProvider = KlingAIProvider(client, settings)

private class KlingAIVideoModel(
    private val client: HttpClient,
    private val settings: KlingAIProviderSettings,
    override val modelId: String,
    private val clock: Clock = Clock.System,
) : VideoModel {
    override val provider: String = "klingai.video"
    override val maxVideosPerCall: Int = 1

    override suspend fun generate(params: VideoGenerationParams): VideoModelResult {
        params.abortSignal.throwIfAborted()
        val options = klingAIOptions(params.providerOptions)
        val warnings = mutableListOf<CallWarning>()
        val mode = klingAIDetectMode(modelId)
        val body = when (mode) {
            KlingAIVideoMode.TextToVideo -> klingAIT2VBody(modelId, params, options, warnings)
            KlingAIVideoMode.ImageToVideo -> klingAII2VBody(modelId, params, options, warnings)
            KlingAIVideoMode.MotionControl -> klingAIMotionControlBody(modelId, params, options, warnings)
        }
        warnings += klingAIStandardWarnings(params)

        val endpoint = when (mode) {
            KlingAIVideoMode.TextToVideo -> "/v1/videos/text2video"
            KlingAIVideoMode.ImageToVideo -> "/v1/videos/image2video"
            KlingAIVideoMode.MotionControl -> "/v1/videos/motion-control"
        }
        val create = klingAIRequestJson(
            client = client,
            method = HttpMethod.Post,
            url = "${settings.baseURL.trimEnd('/')}$endpoint",
            headers = settings.klingAIHeaders(params.headers, clock),
            body = body,
            abortSignal = params.abortSignal,
        )
        val data = JsonAccess.obj(create.value.jsonObject, "data")
        val taskId = (data?.get("task_id") as? JsonPrimitive)?.contentOrNull
            ?: throw InvalidResponseDataError(create.value, "No task_id returned from KlingAI API. Response: ${create.value}")

        val pollIntervalMs = (options["pollIntervalMs"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 5_000L
        val pollTimeoutMs = ((options["pollTimeoutMs"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 600_000L)
            .coerceAtLeast(1L)
        val maxPollAttempts = (options["maxPollAttempts"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
            ?: ceil(pollTimeoutMs.toDouble() / pollIntervalMs.coerceAtLeast(1L).toDouble()).toInt().coerceAtLeast(1)
        val started = clock.now().toEpochMilliseconds()
        var headers = create.headers
        repeat(maxPollAttempts.coerceAtLeast(1)) {
            params.abortSignal.throwIfAborted()
            if (pollIntervalMs > 0) delay(pollIntervalMs)
            if (clock.now().toEpochMilliseconds() - started > pollTimeoutMs) {
                throw NoVideoGeneratedError("Video generation timed out after ${pollTimeoutMs}ms")
            }
            val status = klingAIRequestJson(
                client = client,
                method = HttpMethod.Get,
                url = "${settings.baseURL.trimEnd('/')}$endpoint/$taskId",
                headers = settings.klingAIHeaders(params.headers, clock),
                abortSignal = params.abortSignal,
            )
            headers = status.headers
            val data = (JsonAccess.obj(status.value.jsonObject, "data")) ?: JsonObject(emptyMap())
            when (val taskStatus = (data["task_status"] as? JsonPrimitive)?.contentOrNull) {
                "succeed" -> return klingAISuccessResult(taskId, data, headers, warnings)
                "failed" -> {
                    val statusMsg = (data["task_status_msg"] as? JsonPrimitive)?.contentOrNull ?: "Unknown error"
                    throw NoVideoGeneratedError("Video generation failed: $statusMsg")
                }
                "submitted", "processing", null -> Unit
                else -> throw InvalidResponseDataError(data, "Unknown KlingAI task status: $taskStatus")
            }
        }
        throw NoVideoGeneratedError("Video generation timed out after $maxPollAttempts poll attempts")
    }

    private fun klingAISuccessResult(
        taskId: String,
        data: JsonObject,
        headers: Map<String, String>,
        warnings: List<CallWarning>,
    ): VideoModelResult {
        val videos = ((JsonAccess.obj(data, "task_result"))?.get("videos") as? JsonArray).orEmpty()
        val generated = videos.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val url = (obj["url"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            GeneratedFile(
                mediaType = "video/mp4",
                base64 = "",
                url = url,
                providerMetadata = ProviderMetadata.Raw(JsonObject(mapOf("klingai" to klingAIVideoMetadata(obj)))),
            )
        }
        if (generated.isEmpty()) throw NoVideoGeneratedError("No valid video URLs in KlingAI response")
        return VideoModelResult(
            videos = generated,
            warnings = warnings,
            response = LanguageModelResponseMetadata(modelId = modelId, headers = headers),
            providerMetadata = ProviderMetadata.Raw(
                JsonObject(
                    mapOf(
                        "klingai" to buildJsonObject {
                            put("taskId", JsonPrimitive(taskId))
                            put("videos", JsonArray(videos.mapNotNull { (it as? JsonObject)?.let(::klingAIVideoMetadata) }))
                        },
                    )
                )
            ),
        )
    }

    private fun klingAIOptions(providerOptions: ProviderOptions): JsonObject =
        JsonAccess.obj(providerOptions.toMap(), "klingai") ?: JsonObject(emptyMap())

    private fun klingAIDetectMode(modelId: String): KlingAIVideoMode = when {
        modelId.endsWith("-t2v") -> KlingAIVideoMode.TextToVideo
        modelId.endsWith("-i2v") -> KlingAIVideoMode.ImageToVideo
        modelId.endsWith("-motion-control") -> KlingAIVideoMode.MotionControl
        else -> throw NoSuchModelError("klingai", "videoModel", modelId)
    }

    private fun klingAIModelName(modelId: String, mode: KlingAIVideoMode): String {
        val suffix = if (mode == KlingAIVideoMode.MotionControl) "-motion-control" else "-${modeSuffix(mode)}"
        return modelId.removeSuffix(suffix).replace(Regex("""\.0$"""), "").replace('.', '-')
    }

    private fun modeSuffix(mode: KlingAIVideoMode): String = when (mode) {
        KlingAIVideoMode.TextToVideo -> "t2v"
        KlingAIVideoMode.ImageToVideo -> "i2v"
        KlingAIVideoMode.MotionControl -> "motion-control"
    }

    private fun klingAIT2VBody(
        modelId: String,
        params: VideoGenerationParams,
        options: JsonObject,
        warnings: MutableList<CallWarning>,
    ): JsonObject = buildJsonObject {
        put("model_name", JsonPrimitive(klingAIModelName(modelId, KlingAIVideoMode.TextToVideo)))
        params.prompt.takeIf { it.isNotBlank() }?.let { put("prompt", JsonPrimitive(it)) }
        options["negativePrompt"]?.let { put("negative_prompt", it) }
        options["sound"]?.let { put("sound", it) }
        options["cfgScale"]?.let { put("cfg_scale", it) }
        options["mode"]?.let { put("mode", it) }
        options["cameraControl"]?.let { put("camera_control", it) }
        params.aspectRatio?.let { put("aspect_ratio", JsonPrimitive(it)) }
        params.durationSeconds?.let { put("duration", JsonPrimitive(klingAIDuration(it))) }
        options["multiShot"]?.let { put("multi_shot", it) }
        options["shotType"]?.let { put("shot_type", it) }
        options["multiPrompt"]?.let { put("multi_prompt", it) }
        options["voiceList"]?.let { put("voice_list", it) }
        (options["watermarkEnabled"] as? JsonPrimitive)?.booleanOrNull?.let {
            put("watermark_info", buildJsonObject { put("enabled", JsonPrimitive(it)) })
        }
        if (params.image != null) {
            warnings += CallWarning("unsupported", "KlingAI text-to-video does not support image input. Use an image-to-video model instead.")
        }
        klingAIPassthroughOptions(this, options)
    }

    private fun klingAII2VBody(
        modelId: String,
        params: VideoGenerationParams,
        options: JsonObject,
        warnings: MutableList<CallWarning>,
    ): JsonObject = buildJsonObject {
        put("model_name", JsonPrimitive(klingAIModelName(modelId, KlingAIVideoMode.ImageToVideo)))
        params.prompt.takeIf { it.isNotBlank() }?.let { put("prompt", JsonPrimitive(it)) }
        params.image?.let { put("image", JsonPrimitive(it.url ?: it.base64)) }
        options["imageTail"]?.let { put("image_tail", it) }
        options["negativePrompt"]?.let { put("negative_prompt", it) }
        options["sound"]?.let { put("sound", it) }
        options["cfgScale"]?.let { put("cfg_scale", it) }
        options["mode"]?.let { put("mode", it) }
        options["cameraControl"]?.let { put("camera_control", it) }
        options["staticMask"]?.let { put("static_mask", it) }
        options["dynamicMasks"]?.let { put("dynamic_masks", it) }
        options["multiShot"]?.let { put("multi_shot", it) }
        options["shotType"]?.let { put("shot_type", it) }
        options["multiPrompt"]?.let { put("multi_prompt", it) }
        options["elementList"]?.let { put("element_list", it) }
        options["voiceList"]?.let { put("voice_list", it) }
        (options["watermarkEnabled"] as? JsonPrimitive)?.booleanOrNull?.let {
            put("watermark_info", buildJsonObject { put("enabled", JsonPrimitive(it)) })
        }
        params.durationSeconds?.let { put("duration", JsonPrimitive(klingAIDuration(it))) }
        if (params.aspectRatio != null) {
            warnings += CallWarning("unsupported", "KlingAI image-to-video does not support aspectRatio. The output dimensions are determined by the input image.")
        }
        klingAIPassthroughOptions(this, options)
    }

    private fun klingAIMotionControlBody(
        modelId: String,
        params: VideoGenerationParams,
        options: JsonObject,
        warnings: MutableList<CallWarning>,
    ): JsonObject {
        val videoUrl = (options["videoUrl"] as? JsonPrimitive)?.contentOrNull
        val characterOrientation = (options["characterOrientation"] as? JsonPrimitive)?.contentOrNull
        val mode = (options["mode"] as? JsonPrimitive)?.contentOrNull
        if (videoUrl.isNullOrBlank() || characterOrientation.isNullOrBlank() || mode.isNullOrBlank()) {
            throw InvalidArgumentError(
                "providerOptions",
                "KlingAI Motion Control requires providerOptions.klingai with videoUrl, characterOrientation, and mode."
            )
        }
        return buildJsonObject {
            put("model_name", JsonPrimitive(klingAIModelName(modelId, KlingAIVideoMode.MotionControl)))
            put("video_url", JsonPrimitive(videoUrl))
            put("character_orientation", JsonPrimitive(characterOrientation))
            put("mode", JsonPrimitive(mode))
            params.prompt.takeIf { it.isNotBlank() }?.let { put("prompt", JsonPrimitive(it)) }
            params.image?.let { put("image_url", JsonPrimitive(it.url ?: it.base64)) }
            options["keepOriginalSound"]?.let { put("keep_original_sound", it) }
            (options["watermarkEnabled"] as? JsonPrimitive)?.booleanOrNull?.let {
                put("watermark_info", buildJsonObject { put("enabled", JsonPrimitive(it)) })
            }
            options["elementList"]?.let { put("element_list", it) }
            if (params.aspectRatio != null) {
                warnings += CallWarning("unsupported", "KlingAI Motion Control does not support aspectRatio. The output dimensions are determined by the reference image/video.")
            }
            if (params.durationSeconds != null) {
                warnings += CallWarning("unsupported", "KlingAI Motion Control does not support custom duration. The output duration matches the reference video duration.")
            }
            klingAIPassthroughOptions(this, options)
        }
    }

    private fun klingAIStandardWarnings(params: VideoGenerationParams): List<CallWarning> = buildList {
        if (params.resolution != null) add(
            CallWarning("unsupported", "KlingAI video models do not support the resolution option.")
        )
        if (params.seed != null) add(
            CallWarning("unsupported", "KlingAI video models do not support seed for deterministic generation.")
        )
        if (params.fps != null) add(CallWarning("unsupported", "KlingAI video models do not support custom FPS."))
        if (params.n > 1) add(
            CallWarning(
                "unsupported",
                "KlingAI video models do not support generating multiple videos per call. Only 1 video will be generated."
            )
        )
    }

    private fun klingAIPassthroughOptions(builder: kotlinx.serialization.json.JsonObjectBuilder, options: JsonObject) {
        for ((key, value) in options) {
            if (key !in klingAIHandledProviderOptions) builder.put(key, value)
        }
    }

    private suspend fun klingAIRequestJson(
        client: HttpClient,
        method: HttpMethod,
        url: String,
        headers: Map<String, String>,
        body: JsonObject? = null,
        abortSignal: AbortSignal,
    ): HttpJsonResponse =
        AbortSignalRuntime.withAbortCancellation(abortSignal) {
            HttpTransport.requestJson(
                client = client,
                url = url,
                method = method,
                headers = headers,
                body = body,
                requestBodyValues = body,
                errorMessage = ::klingAIErrorMessage,
                abortSignal = abortSignal,
            )
        }

    private fun klingAIVideoMetadata(value: JsonObject): JsonObject = buildJsonObject {
        (value["id"] as? JsonPrimitive)?.contentOrNull?.let { put("id", JsonPrimitive(it)) }
        (value["url"] as? JsonPrimitive)?.contentOrNull?.let { put("url", JsonPrimitive(it)) }
        (value["watermark_url"] as? JsonPrimitive)?.contentOrNull?.let { put("watermarkUrl", JsonPrimitive(it)) }
        (value["duration"] as? JsonPrimitive)?.contentOrNull?.let { put("duration", JsonPrimitive(it)) }
    }

    private fun klingAIDuration(value: Float): String {
        val wholeSeconds = value.toInt()
        return if (value == wholeSeconds.toFloat()) wholeSeconds.toString() else value.toString()
    }

    private fun klingAIErrorMessage(statusCode: Int, parsed: JsonElement?, raw: String): String {
        val detail = ((parsed as? JsonObject)?.get("message") as? JsonPrimitive)?.contentOrNull
            ?: raw.ifBlank { "request failed" }
        return "KlingAI request failed ($statusCode): $detail"
    }
}

internal enum class KlingAIVideoMode {
    TextToVideo,
    ImageToVideo,
    MotionControl,
}

private val klingAIHandledProviderOptions = setOf(
    "mode",
    "pollIntervalMs",
    "pollTimeoutMs",
    "maxPollAttempts",
    "negativePrompt",
    "sound",
    "cfgScale",
    "cameraControl",
    "multiShot",
    "shotType",
    "multiPrompt",
    "elementList",
    "voiceList",
    "imageTail",
    "staticMask",
    "dynamicMasks",
    "videoUrl",
    "characterOrientation",
    "keepOriginalSound",
    "watermarkEnabled",
)
