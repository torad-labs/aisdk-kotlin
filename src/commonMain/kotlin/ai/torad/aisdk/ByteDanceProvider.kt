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
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.ceil

const val BYTEDANCE_VERSION: String = "1.0.15"

typealias ByteDanceVideoModelId = String

@Serializable
data class ByteDanceVideoProviderOptions(
    val watermark: Boolean? = null,
    val generateAudio: Boolean? = null,
    val cameraFixed: Boolean? = null,
    val returnLastFrame: Boolean? = null,
    val serviceTier: String? = null,
    val draft: Boolean? = null,
    val lastFrameImage: String? = null,
    val referenceImages: List<String>? = null,
    val referenceVideos: List<String>? = null,
    val referenceAudio: List<String>? = null,
    val pollIntervalMs: Long? = null,
    val pollTimeoutMs: Long? = null,
)

@Serializable
data class ByteDanceProviderSettings(
    val apiKey: String? = null,
    val baseURL: String = "https://ark.ap-southeast.bytepluses.com/api/v3",
    val headers: Map<String, String> = emptyMap(),
)

interface ByteDanceProvider : Provider {
    fun video(modelId: ByteDanceVideoModelId): VideoModel
    override fun videoModel(modelId: String): VideoModel = video(modelId)
}

fun createByteDance(
    client: HttpClient,
    settings: ByteDanceProviderSettings = ByteDanceProviderSettings(),
): ByteDanceProvider = DefaultByteDanceProvider(client, settings)

val byteDance: ByteDanceProvider = object : ByteDanceProvider {
    override val providerId: String = "bytedance"
    override fun video(modelId: String): VideoModel =
        throw AiSdkException("ByteDance provider is not configured. Use createByteDance(client, settings).")
}

private class DefaultByteDanceProvider(
    private val client: HttpClient,
    private val settings: ByteDanceProviderSettings,
) : ByteDanceProvider {
    override val providerId: String = "bytedance"
    override fun video(modelId: String): VideoModel = ByteDanceVideoModel(client, settings, modelId)
    override fun languageModel(modelId: String): LanguageModel = throw NoSuchModelError(providerId, "languageModel", modelId)
    override fun embeddingModel(modelId: String): EmbeddingModel = throw NoSuchModelError(providerId, "embeddingModel", modelId)
    override fun imageModel(modelId: String): ImageModel = throw NoSuchModelError(providerId, "imageModel", modelId)
}

private class ByteDanceVideoModel(
    private val client: HttpClient,
    private val settings: ByteDanceProviderSettings,
    override val modelId: String,
) : VideoModel {
    override val provider: String = "bytedance.video"

    override suspend fun generate(params: VideoGenerationParams): VideoModelResult {
        params.abortSignal.throwIfAborted()
        val warnings = mutableListOf<CallWarning>()
        if (params.fps != null) {
            warnings += CallWarning("unsupported", "ByteDance video models do not support custom FPS. Frame rate is fixed at 24 fps.")
        }
        if (params.n > 1) {
            warnings += CallWarning("unsupported", "ByteDance video models do not support generating multiple videos per call. Only 1 video will be generated.")
        }
        val options = byteDanceOptions(params.providerOptions)
        val create = byteDancePostJson(
            client = client,
            url = "${settings.baseURL.trimEnd('/')}/contents/generations/tasks",
            body = byteDanceRequestBody(modelId, params, options),
            headers = byteDanceHeaders(settings, params.headers),
        )
        val taskId = create.value.jsonObject["id"]?.jsonPrimitive?.contentOrNull
            ?: throw AiSdkException("No task ID returned from ByteDance API")
        val status = byteDancePoll(
            client = client,
            settings = settings,
            taskId = taskId,
            callHeaders = params.headers,
            abortSignal = params.abortSignal,
            pollIntervalMs = options["pollIntervalMs"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: DEFAULT_BYTEDANCE_POLL_INTERVAL_MS,
            pollTimeoutMs = options["pollTimeoutMs"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: DEFAULT_BYTEDANCE_POLL_TIMEOUT_MS,
        )
        val statusBody = status.value.jsonObject
        val videoUrl = statusBody["content"]?.jsonObject?.get("video_url")?.jsonPrimitive?.contentOrNull
            ?: throw AiSdkException("No video URL in ByteDance response")
        return VideoModelResult(
            videos = listOf(
                GeneratedFile(
                    mediaType = "video/mp4",
                    base64 = "",
                    url = videoUrl,
                ),
            ),
            warnings = warnings,
            response = LanguageModelResponseMetadata(modelId = modelId, headers = status.headers, body = status.value),
            providerMetadata = mapOf("bytedance" to buildJsonObject {
                put("taskId", JsonPrimitive(taskId))
                statusBody["usage"]?.takeIf { it !is JsonNull }?.let { put("usage", it) }
            }),
        )
    }
}

private const val DEFAULT_BYTEDANCE_POLL_INTERVAL_MS: Long = 3_000L
private const val DEFAULT_BYTEDANCE_POLL_TIMEOUT_MS: Long = 300_000L

private val byteDanceJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
}

private data class ByteDanceJsonResponse(
    val value: JsonElement,
    val headers: Map<String, String>,
)

private val byteDanceHandledOptions = setOf(
    "watermark",
    "generateAudio",
    "cameraFixed",
    "returnLastFrame",
    "serviceTier",
    "draft",
    "lastFrameImage",
    "referenceImages",
    "referenceVideos",
    "referenceAudio",
    "pollIntervalMs",
    "pollTimeoutMs",
)

private val byteDanceResolutionMap = mapOf(
    "864x496" to "480p",
    "496x864" to "480p",
    "752x560" to "480p",
    "560x752" to "480p",
    "640x640" to "480p",
    "992x432" to "480p",
    "432x992" to "480p",
    "864x480" to "480p",
    "480x864" to "480p",
    "736x544" to "480p",
    "544x736" to "480p",
    "960x416" to "480p",
    "416x960" to "480p",
    "832x480" to "480p",
    "480x832" to "480p",
    "624x624" to "480p",
    "1280x720" to "720p",
    "720x1280" to "720p",
    "1112x834" to "720p",
    "834x1112" to "720p",
    "960x960" to "720p",
    "1470x630" to "720p",
    "630x1470" to "720p",
    "1248x704" to "720p",
    "704x1248" to "720p",
    "1120x832" to "720p",
    "832x1120" to "720p",
    "1504x640" to "720p",
    "640x1504" to "720p",
    "1920x1080" to "1080p",
    "1080x1920" to "1080p",
    "1664x1248" to "1080p",
    "1248x1664" to "1080p",
    "1440x1440" to "1080p",
    "2206x946" to "1080p",
    "946x2206" to "1080p",
    "1920x1088" to "1080p",
    "1088x1920" to "1080p",
    "2176x928" to "1080p",
    "928x2176" to "1080p",
)

private fun byteDanceRequestBody(
    modelId: String,
    params: VideoGenerationParams,
    options: JsonObject,
): JsonObject = buildJsonObject {
    put("model", JsonPrimitive(modelId))
    put("content", byteDanceContent(params, options))
    params.aspectRatio?.let { put("ratio", JsonPrimitive(it)) }
    params.durationSeconds?.let { put("duration", JsonPrimitive(it.toDouble())) }
    params.seed?.let { put("seed", JsonPrimitive(it)) }
    (params.resolution ?: params.size)?.let { put("resolution", JsonPrimitive(byteDanceResolutionMap[it] ?: it)) }
    putBooleanIfPresent("watermark", options["watermark"])
    putBooleanIfPresent("generate_audio", options["generateAudio"])
    putBooleanIfPresent("camera_fixed", options["cameraFixed"])
    putBooleanIfPresent("return_last_frame", options["returnLastFrame"])
    putStringIfPresent("service_tier", options["serviceTier"])
    putBooleanIfPresent("draft", options["draft"])
    for ((key, value) in options) {
        if (key !in byteDanceHandledOptions && value !is JsonNull) put(key, value)
    }
}

private fun byteDanceContent(params: VideoGenerationParams, options: JsonObject): JsonArray = JsonArray(buildList {
    add(buildJsonObject {
        put("type", JsonPrimitive("text"))
        put("text", JsonPrimitive(params.prompt))
    })
    params.image?.let { image ->
        add(buildJsonObject {
            put("type", JsonPrimitive("image_url"))
            put("image_url", buildJsonObject { put("url", JsonPrimitive(image.byteDanceDataUri())) })
            if (options["lastFrameImage"] != null) put("role", JsonPrimitive("first_frame"))
        })
    }
    options["lastFrameImage"]?.jsonPrimitive?.contentOrNull?.let { url ->
        add(byteDanceMediaContent("image_url", "image_url", url, "last_frame"))
    }
    options["referenceImages"]?.jsonArray.orEmpty().forEach { url ->
        add(byteDanceMediaContent("image_url", "image_url", url.jsonPrimitive.contentOrNull.orEmpty(), "reference_image"))
    }
    options["referenceVideos"]?.jsonArray.orEmpty().forEach { url ->
        add(byteDanceMediaContent("video_url", "video_url", url.jsonPrimitive.contentOrNull.orEmpty(), "reference_video"))
    }
    options["referenceAudio"]?.jsonArray.orEmpty().forEach { url ->
        add(byteDanceMediaContent("audio_url", "audio_url", url.jsonPrimitive.contentOrNull.orEmpty(), "reference_audio"))
    }
})

private fun byteDanceMediaContent(type: String, field: String, url: String, role: String): JsonObject = buildJsonObject {
    put("type", JsonPrimitive(type))
    put(field, buildJsonObject { put("url", JsonPrimitive(url)) })
    put("role", JsonPrimitive(role))
}

private fun GeneratedFile.byteDanceDataUri(): String {
    url?.takeIf { it.isNotBlank() }?.let { return it }
    return "data:$mediaType;base64,$base64"
}

private suspend fun byteDancePostJson(
    client: HttpClient,
    url: String,
    body: JsonObject,
    headers: Map<String, String>,
): ByteDanceJsonResponse {
    val response = client.request(url) {
        method = HttpMethod.Post
        contentType(ContentType.Application.Json)
        headers.forEach { (name, value) -> header(name, value) }
        setBody(byteDanceJson.encodeToString(JsonElement.serializer(), body))
    }
    return response.parseByteDanceJson()
}

private suspend fun byteDanceGetJson(
    client: HttpClient,
    url: String,
    headers: Map<String, String>,
    abortSignal: AbortSignal,
): ByteDanceJsonResponse {
    abortSignal.throwIfAborted()
    val response = client.request(url) {
        method = HttpMethod.Get
        headers.forEach { (name, value) -> header(name, value) }
    }
    return response.parseByteDanceJson()
}

private suspend fun byteDancePoll(
    client: HttpClient,
    settings: ByteDanceProviderSettings,
    taskId: String,
    callHeaders: Map<String, String>,
    abortSignal: AbortSignal,
    pollIntervalMs: Long,
    pollTimeoutMs: Long,
): ByteDanceJsonResponse {
    val interval = pollIntervalMs.coerceAtLeast(1L)
    val maxPollAttempts = ceil(pollTimeoutMs.coerceAtLeast(1L).toDouble() / interval.toDouble()).toInt().coerceAtLeast(1)
    repeat(maxPollAttempts) { attempt ->
        val status = byteDanceGetJson(
            client = client,
            url = "${settings.baseURL.trimEnd('/')}/contents/generations/tasks/$taskId",
            headers = byteDanceHeaders(settings, callHeaders),
            abortSignal = abortSignal,
        )
        val statusBody = status.value.jsonObject
        when (statusBody["status"]?.jsonPrimitive?.contentOrNull) {
            "succeeded" -> return status
            "failed" -> throw AiSdkException("ByteDance video generation failed: $statusBody")
        }
        if (pollIntervalMs > 0 && attempt < maxPollAttempts - 1) delay(pollIntervalMs)
    }
    throw AiSdkException("ByteDance video generation timed out after ${pollTimeoutMs}ms")
}

private suspend fun HttpResponse.parseByteDanceJson(): ByteDanceJsonResponse {
    val raw = bodyAsText()
    if (status.value !in 200..299) {
        throw AiSdkException("ByteDance request failed (${status.value}): ${byteDanceErrorMessage(raw)}")
    }
    return ByteDanceJsonResponse(
        value = if (raw.isBlank()) JsonObject(emptyMap()) else byteDanceJson.parseToJsonElement(raw),
        headers = headers.entries().associate { it.key to it.value.joinToString(",") },
    )
}

private fun byteDanceHeaders(settings: ByteDanceProviderSettings, callHeaders: Map<String, String>): Map<String, String> {
    val base = linkedMapOf<String, String>()
    settings.apiKey?.takeIf { it.isNotBlank() }?.let { base[HttpHeaders.Authorization] = "Bearer $it" }
    base[HttpHeaders.ContentType] = "application/json"
    base.putAll(settings.headers)
    base.putAll(callHeaders)
    return base
}

private fun byteDanceOptions(providerOptions: Map<String, JsonElement>): JsonObject =
    providerOptions["bytedance"] as? JsonObject ?: JsonObject(emptyMap())

private fun byteDanceErrorMessage(raw: String): String {
    val obj = runCatching { byteDanceJson.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return raw.ifBlank { "request failed" }
    return obj["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull ?: raw.ifBlank { "request failed" }
}

private fun JsonObjectBuilder.putBooleanIfPresent(key: String, value: JsonElement?) {
    value?.jsonPrimitive?.booleanOrNull?.let { put(key, JsonPrimitive(it)) }
}

private fun JsonObjectBuilder.putStringIfPresent(key: String, value: JsonElement?) {
    value?.jsonPrimitive?.contentOrNull?.let { put(key, JsonPrimitive(it)) }
}
