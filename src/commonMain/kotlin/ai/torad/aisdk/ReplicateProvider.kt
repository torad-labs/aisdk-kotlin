package ai.torad.aisdk

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
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
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.ceil

const val REPLICATE_VERSION: String = "2.0.33"

typealias ReplicateImageModelId = String
typealias ReplicateVideoModelId = String
typealias ReplicateImageProviderOptions = ReplicateImageModelOptions
typealias ReplicateVideoProviderOptions = ReplicateVideoModelOptions

@Serializable
data class ReplicateImageModelOptions(
    val maxWaitTimeInSeconds: Double? = null,
    val guidance_scale: Double? = null,
    val num_inference_steps: Double? = null,
    val negative_prompt: String? = null,
    val output_format: String? = null,
    val output_quality: Int? = null,
    val strength: Double? = null,
)

@Serializable
data class ReplicateVideoModelOptions(
    val pollIntervalMs: Long? = null,
    val pollTimeoutMs: Long? = null,
    val maxWaitTimeInSeconds: Double? = null,
    val guidance_scale: Double? = null,
    val num_inference_steps: Double? = null,
    val motion_bucket_id: Double? = null,
    val cond_aug: Double? = null,
    val decoding_t: Double? = null,
    val video_length: String? = null,
    val sizing_strategy: String? = null,
    val frames_per_second: Double? = null,
    val prompt_optimizer: Boolean? = null,
)

@Serializable
data class ReplicateProviderSettings(
    val apiToken: String? = null,
    val baseURL: String = "https://api.replicate.com/v1",
    val headers: Map<String, String> = emptyMap(),
)

interface ReplicateProvider : Provider {
    fun image(modelId: ReplicateImageModelId): ImageModel
    override fun imageModel(modelId: String): ImageModel = image(modelId)
    fun video(modelId: ReplicateVideoModelId): VideoModel
    override fun videoModel(modelId: String): VideoModel = video(modelId)
    fun textEmbeddingModel(modelId: String): Nothing = throw NoSuchModelError(providerId, "embeddingModel", modelId)
}

fun createReplicate(
    client: HttpClient,
    settings: ReplicateProviderSettings = ReplicateProviderSettings(),
): ReplicateProvider = DefaultReplicateProvider(client, settings)

val replicate: ReplicateProvider = object : ReplicateProvider {
    override val providerId: String = "replicate"
    override fun image(modelId: String): ImageModel =
        throw AiSdkException("Replicate provider is not configured. Use createReplicate(client, settings).")
    override fun video(modelId: String): VideoModel =
        throw AiSdkException("Replicate provider is not configured. Use createReplicate(client, settings).")
}

private class DefaultReplicateProvider(
    private val client: HttpClient,
    private val settings: ReplicateProviderSettings,
) : ReplicateProvider {
    override val providerId: String = "replicate"
    override fun image(modelId: String): ImageModel = ReplicateImageModel(client, settings, modelId)
    override fun video(modelId: String): VideoModel = ReplicateVideoModel(client, settings, modelId)
    override fun languageModel(modelId: String): LanguageModel = throw NoSuchModelError(providerId, "languageModel", modelId)
    override fun embeddingModel(modelId: String): EmbeddingModel = throw NoSuchModelError(providerId, "embeddingModel", modelId)
}

private class ReplicateImageModel(
    private val client: HttpClient,
    private val settings: ReplicateProviderSettings,
    override val modelId: String,
) : ImageModel {
    override val provider: String = "replicate"

    override suspend fun generate(params: ImageGenerationParams): ImageModelResult {
        params.abortSignal.throwIfAborted()
        val warnings = mutableListOf<CallWarning>()
        val options = replicateOptions(params.providerOptions)
        val model = replicateModelRef(modelId)
        val input = buildJsonObject {
            put("prompt", JsonPrimitive(params.prompt))
            params.aspectRatio?.let { put("aspect_ratio", JsonPrimitive(it)) }
            params.size?.let { put("size", JsonPrimitive(it)) }
            params.seed?.let { put("seed", JsonPrimitive(it)) }
            put("num_outputs", JsonPrimitive(params.n))
            putReplicateImageInputs(modelId, params.files, params.mask, warnings)
            putReplicateProviderOptions(options, replicateImageExcludedOptionKeys)
        }
        val response = replicatePostJson(
            client = client,
            url = if (model.version != null) {
                "${settings.baseURL.trimEnd('/')}/predictions"
            } else {
                "${settings.baseURL.trimEnd('/')}/models/${model.ownerModel}/predictions"
            },
            body = buildJsonObject {
                put("input", input)
                model.version?.let { put("version", JsonPrimitive(it)) }
            },
            headers = replicateHeaders(
                settings = settings,
                callHeaders = params.headers,
                extraHeaders = replicatePreferHeader(options),
            ),
        )
        val output = response.value.jsonObject["output"]
            ?: throw AiSdkException("Replicate image response is missing output")
        val imageUrls = when (output) {
            is JsonArray -> output.map { it.jsonPrimitive.contentOrNull ?: throw AiSdkException("Replicate image output contains a non-string URL") }
            else -> listOf(output.jsonPrimitive.contentOrNull ?: throw AiSdkException("Replicate image output is not a URL"))
        }
        val images = imageUrls.map { url ->
            replicateDownloadImage(client, url, params.abortSignal)
        }
        return ImageModelResult(
            images = images,
            warnings = warnings,
            response = LanguageModelResponseMetadata(modelId = modelId, headers = response.headers),
        )
    }
}

private class ReplicateVideoModel(
    private val client: HttpClient,
    private val settings: ReplicateProviderSettings,
    override val modelId: String,
) : VideoModel {
    override val provider: String = "replicate.video"

    override suspend fun generate(params: VideoGenerationParams): VideoModelResult {
        params.abortSignal.throwIfAborted()
        val options = replicateOptions(params.providerOptions)
        val model = replicateModelRef(modelId)
        val input = buildJsonObject {
            put("prompt", JsonPrimitive(params.prompt))
            params.image?.let { put("image", JsonPrimitive(it.replicateDataUri())) }
            params.aspectRatio?.let { put("aspect_ratio", JsonPrimitive(it)) }
            (params.resolution ?: params.size)?.let { put("size", JsonPrimitive(it)) }
            params.durationSeconds?.let { put("duration", JsonPrimitive(it.toDouble())) }
            params.fps?.let { put("fps", JsonPrimitive(it)) }
            params.seed?.let { put("seed", JsonPrimitive(it)) }
            putReplicateProviderOptions(options, replicateVideoExcludedOptionKeys)
        }
        val submitted = replicatePostJson(
            client = client,
            url = if (model.version != null) {
                "${settings.baseURL.trimEnd('/')}/predictions"
            } else {
                "${settings.baseURL.trimEnd('/')}/models/${model.ownerModel}/predictions"
            },
            body = buildJsonObject {
                put("input", input)
                model.version?.let { put("version", JsonPrimitive(it)) }
            },
            headers = replicateHeaders(
                settings = settings,
                callHeaders = params.headers,
                extraHeaders = replicatePreferHeader(options),
            ),
        )
        val prediction = replicatePollVideoPrediction(
            client = client,
            settings = settings,
            initialPrediction = submitted.value.jsonObject,
            options = options,
            abortSignal = params.abortSignal,
        )
        val videoUrl = prediction["output"]?.jsonPrimitive?.contentOrNull
            ?: throw AiSdkException("No video URL in Replicate response")
        return VideoModelResult(
            videos = listOf(
                GeneratedFile(
                    mediaType = "video/mp4",
                    base64 = "",
                    url = videoUrl,
                    providerMetadata = mapOf("url" to JsonPrimitive(videoUrl)),
                ),
            ),
            response = LanguageModelResponseMetadata(modelId = modelId, headers = submitted.headers),
            providerMetadata = mapOf("replicate" to replicateVideoProviderMetadata(prediction, videoUrl)),
        )
    }
}

private data class ReplicateModelRef(
    val ownerModel: String,
    val version: String?,
)

private data class ReplicateJsonResponse(
    val value: JsonElement,
    val headers: Map<String, String>,
)

private val replicateJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
}

private val replicateImageExcludedOptionKeys = setOf("maxWaitTimeInSeconds")
private val replicateVideoExcludedOptionKeys = setOf("pollIntervalMs", "pollTimeoutMs", "maxWaitTimeInSeconds")
private const val REPLICATE_MAX_FLUX_2_INPUT_IMAGES = 8
private const val DEFAULT_REPLICATE_VIDEO_POLL_INTERVAL_MS: Long = 2_000L
private const val DEFAULT_REPLICATE_VIDEO_POLL_TIMEOUT_MS: Long = 300_000L

private fun replicateModelRef(modelId: String): ReplicateModelRef {
    val colon = modelId.indexOf(':')
    return if (colon < 0) {
        ReplicateModelRef(ownerModel = modelId, version = null)
    } else {
        ReplicateModelRef(ownerModel = modelId.substring(0, colon), version = modelId.substring(colon + 1))
    }
}

private fun JsonObjectBuilder.putReplicateImageInputs(
    modelId: String,
    files: List<ImageGenerationFile>,
    mask: ImageGenerationFile?,
    warnings: MutableList<CallWarning>,
) {
    val isFlux2 = modelId.startsWith("black-forest-labs/flux-2-")
    if (files.isNotEmpty()) {
        if (isFlux2) {
            files.take(REPLICATE_MAX_FLUX_2_INPUT_IMAGES).forEachIndexed { index, file ->
                val key = if (index == 0) "input_image" else "input_image_${index + 1}"
                put(key, JsonPrimitive(file.replicateDataUri()))
            }
            if (files.size > REPLICATE_MAX_FLUX_2_INPUT_IMAGES) {
                warnings += CallWarning(
                    type = "other",
                    message = "Flux-2 models support up to $REPLICATE_MAX_FLUX_2_INPUT_IMAGES input images. Additional images are ignored.",
                )
            }
        } else {
            put("image", JsonPrimitive(files.first().replicateDataUri()))
            if (files.size > 1) {
                warnings += CallWarning(
                    type = "other",
                    message = "This Replicate model only supports a single input image. Additional images are ignored.",
                )
            }
        }
    }
    if (mask != null) {
        if (isFlux2) {
            warnings += CallWarning(
                type = "other",
                message = "Flux-2 models do not support mask input. The mask will be ignored.",
            )
        } else {
            put("mask", JsonPrimitive(mask.replicateDataUri()))
        }
    }
}

private fun ImageGenerationFile.replicateDataUri(): String {
    url?.takeIf { it.isNotBlank() }?.let { return it }
    val mediaType = mediaType ?: "application/octet-stream"
    val data = base64?.takeIf { it.isNotBlank() }
        ?: throw AiSdkException("Replicate image file must include either url or base64 data.")
    return "data:$mediaType;base64,$data"
}

private fun GeneratedFile.replicateDataUri(): String {
    url?.takeIf { it.isNotBlank() }?.let { return it }
    return "data:$mediaType;base64,$base64"
}

private fun JsonObjectBuilder.putReplicateProviderOptions(options: JsonObject, excludedKeys: Set<String>) {
    for ((key, value) in options) {
        if (key !in excludedKeys) put(key, value)
    }
}

private fun replicatePreferHeader(options: JsonObject): Map<String, String> {
    val maxWait = options["maxWaitTimeInSeconds"]?.jsonPrimitive?.contentOrNull
    return mapOf("prefer" to if (maxWait != null) "wait=$maxWait" else "wait")
}

private suspend fun replicatePostJson(
    client: HttpClient,
    url: String,
    body: JsonObject,
    headers: Map<String, String>,
): ReplicateJsonResponse {
    val response = client.request(url) {
        method = HttpMethod.Post
        contentType(ContentType.Application.Json)
        headers.forEach { (name, value) -> header(name, value) }
        setBody(replicateJson.encodeToString(JsonElement.serializer(), body))
    }
    return response.parseReplicateJson()
}

private suspend fun replicateGetJson(
    client: HttpClient,
    url: String,
    headers: Map<String, String>,
    abortSignal: AbortSignal,
): ReplicateJsonResponse {
    abortSignal.throwIfAborted()
    val response = client.request(url) {
        method = HttpMethod.Get
        headers.forEach { (name, value) -> header(name, value) }
    }
    return response.parseReplicateJson()
}

private suspend fun replicateDownloadImage(
    client: HttpClient,
    url: String,
    abortSignal: AbortSignal,
): GeneratedFile {
    abortSignal.throwIfAborted()
    val response = client.request(url) { method = HttpMethod.Get }
    val bytes = response.bodyAsBytes()
    if (response.status.value !in 200..299) {
        throw AiSdkException("Replicate image download failed (${response.status.value}): ${bytes.decodeToString().ifBlank { "request failed" }}")
    }
    val headers = response.headers.entries().associate { it.key to it.value.joinToString(",") }
    return GeneratedFile(
        mediaType = headers.replicateHeaderValue(HttpHeaders.ContentType) ?: "image/png",
        base64 = convertByteArrayToBase64(bytes),
    )
}

private suspend fun replicatePollVideoPrediction(
    client: HttpClient,
    settings: ReplicateProviderSettings,
    initialPrediction: JsonObject,
    options: JsonObject,
    abortSignal: AbortSignal,
): JsonObject {
    var prediction = initialPrediction
    val pollIntervalMs = options["pollIntervalMs"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        ?: DEFAULT_REPLICATE_VIDEO_POLL_INTERVAL_MS
    val pollTimeoutMs = options["pollTimeoutMs"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        ?: DEFAULT_REPLICATE_VIDEO_POLL_TIMEOUT_MS
    val maxPollAttempts = ceil(pollTimeoutMs.coerceAtLeast(1L).toDouble() / pollIntervalMs.coerceAtLeast(1L).toDouble())
        .toInt()
        .coerceAtLeast(1)
    var attempts = 0
    while (prediction.replicateStatus() in setOf("starting", "processing")) {
        if (attempts >= maxPollAttempts) {
            throw AiSdkException("Video generation timed out after ${pollTimeoutMs}ms")
        }
        if (pollIntervalMs > 0) delay(pollIntervalMs)
        abortSignal.throwIfAborted()
        val pollUrl = prediction["urls"]?.jsonObject?.get("get")?.jsonPrimitive?.contentOrNull
            ?: throw AiSdkException("Replicate prediction response is missing urls.get")
        prediction = replicateGetJson(
            client = client,
            url = pollUrl,
            headers = replicateHeaders(settings),
            abortSignal = abortSignal,
        ).value.jsonObject
        attempts++
    }
    when (prediction.replicateStatus()) {
        "failed" -> throw AiSdkException("Video generation failed: ${prediction["error"]?.jsonPrimitive?.contentOrNull ?: "Unknown error"}")
        "canceled" -> throw AiSdkException("Video generation was canceled")
    }
    return prediction
}

private fun JsonObject.replicateStatus(): String =
    this["status"]?.jsonPrimitive?.contentOrNull ?: throw AiSdkException("Replicate prediction response is missing status")

private suspend fun HttpResponse.parseReplicateJson(): ReplicateJsonResponse {
    val raw = bodyAsText()
    if (status.value !in 200..299) {
        throw AiSdkException("Replicate request failed (${status.value}): ${replicateErrorMessage(raw)}")
    }
    return ReplicateJsonResponse(
        value = if (raw.isBlank()) JsonObject(emptyMap()) else replicateJson.parseToJsonElement(raw),
        headers = headers.entries().associate { it.key to it.value.joinToString(",") },
    )
}

private fun replicateVideoProviderMetadata(prediction: JsonObject, videoUrl: String): JsonElement = buildJsonObject {
    put("videos", JsonArray(listOf(buildJsonObject {
        put("url", JsonPrimitive(videoUrl))
    })))
    putIfPresent("predictionId", prediction["id"])
    putIfPresent("metrics", prediction["metrics"])
}

private fun replicateHeaders(
    settings: ReplicateProviderSettings,
    callHeaders: Map<String, String> = emptyMap(),
    extraHeaders: Map<String, String> = emptyMap(),
): Map<String, String> {
    val base = linkedMapOf<String, String?>()
    settings.apiToken?.takeIf { it.isNotBlank() }?.let { base[HttpHeaders.Authorization] = "Bearer $it" }
    settings.headers.forEach { (key, value) -> base[key] = value }
    callHeaders.forEach { (key, value) -> base[key] = value }
    extraHeaders.forEach { (key, value) -> base[key] = value }
    return withUserAgentSuffix(base, "ai-sdk/replicate/$REPLICATE_VERSION")
}

private fun replicateOptions(providerOptions: Map<String, JsonElement>): JsonObject =
    providerOptions["replicate"] as? JsonObject ?: JsonObject(emptyMap())

private fun replicateErrorMessage(raw: String): String {
    val obj = runCatching { replicateJson.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return raw.ifBlank { "request failed" }
    return obj["detail"]?.jsonPrimitive?.contentOrNull
        ?: obj["error"]?.jsonPrimitive?.contentOrNull
        ?: raw.ifBlank { "request failed" }
}

private fun JsonObjectBuilder.putIfPresent(key: String, value: JsonElement?) {
    if (value != null && value !is JsonNull) put(key, value)
}

private fun Map<String, String>.replicateHeaderValue(name: String): String? =
    entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
