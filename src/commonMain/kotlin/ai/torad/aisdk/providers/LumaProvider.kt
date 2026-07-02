package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import dev.drewhamilton.poko.Poko
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

public const val LUMA_VERSION: String = "2.0.33"

public typealias LumaImageProviderOptions = LumaImageModelOptions

@Serializable
@Poko
/** @since 0.3.0-beta01 */
public class LumaImageModelOptions internal constructor(
    /** @since 0.3.0-beta01 */
    public val referenceType: String? = null,
    /** @since 0.3.0-beta01 */
    public val images: JsonArray? = null,
    /** @since 0.3.0-beta01 */
    public val pollIntervalMillis: Long? = null,
    /** @since 0.3.0-beta01 */
    public val maxPollAttempts: Int? = null,
)

/** @since 0.3.0-beta01 */
public class LumaImageModelOptionsBuilder {
    private var referenceType: String? = null
    private var images: JsonArray? = null
    private var pollIntervalMillis: Long? = null
    private var maxPollAttempts: Int? = null

    /** @since 0.3.0-beta01 */
    public fun referenceType(value: String?): LumaImageModelOptionsBuilder {
        referenceType = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun images(value: JsonArray?): LumaImageModelOptionsBuilder {
        images = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun pollIntervalMillis(value: Long?): LumaImageModelOptionsBuilder {
        pollIntervalMillis = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun maxPollAttempts(value: Int?): LumaImageModelOptionsBuilder {
        maxPollAttempts = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun build(): LumaImageModelOptions =
        LumaImageModelOptions(
            referenceType = referenceType,
            images = images,
            pollIntervalMillis = pollIntervalMillis,
            maxPollAttempts = maxPollAttempts,
        )
}

/** @since 0.3.0-beta01 */
public fun LumaImageModelOptions(
    block: LumaImageModelOptionsBuilder.() -> Unit = {},
): LumaImageModelOptions =
    LumaImageModelOptionsBuilder().apply(block).build()

@Serializable
@Poko
/** @since 0.3.0-beta01 */
public class LumaProviderSettings internal constructor(
    /** @since 0.3.0-beta01 */
    public val apiKey: String? = null,
    /** @since 0.3.0-beta01 */
    public val baseURL: String = "https://api.lumalabs.ai",
    /** @since 0.3.0-beta01 */
    public val headers: Map<String, String> = emptyMap(),
)

/** @since 0.3.0-beta01 */
public class LumaProviderSettingsBuilder {
    private var apiKey: String? = null
    private var baseURL: String = "https://api.lumalabs.ai"
    private var headers: Map<String, String> = emptyMap()

    /** @since 0.3.0-beta01 */
    public fun apiKey(value: String?): LumaProviderSettingsBuilder {
        apiKey = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun baseURL(value: String): LumaProviderSettingsBuilder {
        baseURL = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun headers(value: Map<String, String>): LumaProviderSettingsBuilder {
        headers = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun build(): LumaProviderSettings =
        LumaProviderSettings(
            apiKey = apiKey,
            baseURL = baseURL,
            headers = headers,
        )
}

/** @since 0.3.0-beta01 */
public fun LumaProviderSettings(
    block: LumaProviderSettingsBuilder.() -> Unit = {},
): LumaProviderSettings =
    LumaProviderSettingsBuilder().apply(block).build()

/** @since 0.3.0-beta01 */
public class LumaProvider(
    private val client: HttpClient,
    /** @since 0.3.0-beta01 */
    public val settings: LumaProviderSettings = LumaProviderSettings(),
) : Provider {
    override val providerId: String = "luma"

    /** @since 0.3.0-beta01 */
    public fun image(modelId: ModelId): ImageModel = LumaImageModel(client, settings, modelId.value)

    override fun imageModel(modelId: String): ImageModel = image(ModelId(modelId))

    override fun languageModel(modelId: String): LanguageModel =
        throw NoSuchModelError(providerId, "language", modelId)

    override fun embeddingModel(modelId: String): EmbeddingModel =
        throw NoSuchModelError(providerId, "embedding", modelId)

    /** @since 0.3.0-beta01 */
    public fun textEmbeddingModel(modelId: String): EmbeddingModel = embeddingModel(modelId)
}

/**
 * PascalCase factory — mirrors `OpenAI(...)`.
 * @since 0.3.0-beta01
 */
public fun Luma(
    client: HttpClient,
    settings: LumaProviderSettings = LumaProviderSettings(),
): LumaProvider = LumaProvider(client, settings)

private class LumaImageModel(
    private val client: HttpClient,
    private val settings: LumaProviderSettings,
    override val modelId: String,
) : ImageModel {
    override val provider: String = "luma.image"
    override val maxImagesPerCall: Int = 1

    override suspend fun generate(params: ImageGenerationParams): ImageModelResult {
        params.abortSignal.throwIfAborted()
        val warnings = mutableListOf<CallWarning>()
        if (params.seed != null) {
            warnings += CallWarning("unsupported", "This model does not support the `seed` option.")
        }
        if (params.size != null) {
            warnings += CallWarning("unsupported", "This model does not support the `size` option. Use `aspectRatio` instead.")
        }
        val options = lumaOptions(params.providerOptions)
        val body = requestBody(params, options)
        val create = postJson(
            url = "${settings.baseURL.trimEnd('/')}/dream-machine/v1/generations/image",
            body = body,
            headers = headers(params.headers),
        )
        val generationId = (create.value.jsonObject["id"] as? JsonPrimitive)?.contentOrNull
            ?: throw InvalidResponseDataError(create.value, "Luma generation response is missing id")
        val imageUrl = pollImageUrl(
            generationId = generationId,
            headers = headers(params.headers),
            abortSignal = params.abortSignal,
            pollIntervalMillis = (options["pollIntervalMillis"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
                ?: DEFAULT_LUMA_POLL_INTERVAL_MILLIS,
            maxPollAttempts = (options["maxPollAttempts"] as? JsonPrimitive)?.intOrNull
                ?: DEFAULT_LUMA_MAX_POLL_ATTEMPTS,
        )
        val image = downloadImage(imageUrl, params.abortSignal)
        return ImageModelResult(
            images = listOf(image),
            warnings = warnings,
            response = LanguageModelResponseMetadata(modelId = modelId, headers = create.headers, body = create.value),
        )
    }

    private fun requestBody(
        params: ImageGenerationParams,
        options: JsonObject,
    ): JsonObject = buildJsonObject {
        put("prompt", JsonPrimitive(params.prompt))
        params.aspectRatio?.let { put("aspect_ratio", JsonPrimitive(it)) }
        put("model", JsonPrimitive(modelId))
        putLumaEditingOptions(params, options)
        putLumaProviderOptions(options)
    }

    private fun JsonObjectBuilder.putLumaProviderOptions(options: JsonObject) {
        for ((key, value) in options) {
            if (key !in lumaNonRequestOptionKeys && value !is JsonNull) put(key, value)
        }
    }

    private fun JsonObjectBuilder.putLumaEditingOptions(
        params: ImageGenerationParams,
        options: JsonObject,
    ) {
        if (params.mask != null) {
            throw UnsupportedFunctionalityError(
                "imageEditingWithMasks",
                "Luma AI does not support mask-based image editing. Use the prompt and reference images instead.",
            )
        }
        if (params.files.isEmpty()) return
        val referenceType = (options["referenceType"] as? JsonPrimitive)?.contentOrNull ?: "image"
        val imageConfigs = (JsonAccess.arr(options, "images")).orEmpty().mapNotNull { it as? JsonObject }
        params.files.forEach { file ->
            if (file.url.isNullOrBlank()) {
                throw UnsupportedFunctionalityError(
                    "base64ImageInput",
                    "Luma AI only supports URL-based images. Base64 image data is not supported.",
                )
            }
        }
        when (referenceType) {
            "image" -> {
                if (params.files.size > 4) {
                    throw InvalidArgumentError("files", "Luma AI image supports up to 4 reference images.")
                }
                put(
                    "image",
                    JsonArray(
                        params.files.mapIndexed { index, file ->
                            buildJsonObject {
                                put("url", JsonPrimitive(file.url.orEmpty()))
                                put("weight", imageConfigs.getOrNull(index)?.get("weight") ?: JsonPrimitive(0.85f))
                            }
                        }
                    )
                )
            }
            "style" -> put(
                "style",
                JsonArray(
                    params.files.mapIndexed { index, file ->
                        buildJsonObject {
                            put("url", JsonPrimitive(file.url.orEmpty()))
                            put("weight", imageConfigs.getOrNull(index)?.get("weight") ?: JsonPrimitive(0.8f))
                        }
                    }
                )
            )
            "character" -> {
                val identities = linkedMapOf<String, MutableList<String>>()
                params.files.forEachIndexed { index, file ->
                    val id = (imageConfigs.getOrNull(index)?.get("id") as? JsonPrimitive)?.contentOrNull ?: "identity0"
                    identities.getOrPut(id) { mutableListOf() } += file.url.orEmpty()
                }
                identities.forEach { (id, images) ->
                    if (images.size > 4) {
                        throw InvalidArgumentError(
                            "files",
                            "Luma AI character supports up to 4 images per identity. Identity '$id' has ${images.size} images.",
                        )
                    }
                }
                put(
                    "character",
                    buildJsonObject {
                        identities.forEach { (id, images) ->
                            put(
                                id,
                                buildJsonObject {
                                    put("images", JsonArray(images.map(::JsonPrimitive)))
                                }
                            )
                        }
                    }
                )
            }
            "modify_image" -> {
                if (params.files.size > 1) {
                    throw InvalidArgumentError("files", "Luma AI modify_image only supports a single input image.")
                }
                put(
                    "modify_image",
                    buildJsonObject {
                        put("url", JsonPrimitive(params.files.single().url.orEmpty()))
                        put("weight", imageConfigs.firstOrNull()?.get("weight") ?: JsonPrimitive(1.0f))
                    }
                )
            }
            else -> throw InvalidArgumentError("referenceType", "Unsupported Luma referenceType: $referenceType")
        }
    }

    private suspend fun postJson(
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
            errorMessage = ::lumaErrorMessage,
        )

    private suspend fun getJson(
        url: String,
        headers: Map<String, String>,
        abortSignal: AbortSignal,
    ): HttpJsonResponse =
        AbortSignalRuntime.withAbortCancellation(abortSignal) {
            HttpTransport.requestJson(
                client = client,
                url = url,
                method = HttpMethod.Get,
                headers = headers,
                errorMessage = ::lumaErrorMessage,
                abortSignal = abortSignal,
            )
        }

    private suspend fun pollImageUrl(
        generationId: String,
        headers: Map<String, String>,
        abortSignal: AbortSignal,
        pollIntervalMillis: Long,
        maxPollAttempts: Int,
    ): String {
        val baseURL = settings.baseURL.trimEnd('/')
        repeat(maxPollAttempts.coerceAtLeast(1)) { attempt ->
            abortSignal.throwIfAborted()
            val status = getJson("$baseURL/dream-machine/v1/generations/$generationId", headers, abortSignal)
            val body = status.value.jsonObject
            when ((body["state"] as? JsonPrimitive)?.contentOrNull) {
                "completed" -> {
                    val assets = JsonAccess.obj(body, "assets")
                    return (assets?.get("image") as? JsonPrimitive)?.contentOrNull
                        ?: throw NoImageGeneratedError("Image generation completed but no image was found.")
                }
                "failed" -> throw NoImageGeneratedError("Image generation failed.")
            }
            if (pollIntervalMillis > 0 && attempt < maxPollAttempts - 1) delay(pollIntervalMillis)
        }
        throw NoImageGeneratedError("Image generation timed out after $maxPollAttempts attempts.")
    }

    private suspend fun downloadImage(
        url: String,
        abortSignal: AbortSignal,
    ): GeneratedFile {
        abortSignal.throwIfAborted()
        val (statusCode, responseHeaders, bytes) = AbortSignalRuntime.withAbortCancellation(abortSignal) {
            HttpTransport.withRealTimeout(DEFAULT_REQUEST_TIMEOUT_MS) {
                val abortRegistrations = mutableListOf<AbortSignal.AbortRegistration>()
                try {
                    val response = client.request(url) {
                        abortSignal.throwIfAborted()
                        abortRegistrations += abortSignal.register { executionContext.cancel(AbortError()) }
                        method = HttpMethod.Get
                    }
                    Triple(
                        response.status.value,
                        with(HttpTransport) { response.flattenedHeaders() },
                        with(HttpTransport) { response.bodyAsBytesCapped(url) },
                    )
                } finally {
                    abortRegistrations.forEach { it.cancel() }
                }
            }
        }
        if (statusCode !in 200..299) {
            val raw = bytes.decodeToString()
            throw ApiCallError(
                url = url,
                statusCode = statusCode,
                rawBody = raw,
                headers = responseHeaders,
                message = "Luma image download failed ($statusCode): ${raw.ifBlank { "request failed" }}",
            )
        }
        return GeneratedFile(
            mediaType = responseHeaders.headerValue(HttpHeaders.ContentType) ?: "image/png",
            base64 = Base64Codec.encode(bytes),
        )
    }

    private fun headers(callHeaders: Map<String, String>): Map<String, String> {
        val base = linkedMapOf<String, String>()
        settings.apiKey?.takeIf { it.isNotBlank() }?.let { base[HttpHeaders.Authorization] = "Bearer $it" }
        base.putAll(settings.headers)
        base.putAll(callHeaders)
        return ProviderHeaders.withUserAgentSuffix(base, "ai-sdk/luma/$LUMA_VERSION")
    }

    private fun lumaOptions(providerOptions: ProviderOptions): JsonObject =
        JsonAccess.obj(providerOptions.toMap(), "luma") ?: JsonObject(emptyMap())

    private fun lumaErrorMessage(statusCode: Int, parsed: JsonElement?, raw: String): String {
        val obj = parsed as? JsonObject
        val msgElement = ((obj?.get("detail") as? JsonArray)?.firstOrNull() as? JsonObject)?.get("msg")
        val details = (msgElement as? JsonPrimitive)?.contentOrNull
        val message = details ?: (obj?.get("error") as? JsonPrimitive)?.contentOrNull
            ?: raw.ifBlank { "request failed" }
        return "Luma request failed ($statusCode): $message"
    }

    private fun Map<String, String>.headerValue(name: String): String? =
        entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}

private const val DEFAULT_LUMA_POLL_INTERVAL_MILLIS: Long = 500L
private const val DEFAULT_LUMA_MAX_POLL_ATTEMPTS: Int = 120

private val lumaNonRequestOptionKeys = setOf("pollIntervalMillis", "maxPollAttempts", "referenceType", "images")
