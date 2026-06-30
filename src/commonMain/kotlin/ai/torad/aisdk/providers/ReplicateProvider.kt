package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import ai.torad.aisdk.ProviderMetadata
import io.ktor.client.HttpClient
import io.ktor.client.request.request
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
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
import kotlin.math.ceil

public const val REPLICATE_VERSION: String = "2.0.33"
private const val REPLICATE_DEFAULT_MAX_IMAGES_PER_CALL: Int = 1
private const val REPLICATE_FLUX_2_MAX_IMAGES_PER_CALL: Int = 8

public typealias ReplicateImageProviderOptions = ReplicateImageModelOptions
public typealias ReplicateVideoProviderOptions = ReplicateVideoModelOptions

@Serializable
public data class ReplicateImageModelOptions(
    val maxWaitTimeInSeconds: Double? = null,
    val guidance_scale: Double? = null,
    val num_inference_steps: Double? = null,
    val negative_prompt: String? = null,
    val output_format: String? = null,
    val output_quality: Int? = null,
    val strength: Double? = null,
)

@Serializable
public data class ReplicateVideoModelOptions(
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
public data class ReplicateProviderSettings(
    val apiToken: String? = null,
    val baseURL: String = "https://api.replicate.com/v1",
    val headers: Map<String, String> = emptyMap(),
) {
    internal fun replicateOptions(providerOptions: ProviderOptions): JsonObject =
        JsonAccess.obj(providerOptions.toMap(), "replicate") ?: JsonObject(emptyMap())

    internal fun replicatePreferHeader(options: JsonObject): Map<String, String> {
        val maxWait = (options["maxWaitTimeInSeconds"] as? JsonPrimitive)?.contentOrNull
        return mapOf("prefer" to if (maxWait != null) "wait=$maxWait" else "wait")
    }

    internal fun putReplicateProviderOptions(builder: JsonObjectBuilder, options: JsonObject, excludedKeys: Set<String>) {
        for ((key, value) in options) {
            if (key !in excludedKeys) builder.put(key, value)
        }
    }

    internal fun replicateHeaders(
        callHeaders: Map<String, String> = emptyMap(),
        extraHeaders: Map<String, String> = emptyMap(),
    ): Map<String, String> {
        val base = linkedMapOf<String, String?>()
        apiToken?.takeIf { it.isNotBlank() }?.let { base[HttpHeaders.Authorization] = "Bearer $it" }
        headers.forEach { (key, value) -> base[key] = value }
        callHeaders.forEach { (key, value) -> base[key] = value }
        extraHeaders.forEach { (key, value) -> base[key] = value }
        return ProviderHeaders.withUserAgentSuffix(base, "ai-sdk/replicate/$REPLICATE_VERSION")
    }

    internal suspend fun replicatePostJson(
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
            errorMessage = ::replicateErrorMessage,
        )

    internal suspend fun replicateGetJson(
        client: HttpClient,
        url: String,
        headers: Map<String, String>,
        abortSignal: AbortSignal,
    ): HttpJsonResponse {
        abortSignal.throwIfAborted()
        return HttpTransport.requestJson(
            client = client,
            url = url,
            method = HttpMethod.Get,
            headers = headers,
            errorMessage = ::replicateErrorMessage,
        )
    }

    private fun replicateErrorMessage(statusCode: Int, parsed: JsonElement?, raw: String): String {
        val obj = parsed as? JsonObject
        val detail = (obj?.get("detail") as? JsonPrimitive)?.contentOrNull
            ?: (obj?.get("error") as? JsonPrimitive)?.contentOrNull
            ?: raw.ifBlank { "request failed" }
        return "Replicate request failed ($statusCode): $detail"
    }
}

public class ReplicateProvider(
    private val client: HttpClient,
    private val settings: ReplicateProviderSettings,
) : Provider {
    override val providerId: String = "replicate"

    public fun image(modelId: ModelId): ImageModel = ReplicateImageModel(client, settings, modelId.value)
    override fun imageModel(modelId: String): ImageModel = image(ModelId(modelId))

    public fun video(modelId: ModelId): VideoModel = ReplicateVideoModel(client, settings, modelId.value)
    override fun videoModel(modelId: String): VideoModel = video(ModelId(modelId))

    public fun textEmbeddingModel(modelId: String): Nothing = throw NoSuchModelError(providerId, "embeddingModel", modelId)

    override fun languageModel(modelId: String): LanguageModel = throw NoSuchModelError(providerId, "languageModel", modelId)
    override fun embeddingModel(modelId: String): EmbeddingModel = throw NoSuchModelError(providerId, "embeddingModel", modelId)
}

/** PascalCase factory — mirrors the OpenAI provider pattern. */
public fun Replicate(
    client: HttpClient,
    settings: ReplicateProviderSettings = ReplicateProviderSettings(),
): ReplicateProvider = ReplicateProvider(client, settings)

private class ReplicateImageModel(
    private val client: HttpClient,
    private val settings: ReplicateProviderSettings,
    override val modelId: String,
) : ImageModel {
    override val provider: String = "replicate"
    override val maxImagesPerCall: Int
        get() = if (modelId.startsWith("black-forest-labs/flux-2-")) {
            REPLICATE_FLUX_2_MAX_IMAGES_PER_CALL
        } else {
            REPLICATE_DEFAULT_MAX_IMAGES_PER_CALL
        }

    override suspend fun generate(params: ImageGenerationParams): ImageModelResult {
        params.abortSignal.throwIfAborted()
        val warnings = mutableListOf<CallWarning>()
        val options = settings.replicateOptions(params.providerOptions)
        val model = ReplicateModelRef.fromModelId(modelId)
        val input = buildJsonObject {
            put("prompt", JsonPrimitive(params.prompt))
            params.aspectRatio?.let { put("aspect_ratio", JsonPrimitive(it)) }
            params.size?.let { put("size", JsonPrimitive(it)) }
            params.seed?.let { put("seed", JsonPrimitive(it)) }
            put("num_outputs", JsonPrimitive(params.n))
            putReplicateImageInputs(this, modelId, params.files, params.mask, warnings)
            settings.putReplicateProviderOptions(this, options, replicateImageExcludedOptionKeys)
        }
        val response = settings.replicatePostJson(
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
            headers = settings.replicateHeaders(
                callHeaders = params.headers,
                extraHeaders = settings.replicatePreferHeader(options),
            ),
        )
        val prediction = response.value.jsonObject
        // Inspect a terminal failure status BEFORE the output check so a failed/canceled prediction
        // surfaces its real `error` instead of a generic "missing output". Read status leniently
        // (don't require it) — a successful prefer=wait body need not carry one.
        when ((prediction["status"] as? JsonPrimitive)?.contentOrNull) {
            "failed" -> {
                val detail = (prediction["error"] as? JsonPrimitive)?.contentOrNull ?: "Unknown error"
                throw NoImageGeneratedError("Replicate image generation failed: $detail")
            }
            "canceled" -> throw NoImageGeneratedError("Replicate image generation was canceled")
        }
        val output = prediction["output"]
            ?: throw NoImageGeneratedError("Replicate image response is missing output")
        val imageUrls = when (output) {
            is JsonArray -> output.map {
                (it as? JsonPrimitive)?.contentOrNull
                    ?: throw InvalidResponseDataError(output, "Replicate image output contains a non-string URL")
            }
            else -> listOf(
                (output as? JsonPrimitive)?.contentOrNull
                    ?: throw InvalidResponseDataError(output, "Replicate image output is not a URL"),
            )
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

    private fun putReplicateImageInputs(
        builder: JsonObjectBuilder,
        modelId: String,
        files: List<ImageGenerationFile>,
        mask: ImageGenerationFile?,
        warnings: MutableList<CallWarning>,
    ) = with(builder) {
        val isFlux2 = modelId.startsWith("black-forest-labs/flux-2-")
        if (files.isNotEmpty()) {
            if (isFlux2) {
                files.take(REPLICATE_MAX_FLUX_2_INPUT_IMAGES).forEachIndexed { index, file ->
                    val key = if (index == 0) "input_image" else "input_image_${index + 1}"
                    put(key, JsonPrimitive(replicateDataUri(file)))
                }
                if (files.size > REPLICATE_MAX_FLUX_2_INPUT_IMAGES) {
                    warnings += CallWarning(
                        type = "other",
                        message = "Flux-2 models support up to $REPLICATE_MAX_FLUX_2_INPUT_IMAGES input images. Additional images are ignored.",
                    )
                }
            } else {
                put("image", JsonPrimitive(replicateDataUri(files.first())))
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
                put("mask", JsonPrimitive(replicateDataUri(mask)))
            }
        }
    }

    private fun replicateDataUri(file: ImageGenerationFile): String {
        file.url?.takeIf { it.isNotBlank() }?.let { return it }
        val mediaType = file.mediaType ?: "application/octet-stream"
        val data = file.base64?.takeIf { it.isNotBlank() }
            ?: throw InvalidArgumentError("file", "Replicate image file must include either url or base64 data.")
        return "data:$mediaType;base64,$data"
    }

    private suspend fun replicateDownloadImage(
        client: HttpClient,
        url: String,
        abortSignal: AbortSignal,
    ): GeneratedFile {
        abortSignal.throwIfAborted()
        val (statusCode, headers, bytes) = HttpTransport.withRealTimeout(DEFAULT_REQUEST_TIMEOUT_MS) {
            val response = client.request(url) { method = HttpMethod.Get }
            Triple(
                response.status.value,
                with(HttpTransport) { response.flattenedHeaders() },
                with(HttpTransport) { response.bodyAsBytesCapped(url) },
            )
        }
        if (statusCode !in 200..299) {
            val raw = bytes.decodeToString()
            throw ApiCallError(
                url = url,
                statusCode = statusCode,
                rawBody = raw,
                headers = headers,
                message = "Replicate image download failed ($statusCode): ${raw.ifBlank { "request failed" }}",
            )
        }
        return GeneratedFile(
            mediaType = replicateHeaderValue(headers, HttpHeaders.ContentType) ?: "image/png",
            base64 = Base64Codec.encode(bytes),
        )
    }

    private fun replicateHeaderValue(headers: Map<String, String>, name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}

private class ReplicateVideoModel(
    private val client: HttpClient,
    private val settings: ReplicateProviderSettings,
    override val modelId: String,
) : VideoModel {
    override val provider: String = "replicate.video"
    override val maxVideosPerCall: Int = 1

    override suspend fun generate(params: VideoGenerationParams): VideoModelResult {
        params.abortSignal.throwIfAborted()
        val options = settings.replicateOptions(params.providerOptions)
        val model = ReplicateModelRef.fromModelId(modelId)
        val input = buildJsonObject {
            put("prompt", JsonPrimitive(params.prompt))
            params.image?.let { put("image", JsonPrimitive(replicateDataUri(it))) }
            params.aspectRatio?.let { put("aspect_ratio", JsonPrimitive(it)) }
            (params.resolution ?: params.size)?.let { put("size", JsonPrimitive(it)) }
            params.durationSeconds?.let { put("duration", JsonPrimitive(it.toDouble())) }
            params.fps?.let { put("fps", JsonPrimitive(it)) }
            params.seed?.let { put("seed", JsonPrimitive(it)) }
            settings.putReplicateProviderOptions(this, options, replicateVideoExcludedOptionKeys)
        }
        val submitted = settings.replicatePostJson(
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
            headers = settings.replicateHeaders(
                callHeaders = params.headers,
                extraHeaders = settings.replicatePreferHeader(options),
            ),
        )
        val prediction = replicatePollVideoPrediction(
            initialPrediction = submitted.value.jsonObject,
            options = options,
            abortSignal = params.abortSignal,
        )
        val output = prediction["output"]
            ?: throw NoVideoGeneratedError("No video URL in Replicate response")
        val videoUrl = when (output) {
            is JsonArray -> (output.firstOrNull() as? JsonPrimitive)?.contentOrNull
            else -> (output as? JsonPrimitive)?.contentOrNull
        } ?: throw NoVideoGeneratedError("No video URL in Replicate response")
        return VideoModelResult(
            videos = listOf(
                GeneratedFile(
                    mediaType = "video/mp4",
                    base64 = "",
                    url = videoUrl,
                    providerMetadata = ProviderMetadata.Raw(JsonObject(mapOf("url" to JsonPrimitive(videoUrl)))),
                ),
            ),
            response = LanguageModelResponseMetadata(modelId = modelId, headers = submitted.headers),
            providerMetadata = ProviderMetadata.Raw(JsonObject(mapOf("replicate" to replicateVideoProviderMetadata(prediction, videoUrl)))),
        )
    }

    private fun replicateDataUri(file: GeneratedFile): String {
        file.url?.takeIf { it.isNotBlank() }?.let { return it }
        return "data:${file.mediaType};base64,${file.base64}"
    }

    private suspend fun replicatePollVideoPrediction(
        initialPrediction: JsonObject,
        options: JsonObject,
        abortSignal: AbortSignal,
    ): JsonObject {
        var prediction = initialPrediction
        val pollIntervalMs = (options["pollIntervalMs"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
            ?: DEFAULT_REPLICATE_VIDEO_POLL_INTERVAL_MS
        val pollTimeoutMs = (options["pollTimeoutMs"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
            ?: DEFAULT_REPLICATE_VIDEO_POLL_TIMEOUT_MS
        val maxPollAttempts = ceil(pollTimeoutMs.coerceAtLeast(1L).toDouble() / pollIntervalMs.coerceAtLeast(1L).toDouble())
            .toInt()
            .coerceAtLeast(1)
        var attempts = 0
        while (replicateStatus(prediction) in setOf("starting", "processing")) {
            if (attempts >= maxPollAttempts) {
                throw NoVideoGeneratedError("Video generation timed out after ${pollTimeoutMs}ms")
            }
            if (pollIntervalMs > 0) delay(pollIntervalMs)
            abortSignal.throwIfAborted()
            val pollUrl = ((JsonAccess.obj(prediction, "urls"))?.get("get") as? JsonPrimitive)?.contentOrNull
                ?: throw InvalidResponseDataError(null, "Replicate prediction response is missing urls.get")
            prediction = settings.replicateGetJson(
                client = client,
                url = pollUrl,
                headers = settings.replicateHeaders(),
                abortSignal = abortSignal,
            ).value.jsonObject
            attempts++
        }
        when (replicateStatus(prediction)) {
            "failed" -> {
                val detail = (prediction["error"] as? JsonPrimitive)?.contentOrNull ?: "Unknown error"
                throw NoVideoGeneratedError("Video generation failed: $detail")
            }
            "canceled" -> throw NoVideoGeneratedError("Video generation was canceled")
        }
        return prediction
    }

    private fun replicateStatus(prediction: JsonObject): String =
        (prediction["status"] as? JsonPrimitive)?.contentOrNull
            ?: throw InvalidResponseDataError(null, "Replicate prediction response is missing status")

    private fun replicateVideoProviderMetadata(prediction: JsonObject, videoUrl: String): JsonElement = buildJsonObject {
        put("videos", JsonArray(listOf(buildJsonObject {
            put("url", JsonPrimitive(videoUrl))
        })))
        putIfPresent(this, "predictionId", prediction["id"])
        putIfPresent(this, "metrics", prediction["metrics"])
    }

    private fun putIfPresent(builder: JsonObjectBuilder, key: String, value: JsonElement?) {
        if (value != null && value !is JsonNull) builder.put(key, value)
    }
}

internal data class ReplicateModelRef(
    val ownerModel: String,
    val version: String?,
) {
    companion object {
        internal fun fromModelId(modelId: String): ReplicateModelRef {
            val colon = modelId.indexOf(':')
            return if (colon < 0) {
                ReplicateModelRef(ownerModel = modelId, version = null)
            } else {
                ReplicateModelRef(ownerModel = modelId.substring(0, colon), version = modelId.substring(colon + 1))
            }
        }
    }
}

private val replicateImageExcludedOptionKeys = setOf("maxWaitTimeInSeconds")
private val replicateVideoExcludedOptionKeys = setOf("pollIntervalMs", "pollTimeoutMs", "maxWaitTimeInSeconds")
private const val REPLICATE_MAX_FLUX_2_INPUT_IMAGES = 8
private const val DEFAULT_REPLICATE_VIDEO_POLL_INTERVAL_MS: Long = 2_000L
private const val DEFAULT_REPLICATE_VIDEO_POLL_TIMEOUT_MS: Long = 300_000L
