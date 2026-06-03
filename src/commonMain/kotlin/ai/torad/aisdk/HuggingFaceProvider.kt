package ai.torad.aisdk

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

const val HUGGINGFACE_VERSION: String = "1.0.50"

typealias HuggingFaceResponsesModelId = String
typealias HuggingFaceErrorData = JsonObject

data class HuggingFaceProviderSettings(
    val apiKey: String? = null,
    val baseURL: String = "https://router.huggingface.co/v1",
    val headers: Map<String, String> = emptyMap(),
    val generateId: () -> String = { ai.torad.aisdk.generateId() },
)

@Serializable
data class HuggingFaceResponsesSettings(
    val metadata: Map<String, String>? = null,
    val instructions: String? = null,
    val strictJsonSchema: Boolean? = null,
    val reasoningEffort: String? = null,
)

interface HuggingFaceProvider : Provider {
    val settings: HuggingFaceProviderSettings

    operator fun invoke(modelId: HuggingFaceResponsesModelId): LanguageModel = languageModel(modelId)
    fun responses(modelId: HuggingFaceResponsesModelId): LanguageModel = languageModel(modelId)
    fun textEmbeddingModel(modelId: String): Nothing = throw huggingFaceNoEmbeddingModel(providerId, modelId)
}

fun createHuggingFace(
    client: HttpClient,
    settings: HuggingFaceProviderSettings = HuggingFaceProviderSettings(),
): HuggingFaceProvider = DefaultHuggingFaceProvider(client, settings)

val huggingface: HuggingFaceProvider = object : HuggingFaceProvider {
    override val providerId: String = "huggingface"
    override val settings: HuggingFaceProviderSettings = HuggingFaceProviderSettings()
    override fun languageModel(modelId: String): LanguageModel =
        throw AiSdkException("Hugging Face provider is not configured. Use createHuggingFace(client, settings).")
    override fun embeddingModel(modelId: String): EmbeddingModel = throw huggingFaceNoEmbeddingModel(providerId, modelId)
    override fun imageModel(modelId: String): ImageModel = throw huggingFaceNoImageModel(providerId, modelId)
}

private class DefaultHuggingFaceProvider(
    private val client: HttpClient,
    override val settings: HuggingFaceProviderSettings,
) : HuggingFaceProvider {
    override val providerId: String = "huggingface"

    override fun languageModel(modelId: String): LanguageModel =
        HuggingFaceResponsesLanguageModel(client, settings, modelId)

    override fun embeddingModel(modelId: String): EmbeddingModel = throw huggingFaceNoEmbeddingModel(providerId, modelId)

    override fun imageModel(modelId: String): ImageModel = throw huggingFaceNoImageModel(providerId, modelId)
}

private class HuggingFaceResponsesLanguageModel(
    private val client: HttpClient,
    private val settings: HuggingFaceProviderSettings,
    override val modelId: String,
) : LanguageModel {
    override val provider: String = "huggingface.responses"
    override val supportedUrls: Map<String, List<String>> = mapOf("image/*" to listOf("^https?://.*$"))

    override suspend fun generate(params: LanguageModelCallParams): LanguageModelResult {
        val prepared = huggingFaceResponsesRequestBody(modelId, params, stream = false)
        val response = postJson(prepared.body, acceptEventStream = false, parseJson = true, headers = params.headers)
        return huggingFaceResponsesResult(
            response = response.value.jsonObject,
            requestBody = prepared.body,
            responseHeaders = response.headers,
            responseBody = response.value,
            warnings = prepared.warnings,
            settings = settings,
            json = huggingFaceJson,
        )
    }

    override fun stream(params: LanguageModelCallParams): Flow<StreamEvent> = flow {
        val prepared = huggingFaceResponsesRequestBody(modelId, params, stream = true)
        val response = postJson(prepared.body, acceptEventStream = true, parseJson = false, headers = params.headers)
        emit(StreamEvent.StreamStart(prepared.warnings))
        emit(StreamEvent.ResponseMetadata(headers = response.headers, body = JsonPrimitive(response.rawText)))

        val state = HuggingFaceResponsesStreamState(settings, huggingFaceJson)
        for (event in parseJsonEventStream(response.rawText, jsonSchema<JsonElement>(JsonObject(emptyMap())), huggingFaceJson)) {
            when (event) {
                is ParseResult.Success -> state.accept(event.value).forEach { emit(it) }
                is ParseResult.Failure -> emit(
                    StreamEvent.Error("Failed to parse Hugging Face Responses stream event: ${event.error.message}"),
                )
            }
        }
        state.finish().forEach { emit(it) }
    }

    override fun streamResult(params: LanguageModelCallParams): LanguageModelStreamResult {
        val prepared = huggingFaceResponsesRequestBody(modelId, params, stream = true)
        return LanguageModelStreamResult(
            stream = stream(params),
            request = LanguageModelRequestMetadata(body = prepared.body),
        )
    }

    private suspend fun postJson(
        body: JsonElement,
        acceptEventStream: Boolean,
        parseJson: Boolean,
        headers: Map<String, String>,
    ): HuggingFaceHttpResponse {
        val response = client.request("${settings.baseURL.trimEnd('/')}/responses") {
            method = HttpMethod.Post
            contentType(ContentType.Application.Json)
            if (acceptEventStream) header(HttpHeaders.Accept, "text/event-stream")
            huggingFaceHeaders(settings, headers).forEach { (name, value) -> header(name, value) }
            setBody(huggingFaceJson.encodeToString(JsonElement.serializer(), body))
        }
        return huggingFaceParseResponse(response, parseJson)
    }
}

private data class HuggingFaceHttpResponse(
    val value: JsonElement,
    val rawText: String,
    val headers: Map<String, String>,
)

private data class HuggingFacePreparedRequest(
    val body: JsonObject,
    val warnings: List<CallWarning>,
)

private data class HuggingFaceConvertedMessages(
    val input: JsonArray,
    val warnings: List<CallWarning>,
)

private data class HuggingFacePreparedTools(
    val tools: List<JsonObject>,
    val toolChoice: JsonElement?,
    val warnings: List<CallWarning>,
)

private val huggingFaceJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
}

private fun huggingFaceResponsesRequestBody(
    modelId: String,
    params: LanguageModelCallParams,
    stream: Boolean,
): HuggingFacePreparedRequest {
    val warnings = mutableListOf<CallWarning>()
    params.topK?.let { warnings += CallWarning("unsupported", "topK") }
    params.seed?.let { warnings += CallWarning("unsupported", "seed") }
    params.presencePenalty?.let { warnings += CallWarning("unsupported", "presencePenalty") }
    params.frequencyPenalty?.let { warnings += CallWarning("unsupported", "frequencyPenalty") }
    if (params.stopSequences.isNotEmpty()) warnings += CallWarning("unsupported", "stopSequences")

    val messages = huggingFaceMessages(params.messages)
    warnings += messages.warnings
    val providerOptions = huggingFaceProviderOptions(params.providerOptions)
    val preparedTools = huggingFaceTools(params.tools, params.toolChoice)
    warnings += preparedTools.warnings

    return HuggingFacePreparedRequest(
        body = buildJsonObject {
            put("model", JsonPrimitive(modelId))
            put("input", messages.input)
            params.temperature?.let { put("temperature", JsonPrimitive(it)) }
            params.topP?.let { put("top_p", JsonPrimitive(it)) }
            params.maxOutputTokens?.let { put("max_output_tokens", JsonPrimitive(it)) }
            put("stream", JsonPrimitive(stream))
            huggingFaceTextFormat(params.responseFormat, providerOptions)?.let { format ->
                put("text", buildJsonObject { put("format", format) })
            }
            providerOptions?.metadata?.let { metadata ->
                put("metadata", JsonObject(metadata.mapValues { JsonPrimitive(it.value) }))
            }
            providerOptions?.instructions?.let { put("instructions", JsonPrimitive(it)) }
            if (preparedTools.tools.isNotEmpty()) put("tools", JsonArray(preparedTools.tools))
            preparedTools.toolChoice?.let { put("tool_choice", it) }
            providerOptions?.reasoningEffort?.let { effort ->
                put("reasoning", buildJsonObject { put("effort", JsonPrimitive(effort)) })
            }
        },
        warnings = warnings,
    )
}

private fun huggingFaceMessages(messages: List<ModelMessage>): HuggingFaceConvertedMessages {
    val input = mutableListOf<JsonElement>()
    val warnings = mutableListOf<CallWarning>()

    for (message in messages) {
        when (message.role) {
            MessageRole.System -> input += buildJsonObject {
                put("role", JsonPrimitive("system"))
                put("content", JsonPrimitive(message.content.textContent()))
            }
            MessageRole.User -> input += buildJsonObject {
                put("role", JsonPrimitive("user"))
                put("content", JsonArray(message.content.mapNotNull(::huggingFaceUserContentPart)))
            }
            MessageRole.Assistant -> message.content.forEach { part ->
                when (part) {
                    is ContentPart.Text -> input += huggingFaceAssistantMessage(part.text)
                    is ContentPart.Reasoning -> input += huggingFaceAssistantMessage(part.text)
                    else -> Unit
                }
            }
            MessageRole.Tool -> warnings += CallWarning("unsupported", "tool messages")
        }
    }

    return HuggingFaceConvertedMessages(JsonArray(input), warnings)
}

private fun List<ContentPart>.textContent(): String =
    joinToString("") { part ->
        when (part) {
            is ContentPart.Text -> part.text
            is ContentPart.Reasoning -> part.text
            else -> ""
        }
    }

private fun huggingFaceAssistantMessage(text: String): JsonObject = buildJsonObject {
    put("role", JsonPrimitive("assistant"))
    put(
        "content",
        JsonArray(
            listOf(
                buildJsonObject {
                    put("type", JsonPrimitive("output_text"))
                    put("text", JsonPrimitive(text))
                },
            ),
        ),
    )
}

private fun huggingFaceUserContentPart(part: ContentPart): JsonElement? = when (part) {
    is ContentPart.Text -> buildJsonObject {
        put("type", JsonPrimitive("input_text"))
        put("text", JsonPrimitive(part.text))
    }
    is ContentPart.Image -> buildJsonObject {
        put("type", JsonPrimitive("input_image"))
        put("image_url", JsonPrimitive("data:${huggingFaceImageMediaType(part.mediaType)};base64,${part.base64}"))
    }
    is ContentPart.File -> {
        if (!part.mediaType.startsWith("image/")) {
            throw AiSdkException("Hugging Face Responses API does not support file part media type ${part.mediaType}.")
        }
        buildJsonObject {
            put("type", JsonPrimitive("input_image"))
            put("image_url", JsonPrimitive("data:${huggingFaceImageMediaType(part.mediaType)};base64,${part.base64}"))
        }
    }
    else -> null
}

private fun huggingFaceImageMediaType(mediaType: String): String =
    if (mediaType == "image/*") "image/jpeg" else mediaType

private fun huggingFaceTools(
    tools: List<LanguageModelTool>,
    choice: ToolChoice,
): HuggingFacePreparedTools {
    if (tools.isEmpty()) return HuggingFacePreparedTools(emptyList(), null, emptyList())

    val warnings = mutableListOf<CallWarning>()
    val prepared = tools.mapNotNull { tool ->
        if (tool.providerExecuted) {
            warnings += CallWarning("unsupported", "provider-defined tool ${tool.name}")
            null
        } else {
            buildJsonObject {
                put("type", JsonPrimitive("function"))
                put("name", JsonPrimitive(tool.name))
                if (tool.description.isNotBlank()) put("description", JsonPrimitive(tool.description))
                put("parameters", huggingFaceJson.parseToJsonElement(tool.parametersSchemaJson))
            }
        }
    }

    val toolChoice = when (choice) {
        ToolChoice.Auto -> JsonPrimitive("auto")
        ToolChoice.Required -> JsonPrimitive("required")
        ToolChoice.None -> null
        is ToolChoice.Specific -> buildJsonObject {
            put("type", JsonPrimitive("function"))
            put("function", buildJsonObject { put("name", JsonPrimitive(choice.toolName)) })
        }
    }

    return HuggingFacePreparedTools(prepared, toolChoice, warnings)
}

private fun huggingFaceTextFormat(
    format: ResponseFormat,
    options: HuggingFaceResponsesSettings?,
): JsonElement? = when (format) {
    ResponseFormat.Text -> null
    is ResponseFormat.Json -> format.schemaJson?.let { schema ->
        buildJsonObject {
            put("type", JsonPrimitive("json_schema"))
            put("strict", JsonPrimitive(options?.strictJsonSchema ?: false))
            put("name", JsonPrimitive(format.schemaName ?: "response"))
            format.schemaDescription?.let { put("description", JsonPrimitive(it)) }
            put("schema", schema)
        }
    }
}

private fun huggingFaceProviderOptions(providerOptions: Map<String, JsonElement>): HuggingFaceResponsesSettings? {
    val element = providerOptions["huggingface"] ?: providerOptions["hugging-face"] ?: return null
    return runCatching { huggingFaceJson.decodeFromJsonElement(HuggingFaceResponsesSettings.serializer(), element) }
        .getOrNull()
}

private fun huggingFaceResponsesResult(
    response: JsonObject,
    requestBody: JsonElement,
    responseHeaders: Map<String, String>,
    responseBody: JsonElement,
    warnings: List<CallWarning>,
    settings: HuggingFaceProviderSettings,
    json: Json,
): LanguageModelResult {
    response["error"]?.takeIf { it !is JsonNull }?.let { error ->
        throw AiSdkException("Hugging Face API error: ${huggingFaceErrorMessage(error)}")
    }

    val content = mutableListOf<ContentPart>()
    val toolCalls = mutableListOf<ContentPart.ToolCall>()

    for (part in (response["output"] as? JsonArray).orEmpty()) {
        val obj = part.jsonObject
        val itemId = obj["id"]?.jsonPrimitive?.contentOrNull
        val providerMetadata = itemId?.let(::huggingFaceItemMetadata)
        when (obj["type"]?.jsonPrimitive?.contentOrNull) {
            "message" -> {
                for (messagePart in (obj["content"] as? JsonArray).orEmpty()) {
                    val messageObj = messagePart.jsonObject
                    val text = messageObj["text"]?.jsonPrimitive?.contentOrNull
                    if (!text.isNullOrEmpty()) content += ContentPart.Text(text, providerMetadata)
                    for (annotation in (messageObj["annotations"] as? JsonArray).orEmpty()) {
                        val annotationObj = annotation as? JsonObject ?: continue
                        content += ContentPart.Source(
                            sourceType = StreamEvent.SourcePart.SourceType.Url,
                            url = annotationObj["url"]?.jsonPrimitive?.contentOrNull,
                            title = annotationObj["title"]?.jsonPrimitive?.contentOrNull,
                        )
                    }
                }
            }
            "reasoning" -> {
                val reasoningParts = ((obj["content"] as? JsonArray).orEmpty() + (obj["summary"] as? JsonArray).orEmpty())
                for (reasoningPart in reasoningParts) {
                    val text = reasoningPart.jsonObject["text"]?.jsonPrimitive?.contentOrNull
                    if (!text.isNullOrEmpty()) content += ContentPart.Reasoning(text, providerMetadata)
                }
            }
            "function_call" -> {
                val callId = obj["call_id"]?.jsonPrimitive?.contentOrNull ?: settings.generateId()
                val toolName = obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val toolCall = ContentPart.ToolCall(
                    toolCallId = callId,
                    toolName = toolName,
                    input = huggingFaceParseToolInput(obj["arguments"]?.jsonPrimitive?.contentOrNull, json),
                    providerMetadata = providerMetadata,
                )
                toolCalls += toolCall
                content += toolCall
                obj["output"]?.jsonPrimitive?.contentOrNull?.let { output ->
                    content += ContentPart.ToolResult(callId, toolName, JsonPrimitive(output), providerMetadata = providerMetadata)
                }
            }
            "mcp_call" -> {
                val callId = itemId ?: settings.generateId()
                val toolName = obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val mcpMetadata = huggingFaceItemMetadata(callId, providerExecuted = true)
                val toolCall = ContentPart.ToolCall(
                    toolCallId = callId,
                    toolName = toolName,
                    input = huggingFaceParseToolInput(obj["arguments"]?.jsonPrimitive?.contentOrNull, json),
                    providerMetadata = mcpMetadata,
                )
                toolCalls += toolCall
                content += toolCall
                obj["output"]?.jsonPrimitive?.contentOrNull?.let { output ->
                    content += ContentPart.ToolResult(callId, toolName, JsonPrimitive(output), providerMetadata = mcpMetadata)
                }
            }
            "mcp_list_tools" -> {
                val callId = itemId ?: settings.generateId()
                val mcpMetadata = huggingFaceItemMetadata(callId, providerExecuted = true)
                val toolCall = ContentPart.ToolCall(
                    toolCallId = callId,
                    toolName = "list_tools",
                    input = buildJsonObject {
                        put("server_label", obj["server_label"] ?: JsonPrimitive(""))
                    },
                    providerMetadata = mcpMetadata,
                )
                toolCalls += toolCall
                content += toolCall
                (obj["tools"] as? JsonArray)?.let { tools ->
                    content += ContentPart.ToolResult(
                        toolCallId = callId,
                        toolName = "list_tools",
                        output = buildJsonObject { put("tools", tools) },
                        providerMetadata = mcpMetadata,
                    )
                }
            }
        }
    }

    val incompleteReason = response["incomplete_details"]?.jsonObject?.get("reason")?.jsonPrimitive?.contentOrNull
    val text = content.filterIsInstance<ContentPart.Text>().joinToString("") { it.text }
    val responseId = response["id"]?.jsonPrimitive?.contentOrNull
    return LanguageModelResult(
        text = text,
        toolCalls = toolCalls,
        finishReason = mapHuggingFaceFinishReason(incompleteReason ?: "stop"),
        usage = huggingFaceUsage(response["usage"]),
        providerMetadata = responseId?.let { mapOf("huggingface" to buildJsonObject { put("responseId", JsonPrimitive(it)) }) }.orEmpty(),
        content = content,
        rawFinishReason = incompleteReason,
        warnings = warnings,
        request = LanguageModelRequestMetadata(body = requestBody),
        response = LanguageModelResponseMetadata(
            id = responseId,
            timestampMillis = response["created_at"]?.jsonPrimitive?.longOrNull?.times(1000),
            modelId = response["model"]?.jsonPrimitive?.contentOrNull,
            headers = responseHeaders,
            body = responseBody,
        ),
    )
}

private class HuggingFaceResponsesStreamState(
    private val settings: HuggingFaceProviderSettings,
    private val json: Json,
) {
    private var finishReason = FinishReason.Other
    private var usage = Usage()
    private var responseId: String? = null
    private val openReasoningIds = mutableSetOf<String>()

    fun accept(chunk: JsonElement): List<StreamEvent> {
        val obj = chunk as? JsonObject ?: return emptyList()
        val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: return emptyList()
        val events = mutableListOf<StreamEvent>()
        when (type) {
            "response.created" -> {
                val response = obj["response"]?.jsonObject ?: return emptyList()
                responseId = response["id"]?.jsonPrimitive?.contentOrNull
                events += StreamEvent.ResponseMetadata(
                    id = responseId,
                    timestampMillis = response["created_at"]?.jsonPrimitive?.longOrNull?.times(1000),
                    modelId = response["model"]?.jsonPrimitive?.contentOrNull,
                )
            }
            "response.output_item.added" -> {
                val item = obj["item"]?.jsonObject ?: return emptyList()
                val itemId = item["id"]?.jsonPrimitive?.contentOrNull ?: obj["item_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val itemType = item["type"]?.jsonPrimitive?.contentOrNull
                when (itemType) {
                    "message" -> if (item["role"]?.jsonPrimitive?.contentOrNull == null || item["role"]?.jsonPrimitive?.contentOrNull == "assistant") {
                        events += StreamEvent.TextStart(itemId, huggingFaceItemMetadata(itemId))
                    }
                    "reasoning" -> {
                        openReasoningIds += itemId
                        events += StreamEvent.ReasoningStart(itemId, huggingFaceItemMetadata(itemId))
                    }
                    "function_call" -> events += StreamEvent.ToolInputStart(
                        id = item["call_id"]?.jsonPrimitive?.contentOrNull ?: itemId,
                        toolName = item["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    )
                    "mcp_call" -> events += StreamEvent.ToolInputStart(
                        id = itemId,
                        toolName = item["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        providerMetadata = huggingFaceItemMetadata(itemId, providerExecuted = true),
                    )
                }
            }
            "response.output_item.done" -> {
                val item = obj["item"]?.jsonObject ?: return emptyList()
                val itemId = item["id"]?.jsonPrimitive?.contentOrNull ?: obj["item_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                when (item["type"]?.jsonPrimitive?.contentOrNull) {
                    "message" -> events += StreamEvent.TextEnd(itemId, huggingFaceItemMetadata(itemId))
                    "reasoning" -> if (openReasoningIds.remove(itemId)) {
                        events += StreamEvent.ReasoningEnd(itemId, huggingFaceItemMetadata(itemId))
                    }
                    "function_call" -> {
                        val callId = item["call_id"]?.jsonPrimitive?.contentOrNull ?: settings.generateId()
                        val toolName = item["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        events += StreamEvent.ToolInputEnd(callId)
                        events += StreamEvent.ToolCall(
                            toolCallId = callId,
                            toolName = toolName,
                            inputJson = huggingFaceParseToolInput(item["arguments"]?.jsonPrimitive?.contentOrNull, json),
                            providerMetadata = huggingFaceItemMetadata(itemId),
                        )
                        item["output"]?.jsonPrimitive?.contentOrNull?.let { output ->
                            events += StreamEvent.ToolResult(callId, toolName, JsonPrimitive(output), providerMetadata = huggingFaceItemMetadata(itemId))
                        }
                    }
                    "mcp_call" -> {
                        val toolName = item["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        val metadata = huggingFaceItemMetadata(itemId, providerExecuted = true)
                        events += StreamEvent.ToolInputEnd(itemId, metadata)
                        events += StreamEvent.ToolCall(
                            toolCallId = itemId,
                            toolName = toolName,
                            inputJson = huggingFaceParseToolInput(item["arguments"]?.jsonPrimitive?.contentOrNull, json),
                            providerMetadata = metadata,
                        )
                        item["output"]?.jsonPrimitive?.contentOrNull?.let { output ->
                            events += StreamEvent.ToolResult(itemId, toolName, JsonPrimitive(output), providerMetadata = metadata)
                        }
                    }
                    "mcp_list_tools" -> {
                        val metadata = huggingFaceItemMetadata(itemId, providerExecuted = true)
                        events += StreamEvent.ToolCall(
                            toolCallId = itemId,
                            toolName = "list_tools",
                            inputJson = buildJsonObject {
                                put("server_label", item["server_label"] ?: JsonPrimitive(""))
                            },
                            providerMetadata = metadata,
                        )
                        (item["tools"] as? JsonArray)?.let { tools ->
                            events += StreamEvent.ToolResult(
                                toolCallId = itemId,
                                toolName = "list_tools",
                                outputJson = buildJsonObject { put("tools", tools) },
                                providerMetadata = metadata,
                            )
                        }
                    }
                }
            }
            "response.reasoning_text.delta" -> {
                val itemId = obj["item_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                events += StreamEvent.ReasoningDelta(
                    id = itemId,
                    text = obj["delta"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    providerMetadata = huggingFaceItemMetadata(itemId),
                )
            }
            "response.reasoning_text.done" -> {
                val itemId = obj["item_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                openReasoningIds.remove(itemId)
                events += StreamEvent.ReasoningEnd(itemId, huggingFaceItemMetadata(itemId))
            }
            "response.output_text.delta" -> {
                val itemId = obj["item_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                events += StreamEvent.TextDelta(
                    id = itemId,
                    text = obj["delta"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    providerMetadata = huggingFaceItemMetadata(itemId),
                )
            }
            "response.completed",
            "response.incomplete",
            -> {
                val response = obj["response"]?.jsonObject ?: JsonObject(emptyMap())
                responseId = response["id"]?.jsonPrimitive?.contentOrNull ?: responseId
                val reason = response["incomplete_details"]?.jsonObject?.get("reason")?.jsonPrimitive?.contentOrNull ?: "stop"
                finishReason = mapHuggingFaceFinishReason(reason)
                usage = huggingFaceUsage(response["usage"])
            }
            "response.failed" -> {
                val response = obj["response"]?.jsonObject ?: JsonObject(emptyMap())
                responseId = response["id"]?.jsonPrimitive?.contentOrNull ?: responseId
                finishReason = FinishReason.Error
                usage = huggingFaceUsage(response["usage"])
                events += StreamEvent.Error(
                    response["error"]?.let(::huggingFaceErrorMessage) ?: "Hugging Face Responses stream failed",
                )
            }
        }
        return events
    }

    fun finish(): List<StreamEvent> = buildList {
        openReasoningIds.toList().forEach { id -> add(StreamEvent.ReasoningEnd(id, huggingFaceItemMetadata(id))) }
        add(
            StreamEvent.Finish(
                totalSteps = 1,
                finishReason = finishReason,
                usage = usage,
                providerMetadata = responseId?.let {
                    mapOf("huggingface" to buildJsonObject { put("responseId", JsonPrimitive(it)) })
                },
            ),
        )
    }
}

private suspend fun huggingFaceParseResponse(
    response: HttpResponse,
    parseJson: Boolean,
): HuggingFaceHttpResponse {
    val raw = response.bodyAsText()
    val headers = response.headers.entries().associate { it.key to it.value.joinToString(",") }
    if (response.status.value !in 200..299) {
        val error = runCatching { huggingFaceJson.parseToJsonElement(raw) }.getOrNull()
        throw AiSdkException("Hugging Face API error: ${error?.let(::huggingFaceErrorMessage) ?: raw}")
    }
    return HuggingFaceHttpResponse(
        value = if (parseJson && raw.isNotBlank()) huggingFaceJson.parseToJsonElement(raw) else JsonObject(emptyMap()),
        rawText = raw,
        headers = headers,
    )
}

private fun huggingFaceHeaders(settings: HuggingFaceProviderSettings, extra: Map<String, String>): Map<String, String> {
    val headers = linkedMapOf<String, String?>()
    settings.apiKey?.takeIf { it.isNotBlank() }?.let { headers[HttpHeaders.Authorization] = "Bearer $it" }
    headers.putAll(settings.headers)
    headers.putAll(extra)
    return normalizeHeaders(headers)
}

private fun huggingFaceNoEmbeddingModel(providerId: String?, modelId: String): NoSuchModelError =
    NoSuchModelError(
        providerId = providerId,
        modelType = "embeddingModel",
        modelId = modelId,
        message = "Hugging Face Responses API does not support text embeddings. " +
            "Use the Hugging Face Inference API directly for embeddings.",
    )

private fun huggingFaceNoImageModel(providerId: String?, modelId: String): NoSuchModelError =
    NoSuchModelError(
        providerId = providerId,
        modelType = "imageModel",
        modelId = modelId,
        message = "Hugging Face Responses API does not support image generation. " +
            "Use the Hugging Face Inference API directly for image models.",
    )

private fun huggingFaceUsage(element: JsonElement?): Usage {
    val obj = element as? JsonObject ?: return Usage()
    val inputTokens = obj["input_tokens"]?.jsonPrimitive?.intOrNull ?: 0
    val cachedTokens = obj["input_tokens_details"]?.jsonObject?.get("cached_tokens")?.jsonPrimitive?.intOrNull ?: 0
    val outputTokens = obj["output_tokens"]?.jsonPrimitive?.intOrNull ?: 0
    val reasoningTokens = obj["output_tokens_details"]?.jsonObject?.get("reasoning_tokens")?.jsonPrimitive?.intOrNull ?: 0
    return Usage(
        inputTokens = Usage.InputTokenBreakdown(
            total = inputTokens,
            noCache = inputTokens - cachedTokens,
            cacheRead = cachedTokens,
        ),
        outputTokens = Usage.OutputTokenBreakdown(
            total = outputTokens,
            text = outputTokens - reasoningTokens,
            reasoning = reasoningTokens,
        ),
        raw = element,
    )
}

private fun mapHuggingFaceFinishReason(reason: String?): FinishReason = when (reason) {
    "stop" -> FinishReason.Stop
    "length" -> FinishReason.Length
    "content_filter" -> FinishReason.ContentFilter
    "tool_calls" -> FinishReason.ToolCalls
    "error" -> FinishReason.Error
    else -> FinishReason.Other
}

private fun huggingFaceItemMetadata(
    itemId: String,
    providerExecuted: Boolean = false,
): Map<String, JsonElement> = mapOf(
    "huggingface" to buildJsonObject {
        put("itemId", JsonPrimitive(itemId))
        if (providerExecuted) put("providerExecuted", JsonPrimitive(true))
    },
)

private fun huggingFaceParseToolInput(arguments: String?, json: Json): JsonElement =
    if (arguments.isNullOrBlank()) {
        JsonObject(emptyMap())
    } else {
        runCatching { json.parseToJsonElement(arguments) }.getOrElse { JsonPrimitive(arguments) }
    }

private fun huggingFaceErrorMessage(error: JsonElement): String {
    val obj = error as? JsonObject
    val nested = obj?.get("error")
    val nestedObj = nested as? JsonObject
    return nestedObj?.get("message")?.jsonPrimitive?.contentOrNull
        ?: obj?.get("message")?.jsonPrimitive?.contentOrNull
        ?: nested?.jsonPrimitive?.contentOrNull
        ?: error.toString()
}
