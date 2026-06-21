package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class OpenAICompatibleInBandError(
    val message: String,
    val isRetryable: Boolean,
)

internal val openAIChatReservedOptions = setOf(
    "user",
    "strictJsonSchema",
    "reasoningEffort",
    "textVerbosity",
)

internal val openAICompletionReservedOptions = setOf(
    "user",
)

internal val openAICompatibleImageEditReservedOptions = setOf(
    "model",
    "prompt",
    "image",
    "mask",
    "n",
    "size",
)

/**
 * Wire conversion + result decoding for OpenAI-compatible providers: request
 * message/tool/response-format assembly, chat/completion result decoding,
 * usage/finish-reason translation, provider-option pruning, and error-message
 * extraction. Extracted verbatim from OpenAICompatibleProvider.kt.
 */
internal object OpenAICompatibleWire {
    fun applyChatResponseTransform(settings: OpenAICompatibleProviderSettings, value: JsonElement): JsonElement =
        (value as? JsonObject)?.let { settings.transformChatResponse?.invoke(it) ?: it } ?: value

    fun openAICompatibleInBandError(value: JsonElement): OpenAICompatibleInBandError? {
        val obj = value as? JsonObject
        val error = obj?.get("error")
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

    fun toApiCallError(
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

    fun chatResultFromJson(
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
                input = parseOpenAIToolInput(WireDecoder.requiredString(function, "arguments", provider, "chat completion response", "$.choices[0].message.tool_calls[$index].function")),
                providerMetadata = thoughtSignatureMetadata(callObj)?.let { ProviderMetadata.Raw(JsonObject(it)) } ?: ProviderMetadata.None,
            )
        }
        content += toolCalls
        val finishReason = openAIFinishReason(choice["finish_reason"]?.jsonPrimitive?.contentOrNull)
        return LanguageModelResult(
            text = text,
            toolCalls = toolCalls,
            finishReason = finishReason,
            usage = (convertUsage ?: ::openAIUsage).invoke(obj["usage"]),
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

    fun completionResultFromJson(
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
            finishReason = openAIFinishReason(choice["finish_reason"]?.jsonPrimitive?.contentOrNull),
            usage = openAIUsage(obj["usage"]),
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

    fun streamResponseMetadata(obj: JsonObject): StreamEvent.ResponseMetadata? {
        val id = obj["id"]?.jsonPrimitive?.contentOrNull
        val modelId = obj["model"]?.jsonPrimitive?.contentOrNull
        val timestampMillis = obj["created"]?.jsonPrimitive?.doubleOrNull?.let { (it * 1000).toLong() }
        if (id == null && modelId == null && timestampMillis == null) return null
        return StreamEvent.ResponseMetadata(
            id = id,
            modelId = modelId,
            timestampMillis = timestampMillis,
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
    fun openAIChatMessagesJson(message: ModelMessage): List<JsonObject> = when (message.role) {
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
    fun openAIToolMessagesJson(message: ModelMessage): List<JsonObject> =
        message.content.filterIsInstance<ContentPart.ToolResult>().map { result ->
            buildJsonObject {
                put("role", JsonPrimitive("tool"))
                put("tool_call_id", JsonPrimitive(result.toolCallId))
                put("content", JsonPrimitive(openAIContentString(result.modelVisible)))
            }
        }

    fun openAIUserContentPartJson(part: ContentPart): JsonObject? = when (part) {
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
    fun openAiImageUrl(url: String?, mediaType: String, base64: String): String =
        url ?: "data:$mediaType;base64,$base64"

    fun openAIToolJson(tool: LanguageModelTool): JsonObject = buildJsonObject {
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

    fun openAIToolChoiceJson(choice: ToolChoice): JsonElement? = when (choice) {
        ToolChoice.Auto -> JsonPrimitive("auto")
        ToolChoice.None -> JsonPrimitive("none")
        ToolChoice.Required -> JsonPrimitive("required")
        is ToolChoice.Specific -> buildJsonObject {
            put("type", JsonPrimitive("function"))
            put("function", buildJsonObject { put("name", JsonPrimitive(choice.toolName)) })
        }
    }

    fun openAIResponseFormat(format: ResponseFormat, strictJsonSchema: Boolean): JsonElement? = when (format) {
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

    fun openAICompletionPrompt(messages: List<ModelMessage>): String =
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

    fun openAITextContent(value: JsonElement?): String = when (value) {
        null, JsonNull -> ""
        is JsonPrimitive -> value.contentOrNull.orEmpty()
        is JsonArray -> value.mapNotNull { item ->
            item.jsonObject.takeIf { it["type"]?.jsonPrimitive?.contentOrNull == "text" }
                ?.get("text")?.jsonPrimitive?.contentOrNull
        }.joinToString("")
        else -> value.toString()
    }

    fun openAIContentString(value: JsonElement): String = when (value) {
        is JsonPrimitive -> value.contentOrNull ?: value.toString()
        else -> value.toString()
    }

    fun openAIUsage(value: JsonElement?): Usage {
        val obj = value?.jsonObject ?: return Usage()
        val promptTokens = obj["prompt_tokens"]?.jsonPrimitive?.intOrNull ?: 0
        val completionTokens = obj["completion_tokens"]?.jsonPrimitive?.intOrNull ?: 0
        val cachedTokens = (obj["prompt_tokens_details"]?.jsonObject?.get("cached_tokens")?.jsonPrimitive?.intOrNull ?: 0)
            .coerceIn(0, promptTokens)
        val reasoningTokens = (obj["completion_tokens_details"]?.jsonObject?.get("reasoning_tokens")?.jsonPrimitive?.intOrNull ?: 0)
            .coerceAtLeast(0)
        val outputTotal = if (reasoningTokens > completionTokens) {
            completionTokens + reasoningTokens
        } else {
            completionTokens
        }
        return Usage(
            inputTokens = Usage.InputTokenBreakdown(
                total = promptTokens,
                noCache = promptTokens - cachedTokens,
                cacheRead = cachedTokens,
            ),
            outputTokens = Usage.OutputTokenBreakdown(
                total = outputTotal,
                text = outputTotal - reasoningTokens,
                reasoning = reasoningTokens,
            ),
            raw = value,
        )
    }

    fun openAICompatibleImageUsage(value: JsonElement?): ImageModelUsage {
        val obj = value as? JsonObject ?: return ImageModelUsage()
        return ImageModelUsage(
            inputTokens = obj["input_tokens"]?.jsonPrimitive?.intOrNull,
            outputTokens = obj["output_tokens"]?.jsonPrimitive?.intOrNull,
            totalTokens = obj["total_tokens"]?.jsonPrimitive?.intOrNull,
        )
    }

    fun openAIFinishReason(value: String?): FinishReason = when (value) {
        "stop" -> FinishReason.Stop
        "length" -> FinishReason.Length
        "tool_calls", "function_call" -> FinishReason.ToolCalls
        "content_filter" -> FinishReason.ContentFilter
        else -> FinishReason.Other
    }

    fun parseOpenAIToolInput(value: String?): JsonElement =
        if (value.isNullOrBlank()) {
            JsonObject(emptyMap())
        } else {
            runCatching { aiSdkJson.parseToJsonElement(value) }.getOrElse { JsonPrimitive(value) }
        }

    fun isParsableOpenAIJson(value: String): Boolean =
        value.isNotBlank() && runCatching { aiSdkJson.parseToJsonElement(value) }.isSuccess

    fun thoughtSignatureMetadata(value: JsonObject): Map<String, JsonElement>? {
        val signature = value["extra_content"]?.jsonObject
            ?.get("google")?.jsonObject
            ?.get("thought_signature")?.jsonPrimitive?.contentOrNull
        return signature?.let { mapOf("thoughtSignature" to JsonPrimitive(it)) }
    }

    fun openAIProviderOptions(options: Map<String, JsonElement>, providerName: String): JsonObject {
        val keys = listOf("openai-compatible", "openaiCompatible", providerName, toOpenAICamelCase(providerName))
        var merged = JsonObject(emptyMap())
        for (key in keys.distinct()) {
            val obj = options[key] as? JsonObject ?: continue
            merged = JsonOps.merge(merged, obj)
        }
        return merged
    }

    fun putProviderOptions(builder: JsonObjectBuilder, options: JsonObject, reserved: Set<String>) {
        for ((key, value) in options) {
            if (key !in reserved && value !is JsonNull) builder.put(key, value)
        }
    }

    fun openAIProviderMetadata(value: JsonElement?, providerName: String): Map<String, JsonElement> =
        when (value) {
            is JsonObject -> value.toMap()
            null -> emptyMap()
            else -> mapOf(providerName to value)
        }

    fun openAIFormValue(value: JsonElement): String = when (value) {
        is JsonPrimitive -> value.contentOrNull ?: value.toString()
        else -> value.toString()
    }

    fun audioMediaType(format: String): String = when (format.lowercase()) {
        "wav" -> "audio/wav"
        "opus" -> "audio/ogg"
        "aac" -> "audio/aac"
        "flac" -> "audio/flac"
        else -> "audio/mpeg"
    }

    fun openAICompatibleErrorMessage(statusCode: Int, parsed: JsonElement?, raw: String): String {
        val error = (parsed as? JsonObject)?.get("error")?.jsonObject
        val message = error?.get("message")?.jsonPrimitive?.contentOrNull ?: raw.ifBlank { "OpenAI-compatible request failed" }
        return "OpenAI-compatible request failed ($statusCode): $message"
    }

    fun toOpenAICamelCase(value: String): String =
        value.split('-', '_', '.', ' ')
            .filter { it.isNotBlank() }
            .mapIndexed { index, part -> if (index == 0) part.lowercase() else part.replaceFirstChar { it.uppercase() } }
            .joinToString("")
}
