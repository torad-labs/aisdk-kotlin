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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs
import kotlin.math.ceil

const val BLACK_FOREST_LABS_VERSION: String = "1.0.34"

typealias BlackForestLabsImageModelId = String
typealias BlackForestLabsAspectRatio = String
typealias BlackForestLabsImageProviderOptions = BlackForestLabsImageModelOptions

@Serializable
data class BlackForestLabsImageModelOptions(
    val imagePrompt: String? = null,
    val imagePromptStrength: Double? = null,
    val inputImage: String? = null,
    val inputImage2: String? = null,
    val inputImage3: String? = null,
    val inputImage4: String? = null,
    val inputImage5: String? = null,
    val inputImage6: String? = null,
    val inputImage7: String? = null,
    val inputImage8: String? = null,
    val inputImage9: String? = null,
    val inputImage10: String? = null,
    val steps: Int? = null,
    val guidance: Double? = null,
    val width: Int? = null,
    val height: Int? = null,
    val outputFormat: String? = null,
    val promptUpsampling: Boolean? = null,
    val raw: Boolean? = null,
    val safetyTolerance: Int? = null,
    val webhookSecret: String? = null,
    val webhookUrl: String? = null,
    val pollIntervalMillis: Long? = null,
    val pollTimeoutMillis: Long? = null,
)

@Serializable
data class BlackForestLabsProviderSettings(
    val apiKey: String? = null,
    val baseURL: String = "https://api.bfl.ai/v1",
    val headers: Map<String, String> = emptyMap(),
    val pollIntervalMillis: Long? = null,
    val pollTimeoutMillis: Long? = null,
)

interface BlackForestLabsProvider : Provider {
    fun image(modelId: BlackForestLabsImageModelId): ImageModel
    override fun imageModel(modelId: String): ImageModel = image(modelId)
    fun textEmbeddingModel(modelId: String): Nothing = throw NoSuchModelError(providerId, "embeddingModel", modelId)
}

fun createBlackForestLabs(
    client: HttpClient,
    settings: BlackForestLabsProviderSettings = BlackForestLabsProviderSettings(),
): BlackForestLabsProvider = DefaultBlackForestLabsProvider(client, settings)

val blackForestLabs: BlackForestLabsProvider = object : BlackForestLabsProvider {
    override val providerId: String = "black-forest-labs"
    override fun image(modelId: String): ImageModel =
        throw AiSdkException("Black Forest Labs provider is not configured. Use createBlackForestLabs(client, settings).")
}

private class DefaultBlackForestLabsProvider(
    private val client: HttpClient,
    private val settings: BlackForestLabsProviderSettings,
) : BlackForestLabsProvider {
    override val providerId: String = "black-forest-labs"
    override fun image(modelId: String): ImageModel = BlackForestLabsImageModel(client, settings, modelId)
    override fun languageModel(modelId: String): LanguageModel = throw NoSuchModelError(providerId, "languageModel", modelId)
    override fun embeddingModel(modelId: String): EmbeddingModel = throw NoSuchModelError(providerId, "embeddingModel", modelId)
}

private class BlackForestLabsImageModel(
    private val client: HttpClient,
    private val settings: BlackForestLabsProviderSettings,
    override val modelId: String,
) : ImageModel {
    override val provider: String = "black-forest-labs.image"

    override suspend fun generate(params: ImageGenerationParams): ImageModelResult {
        params.abortSignal.throwIfAborted()
        val options = bflOptions(params.providerOptions)
        val args = bflRequestBody(modelId, params, options)
        val headers = bflHeaders(settings, params.headers)
        val submit = bflPostJson(
            client = client,
            url = "${settings.baseURL.trimEnd('/')}/$modelId",
            body = args.body,
            headers = headers,
        )
        val submitBody = submit.value.jsonObject
        val requestId = submitBody["id"]?.jsonPrimitive?.contentOrNull
            ?: throw AiSdkException("Black Forest Labs submit response is missing id")
        val pollingUrl = submitBody["polling_url"]?.jsonPrimitive?.contentOrNull
            ?: throw AiSdkException("Black Forest Labs submit response is missing polling_url")
        val pollResult = bflPollForImage(
            client = client,
            pollingUrl = bflPollUrl(pollingUrl, requestId),
            headers = headers,
            abortSignal = params.abortSignal,
            pollIntervalMillis = options["pollIntervalMillis"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                ?: settings.pollIntervalMillis
                ?: DEFAULT_BFL_POLL_INTERVAL_MILLIS,
            pollTimeoutMillis = options["pollTimeoutMillis"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                ?: settings.pollTimeoutMillis
                ?: DEFAULT_BFL_POLL_TIMEOUT_MILLIS,
        )
        val downloaded = bflDownloadImage(client, pollResult.imageUrl, headers, params.abortSignal)
        return ImageModelResult(
            images = listOf(downloaded.file),
            warnings = args.warnings,
            response = LanguageModelResponseMetadata(modelId = modelId, headers = downloaded.headers),
            providerMetadata = mapOf(
                "blackForestLabs" to bflProviderMetadata(submitBody, pollResult.result),
            ),
        )
    }
}

private const val DEFAULT_BFL_POLL_INTERVAL_MILLIS: Long = 500L
private const val DEFAULT_BFL_POLL_TIMEOUT_MILLIS: Long = 60_000L

private val bflJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
}

private data class BflArgs(
    val body: JsonObject,
    val warnings: List<CallWarning>,
)

private data class BflJsonResponse(
    val value: JsonElement,
    val headers: Map<String, String>,
)

private data class BflPollResult(
    val imageUrl: String,
    val result: JsonObject,
)

private data class BflDownloadedImage(
    val file: GeneratedFile,
    val headers: Map<String, String>,
)

private fun bflRequestBody(
    modelId: String,
    params: ImageGenerationParams,
    options: JsonObject,
): BflArgs {
    val warnings = mutableListOf<CallWarning>()
    val finalAspectRatio = params.aspectRatio ?: params.size?.let(::bflSizeToAspectRatio)
    if (params.size != null && params.aspectRatio == null) {
        warnings += CallWarning(
            type = "unsupported",
            message = "Deriving aspect_ratio from size. Use width and height provider options to specify dimensions for models that support them.",
        )
    } else if (params.size != null && params.aspectRatio != null) {
        warnings += CallWarning(
            type = "unsupported",
            message = "Black Forest Labs ignores size when aspectRatio is provided. Use width and height provider options to specify dimensions for models that support them.",
        )
    }
    val (sizeWidth, sizeHeight) = bflParseSize(params.size)
    val width = options["width"]?.jsonPrimitive?.intOrNull ?: sizeWidth
    val height = options["height"]?.jsonPrimitive?.intOrNull ?: sizeHeight
    return BflArgs(
        body = buildJsonObject {
            put("prompt", JsonPrimitive(params.prompt))
            putStringIfNotNull("aspect_ratio", finalAspectRatio)
            putIntIfNotNull("seed", params.seed)
            putIntIfNotNull("width", width)
            putIntIfNotNull("height", height)
            putIntIfNotNull("steps", options["steps"]?.jsonPrimitive?.intOrNull)
            putDoubleIfNotNull("guidance", options["guidance"]?.jsonPrimitive?.doubleOrNull)
            putDoubleIfNotNull("image_prompt_strength", options["imagePromptStrength"]?.jsonPrimitive?.doubleOrNull)
            putStringIfNotNull("image_prompt", options["imagePrompt"]?.jsonPrimitive?.contentOrNull)
            putBflInputImages(modelId, params.files)
            putStringIfNotNull("mask", params.mask?.bflValue())
            putStringIfNotNull("output_format", options["outputFormat"]?.jsonPrimitive?.contentOrNull)
            putBooleanIfNotNull("prompt_upsampling", options["promptUpsampling"]?.jsonPrimitive?.booleanOrNull)
            putBooleanIfNotNull("raw", options["raw"]?.jsonPrimitive?.booleanOrNull)
            putIntIfNotNull("safety_tolerance", options["safetyTolerance"]?.jsonPrimitive?.intOrNull)
            putStringIfNotNull("webhook_secret", options["webhookSecret"]?.jsonPrimitive?.contentOrNull)
            putStringIfNotNull("webhook_url", options["webhookUrl"]?.jsonPrimitive?.contentOrNull)
        },
        warnings = warnings,
    )
}

private fun JsonObjectBuilder.putBflInputImages(modelId: String, files: List<ImageGenerationFile>) {
    if (files.size > 10) throw AiSdkException("Black Forest Labs supports up to 10 input images.")
    val inputImageField = if (modelId == "flux-pro-1.0-fill") "image" else "input_image"
    files.forEachIndexed { index, file ->
        val suffix = if (index == 0) "" else "_${index + 1}"
        putStringIfNotNull("$inputImageField$suffix", file.bflValue())
    }
}

private fun ImageGenerationFile.bflValue(): String? =
    url?.takeIf { it.isNotBlank() } ?: base64?.takeIf { it.isNotBlank() }

private suspend fun bflPostJson(
    client: HttpClient,
    url: String,
    body: JsonObject,
    headers: Map<String, String>,
): BflJsonResponse {
    val response = client.request(url) {
        method = HttpMethod.Post
        contentType(ContentType.Application.Json)
        headers.forEach { (name, value) -> header(name, value) }
        setBody(bflJson.encodeToString(JsonElement.serializer(), body))
    }
    return response.parseBflJson()
}

private suspend fun bflGetJson(
    client: HttpClient,
    url: String,
    headers: Map<String, String>,
    abortSignal: AbortSignal,
): BflJsonResponse {
    abortSignal.throwIfAborted()
    val response = client.request(url) {
        method = HttpMethod.Get
        headers.forEach { (name, value) -> header(name, value) }
    }
    return response.parseBflJson()
}

private suspend fun bflPollForImage(
    client: HttpClient,
    pollingUrl: String,
    headers: Map<String, String>,
    abortSignal: AbortSignal,
    pollIntervalMillis: Long,
    pollTimeoutMillis: Long,
): BflPollResult {
    val interval = pollIntervalMillis.coerceAtLeast(1L)
    val maxPollAttempts = ceil(pollTimeoutMillis.coerceAtLeast(1L).toDouble() / interval.toDouble()).toInt().coerceAtLeast(1)
    repeat(maxPollAttempts) { attempt ->
        abortSignal.throwIfAborted()
        val poll = bflGetJson(client, pollingUrl, headers, abortSignal).value.jsonObject
        val status = poll["status"]?.jsonPrimitive?.contentOrNull ?: poll["state"]?.jsonPrimitive?.contentOrNull
            ?: throw AiSdkException("Missing status in Black Forest Labs poll response")
        when (status) {
            "Ready" -> {
                val result = poll["result"]?.jsonObject ?: throw AiSdkException(
                    "Black Forest Labs poll response is Ready but missing result.sample",
                )
                val imageUrl = result["sample"]?.jsonPrimitive?.contentOrNull ?: throw AiSdkException(
                    "Black Forest Labs poll response is Ready but missing result.sample",
                )
                return BflPollResult(imageUrl = imageUrl, result = result)
            }
            "Error", "Failed" -> throw AiSdkException("Black Forest Labs generation failed.")
        }
        if (pollIntervalMillis > 0 && attempt < maxPollAttempts - 1) delay(pollIntervalMillis)
    }
    throw AiSdkException("Black Forest Labs generation timed out.")
}

private suspend fun bflDownloadImage(
    client: HttpClient,
    url: String,
    headers: Map<String, String>,
    abortSignal: AbortSignal,
): BflDownloadedImage {
    abortSignal.throwIfAborted()
    val response = client.request(url) {
        method = HttpMethod.Get
        headers.forEach { (name, value) -> header(name, value) }
    }
    val bytes = response.bodyAsBytes()
    if (response.status.value !in 200..299) {
        throw AiSdkException("Black Forest Labs image download failed (${response.status.value}): ${bytes.decodeToString().ifBlank { "request failed" }}")
    }
    val headersMap = response.headers.entries().associate { it.key to it.value.joinToString(",") }
    return BflDownloadedImage(
        file = GeneratedFile(
            mediaType = headersMap.bflHeaderValue(HttpHeaders.ContentType) ?: "image/png",
            base64 = convertByteArrayToBase64(bytes),
        ),
        headers = headersMap,
    )
}

private suspend fun HttpResponse.parseBflJson(): BflJsonResponse {
    val raw = bodyAsText()
    if (status.value !in 200..299) {
        throw AiSdkException("Black Forest Labs request failed (${status.value}): ${bflErrorMessage(raw)}")
    }
    return BflJsonResponse(
        value = if (raw.isBlank()) JsonObject(emptyMap()) else bflJson.parseToJsonElement(raw),
        headers = headers.entries().associate { it.key to it.value.joinToString(",") },
    )
}

private fun bflProviderMetadata(submit: JsonObject, result: JsonObject): JsonElement = buildJsonObject {
    put("images", JsonArray(listOf(buildJsonObject {
        putIfPresent("seed", result["seed"])
        putIfPresent("start_time", result["start_time"])
        putIfPresent("end_time", result["end_time"])
        putIfPresent("duration", result["duration"])
        putIfPresent("cost", submit["cost"])
        putIfPresent("inputMegapixels", submit["input_mp"])
        putIfPresent("outputMegapixels", submit["output_mp"])
    })))
}

private fun bflHeaders(settings: BlackForestLabsProviderSettings, callHeaders: Map<String, String>): Map<String, String> {
    val base = linkedMapOf<String, String?>()
    settings.apiKey?.takeIf { it.isNotBlank() }?.let { base["x-key"] = it }
    settings.headers.forEach { (key, value) -> base[key] = value }
    callHeaders.forEach { (key, value) -> base[key] = value }
    return withUserAgentSuffix(base, "ai-sdk/black-forest-labs/$BLACK_FOREST_LABS_VERSION")
}

private fun bflOptions(providerOptions: Map<String, JsonElement>): JsonObject =
    providerOptions["blackForestLabs"] as? JsonObject ?: JsonObject(emptyMap())

private fun bflPollUrl(pollingUrl: String, requestId: String): String {
    val hasId = pollingUrl.substringAfter('?', missingDelimiterValue = "").split('&').any { it.substringBefore('=') == "id" }
    if (hasId) return pollingUrl
    val separator = if ('?' in pollingUrl) "&" else "?"
    return "$pollingUrl${separator}id=$requestId"
}

private fun bflParseSize(size: String?): Pair<Int?, Int?> {
    if (size == null) return null to null
    val width = size.substringBefore('x', missingDelimiterValue = "").toIntOrNull()
    val height = size.substringAfter('x', missingDelimiterValue = "").toIntOrNull()
    return width to height
}

private fun bflSizeToAspectRatio(size: String): String? {
    val (width, height) = bflParseSize(size)
    if (width == null || height == null || width <= 0 || height <= 0) return null
    val divisor = bflGcd(width, height)
    return "${width / divisor}:${height / divisor}"
}

private fun bflGcd(a: Int, b: Int): Int {
    var x = abs(a)
    var y = abs(b)
    while (y != 0) {
        val next = x % y
        x = y
        y = next
    }
    return x.coerceAtLeast(1)
}

private fun bflErrorMessage(raw: String): String {
    val obj = runCatching { bflJson.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return raw.ifBlank { "request failed" }
    val detail = obj["detail"]
    return when {
        detail?.jsonPrimitive?.contentOrNull != null -> detail.jsonPrimitive.content
        detail != null && detail !is JsonNull -> detail.toString()
        obj["message"]?.jsonPrimitive?.contentOrNull != null -> obj["message"]?.jsonPrimitive?.content.orEmpty()
        else -> raw.ifBlank { "request failed" }
    }
}

private fun JsonObjectBuilder.putIfPresent(key: String, value: JsonElement?) {
    if (value != null && value !is JsonNull) put(key, value)
}

private fun JsonObjectBuilder.putStringIfNotNull(key: String, value: String?) {
    if (value != null) put(key, JsonPrimitive(value))
}

private fun JsonObjectBuilder.putIntIfNotNull(key: String, value: Int?) {
    if (value != null) put(key, JsonPrimitive(value))
}

private fun JsonObjectBuilder.putDoubleIfNotNull(key: String, value: Double?) {
    if (value != null) put(key, JsonPrimitive(value))
}

private fun JsonObjectBuilder.putBooleanIfNotNull(key: String, value: Boolean?) {
    if (value != null) put(key, JsonPrimitive(value))
}

private fun Map<String, String>.bflHeaderValue(name: String): String? =
    entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
