package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import ai.torad.aisdk.ProviderMetadata
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.ceil

public const val XAI_VERSION: String = "3.0.93"

public typealias XaiChatModelId = String
public typealias XaiResponsesModelId = String
public typealias XaiImageModelId = String
public typealias XaiVideoModelId = String
public typealias XaiProviderOptions = XaiLanguageModelChatOptions
public typealias XaiResponsesProviderOptions = XaiLanguageModelResponsesOptions
public typealias XaiImageProviderOptions = XaiImageModelOptions
public typealias XaiVideoProviderOptions = XaiVideoModelOptions
public typealias XaiErrorData = JsonElement

@Serializable
public data class XaiProviderSettings(
    val baseURL: String = "https://api.x.ai/v1",
    val apiKey: String? = null,
    val headers: Map<String, String> = emptyMap(),
)

@Serializable
public data class XaiLanguageModelChatOptions(
    val reasoningEffort: String? = null,
    val logprobs: Boolean? = null,
    val topLogprobs: Int? = null,
    val parallel_function_calling: Boolean? = null,
    val searchParameters: JsonElement? = null,
)

@Serializable
public data class XaiLanguageModelResponsesOptions(
    val reasoningEffort: String? = null,
    val reasoningSummary: String? = null,
)

@Serializable
public data class XaiImageModelOptions(
    val aspect_ratio: String? = null,
    val output_format: String? = null,
    val sync_mode: Boolean? = null,
    val resolution: String? = null,
    val quality: String? = null,
    val user: String? = null,
)

@Serializable
public data class XaiVideoModelOptions(
    val mode: String? = null,
    val videoUrl: String? = null,
    val referenceImageUrls: List<String>? = null,
    val pollIntervalMs: Long? = null,
    val pollTimeoutMs: Long? = null,
    val resolution: String? = null,
)

public class XaiProvider(
    private val client: HttpClient,
    public val settings: XaiProviderSettings,
) : Provider {
    private val compatible = OpenAICompatible(client, xaiCompatibleSettings())

    override val providerId: String = "xai"
    public val tools: XaiTools = xaiTools

    public operator fun invoke(modelId: XaiChatModelId): LanguageModel = chat(modelId)

    public fun chat(modelId: XaiChatModelId): LanguageModel =
        XaiChatLanguageModel(compatible.chatModel(modelId))

    public fun responses(modelId: XaiResponsesModelId): LanguageModel =
        OpenResponses(
            client,
            OpenResponsesProviderSettings(
                url = "${settings.baseURL.trimEnd('/')}/responses",
                name = "xai",
                authHeadersProvider = { xaiHeaders(settings) },
                userAgentSuffix = null,
            ),
        ).responses(modelId)

    public fun image(modelId: XaiImageModelId): ImageModel =
        XaiImageModel(client, settings, modelId)

    public fun video(modelId: XaiVideoModelId): VideoModel =
        XaiVideoModel(client, settings, modelId)

    override fun languageModel(modelId: String): LanguageModel = chat(modelId)
    override fun imageModel(modelId: String): ImageModel = image(modelId)
    override fun videoModel(modelId: String): VideoModel = video(modelId)
    override fun embeddingModel(modelId: String): EmbeddingModel =
        throw NoSuchModelError(providerId, "embeddingModel", modelId)
    public fun textEmbeddingModel(modelId: String): Nothing = throw NoSuchModelError(providerId, "embeddingModel", modelId)

    private fun xaiCompatibleSettings(): OpenAICompatibleProviderSettings =
        OpenAICompatibleProviderSettings(
            name = "xai",
            baseUrl = settings.baseURL.trimEnd('/'),
            authHeadersProvider = { xaiHeaders(settings) },
            userAgentSuffix = null,
            providerOptionsName = "xai",
            chatMaxOutputTokensKey = "max_completion_tokens",
            supportedUrls = mapOf("image/*" to listOf("^https?://.*$")),
            transformChatRequestBody = ::xaiTransformChatBody,
        )
}

public data class XaiTools(
    val codeExecution: Tool<JsonElement, JsonElement, Any?> = codeExecution(),
    val fileSearch: Tool<JsonElement, JsonElement, Any?> = fileSearch(),
    val mcpServer: Tool<JsonElement, JsonElement, Any?> = mcpServer(),
    val viewImage: Tool<JsonElement, JsonElement, Any?> = viewImage(),
    val viewXVideo: Tool<JsonElement, JsonElement, Any?> = viewXVideo(),
    val webSearch: Tool<JsonElement, JsonElement, Any?> = webSearch(),
    val xSearch: Tool<JsonElement, JsonElement, Any?> = xSearch(),
)

public val xaiTools: XaiTools = XaiTools()

public fun codeExecution(args: JsonElement = JsonObject(emptyMap())): Tool<JsonElement, JsonElement, Any?> =
    xaiProviderTool("code_execution", "Execute code in xAI's hosted code execution environment.", args)

public fun fileSearch(args: JsonElement = JsonObject(emptyMap())): Tool<JsonElement, JsonElement, Any?> =
    xaiProviderTool("file_search", "Search xAI collections and vector stores.", args)

public fun mcpServer(args: JsonElement = JsonObject(emptyMap())): Tool<JsonElement, JsonElement, Any?> =
    xaiProviderTool("mcp", "Call tools from a remote MCP server through xAI.", args)

public fun viewImage(args: JsonElement = JsonObject(emptyMap())): Tool<JsonElement, JsonElement, Any?> =
    xaiProviderTool("view_image", "Inspect an image through xAI's hosted vision tool.", args)

public fun viewXVideo(args: JsonElement = JsonObject(emptyMap())): Tool<JsonElement, JsonElement, Any?> =
    xaiProviderTool("view_x_video", "Inspect an X video through xAI's hosted video tool.", args)

public fun webSearch(args: JsonElement = JsonObject(emptyMap())): Tool<JsonElement, JsonElement, Any?> =
    xaiProviderTool("web_search", "Search the web through xAI.", args)

public fun xSearch(args: JsonElement = JsonObject(emptyMap())): Tool<JsonElement, JsonElement, Any?> =
    xaiProviderTool("x_search", "Search X posts through xAI.", args)

/** PascalCase factory — mirrors `OpenAI(...)`. */
public fun Xai(
    client: HttpClient,
    settings: XaiProviderSettings = XaiProviderSettings(),
): XaiProvider = XaiProvider(client, settings)

/**
 * Rewrites the OpenAI-shaped chat body into xAI's shape: drops `stop` (xAI does not
 * support stop sequences and rejects the key) and strips `additionalProperties: false`
 * from every tool's parameters schema (xAI structured-output requires it removed).
 */
private fun xaiTransformChatBody(body: JsonObject): JsonObject = buildJsonObject {
    for ((key, value) in body) {
        when (key) {
            "stop" -> Unit // dropped — unsupported by xAI
            "tools" -> put("tools", xaiStripToolSchemas(value))
            else -> put(key, value)
        }
    }
}

private fun xaiStripToolSchemas(tools: JsonElement): JsonElement {
    val arr = tools as? JsonArray ?: return tools
    return JsonArray(
        arr.map { tool ->
            val obj = tool as? JsonObject ?: return@map tool
            val function = obj["function"] as? JsonObject ?: return@map tool
            val params = function["parameters"] as? JsonObject ?: return@map tool
            val cleanedParams = stripUnsupportedSchemaKeys(params, dropAdditionalProperties = true)
            JsonObject(obj + ("function" to JsonObject(function + ("parameters" to cleanedParams))))
        },
    )
}

private class XaiChatLanguageModel(
    private val delegate: LanguageModel,
) : LanguageModel by delegate {
    override val supportedUrls: Map<String, List<String>> = mapOf("image/*" to listOf("^https?://.*$"))

    override suspend fun generate(params: LanguageModelCallParams): LanguageModelResult =
        delegate.generate(params.copy(providerOptions = transformXaiChatProviderOptions(params.providerOptions)))
            .withXaiCitations()

    override fun stream(params: LanguageModelCallParams): Flow<StreamEvent> =
        delegate.stream(params.copy(providerOptions = transformXaiChatProviderOptions(params.providerOptions)))

    override fun streamResult(params: LanguageModelCallParams): LanguageModelStreamResult =
        delegate.streamResult(params.copy(providerOptions = transformXaiChatProviderOptions(params.providerOptions))).let {
            it.copy(stream = it.stream.map { event -> event })
        }

    private fun LanguageModelResult.withXaiCitations(): LanguageModelResult {
        val citations = response.body?.jsonObject?.get("citations")?.jsonArray.orEmpty()
            .mapNotNull { it.jsonPrimitive.contentOrNull }
            .map { url ->
                ContentPart.Source(
                    sourceType = StreamEvent.SourcePart.SourceType.Url,
                    url = url,
                )
            }
        return if (citations.isEmpty()) this else copy(content = content + citations)
    }
}

private class XaiImageModel(
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
        val options = xaiOptions(params.providerOptions)
        val endpoint = if (params.files.isEmpty()) "/images/generations" else "/images/edits"
        val body = buildJsonObject {
            put("model", JsonPrimitive(modelId))
            put("prompt", JsonPrimitive(params.prompt))
            put("n", JsonPrimitive(params.n))
            put("response_format", JsonPrimitive("b64_json"))
            (params.aspectRatio ?: options["aspect_ratio"]?.jsonPrimitive?.contentOrNull)?.let {
                put("aspect_ratio", JsonPrimitive(it))
            }
            putXaiProviderOptions(options, setOf("aspect_ratio"))
            putXaiImageInputs(params.files)
        }
        val response = xaiPostJson(
            client = client,
            url = "${settings.baseURL.trimEnd('/')}$endpoint",
            body = body,
            headers = xaiHeaders(settings, params.headers),
        )
        val responseObj = response.value.jsonObject
        val data = responseObj["data"]?.jsonArray.orEmpty()
        val images = data.map { image ->
            val obj = image.jsonObject
            val base64 = obj["b64_json"]?.jsonPrimitive?.contentOrNull
            if (base64 != null) {
                GeneratedFile(mediaType = "image/png", base64 = base64)
            } else {
                val url = obj["url"]?.jsonPrimitive?.contentOrNull
                    ?: throw NoImageGeneratedError("xAI image response is missing b64_json and url")
                xaiDownloadImage(client, url, params.abortSignal)
            }
        }
        return ImageModelResult(
            images = images,
            warnings = warnings,
            response = LanguageModelResponseMetadata(modelId = modelId, headers = response.headers, body = response.value),
            providerMetadata = ProviderMetadata.Raw(JsonObject(mapOf("xai" to buildJsonObject {
                put("images", JsonArray(data.map { image ->
                    val revisedPrompt = image.jsonObject["revised_prompt"]?.jsonPrimitive?.contentOrNull
                    buildJsonObject {
                        if (revisedPrompt != null) put("revisedPrompt", JsonPrimitive(revisedPrompt))
                    }
                }))
                responseObj["usage"]?.jsonObject?.get("cost_in_usd_ticks")?.let { put("costInUsdTicks", it) }
            }))),
        )
    }
}

private class XaiVideoModel(
    private val client: HttpClient,
    private val settings: XaiProviderSettings,
    override val modelId: String,
) : VideoModel {
    override val provider: String = "xai.video"

    override suspend fun generate(params: VideoGenerationParams): VideoModelResult {
        params.abortSignal.throwIfAborted()
        val options = xaiOptions(params.providerOptions)
        val mode = xaiVideoMode(options)
        val warnings = xaiVideoWarnings(params, options, mode)
        val body = xaiVideoRequestBody(modelId, params, options, mode, warnings)
        val endpoint = when (mode) {
            "edit-video" -> "/videos/edits"
            "extend-video" -> "/videos/extensions"
            else -> "/videos/generations"
        }
        val create = xaiPostJson(
            client = client,
            url = "${settings.baseURL.trimEnd('/')}$endpoint",
            body = body,
            headers = xaiHeaders(settings, params.headers),
        )
        val requestId = create.value.jsonObject["request_id"]?.jsonPrimitive?.contentOrNull
            ?: throw NoVideoGeneratedError("No request_id returned from xAI video API")
        val status = xaiPollVideo(
            client = client,
            settings = settings,
            requestId = requestId,
            callHeaders = params.headers,
            abortSignal = params.abortSignal,
            pollIntervalMs = options["pollIntervalMs"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: DEFAULT_XAI_VIDEO_POLL_INTERVAL_MS,
            pollTimeoutMs = options["pollTimeoutMs"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: DEFAULT_XAI_VIDEO_POLL_TIMEOUT_MS,
        )
        val statusObj = status.value.jsonObject
        val video = statusObj["video"]?.jsonObject ?: JsonObject(emptyMap())
        if (video["respect_moderation"]?.jsonPrimitive?.booleanOrNull == false) {
            throw NoVideoGeneratedError("xAI video generation was blocked due to a content policy violation.")
        }
        val videoUrl = video["url"]?.jsonPrimitive?.contentOrNull
            ?: throw NoVideoGeneratedError("xAI video generation completed but no video URL was returned.")
        return VideoModelResult(
            videos = listOf(GeneratedFile(mediaType = "video/mp4", base64 = "", url = videoUrl)),
            warnings = warnings,
            response = LanguageModelResponseMetadata(modelId = modelId, headers = status.headers, body = status.value),
            providerMetadata = ProviderMetadata.Raw(JsonObject(mapOf("xai" to buildJsonObject {
                put("requestId", JsonPrimitive(requestId))
                put("videoUrl", JsonPrimitive(videoUrl))
                video["duration"]?.let { put("duration", it) }
                statusObj["usage"]?.jsonObject?.get("cost_in_usd_ticks")?.let { put("costInUsdTicks", it) }
                statusObj["progress"]?.let { put("progress", it) }
            }))),
        )
    }
}

private const val DEFAULT_XAI_VIDEO_POLL_INTERVAL_MS: Long = 5_000L
private const val DEFAULT_XAI_VIDEO_POLL_TIMEOUT_MS: Long = 600_000L


private fun xaiProviderTool(
    name: String,
    description: String,
    args: JsonElement,
): Tool<JsonElement, JsonElement, Any?> =
    ProviderExecutedTool(
        name = name,
        description = description,
        inputSerializer = JsonElement.serializer(),
        outputSerializer = JsonElement.serializer(),
        metadata = mapOf(
            "providerToolId" to JsonPrimitive("xai.$name"),
            "providerOptions" to args,
        ),
    )

private fun transformXaiChatProviderOptions(options: ProviderOptions): ProviderOptions {
    val map = options.toMap()
    val xai = map["xai"] as? JsonObject ?: return options
    val transformed = buildJsonObject {
        for ((key, value) in xai) {
            when (key) {
                "reasoningEffort" -> put("reasoning_effort", value)
                "topLogprobs" -> {
                    put("top_logprobs", value)
                    if ("logprobs" !in xai) put("logprobs", JsonPrimitive(true))
                }
                "logprobs" -> {
                    put(key, value)
                }
                "searchParameters" -> put("search_parameters", xaiSnakeCaseJson(value))
                else -> put(key, value)
            }
        }
    }
    return ProviderOptions.Raw(JsonObject(map + ("xai" to (transformed as JsonElement))))
}

private fun xaiSnakeCaseJson(value: JsonElement): JsonElement =
    when (value) {
        is JsonObject -> buildJsonObject {
            for ((key, nested) in value) put(xaiSnakeCaseKey(key), xaiSnakeCaseJson(nested))
        }
        is JsonArray -> JsonArray(value.map(::xaiSnakeCaseJson))
        else -> value
    }

private fun xaiSnakeCaseKey(value: String): String =
    // The deprecated `xHandles` alias maps to `included_x_handles`, not the naive
    // snake-case `x_handles` (an unknown key xAI ignores).
    if (value == "xHandles") {
        "included_x_handles"
    } else {
        xaiNaiveSnakeCaseKey(value)
    }

private fun xaiNaiveSnakeCaseKey(value: String): String =
    buildString {
        value.forEachIndexed { index, char ->
            if (char.isUpperCase()) {
                if (index > 0) append('_')
                append(char.lowercaseChar())
            } else {
                append(char)
            }
        }
    }

private fun JsonObjectBuilder.putXaiImageInputs(files: List<ImageGenerationFile>) {
    val imageUrls = files.map { it.xaiDataUri() }
    when (imageUrls.size) {
        0 -> Unit
        1 -> put("image", xaiImageUrlObject(imageUrls.single()))
        else -> put("images", JsonArray(imageUrls.map(::xaiImageUrlObject)))
    }
}

private fun xaiImageUrlObject(url: String): JsonObject = buildJsonObject {
    put("url", JsonPrimitive(url))
    put("type", JsonPrimitive("image_url"))
}

private fun ImageGenerationFile.xaiDataUri(): String {
    url?.takeIf { it.isNotBlank() }?.let { return it }
    val mediaType = mediaType ?: "application/octet-stream"
    val data = base64?.takeIf { it.isNotBlank() }
        ?: throw InvalidArgumentError("file", "xAI image file must include either url or base64 data.")
    return "data:$mediaType;base64,$data"
}

private fun GeneratedFile.xaiDataUri(): String {
    url?.takeIf { it.isNotBlank() }?.let { return it }
    return "data:$mediaType;base64,$base64"
}

private fun xaiVideoMode(options: JsonObject): String? {
    options["mode"]?.jsonPrimitive?.contentOrNull?.let { return it }
    if (!options["videoUrl"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()) return "edit-video"
    if (options["referenceImageUrls"]?.jsonArray?.isNotEmpty() == true) return "reference-to-video"
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
        val resolution = options["resolution"]?.jsonPrimitive?.contentOrNull
            ?: params.resolution?.let { xaiVideoResolutionMap[it] ?: it.also {
                warnings += CallWarning(
                    "unsupported",
                    "Unrecognized resolution \"$it\". Use providerOptions.xai.resolution with \"480p\" or \"720p\" instead.",
                )
            } }
        if (resolution in setOf("480p", "720p")) put("resolution", JsonPrimitive(resolution))
    }
    if (isEdit || isExtension) {
        val videoUrl = options["videoUrl"]?.jsonPrimitive?.contentOrNull
            ?: throw InvalidArgumentError("providerOptions.xai.videoUrl", "videoUrl is required for xAI $mode mode")
        put("video", buildJsonObject { put("url", JsonPrimitive(videoUrl)) })
    }
    params.image?.let { image ->
        put("image", buildJsonObject { put("url", JsonPrimitive(image.xaiDataUri())) })
    }
    if (mode == "reference-to-video") {
        val urls = options["referenceImageUrls"]?.jsonArray.orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull }
        put("reference_images", JsonArray(urls.map { url -> buildJsonObject { put("url", JsonPrimitive(url)) } }))
    }
    putXaiProviderOptions(
        options,
        setOf("mode", "pollIntervalMs", "pollTimeoutMs", "resolution", "videoUrl", "referenceImageUrls"),
    )
}

private val xaiVideoResolutionMap = mapOf(
    "1280x720" to "720p",
    "854x480" to "480p",
    "640x480" to "480p",
)

private fun JsonObjectBuilder.putXaiProviderOptions(options: JsonObject, excludedKeys: Set<String>) {
    for ((key, value) in options) {
        if (key !in excludedKeys && value !is JsonNull) put(key, value)
    }
}

private suspend fun xaiPostJson(
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
        errorMessage = ::xaiErrorMessage,
    )

private suspend fun xaiGetJson(
    client: HttpClient,
    url: String,
    headers: Map<String, String>,
    abortSignal: AbortSignal,
): HttpJsonResponse {
    abortSignal.throwIfAborted()
    return requestJson(
        client = client,
        url = url,
        method = HttpMethod.Get,
        headers = headers,
        errorMessage = ::xaiErrorMessage,
    )
}

private suspend fun xaiDownloadImage(
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
            message = "xAI image download failed (${response.status.value}): ${raw.ifBlank { "request failed" }}",
        )
    }
    return GeneratedFile(
        mediaType = headers.xaiHeaderValue(HttpHeaders.ContentType) ?: "image/png",
        base64 = Base64Codec.encode(bytes),
    )
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
        val status = xaiGetJson(
            client = client,
            url = "${settings.baseURL.trimEnd('/')}/videos/$requestId",
            headers = xaiHeaders(settings, callHeaders),
            abortSignal = abortSignal,
        )
        val body = status.value.jsonObject
        val statusValue = body["status"]?.jsonPrimitive?.contentOrNull
        val hasVideoUrl = body["video"]?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull != null
        if (statusValue == "done" || (statusValue == null && hasVideoUrl)) return status
        if (statusValue in setOf("failed", "error")) throw NoVideoGeneratedError("xAI video generation failed: $body")
    }
    throw NoVideoGeneratedError("xAI video generation timed out after ${pollTimeoutMs}ms")
}

private fun xaiHeaders(settings: XaiProviderSettings, callHeaders: Map<String, String> = emptyMap()): Map<String, String> {
    val base = linkedMapOf<String, String?>()
    settings.apiKey?.takeIf { it.isNotBlank() }?.let { base[HttpHeaders.Authorization] = "Bearer $it" }
    settings.headers.forEach { (key, value) -> base[key] = value }
    callHeaders.forEach { (key, value) -> base[key] = value }
    return ProviderHeaders.withUserAgentSuffix(base, "ai-sdk/xai/$XAI_VERSION")
}

private fun xaiOptions(providerOptions: ProviderOptions): JsonObject =
    providerOptions.toMap()["xai"] as? JsonObject ?: JsonObject(emptyMap())

private fun xaiErrorMessage(statusCode: Int, parsed: JsonElement?, raw: String): String {
    val obj = parsed as? JsonObject
    val detail = obj?.get("error")?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
        ?: obj?.get("error")?.jsonPrimitive?.contentOrNull
        ?: raw.ifBlank { "request failed" }
    return "xAI request failed ($statusCode): $detail"
}

private fun Map<String, String>.xaiHeaderValue(name: String): String? =
    entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
