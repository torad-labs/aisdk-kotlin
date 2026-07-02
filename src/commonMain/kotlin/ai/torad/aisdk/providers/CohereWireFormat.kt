@file:OptIn(ai.torad.aisdk.LowLevelLanguageModelApi::class)

package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

internal object CohereWireFormat {
    internal fun cohereChatRequest(
        settings: CohereProviderSettings,
        modelId: String,
        params: LanguageModelCallParams,
        stream: Boolean = false,
    ): CohereChatRequest {
        val options = settings.cohereOptions(params.providerOptions)
        val prompt = coherePrompt(params.messages)
        val toolConfig = cohereTools(params.tools, params.toolChoice)
        val body = buildJsonObject {
            put("model", JsonPrimitive(modelId))
            put("messages", JsonArray(prompt.messages))
            if (stream) put("stream", JsonPrimitive(true))
            params.maxOutputTokens?.let { put("max_tokens", JsonPrimitive(it)) }
            params.temperature?.let { put("temperature", JsonPrimitive(it)) }
            params.topP?.let { put("p", JsonPrimitive(it)) }
            params.topK?.let { put("k", JsonPrimitive(it)) }
            params.frequencyPenalty?.let { put("frequency_penalty", JsonPrimitive(it)) }
            params.presencePenalty?.let { put("presence_penalty", JsonPrimitive(it)) }
            params.seed?.let { put("seed", JsonPrimitive(it)) }
            if (params.stopSequences.isNotEmpty()) {
                put(
                    "stop_sequences",
                    JsonArray(params.stopSequences.map(::JsonPrimitive))
                )
            }
            cohereResponseFormat(params.responseFormat)?.let { put("response_format", it) }
            if (toolConfig.tools.isNotEmpty()) put("tools", JsonArray(toolConfig.tools))
            toolConfig.toolChoice?.let { put("tool_choice", it) }
            if (prompt.documents.isNotEmpty()) put("documents", JsonArray(prompt.documents))
            (JsonAccess.obj(options, "thinking"))?.let { thinking ->
                put(
                    "thinking",
                    buildJsonObject {
                        put("type", thinking["type"] ?: JsonPrimitive("enabled"))
                        thinking["tokenBudget"]?.let { put("token_budget", it) }
                    }
                )
            }
        }
        return CohereChatRequest(body, prompt.warnings + toolConfig.warnings)
    }

    private fun coherePrompt(messages: List<ModelMessage>): CoherePreparedPrompt {
        val documents = mutableListOf<JsonObject>()
        val warnings = mutableListOf<CallWarning>()
        val cohereMessages = messages.flatMap { cohereMessagesFor(it, documents, warnings) }
        return CoherePreparedPrompt(cohereMessages, documents, warnings)
    }

    private fun cohereMessagesFor(
        message: ModelMessage,
        documents: MutableList<JsonObject>,
        warnings: MutableList<CallWarning>,
    ): List<JsonObject> = when (message.role) {
        MessageRole.System -> listOf(
            buildJsonObject {
                put("role", JsonPrimitive("system"))
                put("content", JsonPrimitive(message.content.textContent()))
            },
        )
        MessageRole.User -> listOf(cohereUserMessage(message.content, documents, warnings))
        MessageRole.Assistant -> listOf(cohereAssistantMessage(message.content))
        MessageRole.Tool -> cohereToolMessages(message.content)
    }

    private fun cohereUserMessage(
        content: List<ContentPart>,
        documents: MutableList<JsonObject>,
        warnings: MutableList<CallWarning>,
    ): JsonObject {
        val parts = mutableListOf<JsonObject>()
        for (part in content) {
            when (part) {
                is ContentPart.Text -> if (part.text.isNotEmpty()) {
                    parts += buildJsonObject {
                        put("type", JsonPrimitive("text"))
                        put("text", JsonPrimitive(part.text))
                    }
                }
                is ContentPart.File -> if (part.mediaType.isCohereImageMediaType()) {
                    parts += cohereImagePart(part.mediaType, part.base64, part.url, part.providerMetadata)
                } else if (part.mediaType.isCohereDocumentMediaType()) {
                    documents += cohereDocumentPart(part)
                } else {
                    throw InvalidArgumentError(
                        "messages",
                        "Cohere supports image files, text/* documents, and application/json documents; got ${part.mediaType}.",
                    )
                }
                is ContentPart.Image ->
                    parts += cohereImagePart(part.mediaType, part.base64, part.url, part.providerMetadata)
                is ContentPart.Source -> warnings += CallWarning(
                    type = "unsupported",
                    message = "Cohere chat prompt conversion ignores source content parts.",
                )
                is ContentPart.Reasoning,
                is ContentPart.ToolCall,
                is ContentPart.ToolResult,
                is ContentPart.ToolApprovalRequest,
                is ContentPart.ToolApprovalResponse,
                is ContentPart.Raw,
                -> Unit
            }
        }
        val hasImage = parts.any { (it["type"] as? JsonPrimitive)?.contentOrNull == "image_url" }
        return buildJsonObject {
            put("role", JsonPrimitive("user"))
            put(
                "content",
                if (hasImage) {
                    JsonArray(parts)
                } else {
                    JsonPrimitive(parts.joinToString("") { (it["text"] as? JsonPrimitive)?.contentOrNull.orEmpty() })
                },
            )
        }
    }

    private fun cohereAssistantMessage(content: List<ContentPart>): JsonObject {
        val toolCalls = content.filterIsInstance<ContentPart.ToolCall>()
        return buildJsonObject {
            put("role", JsonPrimitive("assistant"))
            if (toolCalls.isEmpty()) {
                put("content", JsonPrimitive(content.textContent()))
            } else {
                put("tool_calls", JsonArray(toolCalls.map { cohereAssistantToolCall(it) }))
            }
        }
    }

    private fun cohereAssistantToolCall(call: ContentPart.ToolCall): JsonObject = buildJsonObject {
        val arguments = aiSdkOutputJson.encodeToString(JsonElement.serializer(), call.input)
        put("id", JsonPrimitive(call.toolCallId))
        put("type", JsonPrimitive("function"))
        put(
            "function",
            buildJsonObject {
                put("name", JsonPrimitive(call.toolName))
                put("arguments", JsonPrimitive(arguments))
            },
        )
    }

    private fun cohereToolMessages(content: List<ContentPart>): List<JsonObject> =
        content.filterIsInstance<ContentPart.ToolResult>().map { result ->
            buildJsonObject {
                put("role", JsonPrimitive("tool"))
                put("content", JsonPrimitive(cohereToolResultContent(result.modelVisible)))
                put("tool_call_id", JsonPrimitive(result.toolCallId))
            }
        }

    private fun cohereToolResultContent(value: JsonElement): String =
        when (val output = ToolResultOutputs.toolResultOutputFromWire(value)) {
            is ToolResultOutput.Text -> output.text
            is ToolResultOutput.Error -> output.message
            is ToolResultOutput.ExecutionDenied -> output.reason ?: "Tool execution denied."
            is ToolResultOutput.Json -> output.json.toString()
            is ToolResultOutput.ErrorJson -> output.json.toString()
            is ToolResultOutput.Content -> output.value.joinToString("", transform = ::cohereToolResultItemText)
        }

    /** Flatten one MCP content item to plain text — Cohere tool content is a string, so images can't ride here. */
    private fun cohereToolResultItemText(item: JsonElement): String =
        (
            (item as? JsonObject)?.takeIf { (it["type"] as? JsonPrimitive)?.contentOrNull == "text" }
                ?.get("text") as? JsonPrimitive
            )?.contentOrNull.orEmpty()

    private fun cohereImagePart(
        mediaType: String,
        base64: String,
        url: String?,
        providerMetadata: ProviderMetadata,
    ): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("image_url"))
        put(
            "image_url",
            buildJsonObject {
                val resolved = if (!url.isNullOrEmpty()) {
                    url
                } else {
                    "data:${mediaType.normalizeCohereImageMediaType()};base64,$base64"
                }
                put("url", JsonPrimitive(resolved))
                val detail = JsonAccess.obj(providerMetadata.toMap(), "cohere")?.get("detail") as? JsonPrimitive
                detail?.contentOrNull?.let { put("detail", JsonPrimitive(it)) }
            },
        )
    }

    private fun cohereDocumentPart(part: ContentPart.File): JsonObject = buildJsonObject {
        put(
            "data",
            buildJsonObject {
                put(
                    "text",
                    JsonPrimitive(
                        runCatching { Base64Codec.decode(part.base64).decodeToString() }.getOrElse {
                            throw InvalidArgumentError("messages", "Cohere document file content must be valid base64.")
                        },
                    ),
                )
                part.filename?.let { put("title", JsonPrimitive(it)) }
            }
        )
    }

    private fun cohereTools(tools: List<LanguageModelTool>, toolChoice: ToolChoice): CoherePreparedTools {
        if (tools.isEmpty()) return CoherePreparedTools(emptyList(), null, emptyList())

        val warnings = mutableListOf<CallWarning>()
        val convertedTools = tools.mapNotNull { tool ->
            if (tool.providerExecuted) {
                warnings += CallWarning(
                    type = "unsupported",
                    message = "Cohere does not support provider-executed tool `${tool.name}`.",
                )
                null
            } else {
                tool.name to cohereToolJson(tool)
            }
        }

        val selectedTools = when (toolChoice) {
            is ToolChoice.Specific -> convertedTools.filter { it.first == toolChoice.toolName }
            ToolChoice.Auto,
            ToolChoice.None,
            ToolChoice.Required,
            -> convertedTools
        }

        return CoherePreparedTools(
            tools = selectedTools.map { it.second },
            toolChoice = when (toolChoice) {
                ToolChoice.Auto -> null
                ToolChoice.None -> JsonPrimitive("NONE")
                ToolChoice.Required -> JsonPrimitive("REQUIRED")
                is ToolChoice.Specific -> JsonPrimitive("REQUIRED")
            },
            warnings = warnings,
        )
    }

    private fun cohereToolJson(tool: LanguageModelTool): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("function"))
        put(
            "function",
            buildJsonObject {
                put("name", JsonPrimitive(tool.name))
                put("description", JsonPrimitive(tool.description))
                put("parameters", aiSdkJson.parseToJsonElement(tool.parametersSchemaJson))
            }
        )
    }

    private fun cohereResponseFormat(responseFormat: ResponseFormat): JsonObject? = when (responseFormat) {
        ResponseFormat.Text -> null
        is ResponseFormat.Json -> buildJsonObject {
            put("type", JsonPrimitive("json_object"))
            responseFormat.schemaJson?.let { put("json_schema", it) }
        }
    }

    private fun String.isCohereImageMediaType(): Boolean = this == "image" || startsWith("image/")

    private fun String.normalizeCohereImageMediaType(): String = when (this) {
        "image", "image/*" -> "image/jpeg"
        else -> this
    }

    private fun String.isCohereDocumentMediaType(): Boolean = startsWith("text/") || this == "application/json"

    // Parse one Cohere tool_call array element, skipping a non-object element (Wave 7b). Extracted
    // so cohereChatResult stays under the cyclomatic-complexity threshold after the skip guards.
    private fun cohereToolCallPart(call: JsonElement): ContentPart.ToolCall? {
        val obj = call as? JsonObject ?: return null
        val function = (JsonAccess.obj(obj, "function")) ?: JsonObject(emptyMap())
        return ContentPart.ToolCall(
            toolCallId = (obj["id"] as? JsonPrimitive)?.contentOrNull ?: IdGenerator.generate("call"),
            toolName = (function["name"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            input = cohereToolInput((function["arguments"] as? JsonPrimitive)?.contentOrNull),
        )
    }

    // Parse one Cohere citation array element, skipping a non-object element (Wave 7b).
    private fun cohereCitationPart(citation: JsonElement): ContentPart.Source? {
        val obj = citation as? JsonObject ?: return null
        val sourceObj = (JsonAccess.arr(obj, "sources"))?.firstOrNull() as? JsonObject
        val documentObj = sourceObj?.get("document") as? JsonObject
        return ContentPart.Source(
            sourceType = StreamEvent.SourcePart.SourceType.Document,
            title = (documentObj?.get("title") as? JsonPrimitive)?.contentOrNull ?: "Document",
            providerMetadata = ProviderMetadata.Raw(JsonObject(mapOf("cohere" to obj))),
        )
    }

    internal fun cohereChatResult(
        value: JsonObject,
        requestBody: JsonObject,
        headers: Map<String, String>,
        responseBody: JsonElement,
        warnings: List<CallWarning>,
    ): LanguageModelResult {
        val message = (JsonAccess.obj(value, "message")) ?: JsonObject(emptyMap())
        val content = mutableListOf<ContentPart>()
        val text = (JsonAccess.arr(message, "content")).orEmpty().joinToString("") { part ->
            val obj = part as? JsonObject ?: return@joinToString ""
            when ((obj["type"] as? JsonPrimitive)?.contentOrNull) {
                "text" -> (obj["text"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                else -> ""
            }
        }
        if (text.isNotEmpty()) content += ContentPart.Text(text)
        (JsonAccess.arr(message, "content")).orEmpty().forEach { part ->
            val obj = part as? JsonObject ?: return@forEach
            if ((obj["type"] as? JsonPrimitive)?.contentOrNull == "thinking") {
                (obj["thinking"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }?.let {
                    content += ContentPart.Reasoning(it)
                }
            }
        }
        val toolCalls = (JsonAccess.arr(message, "tool_calls")).orEmpty().mapNotNull(::cohereToolCallPart)
        content += toolCalls
        content += (JsonAccess.arr(message, "citations")).orEmpty().mapNotNull(::cohereCitationPart)
        return LanguageModelResult(
            text = text,
            toolCalls = toolCalls,
            finishReason = cohereFinishReason((value["finish_reason"] as? JsonPrimitive)?.contentOrNull),
            usage = cohereUsage((JsonAccess.obj(value, "usage"))),
            content = content,
            rawFinishReason = (value["finish_reason"] as? JsonPrimitive)?.contentOrNull,
            request = LanguageModelRequestMetadata(requestBody),
            response = LanguageModelResponseMetadata(
                id = (value["generation_id"] as? JsonPrimitive)?.contentOrNull
                    ?: (value["id"] as? JsonPrimitive)?.contentOrNull,
                headers = headers,
                body = responseBody,
            ),
            warnings = warnings,
        )
    }

    internal fun cohereToolInput(value: String?): JsonElement {
        val normalized = if (value == "null") "{}" else value
        return if (normalized.isNullOrBlank()) {
            JsonObject(emptyMap())
        } else {
            runCatching {
                aiSdkJson.parseToJsonElement(normalized)
            }.getOrElse { JsonPrimitive(normalized) }
        }
    }

    internal fun cohereUsage(value: JsonObject?): Usage {
        val tokens = (value?.get("tokens") as? JsonObject)
        val input = (tokens?.get("input_tokens") as? JsonPrimitive)?.intOrNull ?: 0
        val output = (tokens?.get("output_tokens") as? JsonPrimitive)?.intOrNull ?: 0
        return Usage(
            inputTokens = Usage.InputTokenBreakdown(
                total = input,
                noCache = input,
            ),
            outputTokens = Usage.OutputTokenBreakdown(total = output, text = output),
            raw = tokens,
        )
    }

    internal fun cohereFinishReason(value: String?): FinishReason = when (value) {
        // Upstream maps both COMPLETE and STOP_SEQUENCE to stop; ERROR_TOXIC has no dedicated
        // case and falls through to `other`, not content-filter.
        "COMPLETE", "STOP_SEQUENCE", "stop" -> FinishReason.Stop
        "MAX_TOKENS" -> FinishReason.Length
        "TOOL_CALL" -> FinishReason.ToolCalls
        "ERROR" -> FinishReason.Error
        else -> FinishReason.Other
    }

    private fun List<ContentPart>.textContent(): String =
        filterIsInstance<ContentPart.Text>().joinToString("") { it.text }
}

internal data class CohereChatRequest(
    val body: JsonObject,
    val warnings: List<CallWarning>,
)

private data class CoherePreparedPrompt(
    val messages: List<JsonObject>,
    val documents: List<JsonObject>,
    val warnings: List<CallWarning>,
)

private data class CoherePreparedTools(
    val tools: List<JsonObject>,
    val toolChoice: JsonElement?,
    val warnings: List<CallWarning>,
)
