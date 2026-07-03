@file:OptIn(ai.torad.aisdk.LowLevelLanguageModelApi::class)

package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import dev.drewhamilton.poko.Poko
import io.ktor.client.HttpClient
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import kotlinx.coroutines.flow.map
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

public const val XAI_VERSION: String = "3.0.93"

public typealias XaiProviderOptions = XaiLanguageModelChatOptions
public typealias XaiResponsesProviderOptions = XaiLanguageModelResponsesOptions
public typealias XaiImageProviderOptions = XaiImageModelOptions
public typealias XaiVideoProviderOptions = XaiVideoModelOptions
public typealias XaiErrorData = JsonElement

@Serializable
@Poko
/** @since 0.3.0-beta01 */
public class XaiProviderSettings internal constructor(
    /** @since 0.3.0-beta01 */
    public val baseURL: String = "https://api.x.ai/v1",
    /** @since 0.3.0-beta01 */
    public val apiKey: String? = null,
    /** @since 0.3.0-beta01 */
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

/** @since 0.3.0-beta01 */
public class XaiProviderSettingsBuilder {
    private var baseURL: String = "https://api.x.ai/v1"
    private var apiKey: String? = null
    private var headers: Map<String, String> = emptyMap()

    /** @since 0.3.0-beta01 */
    public fun baseURL(value: String): XaiProviderSettingsBuilder {
        baseURL = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun apiKey(value: String?): XaiProviderSettingsBuilder {
        apiKey = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun headers(value: Map<String, String>): XaiProviderSettingsBuilder {
        headers = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun build(): XaiProviderSettings =
        XaiProviderSettings(
            baseURL = baseURL,
            apiKey = apiKey,
            headers = headers,
        )
}

/** @since 0.3.0-beta01 */
public fun XaiProviderSettings(
    block: XaiProviderSettingsBuilder.() -> Unit = {},
): XaiProviderSettings =
    XaiProviderSettingsBuilder().apply(block).build()

@Serializable
@Poko
/** @since 0.3.0-beta01 */
public class XaiLanguageModelChatOptions internal constructor(
    /** @since 0.3.0-beta01 */
    public val reasoningEffort: String? = null,
    /** @since 0.3.0-beta01 */
    public val logprobs: Boolean? = null,
    /** @since 0.3.0-beta01 */
    public val topLogprobs: Int? = null,
    /** @since 0.3.0-beta01 */
    public val parallel_function_calling: Boolean? = null,
    /** @since 0.3.0-beta01 */
    public val searchParameters: JsonElement? = null,
)

/** @since 0.3.0-beta01 */
public class XaiLanguageModelChatOptionsBuilder {
    private var reasoningEffort: String? = null
    private var logprobs: Boolean? = null
    private var topLogprobs: Int? = null
    private var parallelFunctionCalling: Boolean? = null
    private var searchParameters: JsonElement? = null

    /** @since 0.3.0-beta01 */
    public fun reasoningEffort(value: String?): XaiLanguageModelChatOptionsBuilder {
        reasoningEffort = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun logprobs(value: Boolean?): XaiLanguageModelChatOptionsBuilder {
        logprobs = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun topLogprobs(value: Int?): XaiLanguageModelChatOptionsBuilder {
        topLogprobs = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun parallel_function_calling(value: Boolean?): XaiLanguageModelChatOptionsBuilder {
        parallelFunctionCalling = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun searchParameters(value: JsonElement?): XaiLanguageModelChatOptionsBuilder {
        searchParameters = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun build(): XaiLanguageModelChatOptions =
        XaiLanguageModelChatOptions(
            reasoningEffort = reasoningEffort,
            logprobs = logprobs,
            topLogprobs = topLogprobs,
            parallel_function_calling = parallelFunctionCalling,
            searchParameters = searchParameters,
        )
}

/** @since 0.3.0-beta01 */
public fun XaiLanguageModelChatOptions(
    block: XaiLanguageModelChatOptionsBuilder.() -> Unit = {},
): XaiLanguageModelChatOptions =
    XaiLanguageModelChatOptionsBuilder().apply(block).build()

@Serializable
@Poko
/** @since 0.3.0-beta01 */
public class XaiLanguageModelResponsesOptions internal constructor(
    /** @since 0.3.0-beta01 */
    public val reasoningEffort: String? = null,
    /** @since 0.3.0-beta01 */
    public val reasoningSummary: String? = null,
)

/** @since 0.3.0-beta01 */
public class XaiLanguageModelResponsesOptionsBuilder {
    private var reasoningEffort: String? = null
    private var reasoningSummary: String? = null

    /** @since 0.3.0-beta01 */
    public fun reasoningEffort(value: String?): XaiLanguageModelResponsesOptionsBuilder {
        reasoningEffort = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun reasoningSummary(value: String?): XaiLanguageModelResponsesOptionsBuilder {
        reasoningSummary = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun build(): XaiLanguageModelResponsesOptions =
        XaiLanguageModelResponsesOptions(
            reasoningEffort = reasoningEffort,
            reasoningSummary = reasoningSummary,
        )
}

/** @since 0.3.0-beta01 */
public fun XaiLanguageModelResponsesOptions(
    block: XaiLanguageModelResponsesOptionsBuilder.() -> Unit = {},
): XaiLanguageModelResponsesOptions =
    XaiLanguageModelResponsesOptionsBuilder().apply(block).build()

@Serializable
@Poko
/** @since 0.3.0-beta01 */
public class XaiImageModelOptions internal constructor(
    /** @since 0.3.0-beta01 */
    public val aspect_ratio: String? = null,
    /** @since 0.3.0-beta01 */
    public val output_format: String? = null,
    /** @since 0.3.0-beta01 */
    public val sync_mode: Boolean? = null,
    /** @since 0.3.0-beta01 */
    public val resolution: String? = null,
    /** @since 0.3.0-beta01 */
    public val quality: String? = null,
    /** @since 0.3.0-beta01 */
    public val user: String? = null,
)

/** @since 0.3.0-beta01 */
public class XaiImageModelOptionsBuilder {
    private var aspect_ratio: String? = null
    private var output_format: String? = null
    private var sync_mode: Boolean? = null
    private var resolution: String? = null
    private var quality: String? = null
    private var user: String? = null

    /** @since 0.3.0-beta01 */
    public fun aspect_ratio(value: String?): XaiImageModelOptionsBuilder {
        aspect_ratio = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun output_format(value: String?): XaiImageModelOptionsBuilder {
        output_format = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun sync_mode(value: Boolean?): XaiImageModelOptionsBuilder {
        sync_mode = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun resolution(value: String?): XaiImageModelOptionsBuilder {
        resolution = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun quality(value: String?): XaiImageModelOptionsBuilder {
        quality = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun user(value: String?): XaiImageModelOptionsBuilder {
        user = value
        return this
    }

    /** @since 0.3.0-beta01 */
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

/** @since 0.3.0-beta01 */
public fun XaiImageModelOptions(
    block: XaiImageModelOptionsBuilder.() -> Unit = {},
): XaiImageModelOptions =
    XaiImageModelOptionsBuilder().apply(block).build()

@Serializable
@Poko
/** @since 0.3.0-beta01 */
public class XaiVideoModelOptions internal constructor(
    /** @since 0.3.0-beta01 */
    public val mode: String? = null,
    /** @since 0.3.0-beta01 */
    public val videoUrl: String? = null,
    /** @since 0.3.0-beta01 */
    public val referenceImageUrls: List<String>? = null,
    /** @since 0.3.0-beta01 */
    public val pollIntervalMs: Long? = null,
    /** @since 0.3.0-beta01 */
    public val pollTimeoutMs: Long? = null,
    /** @since 0.3.0-beta01 */
    public val resolution: String? = null,
)

/** @since 0.3.0-beta01 */
public class XaiVideoModelOptionsBuilder {
    private var mode: String? = null
    private var videoUrl: String? = null
    private var referenceImageUrls: List<String>? = null
    private var pollIntervalMs: Long? = null
    private var pollTimeoutMs: Long? = null
    private var resolution: String? = null

    /** @since 0.3.0-beta01 */
    public fun mode(value: String?): XaiVideoModelOptionsBuilder {
        mode = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun videoUrl(value: String?): XaiVideoModelOptionsBuilder {
        videoUrl = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun referenceImageUrls(value: List<String>?): XaiVideoModelOptionsBuilder {
        referenceImageUrls = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun pollIntervalMs(value: Long?): XaiVideoModelOptionsBuilder {
        pollIntervalMs = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun pollTimeoutMs(value: Long?): XaiVideoModelOptionsBuilder {
        pollTimeoutMs = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun resolution(value: String?): XaiVideoModelOptionsBuilder {
        resolution = value
        return this
    }

    /** @since 0.3.0-beta01 */
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

/** @since 0.3.0-beta01 */
public fun XaiVideoModelOptions(
    block: XaiVideoModelOptionsBuilder.() -> Unit = {},
): XaiVideoModelOptions =
    XaiVideoModelOptionsBuilder().apply(block).build()

/** @since 0.3.0-beta01 */
public class XaiProvider(
    private val client: HttpClient,
    /** @since 0.3.0-beta01 */
    public val settings: XaiProviderSettings,
) : Provider {
    private val compatible = OpenAICompatible(client, xaiCompatibleSettings())

    override val providerId: String = "xai"

    /** @since 0.3.0-beta01 */
    public val tools: XaiTools = xaiTools

    public operator fun invoke(modelId: ModelId): LanguageModel = chat(modelId)

    /** @since 0.3.0-beta01 */
    public fun chat(modelId: ModelId): LanguageModel =
        XaiChatLanguageModel(compatible.chatModel(modelId.value))

    /** @since 0.3.0-beta01 */
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

    /** @since 0.3.0-beta01 */
    public fun image(modelId: ModelId): ImageModel =
        XaiImageModel(client, settings, modelId.value)

    /** @since 0.3.0-beta01 */
    public fun video(modelId: ModelId): VideoModel =
        XaiVideoModel(client, settings, modelId.value)

    override fun languageModel(modelId: String): LanguageModel = chat(ModelId(modelId))
    override fun imageModel(modelId: String): ImageModel = image(ModelId(modelId))
    override fun videoModel(modelId: String): VideoModel = video(ModelId(modelId))
    override fun embeddingModel(modelId: String): EmbeddingModel =
        throw NoSuchModelError(providerId, "embeddingModel", modelId)

    /** @since 0.3.0-beta01 */
    public fun textEmbeddingModel(
        modelId: String
    ): Nothing = throw NoSuchModelError(providerId, "embeddingModel", modelId)

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
/** @since 0.3.0-beta01 */
public class XaiTools(
    /** @since 0.3.0-beta01 */
    public val codeExecution: Tool<JsonElement, JsonElement, Any?> = CodeExecution(),
    /** @since 0.3.0-beta01 */
    public val fileSearch: Tool<JsonElement, JsonElement, Any?> = FileSearch(),
    /** @since 0.3.0-beta01 */
    public val mcpServer: Tool<JsonElement, JsonElement, Any?> = McpServer(),
    /** @since 0.3.0-beta01 */
    public val viewImage: Tool<JsonElement, JsonElement, Any?> = ViewImage(),
    /** @since 0.3.0-beta01 */
    public val viewXVideo: Tool<JsonElement, JsonElement, Any?> = ViewXVideo(),
    /** @since 0.3.0-beta01 */
    public val webSearch: Tool<JsonElement, JsonElement, Any?> = WebSearch(),
    /** @since 0.3.0-beta01 */
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

/** @since 0.3.0-beta01 */
public val xaiTools: XaiTools = XaiTools()

/** @since 0.3.0-beta01 */
public fun CodeExecution(args: JsonElement = JsonObject(emptyMap())): Tool<JsonElement, JsonElement, Any?> =
    XaiTools.xaiProviderTool("code_execution", "Execute code in xAI's hosted code execution environment.", args)

/** @since 0.3.0-beta01 */
public fun FileSearch(args: JsonElement = JsonObject(emptyMap())): Tool<JsonElement, JsonElement, Any?> =
    XaiTools.xaiProviderTool("file_search", "Search xAI collections and vector stores.", args)

/** @since 0.3.0-beta01 */
public fun McpServer(args: JsonElement = JsonObject(emptyMap())): Tool<JsonElement, JsonElement, Any?> =
    XaiTools.xaiProviderTool("mcp", "Call tools from a remote MCP server through xAI.", args)

/** @since 0.3.0-beta01 */
public fun ViewImage(args: JsonElement = JsonObject(emptyMap())): Tool<JsonElement, JsonElement, Any?> =
    XaiTools.xaiProviderTool("view_image", "Inspect an image through xAI's hosted vision tool.", args)

/** @since 0.3.0-beta01 */
public fun ViewXVideo(args: JsonElement = JsonObject(emptyMap())): Tool<JsonElement, JsonElement, Any?> =
    XaiTools.xaiProviderTool("view_x_video", "Inspect an X video through xAI's hosted video tool.", args)

/** @since 0.3.0-beta01 */
public fun WebSearch(args: JsonElement = JsonObject(emptyMap())): Tool<JsonElement, JsonElement, Any?> =
    XaiTools.xaiProviderTool("web_search", "Search the web through xAI.", args)

/** @since 0.3.0-beta01 */
public fun XSearch(args: JsonElement = JsonObject(emptyMap())): Tool<JsonElement, JsonElement, Any?> =
    XaiTools.xaiProviderTool("x_search", "Search X posts through xAI.", args)

/**
 * PascalCase factory — mirrors `OpenAI(...)`.
 * @since 0.3.0-beta01
 */
public fun Xai(
    client: HttpClient,
    settings: XaiProviderSettings = XaiProviderSettings(),
): XaiProvider = XaiProvider(client, settings)

/**
 * xAI snake-case helpers, shared by [XaiChatLanguageModel] (here) and GoogleVertexProvider's
 * xAI path. Two unrelated consumers operating on raw `JsonElement`/`String` with no single
 * data-type owner, so they stay grouped here rather than being homed on a settings/model type.
 */
