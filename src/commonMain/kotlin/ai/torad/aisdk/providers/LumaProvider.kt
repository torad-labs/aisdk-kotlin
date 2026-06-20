package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import io.ktor.client.HttpClient
import io.ktor.client.request.request
import io.ktor.client.statement.bodyAsBytes
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
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

public const val LUMA_VERSION: String = "2.0.33"

public typealias LumaImageModelId = String
public typealias LumaImageProviderOptions = LumaImageModelOptions

@Serializable
public data class LumaImageModelOptions(
    val referenceType: String? = null,
    val images: JsonArray? = null,
    val pollIntervalMillis: Long? = null,
    val maxPollAttempts: Int? = null,
)

@Serializable
public data class LumaProviderSettings(
    val apiKey: String? = null,
    val baseURL: String = "https://api.lumalabs.ai",
    val headers: Map<String, String> = emptyMap(),
)

public interface LumaProvider : Provider {
    public fun image(modelId: LumaImageModelId): ImageModel
    override fun imageModel(modelId: String): ImageModel = image(modelId)
    public fun textEmbeddingModel(modelId: String): Nothing = throw NoSuchModelError(providerId, "embeddingModel", modelId)
}

public fun createLuma(
    client: HttpClient,
    settings: LumaProviderSettings = LumaProviderSettings(),
): LumaProvider = DefaultLumaProvider(client, settings)

public val luma: LumaProvider = object : LumaProvider {
    override val providerId: String = "luma"
    override fun image(modelId: String): ImageModel =
        throw AiSdkRuntimeException("Luma provider is not configured. Use createLuma(client, settings).")
}

private class DefaultLumaProvider(
    private val client: HttpClient,
    private val settings: LumaProviderSettings,
) : LumaProvider {
    override val providerId: String = "luma"
    override fun image(modelId: String): ImageModel = LumaImageModel(client, settings, modelId)
    override fun languageModel(modelId: String): LanguageModel = throw NoSuchModelError(providerId, "languageModel", modelId)
    override fun embeddingModel(modelId: String): EmbeddingModel = throw NoSuchModelError(providerId, "embeddingModel", modelId)
}

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
        val body = lumaRequestBody(modelId, params, options)
        val create = lumaPostJson(
            client = client,
            url = "${settings.baseURL.trimEnd('/')}/dream-machine/v1/generations/image",
            body = body,
            headers = lumaHeaders(settings, params.headers),
        )
        val generationId = create.value.jsonObject["id"]?.jsonPrimitive?.contentOrNull
            ?: throw AiSdkRuntimeException("Luma generation response is missing id")
        val imageUrl = lumaPollImageUrl(
            client = client,
            baseURL = settings.baseURL.trimEnd('/'),
            generationId = generationId,
            headers = lumaHeaders(settings, params.headers),
            abortSignal = params.abortSignal,
            pollIntervalMillis = options["pollIntervalMillis"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: DEFAULT_LUMA_POLL_INTERVAL_MILLIS,
            maxPollAttempts = options["maxPollAttempts"]?.jsonPrimitive?.intOrNull ?: DEFAULT_LUMA_MAX_POLL_ATTEMPTS,
        )
        val image = lumaDownloadImage(client, imageUrl, params.abortSignal)
        return ImageModelResult(
            images = listOf(image),
            warnings = warnings,
            response = LanguageModelResponseMetadata(modelId = modelId, headers = create.headers, body = create.value),
        )
    }
}

private const val DEFAULT_LUMA_POLL_INTERVAL_MILLIS: Long = 500L
private const val DEFAULT_LUMA_MAX_POLL_ATTEMPTS: Int = 120


private fun lumaRequestBody(
    modelId: String,
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
        throw AiSdkRuntimeException("Luma AI does not support mask-based image editing. Use the prompt and reference images instead.",)
    }
    if (params.files.isEmpty()) return
    val referenceType = options["referenceType"]?.jsonPrimitive?.contentOrNull ?: "image"
    val imageConfigs = options["images"]?.jsonArray.orEmpty().map { it.jsonObject }
    params.files.forEach { file ->
        if (file.url.isNullOrBlank()) {
            throw AiSdkRuntimeException("Luma AI only supports URL-based images. Base64 image data is not supported.")
        }
    }
    when (referenceType) {
        "image" -> {
            if (params.files.size > 4) throw AiSdkRuntimeException("Luma AI image supports up to 4 reference images.")
            put("image", JsonArray(params.files.mapIndexed { index, file ->
                buildJsonObject {
                    put("url", JsonPrimitive(file.url.orEmpty()))
                    put("weight", imageConfigs.getOrNull(index)?.get("weight") ?: JsonPrimitive(0.85f))
                }
            }))
        }
        "style" -> put("style", JsonArray(params.files.mapIndexed { index, file ->
            buildJsonObject {
                put("url", JsonPrimitive(file.url.orEmpty()))
                put("weight", imageConfigs.getOrNull(index)?.get("weight") ?: JsonPrimitive(0.8f))
            }
        }))
        "character" -> {
            val identities = linkedMapOf<String, MutableList<String>>()
            params.files.forEachIndexed { index, file ->
                val id = imageConfigs.getOrNull(index)?.get("id")?.jsonPrimitive?.contentOrNull ?: "identity0"
                identities.getOrPut(id) { mutableListOf() } += file.url.orEmpty()
            }
            identities.forEach { (id, images) ->
                if (images.size > 4) throw AiSdkRuntimeException("Luma AI character supports up to 4 images per identity. Identity '$id' has ${images.size} images.")
            }
            put("character", buildJsonObject {
                identities.forEach { (id, images) ->
                    put(id, buildJsonObject {
                        put("images", JsonArray(images.map(::JsonPrimitive)))
                    })
                }
            })
        }
        "modify_image" -> {
            if (params.files.size > 1) throw AiSdkRuntimeException("Luma AI modify_image only supports a single input image.")
            put("modify_image", buildJsonObject {
                put("url", JsonPrimitive(params.files.single().url.orEmpty()))
                put("weight", imageConfigs.firstOrNull()?.get("weight") ?: JsonPrimitive(1.0f))
            })
        }
        else -> throw AiSdkRuntimeException("Unsupported Luma referenceType: $referenceType")
    }
}

private val lumaNonRequestOptionKeys = setOf("pollIntervalMillis", "maxPollAttempts", "referenceType", "images")

private suspend fun lumaPostJson(
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
        errorMessage = ::lumaErrorMessage,
    )

private suspend fun lumaGetJson(
    client: HttpClient,
    url: String,
    headers: Map<String, String>,
): HttpJsonResponse =
    requestJson(
        client = client,
        url = url,
        method = HttpMethod.Get,
        headers = headers,
        errorMessage = ::lumaErrorMessage,
    )

private suspend fun lumaPollImageUrl(
    client: HttpClient,
    baseURL: String,
    generationId: String,
    headers: Map<String, String>,
    abortSignal: AbortSignal,
    pollIntervalMillis: Long,
    maxPollAttempts: Int,
): String {
    repeat(maxPollAttempts.coerceAtLeast(1)) { attempt ->
        abortSignal.throwIfAborted()
        val status = lumaGetJson(client, "$baseURL/dream-machine/v1/generations/$generationId", headers)
        val body = status.value.jsonObject
        when (body["state"]?.jsonPrimitive?.contentOrNull) {
            "completed" -> return body["assets"]?.jsonObject?.get("image")?.jsonPrimitive?.contentOrNull
                ?: throw AiSdkRuntimeException("Image generation completed but no image was found.")
            "failed" -> throw AiSdkRuntimeException("Image generation failed.")
        }
        if (pollIntervalMillis > 0 && attempt < maxPollAttempts - 1) delay(pollIntervalMillis)
    }
    throw AiSdkRuntimeException("Image generation timed out after $maxPollAttempts attempts.")
}

private suspend fun lumaDownloadImage(
    client: HttpClient,
    url: String,
    abortSignal: AbortSignal,
): GeneratedFile {
    abortSignal.throwIfAborted()
    val response = client.request(url) { method = HttpMethod.Get }
    val bytes = response.bodyAsBytes()
    val headers = response.flattenedHeaders()
    if (response.status.value !in 200..299) {
        val raw = bytes.decodeToString()
        throw apiCallError(
            url = url,
            statusCode = response.status.value,
            rawBody = raw,
            headers = headers,
            message = "Luma image download failed (${response.status.value}): ${raw.ifBlank { "request failed" }}",
        )
    }
    return GeneratedFile(
        mediaType = headers.headerValue(HttpHeaders.ContentType) ?: "image/png",
        base64 = Base64Codec.encode(bytes),
    )
}

private fun lumaHeaders(settings: LumaProviderSettings, callHeaders: Map<String, String>): Map<String, String> {
    val base = linkedMapOf<String, String>()
    settings.apiKey?.takeIf { it.isNotBlank() }?.let { base[HttpHeaders.Authorization] = "Bearer $it" }
    base.putAll(settings.headers)
    base.putAll(callHeaders)
    return ProviderHeaders.withUserAgentSuffix(base, "ai-sdk/luma/$LUMA_VERSION")
}

private fun lumaOptions(providerOptions: Map<String, JsonElement>): JsonObject =
    providerOptions["luma"] as? JsonObject ?: JsonObject(emptyMap())

private fun lumaErrorMessage(statusCode: Int, parsed: JsonElement?, raw: String): String {
    val obj = parsed as? JsonObject
    val details = obj?.get("detail")?.jsonArray?.firstOrNull()?.jsonObject?.get("msg")?.jsonPrimitive?.contentOrNull
    val message = details ?: obj?.get("error")?.jsonPrimitive?.contentOrNull ?: raw.ifBlank { "request failed" }
    return "Luma request failed ($statusCode): $message"
}

private fun Map<String, String>.headerValue(name: String): String? =
    entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
