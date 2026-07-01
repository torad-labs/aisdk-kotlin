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

public const val XAI_VERSION: String = "3.0.93"

public typealias XaiProviderOptions = XaiLanguageModelChatOptions
public typealias XaiResponsesProviderOptions = XaiLanguageModelResponsesOptions
public typealias XaiImageProviderOptions = XaiImageModelOptions
public typealias XaiVideoProviderOptions = XaiVideoModelOptions
public typealias XaiErrorData = JsonElement

@Serializable
@Poko
public class XaiProviderSettings internal constructor(
    public val baseURL: String = "https://api.x.ai/v1",
    public val apiKey: String? = null,
    public val headers: Map<String, String> = emptyMap(),
) {
    internal fun xaiHeaders(callHeaders: Map<String, String> = emptyMap()): Map<String, String> {
        val base = linkedMapOf<String, String?>()
        apiKey?.takeIf { it.isNotBlank() }?.let { base[HttpHeaders.Authorization] = "Bearer $it" }
        headers.forEach { (key, value) -> base[key] = value }
        callHeaders.forEach { (key, value) -> base[key] = value }
        return ProviderHeaders.withUserAgentSuffix(base, "ai-sdk/xai/$XAI_VERSION")
    }

    internal fun xaiOptions(providerOptions: ProviderOptions): JsonObject =
        JsonAccess.obj(providerOptions.toMap(), "xai") ?: JsonObject(emptyMap())

    /**
     * Rewrites the OpenAI-shaped chat body into xAI's shape: drops `stop` (xAI does not
     * support stop sequences and rejects the key) and strips `additionalProperties: false`
     * from every tool's parameters schema (xAI structured-output requires it removed).
     */
    internal fun xaiTransformChatBody(body: JsonObject): JsonObject = buildJsonObject {
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
                val function = JsonAccess.obj(obj, "function") ?: return@map tool
                val params = JsonAccess.obj(function, "parameters") ?: return@map tool
                val cleanedParams = SchemaSanitizer.stripUnsupportedSchemaKeys(params, dropAdditionalProperties = true)
                JsonObject(obj + ("function" to JsonObject(function + ("parameters" to cleanedParams))))
            },
        )
    }

    internal fun putXaiProviderOptions(builder: JsonObjectBuilder, options: JsonObject, excludedKeys: Set<String>) {
        for ((key, value) in options) {
            if (key !in excludedKeys && value !is JsonNull) builder.put(key, value)
        }
    }

    internal suspend fun xaiPostJson(
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
            errorMessage = ::xaiErrorMessage,
        )

    internal suspend fun xaiGetJson(
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
            errorMessage = ::xaiErrorMessage,
        )
    }

    private fun xaiErrorMessage(statusCode: Int, parsed: JsonElement?, raw: String): String {
        val obj = parsed as? JsonObject
        val detail = ((obj?.get("error") as? JsonObject)?.get("message") as? JsonPrimitive)?.contentOrNull
            ?: (obj?.get("error") as? JsonPrimitive)?.contentOrNull
            ?: raw.ifBlank { "request failed" }
        return "xAI request failed ($statusCode): $detail"
    }

    internal companion object {
        /**
         * Recursively snake-cases xAI `searchParameters` keys (xAI's wire convention).
         * Shared by the xAI chat path and the Google Vertex xAI-compatible path.
         */
        fun xaiSnakeCaseJson(value: JsonElement): JsonElement =
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
    }
}

public class XaiProviderSettingsBuilder {
    private var baseURL: String = "https://api.x.ai/v1"
    private var apiKey: String? = null
    private var headers: Map<String, String> = emptyMap()

    public fun baseURL(value: String): XaiProviderSettingsBuilder {
        baseURL = value
        return this
    }

    public fun apiKey(value: String?): XaiProviderSettingsBuilder {
        apiKey = value
        return this
    }

    public fun headers(value: Map<String, String>): XaiProviderSettingsBuilder {
        headers = value
        return this
    }

    public fun build(): XaiProviderSettings =
        XaiProviderSettings(
            baseURL = baseURL,
            apiKey = apiKey,
            headers = headers,
        )
}

public fun XaiProviderSettings(
    block: XaiProviderSettingsBuilder.() -> Unit = {},
): XaiProviderSettings =
    XaiProviderSettingsBuilder().apply(block).build()

@Serializable
@Poko
public class XaiLanguageModelChatOptions internal constructor(
    public val reasoningEffort: String? = null,
    public val logprobs: Boolean? = null,
    public val topLogprobs: Int? = null,
    public val parallel_function_calling: Boolean? = null,
    public val searchParameters: JsonElement? = null,
)

public class XaiLanguageModelChatOptionsBuilder {
    private var reasoningEffort: String? = null
    private var logprobs: Boolean? = null
    private var topLogprobs: Int? = null
    private var parallelFunctionCalling: Boolean? = null
    private var searchParameters: JsonElement? = null

    public fun reasoningEffort(value: String?): XaiLanguageModelChatOptionsBuilder {
        reasoningEffort = value
        return this
    }

    public fun logprobs(value: Boolean?): XaiLanguageModelChatOptionsBuilder {
        logprobs = value
        return this
    }

    public fun topLogprobs(value: Int?): XaiLanguageModelChatOptionsBuilder {
        topLogprobs = value
        return this
    }

    public fun parallel_function_calling(value: Boolean?): XaiLanguageModelChatOptionsBuilder {
        parallelFunctionCalling = value
        return this
    }

    public fun searchParameters(value: JsonElement?): XaiLanguageModelChatOptionsBuilder {
        searchParameters = value
        return this
    }

    public fun build(): XaiLanguageModelChatOptions =
        XaiLanguageModelChatOptions(
            reasoningEffort = reasoningEffort,
            logprobs = logprobs,
            topLogprobs = topLogprobs,
            parallel_function_calling = parallelFunctionCalling,
            searchParameters = searchParameters,
        )
}

public fun XaiLanguageModelChatOptions(
    block: XaiLanguageModelChatOptionsBuilder.() -> Unit = {},
): XaiLanguageModelChatOptions =
    XaiLanguageModelChatOptionsBuilder().apply(block).build()

@Serializable
@Poko
public class XaiLanguageModelResponsesOptions internal constructor(
    public val reasoningEffort: String? = null,
    public val reasoningSummary: String? = null,
)

public class XaiLanguageModelResponsesOptionsBuilder {
    private var reasoningEffort: String? = null
    private var reasoningSummary: String? = null

    public fun reasoningEffort(value: String?): XaiLanguageModelResponsesOptionsBuilder {
        reasoningEffort = value
        return this
    }

    public fun reasoningSummary(value: String?): XaiLanguageModelResponsesOptionsBuilder {
        reasoningSummary = value
        return this
    }

    public fun build(): XaiLanguageModelResponsesOptions =
        XaiLanguageModelResponsesOptions(
            reasoningEffort = reasoningEffort,
            reasoningSummary = reasoningSummary,
        )
}

public fun XaiLanguageModelResponsesOptions(
    block: XaiLanguageModelResponsesOptionsBuilder.() -> Unit = {},
): XaiLanguageModelResponsesOptions =
    XaiLanguageModelResponsesOptionsBuilder().apply(block).build()

@Serializable
@Poko
public class XaiImageModelOptions internal constructor(
    public val aspect_ratio: String? = null,
    public val output_format: String? = null,
    public val sync_mode: Boolean? = null,
    public val resolution: String? = null,
    public val quality: String? = null,
    public val user: String? = null,
)

public class XaiImageModelOptionsBuilder {
    private var aspect_ratio: String? = null
    private var output_format: String? = null
    private var sync_mode: Boolean? = null
    private var resolution: String? = null
    private var quality: String? = null
    private var user: String? = null

    public fun aspect_ratio(value: String?): XaiImageModelOptionsBuilder {
        aspect_ratio = value
        return this
    }

    public fun output_format(value: String?): XaiImageModelOptionsBuilder {
        output_format = value
        return this
    }

    public fun sync_mode(value: Boolean?): XaiImageModelOptionsBuilder {
        sync_mode = value
        return this
    }

    public fun resolution(value: String?): XaiImageModelOptionsBuilder {
        resolution = value
        return this
    }

    public fun quality(value: String?): XaiImageModelOptionsBuilder {
        quality = value
        return this
    }

    public fun user(value: String?): XaiImageModelOptionsBuilder {
        user = value
        return this
    }

    public fun build(): XaiImageModelOptions =
        XaiImageModelOptions(
            aspect_ratio = aspect_ratio,
            output_format = output_format,
            sync_mode = sync_mode,
            resolution = resolution,
            quality = quality,
            user = user,
        )
}

public fun XaiImageModelOptions(
    block: XaiImageModelOptionsBuilder.() -> Unit = {},
): XaiImageModelOptions =
    XaiImageModelOptionsBuilder().apply(block).build()

@Serializable
@Poko
public class XaiVideoModelOptions internal constructor(
    public val mode: String? = null,
    public val videoUrl: String? = null,
    public val referenceImageUrls: List<String>? = null,
    public val pollIntervalMs: Long? = null,
    public val pollTimeoutMs: Long? = null,
    public val resolution: String? = null,
)

public class XaiVideoModelOptionsBuilder {
    private var mode: String? = null
    private var videoUrl: String? = null
    private var referenceImageUrls: List<String>? = null
    private var pollIntervalMs: Long? = null
    private var pollTimeoutMs: Long? = null
    private var resolution: String? = null

    public fun mode(value: String?): XaiVideoModelOptionsBuilder {
        mode = value
        return this
    }

    public fun videoUrl(value: String?): XaiVideoModelOptionsBuilder {
        videoUrl = value
        return this
    }

    public fun referenceImageUrls(value: List<String>?): XaiVideoModelOptionsBuilder {
        referenceImageUrls = value
        return this
    }

    public fun pollIntervalMs(value: Long?): XaiVideoModelOptionsBuilder {
        pollIntervalMs = value
        return this
    }

    public fun pollTimeoutMs(value: Long?): XaiVideoModelOptionsBuilder {
        pollTimeoutMs = value
        return this
    }

    public fun resolution(value: String?): XaiVideoModelOptionsBuilder {
        resolution = value
        return this
    }

    public fun build(): XaiVideoModelOptions =
        XaiVideoModelOptions(
            mode = mode,
            videoUrl = videoUrl,
            referenceImageUrls = referenceImageUrls,
            pollIntervalMs = pollIntervalMs,
            pollTimeoutMs = pollTimeoutMs,
            resolution = resolution,
        )
}

public fun XaiVideoModelOptions(
    block: XaiVideoModelOptionsBuilder.() -> Unit = {},
): XaiVideoModelOptions =
    XaiVideoModelOptionsBuilder().apply(block).build()

public class XaiProvider(
    private val client: HttpClient,
    public val settings: XaiProviderSettings,
) : Provider {
    private val compatible = OpenAICompatible(client, xaiCompatibleSettings())

    override val providerId: String = "xai"
    public val tools: XaiTools = xaiTools

    public operator fun invoke(modelId: ModelId): LanguageModel = chat(modelId)

    public fun chat(modelId: ModelId): LanguageModel =
        XaiChatLanguageModel(compatible.chatModel(modelId.value))

    public fun responses(modelId: ModelId): LanguageModel =
        OpenResponses(
            client,
            OpenResponsesProviderSettings {
                url("${settings.baseURL.trimEnd('/')}/responses")
                name("xai")
                authHeadersProvider { settings.xaiHeaders() }
                userAgentSuffix(null)
            },
        ).responses(modelId.value)

    public fun image(modelId: ModelId): ImageModel =
        XaiImageModel(client, settings, modelId.value)

    public fun video(modelId: ModelId): VideoModel =
        XaiVideoModel(client, settings, modelId.value)

    override fun languageModel(modelId: String): LanguageModel = chat(ModelId(modelId))
    override fun imageModel(modelId: String): ImageModel = image(ModelId(modelId))
    override fun videoModel(modelId: String): VideoModel = video(ModelId(modelId))
    override fun embeddingModel(modelId: String): EmbeddingModel =
        throw NoSuchModelError(providerId, "embeddingModel", modelId)
    public fun textEmbeddingModel(modelId: String): Nothing = throw NoSuchModelError(providerId, "embeddingModel", modelId)

    private fun xaiCompatibleSettings(): OpenAICompatibleProviderSettings =
        OpenAICompatibleProviderSettings {
            name("xai")
            baseUrl(settings.baseURL.trimEnd('/'))
            authHeadersProvider { settings.xaiHeaders() }
            userAgentSuffix(null)
            providerOptionsName("xai")
            chatMaxOutputTokensKey("max_completion_tokens")
            supportedUrls(mapOf("image/*" to listOf("^https?://.*$")))
            transformChatRequestBody(settings::xaiTransformChatBody)
            convertUsage { value ->
                val obj = value as? JsonObject ?: return@convertUsage Usage()
                val promptTokens = (obj["prompt_tokens"] as? JsonPrimitive)?.intOrNull?.coerceAtLeast(0) ?: 0
                val completionTokens = (obj["completion_tokens"] as? JsonPrimitive)?.intOrNull?.coerceAtLeast(0) ?: 0
                val cacheReadTokens = (JsonAccess.obj(obj, "prompt_tokens_details")?.get("cached_tokens") as? JsonPrimitive)
                    ?.intOrNull
                    ?.coerceAtLeast(0)
                    ?: 0
                val reasoningTokens = (JsonAccess.obj(obj, "completion_tokens_details")?.get("reasoning_tokens") as? JsonPrimitive)
                    ?.intOrNull
                    ?.coerceAtLeast(0)
                    ?: 0
                val promptTokensIncludesCached = cacheReadTokens <= promptTokens
                Usage(
                    inputTokens = Usage.InputTokenBreakdown(
                        total = if (promptTokensIncludesCached) promptTokens else promptTokens + cacheReadTokens,
                        noCache = if (promptTokensIncludesCached) promptTokens - cacheReadTokens else promptTokens,
                        cacheRead = cacheReadTokens,
                    ),
                    outputTokens = Usage.OutputTokenBreakdown(
                        total = completionTokens + reasoningTokens,
                        text = completionTokens,
                        reasoning = reasoningTokens,
                    ),
                    raw = value,
                )
            }
            includeUsage(true)
        }
}

@Poko
public class XaiTools(
    public val codeExecution: Tool<JsonElement, JsonElement, Any?> = CodeExecution(),
    public val fileSearch: Tool<JsonElement, JsonElement, Any?> = FileSearch(),
    public val mcpServer: Tool<JsonElement, JsonElement, Any?> = McpServer(),
    public val viewImage: Tool<JsonElement, JsonElement, Any?> = ViewImage(),
    public val viewXVideo: Tool<JsonElement, JsonElement, Any?> = ViewXVideo(),
    public val webSearch: Tool<JsonElement, JsonElement, Any?> = WebSearch(),
    public val xSearch: Tool<JsonElement, JsonElement, Any?> = XSearch(),
) {
    internal companion object {
        fun xaiProviderTool(
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
    }
}

public val xaiTools: XaiTools = XaiTools()

public fun CodeExecution(args: JsonElement = JsonObject(emptyMap())): Tool<JsonElement, JsonElement, Any?> =
    XaiTools.xaiProviderTool("code_execution", "Execute code in xAI's hosted code execution environment.", args)

public fun FileSearch(args: JsonElement = JsonObject(emptyMap())): Tool<JsonElement, JsonElement, Any?> =
    XaiTools.xaiProviderTool("file_search", "Search xAI collections and vector stores.", args)

public fun McpServer(args: JsonElement = JsonObject(emptyMap())): Tool<JsonElement, JsonElement, Any?> =
    XaiTools.xaiProviderTool("mcp", "Call tools from a remote MCP server through xAI.", args)

public fun ViewImage(args: JsonElement = JsonObject(emptyMap())): Tool<JsonElement, JsonElement, Any?> =
    XaiTools.xaiProviderTool("view_image", "Inspect an image through xAI's hosted vision tool.", args)

public fun ViewXVideo(args: JsonElement = JsonObject(emptyMap())): Tool<JsonElement, JsonElement, Any?> =
    XaiTools.xaiProviderTool("view_x_video", "Inspect an X video through xAI's hosted video tool.", args)

public fun WebSearch(args: JsonElement = JsonObject(emptyMap())): Tool<JsonElement, JsonElement, Any?> =
    XaiTools.xaiProviderTool("web_search", "Search the web through xAI.", args)

public fun XSearch(args: JsonElement = JsonObject(emptyMap())): Tool<JsonElement, JsonElement, Any?> =
    XaiTools.xaiProviderTool("x_search", "Search X posts through xAI.", args)

/** PascalCase factory — mirrors `OpenAI(...)`. */
public fun Xai(
    client: HttpClient,
    settings: XaiProviderSettings = XaiProviderSettings(),
): XaiProvider = XaiProvider(client, settings)

private class XaiChatLanguageModel(
    private val delegate: LanguageModel,
) : LanguageModel by delegate {
    override val supportedUrls: Map<String, List<String>> = mapOf("image/*" to listOf("^https?://.*$"))

    override suspend fun generate(params: LanguageModelCallParams): LanguageModelResult =
        delegate.generate(params.toBuilder().providerOptions(transformXaiChatProviderOptions(params.providerOptions)).build())
            .withXaiCitations()

    override fun stream(params: LanguageModelCallParams): Flow<StreamEvent> =
        delegate.stream(params.toBuilder().providerOptions(transformXaiChatProviderOptions(params.providerOptions)).build())

    override fun streamResult(params: LanguageModelCallParams): LanguageModelStreamResult =
        delegate.streamResult(params.toBuilder().providerOptions(transformXaiChatProviderOptions(params.providerOptions)).build()).let {
            LanguageModelStreamResult(
                stream = it.stream.map { event -> event },
                request = it.request,
                response = it.response,
            )
        }

    private fun transformXaiChatProviderOptions(options: ProviderOptions): ProviderOptions {
        val map = options.toMap()
        val xai = JsonAccess.obj(map, "xai") ?: return options
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
                    "searchParameters" -> put("search_parameters", XaiProviderSettings.xaiSnakeCaseJson(value))
                    else -> put(key, value)
                }
            }
        }
        return ProviderOptions.Raw(JsonObject(map + ("xai" to (transformed as JsonElement))))
    }

    private fun LanguageModelResult.withXaiCitations(): LanguageModelResult {
        val citations = ((response.body as? JsonObject)?.get("citations") as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .map { url ->
                ContentPart.Source(
                    sourceType = StreamEvent.SourcePart.SourceType.Url,
                    url = url,
                )
            }
        return if (citations.isEmpty()) {
            this
        } else {
            LanguageModelResult(
                text = text,
                toolCalls = toolCalls,
                finishReason = finishReason,
                usage = usage,
                providerMetadata = providerMetadata,
                content = content + citations,
                rawFinishReason = rawFinishReason,
                warnings = warnings,
                request = request,
                response = response,
            )
        }
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

private class XaiVideoModel(
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

/**
 * xAI snake-case helpers, shared by [XaiChatLanguageModel] (here) and GoogleVertexProvider's
 * xAI path. Two unrelated consumers operating on raw `JsonElement`/`String` with no single
 * data-type owner, so they stay grouped here rather than being homed on a settings/model type.
 */
