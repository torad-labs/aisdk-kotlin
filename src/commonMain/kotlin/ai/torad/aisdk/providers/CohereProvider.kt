package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import io.ktor.client.HttpClient
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

public const val COHERE_VERSION: String = "3.0.36"
private const val COHERE_MAX_EMBEDDINGS_PER_CALL: Int = 96

public typealias CohereChatModelOptions = CohereLanguageModelOptions
public typealias CohereRerankingOptions = CohereRerankingModelOptions

@Serializable
public data class CohereProviderSettings(
    val baseURL: String = "https://api.cohere.com/v2",
    val apiKey: String? = null,
    val headers: Map<String, String> = emptyMap(),
) {
    internal fun cohereHeaders(callHeaders: Map<String, String>): Map<String, String> {
        val base = linkedMapOf<String, String?>()
        apiKey?.takeIf { it.isNotBlank() }?.let { base[HttpHeaders.Authorization] = "Bearer $it" }
        headers.forEach { (key, value) -> base[key] = value }
        callHeaders.forEach { (key, value) -> base[key] = value }
        return ProviderHeaders.withUserAgentSuffix(base, "ai-sdk/cohere/$COHERE_VERSION")
    }

    internal suspend fun coherePostJson(
        client: HttpClient,
        url: String,
        body: JsonObject,
        headers: Map<String, String>,
    ): HttpJsonResponse =
        HttpTransport.requestJson(
            client = client,
            url = url,
            method = HttpMethod.Post,
            headers = headers,
            body = body,
            requestBodyValues = body,
            errorMessage = this::cohereErrorMessage,
        )

    internal fun cohereOptions(providerOptions: ProviderOptions): JsonObject =
        providerOptions.toMap()["cohere"] as? JsonObject ?: JsonObject(emptyMap())

    private fun cohereErrorMessage(statusCode: Int, parsed: JsonElement?, raw: String): String {
        val obj = parsed as? JsonObject
        val detail = (obj?.get("message") as? JsonPrimitive)?.contentOrNull
            ?: (obj?.get("error") as? JsonPrimitive)?.contentOrNull
            ?: raw.ifBlank { "request failed" }
        return "Cohere request failed ($statusCode): $detail"
    }
}

@Serializable
public data class CohereLanguageModelOptions(
    val thinking: CohereThinkingOptions? = null,
)

@Serializable
public data class CohereThinkingOptions(
    val type: String? = null,
    val tokenBudget: Int? = null,
)

@Serializable
public data class CohereEmbeddingModelOptions(
    val inputType: String? = null,
    val truncate: String? = null,
    val outputDimension: Int? = null,
)

@Serializable
public data class CohereRerankingModelOptions(
    val maxTokensPerDoc: Int? = null,
    val priority: Int? = null,
)

public class CohereProvider(
    private val client: HttpClient,
    public val settings: CohereProviderSettings,
) : Provider {
    override val providerId: String = "cohere"

    public operator fun invoke(modelId: ModelId): LanguageModel = languageModel(modelId.value)

    override fun languageModel(modelId: String): LanguageModel =
        CohereChatLanguageModel(client, settings, modelId)

    public fun embedding(modelId: ModelId): EmbeddingModel =
        CohereEmbeddingModel(client, settings, modelId.value)

    public fun textEmbedding(modelId: ModelId): EmbeddingModel = embedding(modelId)
    public fun textEmbeddingModel(modelId: ModelId): EmbeddingModel = embedding(modelId)

    public fun reranking(modelId: ModelId): RerankingModel =
        CohereRerankingModel(client, settings, modelId.value)

    override fun embeddingModel(modelId: String): EmbeddingModel = embedding(ModelId(modelId))
    override fun rerankingModel(modelId: String): RerankingModel = reranking(ModelId(modelId))

    override fun imageModel(modelId: String): ImageModel =
        throw NoSuchModelError(providerId, "imageModel", modelId)
}

/** PascalCase factory — mirrors the OpenAI(...) reference pattern. */
public fun Cohere(
    client: HttpClient,
    settings: CohereProviderSettings = CohereProviderSettings(),
): CohereProvider = CohereProvider(client, settings)

private class CohereChatLanguageModel(
    private val client: HttpClient,
    private val settings: CohereProviderSettings,
    override val modelId: String,
) : LanguageModel {
    override val provider: String = "cohere.chat"
    override val supportedUrls: Map<String, List<String>> = mapOf("image/*" to listOf("^https?://.*$"))

    override suspend fun generate(params: LanguageModelCallParams): LanguageModelResult {
        val request = cohereChatRequest(modelId, params)
        val response = settings.coherePostJson(
            client = client,
            url = "${settings.baseURL.trimEnd('/')}/chat",
            body = request.body,
            headers = settings.cohereHeaders(params.headers),
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
        emit(
            StreamEvent.Finish(
                totalSteps = 1,
                finishReason = result.finishReason,
                usage = result.usage,
                rawFinishReason = result.rawFinishReason,
            ),
        )
    }

    private fun cohereChatRequest(modelId: String, params: LanguageModelCallParams): CohereChatRequest {
        val options = settings.cohereOptions(params.providerOptions)
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
        put("id", JsonPrimitive(call.toolCallId))
        put("type", JsonPrimitive("function"))
        put("function", buildJsonObject {
            put("name", JsonPrimitive(call.toolName))
            put("arguments", JsonPrimitive(aiSdkJson.encodeToString(JsonElement.serializer(), call.input)))
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
        if (value is JsonPrimitive) value.contentOrNull ?: value.toString() else aiSdkJson.encodeToString(JsonElement.serializer(), value)

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
                val detail = (providerMetadata.toMap()["cohere"] as? JsonObject)?.get("detail") as? JsonPrimitive
                detail?.contentOrNull?.let { put("detail", JsonPrimitive(it)) }
            },
        )
    }

    private fun cohereDocumentPart(part: ContentPart.File): JsonObject = buildJsonObject {
        put("data", buildJsonObject {
            put(
                "text",
                JsonPrimitive(
                    runCatching { Base64Codec.decode(part.base64).decodeToString() }.getOrElse {
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
            put("parameters", aiSdkJson.parseToJsonElement(tool.parametersSchemaJson))
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

    // Parse one Cohere tool_call array element, skipping a non-object element (Wave 7b). Extracted
    // so cohereChatResult stays under the cyclomatic-complexity threshold after the skip guards.
    private fun cohereToolCallPart(call: JsonElement): ContentPart.ToolCall? {
        val obj = call as? JsonObject ?: return null
        val function = (obj["function"] as? JsonObject) ?: JsonObject(emptyMap())
        return ContentPart.ToolCall(
            toolCallId = (obj["id"] as? JsonPrimitive)?.contentOrNull ?: IdGenerator.generate("call"),
            toolName = (function["name"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            input = cohereToolInput((function["arguments"] as? JsonPrimitive)?.contentOrNull),
        )
    }

    // Parse one Cohere citation array element, skipping a non-object element (Wave 7b).
    private fun cohereCitationPart(citation: JsonElement): ContentPart.Source? {
        val obj = citation as? JsonObject ?: return null
        val sourceObj = (obj["sources"] as? JsonArray)?.firstOrNull() as? JsonObject
        val documentObj = sourceObj?.get("document") as? JsonObject
        return ContentPart.Source(
            sourceType = StreamEvent.SourcePart.SourceType.Document,
            title = (documentObj?.get("title") as? JsonPrimitive)?.contentOrNull ?: "Document",
            providerMetadata = ProviderMetadata.Raw(JsonObject(mapOf("cohere" to obj))),
        )
    }

    private fun cohereChatResult(
        value: JsonObject,
        requestBody: JsonObject,
        headers: Map<String, String>,
        responseBody: JsonElement,
        warnings: List<CallWarning>,
    ): LanguageModelResult {
        val message = (value["message"] as? JsonObject) ?: JsonObject(emptyMap())
        val content = mutableListOf<ContentPart>()
        val text = (message["content"] as? JsonArray).orEmpty().joinToString("") { part ->
            val obj = part as? JsonObject ?: return@joinToString ""
            when ((obj["type"] as? JsonPrimitive)?.contentOrNull) {
                "text" -> (obj["text"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                else -> ""
            }
        }
        if (text.isNotEmpty()) content += ContentPart.Text(text)
        (message["content"] as? JsonArray).orEmpty().forEach { part ->
            val obj = part as? JsonObject ?: return@forEach
            if ((obj["type"] as? JsonPrimitive)?.contentOrNull == "thinking") {
                (obj["thinking"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }?.let {
                    content += ContentPart.Reasoning(it)
                }
            }
        }
        (message["tool_plan"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let {
            content += ContentPart.Reasoning(it)
        }
        val toolCalls = (message["tool_calls"] as? JsonArray).orEmpty().mapNotNull(::cohereToolCallPart)
        content += toolCalls
        content += (message["citations"] as? JsonArray).orEmpty().mapNotNull(::cohereCitationPart)
        return LanguageModelResult(
            text = text,
            toolCalls = toolCalls,
            finishReason = cohereFinishReason((value["finish_reason"] as? JsonPrimitive)?.contentOrNull),
            usage = cohereUsage((value["usage"] as? JsonObject)),
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

    private fun cohereToolInput(value: String?): JsonElement {
        val normalized = if (value == "null") "{}" else value
        return if (normalized.isNullOrBlank()) JsonObject(emptyMap()) else runCatching {
            aiSdkJson.parseToJsonElement(normalized)
        }.getOrElse { JsonPrimitive(normalized) }
    }

    private fun cohereUsage(value: JsonObject?): Usage {
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

    private fun cohereFinishReason(value: String?): FinishReason = when (value) {
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

private class CohereEmbeddingModel(
    private val client: HttpClient,
    private val settings: CohereProviderSettings,
    override val modelId: String,
) : EmbeddingModel {
    override val provider: String = "cohere.textEmbedding"
    override val maxEmbeddingsPerCall: Int = COHERE_MAX_EMBEDDINGS_PER_CALL
    override val supportsParallelCalls: Boolean = true

    override suspend fun embed(params: EmbeddingModelCallParams): EmbeddingModelResult {
        if (params.values.size > COHERE_MAX_EMBEDDINGS_PER_CALL) {
            throw TooManyEmbeddingValuesForCallError(
                provider = provider,
                modelId = modelId,
                maxEmbeddingsPerCall = COHERE_MAX_EMBEDDINGS_PER_CALL,
                values = params.values,
            )
        }
        val options = settings.cohereOptions(params.providerOptions)
        val body = buildJsonObject {
            put("model", JsonPrimitive(modelId))
            put("texts", JsonArray(params.values.map(::JsonPrimitive)))
            put("embedding_types", JsonArray(listOf(JsonPrimitive("float"))))
            put("input_type", options["inputType"] ?: JsonPrimitive("search_query"))
            (options["truncate"] ?: params.truncate?.let { JsonPrimitive(if (it) "END" else "NONE") })?.let { put("truncate", it) }
            options["outputDimension"]?.let { put("output_dimension", it) }
        }
        val response = settings.coherePostJson(
            client = client,
            url = "${settings.baseURL.trimEnd('/')}/embed",
            body = body,
            headers = settings.cohereHeaders(params.headers),
        )
        val value = response.value.jsonObject
        val embeddings = ((value["embeddings"] as? JsonObject)?.get("float") as? JsonArray).orEmpty()
            .map { row -> (row as? JsonArray).orEmpty().map { WireDecoder.embeddingFloat(it, provider) } }
        val billedUnits = ((value["meta"] as? JsonObject)?.get("billed_units") as? JsonObject)
        val usage = (billedUnits?.get("input_tokens") as? JsonPrimitive)?.intOrNull ?: 0
        return EmbeddingModelResult(
            embeddings = embeddings,
            usage = EmbeddingUsage(tokens = usage, raw = value["meta"]),
            request = LanguageModelRequestMetadata(body),
            response = LanguageModelResponseMetadata(
                id = (value["id"] as? JsonPrimitive)?.contentOrNull,
                headers = response.headers,
                body = response.value,
            ),
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
        val options = settings.cohereOptions(params.providerOptions)
        val body = buildJsonObject {
            put("model", JsonPrimitive(modelId))
            put("query", JsonPrimitive(params.query))
            put("documents", JsonArray(params.documents.map(::JsonPrimitive)))
            params.topN?.let { put("top_n", JsonPrimitive(it)) }
            options["maxTokensPerDoc"]?.let { put("max_tokens_per_doc", it) }
            options["priority"]?.let { put("priority", it) }
        }
        val response = settings.coherePostJson(
            client = client,
            url = "${settings.baseURL.trimEnd('/')}/rerank",
            body = body,
            headers = settings.cohereHeaders(params.headers),
        )
        val value = response.value.jsonObject
        val results = (value["results"] as? JsonArray).orEmpty().mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val index = (obj["index"] as? JsonPrimitive)?.intOrNull ?: 0
            RerankedItem(
                value = params.documents.getOrElse(index) { "" },
                score = (obj["relevance_score"] as? JsonPrimitive)?.floatOrNull ?: 0f,
                index = index,
            )
        }
        val billedUnits = ((value["meta"] as? JsonObject)?.get("billed_units") as? JsonObject)
        val searchUnits = (billedUnits?.get("search_units") as? JsonPrimitive)?.intOrNull ?: 0
        return RerankingModelResult(
            results = results,
            usage = Usage.of(promptTokens = searchUnits, completionTokens = 0),
            response = LanguageModelResponseMetadata(
                id = (value["id"] as? JsonPrimitive)?.contentOrNull,
                headers = response.headers,
                body = response.value,
            ),
        )
    }
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
