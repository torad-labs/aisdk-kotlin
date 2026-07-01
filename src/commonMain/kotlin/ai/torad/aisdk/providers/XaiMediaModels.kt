@file:OptIn(ai.torad.aisdk.LowLevelLanguageModelApi::class)

package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import ai.torad.aisdk.ProviderMetadata
import dev.drewhamilton.poko.Poko
import io.ktor.client.HttpClient
import io.ktor.client.request.request
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlin.math.ceil

internal class XaiImageModel(
    private val client: HttpClient,
    private val settings: XaiProviderSettings,
    override val modelId: String,
) : ImageModel {
    override val provider: String = "xai.image"

    override suspend fun generate(params: ImageGenerationParams): ImageModelResult {
        params.abortSignal.throwIfAborted()
        val warnings = mutableListOf<CallWarning>()
        if (params.size != null) {
            warnings += CallWarning("unsupported", "This model does not support the `size` option. Use `aspectRatio` instead.")
        }
        if (params.seed != null) {
            warnings += CallWarning("unsupported", "xAI image models do not support seed.")
        }
        if (params.mask != null) {
            warnings += CallWarning("unsupported", "xAI image models do not support mask.")
        }
        val options = settings.xaiOptions(params.providerOptions)
        val endpoint = if (params.files.isEmpty()) "/images/generations" else "/images/edits"
        val body = buildJsonObject {
            put("model", JsonPrimitive(modelId))
            put("prompt", JsonPrimitive(params.prompt))
            put("n", JsonPrimitive(params.n))
            put("response_format", JsonPrimitive("b64_json"))
            (params.aspectRatio ?: (options["aspect_ratio"] as? JsonPrimitive)?.contentOrNull)?.let {
                put("aspect_ratio", JsonPrimitive(it))
            }
            settings.putXaiProviderOptions(this, options, setOf("aspect_ratio"))
            putXaiImageInputs(this, params.files)
        }
        val response = settings.xaiPostJson(
            client = client,
            url = "${settings.baseURL.trimEnd('/')}$endpoint",
            body = body,
            headers = settings.xaiHeaders(params.headers),
        )
        val responseObj = response.value.jsonObject
        val data = (JsonAccess.arr(responseObj, "data")).orEmpty()
        val images = data.mapNotNull { image ->
            val obj = image as? JsonObject ?: return@mapNotNull null
            val base64 = (obj["b64_json"] as? JsonPrimitive)?.contentOrNull
            if (base64 != null) {
                GeneratedFile(mediaType = "image/png", base64 = base64)
            } else {
                val url = (obj["url"] as? JsonPrimitive)?.contentOrNull
                    ?: throw NoImageGeneratedError("xAI image response is missing b64_json and url")
                xaiDownloadImage(client, url, params.abortSignal)
            }
        }
        return ImageModelResult(
            images = images,
            warnings = warnings,
            response = LanguageModelResponseMetadata(modelId = modelId, headers = response.headers, body = response.value),
            providerMetadata = xaiImageMetadata(data, responseObj),
        )
    }

    private fun xaiImageMetadata(data: List<JsonElement>, responseObj: JsonObject): ProviderMetadata =
        ProviderMetadata.Raw(JsonObject(mapOf("xai" to buildJsonObject {
            val imageEntries = data.map { image ->
                val revisedPrompt = ((image as? JsonObject)?.get("revised_prompt") as? JsonPrimitive)?.contentOrNull
                buildJsonObject {
                    if (revisedPrompt != null) put("revisedPrompt", JsonPrimitive(revisedPrompt))
                }
            }
            put("images", JsonArray(imageEntries))
            (JsonAccess.obj(responseObj, "usage"))?.get("cost_in_usd_ticks")?.let { put("costInUsdTicks", it) }
        })))

    private fun putXaiImageInputs(builder: JsonObjectBuilder, files: List<ImageGenerationFile>) {
        val imageUrls = files.map { xaiDataUri(it) }
        when (imageUrls.size) {
            0 -> Unit
            1 -> builder.put("image", xaiImageUrlObject(imageUrls.single()))
            else -> builder.put("images", JsonArray(imageUrls.map(::xaiImageUrlObject)))
        }
    }

    private fun xaiImageUrlObject(url: String): JsonObject = buildJsonObject {
        put("url", JsonPrimitive(url))
        put("type", JsonPrimitive("image_url"))
    }

    private fun xaiDataUri(file: ImageGenerationFile): String {
        file.url?.takeIf { it.isNotBlank() }?.let { return it }
        val mediaType = file.mediaType ?: "application/octet-stream"
        val data = file.base64?.takeIf { it.isNotBlank() }
            ?: throw InvalidArgumentError("file", "xAI image file must include either url or base64 data.")
        return "data:$mediaType;base64,$data"
    }

    private suspend fun xaiDownloadImage(
        client: HttpClient,
        url: String,
        abortSignal: AbortSignal,
    ): GeneratedFile {
        abortSignal.throwIfAborted()
        val response = client.request(url) { method = HttpMethod.Get }
        val bytes = response.bodyAsBytes()
        val headers = with(HttpTransport) { response.flattenedHeaders() }
        if (response.status.value !in 200..299) {
            val raw = bytes.decodeToString()
            throw ApiCallError(
                url = url,
                statusCode = response.status.value,
                rawBody = raw,
                headers = headers,
                message = "xAI image download failed (${response.status.value}): ${raw.ifBlank { "request failed" }}",
            )
        }
        return GeneratedFile(
            mediaType = xaiHeaderValue(headers, HttpHeaders.ContentType) ?: "image/png",
            base64 = Base64Codec.encode(bytes),
        )
    }

    private fun xaiHeaderValue(map: Map<String, String>, name: String): String? =
        map.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}

internal class XaiVideoModel(
    private val client: HttpClient,
    private val settings: XaiProviderSettings,
    override val modelId: String,
) : VideoModel {
    override val provider: String = "xai.video"

    override suspend fun generate(params: VideoGenerationParams): VideoModelResult {
        params.abortSignal.throwIfAborted()
        val options = settings.xaiOptions(params.providerOptions)
        val mode = xaiVideoMode(options)
        val warnings = xaiVideoWarnings(params, options, mode)
        val body = xaiVideoRequestBody(modelId, params, options, mode, warnings)
        val endpoint = when (mode) {
            "edit-video" -> "/videos/edits"
            "extend-video" -> "/videos/extensions"
            else -> "/videos/generations"
        }
        val create = settings.xaiPostJson(
            client = client,
            url = "${settings.baseURL.trimEnd('/')}$endpoint",
            body = body,
            headers = settings.xaiHeaders(params.headers),
        )
        val requestId = (create.value.jsonObject["request_id"] as? JsonPrimitive)?.contentOrNull
            ?: throw NoVideoGeneratedError("No request_id returned from xAI video API")
        val status = xaiPollVideo(
            client = client,
            settings = settings,
            requestId = requestId,
            callHeaders = params.headers,
            abortSignal = params.abortSignal,
            pollIntervalMs = (options["pollIntervalMs"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
                ?: DEFAULT_XAI_VIDEO_POLL_INTERVAL_MS,
            pollTimeoutMs = (options["pollTimeoutMs"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
                ?: DEFAULT_XAI_VIDEO_POLL_TIMEOUT_MS,
        )
        val statusObj = status.value.jsonObject
        val video = (JsonAccess.obj(statusObj, "video")) ?: JsonObject(emptyMap())
        if ((video["respect_moderation"] as? JsonPrimitive)?.booleanOrNull == false) {
            throw NoVideoGeneratedError("xAI video generation was blocked due to a content policy violation.")
        }
        val videoUrl = (video["url"] as? JsonPrimitive)?.contentOrNull
            ?: throw NoVideoGeneratedError("xAI video generation completed but no video URL was returned.")
        return VideoModelResult(
            videos = listOf(GeneratedFile(mediaType = "video/mp4", base64 = "", url = videoUrl)),
            warnings = warnings,
            response = LanguageModelResponseMetadata(modelId = modelId, headers = status.headers, body = status.value),
            providerMetadata = ProviderMetadata.Raw(JsonObject(mapOf("xai" to buildJsonObject {
                put("requestId", JsonPrimitive(requestId))
                put("videoUrl", JsonPrimitive(videoUrl))
                video["duration"]?.let { put("duration", it) }
                JsonAccess.obj(statusObj, "usage")?.get("cost_in_usd_ticks")?.let { put("costInUsdTicks", it) }
                statusObj["progress"]?.let { put("progress", it) }
            }))),
        )
    }

    private fun xaiVideoMode(options: JsonObject): String? {
        (options["mode"] as? JsonPrimitive)?.contentOrNull?.let { return it }
        if (!(options["videoUrl"] as? JsonPrimitive)?.contentOrNull.isNullOrBlank()) return "edit-video"
        if ((JsonAccess.arr(options, "referenceImageUrls"))?.isNotEmpty() == true) return "reference-to-video"
        return null
    }

    private fun xaiVideoWarnings(
        params: VideoGenerationParams,
        options: JsonObject,
        mode: String?,
    ): MutableList<CallWarning> {
        val warnings = mutableListOf<CallWarning>()
        if (params.fps != null) warnings += CallWarning("unsupported", "xAI video models do not support custom FPS.")
        if (params.seed != null) warnings += CallWarning("unsupported", "xAI video models do not support seed.")
        if (params.n > 1) warnings += CallWarning("unsupported", "xAI video models do not support generating multiple videos per call. Only 1 video will be generated.")
        if (mode == "edit-video" && params.durationSeconds != null) warnings += CallWarning("unsupported", "xAI video editing does not support custom duration.")
        if (mode == "edit-video" && params.aspectRatio != null) warnings += CallWarning("unsupported", "xAI video editing does not support custom aspect ratio.")
        if (mode == "edit-video" && (options["resolution"] != null || params.resolution != null)) warnings += CallWarning("unsupported", "xAI video editing does not support custom resolution.")
        if (mode == "extend-video" && params.aspectRatio != null) warnings += CallWarning("unsupported", "xAI video extension does not support custom aspect ratio.")
        if (mode == "extend-video" && (options["resolution"] != null || params.resolution != null)) warnings += CallWarning("unsupported", "xAI video extension does not support custom resolution.")
        return warnings
    }

    private fun xaiVideoRequestBody(
        modelId: String,
        params: VideoGenerationParams,
        options: JsonObject,
        mode: String?,
        warnings: MutableList<CallWarning>,
    ): JsonObject = buildJsonObject {
        val isEdit = mode == "edit-video"
        val isExtension = mode == "extend-video"
        put("model", JsonPrimitive(modelId))
        put("prompt", JsonPrimitive(params.prompt))
        if (!isEdit) params.durationSeconds?.let { put("duration", JsonPrimitive(it.toDouble())) }
        if (!isEdit && !isExtension) params.aspectRatio?.let { put("aspect_ratio", JsonPrimitive(it)) }
        if (!isEdit && !isExtension) {
            val resolution = (options["resolution"] as? JsonPrimitive)?.contentOrNull
                ?: params.resolution?.let { xaiVideoResolutionMap[it] ?: it.also {
                    warnings += CallWarning(
                        "unsupported",
                        "Unrecognized resolution \"$it\". Use providerOptions.xai.resolution with \"480p\" or \"720p\" instead.",
                    )
                } }
            if (resolution in setOf("480p", "720p")) put("resolution", JsonPrimitive(resolution))
        }
        if (isEdit || isExtension) {
            val videoUrl = (options["videoUrl"] as? JsonPrimitive)?.contentOrNull
                ?: throw InvalidArgumentError("providerOptions.xai.videoUrl", "videoUrl is required for xAI $mode mode")
            put("video", buildJsonObject { put("url", JsonPrimitive(videoUrl)) })
        }
        params.image?.let { put("image", buildJsonObject { put("url", JsonPrimitive(xaiDataUri(it))) }) }
        if (mode == "reference-to-video") {
            val urls = JsonAccess.arr(options, "referenceImageUrls").orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            put("reference_images", JsonArray(urls.map { url -> buildJsonObject { put("url", JsonPrimitive(url)) } }))
        }
        settings.putXaiProviderOptions(
            this,
            options,
            setOf("mode", "pollIntervalMs", "pollTimeoutMs", "resolution", "videoUrl", "referenceImageUrls"),
        )
    }

    private fun xaiDataUri(file: GeneratedFile): String {
        file.url?.takeIf { it.isNotBlank() }?.let { return it }
        return "data:${file.mediaType};base64,${file.base64}"
    }

    private suspend fun xaiPollVideo(
        client: HttpClient,
        settings: XaiProviderSettings,
        requestId: String,
        callHeaders: Map<String, String>,
        abortSignal: AbortSignal,
        pollIntervalMs: Long,
        pollTimeoutMs: Long,
    ): HttpJsonResponse {
        val interval = pollIntervalMs.coerceAtLeast(1L)
        val maxPollAttempts = ceil(pollTimeoutMs.coerceAtLeast(1L).toDouble() / interval.toDouble()).toInt().coerceAtLeast(1)
        repeat(maxPollAttempts) { attempt ->
            if (pollIntervalMs > 0 && attempt > 0) delay(pollIntervalMs)
            val status = settings.xaiGetJson(
                client = client,
                url = "${settings.baseURL.trimEnd('/')}/videos/$requestId",
                headers = settings.xaiHeaders(callHeaders),
                abortSignal = abortSignal,
            )
            val body = status.value.jsonObject
            val statusValue = (body["status"] as? JsonPrimitive)?.contentOrNull
            val video = JsonAccess.obj(body, "video")
            val hasVideoUrl = (video?.get("url") as? JsonPrimitive)?.contentOrNull != null
            if (statusValue == "done" || (statusValue == null && hasVideoUrl)) return status
            if (statusValue in setOf("failed", "error")) throw NoVideoGeneratedError("xAI video generation failed: $body")
        }
        throw NoVideoGeneratedError("xAI video generation timed out after ${pollTimeoutMs}ms")
    }
}

private const val DEFAULT_XAI_VIDEO_POLL_INTERVAL_MS: Long = 5_000L
private const val DEFAULT_XAI_VIDEO_POLL_TIMEOUT_MS: Long = 600_000L


private val xaiVideoResolutionMap = mapOf(
    "1280x720" to "720p",
    "854x480" to "480p",
    "640x480" to "480p",
)

