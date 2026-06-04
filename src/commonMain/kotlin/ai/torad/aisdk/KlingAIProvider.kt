package ai.torad.aisdk

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlin.math.min
import kotlin.time.Clock
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

const val KLINGAI_VERSION: String = "3.0.18"

typealias KlingAIVideoModelId = String
typealias KlingAIVideoProviderOptions = KlingAIVideoModelOptions

@Serializable
data class KlingAIProviderSettings(
    val accessKey: String? = null,
    val secretKey: String? = null,
    val baseURL: String = "https://api-singapore.klingai.com",
    val headers: Map<String, String> = emptyMap(),
)

@Serializable
data class KlingAIVideoModelOptions(
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

interface KlingAIProvider : Provider {
    fun video(modelId: KlingAIVideoModelId): VideoModel = videoModel(modelId)
}

fun createKlingAI(
    client: HttpClient,
    settings: KlingAIProviderSettings = KlingAIProviderSettings(),
): KlingAIProvider = DefaultKlingAIProvider(client, settings)

val klingai: KlingAIProvider = object : KlingAIProvider {
    override val providerId: String = "klingai"
    override fun videoModel(modelId: String): VideoModel =
        throw AiSdkException("KlingAI provider is not configured. Use createKlingAI(client, settings).")
}

private class DefaultKlingAIProvider(
    private val client: HttpClient,
    private val settings: KlingAIProviderSettings,
) : KlingAIProvider {
    override val providerId: String = "klingai"
    override fun videoModel(modelId: String): VideoModel = KlingAIVideoModel(client, settings, modelId)
    override fun languageModel(modelId: String): LanguageModel = throw NoSuchModelError(providerId, "languageModel", modelId)
    override fun embeddingModel(modelId: String): EmbeddingModel = throw NoSuchModelError(providerId, "embeddingModel", modelId)
    override fun imageModel(modelId: String): ImageModel = throw NoSuchModelError(providerId, "imageModel", modelId)
}

private class KlingAIVideoModel(
    private val client: HttpClient,
    private val settings: KlingAIProviderSettings,
    override val modelId: String,
) : VideoModel {
    override val provider: String = "klingai.video"

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
            headers = klingAIHeaders(settings, params.headers),
            body = body,
        )
        val taskId = create.value.jsonObject["data"]?.jsonObject?.get("task_id")?.jsonPrimitive?.contentOrNull
            ?: throw AiSdkException("No task_id returned from KlingAI API. Response: ${create.value}")

        val pollIntervalMs = options["pollIntervalMs"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 5_000L
        val pollTimeoutMs = options["pollTimeoutMs"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 600_000L
        val started = Clock.System.now().toEpochMilliseconds()
        var headers = create.headers
        while (true) {
            params.abortSignal.throwIfAborted()
            if (pollIntervalMs > 0) delay(pollIntervalMs)
            if (Clock.System.now().toEpochMilliseconds() - started > pollTimeoutMs) {
                throw AiSdkException("Video generation timed out after ${pollTimeoutMs}ms")
            }
            val status = klingAIRequestJson(
                client = client,
                method = HttpMethod.Get,
                url = "${settings.baseURL.trimEnd('/')}$endpoint/$taskId",
                headers = klingAIHeaders(settings, params.headers),
            )
            headers = status.headers
            val data = status.value.jsonObject["data"]?.jsonObject ?: JsonObject(emptyMap())
            when (val taskStatus = data["task_status"]?.jsonPrimitive?.contentOrNull) {
                "succeed" -> return klingAISuccessResult(taskId, data, headers, warnings)
                "failed" -> throw AiSdkException("Video generation failed: ${data["task_status_msg"]?.jsonPrimitive?.contentOrNull ?: "Unknown error"}")
                "submitted", "processing", null -> Unit
                else -> throw AiSdkException("Unknown KlingAI task status: $taskStatus")
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


private data class KlingAIJsonResponse(
    val value: JsonElement,
    val headers: Map<String, String>,
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
    params.durationSeconds?.let { put("duration", JsonPrimitive(it.toString())) }
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
    params.durationSeconds?.let { put("duration", JsonPrimitive(it.toString())) }
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
        throw AiSdkException("KlingAI Motion Control requires providerOptions.klingai with videoUrl, characterOrientation, and mode.")
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
): KlingAIJsonResponse {
    val response = client.request(url) {
        this.method = method
        if (body != null) {
            contentType(ContentType.Application.Json)
            setBody(aiSdkJson.encodeToString(JsonElement.serializer(), body))
        }
        headers.forEach { (name, value) -> header(name, value) }
    }
    return response.parseKlingAIJson()
}

private suspend fun HttpResponse.parseKlingAIJson(): KlingAIJsonResponse {
    val raw = bodyAsText()
    if (status.value !in 200..299) {
        throw AiSdkException("KlingAI request failed (${status.value}): ${klingAIErrorMessage(raw)}")
    }
    return KlingAIJsonResponse(
        value = if (raw.isBlank()) JsonObject(emptyMap()) else aiSdkJson.parseToJsonElement(raw),
        headers = headers.entries().associate { it.key to it.value.joinToString(",") },
    )
}

private fun klingAIHeaders(settings: KlingAIProviderSettings, callHeaders: Map<String, String>): Map<String, String> {
    val token = generateKlingAIAuthToken(
        accessKey = settings.accessKey ?: throw AiSdkException("KlingAI access key is required."),
        secretKey = settings.secretKey ?: throw AiSdkException("KlingAI secret key is required."),
    )
    val base = linkedMapOf<String, String?>()
    base[HttpHeaders.Authorization] = "Bearer $token"
    settings.headers.forEach { (key, value) -> base[key] = value }
    callHeaders.forEach { (key, value) -> base[key] = value }
    return withUserAgentSuffix(base, "ai-sdk/klingai/$KLINGAI_VERSION")
}

private fun klingAIOptions(providerOptions: Map<String, JsonElement>): JsonObject =
    providerOptions["klingai"] as? JsonObject ?: JsonObject(emptyMap())

private fun klingAIVideoMetadata(value: JsonObject): JsonObject = buildJsonObject {
    value["id"]?.jsonPrimitive?.contentOrNull?.let { put("id", JsonPrimitive(it)) }
    value["url"]?.jsonPrimitive?.contentOrNull?.let { put("url", JsonPrimitive(it)) }
    value["watermark_url"]?.jsonPrimitive?.contentOrNull?.let { put("watermarkUrl", JsonPrimitive(it)) }
    value["duration"]?.jsonPrimitive?.contentOrNull?.let { put("duration", JsonPrimitive(it)) }
}

private fun klingAIErrorMessage(raw: String): String {
    val obj = runCatching { aiSdkJson.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return raw.ifBlank { "request failed" }
    return obj["message"]?.jsonPrimitive?.contentOrNull ?: raw.ifBlank { "request failed" }
}

private fun generateKlingAIAuthToken(accessKey: String, secretKey: String): String {
    val now = Clock.System.now().epochSeconds
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
    convertByteArrayToBase64(bytes).replace('+', '-').replace('/', '_').trimEnd('=')

private fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray {
    val blockSize = 64
    val normalizedKey = when {
        key.size > blockSize -> sha256(key)
        key.size < blockSize -> key + ByteArray(blockSize - key.size)
        else -> key
    }
    val outer = ByteArray(blockSize)
    val inner = ByteArray(blockSize)
    for (index in 0 until blockSize) {
        outer[index] = (normalizedKey[index].toInt() xor 0x5c).toByte()
        inner[index] = (normalizedKey[index].toInt() xor 0x36).toByte()
    }
    return sha256(outer + sha256(inner + message))
}

private val sha256K = intArrayOf(
    0x428a2f98, 0x71374491, -0x4a3f0431, -0x164a245b, 0x3956c25b, 0x59f111f1, -0x6dc07d5c, -0x54e3a12b,
    -0x27f85568, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, -0x7f214e02, -0x6423f959, -0x3e640e8c,
    -0x1b64963f, -0x1041b87a, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    -0x67c1aeae, -0x57ce3993, -0x4ffcd838, -0x40a68039, -0x391ff40d, -0x2a586eb9, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, -0x7e3d36d2, -0x6d8dd37b,
    -0x5d40175f, -0x57e599b5, -0x3db47490, -0x3893ae5d, -0x2e6d17e7, -0x2966f9dc, -0xbf1ca7b, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, -0x7b3787ec, -0x7338fdf8, -0x6f410006, -0x5baf9315, -0x41065c09, -0x398e870e,
)

private fun sha256(input: ByteArray): ByteArray {
    var h0 = 0x6a09e667
    var h1 = -0x4498517b
    var h2 = 0x3c6ef372
    var h3 = -0x5ab00ac6
    var h4 = 0x510e527f
    var h5 = -0x64fa9774
    var h6 = 0x1f83d9ab
    var h7 = 0x5be0cd19
    val bitLength = input.size.toLong() * 8L
    val paddingLength = ((56 - (input.size + 1) % 64) + 64) % 64
    val padded = ByteArray(input.size + 1 + paddingLength + 8)
    input.copyInto(padded)
    padded[input.size] = 0x80.toByte()
    for (index in 0 until 8) {
        padded[padded.size - 1 - index] = (bitLength ushr (index * 8)).toByte()
    }
    val w = IntArray(64)
    for (chunkStart in padded.indices step 64) {
        for (index in 0 until 16) {
            val offset = chunkStart + index * 4
            w[index] = ((padded[offset].toInt() and 0xff) shl 24) or
                ((padded[offset + 1].toInt() and 0xff) shl 16) or
                ((padded[offset + 2].toInt() and 0xff) shl 8) or
                (padded[offset + 3].toInt() and 0xff)
        }
        for (index in 16 until 64) {
            val s0 = rotr(w[index - 15], 7) xor rotr(w[index - 15], 18) xor (w[index - 15] ushr 3)
            val s1 = rotr(w[index - 2], 17) xor rotr(w[index - 2], 19) xor (w[index - 2] ushr 10)
            w[index] = w[index - 16] + s0 + w[index - 7] + s1
        }
        var a = h0
        var b = h1
        var c = h2
        var d = h3
        var e = h4
        var f = h5
        var g = h6
        var h = h7
        for (index in 0 until 64) {
            val s1 = rotr(e, 6) xor rotr(e, 11) xor rotr(e, 25)
            val ch = (e and f) xor (e.inv() and g)
            val temp1 = h + s1 + ch + sha256K[index] + w[index]
            val s0 = rotr(a, 2) xor rotr(a, 13) xor rotr(a, 22)
            val maj = (a and b) xor (a and c) xor (b and c)
            val temp2 = s0 + maj
            h = g
            g = f
            f = e
            e = d + temp1
            d = c
            c = b
            b = a
            a = temp1 + temp2
        }
        h0 += a
        h1 += b
        h2 += c
        h3 += d
        h4 += e
        h5 += f
        h6 += g
        h7 += h
    }
    val out = ByteArray(32)
    intArrayOf(h0, h1, h2, h3, h4, h5, h6, h7).forEachIndexed { index, value ->
        out[index * 4] = (value ushr 24).toByte()
        out[index * 4 + 1] = (value ushr 16).toByte()
        out[index * 4 + 2] = (value ushr 8).toByte()
        out[index * 4 + 3] = value.toByte()
    }
    return out
}

private fun rotr(value: Int, bits: Int): Int = (value ushr bits) or (value shl (32 - bits))
