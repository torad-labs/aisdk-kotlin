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
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

const val OPEN_RESPONSES_VERSION: String = "1.0.16"

data class OpenResponsesProviderSettings(
    val url: String,
    val name: String,
    val apiKey: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val authHeadersProvider: (suspend () -> Map<String, String>)? = null,
    val userAgentSuffix: String? = "ai-sdk/open-responses/$OPEN_RESPONSES_VERSION",
    val providerOptionsName: String? = null,
)

@Serializable
data class OpenResponsesOptions(
    val reasoningEffort: String? = null,
    val reasoningSummary: String? = null,
)

interface OpenResponsesProvider : Provider {
    operator fun invoke(modelId: String): LanguageModel = languageModel(modelId)
    fun responses(modelId: String): LanguageModel = languageModel(modelId)
}

fun createOpenResponses(
    client: HttpClient,
    settings: OpenResponsesProviderSettings,
    json: Json = openResponsesJson,
): OpenResponsesProvider = KtorOpenResponsesProvider(client, settings, json)

private class KtorOpenResponsesProvider(
    private val client: HttpClient,
    private val settings: OpenResponsesProviderSettings,
    private val json: Json,
) : OpenResponsesProvider {
    override val providerId: String = settings.name

    override fun languageModel(modelId: String): LanguageModel =
        OpenResponsesLanguageModel(client, settings, json, modelId)
}

private class OpenResponsesLanguageModel(
    private val client: HttpClient,
    private val settings: OpenResponsesProviderSettings,
    private val json: Json,
    override val modelId: String,
) : LanguageModel {
    override val provider: String = "${settings.name}.responses"
    override val supportedUrls: Map<String, List<String>> = mapOf("image/*" to listOf("^https?://.*$"))

    override suspend fun generate(params: LanguageModelCallParams): LanguageModelResult {
        val prepared = openResponsesRequestBody(
            params,
            stream = false,
            providerOptionsName = settings.providerOptionsName ?: settings.name,
            modelId,
            json,
        )
        val response = postJson(prepared.body, acceptEventStream = false, headers = params.headers)
        return openResponsesGenerateResult(
            response = response.value.jsonObject,
            requestBody = prepared.body,
            responseHeaders = response.headers,
            responseBody = response.value,
            warnings = prepared.warnings,
            json = json,
        )
    }

    override fun stream(params: LanguageModelCallParams): Flow<StreamEvent> = flow {
        val prepared = openResponsesRequestBody(
            params,
            stream = true,
            providerOptionsName = settings.providerOptionsName ?: settings.name,
            modelId,
            json,
        )
        val response = postJson(prepared.body, acceptEventStream = true, parseJson = false, headers = params.headers)
        emit(StreamEvent.StreamStart(prepared.warnings))
        emit(StreamEvent.ResponseMetadata(headers = response.headers, body = JsonPrimitive(response.rawText)))

        val state = OpenResponsesStreamState(json)
        for (event in parseJsonEventStream(response.rawText, jsonSchema<JsonElement>(JsonObject(emptyMap())), json)) {
            when (event) {
                is ParseResult.Success -> state.accept(event.value).forEach { emit(it) }
                is ParseResult.Failure -> emit(
                    StreamEvent.Error("Failed to parse Open Responses stream event: ${event.error.message}"),
                )
            }
        }
        state.finish().forEach { emit(it) }
    }

    override fun streamResult(params: LanguageModelCallParams): LanguageModelStreamResult {
        val prepared = openResponsesRequestBody(
            params,
            stream = true,
            providerOptionsName = settings.providerOptionsName ?: settings.name,
            modelId,
            json,
        )
        return LanguageModelStreamResult(
            stream = stream(params),
            request = LanguageModelRequestMetadata(body = prepared.body),
        )
    }

    private suspend fun postJson(
        body: JsonElement,
        acceptEventStream: Boolean,
        parseJson: Boolean = true,
        headers: Map<String, String> = emptyMap(),
    ): OpenResponsesHttpResponse {
        val response = client.request(settings.url) {
            method = HttpMethod.Post
            contentType(ContentType.Application.Json)
            if (acceptEventStream) header(HttpHeaders.Accept, "text/event-stream")
            requestHeaders(headers).forEach { (name, value) -> header(name, value) }
            setBody(json.encodeToString(JsonElement.serializer(), body))
        }
        return parseResponse(response, parseJson)
    }

    private suspend fun requestHeaders(extra: Map<String, String>): Map<String, String> {
        val base = linkedMapOf<String, String>()
        val dynamicAuthHeaders = settings.authHeadersProvider?.invoke()
        if (dynamicAuthHeaders != null) {
            base.putAll(dynamicAuthHeaders)
        } else {
            settings.apiKey?.takeIf { it.isNotBlank() }?.let { base[HttpHeaders.Authorization] = "Bearer $it" }
        }
        base.putAll(settings.headers)
        base.putAll(extra)
        return settings.userAgentSuffix
            ?.let { withUserAgentSuffix(base, it) }
            ?: normalizeHeaders(base)
    }

    private suspend fun parseResponse(
        response: HttpResponse,
        parseJson: Boolean,
    ): OpenResponsesHttpResponse {
        val raw = response.bodyAsText()
        val headers = response.headers.entries().associate { it.key to it.value.joinToString(",") }
        if (response.status.value !in 200..299) {
            throw openResponsesErrorFromResponse(raw)
        }
        return OpenResponsesHttpResponse(
            value = if (parseJson && raw.isNotBlank()) json.parseToJsonElement(raw) else JsonObject(emptyMap()),
            rawText = raw,
            headers = headers,
        )
    }
}

private data class OpenResponsesHttpResponse(
    val value: JsonElement,
    val rawText: String,
    val headers: Map<String, String>,
)

private data class PreparedOpenResponsesRequest(
    val body: JsonObject,
    val warnings: List<CallWarning>,
)

private fun openResponsesRequestBody(
    params: LanguageModelCallParams,
    stream: Boolean,
    providerOptionsName: String,
    modelId: String,
    json: Json,
): PreparedOpenResponsesRequest {
    val warnings = mutableListOf<CallWarning>()
    if (params.stopSequences.isNotEmpty()) {
        warnings += CallWarning("unsupported", "stopSequences are not supported by Open Responses models")
    }
    params.topK?.let {
        warnings += CallWarning("unsupported", "topK is not supported by Open Responses models")
    }
    params.seed?.let {
        warnings += CallWarning("unsupported", "seed is not supported by Open Responses models")
    }

    val convertedInput = convertToOpenResponsesInput(params.messages, warnings)
    val providerOptions = openResponsesProviderOptions(params.providerOptions, providerOptionsName, json)

    return PreparedOpenResponsesRequest(
        body = buildJsonObject {
            put("model", JsonPrimitive(modelId))
            put("input", convertedInput.input)
            convertedInput.instructions?.let { put("instructions", JsonPrimitive(it)) }
            params.maxOutputTokens?.let { put("max_output_tokens", JsonPrimitive(it)) }
            params.temperature?.let { put("temperature", JsonPrimitive(it)) }
            params.topP?.let { put("top_p", JsonPrimitive(it)) }
            params.presencePenalty?.let { put("presence_penalty", JsonPrimitive(it)) }
            params.frequencyPenalty?.let { put("frequency_penalty", JsonPrimitive(it)) }
            if (stream) put("stream", JsonPrimitive(true))

            val reasoning = openResponsesReasoning(providerOptions)
            if (reasoning.isNotEmpty()) put("reasoning", JsonObject(reasoning))

            if (params.tools.isNotEmpty()) put("tools", JsonArray(params.tools.map(::openResponsesToolJson)))
            openResponsesToolChoice(params.toolChoice)?.let { put("tool_choice", it) }
            openResponsesTextFormat(params.responseFormat)?.let { put("text", buildJsonObject { put("format", it) }) }
        },
        warnings = warnings,
    )
}

private data class ConvertedOpenResponsesInput(
    val input: JsonArray,
    val instructions: String?,
)

private fun convertToOpenResponsesInput(
    messages: List<ModelMessage>,
    warnings: MutableList<CallWarning>,
): ConvertedOpenResponsesInput {
    val input = mutableListOf<JsonElement>()
    val systemMessages = mutableListOf<String>()

    for (message in messages) {
        when (message.role) {
            MessageRole.System -> systemMessages += message.content.textContent()
            MessageRole.User -> input += buildJsonObject {
                put("type", JsonPrimitive("message"))
                put("role", JsonPrimitive("user"))
                put("content", JsonArray(message.content.mapNotNull(::openResponsesUserContentPart)))
            }
            MessageRole.Assistant -> {
                val assistantContent = message.content.mapNotNull(::openResponsesAssistantContentPart)
                if (assistantContent.isNotEmpty()) {
                    input += buildJsonObject {
                        put("type", JsonPrimitive("message"))
                        put("role", JsonPrimitive("assistant"))
                        put("content", JsonArray(assistantContent))
                    }
                }
                message.content.filterIsInstance<ContentPart.ToolCall>().forEach { toolCall ->
                    input += buildJsonObject {
                        put("type", JsonPrimitive("function_call"))
                        put("call_id", JsonPrimitive(toolCall.toolCallId))
                        put("name", JsonPrimitive(toolCall.toolName))
                        put("arguments", JsonPrimitive(toolCall.input.toString()))
                    }
                }
            }
            MessageRole.Tool -> message.content.filterIsInstance<ContentPart.ToolResult>().forEach { toolResult ->
                input += buildJsonObject {
                    put("type", JsonPrimitive("function_call_output"))
                    put("call_id", JsonPrimitive(toolResult.toolCallId))
                    put("output", openResponsesToolOutput(toolResultOutputFromWire(toolResult.modelVisible), warnings))
                }
            }
        }
    }

    return ConvertedOpenResponsesInput(
        input = JsonArray(input),
        instructions = systemMessages.takeIf { it.isNotEmpty() }?.joinToString("\n"),
    )
}

private fun List<ContentPart>.textContent(): String =
    joinToString("") { part ->
        when (part) {
            is ContentPart.Text -> part.text
            is ContentPart.Reasoning -> part.text
            else -> ""
        }
    }

private fun openResponsesUserContentPart(part: ContentPart): JsonElement? = when (part) {
    is ContentPart.Text -> buildJsonObject {
        put("type", JsonPrimitive("input_text"))
        put("text", JsonPrimitive(part.text))
    }
    is ContentPart.Image -> buildJsonObject {
        put("type", JsonPrimitive("input_image"))
        put("image_url", JsonPrimitive("data:${part.mediaType};base64,${part.base64}"))
    }
    is ContentPart.File -> if (part.mediaType.startsWith("image/")) {
        buildJsonObject {
            put("type", JsonPrimitive("input_image"))
            put("image_url", JsonPrimitive("data:${part.mediaType};base64,${part.base64}"))
        }
    } else {
        buildJsonObject {
            put("type", JsonPrimitive("input_file"))
            put("filename", JsonPrimitive(part.filename ?: "data"))
            put("file_data", JsonPrimitive("data:${part.mediaType};base64,${part.base64}"))
        }
    }
    else -> null
}

private fun openResponsesAssistantContentPart(part: ContentPart): JsonElement? = when (part) {
    is ContentPart.Text -> buildJsonObject {
        put("type", JsonPrimitive("output_text"))
        put("text", JsonPrimitive(part.text))
    }
    else -> null
}

private fun openResponsesToolOutput(
    output: ToolResultOutput,
    warnings: MutableList<CallWarning>,
): JsonElement = when (output) {
    is ToolResultOutput.Text -> JsonPrimitive(output.text)
    is ToolResultOutput.Error -> JsonPrimitive(output.message)
    is ToolResultOutput.ExecutionDenied -> JsonPrimitive(output.reason ?: "Tool execution denied.")
    is ToolResultOutput.Json -> JsonPrimitive(output.json.toString())
    is ToolResultOutput.ErrorJson -> JsonPrimitive(output.json.toString())
    is ToolResultOutput.Content -> JsonArray(output.value.mapNotNull { item ->
        val obj = item as? JsonObject
        when (obj?.get("type")?.jsonPrimitive?.contentOrNull) {
            "text" -> buildJsonObject {
                put("type", JsonPrimitive("input_text"))
                put("text", obj["text"] ?: JsonPrimitive(""))
            }
            "image-data" -> buildJsonObject {
                put("type", JsonPrimitive("input_image"))
                val mediaType = obj["mediaType"]?.jsonPrimitive?.contentOrNull ?: "application/octet-stream"
                val data = obj["data"]?.jsonPrimitive?.contentOrNull.orEmpty()
                put("image_url", JsonPrimitive("data:$mediaType;base64,$data"))
            }
            "image-url" -> buildJsonObject {
                put("type", JsonPrimitive("input_image"))
                put("image_url", obj["url"] ?: JsonPrimitive(""))
            }
            "file-data" -> buildJsonObject {
                put("type", JsonPrimitive("input_file"))
                put("filename", obj["filename"] ?: JsonPrimitive("data"))
                val mediaType = obj["mediaType"]?.jsonPrimitive?.contentOrNull ?: "application/octet-stream"
                val data = obj["data"]?.jsonPrimitive?.contentOrNull.orEmpty()
                put("file_data", JsonPrimitive("data:$mediaType;base64,$data"))
            }
            else -> {
                warnings += CallWarning("other", "unsupported tool content part type: ${obj?.get("type")}")
                null
            }
        }
    })
}

private fun openResponsesToolJson(tool: LanguageModelTool): JsonObject = buildJsonObject {
    put("type", JsonPrimitive("function"))
    put("name", JsonPrimitive(tool.name))
    put("description", JsonPrimitive(tool.description))
    put("parameters", openResponsesJson.parseToJsonElement(tool.parametersSchemaJson))
    put("strict", JsonPrimitive(true))
}

private fun openResponsesToolChoice(choice: ToolChoice): JsonElement? = when (choice) {
    ToolChoice.Auto -> JsonPrimitive("auto")
    ToolChoice.None -> JsonPrimitive("none")
    ToolChoice.Required -> JsonPrimitive("required")
    is ToolChoice.Specific -> buildJsonObject {
        put("type", JsonPrimitive("function"))
        put("name", JsonPrimitive(choice.toolName))
    }
}

private fun openResponsesTextFormat(format: ResponseFormat): JsonElement? = when (format) {
    ResponseFormat.Text -> null
    is ResponseFormat.Json -> buildJsonObject {
        put("type", JsonPrimitive("json_schema"))
        format.schemaName?.let { put("name", JsonPrimitive(it)) } ?: put("name", JsonPrimitive("response"))
        format.schemaDescription?.let { put("description", JsonPrimitive(it)) }
        format.schemaJson?.let { put("schema", it) }
        put("strict", JsonPrimitive(true))
    }
}

private fun openResponsesProviderOptions(
    providerOptions: Map<String, JsonElement>,
    providerOptionsName: String,
    json: Json,
): OpenResponsesOptions? {
    val element = providerOptions[providerOptionsName] ?: providerOptions["open-responses"] ?: return null
    return runCatching { json.decodeFromJsonElement(OpenResponsesOptions.serializer(), element) }.getOrNull()
}

private fun openResponsesReasoning(options: OpenResponsesOptions?): Map<String, JsonElement> = buildMap {
    options?.reasoningEffort?.let { put("effort", JsonPrimitive(it)) }
    options?.reasoningSummary?.let { put("summary", JsonPrimitive(it)) }
}

private fun openResponsesGenerateResult(
    response: JsonObject,
    requestBody: JsonElement,
    responseHeaders: Map<String, String>,
    responseBody: JsonElement,
    warnings: List<CallWarning>,
    json: Json,
): LanguageModelResult {
    val content = mutableListOf<ContentPart>()
    val toolCalls = mutableListOf<ContentPart.ToolCall>()
    var hasToolCalls = false

    for (part in response["output"]?.jsonArray.orEmpty()) {
        val obj = part.jsonObject
        when (obj["type"]?.jsonPrimitive?.contentOrNull) {
            "reasoning" -> {
                val reasoningParts = (obj["content"] as? JsonArray) ?: (obj["summary"] as? JsonArray) ?: JsonArray(emptyList())
                reasoningParts.forEach { reasoning ->
                    val text = reasoning.jsonObject["text"]?.jsonPrimitive?.contentOrNull
                    if (!text.isNullOrEmpty()) content += ContentPart.Reasoning(text)
                }
            }
            "message" -> {
                obj["content"]?.jsonArray.orEmpty().forEach { messagePart ->
                    val messageObj = messagePart.jsonObject
                    val text = messageObj["text"]?.jsonPrimitive?.contentOrNull
                        ?: messageObj["refusal"]?.jsonPrimitive?.contentOrNull
                    if (!text.isNullOrEmpty()) content += ContentPart.Text(text)
                }
            }
            "function_call" -> {
                hasToolCalls = true
                val toolCall = ContentPart.ToolCall(
                    toolCallId = obj["call_id"]?.jsonPrimitive?.contentOrNull ?: generateId(prefix = "call"),
                    toolName = obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    input = parseToolInput(obj["arguments"]?.jsonPrimitive?.contentOrNull, json),
                )
                toolCalls += toolCall
                content += toolCall
            }
        }
    }

    val incompleteReason = response["incomplete_details"]?.jsonObject?.get("reason")?.jsonPrimitive?.contentOrNull
    val text = content.filterIsInstance<ContentPart.Text>().joinToString("") { it.text }
    return LanguageModelResult(
        text = text,
        toolCalls = toolCalls,
        finishReason = mapOpenResponsesFinishReason(incompleteReason, hasToolCalls),
        usage = openResponsesUsage(response["usage"]),
        content = content,
        rawFinishReason = incompleteReason,
        warnings = warnings,
        request = LanguageModelRequestMetadata(body = requestBody),
        response = LanguageModelResponseMetadata(
            id = response["id"]?.jsonPrimitive?.contentOrNull,
            timestampMillis = response["created_at"]?.jsonPrimitive?.intOrNull?.toLong()?.times(1000),
            modelId = response["model"]?.jsonPrimitive?.contentOrNull,
            headers = responseHeaders,
            body = responseBody,
        ),
    )
}

private class OpenResponsesStreamState(
    private val json: Json,
) {
    private val toolCallsByItemId = mutableMapOf<String, PendingOpenResponsesToolCall>()
    private var finishReason = FinishReason.Other
    private var rawFinishReason: String? = null
    private var usage = Usage()
    private var hasToolCalls = false
    private var activeReasoningId: String? = null

    fun accept(chunk: JsonElement): List<StreamEvent> {
        val obj = chunk as? JsonObject ?: return emptyList()
        val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: return emptyList()
        val events = mutableListOf<StreamEvent>()
        when (type) {
            "response.output_item.added" -> {
                val item = obj["item"]?.jsonObject ?: return emptyList()
                val itemType = item["type"]?.jsonPrimitive?.contentOrNull
                val itemId = item["id"]?.jsonPrimitive?.contentOrNull ?: obj["item_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                when (itemType) {
                    "function_call" -> {
                        toolCallsByItemId[itemId] = PendingOpenResponsesToolCall(
                            toolName = item["name"]?.jsonPrimitive?.contentOrNull,
                            toolCallId = item["call_id"]?.jsonPrimitive?.contentOrNull,
                            arguments = item["arguments"]?.jsonPrimitive?.contentOrNull,
                        )
                    }
                    "reasoning" -> {
                        activeReasoningId = itemId
                        events += StreamEvent.ReasoningStart(itemId)
                    }
                    "message" -> events += StreamEvent.TextStart(itemId)
                }
            }
            "response.function_call_arguments.delta" -> {
                val itemId = obj["item_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val pending = toolCallsByItemId.getOrPut(itemId) { PendingOpenResponsesToolCall() }
                pending.arguments = (pending.arguments ?: "") + obj["delta"]?.jsonPrimitive?.contentOrNull.orEmpty()
            }
            "response.function_call_arguments.done" -> {
                val itemId = obj["item_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val pending = toolCallsByItemId.getOrPut(itemId) { PendingOpenResponsesToolCall() }
                pending.arguments = obj["arguments"]?.jsonPrimitive?.contentOrNull
            }
            "response.output_item.done" -> {
                val item = obj["item"]?.jsonObject ?: return emptyList()
                val itemType = item["type"]?.jsonPrimitive?.contentOrNull
                val itemId = item["id"]?.jsonPrimitive?.contentOrNull ?: obj["item_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                when (itemType) {
                    "function_call" -> {
                        val pending = toolCallsByItemId.remove(itemId)
                        val toolName = pending?.toolName ?: item["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        val toolCallId = pending?.toolCallId ?: item["call_id"]?.jsonPrimitive?.contentOrNull ?: generateId(prefix = "call")
                        val arguments = pending?.arguments ?: item["arguments"]?.jsonPrimitive?.contentOrNull
                        events += StreamEvent.ToolCall(toolCallId, toolName, parseToolInput(arguments, json))
                        hasToolCalls = true
                    }
                    "reasoning" -> {
                        events += StreamEvent.ReasoningEnd(itemId)
                        activeReasoningId = null
                    }
                    "message" -> events += StreamEvent.TextEnd(itemId)
                }
            }
            "response.reasoning_text.delta" -> events += StreamEvent.ReasoningDelta(
                id = obj["item_id"]?.jsonPrimitive?.contentOrNull ?: activeReasoningId ?: "reasoning-0",
                text = obj["delta"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            )
            "response.output_text.delta" -> events += StreamEvent.TextDelta(
                id = obj["item_id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                text = obj["delta"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            )
            "response.completed",
            "response.incomplete",
            -> {
                val response = obj["response"]?.jsonObject ?: JsonObject(emptyMap())
                rawFinishReason = response["incomplete_details"]?.jsonObject?.get("reason")?.jsonPrimitive?.contentOrNull
                finishReason = mapOpenResponsesFinishReason(rawFinishReason, hasToolCalls)
                usage = openResponsesUsage(response["usage"])
            }
            "response.failed" -> {
                val response = obj["response"]?.jsonObject ?: JsonObject(emptyMap())
                finishReason = FinishReason.Error
                rawFinishReason = response["error"]?.jsonObject?.get("code")?.jsonPrimitive?.contentOrNull
                    ?: response["status"]?.jsonPrimitive?.contentOrNull
                usage = openResponsesUsage(response["usage"])
            }
        }
        return events
    }

    fun finish(): List<StreamEvent> = buildList {
        activeReasoningId?.let { add(StreamEvent.ReasoningEnd(it)) }
        add(StreamEvent.Finish(totalSteps = 1, finishReason = finishReason, usage = usage))
    }
}

private data class PendingOpenResponsesToolCall(
    var toolName: String? = null,
    var toolCallId: String? = null,
    var arguments: String? = null,
)

private fun openResponsesUsage(element: JsonElement?): Usage {
    val obj = element as? JsonObject ?: return Usage()
    val inputTokens = obj["input_tokens"]?.jsonPrimitive?.intOrNull ?: 0
    val cachedInputTokens = obj["input_tokens_details"]?.jsonObject?.get("cached_tokens")?.jsonPrimitive?.intOrNull ?: 0
    val outputTokens = obj["output_tokens"]?.jsonPrimitive?.intOrNull ?: 0
    val reasoningTokens = obj["output_tokens_details"]?.jsonObject?.get("reasoning_tokens")?.jsonPrimitive?.intOrNull ?: 0
    return Usage(
        inputTokens = Usage.InputTokenBreakdown(
            total = inputTokens,
            noCache = inputTokens - cachedInputTokens,
            cacheRead = cachedInputTokens,
        ),
        outputTokens = Usage.OutputTokenBreakdown(
            total = outputTokens,
            text = outputTokens - reasoningTokens,
            reasoning = reasoningTokens,
        ),
        raw = element,
    )
}

private fun mapOpenResponsesFinishReason(reason: String?, hasToolCalls: Boolean): FinishReason = when (reason) {
    null -> if (hasToolCalls) FinishReason.ToolCalls else FinishReason.Stop
    "max_output_tokens" -> FinishReason.Length
    "content_filter" -> FinishReason.ContentFilter
    else -> if (hasToolCalls) FinishReason.ToolCalls else FinishReason.Other
}

private fun parseToolInput(arguments: String?, json: Json): JsonElement =
    if (arguments.isNullOrBlank()) {
        JsonObject(emptyMap())
    } else {
        runCatching { json.parseToJsonElement(arguments) }.getOrElse { JsonPrimitive(arguments) }
    }

private fun openResponsesErrorFromResponse(raw: String): AiSdkException {
    val message = runCatching {
        val obj = openResponsesJson.parseToJsonElement(raw).jsonObject
        obj["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
    }.getOrNull()
    return AiSdkException(message ?: raw.ifBlank { "Open Responses request failed" })
}

private val openResponsesJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}
