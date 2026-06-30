package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import ai.torad.aisdk.providers.GoogleHttp.googlePostJson
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
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
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

internal class GoogleGenerativeAIEmbeddingModel(
    private val client: HttpClient,
    private val settings: GoogleGenerativeAIProviderSettings,
    override val modelId: String,
) : EmbeddingModel {
    override val provider: String = settings.name
    override val maxEmbeddingsPerCall: Int = 2048
    override val supportsParallelCalls: Boolean = true

    override suspend fun embed(params: EmbeddingModelCallParams): EmbeddingModelResult {
        params.abortSignal.throwIfAborted()
        if (params.values.size > maxEmbeddingsPerCall) {
            throw TooManyEmbeddingValuesForCallError(provider, modelId, maxEmbeddingsPerCall, params.values)
        }
        val options = JsonAccess.obj(params.providerOptions.toMap(), "google") ?: JsonObject(emptyMap())
        val single = params.values.size == 1
        val body = if (single) {
            GoogleMedia.googleSingleEmbeddingBody(modelId, params.values.single(), options)
        } else {
            GoogleMedia.googleBatchEmbeddingBody(modelId, params.values, options)
        }
        val response = googlePostJson(
            client = client,
            url = "${settings.baseURL.trimEnd('/')}/models/$modelId:${if (single) "embedContent" else "batchEmbedContents"}",
            body = body,
            headers = settings.googleHeaders(params.headers),
            abortSignal = params.abortSignal,
            parseJson = true,
        )
        val embeddings = if (single) {
            val embedding = JsonAccess.obj(response.value.jsonObject, "embedding")
            val values = (embedding?.get("values") as? JsonArray).orEmpty()
            listOf(
                values.map { WireDecoder.embeddingFloat(it, provider) },
            )
        } else {
            (JsonAccess.arr(response.value.jsonObject, "embeddings")).orEmpty().map { item ->
                val values = ((item as? JsonObject)?.get("values") as? JsonArray).orEmpty()
                values.map { WireDecoder.embeddingFloat(it, provider) }
            }
        }
        return EmbeddingModelResult(
            embeddings = embeddings,
            usage = EmbeddingUsage(raw = response.value),
            request = LanguageModelRequestMetadata(body),
            response = LanguageModelResponseMetadata(modelId = modelId, headers = response.headers, body = response.value),
        )
    }
}

internal class GoogleGenerativeAIImageModel(
    private val client: HttpClient,
    private val settings: GoogleGenerativeAIProviderSettings,
    override val modelId: String,
) : ImageModel {
    override val provider: String = settings.name

    override suspend fun generate(params: ImageGenerationParams): ImageModelResult {
        params.abortSignal.throwIfAborted()
        return if (modelId.startsWith("gemini", ignoreCase = true)) {
            generateGeminiImage(params)
        } else {
            generateImagen(params)
        }
    }

    private suspend fun generateImagen(params: ImageGenerationParams): ImageModelResult {
        if (params.files.isNotEmpty()) throw UnsupportedFunctionalityError("imageEditing", "Google Generative AI Imagen models do not support image editing. Use Google Vertex AI for image editing.")
        if (params.mask != null) throw UnsupportedFunctionalityError("imageMask", "Google Generative AI Imagen models do not support masks. Use Google Vertex AI for image editing.")
        val warnings = mutableListOf<CallWarning>()
        if (params.size != null) warnings += CallWarning("unsupported", "size")
        if (params.seed != null) warnings += CallWarning("unsupported", "seed")
        val options = JsonAccess.obj(params.providerOptions.toMap(), "google") ?: JsonObject(emptyMap())
        val body = buildJsonObject {
            put("instances", JsonArray(listOf(buildJsonObject { put("prompt", JsonPrimitive(params.prompt)) })))
            put(
                "parameters",
                buildJsonObject {
                    put("sampleCount", JsonPrimitive(params.n))
                    put("aspectRatio", options["aspectRatio"] ?: JsonPrimitive(params.aspectRatio ?: "1:1"))
                    options.forEach { (key, value) -> if (value !is JsonNull && key !in setOf("googleSearch")) put(key, value) }
                },
            )
        }
        val response = googlePostJson(
            client = client,
            url = "${settings.baseURL.trimEnd('/')}/models/$modelId:predict",
            body = body,
            headers = settings.googleHeaders(params.headers),
            abortSignal = params.abortSignal,
            parseJson = true,
        )
        val responseObject = WireDecoder.objectValue(response.value, provider, "image generation response")
        val predictions = WireDecoder.requiredArray(responseObject, "predictions", provider, "image generation response")
        val images = predictions.mapIndexed { index, prediction ->
            val obj = WireDecoder.objectValue(prediction, provider, "image generation response", "$.predictions[$index]")
            GeneratedFile(
                mediaType = "image/png",
                base64 = WireDecoder.requiredString(obj, "bytesBase64Encoded", provider, "image generation response", "$.predictions[$index]"),
            )
        }
        if (images.isEmpty()) throw NoImageGeneratedError("Google image response contained no predictions.")
        return ImageModelResult(images, warnings, LanguageModelResponseMetadata(modelId = modelId, headers = response.headers, body = response.value), ProviderMetadata.Raw(JsonObject(mapOf("google" to response.value))))
    }

    private suspend fun generateGeminiImage(params: ImageGenerationParams): ImageModelResult {
        if (params.n > 1) throw UnsupportedFunctionalityError("imageMultiSample", "Gemini image models do not support n > 1.")
        if (params.mask != null) throw UnsupportedFunctionalityError("imageMask", "Gemini image models do not support mask-based image editing.")
        val message = ModelMessage(
            MessageRole.User,
            buildList {
                add(ContentPart.Text(params.prompt))
                params.files.forEach { file ->
                    add(ContentPart.File(file.mediaType ?: "image/png", file.base64 ?: throw UnsupportedFunctionalityError("imageInputUrl", "Gemini image input URLs are not supported in this facade."), file.filename))
                }
            },
        )
        val result = GoogleGenerativeAILanguageModel(client, settings, modelId).generate(
            LanguageModelCallParams(
                messages = listOf(message),
                seed = params.seed,
                providerOptions = ProviderOptions.ofPairs(
                    "google" to buildJsonObject {
                        put("responseModalities", JsonArray(listOf(JsonPrimitive("IMAGE"))))
                        params.aspectRatio?.let {
                            put("imageConfig", buildJsonObject { put("aspectRatio", JsonPrimitive(it)) })
                        }
                    },
                ),
                headers = params.headers,
                abortSignal = params.abortSignal,
            ),
        )
        val images = result.content.filterIsInstance<ContentPart.File>()
            .map { GeneratedFile(mediaType = it.mediaType, base64 = it.base64, filename = it.filename, providerMetadata = it.providerMetadata) }
        if (images.isEmpty()) throw NoImageGeneratedError("Gemini image response contained no image file parts.")
        return ImageModelResult(images, result.warnings, result.response, result.providerMetadata)
    }
}

internal class GoogleGenerativeAIVideoModel(
    private val client: HttpClient,
    private val settings: GoogleGenerativeAIProviderSettings,
    override val modelId: String,
) : VideoModel {
    override val provider: String = settings.name

    override suspend fun generate(params: VideoGenerationParams): VideoModelResult {
        params.abortSignal.throwIfAborted()
        val options = JsonAccess.obj(params.providerOptions.toMap(), "google") ?: JsonObject(emptyMap())
        val body = GoogleMedia.googleVideoRequestBody(params, options)
        val operation = googlePostJson(
            client = client,
            url = "${settings.baseURL.trimEnd('/')}/models/$modelId:predictLongRunning",
            body = body,
            headers = settings.googleHeaders(params.headers),
            abortSignal = params.abortSignal,
            parseJson = true,
        ).value.let { WireDecoder.objectValue(it, provider, "video operation response") }
        val operationName = WireDecoder.requiredString(operation, "name", provider, "video operation response")
        var current = operation
        val pollInterval = (options["pollIntervalMs"] as? JsonPrimitive)?.intOrNull?.toLong()
            ?: settings.videoPollIntervalMillis
        val maxAttempts = ((options["maxPollAttempts"] as? JsonPrimitive)?.intOrNull ?: settings.videoMaxPollAttempts)
            .coerceAtLeast(1)
        var headers = emptyMap<String, String>()
        repeat(maxAttempts) {
            if ((current["done"] as? JsonPrimitive)?.booleanOrNull == true) return@repeat
            if (pollInterval > 0) delay(pollInterval)
            val poll = googleGetJsonWithRetry(
                client = client,
                url = "${settings.baseURL.trimEnd('/')}/$operationName",
                headers = settings.googleHeaders(params.headers),
                abortSignal = params.abortSignal,
                retryDelayMillis = pollInterval,
            )
            current = WireDecoder.objectValue(poll.value, provider, "video poll response")
            headers = poll.headers
        }
        if ((current["done"] as? JsonPrimitive)?.booleanOrNull != true) {
            throw NoVideoGeneratedError("Google video generation timed out after $maxAttempts poll attempts.")
        }
        (JsonAccess.obj(current, "error"))?.let { error ->
            val message = (error["message"] as? JsonPrimitive)?.contentOrNull ?: error
            throw NoVideoGeneratedError("Google video generation failed: $message")
        }
        val responseObject = WireDecoder.objectValue(
            WireDecoder.required(current, "response", provider, "video poll response"),
            provider,
            "video poll response",
            "$.response",
        )
        val videoResponse = WireDecoder.objectValue(
            WireDecoder.required(responseObject, "generateVideoResponse", provider, "video poll response", "$.response"),
            provider,
            "video poll response",
            "$.response.generateVideoResponse",
        )
        val samples = WireDecoder.requiredArray(videoResponse, "generatedSamples", provider, "video poll response", "$.response.generateVideoResponse")
        val videos = samples.mapIndexed { index, sample ->
            val sampleObject = WireDecoder.objectValue(sample, provider, "video poll response", "$.response.generateVideoResponse.generatedSamples[$index]")
            val video = WireDecoder.objectValue(
                WireDecoder.required(sampleObject, "video", provider, "video poll response", "$.response.generateVideoResponse.generatedSamples[$index]"),
                provider,
                "video poll response",
                "$.response.generateVideoResponse.generatedSamples[$index].video",
            )
            val uri = WireDecoder.requiredString(video, "uri", provider, "video poll response", "$.response.generateVideoResponse.generatedSamples[$index].video")
            GeneratedFile(
                mediaType = "video/mp4",
                base64 = "",
                url = uri,
                providerMetadata = ProviderMetadata.Raw(JsonObject(mapOf(
                    "google" to buildJsonObject {
                        put("uri", JsonPrimitive(uri))
                        put("requiresApiKey", JsonPrimitive(settings.apiKey != null))
                    },
                ))),
            )
        }
        if (videos.isEmpty()) throw NoVideoGeneratedError("Google video response contained no videos.")
        return VideoModelResult(videos = videos, response = LanguageModelResponseMetadata(modelId = modelId, headers = headers, body = current), providerMetadata = ProviderMetadata.Raw(JsonObject(mapOf("google" to current))))
    }

    private suspend fun googleGetJsonWithRetry(
        client: HttpClient,
        url: String,
        headers: Map<String, String>,
        abortSignal: AbortSignal,
        parseJson: Boolean = true,
        maxRetries: Int = 2,
        retryDelayMillis: Long = 0,
    ): HttpJsonResponse {
        var attempt = 0
        while (true) {
            abortSignal.throwIfAborted()
            val response = client.request(url) {
                method = HttpMethod.Get
                headers.forEach { (name, value) -> header(name, value) }
            }
            if (response.status.value !in 500..599 || attempt >= maxRetries) {
                return with(GoogleHttp) { response.parseGoogleResponse(url, parseJson = parseJson) }
            }
            response.bodyAsText()
            attempt += 1
            if (retryDelayMillis > 0) delay(retryDelayMillis)
        }
    }
}

// Request-body builders for the Google embedding / image / video model families.
internal object GoogleMedia {
    fun googleSingleEmbeddingBody(modelId: String, value: String, options: JsonObject): JsonObject = buildJsonObject {
    put("model", JsonPrimitive("models/$modelId"))
    put("content", buildJsonObject { put("parts", JsonArray(listOf(buildJsonObject { put("text", JsonPrimitive(value)) }))) })
    options["outputDimensionality"]?.let { put("outputDimensionality", it) }
    options["taskType"]?.let { put("taskType", it) }
}

    fun googleBatchEmbeddingBody(modelId: String, values: List<String>, options: JsonObject): JsonObject = buildJsonObject {
    put(
        "requests",
        JsonArray(
            values.map { value ->
                buildJsonObject {
                    put("model", JsonPrimitive("models/$modelId"))
                    put("content", buildJsonObject {
                        put("role", JsonPrimitive("user"))
                        put("parts", JsonArray(listOf(buildJsonObject { put("text", JsonPrimitive(value)) })))
                    })
                    options["outputDimensionality"]?.let { put("outputDimensionality", it) }
                    options["taskType"]?.let { put("taskType", it) }
                }
            },
        ),
    )
}

    fun googleVideoRequestBody(params: VideoGenerationParams, options: JsonObject): JsonObject = buildJsonObject {
    put(
        "instances",
        JsonArray(
            listOf(
                buildJsonObject {
                    put("prompt", JsonPrimitive(params.prompt))
                    params.image?.let { image ->
                        put(
                            "image",
                            buildJsonObject {
                                put("inlineData", buildJsonObject {
                                    put("mimeType", JsonPrimitive(image.mediaType))
                                    put("data", JsonPrimitive(image.base64))
                                })
                            },
                        )
                    }
                    options["referenceImages"]?.let { put("referenceImages", it) }
                },
            ),
        ),
    )
    put(
        "parameters",
        buildJsonObject {
            put("sampleCount", JsonPrimitive(params.n))
            params.aspectRatio?.let { put("aspectRatio", JsonPrimitive(it)) }
            params.durationSeconds?.let { put("durationSeconds", JsonPrimitive(it)) }
            params.seed?.let { put("seed", JsonPrimitive(it)) }
            params.resolution?.let { put("resolution", JsonPrimitive(googleVideoResolution(it))) }
            options["personGeneration"]?.let { put("personGeneration", it) }
            options["negativePrompt"]?.let { put("negativePrompt", it) }
        },
    )
}
    fun googleVideoResolution(resolution: String): String = when (resolution) {
    "1280x720" -> "720p"
    "1920x1080" -> "1080p"
    "3840x2160" -> "4k"
    else -> resolution
}
}
