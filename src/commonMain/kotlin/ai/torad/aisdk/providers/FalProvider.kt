package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
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
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

public const val FAL_VERSION: String = "2.0.34"

public typealias FalImageModelId = String
public typealias FalSpeechModelId = String
public typealias FalTranscriptionModelId = String
public typealias FalVideoModelId = String
public typealias FalImageProviderOptions = FalImageModelOptions
public typealias FalVideoProviderOptions = FalVideoModelOptions
public typealias FalErrorData = JsonObject

@Serializable
public data class FalProviderSettings(
    val apiKey: String? = null,
    val baseURL: String = "https://fal.run",
    val headers: Map<String, String> = emptyMap(),
    val transcriptionPollIntervalMillis: Long = 1_000L,
    val transcriptionMaxPollAttempts: Int = 60,
    val videoPollIntervalMillis: Long = 2_000L,
    val videoMaxPollAttempts: Int = 150,
)

@Serializable
public data class FalImageModelOptions(
    val useMultipleImages: Boolean? = null,
)

@Serializable
public data class FalSpeechModelOptions(
    val voice_setting: JsonObject? = null,
    val audio_setting: JsonObject? = null,
    val language_boost: String? = null,
    val pronunciation_dict: JsonObject? = null,
)

@Serializable
public data class FalTranscriptionModelOptions(
    val language: String? = "en",
    val diarize: Boolean? = true,
    val chunkLevel: String? = "segment",
    val version: String? = "3",
    val batchSize: Int? = 64,
    val numSpeakers: Int? = null,
)

@Serializable
public data class FalVideoModelOptions(
    val loop: Boolean? = null,
    val motionStrength: Float? = null,
    val pollIntervalMs: Long? = null,
    val pollTimeoutMs: Long? = null,
    val resolution: String? = null,
    val negativePrompt: String? = null,
    val promptOptimizer: Boolean? = null,
)

public class FalProvider(
    private val client: HttpClient,
    public val settings: FalProviderSettings,
) : Provider {
    override val providerId: String = "fal"

    public fun image(modelId: FalImageModelId): ImageModel = FalImageModel(client, settings, modelId)
    public fun speech(modelId: FalSpeechModelId): SpeechModel = FalSpeechModel(client, settings, modelId)
    public fun transcription(modelId: FalTranscriptionModelId): TranscriptionModel = FalTranscriptionModel(client, settings, modelId)
    public fun video(modelId: FalVideoModelId): VideoModel = FalVideoModel(client, settings, modelId)
    public fun textEmbeddingModel(modelId: String): Nothing = throw NoSuchModelError(providerId, "embeddingModel", modelId)

    override fun imageModel(modelId: String): ImageModel = image(modelId)
    override fun speechModel(modelId: String): SpeechModel = speech(modelId)
    override fun transcriptionModel(modelId: String): TranscriptionModel = transcription(modelId)
    override fun videoModel(modelId: String): VideoModel = video(modelId)
    override fun languageModel(modelId: String): LanguageModel = throw NoSuchModelError(providerId, "languageModel", modelId)
    override fun embeddingModel(modelId: String): EmbeddingModel = throw NoSuchModelError(providerId, "embeddingModel", modelId)
}

/** PascalCase factory — mirrors `OpenAI(...)`, the Layer-3 reference pattern. */
public fun Fal(
    client: HttpClient,
    settings: FalProviderSettings = FalProviderSettings(),
): FalProvider = FalProvider(client, settings)

private class FalImageModel(
    private val client: HttpClient,
    private val settings: FalProviderSettings,
    override val modelId: String,
) : ImageModel {
    override val provider: String = "fal.image"
    override val maxImagesPerCall: Int = 1

    override suspend fun generate(params: ImageGenerationParams): ImageModelResult {
        params.abortSignal.throwIfAborted()
        val prepared = falImageRequestBody(params)
        val response = falPostJson(
            client = client,
            url = "${settings.baseURL.trimEnd('/')}/$modelId",
            body = prepared.body,
            headers = falHeaders(settings, params.headers),
        )
        val value = response.value.jsonObject
        val targetImages = falResponseImages(value)
        if (targetImages.isEmpty()) throw NoImageGeneratedError("No fal image URL in response")

        val downloaded = targetImages.map { image ->
            val url = image["url"]?.jsonPrimitive?.contentOrNull
                ?: throw NoImageGeneratedError("No fal image URL in response")
            val bytes = falGetBinary(client, url, emptyMap(), params.abortSignal)
            GeneratedFile(
                mediaType = image["content_type"]?.jsonPrimitive?.contentOrNull
                    ?: bytes.headers.headerValue(HttpHeaders.ContentType)
                    ?: "image/png",
                base64 = Base64Codec.encode(bytes.bytes),
                filename = image["file_name"]?.jsonPrimitive?.contentOrNull,
                providerMetadata = mapOf("fal" to falImageMetadata(image)),
                url = url,
            )
        }

        return ImageModelResult(
            images = downloaded,
            warnings = prepared.warnings,
            response = LanguageModelResponseMetadata(modelId = modelId, headers = response.headers, body = response.value),
            providerMetadata = mapOf("fal" to falImageProviderMetadata(value, targetImages)),
        )
    }
}

private class FalSpeechModel(
    private val client: HttpClient,
    private val settings: FalProviderSettings,
    override val modelId: String,
) : SpeechModel {
    override val provider: String = "fal.speech"

    override suspend fun generate(params: SpeechGenerationParams): SpeechModelResult {
        params.abortSignal.throwIfAborted()
        val prepared = falSpeechRequestBody(params)
        val response = falPostJson(
            client = client,
            url = "https://fal.run/$modelId",
            body = prepared.body,
            headers = falHeaders(settings, params.headers),
        )
        val audioUrl = response.value.jsonObject["audio"]?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
            ?: throw NoSpeechGeneratedError("fal speech response is missing audio.url")
        val audio = falGetBinary(client, audioUrl, emptyMap(), params.abortSignal)
        return SpeechModelResult(
            audio = GeneratedFile(
                mediaType = audio.headers.headerValue(HttpHeaders.ContentType) ?: "audio/mpeg",
                base64 = Base64Codec.encode(audio.bytes),
                url = audioUrl,
            ),
            warnings = prepared.warnings,
            response = LanguageModelResponseMetadata(modelId = modelId, headers = response.headers, body = response.value),
        )
    }
}

private class FalTranscriptionModel(
    private val client: HttpClient,
    private val settings: FalProviderSettings,
    override val modelId: String,
) : TranscriptionModel {
    override val provider: String = "fal.transcription"

    override suspend fun transcribe(params: TranscriptionParams): TranscriptionModelResult {
        params.abortSignal.throwIfAborted()
        val body = falTranscriptionRequestBody(params)
        val queue = falPostJson(
            client = client,
            url = "https://queue.fal.run/fal-ai/$modelId",
            body = body,
            headers = falHeaders(settings, params.headers),
        )
        val requestId = queue.value.jsonObject["request_id"]?.jsonPrimitive?.contentOrNull
            ?: throw InvalidResponseDataError(queue.value, "fal transcription queue response is missing request_id")
        val result = falPollJson(
            client = client,
            url = "https://queue.fal.run/fal-ai/$modelId/requests/$requestId",
            headers = falHeaders(settings, params.headers),
            abortSignal = params.abortSignal,
            pollIntervalMillis = settings.transcriptionPollIntervalMillis,
            maxPollAttempts = settings.transcriptionMaxPollAttempts,
            timeoutMessage = "Transcription request timed out after ${settings.transcriptionPollIntervalMillis * settings.transcriptionMaxPollAttempts}ms",
        )
        val value = result.value.jsonObject
        val chunks = value["chunks"] as? JsonArray ?: JsonArray(emptyList())
        return TranscriptionModelResult(
            text = value["text"]?.jsonPrimitive?.contentOrNull,
            segments = chunks.map { chunk ->
                val obj = chunk.jsonObject
                val timestamp = obj["timestamp"] as? JsonArray
                TranscriptSegment(
                    text = obj["text"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    startSeconds = timestamp?.getOrNull(0)?.jsonPrimitive?.floatOrNull,
                    endSeconds = timestamp?.getOrNull(1)?.jsonPrimitive?.floatOrNull,
                )
            },
            response = LanguageModelResponseMetadata(modelId = modelId, headers = result.headers, body = result.value),
            providerMetadata = mapOf("fal" to result.value),
        )
    }
}

private class FalVideoModel(
    private val client: HttpClient,
    private val settings: FalProviderSettings,
    override val modelId: String,
) : VideoModel {
    override val provider: String = "fal.video"
    override val maxVideosPerCall: Int = 1

    override suspend fun generate(params: VideoGenerationParams): VideoModelResult {
        params.abortSignal.throwIfAborted()
        val options = falOptions(params.providerOptions)
        val body = falVideoRequestBody(params, options)
        val queue = falPostJson(
            client = client,
            url = "https://queue.fal.run/fal-ai/${falNormalizedVideoModelId(modelId)}",
            body = body,
            headers = falHeaders(settings, params.headers),
        )
        val responseUrl = queue.value.jsonObject["response_url"]?.jsonPrimitive?.contentOrNull
            ?: throw InvalidResponseDataError(queue.value, "No response URL returned from queue endpoint")
        val pollIntervalMillis = options["pollIntervalMs"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            ?: settings.videoPollIntervalMillis
        val result = falPollJson(
            client = client,
            url = responseUrl,
            headers = falHeaders(settings, params.headers),
            abortSignal = params.abortSignal,
            pollIntervalMillis = pollIntervalMillis,
            maxPollAttempts = options["pollTimeoutMs"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                ?.let { timeout -> (timeout / pollIntervalMillis.coerceAtLeast(1)).toInt().coerceAtLeast(1) }
                ?: settings.videoMaxPollAttempts,
            timeoutMessage = "Video generation request timed out",
        )
        val value = result.value.jsonObject
        val video = value["video"]?.jsonObject ?: throw NoVideoGeneratedError("No video URL in response")
        val videoUrl = video["url"]?.jsonPrimitive?.contentOrNull ?: throw NoVideoGeneratedError("No video URL in response")
        val mediaType = video["content_type"]?.jsonPrimitive?.contentOrNull ?: "video/mp4"
        return VideoModelResult(
            videos = listOf(
                GeneratedFile(
                    mediaType = mediaType,
                    base64 = "",
                    url = videoUrl,
                    providerMetadata = mapOf("fal" to falVideoMetadata(video)),
                ),
            ),
            response = LanguageModelResponseMetadata(modelId = modelId, headers = result.headers, body = result.value),
            providerMetadata = mapOf("fal" to falVideoProviderMetadata(value, video)),
        )
    }
}



private data class FalBinaryResponse(
    val bytes: ByteArray,
    val headers: Map<String, String>,
)

private data class FalImageRequest(
    val body: JsonObject,
    val warnings: List<CallWarning>,
)

private data class FalSpeechRequest(
    val body: JsonObject,
    val warnings: List<CallWarning>,
)

private fun falImageRequestBody(params: ImageGenerationParams): FalImageRequest {
    val warnings = mutableListOf<CallWarning>()
    val options = falOptions(params.providerOptions)
    val normalizedOptions = falImageOptions(options, warnings)
    val useMultipleImages = options["useMultipleImages"]?.jsonPrimitive?.booleanOrNull == true

    return FalImageRequest(
        body = buildJsonObject {
            put("prompt", JsonPrimitive(params.prompt))
            params.seed?.let { put("seed", JsonPrimitive(it)) }
            falImageSize(params.size, params.aspectRatio)?.let { put("image_size", it) }
            put("num_images", JsonPrimitive(params.n))
            if (params.files.isNotEmpty()) {
                if (useMultipleImages) {
                    put("image_urls", JsonArray(params.files.map(::falImageGenerationFileUrl)))
                } else {
                    put("image_url", falImageGenerationFileUrl(params.files.first()))
                    if (params.files.size > 1) {
                        warnings += CallWarning(
                            "other",
                            "Multiple input images provided but useMultipleImages is not enabled. Only the first image will be used.",
                        )
                    }
                }
            }
            params.mask?.let { put("mask_url", falImageGenerationFileUrl(it)) }
            putJsonObjectFields(normalizedOptions)
        },
        warnings = warnings,
    )
}

private fun falSpeechRequestBody(params: SpeechGenerationParams): FalSpeechRequest {
    val warnings = mutableListOf<CallWarning>()
    if (!params.instructions.isNullOrBlank()) {
        warnings += CallWarning("unsupported", "instructions")
    }
    val outputFormat = when (params.responseFormat) {
        null, "url" -> "url"
        "hex" -> "hex"
        else -> {
            warnings += CallWarning("unsupported", "outputFormat")
            "url"
        }
    }
    return FalSpeechRequest(
        body = buildJsonObject {
            put("text", JsonPrimitive(params.text))
            put("output_format", JsonPrimitive(outputFormat))
            params.voice?.let { put("voice", JsonPrimitive(it)) }
            params.speed?.let { put("speed", JsonPrimitive(it)) }
            putJsonObjectFields(falOptions(params.providerOptions))
        },
        warnings = warnings,
    )
}

private fun falTranscriptionRequestBody(params: TranscriptionParams): JsonObject {
    val options = falOptions(params.providerOptions)
    return buildJsonObject {
        put("task", JsonPrimitive("transcribe"))
        put("diarize", options["diarize"] ?: JsonPrimitive(true))
        put("chunk_level", options["chunkLevel"] ?: options["chunk_level"] ?: JsonPrimitive("word"))
        params.language?.let { put("language", JsonPrimitive(it)) }
        options["language"]?.let { put("language", it) }
        options["version"]?.let { put("version", it) }
        (options["batchSize"] ?: options["batch_size"])?.let { put("batch_size", it) }
        (options["numSpeakers"] ?: options["num_speakers"])?.let { put("num_speakers", it) }
        put("audio_url", JsonPrimitive("data:${params.audio.mediaType};base64,${params.audio.base64}"))
    }
}

private fun falVideoRequestBody(
    params: VideoGenerationParams,
    options: JsonObject,
): JsonObject = buildJsonObject {
    params.prompt.takeIf { it.isNotBlank() }?.let { put("prompt", JsonPrimitive(it)) }
    params.image?.let { put("image_url", JsonPrimitive(it.url ?: "data:${it.mediaType};base64,${it.base64}")) }
    params.aspectRatio?.let { put("aspect_ratio", JsonPrimitive(it)) }
    params.durationSeconds?.let { put("duration", JsonPrimitive("${formatFalSeconds(it)}s")) }
    params.seed?.let { put("seed", JsonPrimitive(it)) }
    (options["resolution"] ?: params.resolution?.let(::JsonPrimitive))?.let { put("resolution", it) }
    options["loop"]?.takeUnless { it is JsonNull }?.let { put("loop", it) }
    options["motionStrength"]?.takeUnless { it is JsonNull }?.let { put("motion_strength", it) }
    options["negativePrompt"]?.takeUnless { it is JsonNull }?.let { put("negative_prompt", it) }
    options["promptOptimizer"]?.takeUnless { it is JsonNull }?.let { put("prompt_optimizer", it) }
    for ((key, value) in options) {
        if (key !in falVideoNonPassthroughKeys && value !is JsonNull) put(key, value)
    }
}

private fun JsonObjectBuilder.putJsonObjectFields(fields: JsonObject) {
    fields.forEach { (key, value) ->
        if (value !is JsonNull) put(key, value)
    }
}

private val falVideoNonPassthroughKeys = setOf(
    "loop",
    "motionStrength",
    "pollIntervalMs",
    "pollTimeoutMs",
    "resolution",
    "negativePrompt",
    "promptOptimizer",
)

private fun falImageOptions(
    options: JsonObject,
    warnings: MutableList<CallWarning>,
): JsonObject {
    val deprecated = mutableListOf<String>()
    val result = linkedMapOf<String, JsonElement>()
    fun putMapped(camel: String, api: String) {
        val snake = api
        val value = options[snake] ?: options[camel]
        if (options[snake] != null) deprecated += snake
        if (value != null && value !is JsonNull) result[api] = value
    }

    putMapped("imageUrl", "image_url")
    putMapped("maskUrl", "mask_url")
    putMapped("guidanceScale", "guidance_scale")
    putMapped("numInferenceSteps", "num_inference_steps")
    putMapped("enableSafetyChecker", "enable_safety_checker")
    putMapped("outputFormat", "output_format")
    putMapped("syncMode", "sync_mode")
    putMapped("safetyTolerance", "safety_tolerance")
    listOf("strength", "acceleration").forEach { key ->
        options[key]?.takeUnless { it is JsonNull }?.let { result[key] = it }
    }
    val known = setOf(
        "imageUrl", "maskUrl", "guidanceScale", "numInferenceSteps", "enableSafetyChecker", "outputFormat", "syncMode",
        "safetyTolerance", "useMultipleImages", "image_url", "mask_url", "guidance_scale", "num_inference_steps",
        "enable_safety_checker", "output_format", "sync_mode", "safety_tolerance", "strength", "acceleration",
    )
    for ((key, value) in options) {
        if (key !in known && value !is JsonNull) result[key] = value
    }
    if (deprecated.isNotEmpty()) {
        warnings += CallWarning(
            "other",
            "The following provider options use deprecated snake_case and will be removed in @ai-sdk/fal v2.0. " +
                "Please use camelCase instead: ${deprecated.joinToString(", ") { key -> "'$key' (use '${snakeToCamel(key)}')" }}",
        )
    }
    return JsonObject(result)
}

private fun falOptions(providerOptions: ProviderOptions): JsonObject =
    providerOptions.toMap()["fal"] as? JsonObject ?: JsonObject(emptyMap())

private fun falImageSize(size: String?, aspectRatio: String?): JsonElement? {
    if (size != null) {
        val width = size.substringBefore('x').toIntOrNull()
        val height = size.substringAfter('x', missingDelimiterValue = "").toIntOrNull()
        if (width != null && height != null) {
            return buildJsonObject {
                put("width", JsonPrimitive(width))
                put("height", JsonPrimitive(height))
            }
        }
    }
    return when (aspectRatio) {
        "1:1" -> JsonPrimitive("square_hd")
        "16:9" -> JsonPrimitive("landscape_16_9")
        "9:16" -> JsonPrimitive("portrait_16_9")
        "4:3" -> JsonPrimitive("landscape_4_3")
        "3:4" -> JsonPrimitive("portrait_4_3")
        "16:10" -> buildJsonObject { put("width", JsonPrimitive(1280)); put("height", JsonPrimitive(800)) }
        "10:16" -> buildJsonObject { put("width", JsonPrimitive(800)); put("height", JsonPrimitive(1280)) }
        "21:9" -> buildJsonObject { put("width", JsonPrimitive(2560)); put("height", JsonPrimitive(1080)) }
        "9:21" -> buildJsonObject { put("width", JsonPrimitive(1080)); put("height", JsonPrimitive(2560)) }
        else -> null
    }
}

private fun falImageGenerationFileUrl(file: ImageGenerationFile): JsonPrimitive =
    JsonPrimitive(file.url ?: "data:${file.mediaType ?: "application/octet-stream"};base64,${file.base64.orEmpty()}")

private fun snakeToCamel(key: String): String =
    key.replace(Regex("_([a-z])")) { it.groupValues[1].uppercase() }

private fun formatFalSeconds(value: Float): String {
    val int = value.toInt()
    return if (value == int.toFloat()) int.toString() else value.toString()
}

private fun falNormalizedVideoModelId(modelId: String): String =
    modelId.removePrefix("fal-ai/").removePrefix("fal/")

private suspend fun falPostJson(
    client: HttpClient,
    url: String,
    body: JsonObject,
    headers: Map<String, String>,
): HttpJsonResponse =
    requestJson(
        client = client,
        url = url,
        method = HttpMethod.Post,
        headers = headers,
        body = body,
        requestBodyValues = body,
        errorMessage = falErrorMessage,
        errorFromResponse = falInProgressSignal,
    )

private suspend fun falGetJson(
    client: HttpClient,
    url: String,
    headers: Map<String, String>,
): HttpJsonResponse =
    requestJson(
        client = client,
        url = url,
        method = HttpMethod.Get,
        headers = headers,
        errorMessage = falErrorMessage,
        errorFromResponse = falInProgressSignal,
    )

/**
 * fal returns 4xx with `detail == "Request is still in progress"` while a job
 * is queued; the poll loop treats that exception's message as a retry signal,
 * so it must stay a plain [AiSdkException] (not an [APICallError]). Any other
 * non-2xx falls through to the default rich [APICallError].
 */
private val falInProgressSignal: ResponseErrorFactory = { _, parsed, _, _ ->
    val detail = (parsed as? JsonObject)?.get("detail")?.jsonPrimitive?.contentOrNull
    if (detail == "Request is still in progress") InvalidResponseDataError(null, detail.orEmpty()) else null
}

private suspend fun falPollJson(
    client: HttpClient,
    url: String,
    headers: Map<String, String>,
    abortSignal: AbortSignal,
    pollIntervalMillis: Long,
    maxPollAttempts: Int,
    timeoutMessage: String,
): HttpJsonResponse {
    repeat(maxPollAttempts.coerceAtLeast(1)) { attempt ->
        abortSignal.throwIfAborted()
        val response = runCatching { falGetJson(client, url, headers) }
            .getOrElse { error ->
                if (error.message == "Request is still in progress") null else throw error
            }
        if (response != null) return response
        if (pollIntervalMillis > 0 && attempt < maxPollAttempts - 1) delay(pollIntervalMillis)
    }
    throw NoVideoGeneratedError(timeoutMessage)
}

private suspend fun falGetBinary(
    client: HttpClient,
    url: String,
    headers: Map<String, String>,
    abortSignal: AbortSignal,
): FalBinaryResponse {
    abortSignal.throwIfAborted()
    val response = client.request(url) {
        method = HttpMethod.Get
        headers.forEach { (name, value) -> header(name, value) }
    }
    val bytes = response.bodyAsBytes()
    val flattened = response.flattenedHeaders()
    if (response.status.value !in 200..299) {
        val raw = bytes.decodeToString()
        throw apiCallError(
            url = url,
            statusCode = response.status.value,
            rawBody = raw,
            headers = flattened,
            message = "fal binary request failed with status ${response.status.value}: $raw",
        )
    }
    return FalBinaryResponse(
        bytes = bytes,
        headers = flattened,
    )
}

private fun falHeaders(settings: FalProviderSettings, extra: Map<String, String>): Map<String, String> =
    ProviderHeaders.build(settings.headers, extra, "ai-sdk/fal/$FAL_VERSION") { headers ->
        settings.apiKey?.takeIf { it.isNotBlank() }?.let { headers[HttpHeaders.Authorization] = "Key $it" }
    }

private fun falResponseImages(value: JsonObject): List<JsonObject> {
    (value["images"] as? JsonArray)?.let { images -> return images.map { it.jsonObject } }
    (value["image"] as? JsonObject)?.let { return listOf(it) }
    return emptyList()
}

private fun falImageProviderMetadata(
    value: JsonObject,
    images: List<JsonObject>,
): JsonObject = buildJsonObject {
    put("images", JsonArray(images.mapIndexed { index, image ->
        buildJsonObject {
            putJsonObjectFields(falImageMetadata(image))
            val nsfw = (value["has_nsfw_concepts"] as? JsonArray)?.getOrNull(index)
                ?: (value["nsfw_content_detected"] as? JsonArray)?.getOrNull(index)
            nsfw?.let { put("nsfw", it) }
        }
    }))
    for ((key, item) in value) {
        if (key !in setOf("images", "image", "prompt", "has_nsfw_concepts", "nsfw_content_detected") && item !is JsonNull) {
            put(key, item)
        }
    }
}

private fun falImageMetadata(image: JsonObject): JsonObject = buildJsonObject {
    listOf("width", "height", "file_data", "file_size").forEach { key ->
        image[key]?.takeUnless { it is JsonNull }?.let { put(key, it) }
    }
    image["content_type"]?.takeUnless { it is JsonNull }?.let { put("contentType", it) }
    image["file_name"]?.takeUnless { it is JsonNull }?.let { put("fileName", it) }
}

private fun falVideoProviderMetadata(
    value: JsonObject,
    video: JsonObject,
): JsonObject = buildJsonObject {
    put("videos", buildJsonArray { add(falVideoMetadata(video)) })
    listOf("seed", "timings", "has_nsfw_concepts", "prompt").forEach { key ->
        value[key]?.takeUnless { it is JsonNull }?.let { put(key, it) }
    }
}

private fun falVideoMetadata(video: JsonObject): JsonObject = buildJsonObject {
    video["url"]?.let { put("url", it) }
    listOf("width", "height", "duration", "fps").forEach { key ->
        video[key]?.takeUnless { it is JsonNull }?.let { put(key, it) }
    }
    video["content_type"]?.takeUnless { it is JsonNull }?.let { put("contentType", it) }
}

private val falErrorMessage: ErrorMessageExtractor = { _, parsed, raw ->
    val obj = parsed as? JsonObject
    val validation = obj?.get("detail") as? JsonArray
    when {
        obj == null -> raw
        validation != null -> validation.joinToString("\n") { item ->
            val detail = item.jsonObject
            val loc = (detail["loc"] as? JsonArray)?.joinToString(".") { it.jsonPrimitive.contentOrNull.orEmpty() }
            val msg = detail["msg"]?.jsonPrimitive?.contentOrNull.orEmpty()
            listOfNotNull(loc?.takeIf { it.isNotBlank() }, msg.takeIf { it.isNotBlank() }).joinToString(": ")
        }
        else -> (obj["error"] as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull
            ?: obj["message"]?.jsonPrimitive?.contentOrNull
            ?: raw
    }
}

private fun Map<String, String>.headerValue(name: String): String? =
    entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
