package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * HTTP transport base for OpenAI-compatible models: URL/query assembly, common
 * (auth) header resolution, and JSON / SSE / multipart / raw-bytes POST helpers.
 * Extracted verbatim from OpenAICompatibleProvider.kt.
 */
internal abstract class OpenAICompatibleHttpModel(
    protected val client: HttpClient,
    protected val settings: OpenAICompatibleProviderSettings,
    protected val json: Json,
    val modelId: String,
    private val modelType: String,
) {
    protected val providerName: String
        get() = "${settings.name}.$modelType"

    protected fun url(path: String): String {
        settings.urlBuilder?.let { return it(path, modelId) }
        val base = settings.baseUrl.trimEnd('/') + path
        if (settings.queryParams.isEmpty()) return base
        return base + "?" + settings.queryParams.entries.joinToString("&") { (key, value) ->
            "${UrlOps.encode(key)}=${UrlOps.encode(value)}"
        }
    }

    protected suspend fun commonHeaders(extra: Map<String, String> = emptyMap()): Map<String, String> {
        val dynamicAuthHeaders = settings.authHeadersProvider?.invoke()
        return ProviderHeaders.build(settings.headers, extra, settings.userAgentSuffix) { base ->
            if (dynamicAuthHeaders != null) {
                base.putAll(dynamicAuthHeaders)
            } else {
                settings.apiKey?.takeIf { it.isNotBlank() }?.let { base[HttpHeaders.Authorization] = "Bearer $it" }
            }
        }
    }

    protected suspend fun postJson(
        path: String,
        body: JsonElement,
        headers: Map<String, String> = emptyMap(),
        parseJson: Boolean = true,
    ): HttpJsonResponse =
        HttpTransport.requestJson(
            client = client,
            url = url(path),
            method = HttpMethod.Post,
            headers = commonHeaders(headers),
            body = body,
            json = json,
            parseJson = parseJson,
            errorMessage = ::openAICompatibleErrorMessage,
        )

    /**
     * Streaming counterpart of [postJson]: opens an SSE request and yields raw
     * response lines incrementally (see [streamSse]). The auth/common headers
     * are resolved inside the flow because [commonHeaders] is `suspend`.
     */
    protected fun postSse(
        path: String,
        body: JsonElement,
        headers: Map<String, String> = emptyMap(),
        onResponse: suspend (Map<String, String>) -> Unit = {},
    ): Flow<String> = flow {
        emitAll(
            HttpTransport.streamSse(client = client,
            url = url(path),
            method = HttpMethod.Post,
            headers = commonHeaders(headers),
            body = body,
            json = json,
            errorMessage = ::openAICompatibleErrorMessage,
            onResponse = onResponse,),
        )
    }

    protected suspend fun postMultipart(
        path: String,
        body: MultiPartFormDataContent,
        headers: Map<String, String> = emptyMap(),
    ): HttpJsonResponse {
        val response = client.request(url(path)) {
            method = HttpMethod.Post
            commonHeaders(headers).forEach { (name, value) -> header(name, value) }
            setBody(body)
        }
        return with(HttpTransport) { response.toJsonResponse(
            url = url(path),
            json = json,
            errorMessage = ::openAICompatibleErrorMessage,
        ) }
    }

    protected suspend fun postBytes(
        path: String,
        body: JsonElement,
        headers: Map<String, String> = emptyMap(),
    ): OpenAIBytesResponse {
        val response = client.request(url(path)) {
            method = HttpMethod.Post
            contentType(ContentType.Application.Json)
            commonHeaders(headers).forEach { (name, value) -> header(name, value) }
            setBody(json.encodeToString(JsonElement.serializer(), body))
        }
        val responseHeaders = with(HttpTransport) { response.flattenedHeaders() }
        val bytes = response.bodyAsBytes()
        if (response.status.value !in 200..299) {
            val raw = bytes.decodeToString()
            val parsed = runCatching { json.parseToJsonElement(raw) }.getOrNull()
            throw ApiCallError(
                url = url(path),
                statusCode = response.status.value,
                rawBody = raw,
                headers = responseHeaders,
                message = openAICompatibleErrorMessage(response.status.value, parsed, raw),
            )
        }
        return OpenAIBytesResponse(bytes = bytes, headers = responseHeaders)
    }

    // ---- Wire conversion + result decoding (model-internal). ----
    // Request message/tool/response-format assembly, chat/completion result
    // decoding, provider-option pruning, and error-message extraction. Shared
    // wire→core-type factories live on the core types' companions
    // (Usage.fromOpenAI, FinishReason.fromOpenAI, …).

    protected fun applyChatResponseTransform(settings: OpenAICompatibleProviderSettings, value: JsonElement): JsonElement =
        (value as? JsonObject)?.let { settings.transformChatResponse?.invoke(it) ?: it } ?: value

    protected fun openAICompatibleInBandError(value: JsonElement): OpenAICompatibleInBandError? {
        val obj = value as? JsonObject
        // Treat an explicit `"error": null` (JsonNull, a non-null reference) as absent — many
        // OpenAI-compatible backends (LiteLLM/vLLM/gateways) include it on SUCCESS, and reading it
        // as a real error turned every such 200 response into a thrown "200: null" failure.
        val error = obj?.get("error")?.takeUnless { it is JsonNull }
        return if (obj == null || error == null) {
            null
        } else {
            val errorObj = error as? JsonObject
            val code = obj.jsonStringOrNull("code") ?: errorObj?.jsonStringOrNull("code")
            val message = when (error) {
                is JsonPrimitive -> error.contentOrNull ?: error.content
                is JsonObject -> error.jsonStringOrNull("message") ?: error.jsonStringOrNull("type")
                else -> null
            } ?: obj.jsonStringOrNull("message") ?: error.toString()
            OpenAICompatibleInBandError(
                message = message,
                isRetryable = code == "The service is currently unavailable",
            )
        }
    }

    protected fun toApiCallError(
        error: OpenAICompatibleInBandError,
        url: String,
        requestBody: JsonElement,
        responseBody: String,
        responseHeaders: Map<String, String>,
    ): APICallError = APICallError(
        message = error.message,
        url = url,
        requestBodyValues = requestBody,
        statusCode = 200,
        responseHeaders = responseHeaders,
        responseBody = responseBody,
        isRetryable = error.isRetryable,
    )

    private fun JsonObject.jsonStringOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    protected fun chatResultFromJson(
        value: JsonElement,
        provider: String,
        requestBody: JsonElement,
        responseHeaders: Map<String, String>,
        responseBody: JsonElement,
        warnings: List<CallWarning>,
        convertUsage: ((JsonElement?) -> Usage)? = null,
    ): LanguageModelResult {
        val obj = WireDecoder.objectValue(value, provider, "chat completion response")
        val choice = WireDecoder.requiredArray(obj, "choices", provider, "chat completion response")
            .firstOrNull()
            ?.let { WireDecoder.objectValue(it, provider, "chat completion response", "$.choices[0]") }
            ?: WireDecoder.fail(provider, "chat completion response", "$.choices", "expected at least one choice")
        val message = WireDecoder.objectValue(
            WireDecoder.required(choice, "message", provider, "chat completion response", "$.choices[0]"),
            provider,
            "chat completion response",
            "$.choices[0].message",
        )
        val content = mutableListOf<ContentPart>()
        val text = openAITextContent(message["content"])
        if (text.isNotEmpty()) content += ContentPart.Text(text)
        val reasoning = message["reasoning_content"]?.jsonPrimitive?.contentOrNull
            ?: message["reasoning"]?.jsonPrimitive?.contentOrNull
        if (!reasoning.isNullOrEmpty()) content += ContentPart.Reasoning(reasoning)
        val toolCalls = WireDecoder.optionalArray(message, "tool_calls", provider, "chat completion response", "$.choices[0].message").orEmpty()
            .mapIndexed { index, call ->
            val callObj = WireDecoder.objectValue(call, provider, "chat completion response", "$.choices[0].message.tool_calls[$index]")
            val function = WireDecoder.objectValue(
                WireDecoder.required(callObj, "function", provider, "chat completion response", "$.choices[0].message.tool_calls[$index]"),
                provider,
                "chat completion response",
                "$.choices[0].message.tool_calls[$index].function",
            )
            ContentPart.ToolCall(
                toolCallId = callObj["id"]?.jsonPrimitive?.contentOrNull ?: IdGenerator.generate("call"),
                toolName = WireDecoder.requiredString(function, "name", provider, "chat completion response", "$.choices[0].message.tool_calls[$index].function"),
                input = ContentPart.ToolCall.parseOpenAIToolInput(WireDecoder.requiredString(function, "arguments", provider, "chat completion response", "$.choices[0].message.tool_calls[$index].function")),
                providerMetadata = ContentPart.ToolCall.thoughtSignatureMetadata(callObj)?.let { ProviderMetadata.Raw(JsonObject(it)) } ?: ProviderMetadata.None,
            )
        }
        content += toolCalls
        val finishReason = FinishReason.fromOpenAI(choice["finish_reason"]?.jsonPrimitive?.contentOrNull)
        return LanguageModelResult(
            text = text,
            toolCalls = toolCalls,
            finishReason = finishReason,
            usage = (convertUsage ?: Usage.Companion::fromOpenAI).invoke(obj["usage"]),
            providerMetadata = openAIProviderMetadata(obj["providerMetadata"], "openaiCompatible").let { m -> if (m.isEmpty()) ProviderMetadata.None else ProviderMetadata.Raw(JsonObject(m)) },
            content = content,
            rawFinishReason = choice["finish_reason"]?.jsonPrimitive?.contentOrNull,
            warnings = warnings,
            request = LanguageModelRequestMetadata(requestBody),
            response = LanguageModelResponseMetadata(
                id = obj["id"]?.jsonPrimitive?.contentOrNull,
                timestampMillis = obj["created"]?.jsonPrimitive?.doubleOrNull?.let { (it * 1000).toLong() },
                modelId = obj["model"]?.jsonPrimitive?.contentOrNull,
                headers = responseHeaders,
                body = responseBody,
            ),
        )
    }

    protected fun completionResultFromJson(
        value: JsonElement,
        provider: String,
        requestBody: JsonElement,
        responseHeaders: Map<String, String>,
        responseBody: JsonElement,
        warnings: List<CallWarning>,
    ): LanguageModelResult {
        val obj = WireDecoder.objectValue(value, provider, "completion response")
        val choice = WireDecoder.requiredArray(obj, "choices", provider, "completion response")
            .firstOrNull()
            ?.let { WireDecoder.objectValue(it, provider, "completion response", "$.choices[0]") }
            ?: WireDecoder.fail(provider, "completion response", "$.choices", "expected at least one choice")
        val text = WireDecoder.requiredString(choice, "text", provider, "completion response", "$.choices[0]")
        return LanguageModelResult(
            text = text,
            finishReason = FinishReason.fromOpenAI(choice["finish_reason"]?.jsonPrimitive?.contentOrNull),
            usage = Usage.fromOpenAI(obj["usage"]),
            rawFinishReason = choice["finish_reason"]?.jsonPrimitive?.contentOrNull,
            warnings = warnings,
            request = LanguageModelRequestMetadata(requestBody),
            response = LanguageModelResponseMetadata(
                id = obj["id"]?.jsonPrimitive?.contentOrNull,
                timestampMillis = obj["created"]?.jsonPrimitive?.doubleOrNull?.let { (it * 1000).toLong() },
                modelId = obj["model"]?.jsonPrimitive?.contentOrNull,
                headers = responseHeaders,
                body = responseBody,
            ),
        )
    }

    /**
     * One message as OpenAI chat-format JSON — or null when the message is SDK-internal bookkeeping that must
     * NOT reach the wire. OpenAI-format providers have no tool-approval concept: a Tool-role message carrying
     * only a [ContentPart.ToolApprovalResponse] (appended by the approval-resume cycle) used to serialize as
     * `{role:"tool", tool_call_id:"", content:""}`, which strict shims reject (Gemini:
     * `function_response.name: Name cannot be empty`). The wire sees the assistant's `tool_calls` entry and
     * the eventual real [ContentPart.ToolResult] — a consistent OpenAI conversation; approvals stay internal.
     */
    protected fun openAIChatMessagesJson(message: ModelMessage): List<JsonObject> = when (message.role) {
        MessageRole.System -> listOf(
            buildJsonObject {
                put("role", JsonPrimitive("system"))
                put("content", JsonPrimitive(message.content.filterIsInstance<ContentPart.Text>().joinToString("") { it.text }))
            },
        )
        MessageRole.User -> listOf(
            buildJsonObject {
                put("role", JsonPrimitive("user"))
                if (message.content.size == 1 && message.content.single() is ContentPart.Text) {
                    put("content", JsonPrimitive((message.content.single() as ContentPart.Text).text))
                } else {
                    put("content", JsonArray(message.content.mapNotNull(::openAIUserContentPartJson)))
                }
            },
        )
        MessageRole.Assistant -> listOf(
            buildJsonObject {
                put("role", JsonPrimitive("assistant"))
                val text = message.content.filterIsInstance<ContentPart.Text>().joinToString("") { it.text }
                val reasoning = message.content.filterIsInstance<ContentPart.Reasoning>().joinToString("") { it.text }
                val toolCalls = message.content.filterIsInstance<ContentPart.ToolCall>()
                put("content", if (toolCalls.isEmpty()) JsonPrimitive(text) else JsonPrimitive(text.takeIf { it.isNotEmpty() }))
                if (reasoning.isNotEmpty()) put("reasoning_content", JsonPrimitive(reasoning))
                if (toolCalls.isNotEmpty()) {
                    put(
                        "tool_calls",
                        JsonArray(toolCalls.map { part ->
                            buildJsonObject {
                                put("id", JsonPrimitive(part.toolCallId))
                                put("type", JsonPrimitive("function"))
                                put(
                                    "function",
                                    buildJsonObject {
                                        put("name", JsonPrimitive(part.toolName))
                                        put("arguments", JsonPrimitive(part.input.toString()))
                                    },
                                )
                            }
                        }),
                    )
                }
            },
        )
        MessageRole.Tool -> openAIToolMessagesJson(message)
    }

    /**
     * A Tool-role message expands to one wire `tool` message per real [ContentPart.ToolResult] — matching
     * upstream's per-result loop (OpenAI requires one `tool` message per `tool_call_id`). Approval-response
     * parts carry no wire concept, so a message with no [ContentPart.ToolResult] (approval bookkeeping)
     * produces no wire messages and never reaches the wire.
     */
    private fun openAIToolMessagesJson(message: ModelMessage): List<JsonObject> =
        message.content.filterIsInstance<ContentPart.ToolResult>().map { result ->
            buildJsonObject {
                put("role", JsonPrimitive("tool"))
                put("tool_call_id", JsonPrimitive(result.toolCallId))
                put("content", JsonPrimitive(openAIContentString(result.modelVisible)))
            }
        }

    private fun openAIUserContentPartJson(part: ContentPart): JsonObject? = when (part) {
        is ContentPart.Text -> buildJsonObject {
            put("type", JsonPrimitive("text"))
            put("text", JsonPrimitive(part.text))
        }
        is ContentPart.Image -> buildJsonObject {
            put("type", JsonPrimitive("image_url"))
            val src = openAiImageUrl(part.url, part.mediaType, part.base64)
            put("image_url", buildJsonObject { put("url", JsonPrimitive(src)) })
        }
        is ContentPart.File -> when {
            part.mediaType.startsWith("image/") -> buildJsonObject {
                put("type", JsonPrimitive("image_url"))
                val src = openAiImageUrl(part.url, part.mediaType, part.base64)
                put("image_url", buildJsonObject { put("url", JsonPrimitive(src)) })
            }
            part.mediaType.startsWith("audio/") -> buildJsonObject {
                put("type", JsonPrimitive("input_audio"))
                put(
                    "input_audio",
                    buildJsonObject {
                        put("data", JsonPrimitive(part.base64))
                        put("format", JsonPrimitive(if (part.mediaType == "audio/wav") "wav" else "mp3"))
                    },
                )
            }
            part.mediaType == "application/pdf" -> buildJsonObject {
                put("type", JsonPrimitive("file"))
                put(
                    "file",
                    buildJsonObject {
                        put("filename", JsonPrimitive(part.filename ?: "document.pdf"))
                        put("file_data", JsonPrimitive("data:application/pdf;base64,${part.base64}"))
                    },
                )
            }
            part.mediaType.startsWith("text/") -> buildJsonObject {
                put("type", JsonPrimitive("text"))
                put("text", JsonPrimitive(Base64Codec.decode(part.base64).decodeToString()))
            }
            else -> null
        }
        is ContentPart.Reasoning,
        is ContentPart.ToolCall,
        is ContentPart.ToolResult,
        is ContentPart.ToolApprovalRequest,
        is ContentPart.ToolApprovalResponse,
        is ContentPart.Source,
        -> null
    }

    /**
     * The `image_url.url` value: a remote [url] is passed through directly (OpenAI
     * fetches it); otherwise the inline [base64] is wrapped as a data URL. Closes the
     * gap where a ContentPart carrying only a `url` produced `data:...;base64,` (empty).
     */
    private fun openAiImageUrl(url: String?, mediaType: String, base64: String): String =
        url ?: "data:$mediaType;base64,$base64"

    protected fun openAIToolJson(tool: LanguageModelTool): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("function"))
        put(
            "function",
            buildJsonObject {
                put("name", JsonPrimitive(tool.name))
                put("description", JsonPrimitive(tool.description))
                put("parameters", aiSdkJson.parseToJsonElement(tool.parametersSchemaJson))
                put("strict", JsonPrimitive(tool.strict))
            },
        )
        // Per-tool provider config (e.g. cache_control), merged at the top level.
        tool.providerOptions.toMap().forEach { (key, value) -> put(key, value) }
    }

    protected fun openAIToolChoiceJson(choice: ToolChoice): JsonElement? = when (choice) {
        ToolChoice.Auto -> JsonPrimitive("auto")
        ToolChoice.None -> JsonPrimitive("none")
        ToolChoice.Required -> JsonPrimitive("required")
        is ToolChoice.Specific -> buildJsonObject {
            put("type", JsonPrimitive("function"))
            put("function", buildJsonObject { put("name", JsonPrimitive(choice.toolName)) })
        }
    }

    protected fun openAIResponseFormat(format: ResponseFormat, strictJsonSchema: Boolean): JsonElement? = when (format) {
        ResponseFormat.Text -> null
        is ResponseFormat.Json -> {
            if (format.schemaJson != null) {
                buildJsonObject {
                    put("type", JsonPrimitive("json_schema"))
                    put(
                        "json_schema",
                        buildJsonObject {
                            put("name", JsonPrimitive(format.schemaName ?: "response"))
                            format.schemaDescription?.let { put("description", JsonPrimitive(it)) }
                            put("schema", format.schemaJson)
                            put("strict", JsonPrimitive(strictJsonSchema))
                        },
                    )
                }
            } else {
                buildJsonObject { put("type", JsonPrimitive("json_object")) }
            }
        }
    }

    protected fun openAICompletionPrompt(messages: List<ModelMessage>): String =
        messages.joinToString("\n") { message ->
            val role = message.role.name.lowercase()
            val content = message.content.joinToString("") { part ->
                when (part) {
                    is ContentPart.Text -> part.text
                    is ContentPart.Reasoning -> part.text
                    is ContentPart.ToolResult -> openAIContentString(part.modelVisible)
                    is ContentPart.ToolCall -> part.input.toString()
                    is ContentPart.ToolApprovalRequest,
                    is ContentPart.ToolApprovalResponse,
                    is ContentPart.Source,
                    is ContentPart.File,
                    is ContentPart.Image,
                    -> ""
                }
            }
            "$role: $content"
        }

    private fun openAITextContent(value: JsonElement?): String = when (value) {
        null, JsonNull -> ""
        is JsonPrimitive -> value.contentOrNull.orEmpty()
        is JsonArray -> value.mapNotNull { item ->
            item.jsonObject.takeIf { it["type"]?.jsonPrimitive?.contentOrNull == "text" }
                ?.get("text")?.jsonPrimitive?.contentOrNull
        }.joinToString("")
        else -> value.toString()
    }

    private fun openAIContentString(value: JsonElement): String = when (value) {
        is JsonPrimitive -> value.contentOrNull ?: value.toString()
        else -> value.toString()
    }

    private fun JsonObject.deepMergedWith(other: JsonObject): JsonObject {
        val merged = toMutableMap()
        for ((key, value) in other) {
            val prior = merged[key]
            merged[key] = if (prior is JsonObject && value is JsonObject) prior.deepMergedWith(value) else value
        }
        return JsonObject(merged)
    }

    protected fun openAIProviderOptions(options: Map<String, JsonElement>, providerName: String): JsonObject {
        val keys = listOf("openai-compatible", "openaiCompatible", providerName, toOpenAICamelCase(providerName))
        var merged = JsonObject(emptyMap())
        for (key in keys.distinct()) {
            val obj = options[key] as? JsonObject ?: continue
            merged = merged.deepMergedWith(obj)
        }
        return merged
    }

    protected fun putProviderOptions(builder: JsonObjectBuilder, options: JsonObject, reserved: Set<String>) {
        for ((key, value) in options) {
            if (key !in reserved && value !is JsonNull) builder.put(key, value)
        }
    }

    protected fun openAIProviderMetadata(value: JsonElement?, providerName: String): Map<String, JsonElement> =
        when (value) {
            is JsonObject -> value.toMap()
            null -> emptyMap()
            else -> mapOf(providerName to value)
        }

    protected fun openAIFormValue(value: JsonElement): String = when (value) {
        is JsonPrimitive -> value.contentOrNull ?: value.toString()
        else -> value.toString()
    }

    protected fun audioMediaType(format: String): String = when (format.lowercase()) {
        "wav" -> "audio/wav"
        "opus" -> "audio/ogg"
        "aac" -> "audio/aac"
        "flac" -> "audio/flac"
        else -> "audio/mpeg"
    }

    private fun openAICompatibleErrorMessage(statusCode: Int, parsed: JsonElement?, raw: String): String {
        val error = (parsed as? JsonObject)?.get("error")?.jsonObject
        val message = error?.get("message")?.jsonPrimitive?.contentOrNull ?: raw.ifBlank { "OpenAI-compatible request failed" }
        return "OpenAI-compatible request failed ($statusCode): $message"
    }

    private fun toOpenAICamelCase(value: String): String =
        value.split('-', '_', '.', ' ')
            .filter { it.isNotBlank() }
            .mapIndexed { index, part -> if (index == 0) part.lowercase() else part.replaceFirstChar { it.uppercase() } }
            .joinToString("")
}

internal data class OpenAICompatibleInBandError(
    val message: String,
    val isRetryable: Boolean,
)

/** Raw-bytes HTTP response (audio/speech). Not a `data class`: a [ByteArray] member would give it broken structural equals/hashCode. */
internal class OpenAIBytesResponse(
    val bytes: ByteArray,
    val headers: Map<String, String>,
)
