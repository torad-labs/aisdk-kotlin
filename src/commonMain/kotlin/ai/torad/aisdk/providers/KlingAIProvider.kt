package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import io.ktor.client.HttpClient
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import kotlin.time.Clock
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

public const val KLINGAI_VERSION: String = "3.0.18"

public typealias KlingAIVideoModelId = String
public typealias KlingAIVideoProviderOptions = KlingAIVideoModelOptions

@Serializable
public data class KlingAIProviderSettings(
    val accessKey: String? = null,
    val secretKey: String? = null,
    val baseURL: String = "https://api-singapore.klingai.com",
    val headers: Map<String, String> = emptyMap(),
)

@Serializable
public data class KlingAIVideoModelOptions(
    val mode: String? = null,
    val pollIntervalMs: Long? = null,
    val pollTimeoutMs: Long? = null,
    val negativePrompt: String? = null,
    val sound: String? = null,
    val cfgScale: Float? = null,
    val cameraControl: JsonObject? = null,
    val imageTail: String? = null,
    val staticMask: String? = null,
    val dynamicMasks: JsonArray? = null,
    val multiShot: Boolean? = null,
    val shotType: String? = null,
    val multiPrompt: JsonArray? = null,
    val elementList: JsonArray? = null,
    val voiceList: JsonArray? = null,
    val watermarkEnabled: Boolean? = null,
    val videoUrl: String? = null,
    val characterOrientation: String? = null,
    val keepOriginalSound: String? = null,
)

public class KlingAIProvider(
    private val client: HttpClient,
    public val settings: KlingAIProviderSettings,
) : Provider {
    override val providerId: String = "klingai"

    public fun video(modelId: KlingAIVideoModelId): VideoModel = videoModel(modelId)

    override fun videoModel(modelId: String): VideoModel = KlingAIVideoModel(client, settings, modelId)
    override fun languageModel(modelId: String): LanguageModel = throw NoSuchModelError(providerId, "languageModel", modelId)
    override fun embeddingModel(modelId: String): EmbeddingModel = throw NoSuchModelError(providerId, "embeddingModel", modelId)
    override fun imageModel(modelId: String): ImageModel = throw NoSuchModelError(providerId, "imageModel", modelId)
}

/** PascalCase factory — mirrors the OpenAI(...) reference pattern. */
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
            headers = klingAIHeaders(settings, params.headers, clock),
            body = body,
        )
        val taskId = create.value.jsonObject["data"]?.jsonObject?.get("task_id")?.jsonPrimitive?.contentOrNull
            ?: throw InvalidResponseDataError(create.value, "No task_id returned from KlingAI API. Response: ${create.value}")

        val pollIntervalMs = options["pollIntervalMs"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 5_000L
        val pollTimeoutMs = options["pollTimeoutMs"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 600_000L
        val started = clock.now().toEpochMilliseconds()
        var headers = create.headers
        while (true) {
            params.abortSignal.throwIfAborted()
            if (pollIntervalMs > 0) delay(pollIntervalMs)
            if (clock.now().toEpochMilliseconds() - started > pollTimeoutMs) {
                throw NoVideoGeneratedError("Video generation timed out after ${pollTimeoutMs}ms")
            }
            val status = klingAIRequestJson(
                client = client,
                method = HttpMethod.Get,
                url = "${settings.baseURL.trimEnd('/')}$endpoint/$taskId",
                headers = klingAIHeaders(settings, params.headers, clock),
            )
            headers = status.headers
            val data = status.value.jsonObject["data"]?.jsonObject ?: JsonObject(emptyMap())
            when (val taskStatus = data["task_status"]?.jsonPrimitive?.contentOrNull) {
                "succeed" -> return klingAISuccessResult(taskId, data, headers, warnings)
                "failed" -> throw NoVideoGeneratedError("Video generation failed: ${data["task_status_msg"]?.jsonPrimitive?.contentOrNull ?: "Unknown error"}")
                "submitted", "processing", null -> Unit
                else -> throw InvalidResponseDataError(data, "Unknown KlingAI task status: $taskStatus")
            }
        }
    }

    private fun klingAISuccessResult(
        taskId: String,
        data: JsonObject,
        headers: Map<String, String>,
        warnings: List<CallWarning>,
    ): VideoModelResult {
        val videos = data["task_result"]?.jsonObject?.get("videos")?.jsonArray.orEmpty()
        val generated = videos.mapNotNull { item ->
            val obj = item.jsonObject
            val url = obj["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            GeneratedFile(
                mediaType = "video/mp4",
                base64 = "",
                url = url,
                providerMetadata = mapOf("klingai" to klingAIVideoMetadata(obj)),
            )
        }
        if (generated.isEmpty()) throw NoVideoGeneratedError("No valid video URLs in KlingAI response")
        return VideoModelResult(
            videos = generated,
            warnings = warnings,
            response = LanguageModelResponseMetadata(modelId = modelId, headers = headers),
            providerMetadata = mapOf(
                "klingai" to buildJsonObject {
                    put("taskId", JsonPrimitive(taskId))
                    put("videos", JsonArray(videos.map { klingAIVideoMetadata(it.jsonObject) }))
                },
            ),
        )
    }
}

private enum class KlingAIVideoMode {
    TextToVideo,
    ImageToVideo,
    MotionControl,
}

private val klingAIHandledProviderOptions = setOf(
    "mode",
    "pollIntervalMs",
    "pollTimeoutMs",
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
    options["watermarkEnabled"]?.jsonPrimitive?.booleanOrNull?.let { put("watermark_info", buildJsonObject { put("enabled", JsonPrimitive(it)) }) }
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
    options["watermarkEnabled"]?.jsonPrimitive?.booleanOrNull?.let { put("watermark_info", buildJsonObject { put("enabled", JsonPrimitive(it)) }) }
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
    val videoUrl = options["videoUrl"]?.jsonPrimitive?.contentOrNull
    val characterOrientation = options["characterOrientation"]?.jsonPrimitive?.contentOrNull
    val mode = options["mode"]?.jsonPrimitive?.contentOrNull
    if (videoUrl.isNullOrBlank() || characterOrientation.isNullOrBlank() || mode.isNullOrBlank()) {
        throw AiSdkRuntimeException("KlingAI Motion Control requires providerOptions.klingai with videoUrl, characterOrientation, and mode.")
    }
    return buildJsonObject {
        put("model_name", JsonPrimitive(klingAIModelName(modelId, KlingAIVideoMode.MotionControl)))
        put("video_url", JsonPrimitive(videoUrl))
        put("character_orientation", JsonPrimitive(characterOrientation))
        put("mode", JsonPrimitive(mode))
        params.prompt.takeIf { it.isNotBlank() }?.let { put("prompt", JsonPrimitive(it)) }
        params.image?.let { put("image_url", JsonPrimitive(it.url ?: it.base64)) }
        options["keepOriginalSound"]?.let { put("keep_original_sound", it) }
        options["watermarkEnabled"]?.jsonPrimitive?.booleanOrNull?.let { put("watermark_info", buildJsonObject { put("enabled", JsonPrimitive(it)) }) }
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
    if (params.resolution != null) add(CallWarning("unsupported", "KlingAI video models do not support the resolution option."))
    if (params.seed != null) add(CallWarning("unsupported", "KlingAI video models do not support seed for deterministic generation."))
    if (params.fps != null) add(CallWarning("unsupported", "KlingAI video models do not support custom FPS."))
    if (params.n > 1) add(CallWarning("unsupported", "KlingAI video models do not support generating multiple videos per call. Only 1 video will be generated."))
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
): HttpJsonResponse =
    requestJson(
        client = client,
        url = url,
        method = method,
        headers = headers,
        body = body,
        requestBodyValues = body,
        errorMessage = ::klingAIErrorMessage,
    )

private fun klingAIHeaders(
    settings: KlingAIProviderSettings,
    callHeaders: Map<String, String>,
    clock: Clock = Clock.System,
): Map<String, String> {
    val token = generateKlingAIAuthToken(
        accessKey = settings.accessKey ?: throw AiSdkRuntimeException("KlingAI access key is required."),
        secretKey = settings.secretKey ?: throw AiSdkRuntimeException("KlingAI secret key is required."),
        clock = clock,
    )
    return ProviderHeaders.build(settings.headers, callHeaders, "ai-sdk/klingai/$KLINGAI_VERSION") { base ->
        base[HttpHeaders.Authorization] = "Bearer $token"
    }
}

private fun klingAIOptions(providerOptions: Map<String, JsonElement>): JsonObject =
    providerOptions["klingai"] as? JsonObject ?: JsonObject(emptyMap())

private fun klingAIVideoMetadata(value: JsonObject): JsonObject = buildJsonObject {
    value["id"]?.jsonPrimitive?.contentOrNull?.let { put("id", JsonPrimitive(it)) }
    value["url"]?.jsonPrimitive?.contentOrNull?.let { put("url", JsonPrimitive(it)) }
    value["watermark_url"]?.jsonPrimitive?.contentOrNull?.let { put("watermarkUrl", JsonPrimitive(it)) }
    value["duration"]?.jsonPrimitive?.contentOrNull?.let { put("duration", JsonPrimitive(it)) }
}

private fun klingAIDuration(value: Float): String {
    val wholeSeconds = value.toInt()
    return if (value == wholeSeconds.toFloat()) wholeSeconds.toString() else value.toString()
}

private fun klingAIErrorMessage(statusCode: Int, parsed: JsonElement?, raw: String): String {
    val detail = (parsed as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull
        ?: raw.ifBlank { "request failed" }
    return "KlingAI request failed ($statusCode): $detail"
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
    val signingInput = "${base64Url(aiSdkJson.encodeToString(JsonElement.serializer(), header).encodeToByteArray())}." +
        base64Url(aiSdkJson.encodeToString(JsonElement.serializer(), payload).encodeToByteArray())
    val signature = hmacSha256(secretKey.encodeToByteArray(), signingInput.encodeToByteArray())
    return "$signingInput.${base64Url(signature)}"
}

private fun base64Url(bytes: ByteArray): String =
    Base64Codec.encode(bytes).replace('+', '-').replace('/', '_').trimEnd('=')
