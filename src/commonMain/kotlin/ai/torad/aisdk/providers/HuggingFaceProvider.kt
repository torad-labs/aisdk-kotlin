@file:OptIn(ai.torad.aisdk.LowLevelLanguageModelApi::class)

package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import ai.torad.aisdk.ProviderMetadata
import dev.drewhamilton.poko.Poko
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
import kotlinx.serialization.SerializationException
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
import kotlinx.serialization.json.longOrNull

public const val HUGGINGFACE_VERSION: String = "1.0.50"

public typealias HuggingFaceErrorData = JsonObject

public class HuggingFaceProviderSettings internal constructor(
    public val apiKey: String? = null,
    public val baseURL: String = "https://router.huggingface.co/v1",
    public val headers: Map<String, String> = emptyMap(),
    public val generateId: () -> String = { IdGenerator.generate() },
) {
    internal fun huggingFaceHeaders(extra: Map<String, String>): Map<String, String> {
        val merged = linkedMapOf<String, String?>()
        apiKey?.takeIf { it.isNotBlank() }?.let { merged[HttpHeaders.Authorization] = "Bearer $it" }
        merged.putAll(headers)
        merged.putAll(extra)
        return ProviderHeaders.normalize(merged)
    }

    internal fun huggingFaceUsage(element: JsonElement?): Usage {
        val obj = element as? JsonObject ?: return Usage()
        val inputTokens = (obj["input_tokens"] as? JsonPrimitive)?.intOrNull ?: 0
        val cachedTokens = (((JsonAccess.obj(obj, "input_tokens_details"))?.get("cached_tokens") as? JsonPrimitive)?.intOrNull ?: 0)
            .coerceIn(0, inputTokens)
        val outputTokens = (obj["output_tokens"] as? JsonPrimitive)?.intOrNull ?: 0
        val reasoningTokens = (((JsonAccess.obj(obj, "output_tokens_details"))?.get("reasoning_tokens") as? JsonPrimitive)?.intOrNull ?: 0)
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

    internal fun mapHuggingFaceFinishReason(reason: String?): FinishReason = when (reason) {
        "stop" -> FinishReason.Stop
        "length" -> FinishReason.Length
        "content_filter" -> FinishReason.ContentFilter
        "tool_calls" -> FinishReason.ToolCalls
        "error" -> FinishReason.Error
        else -> FinishReason.Other
    }

    internal fun huggingFaceItemMetadata(
        itemId: String,
        providerExecuted: Boolean = false,
    ): ProviderMetadata = ProviderMetadata.Raw(JsonObject(mapOf(
        "huggingface" to buildJsonObject {
            put("itemId", JsonPrimitive(itemId))
            if (providerExecuted) put("providerExecuted", JsonPrimitive(true))
        },
    )))

    internal fun huggingFaceParseToolInput(arguments: String?, json: Json): JsonElement =
        if (arguments.isNullOrBlank()) {
            JsonObject(emptyMap())
        } else {
            runCatching { json.parseToJsonElement(arguments) }.getOrElse { JsonPrimitive(arguments) }
        }

    internal fun huggingFaceErrorMessage(error: JsonElement): String {
        val obj = error as? JsonObject
        val nested = obj?.get("error")
        val nestedObj = nested as? JsonObject
        return (nestedObj?.get("message") as? JsonPrimitive)?.contentOrNull
            ?: (obj?.get("message") as? JsonPrimitive)?.contentOrNull
            ?: (nested as? JsonPrimitive)?.contentOrNull
            ?: error.toString()
    }
}

public class HuggingFaceProviderSettingsBuilder internal constructor() {
    private var apiKey: String? = null
    private var baseURL: String = "https://router.huggingface.co/v1"
    private var headers: Map<String, String> = emptyMap()
    private var generateId: () -> String = { IdGenerator.generate() }

    public fun apiKey(value: String?) {
        apiKey = value
    }

    public fun baseURL(value: String) {
        baseURL = value
    }

    public fun headers(value: Map<String, String>) {
        headers = value
    }

    public fun generateId(value: () -> String) {
        generateId = value
    }

    internal fun build(): HuggingFaceProviderSettings =
        HuggingFaceProviderSettings(
            apiKey = apiKey,
            baseURL = baseURL,
            headers = headers,
            generateId = generateId,
        )
}

public fun HuggingFaceProviderSettings(
    block: HuggingFaceProviderSettingsBuilder.() -> Unit = {},
): HuggingFaceProviderSettings =
    HuggingFaceProviderSettingsBuilder().apply(block).build()

@Serializable
@Poko
public class HuggingFaceResponsesSettings internal constructor(
    public val metadata: Map<String, String>? = null,
    public val instructions: String? = null,
    public val strictJsonSchema: Boolean? = null,
    public val reasoningEffort: String? = null,
)

public class HuggingFaceResponsesSettingsBuilder internal constructor() {
    private var metadata: Map<String, String>? = null
    private var instructions: String? = null
    private var strictJsonSchema: Boolean? = null
    private var reasoningEffort: String? = null

    public fun metadata(value: Map<String, String>?) {
        metadata = value
    }

    public fun instructions(value: String?) {
        instructions = value
    }

    public fun strictJsonSchema(value: Boolean?) {
        strictJsonSchema = value
    }

    public fun reasoningEffort(value: String?) {
        reasoningEffort = value
    }

    internal fun build(): HuggingFaceResponsesSettings =
        HuggingFaceResponsesSettings(
            metadata = metadata,
            instructions = instructions,
            strictJsonSchema = strictJsonSchema,
            reasoningEffort = reasoningEffort,
        )
}

public fun HuggingFaceResponsesSettings(
    block: HuggingFaceResponsesSettingsBuilder.() -> Unit = {},
): HuggingFaceResponsesSettings =
    HuggingFaceResponsesSettingsBuilder().apply(block).build()

public class HuggingFaceProvider(
    private val client: HttpClient,
    public val settings: HuggingFaceProviderSettings,
) : Provider {
    override val providerId: String = "huggingface"

    public operator fun invoke(modelId: ModelId): LanguageModel = languageModel(modelId.value)

    public fun responses(modelId: ModelId): LanguageModel = languageModel(modelId.value)

    public fun textEmbeddingModel(modelId: String): Nothing = throw huggingFaceNoEmbeddingModel(providerId, modelId)

    override fun languageModel(modelId: String): LanguageModel =
        HuggingFaceResponsesLanguageModel(client, settings, modelId)

    override fun embeddingModel(modelId: String): EmbeddingModel = throw huggingFaceNoEmbeddingModel(providerId, modelId)

    override fun imageModel(modelId: String): ImageModel = throw huggingFaceNoImageModel(providerId, modelId)

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
        val prepared = huggingFaceResponsesRequestBody(modelId, params, stream = false)
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
        val prepared = huggingFaceResponsesRequestBody(modelId, params, stream = true)
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
        val prepared = huggingFaceResponsesRequestBody(modelId, params, stream = true)
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
            settings.huggingFaceHeaders(headers).forEach { (name, value) -> header(name, value) }
            setBody(aiSdkOutputJson.encodeToString(JsonElement.serializer(), body))
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
            val parsed = TypedJsonOps.parseJsonElementOrNull(aiSdkJson, raw)
            throw ApiCallError(
                url = response.call.request.url.toString(),
                statusCode = response.status.value,
                rawBody = raw,
                headers = headers,
                message = "Hugging Face API error: ${parsed?.let(settings::huggingFaceErrorMessage) ?: raw}",
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
                message = "Hugging Face API error: ${settings.huggingFaceErrorMessage(error)}",
                requestBodyValues = requestBody,
            )
        }

        val content = mutableListOf<ContentPart>()
        val toolCalls = mutableListOf<ContentPart.ToolCall>()
        for (part in (JsonAccess.arr(response, "output")).orEmpty()) {
            val obj = part as? JsonObject ?: continue
            val itemId = (obj["id"] as? JsonPrimitive)?.contentOrNull
            val providerMetadata = itemId?.let(settings::huggingFaceItemMetadata) ?: ProviderMetadata.None
            when ((obj["type"] as? JsonPrimitive)?.contentOrNull) {
                "message" -> {
                    for (messagePart in (JsonAccess.arr(obj, "content")).orEmpty()) {
                        val messageObj = messagePart as? JsonObject ?: continue
                        val text = (messageObj["text"] as? JsonPrimitive)?.contentOrNull
                        if (!text.isNullOrEmpty()) content += ContentPart.Text(text, providerMetadata)
                        for (annotation in (JsonAccess.arr(messageObj, "annotations")).orEmpty()) {
                            val annotationObj = annotation as? JsonObject ?: continue
                            content += ContentPart.Source(
                                sourceType = StreamEvent.SourcePart.SourceType.Url,
                                url = (annotationObj["url"] as? JsonPrimitive)?.contentOrNull,
                                title = (annotationObj["title"] as? JsonPrimitive)?.contentOrNull,
                            )
                        }
                    }
                }
                "reasoning" -> {
                    val reasoningParts = JsonAccess.arr(obj, "content").orEmpty() +
                        JsonAccess.arr(obj, "summary").orEmpty()
                    for (reasoningPart in reasoningParts) {
                        val text = ((reasoningPart as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull
                        if (!text.isNullOrEmpty()) content += ContentPart.Reasoning(text, providerMetadata)
                    }
                }
                "function_call" -> {
                    val callId = (obj["call_id"] as? JsonPrimitive)?.contentOrNull ?: settings.generateId()
                    val toolName = (obj["name"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                    val toolCall = ContentPart.ToolCall(
                        toolCallId = callId,
                        toolName = toolName,
                        input = settings.huggingFaceParseToolInput(
                            (obj["arguments"] as? JsonPrimitive)?.contentOrNull,
                            aiSdkJson,
                        ),
                        providerMetadata = providerMetadata,
                    )
                    toolCalls += toolCall
                    content += toolCall
                    (obj["output"] as? JsonPrimitive)?.contentOrNull?.let { output ->
                        content += ContentPart.ToolResult(callId, toolName, JsonPrimitive(output), providerMetadata = providerMetadata)
                    }
                }
                "mcp_call" -> {
                    val callId = itemId ?: settings.generateId()
                    val toolName = (obj["name"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                    val mcpMetadata = settings.huggingFaceItemMetadata(callId, providerExecuted = true)
                    val toolCall = ContentPart.ToolCall(
                        toolCallId = callId,
                        toolName = toolName,
                        input = settings.huggingFaceParseToolInput(
                            (obj["arguments"] as? JsonPrimitive)?.contentOrNull,
                            aiSdkJson,
                        ),
                        providerMetadata = mcpMetadata,
                    )
                    toolCalls += toolCall
                    content += toolCall
                    (obj["output"] as? JsonPrimitive)?.contentOrNull?.let { output ->
                        content += ContentPart.ToolResult(callId, toolName, JsonPrimitive(output), providerMetadata = mcpMetadata)
                    }
                }
                "mcp_list_tools" -> {
                    val callId = itemId ?: settings.generateId()
                    val mcpMetadata = settings.huggingFaceItemMetadata(callId, providerExecuted = true)
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
                    (JsonAccess.arr(obj, "tools"))?.let { tools ->
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

        val reasonElement = (JsonAccess.obj(response, "incomplete_details"))?.get("reason")
        val incompleteReason = (reasonElement as? JsonPrimitive)?.contentOrNull
        val text = content.filterIsInstance<ContentPart.Text>().joinToString("") { it.text }
        val responseId = (response["id"] as? JsonPrimitive)?.contentOrNull
        return LanguageModelResult(
            text = text,
            toolCalls = toolCalls,
            finishReason = settings.mapHuggingFaceFinishReason(incompleteReason ?: "stop"),
            usage = settings.huggingFaceUsage(response["usage"]),
            providerMetadata = responseId?.let { ProviderMetadata.Raw(JsonObject(mapOf("huggingface" to buildJsonObject { put("responseId", JsonPrimitive(it)) }))) } ?: ProviderMetadata.None,
            content = content,
            rawFinishReason = incompleteReason,
            warnings = warnings,
            request = LanguageModelRequestMetadata(body = requestBody),
            response = LanguageModelResponseMetadata(
                id = responseId,
                timestampMillis = (response["created_at"] as? JsonPrimitive)?.longOrNull?.times(1000),
                modelId = (response["model"] as? JsonPrimitive)?.contentOrNull,
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
            headers = settings.huggingFaceHeaders(headers) + (HttpHeaders.Accept to "text/event-stream"),
            body = body,
            json = aiSdkJson,
            requestBodyValues = body,
            errorMessage = { _, parsed, raw ->
                "Hugging Face API error: ${parsed?.let(settings::huggingFaceErrorMessage) ?: raw}"
            },
            onResponse = onResponse,),
        )
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

    private fun huggingFaceProviderOptions(providerOptions: ProviderOptions): HuggingFaceResponsesSettings? {
        val element = providerOptions.toMap()["huggingface"] ?: providerOptions.toMap()["hugging-face"] ?: return null
        // Surface a malformed options block instead of getOrNull()-swallowing it to null, which
        // silently dropped EVERY hf option (instructions/reasoningEffort/metadata/strictJsonSchema).
        return try {
            aiSdkJson.decodeFromJsonElement(HuggingFaceResponsesSettings.serializer(), element)
        } catch (e: SerializationException) {
            throw InvalidArgumentError(
                "providerOptions.huggingface",
                "could not decode Hugging Face provider options: ${e.message ?: "<no message>"}",
                e,
            )
        }
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
        val type = (obj["type"] as? JsonPrimitive)?.contentOrNull ?: return emptyList()
        val events = mutableListOf<StreamEvent>()
        when (type) {
            "response.created" -> {
                val response = (JsonAccess.obj(obj, "response")) ?: return emptyList()
                responseId = (response["id"] as? JsonPrimitive)?.contentOrNull
                events += StreamEvent.ResponseMetadata(
                    id = responseId,
                    timestampMillis = (response["created_at"] as? JsonPrimitive)?.longOrNull?.times(1000),
                    modelId = (response["model"] as? JsonPrimitive)?.contentOrNull,
                )
            }
            "response.output_item.added" -> {
                val item = JsonAccess.obj(obj, "item")
                    ?: return listOf(StreamEvent.Error("Hugging Face stream protocol error: response.output_item.added missing item."))
                val itemId = (item["id"] as? JsonPrimitive)?.contentOrNull
                    ?: (obj["item_id"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val itemType = (item["type"] as? JsonPrimitive)?.contentOrNull
                when (itemType) {
                    "message" -> if ((item["role"] as? JsonPrimitive)?.contentOrNull == null || (item["role"] as? JsonPrimitive)?.contentOrNull == "assistant") {
                        events += StreamEvent.TextStart(itemId, settings.huggingFaceItemMetadata(itemId))
                    }
                    "reasoning" -> {
                        openReasoningIds += itemId
                        events += StreamEvent.ReasoningStart(itemId, settings.huggingFaceItemMetadata(itemId))
                    }
                    "function_call" -> events += StreamEvent.ToolInputStart(
                        id = (item["call_id"] as? JsonPrimitive)?.contentOrNull ?: itemId,
                        toolName = (item["name"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                    )
                    "mcp_call" -> events += StreamEvent.ToolInputStart(
                        id = itemId,
                        toolName = (item["name"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                        providerMetadata = settings.huggingFaceItemMetadata(itemId, providerExecuted = true),
                    )
                }
            }
            "response.output_item.done" -> {
                val item = JsonAccess.obj(obj, "item")
                    ?: return listOf(StreamEvent.Error("Hugging Face stream protocol error: response.output_item.done missing item."))
                val itemId = (item["id"] as? JsonPrimitive)?.contentOrNull
                    ?: (obj["item_id"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                when ((item["type"] as? JsonPrimitive)?.contentOrNull) {
                    "message" -> events += StreamEvent.TextEnd(itemId, settings.huggingFaceItemMetadata(itemId))
                    "reasoning" -> if (openReasoningIds.remove(itemId)) {
                        events += StreamEvent.ReasoningEnd(itemId, settings.huggingFaceItemMetadata(itemId))
                    }
                    "function_call" -> {
                        val callId = (item["call_id"] as? JsonPrimitive)?.contentOrNull ?: settings.generateId()
                        val toolName = (item["name"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                        events += StreamEvent.ToolInputEnd(callId)
                        events += StreamEvent.ToolCall(
                            toolCallId = callId,
                            toolName = toolName,
                            inputJson = settings.huggingFaceParseToolInput(
                                (item["arguments"] as? JsonPrimitive)?.contentOrNull,
                                json,
                            ),
                            providerMetadata = settings.huggingFaceItemMetadata(itemId),
                        )
                        (item["output"] as? JsonPrimitive)?.contentOrNull?.let { output ->
                            events += StreamEvent.ToolResult(callId, toolName, JsonPrimitive(output), providerMetadata = settings.huggingFaceItemMetadata(itemId))
                        }
                    }
                    "mcp_call" -> {
                        val toolName = (item["name"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                        val metadata = settings.huggingFaceItemMetadata(itemId, providerExecuted = true)
                        events += StreamEvent.ToolInputEnd(itemId, metadata)
                        events += StreamEvent.ToolCall(
                            toolCallId = itemId,
                            toolName = toolName,
                            inputJson = settings.huggingFaceParseToolInput(
                                (item["arguments"] as? JsonPrimitive)?.contentOrNull,
                                json,
                            ),
                            providerMetadata = metadata,
                        )
                        (item["output"] as? JsonPrimitive)?.contentOrNull?.let { output ->
                            events += StreamEvent.ToolResult(itemId, toolName, JsonPrimitive(output), providerMetadata = metadata)
                        }
                    }
                    "mcp_list_tools" -> {
                        val metadata = settings.huggingFaceItemMetadata(itemId, providerExecuted = true)
                        events += StreamEvent.ToolCall(
                            toolCallId = itemId,
                            toolName = "list_tools",
                            inputJson = buildJsonObject {
                                put("server_label", item["server_label"] ?: JsonPrimitive(""))
                            },
                            providerMetadata = metadata,
                        )
                        (JsonAccess.arr(item, "tools"))?.let { tools ->
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
                val itemId = (obj["item_id"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                events += StreamEvent.ReasoningDelta(
                    id = itemId,
                    text = (obj["delta"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                    providerMetadata = settings.huggingFaceItemMetadata(itemId),
                )
            }
            "response.reasoning_summary_text.done", "response.reasoning_text.done" -> {
                val itemId = (obj["item_id"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                openReasoningIds.remove(itemId)
                events += StreamEvent.ReasoningEnd(itemId, settings.huggingFaceItemMetadata(itemId))
            }
            "response.output_text.delta" -> {
                val itemId = (obj["item_id"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                events += StreamEvent.TextDelta(
                    id = itemId,
                    text = (obj["delta"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                    providerMetadata = settings.huggingFaceItemMetadata(itemId),
                )
            }
            "response.completed",
            "response.incomplete",
            -> {
                val response = (JsonAccess.obj(obj, "response")) ?: JsonObject(emptyMap())
                responseId = (response["id"] as? JsonPrimitive)?.contentOrNull ?: responseId
                val reasonElement = (JsonAccess.obj(response, "incomplete_details"))?.get("reason")
                val reason = (reasonElement as? JsonPrimitive)?.contentOrNull ?: "stop"
                rawFinishReason = reason
                finishReason = settings.mapHuggingFaceFinishReason(reason)
                usage = settings.huggingFaceUsage(response["usage"])
            }
            "response.failed" -> {
                val response = (JsonAccess.obj(obj, "response")) ?: JsonObject(emptyMap())
                responseId = (response["id"] as? JsonPrimitive)?.contentOrNull ?: responseId
                finishReason = FinishReason.Error
                usage = settings.huggingFaceUsage(response["usage"])
                events += StreamEvent.Error(
                    response["error"]?.let(settings::huggingFaceErrorMessage) ?: "Hugging Face Responses stream failed",
                )
            }
        }
        return events
    }

    fun finish(): List<StreamEvent> = buildList {
        openReasoningIds.toList().forEach { id -> add(StreamEvent.ReasoningEnd(id, settings.huggingFaceItemMetadata(id))) }
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
