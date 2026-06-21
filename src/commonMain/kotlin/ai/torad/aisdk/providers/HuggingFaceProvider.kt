package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import ai.torad.aisdk.ProviderMetadata
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
import kotlinx.coroutines.flow.emitAll
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

public const val HUGGINGFACE_VERSION: String = "1.0.50"

public typealias HuggingFaceErrorData = JsonObject

public data class HuggingFaceProviderSettings(
    val apiKey: String? = null,
    val baseURL: String = "https://router.huggingface.co/v1",
    val headers: Map<String, String> = emptyMap(),
    val generateId: () -> String = { IdGenerator.generate() },
)

@Serializable
public data class HuggingFaceResponsesSettings(
    val metadata: Map<String, String>? = null,
    val instructions: String? = null,
    val strictJsonSchema: Boolean? = null,
    val reasoningEffort: String? = null,
)

public class HuggingFaceProvider(
    private val client: HttpClient,
    public val settings: HuggingFaceProviderSettings,
) : Provider {
    override val providerId: String = "huggingface"

    public operator fun invoke(modelId: ModelId): LanguageModel = languageModel(modelId.value)

    public fun responses(modelId: ModelId): LanguageModel = languageModel(modelId.value)

    public fun textEmbeddingModel(modelId: String): Nothing = throw HuggingFaceWire.huggingFaceNoEmbeddingModel(providerId, modelId)

    override fun languageModel(modelId: String): LanguageModel =
        HuggingFaceResponsesLanguageModel(client, settings, modelId)

    override fun embeddingModel(modelId: String): EmbeddingModel = throw HuggingFaceWire.huggingFaceNoEmbeddingModel(providerId, modelId)

    override fun imageModel(modelId: String): ImageModel = throw HuggingFaceWire.huggingFaceNoImageModel(providerId, modelId)
}

/** PascalCase factory — mirrors `OpenAI(...)`. */
public fun HuggingFace(
    client: HttpClient,
    settings: HuggingFaceProviderSettings = HuggingFaceProviderSettings(),
): HuggingFaceProvider = HuggingFaceProvider(client, settings)

private class HuggingFaceResponsesLanguageModel(
    private val client: HttpClient,
    private val settings: HuggingFaceProviderSettings,
    override val modelId: String,
) : LanguageModel {
    override val provider: String = "huggingface.responses"
    override val supportedUrls: Map<String, List<String>> = mapOf("image/*" to listOf("^https?://.*$"))

    override suspend fun generate(params: LanguageModelCallParams): LanguageModelResult {
        val prepared = HuggingFaceWire.huggingFaceResponsesRequestBody(modelId, params, stream = false)
        val response = postJson(prepared.body, headers = params.headers)
        return responsesResult(
            response = response.value.jsonObject,
            requestBody = prepared.body,
            responseHeaders = response.headers,
            responseBody = response.value,
            warnings = prepared.warnings,
        )
    }

    override fun stream(params: LanguageModelCallParams): Flow<StreamEvent> = flow {
        val prepared = HuggingFaceWire.huggingFaceResponsesRequestBody(modelId, params, stream = true)
        emit(StreamEvent.StreamStart(prepared.warnings))
        val state = HuggingFaceResponsesStreamState(settings, aiSdkJson)
        var sseHeaders: Map<String, String> = emptyMap()
        val rawLines = streamResponsesSse(prepared.body, params.headers) { sseHeaders = it }
        with(HttpTransport) {
            forwardSseEvents(
                events = EventStreamParser.parse(rawLines, Schemas.jsonSchema<JsonElement>(JsonObject(emptyMap())), aiSdkJson),
                capturedHeaders = { sseHeaders },
                parseErrorPrefix = "Failed to parse Hugging Face Responses stream event",
                onEvent = { state.accept(it).forEach { e -> emit(e) } },
            )
        }
        state.finish().forEach { emit(it) }
    }

    override fun streamResult(params: LanguageModelCallParams): LanguageModelStreamResult {
        val prepared = HuggingFaceWire.huggingFaceResponsesRequestBody(modelId, params, stream = true)
        return LanguageModelStreamResult(
            stream = stream(params),
            request = LanguageModelRequestMetadata(body = prepared.body),
        )
    }

    private suspend fun postJson(
        body: JsonElement,
        headers: Map<String, String>,
    ): HuggingFaceHttpResponse {
        val response = client.request("${settings.baseURL.trimEnd('/')}/responses") {
            method = HttpMethod.Post
            contentType(ContentType.Application.Json)
            HuggingFaceWire.huggingFaceHeaders(settings, headers).forEach { (name, value) -> header(name, value) }
            setBody(aiSdkJson.encodeToString(JsonElement.serializer(), body))
        }
        return parseResponse(response, parseJson = true)
    }

    private suspend fun parseResponse(
        response: HttpResponse,
        parseJson: Boolean,
    ): HuggingFaceHttpResponse {
        val raw = response.bodyAsText()
        val headers = response.headers.entries().associate { it.key to it.value.joinToString(",") }
        if (response.status.value !in 200..299) {
            val parsed = runCatching { aiSdkJson.parseToJsonElement(raw) }.getOrNull()
            throw ApiCallError(
                url = response.call.request.url.toString(),
                statusCode = response.status.value,
                rawBody = raw,
                headers = headers,
                message = "Hugging Face API error: ${parsed?.let(HuggingFaceWire::huggingFaceErrorMessage) ?: raw}",
                requestBodyValues = parsed,
            )
        }
        return HuggingFaceHttpResponse(
            value = if (parseJson && raw.isNotBlank()) aiSdkJson.parseToJsonElement(raw) else JsonObject(emptyMap()),
            rawText = raw,
            headers = headers,
        )
    }

    private fun responsesResult(
        response: JsonObject,
        requestBody: JsonElement,
        responseHeaders: Map<String, String>,
        responseBody: JsonElement,
        warnings: List<CallWarning>,
    ): LanguageModelResult {
        response["error"]?.takeIf { it !is JsonNull }?.let { error ->
            throw ApiCallError(
                url = "${settings.baseURL.trimEnd('/')}/responses",
                statusCode = 200,
                rawBody = responseBody.toString(),
                headers = responseHeaders,
                message = "Hugging Face API error: ${HuggingFaceWire.huggingFaceErrorMessage(error)}",
                requestBodyValues = requestBody,
            )
        }

        val content = mutableListOf<ContentPart>()
        val toolCalls = mutableListOf<ContentPart.ToolCall>()

        for (part in (response["output"] as? JsonArray).orEmpty()) {
            val obj = part.jsonObject
            val itemId = obj["id"]?.jsonPrimitive?.contentOrNull
            val providerMetadata = itemId?.let(HuggingFaceWire::huggingFaceItemMetadata) ?: ProviderMetadata.None
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
                        input = HuggingFaceWire.huggingFaceParseToolInput(obj["arguments"]?.jsonPrimitive?.contentOrNull, aiSdkJson),
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
                    val mcpMetadata = HuggingFaceWire.huggingFaceItemMetadata(callId, providerExecuted = true)
                    val toolCall = ContentPart.ToolCall(
                        toolCallId = callId,
                        toolName = toolName,
                        input = HuggingFaceWire.huggingFaceParseToolInput(obj["arguments"]?.jsonPrimitive?.contentOrNull, aiSdkJson),
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
                    val mcpMetadata = HuggingFaceWire.huggingFaceItemMetadata(callId, providerExecuted = true)
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
            finishReason = HuggingFaceWire.mapHuggingFaceFinishReason(incompleteReason ?: "stop"),
            usage = HuggingFaceWire.huggingFaceUsage(response["usage"]),
            providerMetadata = responseId?.let { ProviderMetadata.Raw(JsonObject(mapOf("huggingface" to buildJsonObject { put("responseId", JsonPrimitive(it)) }))) } ?: ProviderMetadata.None,
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

    /** Streaming counterpart of [postJson]: reads the SSE body incrementally,
     *  surfacing non-2xx as the same rich [APICallError] as [parseResponse]. */
    private fun streamResponsesSse(
        body: JsonElement,
        headers: Map<String, String>,
        onResponse: suspend (Map<String, String>) -> Unit,
    ): Flow<String> = flow {
        emitAll(
            HttpTransport.streamSse(client = client,
            url = "${settings.baseURL.trimEnd('/')}/responses",
            method = HttpMethod.Post,
            headers = HuggingFaceWire.huggingFaceHeaders(settings, headers) + (HttpHeaders.Accept to "text/event-stream"),
            body = body,
            json = aiSdkJson,
            requestBodyValues = body,
            errorMessage = { _, parsed, raw ->
                "Hugging Face API error: ${parsed?.let(HuggingFaceWire::huggingFaceErrorMessage) ?: raw}"
            },
            onResponse = onResponse,),
        )
    }
}

private data class HuggingFaceHttpResponse(
    val value: JsonElement,
    val rawText: String,
    val headers: Map<String, String>,
)

internal data class HuggingFacePreparedRequest(
    val body: JsonObject,
    val warnings: List<CallWarning>,
)

internal data class HuggingFaceConvertedMessages(
    val input: JsonArray,
    val warnings: List<CallWarning>,
)

internal data class HuggingFacePreparedTools(
    val tools: List<JsonObject>,
    val toolChoice: JsonElement?,
    val warnings: List<CallWarning>,
)

private class HuggingFaceResponsesStreamState(
    private val settings: HuggingFaceProviderSettings,
    private val json: Json,
) {
    private var finishReason = FinishReason.Other
    private var rawFinishReason: String? = null
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
                val item = obj["item"] as? JsonObject
                    ?: return listOf(StreamEvent.Error("Hugging Face stream protocol error: response.output_item.added missing item."))
                val itemId = item["id"]?.jsonPrimitive?.contentOrNull ?: obj["item_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val itemType = item["type"]?.jsonPrimitive?.contentOrNull
                when (itemType) {
                    "message" -> if (item["role"]?.jsonPrimitive?.contentOrNull == null || item["role"]?.jsonPrimitive?.contentOrNull == "assistant") {
                        events += StreamEvent.TextStart(itemId, HuggingFaceWire.huggingFaceItemMetadata(itemId))
                    }
                    "reasoning" -> {
                        openReasoningIds += itemId
                        events += StreamEvent.ReasoningStart(itemId, HuggingFaceWire.huggingFaceItemMetadata(itemId))
                    }
                    "function_call" -> events += StreamEvent.ToolInputStart(
                        id = item["call_id"]?.jsonPrimitive?.contentOrNull ?: itemId,
                        toolName = item["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    )
                    "mcp_call" -> events += StreamEvent.ToolInputStart(
                        id = itemId,
                        toolName = item["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        providerMetadata = HuggingFaceWire.huggingFaceItemMetadata(itemId, providerExecuted = true),
                    )
                }
            }
            "response.output_item.done" -> {
                val item = obj["item"] as? JsonObject
                    ?: return listOf(StreamEvent.Error("Hugging Face stream protocol error: response.output_item.done missing item."))
                val itemId = item["id"]?.jsonPrimitive?.contentOrNull ?: obj["item_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                when (item["type"]?.jsonPrimitive?.contentOrNull) {
                    "message" -> events += StreamEvent.TextEnd(itemId, HuggingFaceWire.huggingFaceItemMetadata(itemId))
                    "reasoning" -> if (openReasoningIds.remove(itemId)) {
                        events += StreamEvent.ReasoningEnd(itemId, HuggingFaceWire.huggingFaceItemMetadata(itemId))
                    }
                    "function_call" -> {
                        val callId = item["call_id"]?.jsonPrimitive?.contentOrNull ?: settings.generateId()
                        val toolName = item["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        events += StreamEvent.ToolInputEnd(callId)
                        events += StreamEvent.ToolCall(
                            toolCallId = callId,
                            toolName = toolName,
                            inputJson = HuggingFaceWire.huggingFaceParseToolInput(item["arguments"]?.jsonPrimitive?.contentOrNull, json),
                            providerMetadata = HuggingFaceWire.huggingFaceItemMetadata(itemId),
                        )
                        item["output"]?.jsonPrimitive?.contentOrNull?.let { output ->
                            events += StreamEvent.ToolResult(callId, toolName, JsonPrimitive(output), providerMetadata = HuggingFaceWire.huggingFaceItemMetadata(itemId))
                        }
                    }
                    "mcp_call" -> {
                        val toolName = item["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        val metadata = HuggingFaceWire.huggingFaceItemMetadata(itemId, providerExecuted = true)
                        events += StreamEvent.ToolInputEnd(itemId, metadata)
                        events += StreamEvent.ToolCall(
                            toolCallId = itemId,
                            toolName = toolName,
                            inputJson = HuggingFaceWire.huggingFaceParseToolInput(item["arguments"]?.jsonPrimitive?.contentOrNull, json),
                            providerMetadata = metadata,
                        )
                        item["output"]?.jsonPrimitive?.contentOrNull?.let { output ->
                            events += StreamEvent.ToolResult(itemId, toolName, JsonPrimitive(output), providerMetadata = metadata)
                        }
                    }
                    "mcp_list_tools" -> {
                        val metadata = HuggingFaceWire.huggingFaceItemMetadata(itemId, providerExecuted = true)
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
            "response.reasoning_summary_text.delta", "response.reasoning_text.delta" -> {
                val itemId = obj["item_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                events += StreamEvent.ReasoningDelta(
                    id = itemId,
                    text = obj["delta"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    providerMetadata = HuggingFaceWire.huggingFaceItemMetadata(itemId),
                )
            }
            "response.reasoning_summary_text.done", "response.reasoning_text.done" -> {
                val itemId = obj["item_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                openReasoningIds.remove(itemId)
                events += StreamEvent.ReasoningEnd(itemId, HuggingFaceWire.huggingFaceItemMetadata(itemId))
            }
            "response.output_text.delta" -> {
                val itemId = obj["item_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                events += StreamEvent.TextDelta(
                    id = itemId,
                    text = obj["delta"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    providerMetadata = HuggingFaceWire.huggingFaceItemMetadata(itemId),
                )
            }
            "response.completed",
            "response.incomplete",
            -> {
                val response = obj["response"]?.jsonObject ?: JsonObject(emptyMap())
                responseId = response["id"]?.jsonPrimitive?.contentOrNull ?: responseId
                val reason = response["incomplete_details"]?.jsonObject?.get("reason")?.jsonPrimitive?.contentOrNull ?: "stop"
                rawFinishReason = reason
                finishReason = HuggingFaceWire.mapHuggingFaceFinishReason(reason)
                usage = HuggingFaceWire.huggingFaceUsage(response["usage"])
            }
            "response.failed" -> {
                val response = obj["response"]?.jsonObject ?: JsonObject(emptyMap())
                responseId = response["id"]?.jsonPrimitive?.contentOrNull ?: responseId
                finishReason = FinishReason.Error
                usage = HuggingFaceWire.huggingFaceUsage(response["usage"])
                events += StreamEvent.Error(
                    response["error"]?.let(HuggingFaceWire::huggingFaceErrorMessage) ?: "Hugging Face Responses stream failed",
                )
            }
        }
        return events
    }

    fun finish(): List<StreamEvent> = buildList {
        openReasoningIds.toList().forEach { id -> add(StreamEvent.ReasoningEnd(id, HuggingFaceWire.huggingFaceItemMetadata(id))) }
        add(
            StreamEvent.Finish(
                totalSteps = 1,
                finishReason = finishReason,
                usage = usage,
                providerMetadata = responseId?.let {
                    ProviderMetadata.Raw(JsonObject(mapOf("huggingface" to buildJsonObject { put("responseId", JsonPrimitive(it)) })))
                } ?: ProviderMetadata.None,
                rawFinishReason = rawFinishReason,
            ),
        )
    }
}

/**
 * Wire-format helpers for the Hugging Face Responses API. These are file-local
 * conversion/parse routines grouped as object members so none remain loose
 * top-level functions.
 */
internal object HuggingFaceWire {
    fun huggingFaceResponsesRequestBody(
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

    fun huggingFaceMessages(messages: List<ModelMessage>): HuggingFaceConvertedMessages {
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
                        is ContentPart.ToolCall,
                        is ContentPart.ToolResult,
                        is ContentPart.ToolApprovalRequest,
                        is ContentPart.ToolApprovalResponse,
                        is ContentPart.Source,
                        is ContentPart.File,
                        is ContentPart.Image,
                        -> Unit
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
                is ContentPart.ToolCall,
                is ContentPart.ToolResult,
                is ContentPart.ToolApprovalRequest,
                is ContentPart.ToolApprovalResponse,
                is ContentPart.Source,
                is ContentPart.File,
                is ContentPart.Image,
                -> ""
            }
        }

    fun huggingFaceAssistantMessage(text: String): JsonObject = buildJsonObject {
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

    fun huggingFaceUserContentPart(part: ContentPart): JsonElement? = when (part) {
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
                throw UnsupportedFunctionalityError("file part media type ${part.mediaType}", "Hugging Face Responses API does not support file part media type ${part.mediaType}.")
            }
            buildJsonObject {
                put("type", JsonPrimitive("input_image"))
                put("image_url", JsonPrimitive("data:${huggingFaceImageMediaType(part.mediaType)};base64,${part.base64}"))
            }
        }
        is ContentPart.Reasoning,
        is ContentPart.ToolCall,
        is ContentPart.ToolResult,
        is ContentPart.ToolApprovalRequest,
        is ContentPart.ToolApprovalResponse,
        is ContentPart.Source,
        -> null
    }

    fun huggingFaceImageMediaType(mediaType: String): String =
        if (mediaType == "image/*") "image/jpeg" else mediaType

    fun huggingFaceTools(
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
                    put("parameters", aiSdkJson.parseToJsonElement(tool.parametersSchemaJson))
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

    fun huggingFaceTextFormat(
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

    fun huggingFaceProviderOptions(providerOptions: ProviderOptions): HuggingFaceResponsesSettings? {
        val element = providerOptions.toMap()["huggingface"] ?: providerOptions.toMap()["hugging-face"] ?: return null
        return runCatching { aiSdkJson.decodeFromJsonElement(HuggingFaceResponsesSettings.serializer(), element) }
            .getOrNull()
    }

    fun huggingFaceHeaders(settings: HuggingFaceProviderSettings, extra: Map<String, String>): Map<String, String> {
        val headers = linkedMapOf<String, String?>()
        settings.apiKey?.takeIf { it.isNotBlank() }?.let { headers[HttpHeaders.Authorization] = "Bearer $it" }
        headers.putAll(settings.headers)
        headers.putAll(extra)
        return ProviderHeaders.normalize(headers)
    }

    fun huggingFaceNoEmbeddingModel(providerId: String?, modelId: String): NoSuchModelError =
        NoSuchModelError(
            providerId = providerId,
            modelType = "embeddingModel",
            modelId = modelId,
            message = "Hugging Face Responses API does not support text embeddings. " +
                "Use the Hugging Face Inference API directly for embeddings.",
        )

    fun huggingFaceNoImageModel(providerId: String?, modelId: String): NoSuchModelError =
        NoSuchModelError(
            providerId = providerId,
            modelType = "imageModel",
            modelId = modelId,
            message = "Hugging Face Responses API does not support image generation. " +
                "Use the Hugging Face Inference API directly for image models.",
        )

    fun huggingFaceUsage(element: JsonElement?): Usage {
        val obj = element as? JsonObject ?: return Usage()
        val inputTokens = obj["input_tokens"]?.jsonPrimitive?.intOrNull ?: 0
        val cachedTokens = (obj["input_tokens_details"]?.jsonObject?.get("cached_tokens")?.jsonPrimitive?.intOrNull ?: 0)
            .coerceIn(0, inputTokens)
        val outputTokens = obj["output_tokens"]?.jsonPrimitive?.intOrNull ?: 0
        val reasoningTokens = (obj["output_tokens_details"]?.jsonObject?.get("reasoning_tokens")?.jsonPrimitive?.intOrNull ?: 0)
            .coerceAtLeast(0)
        val outputTotal = if (reasoningTokens > outputTokens) outputTokens + reasoningTokens else outputTokens
        return Usage(
            inputTokens = Usage.InputTokenBreakdown(
                total = inputTokens,
                noCache = (inputTokens - cachedTokens).coerceAtLeast(0),
                cacheRead = cachedTokens,
            ),
            outputTokens = Usage.OutputTokenBreakdown(
                total = outputTotal,
                text = outputTotal - reasoningTokens,
                reasoning = reasoningTokens,
            ),
            raw = element,
        )
    }

    fun mapHuggingFaceFinishReason(reason: String?): FinishReason = when (reason) {
        "stop" -> FinishReason.Stop
        "length" -> FinishReason.Length
        "content_filter" -> FinishReason.ContentFilter
        "tool_calls" -> FinishReason.ToolCalls
        "error" -> FinishReason.Error
        else -> FinishReason.Other
    }

    fun huggingFaceItemMetadata(
        itemId: String,
        providerExecuted: Boolean = false,
    ): ProviderMetadata = ProviderMetadata.Raw(JsonObject(mapOf(
        "huggingface" to buildJsonObject {
            put("itemId", JsonPrimitive(itemId))
            if (providerExecuted) put("providerExecuted", JsonPrimitive(true))
        },
    )))

    fun huggingFaceParseToolInput(arguments: String?, json: Json): JsonElement =
        if (arguments.isNullOrBlank()) {
            JsonObject(emptyMap())
        } else {
            runCatching { json.parseToJsonElement(arguments) }.getOrElse { JsonPrimitive(arguments) }
        }

    fun huggingFaceErrorMessage(error: JsonElement): String {
        val obj = error as? JsonObject
        val nested = obj?.get("error")
        val nestedObj = nested as? JsonObject
        return nestedObj?.get("message")?.jsonPrimitive?.contentOrNull
            ?: obj?.get("message")?.jsonPrimitive?.contentOrNull
            ?: nested?.jsonPrimitive?.contentOrNull
            ?: error.toString()
    }
}
