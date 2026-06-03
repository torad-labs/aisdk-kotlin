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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

const val COHERE_VERSION: String = "3.0.36"

typealias CohereChatModelId = String
typealias CohereEmbeddingModelId = String
typealias CohereRerankingModelId = String
typealias CohereChatModelOptions = CohereLanguageModelOptions
typealias CohereRerankingOptions = CohereRerankingModelOptions

@Serializable
data class CohereProviderSettings(
    val baseURL: String = "https://api.cohere.com/v2",
    val apiKey: String? = null,
    val headers: Map<String, String> = emptyMap(),
)

@Serializable
data class CohereLanguageModelOptions(
    val thinking: CohereThinkingOptions? = null,
)

@Serializable
data class CohereThinkingOptions(
    val type: String? = null,
    val tokenBudget: Int? = null,
)

@Serializable
data class CohereEmbeddingModelOptions(
    val inputType: String? = null,
    val truncate: String? = null,
    val outputDimension: Int? = null,
)

@Serializable
data class CohereRerankingModelOptions(
    val maxTokensPerDoc: Int? = null,
    val priority: Int? = null,
)

interface CohereProvider : Provider {
    val settings: CohereProviderSettings

    operator fun invoke(modelId: CohereChatModelId): LanguageModel = languageModel(modelId)
    fun embedding(modelId: CohereEmbeddingModelId): EmbeddingModel
    fun textEmbedding(modelId: CohereEmbeddingModelId): EmbeddingModel = embedding(modelId)
    fun textEmbeddingModel(modelId: CohereEmbeddingModelId): EmbeddingModel = embedding(modelId)
    fun reranking(modelId: CohereRerankingModelId): RerankingModel

    override fun embeddingModel(modelId: String): EmbeddingModel = embedding(modelId)
    override fun rerankingModel(modelId: String): RerankingModel = reranking(modelId)
}

fun createCohere(
    client: HttpClient,
    settings: CohereProviderSettings = CohereProviderSettings(),
): CohereProvider = DefaultCohereProvider(client, settings)

val cohere: CohereProvider = object : CohereProvider {
    override val providerId: String = "cohere"
    override val settings: CohereProviderSettings = CohereProviderSettings()
    override fun languageModel(modelId: String): LanguageModel =
        throw AiSdkException("Cohere provider is not configured. Use createCohere(client, settings).")
    override fun embedding(modelId: String): EmbeddingModel =
        throw AiSdkException("Cohere provider is not configured. Use createCohere(client, settings).")
    override fun reranking(modelId: String): RerankingModel =
        throw AiSdkException("Cohere provider is not configured. Use createCohere(client, settings).")
}

private class DefaultCohereProvider(
    private val client: HttpClient,
    override val settings: CohereProviderSettings,
) : CohereProvider {
    override val providerId: String = "cohere"

    override fun languageModel(modelId: String): LanguageModel =
        CohereChatLanguageModel(client, settings, modelId)

    override fun embedding(modelId: String): EmbeddingModel =
        CohereEmbeddingModel(client, settings, modelId)

    override fun reranking(modelId: String): RerankingModel =
        CohereRerankingModel(client, settings, modelId)

    override fun imageModel(modelId: String): ImageModel =
        throw NoSuchModelError(providerId, "imageModel", modelId)
}

private class CohereChatLanguageModel(
    private val client: HttpClient,
    private val settings: CohereProviderSettings,
    override val modelId: String,
) : LanguageModel {
    override val provider: String = "cohere.chat"
    override val supportedUrls: Map<String, List<String>> = mapOf("image/*" to listOf("^https?://.*$"))

    override suspend fun generate(params: LanguageModelCallParams): LanguageModelResult {
        val request = cohereChatRequest(modelId, params)
        val response = coherePostJson(
            client = client,
            url = "${settings.baseURL.trimEnd('/')}/chat",
            body = request.body,
            headers = cohereHeaders(settings, params.headers),
        )
        return cohereChatResult(response.value.jsonObject, request.body, response.headers, response.value, request.warnings)
    }

    override fun stream(params: LanguageModelCallParams): Flow<StreamEvent> = flow {
        val result = generate(params)
        emit(StreamEvent.StreamStart(result.warnings))
        if (result.text.isNotEmpty()) {
            emit(StreamEvent.TextStart("txt-0"))
            emit(StreamEvent.TextDelta("txt-0", result.text))
            emit(StreamEvent.TextEnd("txt-0"))
        }
        result.content.filterIsInstance<ContentPart.Reasoning>().forEachIndexed { index, reasoning ->
            val id = "rsn-$index"
            emit(StreamEvent.ReasoningStart(id, reasoning.providerMetadata))
            emit(StreamEvent.ReasoningDelta(id, reasoning.text, reasoning.providerMetadata))
            emit(StreamEvent.ReasoningEnd(id, reasoning.providerMetadata))
        }
        result.toolCalls.forEach {
            emit(StreamEvent.ToolInputStart(it.toolCallId, it.toolName, it.providerMetadata))
            emit(StreamEvent.ToolInputDelta(it.toolCallId, it.input.toString(), it.providerMetadata))
            emit(StreamEvent.ToolInputEnd(it.toolCallId, it.providerMetadata))
            emit(StreamEvent.ToolCall(it.toolCallId, it.toolName, it.input, it.providerMetadata))
        }
        emit(StreamEvent.Finish(totalSteps = 1, finishReason = result.finishReason, usage = result.usage))
    }
}

private class CohereEmbeddingModel(
    private val client: HttpClient,
    private val settings: CohereProviderSettings,
    override val modelId: String,
) : EmbeddingModel {
    override val provider: String = "cohere.textEmbedding"

    override suspend fun embed(params: EmbeddingModelCallParams): EmbeddingModelResult {
        val options = cohereOptions(params.providerOptions)
        val body = buildJsonObject {
            put("model", JsonPrimitive(modelId))
            put("texts", JsonArray(params.values.map(::JsonPrimitive)))
            put("embedding_types", JsonArray(listOf(JsonPrimitive("float"))))
            put("input_type", options["inputType"] ?: JsonPrimitive("search_query"))
            (options["truncate"] ?: params.truncate?.let { JsonPrimitive(if (it) "END" else "NONE") })?.let { put("truncate", it) }
            options["outputDimension"]?.let { put("output_dimension", it) }
        }
        val response = coherePostJson(
            client = client,
            url = "${settings.baseURL.trimEnd('/')}/embed",
            body = body,
            headers = cohereHeaders(settings, params.headers),
        )
        val value = response.value.jsonObject
        val embeddings = value["embeddings"]?.jsonObject?.get("float")?.jsonArray.orEmpty()
            .map { row -> row.jsonArray.map { embeddingFloat(it, provider) } }
        val usage = value["meta"]?.jsonObject
            ?.get("billed_units")?.jsonObject
            ?.get("input_tokens")?.jsonPrimitive?.intOrNull ?: 0
        return EmbeddingModelResult(
            embeddings = embeddings,
            usage = EmbeddingUsage(tokens = usage, raw = value["meta"]),
            request = LanguageModelRequestMetadata(body),
            response = LanguageModelResponseMetadata(id = value["id"]?.jsonPrimitive?.contentOrNull, headers = response.headers, body = response.value),
        )
    }
}

private class CohereRerankingModel(
    private val client: HttpClient,
    private val settings: CohereProviderSettings,
    override val modelId: String,
) : RerankingModel {
    override val provider: String = "cohere.reranking"

    override suspend fun rerank(params: RerankingParams): RerankingModelResult {
        val options = cohereOptions(params.providerOptions)
        val body = buildJsonObject {
            put("model", JsonPrimitive(modelId))
            put("query", JsonPrimitive(params.query))
            put("documents", JsonArray(params.documents.map(::JsonPrimitive)))
            params.topN?.let { put("top_n", JsonPrimitive(it)) }
            options["maxTokensPerDoc"]?.let { put("max_tokens_per_doc", it) }
            options["priority"]?.let { put("priority", it) }
        }
        val response = coherePostJson(
            client = client,
            url = "${settings.baseURL.trimEnd('/')}/rerank",
            body = body,
            headers = cohereHeaders(settings, params.headers),
        )
        val value = response.value.jsonObject
        val results = value["results"]?.jsonArray.orEmpty().map { item ->
            val obj = item.jsonObject
            val index = obj["index"]?.jsonPrimitive?.intOrNull ?: 0
            RerankedItem(
                value = params.documents.getOrElse(index) { "" },
                score = obj["relevance_score"]?.jsonPrimitive?.floatOrNull ?: 0f,
                index = index,
            )
        }
        val searchUnits = value["meta"]?.jsonObject
            ?.get("billed_units")?.jsonObject
            ?.get("search_units")?.jsonPrimitive?.intOrNull ?: 0
        return RerankingModelResult(
            results = results,
            usage = Usage(promptTokens = searchUnits, completionTokens = 0),
            response = LanguageModelResponseMetadata(id = value["id"]?.jsonPrimitive?.contentOrNull, headers = response.headers, body = response.value),
        )
    }
}

private val cohereJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
}

private data class CohereJsonResponse(
    val value: JsonElement,
    val headers: Map<String, String>,
)

private data class CohereChatRequest(
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

private fun cohereChatRequest(modelId: String, params: LanguageModelCallParams): CohereChatRequest {
    val options = cohereOptions(params.providerOptions)
    val prompt = coherePrompt(params.messages)
    val toolConfig = cohereTools(params.tools, params.toolChoice)
    val body = buildJsonObject {
        put("model", JsonPrimitive(modelId))
        put("messages", JsonArray(prompt.messages))
        params.maxOutputTokens?.let { put("max_tokens", JsonPrimitive(it)) }
        params.temperature?.let { put("temperature", JsonPrimitive(it)) }
        params.topP?.let { put("p", JsonPrimitive(it)) }
        params.topK?.let { put("k", JsonPrimitive(it)) }
        params.frequencyPenalty?.let { put("frequency_penalty", JsonPrimitive(it)) }
        params.presencePenalty?.let { put("presence_penalty", JsonPrimitive(it)) }
        params.seed?.let { put("seed", JsonPrimitive(it)) }
        if (params.stopSequences.isNotEmpty()) put("stop_sequences", JsonArray(params.stopSequences.map(::JsonPrimitive)))
        cohereResponseFormat(params.responseFormat)?.let { put("response_format", it) }
        if (toolConfig.tools.isNotEmpty()) put("tools", JsonArray(toolConfig.tools))
        toolConfig.toolChoice?.let { put("tool_choice", it) }
        if (prompt.documents.isNotEmpty()) put("documents", JsonArray(prompt.documents))
        (options["thinking"] as? JsonObject)?.let { thinking ->
            put("thinking", buildJsonObject {
                put("type", thinking["type"] ?: JsonPrimitive("enabled"))
                thinking["tokenBudget"]?.let { put("token_budget", it) }
            })
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
                parts += cohereImagePart(part.mediaType, part.base64, part.providerMetadata)
            } else if (part.mediaType.isCohereDocumentMediaType()) {
                documents += cohereDocumentPart(part)
            } else {
                throw InvalidArgumentError(
                    "messages",
                    "Cohere supports image files, text/* documents, and application/json documents; got ${part.mediaType}.",
                )
            }
            is ContentPart.Image -> parts += cohereImagePart(part.mediaType, part.base64, part.providerMetadata)
            is ContentPart.Source -> warnings += CallWarning(
                type = "unsupported",
                message = "Cohere chat prompt conversion ignores source content parts.",
            )
            is ContentPart.Reasoning,
            is ContentPart.ToolCall,
            is ContentPart.ToolResult,
            is ContentPart.ToolApprovalRequest,
            is ContentPart.ToolApprovalResponse,
            -> Unit
        }
    }
    val hasImage = parts.any { it["type"]?.jsonPrimitive?.contentOrNull == "image_url" }
    return buildJsonObject {
        put("role", JsonPrimitive("user"))
        put(
            "content",
            if (hasImage) {
                JsonArray(parts)
            } else {
                JsonPrimitive(parts.joinToString("") { it["text"]?.jsonPrimitive?.contentOrNull.orEmpty() })
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
            put("tool_calls", JsonArray(toolCalls.map(::cohereAssistantToolCall)))
        }
    }
}

private fun cohereAssistantToolCall(call: ContentPart.ToolCall): JsonObject = buildJsonObject {
    put("id", JsonPrimitive(call.toolCallId))
    put("type", JsonPrimitive("function"))
    put("function", buildJsonObject {
        put("name", JsonPrimitive(call.toolName))
        put("arguments", JsonPrimitive(cohereJson.encodeToString(JsonElement.serializer(), call.input)))
    })
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
    if (value is JsonPrimitive) value.contentOrNull ?: value.toString() else cohereJson.encodeToString(JsonElement.serializer(), value)

private fun cohereImagePart(
    mediaType: String,
    base64: String,
    providerMetadata: Map<String, JsonElement>?,
): JsonObject = buildJsonObject {
    put("type", JsonPrimitive("image_url"))
    put("image_url", buildJsonObject {
        put("url", JsonPrimitive("data:${mediaType.normalizeCohereImageMediaType()};base64,$base64"))
        (providerMetadata?.get("cohere") as? JsonObject)
            ?.get("detail")
            ?.jsonPrimitive
            ?.contentOrNull
            ?.let { put("detail", JsonPrimitive(it)) }
    })
}

private fun cohereDocumentPart(part: ContentPart.File): JsonObject = buildJsonObject {
    put("data", buildJsonObject {
        put(
            "text",
            JsonPrimitive(
                runCatching { convertBase64ToByteArray(part.base64).decodeToString() }.getOrElse {
                    throw InvalidArgumentError("messages", "Cohere document file content must be valid base64.")
                },
            ),
        )
        part.filename?.let { put("title", JsonPrimitive(it)) }
    })
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
    put("function", buildJsonObject {
        put("name", JsonPrimitive(tool.name))
        put("description", JsonPrimitive(tool.description))
        put("parameters", cohereJson.parseToJsonElement(tool.parametersSchemaJson))
    })
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

private fun cohereChatResult(
    value: JsonObject,
    requestBody: JsonObject,
    headers: Map<String, String>,
    responseBody: JsonElement,
    warnings: List<CallWarning>,
): LanguageModelResult {
    val message = value["message"]?.jsonObject ?: JsonObject(emptyMap())
    val content = mutableListOf<ContentPart>()
    val text = message["content"]?.jsonArray.orEmpty().joinToString("") { part ->
        val obj = part.jsonObject
        when (obj["type"]?.jsonPrimitive?.contentOrNull) {
            "text" -> obj["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
            else -> ""
        }
    }
    if (text.isNotEmpty()) content += ContentPart.Text(text)
    message["content"]?.jsonArray.orEmpty().forEach { part ->
        val obj = part.jsonObject
        if (obj["type"]?.jsonPrimitive?.contentOrNull == "thinking") {
            obj["thinking"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }?.let {
                content += ContentPart.Reasoning(it)
            }
        }
    }
    message["tool_plan"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let {
        content += ContentPart.Reasoning(it)
    }
    val toolCalls = message["tool_calls"]?.jsonArray.orEmpty().map { call ->
        val obj = call.jsonObject
        val function = obj["function"]?.jsonObject ?: JsonObject(emptyMap())
        ContentPart.ToolCall(
            toolCallId = obj["id"]?.jsonPrimitive?.contentOrNull ?: generateId("call"),
            toolName = function["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            input = cohereToolInput(function["arguments"]?.jsonPrimitive?.contentOrNull),
        )
    }
    content += toolCalls
    message["citations"]?.jsonArray.orEmpty().forEach { citation ->
        val obj = citation.jsonObject
        content += ContentPart.Source(
            sourceType = StreamEvent.SourcePart.SourceType.Document,
            title = obj["sources"]?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("document")?.jsonObject
                ?.get("title")?.jsonPrimitive?.contentOrNull ?: "Document",
            providerMetadata = mapOf("cohere" to obj),
        )
    }
    return LanguageModelResult(
        text = text,
        toolCalls = toolCalls,
        finishReason = cohereFinishReason(value["finish_reason"]?.jsonPrimitive?.contentOrNull),
        usage = cohereUsage(value["usage"]?.jsonObject),
        content = content,
        rawFinishReason = value["finish_reason"]?.jsonPrimitive?.contentOrNull,
        request = LanguageModelRequestMetadata(requestBody),
        response = LanguageModelResponseMetadata(
            id = value["generation_id"]?.jsonPrimitive?.contentOrNull ?: value["id"]?.jsonPrimitive?.contentOrNull,
            headers = headers,
            body = responseBody,
        ),
        warnings = warnings,
    )
}

private fun cohereToolInput(value: String?): JsonElement {
    val normalized = if (value == "null") "{}" else value
    return if (normalized.isNullOrBlank()) JsonObject(emptyMap()) else runCatching {
        cohereJson.parseToJsonElement(normalized)
    }.getOrElse { JsonPrimitive(normalized) }
}

private fun cohereUsage(value: JsonObject?): Usage {
    val tokens = value?.get("tokens")?.jsonObject
    val input = tokens?.get("input_tokens")?.jsonPrimitive?.intOrNull ?: 0
    val output = tokens?.get("output_tokens")?.jsonPrimitive?.intOrNull ?: 0
    return Usage(
        inputTokens = Usage.InputTokenBreakdown(
            total = input,
            noCache = input,
        ),
        outputTokens = Usage.OutputTokenBreakdown(total = output, text = output),
        raw = tokens,
    )
}

private fun cohereFinishReason(value: String?): FinishReason = when (value) {
    "COMPLETE", "stop" -> FinishReason.Stop
    "MAX_TOKENS" -> FinishReason.Length
    "TOOL_CALL" -> FinishReason.ToolCalls
    "ERROR_TOXIC" -> FinishReason.ContentFilter
    "ERROR" -> FinishReason.Error
    else -> FinishReason.Other
}

private suspend fun coherePostJson(
    client: HttpClient,
    url: String,
    body: JsonObject,
    headers: Map<String, String>,
): CohereJsonResponse {
    val response = client.request(url) {
        method = HttpMethod.Post
        contentType(ContentType.Application.Json)
        headers.forEach { (name, value) -> header(name, value) }
        setBody(cohereJson.encodeToString(JsonElement.serializer(), body))
    }
    return response.parseCohereJson()
}

private suspend fun HttpResponse.parseCohereJson(): CohereJsonResponse {
    val raw = bodyAsText()
    if (status.value !in 200..299) {
        throw AiSdkException("Cohere request failed (${status.value}): ${cohereErrorMessage(raw)}")
    }
    return CohereJsonResponse(
        value = if (raw.isBlank()) JsonObject(emptyMap()) else cohereJson.parseToJsonElement(raw),
        headers = headers.entries().associate { it.key to it.value.joinToString(",") },
    )
}

private fun cohereHeaders(settings: CohereProviderSettings, callHeaders: Map<String, String>): Map<String, String> {
    val base = linkedMapOf<String, String?>()
    settings.apiKey?.takeIf { it.isNotBlank() }?.let { base[HttpHeaders.Authorization] = "Bearer $it" }
    settings.headers.forEach { (key, value) -> base[key] = value }
    callHeaders.forEach { (key, value) -> base[key] = value }
    return withUserAgentSuffix(base, "ai-sdk/cohere/$COHERE_VERSION")
}

private fun cohereOptions(providerOptions: Map<String, JsonElement>): JsonObject =
    providerOptions["cohere"] as? JsonObject ?: JsonObject(emptyMap())

private fun cohereErrorMessage(raw: String): String {
    val obj = runCatching { cohereJson.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return raw.ifBlank { "request failed" }
    return obj["message"]?.jsonPrimitive?.contentOrNull
        ?: obj["error"]?.jsonPrimitive?.contentOrNull
        ?: raw.ifBlank { "request failed" }
}

private fun List<ContentPart>.textContent(): String =
    filterIsInstance<ContentPart.Text>().joinToString("") { it.text }
