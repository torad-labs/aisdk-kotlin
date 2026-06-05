package ai.torad.aisdk.providers

import ai.torad.aisdk.*
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
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

public const val OPEN_RESPONSES_VERSION: String = "1.0.16"
public const val OPEN_RESPONSES_TOP_LOGPROBS_MAX: Int = 20

public val OPEN_RESPONSES_SUPPORTED_URLS: Map<String, List<String>> = mapOf("image/*" to listOf("^https?://.*$"))

public data class OpenResponsesProviderSettings(
    val url: String,
    val name: String,
    val apiKey: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val authHeadersProvider: (suspend () -> Map<String, String>)? = null,
    val userAgentSuffix: String? = "ai-sdk/open-responses/$OPEN_RESPONSES_VERSION",
    val providerOptionsName: String? = null,
    val supportedUrls: Map<String, List<String>> = OPEN_RESPONSES_SUPPORTED_URLS,
    val fileIdPrefixes: List<String> = emptyList(),
)

@Serializable
public data class OpenResponsesOptions(
    val conversation: String? = null,
    val include: List<String>? = null,
    val instructions: String? = null,
    val logprobs: JsonElement? = null,
    val maxToolCalls: Int? = null,
    val metadata: JsonElement? = null,
    val parallelToolCalls: Boolean? = null,
    val previousResponseId: String? = null,
    val promptCacheKey: String? = null,
    val promptCacheRetention: String? = null,
    val reasoningEffort: String? = null,
    val reasoningSummary: String? = null,
    val safetyIdentifier: String? = null,
    val serviceTier: String? = null,
    val store: Boolean? = null,
    val passThroughUnsupportedFiles: Boolean? = null,
    val strictJsonSchema: Boolean? = null,
    val textVerbosity: String? = null,
    val truncation: String? = null,
    val user: String? = null,
    val systemMessageMode: String? = null,
    val forceReasoning: Boolean? = null,
    val allowedTools: OpenResponsesAllowedTools? = null,
)

@Serializable
public data class OpenResponsesAllowedTools(
    val toolNames: List<String> = emptyList(),
    val mode: String? = null,
)

public interface OpenResponsesProvider : Provider {
    public operator fun invoke(modelId: String): LanguageModel = languageModel(modelId)
    public fun responses(modelId: String): LanguageModel = languageModel(modelId)
}

public fun createOpenResponses(
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
    override val supportedUrls: Map<String, List<String>> = settings.supportedUrls

    override suspend fun generate(params: LanguageModelCallParams): LanguageModelResult {
        val prepared = openResponsesRequestBody(
            params,
            stream = false,
            providerOptionsName = settings.providerOptionsName ?: settings.name,
            modelId,
            json,
            settings.fileIdPrefixes,
        )
        val response = postJson(prepared.body, acceptEventStream = false, headers = params.headers)
        return openResponsesGenerateResult(
            response = response.value.jsonObject,
            requestBody = prepared.body,
            responseHeaders = response.headers,
            responseBody = response.value,
            warnings = prepared.warnings,
            json = json,
            providerMetadataKey = settings.providerOptionsName ?: settings.name,
        )
    }

    override fun stream(params: LanguageModelCallParams): Flow<StreamEvent> = flow {
        val prepared = openResponsesRequestBody(
            params,
            stream = true,
            providerOptionsName = settings.providerOptionsName ?: settings.name,
            modelId,
            json,
            settings.fileIdPrefixes,
        )
        emit(StreamEvent.StreamStart(prepared.warnings))
        val state = OpenResponsesStreamState(json)
        val rawLines = streamResponsesSse(prepared.body, params.headers) { responseHeaders ->
            emit(StreamEvent.ResponseMetadata(headers = responseHeaders))
        }
        parseJsonEventStream(rawLines, jsonSchema<JsonElement>(JsonObject(emptyMap())), json).collect { event ->
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
            settings.fileIdPrefixes,
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

    /** Streaming counterpart of [postJson]: reads the SSE body incrementally,
     *  surfacing non-2xx as the same rich [APICallError] as [parseResponse]. */
    private fun streamResponsesSse(
        body: JsonElement,
        headers: Map<String, String>,
        onResponse: suspend (Map<String, String>) -> Unit,
    ): Flow<String> = flow {
        emitAll(
            streamSse(
                client = client,
                url = settings.url,
                method = HttpMethod.Post,
                headers = requestHeaders(headers) + (HttpHeaders.Accept to "text/event-stream"),
                body = body,
                json = json,
                requestBodyValues = body,
                errorMessage = { _, parsed, raw ->
                    (parsed as? JsonObject)?.get("error")?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
                        ?: raw.ifBlank { "Open Responses request failed" }
                },
                onResponse = onResponse,
            ),
        )
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
            throw openResponsesErrorFromResponse(response, raw, headers)
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
    fileIdPrefixes: List<String> = emptyList(),
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

    val convertedInput = convertToOpenResponsesInput(params.messages, warnings, fileIdPrefixes)
    val providerOptions = openResponsesProviderOptions(params.providerOptions, providerOptionsName, json)
    val topLogprobs = openResponsesTopLogprobs(providerOptions?.logprobs)
    val include = openResponsesInclude(providerOptions, params.tools, modelId, topLogprobs)

    return PreparedOpenResponsesRequest(
        body = buildJsonObject {
            put("model", JsonPrimitive(modelId))
            put("input", convertedInput.input)
            val instructions = when (providerOptions?.systemMessageMode) {
                "remove" -> providerOptions.instructions
                else -> providerOptions?.instructions ?: convertedInput.instructions
            }
            instructions?.let { put("instructions", JsonPrimitive(it)) }
            params.maxOutputTokens?.let { put("max_output_tokens", JsonPrimitive(it)) }
            params.temperature?.let { put("temperature", JsonPrimitive(it)) }
            params.topP?.let { put("top_p", JsonPrimitive(it)) }
            params.presencePenalty?.let { put("presence_penalty", JsonPrimitive(it)) }
            params.frequencyPenalty?.let { put("frequency_penalty", JsonPrimitive(it)) }
            if (stream) put("stream", JsonPrimitive(true))

            val reasoning = openResponsesReasoning(providerOptions)
            if (reasoning.isNotEmpty()) put("reasoning", JsonObject(reasoning))

            if (params.tools.isNotEmpty()) put("tools", JsonArray(params.tools.map(::openResponsesToolJson)))
            openResponsesToolChoice(params.toolChoice, providerOptions)?.let { put("tool_choice", it) }
            val text = openResponsesText(params.responseFormat, providerOptions)
            if (text.isNotEmpty()) put("text", JsonObject(text))

            providerOptions?.conversation?.let { put("conversation", JsonPrimitive(it)) }
            providerOptions?.maxToolCalls?.let { put("max_tool_calls", JsonPrimitive(it)) }
            providerOptions?.metadata?.let { put("metadata", it) }
            providerOptions?.parallelToolCalls?.let { put("parallel_tool_calls", JsonPrimitive(it)) }
            providerOptions?.previousResponseId?.let { put("previous_response_id", JsonPrimitive(it)) }
            providerOptions?.promptCacheKey?.let { put("prompt_cache_key", JsonPrimitive(it)) }
            providerOptions?.promptCacheRetention?.let { put("prompt_cache_retention", JsonPrimitive(it)) }
            providerOptions?.safetyIdentifier?.let { put("safety_identifier", JsonPrimitive(it)) }
            providerOptions?.serviceTier?.let { put("service_tier", JsonPrimitive(it)) }
            providerOptions?.store?.let { put("store", JsonPrimitive(it)) }
            providerOptions?.truncation?.let { put("truncation", JsonPrimitive(it)) }
            providerOptions?.user?.let { put("user", JsonPrimitive(it)) }
            include?.let { put("include", it) }
            topLogprobs?.let { put("top_logprobs", JsonPrimitive(it)) }
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
    fileIdPrefixes: List<String> = emptyList(),
): ConvertedOpenResponsesInput {
    val input = mutableListOf<JsonElement>()
    val systemMessages = mutableListOf<String>()

    for (message in messages) {
        when (message.role) {
            MessageRole.System -> systemMessages += message.content.textContent()
            MessageRole.User -> input += buildJsonObject {
                put("type", JsonPrimitive("message"))
                put("role", JsonPrimitive("user"))
                put("content", JsonArray(message.content.mapNotNull { openResponsesUserContentPart(it, fileIdPrefixes) }))
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

private fun isOpenResponsesFileId(value: String, prefixes: List<String>): Boolean =
    prefixes.any { prefix -> prefix.isNotEmpty() && value.startsWith(prefix) } && !isOpenResponsesBase64Payload(value)

private fun isOpenResponsesBase64Payload(value: String): Boolean =
    runCatching { convertBase64ToByteArray(value) }.isSuccess

private fun openResponsesFileId(
    value: String,
    prefixes: List<String>,
    providerMetadata: Map<String, JsonElement>?,
): String? =
    explicitOpenResponsesFileId(providerMetadata)
        ?: value.takeIf { isOpenResponsesFileId(it, prefixes) }

private fun explicitOpenResponsesFileId(providerMetadata: Map<String, JsonElement>?): String? {
    val openai = providerMetadata?.get("openai") as? JsonObject
    return openai?.get("file_id").metadataString()
        ?: openai?.get("fileId").metadataString()
        ?: providerMetadata?.get("file_id").metadataString()
        ?: providerMetadata?.get("fileId").metadataString()
}

private fun JsonElement?.metadataString(): String? =
    (this as? JsonPrimitive)?.contentOrNull

private fun openResponsesUserContentPart(
    part: ContentPart,
    fileIdPrefixes: List<String>,
): JsonElement? = when (part) {
    is ContentPart.Text -> buildJsonObject {
        put("type", JsonPrimitive("input_text"))
        put("text", JsonPrimitive(part.text))
    }
    is ContentPart.Image -> buildJsonObject {
        put("type", JsonPrimitive("input_image"))
        val fileId = openResponsesFileId(part.base64, fileIdPrefixes, part.providerMetadata)
        if (fileId != null) {
            put("file_id", JsonPrimitive(fileId))
        } else {
            put("image_url", JsonPrimitive("data:${part.mediaType};base64,${part.base64}"))
        }
    }
    is ContentPart.File -> if (part.mediaType.startsWith("image/")) {
        buildJsonObject {
            put("type", JsonPrimitive("input_image"))
            val fileId = openResponsesFileId(part.base64, fileIdPrefixes, part.providerMetadata)
            if (fileId != null) {
                put("file_id", JsonPrimitive(fileId))
            } else {
                put("image_url", JsonPrimitive("data:${part.mediaType};base64,${part.base64}"))
            }
        }
    } else {
        buildJsonObject {
            put("type", JsonPrimitive("input_file"))
            put("filename", JsonPrimitive(part.filename ?: "data"))
            val fileId = openResponsesFileId(part.base64, fileIdPrefixes, part.providerMetadata)
            if (fileId != null) {
                put("file_id", JsonPrimitive(fileId))
            } else {
                put("file_data", JsonPrimitive("data:${part.mediaType};base64,${part.base64}"))
            }
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

private fun openResponsesToolJson(tool: LanguageModelTool): JsonObject =
    if (tool.providerExecuted) openResponsesProviderToolJson(tool) else openResponsesFunctionToolJson(tool)

private fun openResponsesFunctionToolJson(tool: LanguageModelTool): JsonObject = buildJsonObject {
    put("type", JsonPrimitive("function"))
    put("name", JsonPrimitive(tool.name))
    put("description", JsonPrimitive(tool.description))
    put("parameters", openResponsesJson.parseToJsonElement(tool.parametersSchemaJson))
    put("strict", JsonPrimitive(tool.strict))
}

private fun openResponsesProviderToolJson(tool: LanguageModelTool): JsonObject = buildJsonObject {
    val args = openResponsesProviderToolArgs(tool)
    when (val type = openResponsesProviderToolType(tool)) {
        "file_search" -> {
            put("type", JsonPrimitive(type))
            putOpenResponsesField("vector_store_ids", args["vectorStoreIds"] ?: args["vector_store_ids"])
            putOpenResponsesField("max_num_results", args["maxNumResults"] ?: args["max_num_results"])
            openResponsesRankingOptions(args)?.let { put("ranking_options", it) }
            putOpenResponsesField("filters", args["filters"])
        }
        "web_search", "web_search_preview" -> {
            put("type", JsonPrimitive(type))
            putOpenResponsesField("external_web_access", args["externalWebAccess"] ?: args["external_web_access"])
            putOpenResponsesField("filters", openResponsesWebSearchFilters(args["filters"]))
            putOpenResponsesField("search_context_size", args["searchContextSize"] ?: args["search_context_size"])
            putOpenResponsesField("user_location", args["userLocation"] ?: args["user_location"])
        }
        "code_interpreter" -> {
            put("type", JsonPrimitive(type))
            put("container", openResponsesCodeInterpreterContainer(args["container"]))
        }
        "image_generation" -> {
            put("type", JsonPrimitive(type))
            putOpenResponsesField("background", args["background"])
            putOpenResponsesField("input_fidelity", args["inputFidelity"] ?: args["input_fidelity"])
            putOpenResponsesField("input_image_mask", openResponsesInputImageMask(args["inputImageMask"] ?: args["input_image_mask"]))
            putOpenResponsesField("model", args["model"])
            putOpenResponsesField("moderation", args["moderation"])
            putOpenResponsesField("partial_images", args["partialImages"] ?: args["partial_images"])
            putOpenResponsesField("quality", args["quality"])
            putOpenResponsesField("output_compression", args["outputCompression"] ?: args["output_compression"])
            putOpenResponsesField("output_format", args["outputFormat"] ?: args["output_format"])
            putOpenResponsesField("size", args["size"])
        }
        "mcp" -> {
            put("type", JsonPrimitive(type))
            putOpenResponsesField("server_label", args["serverLabel"] ?: args["server_label"])
            putOpenResponsesField("allowed_tools", openResponsesAllowedMcpTools(args["allowedTools"] ?: args["allowed_tools"]))
            putOpenResponsesField("authorization", args["authorization"])
            putOpenResponsesField("connector_id", args["connectorId"] ?: args["connector_id"])
            putOpenResponsesField("headers", args["headers"])
            putOpenResponsesField("require_approval", openResponsesRequireApproval(args["requireApproval"] ?: args["require_approval"]))
            putOpenResponsesField("server_description", args["serverDescription"] ?: args["server_description"])
            putOpenResponsesField("server_url", args["serverUrl"] ?: args["server_url"])
        }
        "shell" -> {
            put("type", JsonPrimitive(type))
            putOpenResponsesField("environment", openResponsesShellEnvironment(args["environment"]))
        }
        "tool_search" -> {
            put("type", JsonPrimitive(type))
            putOpenResponsesField("execution", args["execution"])
            putOpenResponsesField("description", args["description"])
            putOpenResponsesField("parameters", args["parameters"])
        }
        "custom" -> {
            put("type", JsonPrimitive(type))
            putOpenResponsesField("name", args["name"] ?: JsonPrimitive(tool.name))
            putOpenResponsesField("description", args["description"] ?: JsonPrimitive(tool.description))
            putOpenResponsesField("format", args["format"])
        }
        else -> put("type", JsonPrimitive(type))
    }
}

private fun openResponsesToolChoice(
    choice: ToolChoice,
    options: OpenResponsesOptions?,
): JsonElement? {
    options?.allowedTools?.takeIf { it.toolNames.isNotEmpty() }?.let { allowed ->
        return buildJsonObject {
            put("type", JsonPrimitive("allowed_tools"))
            put("mode", JsonPrimitive(allowed.mode ?: "auto"))
            put(
                "tools",
                JsonArray(
                    allowed.toolNames.map { name ->
                        buildJsonObject {
                            put("type", JsonPrimitive("function"))
                            put("name", JsonPrimitive(name))
                        }
                    },
                ),
            )
        }
    }
    return when (choice) {
        ToolChoice.Auto -> JsonPrimitive("auto")
        ToolChoice.None -> JsonPrimitive("none")
        ToolChoice.Required -> JsonPrimitive("required")
        is ToolChoice.Specific -> buildJsonObject {
            val providerToolType = openResponsesProviderToolTypeOrNull(choice.toolName)
            if (providerToolType != null) {
                put("type", JsonPrimitive(providerToolType))
            } else {
                put("type", JsonPrimitive("function"))
                put("name", JsonPrimitive(choice.toolName))
            }
        }
    }
}

private fun openResponsesText(
    format: ResponseFormat,
    options: OpenResponsesOptions?,
): Map<String, JsonElement> = buildMap {
    openResponsesTextFormat(format, options?.strictJsonSchema ?: true)?.let { put("format", it) }
    options?.textVerbosity?.let { put("verbosity", JsonPrimitive(it)) }
}

private fun openResponsesTextFormat(format: ResponseFormat, strict: Boolean): JsonElement? = when (format) {
    ResponseFormat.Text -> null
    is ResponseFormat.Json -> buildJsonObject {
        put("type", JsonPrimitive("json_schema"))
        format.schemaName?.let { put("name", JsonPrimitive(it)) } ?: put("name", JsonPrimitive("response"))
        format.schemaDescription?.let { put("description", JsonPrimitive(it)) }
        format.schemaJson?.let { put("schema", it) }
        put("strict", JsonPrimitive(strict))
    }
}

private fun openResponsesTopLogprobs(logprobs: JsonElement?): Int? {
    val primitive = logprobs?.jsonPrimitive ?: return null
    primitive.booleanOrNull?.let { return if (it) OPEN_RESPONSES_TOP_LOGPROBS_MAX else null }
    return primitive.intOrNull?.coerceIn(1, OPEN_RESPONSES_TOP_LOGPROBS_MAX)
}

private fun openResponsesInclude(
    options: OpenResponsesOptions?,
    tools: List<LanguageModelTool>,
    modelId: String,
    topLogprobs: Int?,
): JsonArray? {
    val include = linkedSetOf<String>()
    options?.include.orEmpty().forEach { include += it }
    if (topLogprobs != null) include += "message.output_text.logprobs"
    if (tools.any { it.providerExecuted && it.name in setOf("web_search", "web_search_preview") }) {
        include += "web_search_call.action.sources"
    }
    if (tools.any { it.providerExecuted && it.name == "code_interpreter" }) {
        include += "code_interpreter_call.outputs"
    }
    if (options?.store == false && isOpenResponsesReasoningModel(modelId, options)) {
        include += "reasoning.encrypted_content"
    }
    return include.takeIf { it.isNotEmpty() }?.let { JsonArray(it.map(::JsonPrimitive)) }
}

private fun JsonObjectBuilder.putOpenResponsesField(name: String, value: JsonElement?) {
    if (value != null && value !is JsonNull) put(name, value)
}

private fun openResponsesProviderToolArgs(tool: LanguageModelTool): JsonObject =
    (tool.metadata["providerToolArgs"] as? JsonObject)
        ?: (tool.metadata["providerOptions"] as? JsonObject)
        ?: JsonObject(emptyMap())

private fun openResponsesProviderToolType(tool: LanguageModelTool): String {
    val providerToolId = tool.metadata["providerToolId"]?.jsonPrimitive?.contentOrNull
    return providerToolId?.removePrefix("openai.") ?: openResponsesProviderToolTypeOrNull(tool.name) ?: "custom"
}

private fun openResponsesProviderToolTypeOrNull(toolName: String): String? = when (toolName) {
    "apply_patch",
    "code_interpreter",
    "file_search",
    "image_generation",
    "local_shell",
    "mcp",
    "shell",
    "tool_search",
    "web_search",
    "web_search_preview",
    -> toolName
    else -> null
}

private fun openResponsesRankingOptions(args: JsonObject): JsonObject? {
    val ranking = args["ranking"] as? JsonObject ?: return null
    val mapped = buildJsonObject {
        putOpenResponsesField("ranker", ranking["ranker"])
        putOpenResponsesField("score_threshold", ranking["scoreThreshold"] ?: ranking["score_threshold"])
    }
    return mapped.takeIf { it.isNotEmpty() }
}

private fun openResponsesWebSearchFilters(value: JsonElement?): JsonElement? {
    val obj = value as? JsonObject ?: return value
    val allowedDomains = obj["allowedDomains"] ?: obj["allowed_domains"]
    return if (allowedDomains == null) value else buildJsonObject { put("allowed_domains", allowedDomains) }
}

private fun openResponsesCodeInterpreterContainer(value: JsonElement?): JsonElement =
    when (value) {
        null, JsonNull -> buildJsonObject { put("type", JsonPrimitive("auto")) }
        is JsonPrimitive -> value
        is JsonObject -> buildJsonObject {
            put("type", JsonPrimitive("auto"))
            putOpenResponsesField("file_ids", value["fileIds"] ?: value["file_ids"])
        }
        else -> value
    }

private fun openResponsesInputImageMask(value: JsonElement?): JsonElement? {
    val obj = value as? JsonObject ?: return value
    return buildJsonObject {
        putOpenResponsesField("file_id", obj["fileId"] ?: obj["file_id"])
        putOpenResponsesField("image_url", obj["imageUrl"] ?: obj["image_url"])
    }.takeIf { it.isNotEmpty() }
}

private fun openResponsesAllowedMcpTools(value: JsonElement?): JsonElement? {
    val obj = value as? JsonObject ?: return value
    return buildJsonObject {
        putOpenResponsesField("read_only", obj["readOnly"] ?: obj["read_only"])
        putOpenResponsesField("tool_names", obj["toolNames"] ?: obj["tool_names"])
    }.takeIf { it.isNotEmpty() }
}

private fun openResponsesRequireApproval(value: JsonElement?): JsonElement? {
    val obj = value as? JsonObject ?: return value
    val never = obj["never"] as? JsonObject ?: return value
    return buildJsonObject {
        put(
            "never",
            buildJsonObject {
                putOpenResponsesField("tool_names", never["toolNames"] ?: never["tool_names"])
            },
        )
    }
}

private fun openResponsesShellEnvironment(value: JsonElement?): JsonElement? {
    val obj = value as? JsonObject ?: return value
    return when (obj["type"]?.jsonPrimitive?.contentOrNull) {
        "containerReference" -> buildJsonObject {
            put("type", JsonPrimitive("container_reference"))
            putOpenResponsesField("container_id", obj["containerId"] ?: obj["container_id"])
        }
        "containerAuto" -> buildJsonObject {
            put("type", JsonPrimitive("container_auto"))
            putOpenResponsesField("file_ids", obj["fileIds"] ?: obj["file_ids"])
            putOpenResponsesField("memory_limit", obj["memoryLimit"] ?: obj["memory_limit"])
            putOpenResponsesField("network_policy", obj["networkPolicy"] ?: obj["network_policy"])
            putOpenResponsesField("skills", obj["skills"])
        }
        else -> value
    }
}

private fun isOpenResponsesReasoningModel(modelId: String, options: OpenResponsesOptions?): Boolean =
    options?.forceReasoning == true ||
        modelId == "o1" ||
        modelId.startsWith("o1-") ||
        modelId == "o3" ||
        modelId.startsWith("o3-") ||
        modelId == "o3-mini" ||
        modelId.startsWith("o3-mini-") ||
        modelId == "o4-mini" ||
        modelId.startsWith("o4-mini-") ||
        modelId == "gpt-5" ||
        modelId.startsWith("gpt-5-") ||
        modelId.startsWith("gpt-5.") ||
        modelId.startsWith("gpt-5_")

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

private fun openResponsesResultProviderMetadata(
    response: JsonObject,
    providerMetadataKey: String,
    logprobs: List<JsonElement>,
): Map<String, JsonElement> {
    val metadata = buildJsonObject {
        response["id"]?.jsonPrimitive?.contentOrNull?.let { put("responseId", JsonPrimitive(it)) }
        if (logprobs.isNotEmpty()) put("logprobs", JsonArray(logprobs))
    }
    return metadata.takeIf { it.isNotEmpty() }?.let { mapOf(providerMetadataKey to it) }.orEmpty()
}

private fun openResponsesPartMetadata(
    providerMetadataKey: String,
    itemId: String?,
    obj: JsonObject,
): Map<String, JsonElement>? {
    val metadata = buildJsonObject {
        itemId?.let { put("itemId", JsonPrimitive(it)) }
        (obj["annotations"] as? JsonArray)?.takeIf { it.isNotEmpty() }?.let { put("annotations", it) }
        obj["logprobs"]?.let { put("logprobs", it) }
        obj["encrypted_content"]?.let { put("encryptedContent", it) }
    }
    return metadata.takeIf { it.isNotEmpty() }?.let { mapOf(providerMetadataKey to it) }
}

private fun openResponsesGenerateResult(
    response: JsonObject,
    requestBody: JsonElement,
    responseHeaders: Map<String, String>,
    responseBody: JsonElement,
    warnings: List<CallWarning>,
    json: Json,
    providerMetadataKey: String,
): LanguageModelResult {
    val content = mutableListOf<ContentPart>()
    val toolCalls = mutableListOf<ContentPart.ToolCall>()
    val logprobs = mutableListOf<JsonElement>()
    var hasToolCalls = false

    for (part in response["output"]?.jsonArray.orEmpty()) {
        val obj = part.jsonObject
        val itemId = obj["id"]?.jsonPrimitive?.contentOrNull
        when (obj["type"]?.jsonPrimitive?.contentOrNull) {
            "reasoning" -> {
                val reasoningParts = (obj["content"] as? JsonArray) ?: (obj["summary"] as? JsonArray) ?: JsonArray(emptyList())
                reasoningParts.forEach { reasoning ->
                    val text = reasoning.jsonObject["text"]?.jsonPrimitive?.contentOrNull
                    if (!text.isNullOrEmpty()) {
                        content += ContentPart.Reasoning(text, openResponsesPartMetadata(providerMetadataKey, itemId, reasoning.jsonObject))
                    }
                }
            }
            "message" -> {
                obj["content"]?.jsonArray.orEmpty().forEach { messagePart ->
                    val messageObj = messagePart.jsonObject
                    val text = messageObj["text"]?.jsonPrimitive?.contentOrNull
                        ?: messageObj["refusal"]?.jsonPrimitive?.contentOrNull
                    messageObj["logprobs"]?.let { logprobs += it }
                    if (!text.isNullOrEmpty()) {
                        content += ContentPart.Text(text, openResponsesPartMetadata(providerMetadataKey, itemId, messageObj))
                    }
                }
            }
            "function_call" -> {
                hasToolCalls = true
                val toolCall = ContentPart.ToolCall(
                    toolCallId = obj["call_id"]?.jsonPrimitive?.contentOrNull ?: generateId(prefix = "call"),
                    toolName = obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    input = parseToolInput(obj["arguments"]?.jsonPrimitive?.contentOrNull, json),
                    providerMetadata = openResponsesPartMetadata(providerMetadataKey, itemId, obj),
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
        providerMetadata = openResponsesResultProviderMetadata(response, providerMetadataKey, logprobs),
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
                val item = obj["item"] as? JsonObject
                    ?: return listOf(StreamEvent.Error("Open Responses stream protocol error: response.output_item.added missing item."))
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
                val item = obj["item"] as? JsonObject
                    ?: return listOf(StreamEvent.Error("Open Responses stream protocol error: response.output_item.done missing item."))
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
    val cachedInputTokens = (obj["input_tokens_details"]?.jsonObject?.get("cached_tokens")?.jsonPrimitive?.intOrNull ?: 0)
        .coerceIn(0, inputTokens)
    val outputTokens = obj["output_tokens"]?.jsonPrimitive?.intOrNull ?: 0
    val reasoningTokens = (obj["output_tokens_details"]?.jsonObject?.get("reasoning_tokens")?.jsonPrimitive?.intOrNull ?: 0)
        .coerceAtLeast(0)
    val outputTotal = if (reasoningTokens > outputTokens) outputTokens + reasoningTokens else outputTokens
    return Usage(
        inputTokens = Usage.InputTokenBreakdown(
            total = inputTokens,
            noCache = (inputTokens - cachedInputTokens).coerceAtLeast(0),
            cacheRead = cachedInputTokens,
        ),
        outputTokens = Usage.OutputTokenBreakdown(
            total = outputTotal,
            text = outputTotal - reasoningTokens,
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

private fun openResponsesErrorFromResponse(
    response: HttpResponse,
    raw: String,
    headers: Map<String, String>,
): APICallError {
    val message = runCatching {
        val obj = openResponsesJson.parseToJsonElement(raw).jsonObject
        obj["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
    }.getOrNull()
    return apiCallError(
        url = response.call.request.url.toString(),
        statusCode = response.status.value,
        rawBody = raw,
        headers = headers,
        message = message ?: raw.ifBlank { "Open Responses request failed" },
    )
}

private val openResponsesJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}
