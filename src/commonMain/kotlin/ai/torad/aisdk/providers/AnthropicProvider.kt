@file:OptIn(ai.torad.aisdk.LowLevelLanguageModelApi::class)

package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

public const val ANTHROPIC_VERSION: String = "3.0.81"

public typealias AnthropicLanguageModelOptions = JsonObject
public typealias AnthropicProviderOptions = AnthropicLanguageModelOptions
public typealias AnthropicToolOptions = JsonObject
public typealias AnthropicMessageMetadata = JsonObject
public typealias AnthropicUsageIteration = JsonObject

/** @since 0.3.0-beta01 */
public class AnthropicProviderSettings internal constructor(
    /** @since 0.3.0-beta01 */
    public val baseURL: String = "https://api.anthropic.com/v1",
    /** @since 0.3.0-beta01 */
    public val apiKey: String? = null,
    /** @since 0.3.0-beta01 */
    public val authToken: String? = null,
    /** @since 0.3.0-beta01 */
    public val headers: Map<String, String> = emptyMap(),
    /** @since 0.3.0-beta01 */
    public val requestHeadersProvider:
    (suspend (url: String, body: String, headers: Map<String, String>) -> Map<String, String>)? = null,
    /** @since 0.3.0-beta01 */
    public val buildRequestUrl: ((baseURL: String, modelId: String, isStreaming: Boolean) -> String)? = null,
    /** @since 0.3.0-beta01 */
    public val transformRequestBody: ((modelId: String, body: JsonObject, isStreaming: Boolean) -> JsonObject)? = null,
    /** @since 0.3.0-beta01 */
    public val supportedUrls: Map<String, List<String>>? = null,
    /** @since 0.3.0-beta01 */
    public val generateId: () -> String = { IdGenerator.generate() },
    /** @since 0.3.0-beta01 */
    public val name: String = "anthropic.messages",
) {
    internal fun anthropicHeaders(
        extra: Map<String, String>,
        betas: Set<String>,
    ): Map<String, String> {
        val headers = linkedMapOf<String, String?>()
        headers["anthropic-version"] = "2023-06-01"
        authToken?.takeIf { it.isNotBlank() }?.let { headers[HttpHeaders.Authorization] = "Bearer $it" }
            ?: apiKey?.takeIf { it.isNotBlank() }?.let { headers["x-api-key"] = it }
        val suppliedBetas = (this.headers.entries + extra.entries)
            .filter { it.key.equals("anthropic-beta", ignoreCase = true) }
            .flatMap { (_, value) -> value.split(',') }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        headers.putAll(this.headers)
        headers.putAll(extra)
        headers.keys
            .filter { it.equals("anthropic-beta", ignoreCase = true) }
            .toList()
            .forEach { headers.remove(it) }
        val mergedBetas = linkedSetOf<String>()
        mergedBetas += suppliedBetas
        mergedBetas += betas
        if (mergedBetas.isNotEmpty()) headers["anthropic-beta"] = mergedBetas.joinToString(",")
        return ProviderHeaders.withUserAgentSuffix(headers, "ai-sdk/anthropic/$ANTHROPIC_VERSION")
    }

    internal fun anthropicOptions(providerOptions: ProviderOptions): JsonObject {
        val options = providerOptions.toMap()
        val canonical = JsonAccess.obj(options, "anthropic") ?: JsonObject(emptyMap())
        val customName = name.substringBefore('.')
        val custom = if (customName != "anthropic") JsonAccess.obj(options, customName) else null
        return canonical.deepMergedWith(custom ?: JsonObject(emptyMap()))
    }

    private fun JsonObject.deepMergedWith(other: JsonObject): JsonObject {
        val merged = toMutableMap()
        for ((key, value) in other) {
            val prior = merged[key]
            merged[key] = if (prior is JsonObject && value is JsonObject) prior.deepMergedWith(value) else value
        }
        return JsonObject(merged)
    }

    internal fun anthropicCitationSource(citation: JsonObject): ContentPart.Source? =
        when ((citation["type"] as? JsonPrimitive)?.contentOrNull) {
            "web_search_result_location" -> ContentPart.Source(
                sourceType = StreamEvent.SourcePart.SourceType.Url,
                url = (citation["url"] as? JsonPrimitive)?.contentOrNull,
                title = (citation["title"] as? JsonPrimitive)?.contentOrNull,
                providerMetadata = ProviderMetadata.Raw(
                    JsonObject(
                        mapOf(
                            "anthropic" to buildJsonObject {
                                citation["cited_text"]?.let { put("citedText", it) }
                                citation["encrypted_index"]?.let { put("encryptedIndex", it) }
                                put("id", JsonPrimitive(generateId()))
                            }
                        )
                    )
                ),
            )
            "page_location", "char_location" -> ContentPart.Source(
                sourceType = StreamEvent.SourcePart.SourceType.Document,
                title = (citation["document_title"] as? JsonPrimitive)?.contentOrNull,
                providerMetadata = ProviderMetadata.Raw(JsonObject(mapOf("anthropic" to citation))),
            )
            else -> anthropicUnknownCitationSource(citation)
        }

    private fun anthropicUnknownCitationSource(citation: JsonObject): ContentPart.Source =
        ContentPart.Source(
            sourceType = if (citation["url"] != null) {
                StreamEvent.SourcePart.SourceType.Url
            } else {
                StreamEvent.SourcePart.SourceType.Document
            },
            url = (citation["url"] as? JsonPrimitive)?.contentOrNull,
            title = (citation["title"] as? JsonPrimitive)?.contentOrNull
                ?: (citation["document_title"] as? JsonPrimitive)?.contentOrNull,
            providerMetadata = ProviderMetadata.Raw(JsonObject(mapOf("anthropic" to citation))),
        )

    internal companion object {
        internal fun anthropicCacheControl(metadata: Map<String, JsonElement>?): JsonElement? =
            (metadata?.get("anthropic") as? JsonObject)?.let { it["cacheControl"] ?: it["cache_control"] }

        internal fun anthropicFileOptions(metadata: Map<String, JsonElement>?): JsonObject? {
            val options = metadata?.get("anthropic") as? JsonObject ?: return null
            return buildJsonObject {
                (JsonAccess.obj(options, "citations"))?.let { put("citations", it) }
                options["title"]?.let { put("title", it) }
                options["context"]?.let { put("context", it) }
            }.takeIf { it.isNotEmpty() }
        }

        internal val anthropicRejectsSamplingParameterModelFragments: Set<String> =
            setOf("claude-opus-4-8", "claude-opus-4-7", "claude-fable-5")

        /**
         * The default `max_tokens` for a Claude model when the caller omits maxOutputTokens.
         * Ports upstream's getModelCapabilities table — hardcoding 4096 truncated output on
         * every modern Claude model.
         */
        @Suppress("MagicNumber") // the values ARE the per-model max-output-token limits
        internal fun anthropicMaxOutputTokensOrNull(modelId: String): Int? = when {
            modelId.contains("claude-opus-4-8") || modelId.contains("claude-opus-4-7") -> 128_000
            modelId.contains("claude-sonnet-4-6") || modelId.contains("claude-opus-4-6") -> 128_000
            modelId.contains("claude-sonnet-4-5") || modelId.contains("claude-opus-4-5") ||
                modelId.contains("claude-haiku-4-5") -> 64_000
            modelId.contains("claude-opus-4-1") -> 32_000
            modelId.contains("claude-sonnet-4-") -> 64_000
            modelId.contains("claude-opus-4-") -> 32_000
            modelId.contains("claude-3-haiku") -> 4_096
            else -> null
        }

        internal fun anthropicMaxOutputTokensForModel(modelId: String): Int =
            anthropicMaxOutputTokensOrNull(modelId) ?: 4_096
    }
}

/** @since 0.3.0-beta01 */
public class AnthropicProviderSettingsBuilder {
    private var baseURL: String = "https://api.anthropic.com/v1"
    private var apiKey: String? = null
    private var authToken: String? = null
    private var headers: Map<String, String> = emptyMap()
    private var requestHeadersProvider:
        (suspend (url: String, body: String, headers: Map<String, String>) -> Map<String, String>)? = null
    private var buildRequestUrl: ((baseURL: String, modelId: String, isStreaming: Boolean) -> String)? = null
    private var transformRequestBody: ((modelId: String, body: JsonObject, isStreaming: Boolean) -> JsonObject)? = null
    private var supportedUrls: Map<String, List<String>>? = null
    private var generateId: () -> String = { IdGenerator.generate() }
    private var name: String = "anthropic.messages"

    /** @since 0.3.0-beta01 */
    public fun baseURL(value: String): AnthropicProviderSettingsBuilder {
        baseURL = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun apiKey(value: String?): AnthropicProviderSettingsBuilder {
        apiKey = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun authToken(value: String?): AnthropicProviderSettingsBuilder {
        authToken = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun headers(value: Map<String, String>): AnthropicProviderSettingsBuilder {
        headers = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun requestHeadersProvider(
        value: (suspend (url: String, body: String, headers: Map<String, String>) -> Map<String, String>)?
    ): AnthropicProviderSettingsBuilder {
        requestHeadersProvider = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun buildRequestUrl(
        value: ((baseURL: String, modelId: String, isStreaming: Boolean) -> String)?
    ): AnthropicProviderSettingsBuilder {
        buildRequestUrl = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun transformRequestBody(
        value: ((modelId: String, body: JsonObject, isStreaming: Boolean) -> JsonObject)?
    ): AnthropicProviderSettingsBuilder {
        transformRequestBody = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun supportedUrls(value: Map<String, List<String>>?): AnthropicProviderSettingsBuilder {
        supportedUrls = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun generateId(value: () -> String): AnthropicProviderSettingsBuilder {
        generateId = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun name(value: String): AnthropicProviderSettingsBuilder {
        name = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun build(): AnthropicProviderSettings =
        AnthropicProviderSettings(
            baseURL = baseURL,
            apiKey = apiKey,
            authToken = authToken,
            headers = headers,
            requestHeadersProvider = requestHeadersProvider,
            buildRequestUrl = buildRequestUrl,
            transformRequestBody = transformRequestBody,
            supportedUrls = supportedUrls,
            generateId = generateId,
            name = name,
        )
}

/** @since 0.3.0-beta01 */
public fun AnthropicProviderSettings(
    block: AnthropicProviderSettingsBuilder.() -> Unit = {},
): AnthropicProviderSettings =
    AnthropicProviderSettingsBuilder().apply(block).build()

/** @since 0.3.0-beta01 */
public class AnthropicProvider(
    private val client: HttpClient,
    /** @since 0.3.0-beta01 */
    public val settings: AnthropicProviderSettings = AnthropicProviderSettings(),
) : Provider {
    init {
        if (!settings.apiKey.isNullOrBlank() && !settings.authToken.isNullOrBlank()) {
            throw InvalidArgumentError(
                "apiKey/authToken",
                "Both apiKey and authToken were provided. Please use only one authentication method."
            )
        }
    }

    override val providerId: String = "anthropic"

    /** @since 0.3.0-beta01 */
    public val tools: AnthropicTools = anthropicTools

    public operator fun invoke(modelId: ModelId): LanguageModel = languageModel(modelId.value)

    /** @since 0.3.0-beta01 */
    public fun chat(modelId: ModelId): LanguageModel = languageModel(modelId.value)

    /** @since 0.3.0-beta01 */
    public fun messages(modelId: ModelId): LanguageModel = languageModel(modelId.value)

    override fun languageModel(modelId: String): LanguageModel =
        AnthropicMessagesLanguageModel(client, settings, modelId)

    override fun embeddingModel(modelId: String): EmbeddingModel = throw NoSuchModelError(
        providerId,
        "embeddingModel",
        modelId
    )
    override fun imageModel(modelId: String): ImageModel = throw NoSuchModelError(providerId, "imageModel", modelId)

    /** @since 0.3.0-beta01 */
    public fun textEmbeddingModel(modelId: String): Nothing = throw NoSuchModelError(
        providerId,
        "embeddingModel",
        modelId
    )
}

/**
 * PascalCase factory — mirrors the reference `OpenAI(...)` faux-constructor.
 * @since 0.3.0-beta01
 */
public fun Anthropic(
    client: HttpClient,
    settings: AnthropicProviderSettings = AnthropicProviderSettings(),
): AnthropicProvider = AnthropicProvider(client, settings)

/** @since 0.3.0-beta01 */
public class AnthropicMessagesLanguageModel(
    private val client: HttpClient,
    private val settings: AnthropicProviderSettings,
    override val modelId: String,
) : LanguageModel {
    override val provider: String = settings.name
    override val supportedUrls: Map<String, List<String>> = settings.supportedUrls ?: mapOf(
        "image/*" to listOf("^https://.*$"),
        "application/pdf" to listOf("^https://.*$"),
    )

    override suspend fun generate(params: LanguageModelCallParams): LanguageModelResult {
        val prepared = PreparedAnthropicRequest.anthropicRequestBody(settings, modelId, params, stream = false)
        val response = anthropicPost(prepared.body, prepared.betas, params.headers)
        return anthropicGenerateResult(
            response = response.value.jsonObject,
            requestBody = prepared.body,
            responseHeaders = response.headers,
            responseBody = response.value,
            warnings = prepared.warnings,
            settings = settings,
            json = aiSdkJson,
        )
    }

    override fun stream(params: LanguageModelCallParams): Flow<StreamEvent> = flow {
        val prepared = PreparedAnthropicRequest.anthropicRequestBody(settings, modelId, params, stream = true)
        val state = AnthropicStreamState(settings, aiSdkJson)
        var sseHeaders: Map<String, String> = emptyMap()
        val rawLines = anthropicStreamSse(prepared.body, prepared.betas, params.headers) { sseHeaders = it }
        val baseURL = settings.baseURL.trimEnd('/')
        val requestUrl = settings.buildRequestUrl?.invoke(baseURL, modelId, true) ?: "$baseURL/messages"
        val parsedEvents = EventStreamParser.parse(
            rawLines,
            Schemas.jsonSchema<JsonElement>(JsonObject(emptyMap())),
            aiSdkJson
        )
        val firstProviderEvent = BooleanArray(1) { true }
        val streamStartEmitted = BooleanArray(1)
        val responseMetadataEmitted = BooleanArray(1)
        val emitStartAndMetadata: suspend () -> Unit = {
            if (!streamStartEmitted[0]) {
                emit(StreamEvent.StreamStart(prepared.warnings))
                streamStartEmitted[0] = true
            }
            if (!responseMetadataEmitted[0]) {
                emit(StreamEvent.ResponseMetadata(headers = sseHeaders))
                responseMetadataEmitted[0] = true
            }
        }

        parsedEvents.collect { result ->
            if (firstProviderEvent[0] && result is ParseResult.Success) {
                val obj = result.value as? JsonObject
                if ((obj?.get("type") as? JsonPrimitive)?.contentOrNull == "error") {
                    val error = JsonAccess.obj(obj, "error") ?: obj
                    val errorType = (error["type"] as? JsonPrimitive)?.contentOrNull
                    val statusCode = if (errorType == "overloaded_error") 529 else 500
                    throw APICallError(
                        message = anthropicErrorMessage(obj["error"] ?: obj, obj.toString()),
                        url = requestUrl,
                        requestBodyValues = prepared.body,
                        statusCode = statusCode,
                        responseHeaders = sseHeaders,
                        responseBody = obj.toString(),
                        isRetryable = errorType == "overloaded_error",
                    )
                }
            }
            firstProviderEvent[0] = false
            emitStartAndMetadata()
            when (result) {
                is ParseResult.Success -> state.accept(result.value).forEach { emit(it) }
                is ParseResult.Failure -> emit(
                    StreamEvent.Error("Failed to parse Anthropic stream event: ${result.error.message}"),
                )
            }
        }
        emitStartAndMetadata()
        state.finish().forEach { emit(it) }
    }

    override fun streamResult(params: LanguageModelCallParams): LanguageModelStreamResult {
        val prepared = PreparedAnthropicRequest.anthropicRequestBody(settings, modelId, params, stream = true)
        return LanguageModelStreamResult(stream = stream(params), request = LanguageModelRequestMetadata(prepared.body))
    }

    private suspend fun anthropicPost(
        body: JsonObject,
        betas: Set<String>,
        extraHeaders: Map<String, String>,
    ): HttpJsonResponse {
        val baseURL = settings.baseURL.trimEnd('/')
        val url = settings.buildRequestUrl?.invoke(baseURL, modelId, false) ?: "$baseURL/messages"
        val requestBody = settings.transformRequestBody?.invoke(modelId, body, false) ?: body
        val encodedBody = aiSdkOutputJson.encodeToString(JsonElement.serializer(), requestBody)
        val baseHeaders = settings.anthropicHeaders(extraHeaders, betas)
        val requestHeaders = settings.requestHeadersProvider?.invoke(url, encodedBody, baseHeaders) ?: baseHeaders
        val response = client.request(url) {
            method = HttpMethod.Post
            contentType(ContentType.Application.Json)
            requestHeaders.forEach { (name, value) -> header(name, value) }
            setBody(encodedBody)
        }
        return with(HttpTransport) {
            response.toJsonResponse(
                url = url,
                parseJson = true,
                requestBodyValues = requestBody,
                errorMessage = { _, parsed, raw -> anthropicErrorMessage(parsed, raw) },
            )
        }
    }

    /** Streaming counterpart of [anthropicPost]: same URL/header/transform path,
     *  but reads the SSE body incrementally off the wire (see `streamSse`). */
    private fun anthropicStreamSse(
        body: JsonObject,
        betas: Set<String>,
        extraHeaders: Map<String, String>,
        onResponse: suspend (Map<String, String>) -> Unit,
    ): Flow<String> = flow {
        val baseURL = settings.baseURL.trimEnd('/')
        val url = settings.buildRequestUrl?.invoke(baseURL, modelId, true) ?: "$baseURL/messages"
        val requestBody = settings.transformRequestBody?.invoke(modelId, body, true) ?: body
        val encodedBody = aiSdkOutputJson.encodeToString(JsonElement.serializer(), requestBody)
        val baseHeaders = settings.anthropicHeaders(extraHeaders, betas)
        val requestHeaders = settings.requestHeadersProvider?.invoke(url, encodedBody, baseHeaders) ?: baseHeaders
        emitAll(
            HttpTransport.streamSse(
                client = client,
                url = url,
                method = HttpMethod.Post,
                headers = requestHeaders + (HttpHeaders.Accept to "text/event-stream"),
                body = requestBody,
                json = aiSdkJson,
                requestBodyValues = requestBody,
                errorMessage = { _, parsed, raw -> anthropicErrorMessage(parsed, raw) },
                onResponse = onResponse,
            ),
        )
    }

    private fun anthropicGenerateResult(
        response: JsonObject,
        requestBody: JsonElement,
        responseHeaders: Map<String, String>,
        responseBody: JsonElement,
        warnings: List<CallWarning>,
        settings: AnthropicProviderSettings,
        json: Json,
    ): LanguageModelResult {
        val content = mutableListOf<ContentPart>()
        val toolCalls = mutableListOf<ContentPart.ToolCall>()
        (JsonAccess.arr(response, "content")).orEmpty().forEachIndexed { index, part ->
            val obj = part as? JsonObject ?: return@forEachIndexed
            val path = "$.content[$index]"
            when ((obj["type"] as? JsonPrimitive)?.contentOrNull) {
                "text" -> {
                    (obj["text"] as? JsonPrimitive)?.contentOrNull?.let { text -> content += ContentPart.Text(text) }
                    for (citation in (JsonAccess.arr(obj, "citations")).orEmpty()) {
                        val citationObj = citation as? JsonObject ?: continue
                        settings.anthropicCitationSource(citationObj)?.let { content += it }
                    }
                }
                "thinking" -> content += ContentPart.Reasoning(
                    text = (obj["thinking"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                    providerMetadata = ProviderMetadata.Raw(
                        JsonObject(
                            mapOf(
                                "anthropic" to buildJsonObject {
                                    obj["signature"]?.let { put("signature", it) }
                                }
                            )
                        )
                    ),
                )
                "redacted_thinking" -> content += ContentPart.Reasoning(
                    text = "",
                    providerMetadata = ProviderMetadata.Raw(
                        JsonObject(
                            mapOf(
                                "anthropic" to buildJsonObject {
                                    obj["data"]?.let { put("redactedData", it) }
                                }
                            )
                        )
                    ),
                )
                "tool_use", "server_tool_use", "mcp_tool_use" -> {
                    val toolCallId = WireDecoder.requiredString(obj, "id", "anthropic", "response content", path)
                    if (toolCallId.isBlank()) {
                        WireDecoder.fail(
                            "anthropic",
                            "response content",
                            WireDecoder.child(path, "id"),
                            "expected non-blank string",
                            obj["id"],
                        )
                    }
                    val toolName = WireDecoder.requiredString(obj, "name", "anthropic", "response content", path)
                    if (toolName.isBlank()) {
                        WireDecoder.fail(
                            "anthropic",
                            "response content",
                            WireDecoder.child(path, "name"),
                            "expected non-blank string",
                            obj["name"],
                        )
                    }
                    val toolCall = ContentPart.ToolCall(
                        toolCallId = toolCallId,
                        toolName = toolName,
                        input = obj["input"] ?: JsonObject(emptyMap()),
                        providerMetadata = if ((obj["type"] as? JsonPrimitive)?.contentOrNull != "tool_use") {
                            ProviderMetadata.Raw(
                                JsonObject(
                                    mapOf(
                                        "anthropic" to buildJsonObject { put("providerExecuted", JsonPrimitive(true)) }
                                    )
                                )
                            )
                        } else {
                            ProviderMetadata.None
                        },
                    )
                    toolCalls += toolCall
                    content += toolCall
                }
                "mcp_tool_result", "web_search_tool_result", "web_fetch_tool_result", "code_execution_tool_result" -> {
                    val toolCallId = WireDecoder.optionalString(obj, "tool_use_id", "anthropic", "response content", path)
                        ?.takeIf { it.isNotBlank() }
                        ?: WireDecoder.optionalString(obj, "id", "anthropic", "response content", path)
                            ?.takeIf { it.isNotBlank() }
                        ?: WireDecoder.fail(
                            "anthropic",
                            "response content",
                            path,
                            "missing non-blank required field: tool_use_id or id",
                            obj,
                        )
                    val toolName = WireDecoder.requiredString(obj, "name", "anthropic", "response content", path)
                    if (toolName.isBlank()) {
                        WireDecoder.fail(
                            "anthropic",
                            "response content",
                            WireDecoder.child(path, "name"),
                            "expected non-blank string",
                            obj["name"],
                        )
                    }
                    content += ContentPart.ToolResult(
                        toolCallId = toolCallId,
                        toolName = toolName,
                        output = obj["content"] ?: obj,
                        providerMetadata = ProviderMetadata.Raw(JsonObject(mapOf("anthropic" to obj))),
                    )
                }
            }
        }

        val stopReason = (response["stop_reason"] as? JsonPrimitive)?.contentOrNull
        val usage = Usage.fromAnthropic(response["usage"])
        val text = content.filterIsInstance<ContentPart.Text>().joinToString("") { it.text }
        val metadata = buildJsonObject {
            response["usage"]?.let { put("usage", it) }
            put("cacheCreationInputTokens", JsonPrimitive(usage.inputTokens.cacheWrite))
            response["stop_sequence"]?.let { put("stopSequence", it) }
            response["container"]?.let { put("container", PreparedAnthropicRequest.camelToSnakeJson(it)) }
            response["context_management"]?.let { put("contextManagement", it) }
        }
        return LanguageModelResult(
            text = text,
            toolCalls = toolCalls,
            finishReason = FinishReason.fromAnthropicStopReason(stopReason),
            usage = usage,
            providerMetadata = ProviderMetadata.Raw(JsonObject(mapOf("anthropic" to metadata))),
            content = content,
            rawFinishReason = stopReason,
            warnings = warnings,
            request = LanguageModelRequestMetadata(body = requestBody),
            response = LanguageModelResponseMetadata(
                id = (response["id"] as? JsonPrimitive)?.contentOrNull,
                modelId = (response["model"] as? JsonPrimitive)?.contentOrNull,
                headers = responseHeaders,
                body = responseBody,
            ),
        )
    }

    public companion object {
        /** @since 0.3.0-beta01 */
        public fun forwardAnthropicContainerIdFromLastStep(
            steps: List<Map<String, JsonElement>>,
        ): Map<String, JsonElement>? {
            for (step in steps.asReversed()) {
                val containerObj = (JsonAccess.obj(step, "anthropic"))?.get("container") as? JsonObject
                val idElement = containerObj?.get("id")
                val containerId = (idElement as? JsonPrimitive)?.contentOrNull
                if (!containerId.isNullOrBlank()) {
                    return mapOf(
                        "anthropic" to buildJsonObject {
                            put("container", buildJsonObject { put("id", JsonPrimitive(containerId)) })
                        },
                    )
                }
            }
            return null
        }

        internal fun anthropicErrorMessage(parsed: JsonElement?, raw: String): String {
            val obj = parsed as? JsonObject ?: return raw
            val error = JsonAccess.obj(obj, "error")
            return (error?.get("message") as? JsonPrimitive)?.contentOrNull
                ?: (error?.get("type") as? JsonPrimitive)?.contentOrNull
                ?: (obj["message"] as? JsonPrimitive)?.contentOrNull
                ?: raw
        }
    }
}

/** @since 0.3.0-beta01 */
public val anthropicTools: AnthropicTools = AnthropicTools()

private class AnthropicStreamState(
    private val settings: AnthropicProviderSettings,
    private val json: Json,
) {
    private val blocks = mutableMapOf<Int, AnthropicStreamBlock>()
    private var finishReason = FinishReason.Other
    private var rawStopReason: String? = null
    private var usage = Usage()
    private var responseId: String? = null
    private var modelId: String? = null

    fun accept(chunk: JsonElement): List<StreamEvent> {
        val obj = try {
            WireDecoder.objectValue(chunk, "anthropic", "stream event")
        } catch (error: WireDecodeException) {
            return listOf(StreamEvent.Error(error.message ?: "Anthropic stream protocol error"))
        }
        val type = try {
            WireDecoder.requiredString(obj, "type", "anthropic", "stream event")
        } catch (error: WireDecodeException) {
            return listOf(StreamEvent.Error(error.message ?: "Anthropic stream protocol error"))
        }
        val events = mutableListOf<StreamEvent>()
        when (type) {
            "message_start" -> {
                val message = try {
                    WireDecoder.objectValue(
                        WireDecoder.required(obj, "message", "anthropic", "stream event"),
                        "anthropic",
                        "stream event",
                        "$.message"
                    )
                } catch (error: WireDecodeException) {
                    return listOf(StreamEvent.Error(error.message ?: "Anthropic stream protocol error"))
                }
                responseId = (message["id"] as? JsonPrimitive)?.contentOrNull
                modelId = (message["model"] as? JsonPrimitive)?.contentOrNull
                usage = Usage.fromAnthropic(message["usage"])
                events += StreamEvent.ResponseMetadata(id = responseId, modelId = modelId)
            }
            "content_block_start" -> {
                val index = try {
                    WireDecoder.optionalInt(obj, "index", "anthropic", "stream event") ?: blocks.size
                } catch (error: WireDecodeException) {
                    return listOf(StreamEvent.Error(error.message ?: "Anthropic stream protocol error"))
                }
                val block = try {
                    WireDecoder.objectValue(
                        WireDecoder.required(obj, "content_block", "anthropic", "stream event"),
                        "anthropic",
                        "stream event",
                        "$.content_block"
                    )
                } catch (error: WireDecodeException) {
                    return listOf(StreamEvent.Error(error.message ?: "Anthropic stream protocol error"))
                }
                val blockType = try {
                    WireDecoder.requiredString(block, "type", "anthropic", "stream event", "$.content_block")
                } catch (error: WireDecodeException) {
                    return listOf(StreamEvent.Error(error.message ?: "Anthropic stream protocol error"))
                }
                val isToolBlock = blockType == "tool_use" || blockType == "server_tool_use" || blockType == "mcp_tool_use"
                val id = if (isToolBlock) {
                    (block["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                        ?: return listOf(missingToolIdentityError(type, "id"))
                } else {
                    (block["id"] as? JsonPrimitive)?.contentOrNull ?: "block-$index"
                }
                val toolName = if (isToolBlock) {
                    (block["name"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                        ?: return listOf(missingToolIdentityError(type, "name"))
                } else {
                    (block["name"] as? JsonPrimitive)?.contentOrNull
                }
                blocks[index] = AnthropicStreamBlock(id, blockType, toolName, anthropicInitialStreamInput(block["input"]))
                when (blockType) {
                    "text" -> events += StreamEvent.TextStart(id)
                    "thinking" -> events += StreamEvent.ReasoningStart(id)
                    "redacted_thinking" -> events += StreamEvent.ReasoningStart(
                        id,
                        providerMetadata = block["data"]?.let { data ->
                            ProviderMetadata.Raw(JsonObject(mapOf("anthropic" to buildJsonObject { put("redactedData", data) })))
                        } ?: ProviderMetadata.None,
                    )
                    "tool_use", "server_tool_use", "mcp_tool_use" -> {
                        events += StreamEvent.ToolInputStart(id, toolName ?: return listOf(missingToolIdentityError(type, "name")))
                    }
                }
            }
            "content_block_delta" -> {
                val index = try {
                    // index is REQUIRED for delta/stop (unlike content_block_start, which CREATES a
                    // block at blocks.size) — a missing index references no block, so it is a wire
                    // error. This single strict read replaces the old required()+optionalInt double
                    // read whose `?: blocks.size` fallback was dead code.
                    WireDecoder.intValue(
                        WireDecoder.required(obj, "index", "anthropic", "stream event"),
                        "anthropic",
                        "stream event",
                        "$.index",
                    )
                } catch (error: WireDecodeException) {
                    return listOf(StreamEvent.Error(error.message ?: "Anthropic stream protocol error"))
                }
                val block = blocks[index]
                    ?: return listOf(StreamEvent.Error("Anthropic stream protocol error: content_block_delta for unknown block index $index."))
                val delta = try {
                    WireDecoder.objectValue(
                        WireDecoder.required(obj, "delta", "anthropic", "stream event"),
                        "anthropic",
                        "stream event",
                        "$.delta"
                    )
                } catch (error: WireDecodeException) {
                    return listOf(StreamEvent.Error(error.message ?: "Anthropic stream protocol error"))
                }
                when (WireDecoder.optionalString(delta, "type", "anthropic", "stream event", "$.delta")) {
                    "text_delta" -> events += StreamEvent.TextDelta(block.id, WireDecoder.requiredString(delta, "text", "anthropic", "stream event", "$.delta"))
                    "thinking_delta" -> events += StreamEvent.ReasoningDelta(block.id, WireDecoder.requiredString(delta, "thinking", "anthropic", "stream event", "$.delta"))
                    // Capture the streamed thinking signature onto the block; it surfaces on the
                    // eventual ReasoningEnd providerMetadata (content_block_stop) for replay parity
                    // with the non-streaming `signature` capture. Not a fatal stream error.
                    "signature_delta" ->
                        (delta["signature"] as? JsonPrimitive)?.contentOrNull?.let { block.signature = it }
                    // Mid-stream citation: emit a Source, mirroring the non-streaming citation path.
                    "citations_delta" -> citationSourceEvent(delta)?.let { events += it }
                    "input_json_delta" -> {
                        val text = WireDecoder.requiredString(
                            delta,
                            "partial_json",
                            "anthropic",
                            "stream event",
                            "$.delta"
                        )
                        block.input += text
                        events += StreamEvent.ToolInputDelta(block.id, text)
                    }
                    null -> return listOf(
                        StreamEvent.Error("Anthropic stream protocol error: content_block_delta missing delta.type.")
                    )
                    // Forward-compatible: ignore unknown delta subtypes (matches upstream Vercel AI
                    // SDK) rather than aborting generation on a delta Anthropic adds later.
                    else -> return emptyList()
                }
            }
            "content_block_stop" -> {
                val index = try {
                    // index is REQUIRED for delta/stop (unlike content_block_start, which CREATES a
                    // block at blocks.size) — a missing index references no block, so it is a wire
                    // error. This single strict read replaces the old required()+optionalInt double
                    // read whose `?: blocks.size` fallback was dead code.
                    WireDecoder.intValue(
                        WireDecoder.required(obj, "index", "anthropic", "stream event"),
                        "anthropic",
                        "stream event",
                        "$.index",
                    )
                } catch (error: WireDecodeException) {
                    return listOf(StreamEvent.Error(error.message ?: "Anthropic stream protocol error"))
                }
                val block = blocks.remove(index)
                    ?: return listOf(StreamEvent.Error("Anthropic stream protocol error: content_block_stop for unknown block index $index."))
                when (block.type) {
                    "text" -> events += StreamEvent.TextEnd(block.id)
                    // Surface the streamed signature for replay, mirroring the non-streaming
                    // `signature` capture. ProviderMetadata.None when no signature_delta arrived.
                    "thinking", "redacted_thinking" -> events += StreamEvent.ReasoningEnd(
                        block.id,
                        providerMetadata = reasoningEndMetadata(block.signature),
                    )
                    "tool_use", "server_tool_use", "mcp_tool_use" -> {
                        events += StreamEvent.ToolInputEnd(block.id)
                        val toolName = block.toolName ?: return listOf(missingToolIdentityError("content_block_stop", "name"))
                        val inputJson = if (block.input.isBlank()) {
                            JsonObject(emptyMap())
                        } else {
                            runCatching { json.parseToJsonElement(block.input) }.getOrElse {
                                events += StreamEvent.Error("Anthropic stream protocol error: malformed tool input JSON for `$toolName`.")
                                return events
                            }
                        }
                        events += StreamEvent.ToolCall(
                            toolCallId = block.id,
                            toolName = toolName,
                            inputJson = inputJson,
                            providerMetadata = if (block.type != "tool_use") {
                                ProviderMetadata.Raw(JsonObject(mapOf("anthropic" to buildJsonObject {
                                    put("providerExecuted", JsonPrimitive(true))
                                })))
                            } else {
                                ProviderMetadata.None
                            },
                        )
                    }
                }
            }
            "message_delta" -> {
                val delta = (JsonAccess.obj(obj, "delta")) ?: JsonObject(emptyMap())
                rawStopReason = (delta["stop_reason"] as? JsonPrimitive)?.contentOrNull
                finishReason = FinishReason.fromAnthropicStopReason(rawStopReason)
                // Merge onto the message_start usage (delta usually has only output_tokens).
                usage = Usage.mergeAnthropic(usage, obj["usage"])
            }
            "error" -> events += StreamEvent.Error(AnthropicMessagesLanguageModel.anthropicErrorMessage(obj["error"] ?: obj, obj.toString()))
        }
        return events
    }

    private fun anthropicInitialStreamInput(input: JsonElement?): String = when (input) {
        null -> ""
        is JsonObject -> if (input.isEmpty()) "" else input.toString()
        else -> input.toString()
    }

    /** Wrap a captured thinking [signature] as `anthropic.signature` provider metadata for
     *  [StreamEvent.ReasoningEnd]; [ProviderMetadata.None] when none was streamed. */
    private fun reasoningEndMetadata(signature: String?): ProviderMetadata =
        signature?.let { sig ->
            val anthropic = buildJsonObject { put("signature", JsonPrimitive(sig)) }
            ProviderMetadata.Raw(JsonObject(mapOf("anthropic" to anthropic)))
        } ?: ProviderMetadata.None

    /** Map a streamed `citations_delta` to a [StreamEvent.SourcePart], reusing the non-streaming
     *  citation decoder; null when the delta carries no parsable citation. */
    private fun citationSourceEvent(delta: JsonObject): StreamEvent.SourcePart? =
        (JsonAccess.obj(delta, "citation"))
            ?.let { settings.anthropicCitationSource(it) }
            ?.let { source ->
                StreamEvent.SourcePart(
                    id = settings.generateId(),
                    sourceType = source.sourceType,
                    url = source.url,
                    title = source.title,
                    providerMetadata = source.providerMetadata,
                )
            }

    private fun missingToolIdentityError(eventType: String, field: String): StreamEvent.Error =
        StreamEvent.Error("Anthropic stream protocol error: $eventType missing required $field.")

    fun finish(): List<StreamEvent> = listOf(
        StreamEvent.Finish(
            totalSteps = 1,
            finishReason = finishReason,
            usage = usage,
            providerMetadata = ProviderMetadata.Raw(
                JsonObject(
                    mapOf(
                        "anthropic" to buildJsonObject {
                            responseId?.let { put("responseId", JsonPrimitive(it)) }
                        }
                    )
                )
            ),
            rawFinishReason = rawStopReason,
        ),
    )
}

private data class AnthropicStreamBlock(
    val id: String,
    val type: String,
    val toolName: String?,
    var input: String,
    /** Captured from streamed `signature_delta` on a thinking block; surfaced on the
     *  eventual [StreamEvent.ReasoningEnd] so streamed reasoning carries its signature
     *  for replay (mirrors the non-streaming `signature` capture). */
    var signature: String? = null,
)
