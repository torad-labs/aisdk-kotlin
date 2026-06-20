package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.readLine
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
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
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

public const val AMAZON_BEDROCK_VERSION: String = "4.0.112"

public typealias BedrockChatModelId = String
public typealias BedrockEmbeddingModelId = String
public typealias BedrockImageModelId = String
public typealias BedrockRerankingModelId = String
public typealias BedrockProviderOptions = JsonObject
public typealias AmazonBedrockLanguageModelOptions = JsonObject
public typealias AmazonBedrockEmbeddingModelOptions = JsonObject
public typealias AmazonBedrockRerankingModelOptions = JsonObject
public typealias BedrockRerankingOptions = JsonObject
public typealias BedrockAnthropicModelId = String
public typealias BedrockMantleModelId = String
public typealias BedrockMantleChatModelId = String
public typealias BedrockMantleResponsesModelId = String

@Serializable
public data class BedrockCredentials(
    val accessKeyId: String,
    val secretAccessKey: String,
    val sessionToken: String? = null,
    val region: String? = null,
)

public data class AmazonBedrockProviderSettings(
    val region: String? = null,
    val apiKey: String? = null,
    val accessKeyId: String? = null,
    val secretAccessKey: String? = null,
    val sessionToken: String? = null,
    val credentialProvider: (suspend () -> BedrockCredentials)? = null,
    val baseURL: String? = null,
    val agentBaseURL: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val generateId: () -> String = { IdGenerator.generate() },
)

/** Default Bedrock output budget added to the thinking budget when maxOutputTokens is unset. */
private const val DEFAULT_BEDROCK_MAX_TOKENS: Int = 4096

public class AmazonBedrockProvider(
    private val client: HttpClient,
    public val settings: AmazonBedrockProviderSettings,
) : Provider {
    override val providerId: String = "amazon-bedrock"
    public val tools: AnthropicTools = anthropicTools

    public operator fun invoke(modelId: BedrockChatModelId): LanguageModel = languageModel(modelId)

    override fun languageModel(modelId: String): LanguageModel =
        BedrockChatLanguageModel(client, settings, modelId, "amazon-bedrock")

    public fun embedding(modelId: BedrockEmbeddingModelId): EmbeddingModel =
        BedrockEmbeddingModel(client, settings, modelId)

    public fun textEmbedding(modelId: BedrockEmbeddingModelId): EmbeddingModel = embedding(modelId)
    public fun textEmbeddingModel(modelId: BedrockEmbeddingModelId): EmbeddingModel = embedding(modelId)

    public fun image(modelId: BedrockImageModelId): ImageModel =
        BedrockImageModel(client, settings, modelId)

    public fun reranking(modelId: BedrockRerankingModelId): RerankingModel =
        BedrockRerankingModel(client, settings, modelId)

    override fun embeddingModel(modelId: String): EmbeddingModel = embedding(modelId)
    override fun imageModel(modelId: String): ImageModel = image(modelId)
    override fun rerankingModel(modelId: String): RerankingModel = reranking(modelId)
}

/** PascalCase factory — mirrors the OpenAI(...) reference faux-constructor. */
public fun AmazonBedrock(
    client: HttpClient,
    settings: AmazonBedrockProviderSettings = AmazonBedrockProviderSettings(),
): AmazonBedrockProvider = AmazonBedrockProvider(client, settings)

public typealias BedrockAnthropicProviderSettings = AmazonBedrockProviderSettings

public class BedrockAnthropicProvider(
    private val client: HttpClient,
    public val settings: AmazonBedrockProviderSettings,
) : Provider {
    override val providerId: String = "bedrock.anthropic"
    public val tools: AnthropicTools = anthropicTools

    public operator fun invoke(modelId: BedrockAnthropicModelId): LanguageModel = languageModel(modelId)

    override fun languageModel(modelId: String): LanguageModel =
        BedrockChatLanguageModel(client, settings, modelId, "bedrock.anthropic.messages")

    public fun textEmbeddingModel(modelId: String): Nothing = throw NoSuchModelError(providerId, "embeddingModel", modelId)
}

/** PascalCase factory — mirrors the OpenAI(...) reference faux-constructor. */
public fun BedrockAnthropic(
    client: HttpClient,
    settings: BedrockAnthropicProviderSettings = BedrockAnthropicProviderSettings(),
): BedrockAnthropicProvider = BedrockAnthropicProvider(client, settings)

public typealias BedrockMantleProviderSettings = AmazonBedrockProviderSettings

public class BedrockMantleProvider(
    private val client: HttpClient,
    public val settings: AmazonBedrockProviderSettings,
) : Provider {
    override val providerId: String = "bedrock-mantle"

    public operator fun invoke(modelId: BedrockMantleChatModelId): LanguageModel = chat(modelId)

    override fun languageModel(modelId: String): LanguageModel = chat(modelId)

    public fun chat(modelId: BedrockMantleChatModelId): LanguageModel =
        BedrockMantleChatLanguageModel(client, settings, modelId, "bedrock-mantle.chat", "/chat/completions")

    public fun responses(modelId: BedrockMantleResponsesModelId): LanguageModel =
        BedrockMantleChatLanguageModel(client, settings, modelId, "bedrock-mantle.responses", "/responses")

    public fun textEmbeddingModel(modelId: String): Nothing = throw NoSuchModelError(providerId, "embeddingModel", modelId)
}

/** PascalCase factory — mirrors the OpenAI(...) reference faux-constructor. */
public fun BedrockMantle(
    client: HttpClient,
    settings: BedrockMantleProviderSettings = BedrockMantleProviderSettings(),
): BedrockMantleProvider = BedrockMantleProvider(client, settings)

private class BedrockChatLanguageModel(
    private val client: HttpClient,
    private val settings: AmazonBedrockProviderSettings,
    override val modelId: String,
    override val provider: String,
) : LanguageModel {
    override suspend fun generate(params: LanguageModelCallParams): LanguageModelResult {
        val prepared = bedrockChatRequestBody(modelId, params)
        val response = bedrockPostJson(
            client = client,
            url = "${bedrockRuntimeBaseURL(settings)}/model/${bedrockEncodeModelId(modelId)}/converse",
            body = prepared.body,
            settings = settings,
            extraHeaders = params.headers,
            abortSignal = params.abortSignal,
            parseJson = true,
        )
        return bedrockChatGenerateResult(
            response = response.value.jsonObject,
            requestBody = prepared.body,
            responseHeaders = response.headers,
            responseBody = response.value,
            warnings = prepared.warnings,
            generateId = settings.generateId,
            usesJsonResponseTool = prepared.usesJsonResponseTool,
        )
    }

    override fun stream(params: LanguageModelCallParams): Flow<StreamEvent> = flow {
        val prepared = bedrockChatRequestBody(modelId, params)
        emit(StreamEvent.StreamStart(prepared.warnings))
        val state = BedrockStreamState(settings.generateId, prepared.usesJsonResponseTool)
        var sseHeaders: Map<String, String> = emptyMap()
        val payloads = bedrockStreamPayloads(
            client = client,
            url = "${bedrockRuntimeBaseURL(settings)}/model/${bedrockEncodeModelId(modelId)}/converse-stream",
            body = prepared.body,
            settings = settings,
            extraHeaders = params.headers + (HttpHeaders.Accept to "application/vnd.amazon.eventstream"),
            abortSignal = params.abortSignal,
        ) { sseHeaders = it }
        // Emit ResponseMetadata in this collector's coroutine (never from the
        // channelFlow producer's onResponse) — once, before the first payload.
        var metadataEmitted = false
        suspend fun emitMetadataOnce() {
            if (!metadataEmitted) {
                emit(
                    StreamEvent.ResponseMetadata(
                        id = sseHeaders.headerValue("x-amzn-requestid"),
                        modelId = modelId,
                        headers = sseHeaders,
                    ),
                )
                metadataEmitted = true
            }
        }
        payloads.collect { rawLine ->
            emitMetadataOnce()
            val line = rawLine.trim()
            if (line.isNotEmpty()) {
                val parsed = runCatching { aiSdkJson.parseToJsonElement(line).jsonObject }.getOrNull()
                if (parsed == null) {
                    emit(StreamEvent.Error("Failed to parse Bedrock stream event: $line"))
                } else {
                    state.accept(parsed).forEach { emit(it) }
                }
            }
        }
        emitMetadataOnce()
        state.finish().forEach { emit(it) }
    }

    override fun streamResult(params: LanguageModelCallParams): LanguageModelStreamResult {
        val prepared = bedrockChatRequestBody(modelId, params)
        return LanguageModelStreamResult(stream = stream(params), request = LanguageModelRequestMetadata(prepared.body))
    }
}

private class BedrockEmbeddingModel(
    private val client: HttpClient,
    private val settings: AmazonBedrockProviderSettings,
    override val modelId: String,
) : EmbeddingModel {
    override val provider: String = "amazon-bedrock.embedding"
    override val maxEmbeddingsPerCall: Int = 1
    override val supportsParallelCalls: Boolean = true

    override suspend fun embed(params: EmbeddingModelCallParams): EmbeddingModelResult {
        params.abortSignal.throwIfAborted()
        if (params.values.size > 1) {
            throw TooManyEmbeddingValuesForCallError(provider, modelId, maxEmbeddingsPerCall, params.values)
        }
        val value = params.values.singleOrNull()
            ?: throw InvalidArgumentError("values", "Amazon Bedrock embedding requires one value.")
        val options = params.providerOptions.toMap()["bedrock"] as? JsonObject ?: JsonObject(emptyMap())
        val body = bedrockEmbeddingBody(modelId, value, options)
        val response = bedrockPostJson(
            client = client,
            url = "${bedrockRuntimeBaseURL(settings)}/model/${bedrockEncodeModelId(modelId)}/invoke",
            body = body,
            settings = settings,
            extraHeaders = params.headers,
            abortSignal = params.abortSignal,
            parseJson = true,
        )
        val obj = response.value.jsonObject
        return EmbeddingModelResult(
            embeddings = listOf(bedrockEmbeddingVector(obj)),
            usage = EmbeddingUsage(tokens = bedrockEmbeddingTokens(obj), raw = response.value),
            request = LanguageModelRequestMetadata(body),
            response = LanguageModelResponseMetadata(modelId = modelId, headers = response.headers, body = response.value),
        )
    }
}

private class BedrockImageModel(
    private val client: HttpClient,
    private val settings: AmazonBedrockProviderSettings,
    override val modelId: String,
) : ImageModel {
    override val provider: String = "amazon-bedrock.image"

    override suspend fun generate(params: ImageGenerationParams): ImageModelResult {
        params.abortSignal.throwIfAborted()
        val prepared = bedrockImageBody(params)
        val response = bedrockPostJson(
            client = client,
            url = "${bedrockRuntimeBaseURL(settings)}/model/${bedrockEncodeModelId(modelId)}/invoke",
            body = prepared.body,
            settings = settings,
            extraHeaders = params.headers,
            abortSignal = params.abortSignal,
            parseJson = true,
        )
        val obj = response.value.jsonObject
        if (obj["status"]?.jsonPrimitive?.contentOrNull == "Request Moderated") {
            throw NoImageGeneratedError("Amazon Bedrock request was moderated: ${obj["details"] ?: "Unknown"}")
        }
        val images = (obj["images"] as? JsonArray).orEmpty().map {
            GeneratedFile(mediaType = "image/png", base64 = it.jsonPrimitive.content)
        }
        if (images.isEmpty()) throw NoImageGeneratedError("Amazon Bedrock returned no images.")
        return ImageModelResult(
            images = images,
            warnings = prepared.warnings,
            response = LanguageModelResponseMetadata(modelId = modelId, headers = response.headers, body = response.value),
            providerMetadata = mapOf("bedrock" to response.value),
        )
    }
}

private class BedrockRerankingModel(
    private val client: HttpClient,
    private val settings: AmazonBedrockProviderSettings,
    override val modelId: String,
) : RerankingModel {
    override val provider: String = "amazon-bedrock.reranking"

    override suspend fun rerank(params: RerankingParams): RerankingModelResult {
        params.abortSignal.throwIfAborted()
        val options = params.providerOptions.toMap()["bedrock"] as? JsonObject ?: JsonObject(emptyMap())
        val body = bedrockRerankBody(settings.region ?: "us-east-1", modelId, params, options)
        val response = bedrockPostJson(
            client = client,
            url = "${bedrockAgentRuntimeBaseURL(settings)}/rerank",
            body = body,
            settings = settings,
            extraHeaders = params.headers,
            abortSignal = params.abortSignal,
            parseJson = true,
        )
        val results = (response.value.jsonObject["results"] as? JsonArray).orEmpty().map { item ->
            val obj = item.jsonObject
            val index = obj["index"]?.jsonPrimitive?.intOrNull ?: 0
            RerankedItem(
                value = params.documents.getOrElse(index) { "" },
                score = obj["relevanceScore"]?.jsonPrimitive?.floatOrNull
                    ?: obj["score"]?.jsonPrimitive?.floatOrNull
                    ?: 0f,
                index = index,
            )
        }
        return RerankingModelResult(
            results = results,
            response = LanguageModelResponseMetadata(modelId = modelId, headers = response.headers, body = response.value),
            providerMetadata = mapOf("bedrock" to response.value),
        )
    }
}

private class BedrockMantleChatLanguageModel(
    private val client: HttpClient,
    private val settings: AmazonBedrockProviderSettings,
    override val modelId: String,
    override val provider: String,
    private val path: String,
) : LanguageModel {
    override suspend fun generate(params: LanguageModelCallParams): LanguageModelResult {
        val body = buildJsonObject {
            put("model", JsonPrimitive(modelId))
            put("messages", JsonArray(params.messages.map(::bedrockMantleMessage)))
            params.temperature?.let { put("temperature", JsonPrimitive(it)) }
            params.topP?.let { put("top_p", JsonPrimitive(it)) }
            params.maxOutputTokens?.let { put("max_tokens", JsonPrimitive(it)) }
            if (params.stopSequences.isNotEmpty()) put("stop", JsonArray(params.stopSequences.map(::JsonPrimitive)))
            if (params.tools.isNotEmpty()) {
                put("tools", JsonArray(params.tools.map(::bedrockMantleTool)))
            }
        }
        val response = bedrockPostJson(
            client = client,
            url = "${bedrockMantleBaseURL(settings)}$path",
            body = body,
            settings = settings,
            extraHeaders = params.headers,
            service = "bedrock-mantle",
            abortSignal = params.abortSignal,
            parseJson = true,
        )
        val obj = response.value.jsonObject
        val choice = obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject
        val message = choice?.get("message")?.jsonObject
        val content = message?.get("content")?.jsonPrimitive?.contentOrNull.orEmpty()
        return LanguageModelResult(
            text = content,
            finishReason = mapOpenAILikeFinishReason(choice?.get("finish_reason")?.jsonPrimitive?.contentOrNull),
            usage = bedrockOpenAILikeUsage(obj["usage"]),
            request = LanguageModelRequestMetadata(body),
            response = LanguageModelResponseMetadata(
                id = obj["id"]?.jsonPrimitive?.contentOrNull,
                modelId = obj["model"]?.jsonPrimitive?.contentOrNull ?: modelId,
                headers = response.headers,
                body = response.value,
            ),
            rawFinishReason = choice?.get("finish_reason")?.jsonPrimitive?.contentOrNull,
        )
    }

    override fun stream(params: LanguageModelCallParams): Flow<StreamEvent> = flow {
        val result = generate(params)
        emit(StreamEvent.StreamStart(result.warnings))
        emit(StreamEvent.ResponseMetadata(result.response.id, result.response.timestampMillis, result.response.modelId, result.response.headers, result.response.body))
        if (result.text.isNotEmpty()) {
            emit(StreamEvent.TextStart("0"))
            emit(StreamEvent.TextDelta("0", result.text))
            emit(StreamEvent.TextEnd("0"))
        }
        emit(
            StreamEvent.Finish(
                1,
                result.finishReason,
                result.usage,
                result.providerMetadata,
                rawFinishReason = result.rawFinishReason,
            ),
        )
    }
}

private data class BedrockPreparedChatRequest(
    val body: JsonObject,
    val warnings: List<CallWarning>,
    val usesJsonResponseTool: Boolean,
)

private data class BedrockPreparedImageRequest(
    val body: JsonObject,
    val warnings: List<CallWarning>,
)

private data class BedrockHttpResponse(
    val value: JsonElement,
    val headers: Map<String, String>,
)


private fun bedrockChatRequestBody(
    modelId: String,
    params: LanguageModelCallParams,
): BedrockPreparedChatRequest {
    val warnings = mutableListOf<CallWarning>()
    params.frequencyPenalty?.let { warnings += CallWarning("unsupported", "frequencyPenalty") }
    params.presencePenalty?.let { warnings += CallWarning("unsupported", "presencePenalty") }
    params.seed?.let { warnings += CallWarning("unsupported", "seed") }
    val options = params.providerOptions.toMap()["bedrock"] as? JsonObject ?: JsonObject(emptyMap())
    // Anthropic thinking changes inferenceConfig: the thinking budget is added to maxTokens
    // (Bedrock counts it against the output budget) and Bedrock rejects temperature/topP/topK
    // for thinking-enabled Anthropic calls, so they're stripped (matching upstream).
    val reasoningConfig = options["reasoningConfig"] as? JsonObject
    val isAnthropicThinking = modelId.contains("anthropic") &&
        reasoningConfig?.get("type")?.jsonPrimitive?.contentOrNull in setOf("enabled", "adaptive")
    val thinkingBudget = reasoningConfig?.get("budgetTokens")?.jsonPrimitive?.intOrNull
    val hasSamplingParams = params.temperature != null || params.topP != null || params.topK != null
    if (isAnthropicThinking && hasSamplingParams) {
        warnings += CallWarning("unsupported", "temperature/topP/topK are not supported with Anthropic thinking")
    }
    val inferenceConfig = buildJsonObject {
        val baseMaxTokens = params.maxOutputTokens
        val resolvedMaxTokens = when {
            !isAnthropicThinking || thinkingBudget == null -> baseMaxTokens
            baseMaxTokens != null -> baseMaxTokens + thinkingBudget
            else -> thinkingBudget + DEFAULT_BEDROCK_MAX_TOKENS
        }
        resolvedMaxTokens?.let { put("maxTokens", JsonPrimitive(it)) }
        if (!isAnthropicThinking) {
            params.temperature?.coerceIn(0f, 1f)?.let { put("temperature", JsonPrimitive(it)) }
            params.topP?.let { put("topP", JsonPrimitive(it)) }
            params.topK?.let { put("topK", JsonPrimitive(it)) }
        }
        if (params.stopSequences.isNotEmpty()) put("stopSequences", JsonArray(params.stopSequences.map(::JsonPrimitive)))
    }
    val converted = bedrockMessages(params.messages)
    warnings += converted.warnings
    val tools = bedrockTools(modelId, params.tools, params.toolChoice, params.responseFormat)
    warnings += tools.warnings
    val bedrockOptions = bedrockAdditionalModelRequestFields(options, modelId, params.responseFormat)
    val serviceTier = options["serviceTier"]?.jsonPrimitive?.contentOrNull

    return BedrockPreparedChatRequest(
        body = buildJsonObject {
            if (converted.system.isNotEmpty()) put("system", converted.system)
            put("messages", converted.messages)
            if (bedrockOptions.isNotEmpty()) put("additionalModelRequestFields", bedrockOptions)
            if (modelId.contains("anthropic")) {
                put("additionalModelResponseFieldPaths", JsonArray(listOf(JsonPrimitive("/delta/stop_sequence"))))
            }
            if (inferenceConfig.isNotEmpty()) put("inferenceConfig", inferenceConfig)
            serviceTier?.let {
                put("serviceTier", buildJsonObject { put("type", JsonPrimitive(it)) })
            }
            tools.toolConfig?.let { put("toolConfig", it) }
        },
        warnings = warnings,
        usesJsonResponseTool = tools.usesJsonResponseTool,
    )
}

private data class BedrockConvertedMessages(
    val system: JsonArray,
    val messages: JsonArray,
    val warnings: List<CallWarning>,
)

private fun bedrockMessages(messages: List<ModelMessage>): BedrockConvertedMessages {
    val system = mutableListOf<JsonElement>()
    val converted = mutableListOf<JsonElement>()
    val warnings = mutableListOf<CallWarning>()
    for (message in messages) {
        when (message.role) {
            MessageRole.System -> message.content.forEach { part ->
                if (part is ContentPart.Text) {
                    system += buildJsonObject {
                        put("text", JsonPrimitive(part.text))
                        bedrockCachePoint(part.providerMetadata)?.let { put("cachePoint", it) }
                    }
                }
            }
            MessageRole.User -> converted += buildJsonObject {
                put("role", JsonPrimitive("user"))
                put("content", JsonArray(message.content.mapNotNull(::bedrockUserPart)))
            }
            MessageRole.Assistant -> converted += buildJsonObject {
                put("role", JsonPrimitive("assistant"))
                put("content", JsonArray(message.content.mapNotNull(::bedrockAssistantPart)))
            }
            MessageRole.Tool -> converted += buildJsonObject {
                put("role", JsonPrimitive("user"))
                put("content", JsonArray(message.content.mapNotNull(::bedrockToolResultPart)))
            }
        }
    }
    return BedrockConvertedMessages(JsonArray(system), JsonArray(converted), warnings)
}

private fun bedrockUserPart(part: ContentPart): JsonElement? = when (part) {
    is ContentPart.Text -> buildJsonObject {
        put("text", JsonPrimitive(part.text))
        bedrockCachePoint(part.providerMetadata)?.let { put("cachePoint", it) }
    }
    is ContentPart.Image -> buildJsonObject {
        put(
            "image",
            buildJsonObject {
                put("format", JsonPrimitive(bedrockImageFormat(part.mediaType)))
                put("source", buildJsonObject { put("bytes", JsonPrimitive(part.base64)) })
            },
        )
    }
    is ContentPart.File -> buildJsonObject {
        if (part.mediaType.startsWith("image/")) {
            put(
                "image",
                buildJsonObject {
                    put("format", JsonPrimitive(bedrockImageFormat(part.mediaType)))
                    put("source", buildJsonObject { put("bytes", JsonPrimitive(part.base64)) })
                },
            )
        } else {
            put(
                "document",
                buildJsonObject {
                    put("format", JsonPrimitive(bedrockDocumentFormat(part.mediaType)))
                    put("name", JsonPrimitive(part.filename?.substringBeforeLast('.') ?: "document"))
                    put("source", buildJsonObject { put("bytes", JsonPrimitive(part.base64)) })
                    if (bedrockCitationsEnabled(part.providerMetadata)) {
                        put("citations", buildJsonObject { put("enabled", JsonPrimitive(true)) })
                    }
                },
            )
        }
    }
    else -> null
}

private fun bedrockAssistantPart(part: ContentPart): JsonElement? = when (part) {
    is ContentPart.Text -> buildJsonObject { put("text", JsonPrimitive(part.text)) }
    is ContentPart.Reasoning -> buildJsonObject {
        put(
            "reasoningContent",
            buildJsonObject {
                put(
                    "reasoningText",
                    buildJsonObject {
                        put("text", JsonPrimitive(part.text))
                        (part.providerMetadata?.get("bedrock") as? JsonObject)?.get("signature")?.let { put("signature", it) }
                    },
                )
            },
        )
    }
    is ContentPart.ToolCall -> buildJsonObject {
        put(
            "toolUse",
            buildJsonObject {
                put("toolUseId", JsonPrimitive(part.toolCallId))
                put("name", JsonPrimitive(part.toolName))
                put("input", part.input)
            },
        )
    }
    else -> null
}

private fun bedrockToolResultPart(part: ContentPart): JsonElement? = when (part) {
    // Bedrock's toolResult has NO `status` field, and content blocks may only be
    // text/image/document — never `{json}` (a non-string output is JSON-stringified into a
    // text block, matching upstream).
    is ContentPart.ToolResult -> buildJsonObject {
        put(
            "toolResult",
            buildJsonObject {
                put("toolUseId", JsonPrimitive(part.toolCallId))
                put("content", JsonArray(listOf(bedrockToolResultContent(part.modelVisible))))
            },
        )
    }
    is ContentPart.ToolApprovalResponse -> buildJsonObject {
        put(
            "toolResult",
            buildJsonObject {
                put("toolUseId", JsonPrimitive(part.toolCallId))
                val text = part.reason ?: "Tool execution ${if (part.approved) "approved" else "denied"}."
                put("content", JsonArray(listOf(buildJsonObject { put("text", JsonPrimitive(text)) })))
            },
        )
    }
    else -> null
}

private fun bedrockToolResultContent(output: JsonElement): JsonObject = when {
    output is JsonPrimitive && output.isString -> buildJsonObject { put("text", JsonPrimitive(output.content)) }
    // Any non-string output becomes a JSON-stringified text block (Bedrock rejects a `json` block).
    else -> buildJsonObject { put("text", JsonPrimitive(output.toString())) }
}

private data class BedrockPreparedTools(
    val toolConfig: JsonObject?,
    val warnings: List<CallWarning>,
    val usesJsonResponseTool: Boolean,
)

private fun bedrockTools(
    modelId: String,
    tools: List<LanguageModelTool>,
    choice: ToolChoice,
    responseFormat: ResponseFormat,
): BedrockPreparedTools {
    val warnings = mutableListOf<CallWarning>()
    if (choice == ToolChoice.None) return BedrockPreparedTools(null, warnings, false)
    val preparedTools = mutableListOf<LanguageModelTool>()
    preparedTools += when (choice) {
        is ToolChoice.Specific -> tools.filter { it.name == choice.toolName }
        else -> tools
    }
    val usesJsonResponseTool = responseFormat is ResponseFormat.Json && responseFormat.schemaJson != null
    if (usesJsonResponseTool) {
        preparedTools += LanguageModelTool("json", "Respond with a JSON object.", responseFormat.schemaJson.toString())
    }
    val bedrockTools = preparedTools.mapNotNull { tool ->
        if (tool.providerExecuted && !modelId.contains("anthropic")) {
            warnings += CallWarning("unsupported", "tool ${tool.name}")
            null
        } else {
            buildJsonObject {
                put(
                    "toolSpec",
                    buildJsonObject {
                        put("name", JsonPrimitive(tool.name))
                        if (tool.description.isNotBlank()) put("description", JsonPrimitive(tool.description))
                        put("inputSchema", buildJsonObject { put("json", aiSdkJson.parseToJsonElement(tool.parametersSchemaJson)) })
                    },
                )
            }
        }
    }
    if (bedrockTools.isEmpty()) return BedrockPreparedTools(null, warnings, usesJsonResponseTool)
    val toolChoice = when (choice) {
        ToolChoice.Auto -> buildJsonObject { put("auto", buildJsonObject { }) }
        ToolChoice.Required -> buildJsonObject { put("any", buildJsonObject { }) }
        is ToolChoice.Specific -> buildJsonObject { put("tool", buildJsonObject { put("name", JsonPrimitive(choice.toolName)) }) }
        ToolChoice.None -> null
    }
    return BedrockPreparedTools(
        toolConfig = buildJsonObject {
            put("tools", JsonArray(bedrockTools))
            toolChoice?.let { put("toolChoice", it) }
        },
        warnings = warnings,
        usesJsonResponseTool = usesJsonResponseTool,
    )
}

private fun bedrockAdditionalModelRequestFields(
    options: JsonObject,
    modelId: String,
    responseFormat: ResponseFormat,
): JsonObject {
    val additional = (options["additionalModelRequestFields"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
    val reasoningConfig = options["reasoningConfig"] as? JsonObject
    val reasoningType = reasoningConfig?.get("type")?.jsonPrimitive?.contentOrNull
    val maxReasoningEffort = reasoningConfig?.get("maxReasoningEffort")?.jsonPrimitive?.contentOrNull
    if (modelId.contains("anthropic") && reasoningType in setOf("enabled", "adaptive")) {
        additional["thinking"] = buildJsonObject {
            put("type", JsonPrimitive(reasoningType))
            reasoningConfig?.get("budgetTokens")?.let { put("budget_tokens", it) }
            reasoningConfig?.get("display")?.let { put("display", it) }
        }
    }
    if (maxReasoningEffort != null) {
        if (modelId.contains("anthropic")) {
            val existing = additional["output_config"] as? JsonObject ?: JsonObject(emptyMap())
            additional["output_config"] = JsonObject(existing + ("effort" to JsonPrimitive(maxReasoningEffort)))
        } else if (modelId.startsWith("openai.")) {
            additional["reasoning_effort"] = JsonPrimitive(maxReasoningEffort)
        } else {
            additional["reasoningConfig"] = buildJsonObject {
                reasoningType?.let { put("type", JsonPrimitive(it)) }
                reasoningConfig["budgetTokens"]?.let { put("budgetTokens", it) }
                put("maxReasoningEffort", JsonPrimitive(maxReasoningEffort))
            }
        }
    }
    if (modelId.contains("anthropic") && responseFormat is ResponseFormat.Json && responseFormat.schemaJson != null && reasoningType in setOf("enabled", "adaptive")) {
        val existing = additional["output_config"] as? JsonObject ?: JsonObject(emptyMap())
        additional["output_config"] = JsonObject(
            existing + ("format" to buildJsonObject {
                put("type", JsonPrimitive("json_schema"))
                put("schema", responseFormat.schemaJson)
            }),
        )
    }
    (options["anthropicBeta"] as? JsonArray)?.let { additional["anthropic_beta"] = it }
    return JsonObject(additional)
}

private fun bedrockEmbeddingBody(modelId: String, value: String, options: JsonObject): JsonObject =
    when {
        modelId.startsWith("amazon.nova-") && modelId.contains("embed") -> buildJsonObject {
            put("taskType", JsonPrimitive("SINGLE_EMBEDDING"))
            put(
                "singleEmbeddingParams",
                buildJsonObject {
                    put("embeddingPurpose", options["embeddingPurpose"] ?: JsonPrimitive("GENERIC_INDEX"))
                    put("embeddingDimension", options["embeddingDimension"] ?: JsonPrimitive(1024))
                    put(
                        "text",
                        buildJsonObject {
                            put("truncationMode", options["truncate"] ?: JsonPrimitive("END"))
                            put("value", JsonPrimitive(value))
                        },
                    )
                },
            )
        }
        modelId.startsWith("cohere.embed-") -> buildJsonObject {
            put("input_type", options["inputType"] ?: JsonPrimitive("search_query"))
            put("texts", JsonArray(listOf(JsonPrimitive(value))))
            options["truncate"]?.let { put("truncate", it) }
            options["outputDimension"]?.let { put("output_dimension", it) }
        }
        else -> buildJsonObject {
            put("inputText", JsonPrimitive(value))
            options["dimensions"]?.let { put("dimensions", it) }
            options["normalize"]?.let { put("normalize", it) }
        }
    }

private fun bedrockEmbeddingVector(response: JsonObject): List<Float> {
    response["embedding"]?.let { return it.jsonArray.map { item -> WireDecoder.embeddingFloat(item, "amazon-bedrock.embedding") } }
    val embeddings = response["embeddings"]
    if (embeddings is JsonArray) {
        val first = embeddings.firstOrNull() ?: return emptyList()
        if (first is JsonArray) return first.map { WireDecoder.embeddingFloat(it, "amazon-bedrock.embedding") }
        val obj = first.jsonObject
        return obj["embedding"]?.jsonArray.orEmpty().map { WireDecoder.embeddingFloat(it, "amazon-bedrock.embedding") }
    }
    val floatEmbeddings = embeddings?.jsonObject?.get("float")?.jsonArray?.firstOrNull()?.jsonArray
    return floatEmbeddings.orEmpty().map { WireDecoder.embeddingFloat(it, "amazon-bedrock.embedding") }
}

private fun bedrockEmbeddingTokens(response: JsonObject): Int =
    response["inputTextTokenCount"]?.jsonPrimitive?.intOrNull
        ?: response["inputTokenCount"]?.jsonPrimitive?.intOrNull
        ?: 0

private fun bedrockImageBody(params: ImageGenerationParams): BedrockPreparedImageRequest {
    val warnings = mutableListOf<CallWarning>()
    if (params.aspectRatio != null) warnings += CallWarning("unsupported", "aspectRatio")
    val options = params.providerOptions.toMap()["bedrock"] as? JsonObject ?: JsonObject(emptyMap())
    val sizeParts = params.size?.split("x")?.mapNotNull { it.toIntOrNull() }.orEmpty()
    val imageGenerationConfig = buildJsonObject {
        sizeParts.getOrNull(0)?.let { put("width", JsonPrimitive(it)) }
        sizeParts.getOrNull(1)?.let { put("height", JsonPrimitive(it)) }
        params.seed?.let { put("seed", JsonPrimitive(it)) }
        put("numberOfImages", JsonPrimitive(params.n))
        options["quality"]?.let { put("quality", it) }
        options["cfgScale"]?.let { put("cfgScale", it) }
    }
    val body = if (params.files.isNotEmpty()) {
        val taskType = options["taskType"]?.jsonPrimitive?.contentOrNull
            ?: if (params.mask != null || options["maskPrompt"] != null) "INPAINTING" else "IMAGE_VARIATION"
        when (taskType) {
            "INPAINTING" -> buildJsonObject {
                put("taskType", JsonPrimitive("INPAINTING"))
                put(
                    "inPaintingParams",
                    buildJsonObject {
                        put("image", JsonPrimitive(bedrockImageFileBase64(params.files.first())))
                        if (params.prompt.isNotBlank()) put("text", JsonPrimitive(params.prompt))
                        options["negativeText"]?.let { put("negativeText", it) }
                        params.mask?.let { put("maskImage", JsonPrimitive(bedrockImageFileBase64(it))) }
                        options["maskPrompt"]?.let { put("maskPrompt", it) }
                    },
                )
                put("imageGenerationConfig", imageGenerationConfig)
            }
            "OUTPAINTING" -> buildJsonObject {
                put("taskType", JsonPrimitive("OUTPAINTING"))
                put(
                    "outPaintingParams",
                    buildJsonObject {
                        put("image", JsonPrimitive(bedrockImageFileBase64(params.files.first())))
                        if (params.prompt.isNotBlank()) put("text", JsonPrimitive(params.prompt))
                        options["negativeText"]?.let { put("negativeText", it) }
                        options["outPaintingMode"]?.let { put("outPaintingMode", it) }
                        params.mask?.let { put("maskImage", JsonPrimitive(bedrockImageFileBase64(it))) }
                        options["maskPrompt"]?.let { put("maskPrompt", it) }
                    },
                )
                put("imageGenerationConfig", imageGenerationConfig)
            }
            "BACKGROUND_REMOVAL" -> buildJsonObject {
                put("taskType", JsonPrimitive("BACKGROUND_REMOVAL"))
                put("backgroundRemovalParams", buildJsonObject { put("image", JsonPrimitive(bedrockImageFileBase64(params.files.first()))) })
            }
            else -> buildJsonObject {
                put("taskType", JsonPrimitive("IMAGE_VARIATION"))
                put(
                    "imageVariationParams",
                    buildJsonObject {
                        put("images", JsonArray(params.files.map { JsonPrimitive(bedrockImageFileBase64(it)) }))
                        if (params.prompt.isNotBlank()) put("text", JsonPrimitive(params.prompt))
                        options["negativeText"]?.let { put("negativeText", it) }
                        options["similarityStrength"]?.let { put("similarityStrength", it) }
                    },
                )
                put("imageGenerationConfig", imageGenerationConfig)
            }
        }
    } else {
        buildJsonObject {
            put("taskType", JsonPrimitive("TEXT_IMAGE"))
            put(
                "textToImageParams",
                buildJsonObject {
                    put("text", JsonPrimitive(params.prompt))
                    options["negativeText"]?.let { put("negativeText", it) }
                    options["style"]?.let { put("style", it) }
                },
            )
            put("imageGenerationConfig", imageGenerationConfig)
        }
    }
    return BedrockPreparedImageRequest(body, warnings)
}

private fun bedrockRerankBody(
    region: String,
    modelId: String,
    params: RerankingParams,
    options: JsonObject,
): JsonObject = buildJsonObject {
    options["nextToken"]?.let { put("nextToken", it) }
    put(
        "queries",
        JsonArray(
            listOf(
                buildJsonObject {
                    put("type", JsonPrimitive("TEXT"))
                    put("textQuery", buildJsonObject { put("text", JsonPrimitive(params.query)) })
                },
            ),
        ),
    )
    put(
        "rerankingConfiguration",
        buildJsonObject {
            put("type", JsonPrimitive("BEDROCK_RERANKING_MODEL"))
            put(
                "bedrockRerankingConfiguration",
                buildJsonObject {
                    put(
                        "modelConfiguration",
                        buildJsonObject {
                            put("modelArn", JsonPrimitive("arn:aws:bedrock:$region::foundation-model/$modelId"))
                            options["additionalModelRequestFields"]?.let { put("additionalModelRequestFields", it) }
                        },
                    )
                    params.topN?.let { put("numberOfResults", JsonPrimitive(it)) }
                },
            )
        },
    )
    put(
        "sources",
        JsonArray(
            params.documents.map { document ->
                buildJsonObject {
                    put("type", JsonPrimitive("INLINE"))
                    put(
                        "inlineDocumentSource",
                        buildJsonObject {
                            put("type", JsonPrimitive("TEXT"))
                            put("textDocument", buildJsonObject { put("text", JsonPrimitive(document)) })
                        },
                    )
                }
            },
        ),
    )
}

private fun bedrockChatGenerateResult(
    response: JsonObject,
    requestBody: JsonObject,
    responseHeaders: Map<String, String>,
    responseBody: JsonElement,
    warnings: List<CallWarning>,
    generateId: () -> String,
    usesJsonResponseTool: Boolean,
): LanguageModelResult {
    val content = mutableListOf<ContentPart>()
    val toolCalls = mutableListOf<ContentPart.ToolCall>()
    var isJsonResponseFromTool = false
    val parts = response["output"]?.jsonObject?.get("message")?.jsonObject?.get("content") as? JsonArray ?: JsonArray(emptyList())
    for (part in parts) {
        val obj = part.jsonObject
        obj["text"]?.jsonPrimitive?.contentOrNull?.let { content += ContentPart.Text(it) }
        (obj["reasoningContent"] as? JsonObject)?.let { reasoning ->
            reasoning["reasoningText"]?.jsonObject?.let {
                content += ContentPart.Reasoning(
                    text = it["text"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    providerMetadata = mapOf("bedrock" to buildJsonObject { it["signature"]?.let { signature -> put("signature", signature) } }),
                )
            }
            reasoning["redactedReasoning"]?.jsonObject?.let {
                content += ContentPart.Reasoning(
                    text = "",
                    providerMetadata = mapOf("bedrock" to buildJsonObject { it["data"]?.let { data -> put("redactedData", data) } }),
                )
            }
        }
        (obj["toolUse"] as? JsonObject)?.let { tool ->
            val name = tool["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (usesJsonResponseTool && name == "json") {
                isJsonResponseFromTool = true
                content += ContentPart.Text((tool["input"] ?: JsonObject(emptyMap())).toString())
            } else {
                val call = ContentPart.ToolCall(
                    toolCallId = tool["toolUseId"]?.jsonPrimitive?.contentOrNull ?: IdGenerator.generate(),
                    toolName = name,
                    input = tool["input"] ?: JsonObject(emptyMap()),
                )
                content += call
                toolCalls += call
            }
        }
    }
    val text = content.filterIsInstance<ContentPart.Text>().joinToString("") { it.text }
    val stopReason = response["stopReason"]?.jsonPrimitive?.contentOrNull
    val metadata = buildJsonObject {
        response["trace"]?.let { put("trace", it) }
        response["performanceConfig"]?.let { put("performanceConfig", it) }
        response["serviceTier"]?.let { put("serviceTier", it) }
        response["usage"]?.let { put("usage", it) }
        if (isJsonResponseFromTool) put("isJsonResponseFromTool", JsonPrimitive(true))
        response["additionalModelResponseFields"]?.jsonObject?.get("delta")?.jsonObject?.get("stop_sequence")?.let { put("stopSequence", it) }
    }
    return LanguageModelResult(
        text = text,
        toolCalls = toolCalls,
        finishReason = mapBedrockFinishReason(stopReason, isJsonResponseFromTool),
        usage = bedrockUsage(response["usage"]),
        providerMetadata = if (metadata.isNotEmpty()) mapOf("bedrock" to metadata) else emptyMap(),
        content = content,
        rawFinishReason = stopReason,
        warnings = warnings,
        request = LanguageModelRequestMetadata(requestBody),
        response = LanguageModelResponseMetadata(
            id = responseHeaders.headerValue("x-amzn-requestid"),
            modelId = response["modelId"]?.jsonPrimitive?.contentOrNull,
            headers = responseHeaders,
            body = responseBody,
        ),
    )
}

private class BedrockStreamState(
    private val generateId: () -> String,
    private val usesJsonResponseTool: Boolean,
) {
    private val blocks = mutableMapOf<Int, BedrockStreamBlock>()
    private var finishReason = FinishReason.Other
    private var rawStopReason: String? = null
    private var usage = Usage()
    private var providerMetadata: JsonObject? = null
    private var isJsonResponseFromTool = false
    private var stopSequence: JsonElement? = null

    fun accept(value: JsonObject): List<StreamEvent> {
        val events = mutableListOf<StreamEvent>()
        val error = value["internalServerException"] ?: value["modelStreamErrorException"] ?: value["throttlingException"] ?: value["validationException"]
        if (error != null) {
            finishReason = FinishReason.Error
            events += StreamEvent.Error(error.toString())
            return events
        }
        (value["messageStop"] as? JsonObject)?.let { stop ->
            rawStopReason = stop["stopReason"]?.jsonPrimitive?.contentOrNull
            finishReason = mapBedrockFinishReason(rawStopReason, isJsonResponseFromTool)
            stopSequence = stop["additionalModelResponseFields"]?.jsonObject?.get("delta")?.jsonObject?.get("stop_sequence")
        }
        (value["metadata"] as? JsonObject)?.let { metadata ->
            metadata["usage"]?.let { usage = bedrockUsage(it) }
            providerMetadata = metadata
        }
        (value["contentBlockStart"] as? JsonObject)?.let { start ->
            val index = start["contentBlockIndex"]?.jsonPrimitive?.intOrNull ?: return@let
            val toolUse = start["start"]?.jsonObject?.get("toolUse") as? JsonObject
            if (toolUse != null) {
                val id = toolUse["toolUseId"]?.jsonPrimitive?.contentOrNull ?: IdGenerator.generate()
                val name = toolUse["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val isJsonTool = usesJsonResponseTool && name == "json"
                blocks[index] = BedrockStreamBlock.Tool(id, name, "", isJsonTool)
                if (!isJsonTool) events += StreamEvent.ToolInputStart(id, name)
            } else {
                blocks[index] = BedrockStreamBlock.Text
                events += StreamEvent.TextStart(index.toString())
            }
        }
        (value["contentBlockDelta"] as? JsonObject)?.let { deltaEvent ->
            val index = deltaEvent["contentBlockIndex"]?.jsonPrimitive?.intOrNull ?: 0
            val delta = deltaEvent["delta"] as? JsonObject ?: return@let
            delta["text"]?.jsonPrimitive?.contentOrNull?.let { text ->
                if (blocks[index] == null) {
                    blocks[index] = BedrockStreamBlock.Text
                    events += StreamEvent.TextStart(index.toString())
                }
                events += StreamEvent.TextDelta(index.toString(), text)
            }
            (delta["reasoningContent"] as? JsonObject)?.let { reasoning ->
                if (blocks[index] == null) {
                    blocks[index] = BedrockStreamBlock.Reasoning
                    events += StreamEvent.ReasoningStart(index.toString())
                }
                val metadata = buildJsonObject {
                    reasoning["signature"]?.let { put("signature", it) }
                    reasoning["data"]?.let { put("redactedData", it) }
                }.takeIf { it.isNotEmpty() }?.let { mapOf("bedrock" to it) }
                events += StreamEvent.ReasoningDelta(index.toString(), reasoning["text"]?.jsonPrimitive?.contentOrNull.orEmpty(), metadata)
            }
            (delta["toolUse"] as? JsonObject)?.get("input")?.jsonPrimitive?.contentOrNull?.let { inputDelta ->
                val block = blocks[index] as? BedrockStreamBlock.Tool ?: return@let
                block.input += inputDelta
                if (!block.isJsonResponseTool) events += StreamEvent.ToolInputDelta(block.id, inputDelta)
            }
        }
        (value["contentBlockStop"] as? JsonObject)?.let { stop ->
            val index = stop["contentBlockIndex"]?.jsonPrimitive?.intOrNull ?: return@let
            when (val block = blocks.remove(index)) {
                BedrockStreamBlock.Text -> events += StreamEvent.TextEnd(index.toString())
                BedrockStreamBlock.Reasoning -> events += StreamEvent.ReasoningEnd(index.toString())
                is BedrockStreamBlock.Tool -> {
                    if (block.isJsonResponseTool) {
                        isJsonResponseFromTool = true
                        events += StreamEvent.TextStart(index.toString())
                        events += StreamEvent.TextDelta(index.toString(), block.input.ifBlank { "{}" })
                        events += StreamEvent.TextEnd(index.toString())
                    } else {
                        events += StreamEvent.ToolInputEnd(block.id)
                        events += StreamEvent.ToolCall(
                            toolCallId = block.id,
                            toolName = block.name,
                            inputJson = runCatching { aiSdkJson.parseToJsonElement(block.input.ifBlank { "{}" }) }.getOrElse { JsonPrimitive(block.input) },
                        )
                    }
                }
                null -> Unit
            }
        }
        return events
    }

    fun finish(): List<StreamEvent> {
        val metadata = buildJsonObject {
            providerMetadata?.let { putJsonObjectFields(it) }
            if (isJsonResponseFromTool) put("isJsonResponseFromTool", JsonPrimitive(true))
            stopSequence?.let { put("stopSequence", it) }
        }
        return listOf(
            StreamEvent.Finish(
                totalSteps = 1,
                finishReason = finishReason,
                usage = usage,
                providerMetadata = if (metadata.isNotEmpty()) mapOf("bedrock" to metadata) else null,
                rawFinishReason = rawStopReason,
            ),
        )
    }
}

private sealed interface BedrockStreamBlock {
    data object Text : BedrockStreamBlock
    data object Reasoning : BedrockStreamBlock
    data class Tool(val id: String, val name: String, var input: String, val isJsonResponseTool: Boolean) : BedrockStreamBlock
}

private suspend fun bedrockPostJson(
    client: HttpClient,
    url: String,
    body: JsonElement,
    settings: AmazonBedrockProviderSettings,
    extraHeaders: Map<String, String>,
    service: String = "bedrock",
    abortSignal: AbortSignal,
    parseJson: Boolean,
): BedrockHttpResponse {
    abortSignal.throwIfAborted()
    val encodedBody = aiSdkJson.encodeToString(JsonElement.serializer(), body)
    val headers = bedrockHeaders(settings, extraHeaders, url, encodedBody, service)
    val response = client.request(url) {
        method = HttpMethod.Post
        contentType(ContentType.Application.Json)
        headers.forEach { (name, value) -> header(name, value) }
        setBody(encodedBody)
    }
    val raw = response.bodyAsBytes().decodeToString()
    val responseHeaders = response.flattenedHeaders()
    if (response.status.value !in 200..299) {
        val parsed = runCatching { aiSdkJson.parseToJsonElement(raw) }.getOrNull()
        throw apiCallError(
            url = url,
            statusCode = response.status.value,
            rawBody = raw,
            headers = responseHeaders,
            message = bedrockErrorMessage(parsed, raw),
            requestBodyValues = body,
        )
    }
    return BedrockHttpResponse(
        value = if (parseJson && raw.isNotBlank()) aiSdkJson.parseToJsonElement(raw) else JsonObject(emptyMap()),
        headers = responseHeaders,
    )
}

/**
 * Streams Bedrock converse-stream payloads incrementally off the response
 * channel. For `application/vnd.amazon.eventstream` (the live wire format) it
 * reads one Smithy binary frame at a time — the 4-byte total-length prelude,
 * then the remaining bytes — and decodes each as it arrives. For any other
 * content type it falls back to line framing. Non-2xx surfaces the rich
 * [APICallError] via [bedrockErrorMessage], matching [bedrockPostJson].
 */
private fun bedrockStreamPayloads(
    client: HttpClient,
    url: String,
    body: JsonElement,
    settings: AmazonBedrockProviderSettings,
    extraHeaders: Map<String, String>,
    abortSignal: AbortSignal,
    onResponse: suspend (Map<String, String>) -> Unit,
): Flow<String> = channelFlow {
    // channelFlow (not flow): execute{} and the channel reads may run in a
    // different coroutine than the collector on Kotlin/Native; `send` is safe
    // across that boundary where `emit` would throw "Flow invariant is violated".
    abortSignal.throwIfAborted()
    val encodedBody = aiSdkJson.encodeToString(JsonElement.serializer(), body)
    val headers = bedrockHeaders(settings, extraHeaders, url, encodedBody, "bedrock")
    val statement = client.prepareRequest(url) {
        method = HttpMethod.Post
        contentType(ContentType.Application.Json)
        headers.forEach { (name, value) -> header(name, value) }
        setBody(encodedBody)
    }
    statement.execute { response ->
        val flattened = response.flattenedHeaders()
        if (response.status.value !in 200..299) {
            val raw = response.bodyAsBytes().decodeToString()
            val parsed = runCatching { aiSdkJson.parseToJsonElement(raw) }.getOrNull()
            throw apiCallError(
                url = url,
                statusCode = response.status.value,
                rawBody = raw,
                headers = flattened,
                message = bedrockErrorMessage(parsed, raw),
                requestBodyValues = body,
            )
        }
        onResponse(flattened)
        val channel = response.bodyAsChannel()
        val contentType = flattened.headerValue(HttpHeaders.ContentType).orEmpty()
        if (contentType.contains("application/vnd.amazon.eventstream", ignoreCase = true)) {
            sendBedrockEventStreamFrames(channel)
        } else {
            while (true) {
                val line = channel.readLine() ?: break
                send(line)
            }
        }
    }
}

/** Read and decode Smithy binary event-stream frames off [channel] one at a
 *  time, sending each reshaped payload as it arrives. */
private suspend fun ProducerScope<String>.sendBedrockEventStreamFrames(channel: ByteReadChannel) {
    while (true) {
        val prelude = channel.readBedrockFrame(4) ?: break
        val totalLength = prelude.readInt32BE(0)
        if (totalLength < 16) break
        val rest = channel.readBedrockFrame(totalLength - 4) ?: break
        for (message in decodeBedrockEventStream(prelude + rest)) {
            send(bedrockMessagePayload(message))
        }
    }
}

/** Reshape one decoded event-stream message into the JSON-line payload the
 *  [BedrockStreamState] consumes (mirrors the v6 `:event-type` wrapping). */
private fun bedrockMessagePayload(message: BedrockEventStreamMessage): String {
    val payload = runCatching { aiSdkJson.parseToJsonElement(message.payloadText) }.getOrNull()
    val eventType = message.eventType
    return if (eventType.isBlank()) {
        message.payloadText
    } else if (payload is JsonObject && payload.size == 1 && payload[eventType] != null) {
        message.payloadText
    } else {
        buildJsonObject {
            put(eventType, payload ?: JsonPrimitive(message.payloadText))
        }.toString()
    }
}

/** Read exactly [count] bytes off the channel, or null at a clean EOF (the
 *  buffered decoder likewise stops on an incomplete trailing frame). */
private suspend fun ByteReadChannel.readBedrockFrame(count: Int): ByteArray? {
    val out = ByteArray(count)
    var read = 0
    while (read < count) {
        val n = readAvailable(out, read, count - read)
        if (n < 0) {
            // A clean EOF at a frame boundary (read == 0) is the normal end of
            // the stream. EOF after a partial read means the server cut a frame
            // mid-flight — surface it instead of silently returning a truncated
            // generation. (readAvailable suspends via awaitContent when the
            // buffer is momentarily empty, so this never busy-spins.)
            if (read == 0) return null
            throw InvalidResponseDataError(null, "Bedrock event stream truncated: got $read of $count frame bytes before EOF")
        }
        read += n
    }
    return out
}

private data class BedrockEventStreamMessage(
    val messageType: String,
    val eventType: String,
    val payloadText: String,
)

private fun decodeBedrockEventStream(bytes: ByteArray): List<BedrockEventStreamMessage> {
    val messages = mutableListOf<BedrockEventStreamMessage>()
    var offset = 0
    while (offset + 16 <= bytes.size) {
        val totalLength = bytes.readInt32BE(offset)
        val headersLength = bytes.readInt32BE(offset + 4)
        if (totalLength < 16 || headersLength < 0 || offset + totalLength > bytes.size) break
        val headersStart = offset + 12
        val payloadStart = headersStart + headersLength
        val payloadEnd = offset + totalLength - 4
        if (payloadStart > payloadEnd || payloadEnd > bytes.size) break
        val headers = bytes.readSmithyHeaders(headersStart, payloadStart)
        val payload = bytes.copyOfRange(payloadStart, payloadEnd).decodeToString()
        messages += BedrockEventStreamMessage(
            messageType = headers[":message-type"].orEmpty(),
            eventType = headers[":event-type"].orEmpty().ifBlank {
                headers[":error-code"].orEmpty().replaceFirstChar { it.lowercaseChar() }
            },
            payloadText = payload,
        )
        offset += totalLength
    }
    return messages
}

private fun ByteArray.readSmithyHeaders(start: Int, end: Int): Map<String, String> {
    val headers = linkedMapOf<String, String>()
    var offset = start
    while (offset < end) {
        val nameLength = this[offset].toInt() and 0xff
        offset += 1
        if (nameLength == 0 || offset + nameLength + 1 > end) break
        val name = copyOfRange(offset, offset + nameLength).decodeToString()
        offset += nameLength
        val type = this[offset].toInt() and 0xff
        offset += 1
        when (type) {
            0, 1 -> headers[name] = type.toString()
            2 -> offset += 1
            3 -> offset += 2
            4 -> offset += 4
            5, 8 -> offset += 8
            6, 7 -> {
                if (offset + 2 > end) break
                val length = readUInt16BE(offset)
                offset += 2
                if (offset + length > end) break
                if (type == 7) headers[name] = copyOfRange(offset, offset + length).decodeToString()
                offset += length
            }
            9 -> offset += 16
            else -> break
        }
    }
    return headers
}

private fun ByteArray.readInt32BE(index: Int): Int =
    ((this[index].toInt() and 0xff) shl 24) or
        ((this[index + 1].toInt() and 0xff) shl 16) or
        ((this[index + 2].toInt() and 0xff) shl 8) or
        (this[index + 3].toInt() and 0xff)

private fun ByteArray.readUInt16BE(index: Int): Int =
    ((this[index].toInt() and 0xff) shl 8) or (this[index + 1].toInt() and 0xff)

private suspend fun bedrockHeaders(
    settings: AmazonBedrockProviderSettings,
    extra: Map<String, String>,
    url: String,
    body: String,
    service: String,
    amzDate: String = currentAwsAmzDate(),
): Map<String, String> {
    val headers = linkedMapOf<String, String?>()
    headers[HttpHeaders.ContentType] = ContentType.Application.Json.toString()
    settings.headers.forEach { (key, value) -> headers[key] = value }
    extra.forEach { (key, value) -> headers[key] = value }
    val headersWithUserAgent = ProviderHeaders.withUserAgentSuffix(headers, "ai-sdk/amazon-bedrock/$AMAZON_BEDROCK_VERSION")
    val apiKey = settings.apiKey?.trim()?.takeIf { it.isNotEmpty() }
    if (apiKey != null) {
        return headersWithUserAgent + (HttpHeaders.Authorization to "Bearer $apiKey")
    }
    val credentials = if (settings.accessKeyId != null || settings.secretAccessKey != null || settings.credentialProvider != null) {
        val credentials = settings.credentialProvider?.invoke()
            ?: BedrockCredentials(settings.accessKeyId.orEmpty(), settings.secretAccessKey.orEmpty(), settings.sessionToken, settings.region)
        if (credentials.accessKeyId.isBlank() || credentials.secretAccessKey.isBlank()) {
            throw LoadAPIKeyError("AWS SigV4 authentication requires both accessKeyId and secretAccessKey.")
        }
        credentials
    } else {
        return headersWithUserAgent
    }
    val region = credentials.region ?: settings.region ?: "us-east-1"
    return awsSigV4SignedHeaders(
        method = "POST",
        url = url,
        service = service,
        region = region,
        headers = headersWithUserAgent,
        body = body,
        credentials = AwsSigV4Credentials(
            accessKeyId = credentials.accessKeyId,
            secretAccessKey = credentials.secretAccessKey,
            sessionToken = credentials.sessionToken,
        ),
        amzDate = amzDate,
    )
}

private fun bedrockRuntimeBaseURL(settings: AmazonBedrockProviderSettings): String =
    settings.baseURL?.trimEnd('/')
        ?: "https://bedrock-runtime.${settings.region ?: "us-east-1"}.amazonaws.com"

private fun bedrockAgentRuntimeBaseURL(settings: AmazonBedrockProviderSettings): String =
    settings.agentBaseURL?.trimEnd('/')
        ?: settings.baseURL?.trimEnd('/')
        ?: "https://bedrock-agent-runtime.${settings.region ?: "us-west-2"}.amazonaws.com"

private fun bedrockMantleBaseURL(settings: AmazonBedrockProviderSettings): String =
    settings.baseURL?.trimEnd('/')
        ?: "https://bedrock-mantle.${settings.region ?: "us-east-1"}.api.aws/v1"

private fun bedrockUsage(element: JsonElement?): Usage {
    val obj = element as? JsonObject ?: return Usage()
    val input = obj["inputTokens"]?.jsonPrimitive?.intOrNull ?: 0
    val output = obj["outputTokens"]?.jsonPrimitive?.intOrNull ?: 0
    val cacheRead = obj["cacheReadInputTokens"]?.jsonPrimitive?.intOrNull
        ?: obj["cacheReadInputTokenCount"]?.jsonPrimitive?.intOrNull
        ?: 0
    val cacheWrite = obj["cacheWriteInputTokens"]?.jsonPrimitive?.intOrNull
        ?: obj["cacheWriteInputTokenCount"]?.jsonPrimitive?.intOrNull
        ?: 0
    val safeCacheRead = cacheRead.coerceIn(0, input)
    val safeCacheWrite = cacheWrite.coerceIn(0, input - safeCacheRead)
    return Usage(
        inputTokens = Usage.InputTokenBreakdown(
            total = input,
            noCache = input - safeCacheRead - safeCacheWrite,
            cacheRead = safeCacheRead,
            cacheWrite = safeCacheWrite,
        ),
        outputTokens = Usage.OutputTokenBreakdown(total = output),
        raw = element,
    )
}

private fun bedrockOpenAILikeUsage(element: JsonElement?): Usage {
    val obj = element as? JsonObject ?: return Usage()
    return Usage(
        promptTokens = obj["prompt_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
        completionTokens = obj["completion_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
    )
}

private fun mapBedrockFinishReason(reason: String?, isJsonResponseFromTool: Boolean = false): FinishReason =
    if (isJsonResponseFromTool) {
        FinishReason.Stop
    } else {
        when (reason) {
            "end_turn", "stop_sequence" -> FinishReason.Stop
            "tool_use" -> FinishReason.ToolCalls
            "max_tokens" -> FinishReason.Length
            "content_filtered", "guardrail_intervened" -> FinishReason.ContentFilter
            else -> FinishReason.Other
        }
    }

private fun mapOpenAILikeFinishReason(reason: String?): FinishReason = when (reason) {
    "stop" -> FinishReason.Stop
    "length" -> FinishReason.Length
    "tool_calls" -> FinishReason.ToolCalls
    "content_filter" -> FinishReason.ContentFilter
    else -> FinishReason.Other
}

private fun bedrockMantleMessage(message: ModelMessage): JsonObject = buildJsonObject {
    put(
        "role",
        JsonPrimitive(
            when (message.role) {
                MessageRole.System -> "system"
                MessageRole.User -> "user"
                MessageRole.Assistant -> "assistant"
                MessageRole.Tool -> "tool"
            },
        ),
    )
    put("content", JsonPrimitive(message.content.joinToString("") { if (it is ContentPart.Text) it.text else "" }))
}

private fun bedrockMantleTool(tool: LanguageModelTool): JsonObject = buildJsonObject {
    put("type", JsonPrimitive("function"))
    put(
        "function",
        buildJsonObject {
            put("name", JsonPrimitive(tool.name))
            put("description", JsonPrimitive(tool.description))
            put("parameters", aiSdkJson.parseToJsonElement(tool.parametersSchemaJson))
        },
    )
}

private fun bedrockImageFileBase64(file: ImageGenerationFile): String =
    file.base64 ?: throw UnsupportedFunctionalityError("url-based images", "URL-based images are not supported for Amazon Bedrock image editing. Provide base64 data directly.")

private fun bedrockImageFormat(mediaType: String): String =
    mediaType.substringAfter("image/", "png").substringBefore("+").substringBefore(";")

private fun bedrockDocumentFormat(mediaType: String): String = when (mediaType) {
    "application/pdf" -> "pdf"
    "text/csv" -> "csv"
    "text/html" -> "html"
    "text/plain" -> "txt"
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx"
    "application/msword" -> "doc"
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> "xlsx"
    "application/vnd.ms-excel" -> "xls"
    else -> mediaType.substringAfterLast('/').substringAfterLast('.')
}

private fun bedrockCachePoint(metadata: Map<String, JsonElement>?): JsonElement? =
    (metadata?.get("bedrock") as? JsonObject)?.get("cachePoint")

private fun bedrockCitationsEnabled(metadata: Map<String, JsonElement>?): Boolean =
    ((metadata?.get("bedrock") as? JsonObject)?.get("citations") as? JsonObject)
        ?.get("enabled")?.jsonPrimitive?.contentOrNull == "true"

private fun bedrockEncodeModelId(modelId: String): String =
    modelId.flatMap { ch ->
        if (ch.isLetterOrDigit() || ch in "-_.~") {
            listOf(ch.toString())
        } else {
            listOf("%" + ch.code.toString(16).uppercase().padStart(2, '0'))
        }
    }.joinToString("")

private fun appendUserAgent(existing: String?, suffix: String): String =
    existing?.takeIf { it.isNotBlank() }?.let { "$it $suffix" } ?: suffix

private fun bedrockErrorMessage(parsed: JsonElement?, raw: String): String {
    val obj = parsed as? JsonObject ?: return raw
    val message = obj["message"]?.jsonPrimitive?.contentOrNull
        ?: obj["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
        ?: obj["type"]?.jsonPrimitive?.contentOrNull
        ?: raw
    val code = obj["__type"]?.jsonPrimitive?.contentOrNull
        ?: obj["code"]?.jsonPrimitive?.contentOrNull
        ?: obj["type"]?.jsonPrimitive?.contentOrNull
    return if (isBedrockClockSkewError(code, message)) {
        "Amazon Bedrock request failed because the local clock appears to be skewed. Sync the host clock and retry. Provider message: $message"
    } else {
        message
    }
}

private fun isBedrockClockSkewError(code: String?, message: String): Boolean {
    val codeText = code.orEmpty()
    return codeText.contains("RequestTimeTooSkewed", ignoreCase = true) ||
        codeText.contains("RequestExpired", ignoreCase = true) ||
        message.contains("Signature expired", ignoreCase = true) ||
        message.contains("Request time too skewed", ignoreCase = true)
}

private fun JsonObjectBuilder.putJsonObjectFields(fields: JsonObject) {
    fields.forEach { (key, value) -> if (value !is JsonNull) put(key, value) }
}

private fun Map<String, String>.headerValue(name: String): String? =
    entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
