@file:OptIn(ai.torad.aisdk.LowLevelLanguageModelApi::class)

package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import dev.drewhamilton.poko.Poko
import io.ktor.client.HttpClient
import io.ktor.http.HttpHeaders
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

public const val AMAZON_BEDROCK_VERSION: String = "4.0.112"

public typealias BedrockProviderOptions = JsonObject
public typealias AmazonBedrockLanguageModelOptions = JsonObject
public typealias AmazonBedrockEmbeddingModelOptions = JsonObject
public typealias AmazonBedrockRerankingModelOptions = JsonObject
public typealias BedrockRerankingOptions = JsonObject

@Serializable
@Poko
/** @since 0.3.0-beta01 */
public class BedrockCredentials internal constructor(
    /** @since 0.3.0-beta01 */
    public val accessKeyId: String,
    /** @since 0.3.0-beta01 */
    public val secretAccessKey: String,
    /** @since 0.3.0-beta01 */
    public val sessionToken: String? = null,
    /** @since 0.3.0-beta01 */
    public val region: String? = null,
)

/** @since 0.3.0-beta01 */
public class BedrockCredentialsBuilder {
    private var accessKeyId: String? = null
    private var secretAccessKey: String? = null
    private var sessionToken: String? = null
    private var region: String? = null

    /** @since 0.3.0-beta01 */
    public fun accessKeyId(value: String): BedrockCredentialsBuilder {
        accessKeyId = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun secretAccessKey(value: String): BedrockCredentialsBuilder {
        secretAccessKey = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun sessionToken(value: String?): BedrockCredentialsBuilder {
        sessionToken = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun region(value: String?): BedrockCredentialsBuilder {
        region = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun build(): BedrockCredentials =
        BedrockCredentials(
            accessKeyId = requireNotNull(accessKeyId) { "BedrockCredentials.accessKeyId is required" },
            secretAccessKey = requireNotNull(secretAccessKey) { "BedrockCredentials.secretAccessKey is required" },
            sessionToken = sessionToken,
            region = region,
        )
}

/** @since 0.3.0-beta01 */
public fun BedrockCredentials(
    block: BedrockCredentialsBuilder.() -> Unit = {},
): BedrockCredentials =
    BedrockCredentialsBuilder().apply(block).build()

/** @since 0.3.0-beta01 */
public class AmazonBedrockProviderSettings internal constructor(
    /** @since 0.3.0-beta01 */
    public val region: String? = null,
    /** @since 0.3.0-beta01 */
    public val apiKey: String? = null,
    /** @since 0.3.0-beta01 */
    public val accessKeyId: String? = null,
    /** @since 0.3.0-beta01 */
    public val secretAccessKey: String? = null,
    /** @since 0.3.0-beta01 */
    public val sessionToken: String? = null,
    /** @since 0.3.0-beta01 */
    public val credentialProvider: (suspend () -> BedrockCredentials)? = null,
    /** @since 0.3.0-beta01 */
    public val baseURL: String? = null,
    /** @since 0.3.0-beta01 */
    public val agentBaseURL: String? = null,
    /** @since 0.3.0-beta01 */
    public val headers: Map<String, String> = emptyMap(),
    /** @since 0.3.0-beta01 */
    public val generateId: () -> String = { IdGenerator.generate() },
) {
    internal fun bedrockRuntimeBaseURL(): String =
        baseURL?.trimEnd('/')
            ?: "https://bedrock-runtime.${region ?: "us-east-1"}.amazonaws.com"

    internal fun bedrockAgentRuntimeBaseURL(): String =
        agentBaseURL?.trimEnd('/')
            ?: baseURL?.trimEnd('/')
            ?: "https://bedrock-agent-runtime.${region ?: "us-west-2"}.amazonaws.com"

    internal fun bedrockMantleBaseURL(): String =
        baseURL?.trimEnd('/')
            ?: "https://bedrock-mantle.${region ?: "us-east-1"}.api.aws/v1"

    // Percent-encodes a model id into a URL path segment; shared by every Bedrock
    // model class (chat/embedding/image), which all build URLs from this settings.
    internal fun bedrockEncodeModelId(modelId: String): String =
        modelId.flatMap { ch ->
            if (ch.isLetterOrDigit() || ch in "-_.~") {
                listOf(ch.toString())
            } else {
                listOf("%" + ch.code.toString(16).uppercase().padStart(2, '0'))
            }
        }.joinToString("")
}

/** @since 0.3.0-beta01 */
public class AmazonBedrockProviderSettingsBuilder {
    private var region: String? = null
    private var apiKey: String? = null
    private var accessKeyId: String? = null
    private var secretAccessKey: String? = null
    private var sessionToken: String? = null
    private var credentialProvider: (suspend () -> BedrockCredentials)? = null
    private var baseURL: String? = null
    private var agentBaseURL: String? = null
    private var headers: Map<String, String> = emptyMap()
    private var generateId: () -> String = { IdGenerator.generate() }

    /** @since 0.3.0-beta01 */
    public fun region(value: String?): AmazonBedrockProviderSettingsBuilder {
        region = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun apiKey(value: String?): AmazonBedrockProviderSettingsBuilder {
        apiKey = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun accessKeyId(value: String?): AmazonBedrockProviderSettingsBuilder {
        accessKeyId = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun secretAccessKey(value: String?): AmazonBedrockProviderSettingsBuilder {
        secretAccessKey = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun sessionToken(value: String?): AmazonBedrockProviderSettingsBuilder {
        sessionToken = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun credentialProvider(value: (suspend () -> BedrockCredentials)?): AmazonBedrockProviderSettingsBuilder {
        credentialProvider = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun baseURL(value: String?): AmazonBedrockProviderSettingsBuilder {
        baseURL = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun agentBaseURL(value: String?): AmazonBedrockProviderSettingsBuilder {
        agentBaseURL = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun headers(value: Map<String, String>): AmazonBedrockProviderSettingsBuilder {
        headers = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun generateId(value: () -> String): AmazonBedrockProviderSettingsBuilder {
        generateId = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun build(): AmazonBedrockProviderSettings =
        AmazonBedrockProviderSettings(
            region = region,
            apiKey = apiKey,
            accessKeyId = accessKeyId,
            secretAccessKey = secretAccessKey,
            sessionToken = sessionToken,
            credentialProvider = credentialProvider,
            baseURL = baseURL,
            agentBaseURL = agentBaseURL,
            headers = headers,
            generateId = generateId,
        )
}

/** @since 0.3.0-beta01 */
public fun AmazonBedrockProviderSettings(
    block: AmazonBedrockProviderSettingsBuilder.() -> Unit = {},
): AmazonBedrockProviderSettings =
    AmazonBedrockProviderSettingsBuilder().apply(block).build()

/** @since 0.3.0-beta01 */
public class AmazonBedrockProvider(
    private val client: HttpClient,
    /** @since 0.3.0-beta01 */
    public val settings: AmazonBedrockProviderSettings,
) : Provider {
    override val providerId: String = "amazon-bedrock"
    /** @since 0.3.0-beta01 */
    public val tools: AnthropicTools = anthropicTools

    public operator fun invoke(modelId: ModelId): LanguageModel = languageModel(modelId.value)

    override fun languageModel(modelId: String): LanguageModel =
        BedrockChatLanguageModel(client, settings, modelId, "amazon-bedrock")

    /** @since 0.3.0-beta01 */
    public fun embedding(modelId: ModelId): EmbeddingModel =
        BedrockEmbeddingModel(client, settings, modelId.value)

    /** @since 0.3.0-beta01 */
    public fun textEmbedding(modelId: ModelId): EmbeddingModel = embedding(modelId)
    /** @since 0.3.0-beta01 */
    public fun textEmbeddingModel(modelId: ModelId): EmbeddingModel = embedding(modelId)

    /** @since 0.3.0-beta01 */
    public fun image(modelId: ModelId): ImageModel =
        BedrockImageModel(client, settings, modelId.value)

    /** @since 0.3.0-beta01 */
    public fun reranking(modelId: ModelId): RerankingModel =
        BedrockRerankingModel(client, settings, modelId.value)

    override fun embeddingModel(modelId: String): EmbeddingModel = embedding(ModelId(modelId))
    override fun imageModel(modelId: String): ImageModel = image(ModelId(modelId))
    override fun rerankingModel(modelId: String): RerankingModel = reranking(ModelId(modelId))
}

/**
 * PascalCase factory — mirrors the OpenAI(...) reference faux-constructor.
 * @since 0.3.0-beta01
 */
public fun AmazonBedrock(
    client: HttpClient,
    settings: AmazonBedrockProviderSettings = AmazonBedrockProviderSettings(),
): AmazonBedrockProvider = AmazonBedrockProvider(client, settings)

public typealias BedrockAnthropicProviderSettings = AmazonBedrockProviderSettings

/** @since 0.3.0-beta01 */
public class BedrockAnthropicProvider(
    private val client: HttpClient,
    /** @since 0.3.0-beta01 */
    public val settings: AmazonBedrockProviderSettings,
) : Provider {
    override val providerId: String = "bedrock.anthropic"
    /** @since 0.3.0-beta01 */
    public val tools: AnthropicTools = anthropicTools

    public operator fun invoke(modelId: ModelId): LanguageModel = languageModel(modelId.value)

    override fun languageModel(modelId: String): LanguageModel =
        BedrockChatLanguageModel(client, settings, modelId, "bedrock.anthropic.messages")

    /** @since 0.3.0-beta01 */
    public fun textEmbeddingModel(modelId: String): Nothing = throw NoSuchModelError(providerId, "embeddingModel", modelId)
}

/**
 * PascalCase factory — mirrors the OpenAI(...) reference faux-constructor.
 * @since 0.3.0-beta01
 */
public fun BedrockAnthropic(
    client: HttpClient,
    settings: BedrockAnthropicProviderSettings = BedrockAnthropicProviderSettings(),
): BedrockAnthropicProvider = BedrockAnthropicProvider(client, settings)

public typealias BedrockMantleProviderSettings = AmazonBedrockProviderSettings

/** @since 0.3.0-beta01 */
public class BedrockMantleProvider(
    private val client: HttpClient,
    /** @since 0.3.0-beta01 */
    public val settings: AmazonBedrockProviderSettings,
) : Provider {
    override val providerId: String = "bedrock-mantle"

    public operator fun invoke(modelId: ModelId): LanguageModel = chat(modelId)

    override fun languageModel(modelId: String): LanguageModel = chat(ModelId(modelId))

    /** @since 0.3.0-beta01 */
    public fun chat(modelId: ModelId): LanguageModel =
        BedrockMantleChatLanguageModel(client, settings, modelId.value, "bedrock-mantle.chat", "/chat/completions")

    /** @since 0.3.0-beta01 */
    public fun responses(modelId: ModelId): LanguageModel =
        BedrockMantleChatLanguageModel(client, settings, modelId.value, "bedrock-mantle.responses", "/responses")

    /** @since 0.3.0-beta01 */
    public fun textEmbeddingModel(modelId: String): Nothing = throw NoSuchModelError(providerId, "embeddingModel", modelId)
}

/**
 * PascalCase factory — mirrors the OpenAI(...) reference faux-constructor.
 * @since 0.3.0-beta01
 */
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
        val prepared = BedrockRequest.bedrockChatRequestBody(modelId, params)
        val response = BedrockHttp.bedrockPostJson(
            client = client,
            url = "${settings.bedrockRuntimeBaseURL()}/model/${settings.bedrockEncodeModelId(modelId)}/converse",
            body = prepared.body,
            settings = settings,
            extraHeaders = params.headers,
            abortSignal = params.abortSignal,
            parseJson = true,
        )
        return BedrockResponse.bedrockChatGenerateResult(
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
        val prepared = BedrockRequest.bedrockChatRequestBody(modelId, params)
        emit(StreamEvent.StreamStart(prepared.warnings))
        val state = BedrockStreamState(settings.generateId, prepared.usesJsonResponseTool)
        var sseHeaders: Map<String, String> = emptyMap()
        val payloads = BedrockHttp.bedrockStreamPayloads(
            client = client,
            url = "${settings.bedrockRuntimeBaseURL()}/model/${settings.bedrockEncodeModelId(modelId)}/converse-stream",
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
                        id = BedrockHttp.headerValue(sseHeaders, "x-amzn-requestid"),
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
        val prepared = BedrockRequest.bedrockChatRequestBody(modelId, params)
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
        val options = JsonAccess.obj(params.providerOptions.toMap(), "bedrock") ?: JsonObject(emptyMap())
        val body = BedrockRequest.bedrockEmbeddingBody(modelId, value, options)
        val response = BedrockHttp.bedrockPostJson(
            client = client,
            url = "${settings.bedrockRuntimeBaseURL()}/model/${settings.bedrockEncodeModelId(modelId)}/invoke",
            body = body,
            settings = settings,
            extraHeaders = params.headers,
            abortSignal = params.abortSignal,
            parseJson = true,
        )
        val obj = response.value.jsonObject
        return EmbeddingModelResult(
            embeddings = listOf(BedrockResponse.bedrockEmbeddingVector(obj)),
            usage = EmbeddingUsage(tokens = BedrockResponse.bedrockEmbeddingTokens(obj), raw = response.value),
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
        val prepared = BedrockRequest.bedrockImageBody(params)
        val response = BedrockHttp.bedrockPostJson(
            client = client,
            url = "${settings.bedrockRuntimeBaseURL()}/model/${settings.bedrockEncodeModelId(modelId)}/invoke",
            body = prepared.body,
            settings = settings,
            extraHeaders = params.headers,
            abortSignal = params.abortSignal,
            parseJson = true,
        )
        val obj = response.value.jsonObject
        if ((obj["status"] as? JsonPrimitive)?.contentOrNull == "Request Moderated") {
            throw NoImageGeneratedError("Amazon Bedrock request was moderated: ${obj["details"] ?: "Unknown"}")
        }
        val images = (JsonAccess.arr(obj, "images")).orEmpty().mapNotNull {
            val base64 = (it as? JsonPrimitive)?.content ?: return@mapNotNull null
            GeneratedFile(mediaType = "image/png", base64 = base64)
        }
        if (images.isEmpty()) throw NoImageGeneratedError("Amazon Bedrock returned no images.")
        return ImageModelResult(
            images = images,
            warnings = prepared.warnings,
            response = LanguageModelResponseMetadata(modelId = modelId, headers = response.headers, body = response.value),
            providerMetadata = ProviderMetadata.Raw(JsonObject(mapOf("bedrock" to response.value))),
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
        val options = JsonAccess.obj(params.providerOptions.toMap(), "bedrock") ?: JsonObject(emptyMap())
        // Default region must match bedrockAgentRuntimeBaseURL's "us-west-2" default — the ARN
        // region is validated against the endpoint region, so a us-east-1 ARN on the us-west-2
        // endpoint failed every out-of-the-box rerank call.
        val body = BedrockRequest.bedrockRerankBody(settings.region ?: "us-west-2", modelId, params, options)
        val response = BedrockHttp.bedrockPostJson(
            client = client,
            url = "${settings.bedrockAgentRuntimeBaseURL()}/rerank",
            body = body,
            settings = settings,
            extraHeaders = params.headers,
            abortSignal = params.abortSignal,
            parseJson = true,
        )
        val results = (JsonAccess.arr(response.value.jsonObject, "results")).orEmpty().mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val index = (obj["index"] as? JsonPrimitive)?.intOrNull ?: 0
            RerankedItem(
                value = params.documents.getOrElse(index) { "" },
                score = (obj["relevanceScore"] as? JsonPrimitive)?.floatOrNull
                    ?: (obj["score"] as? JsonPrimitive)?.floatOrNull
                    ?: 0f,
                index = index,
            )
        }
        return RerankingModelResult(
            results = results,
            response = LanguageModelResponseMetadata(modelId = modelId, headers = response.headers, body = response.value),
            providerMetadata = ProviderMetadata.Raw(JsonObject(mapOf("bedrock" to response.value))),
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
            put("messages", JsonArray(params.messages.map { BedrockRequest.bedrockMantleMessage(it) }))
            params.temperature?.let { put("temperature", JsonPrimitive(it)) }
            params.topP?.let { put("top_p", JsonPrimitive(it)) }
            params.maxOutputTokens?.let { put("max_tokens", JsonPrimitive(it)) }
            if (params.stopSequences.isNotEmpty()) put("stop", JsonArray(params.stopSequences.map(::JsonPrimitive)))
            if (params.tools.isNotEmpty()) {
                put("tools", JsonArray(params.tools.map { BedrockRequest.bedrockMantleTool(it) }))
            }
        }
        val response = BedrockHttp.bedrockPostJson(
            client = client,
            url = "${settings.bedrockMantleBaseURL()}$path",
            body = body,
            settings = settings,
            extraHeaders = params.headers,
            service = "bedrock-mantle",
            abortSignal = params.abortSignal,
            parseJson = true,
        )
        val obj = response.value.jsonObject
        val choice = ((JsonAccess.arr(obj, "choices"))?.firstOrNull() as? JsonObject)
        val message = (choice?.get("message") as? JsonObject)
        val content = (message?.get("content") as? JsonPrimitive)?.contentOrNull.orEmpty()
        val toolCalls = (message?.get("tool_calls") as? JsonArray).orEmpty().mapNotNull { call ->
            val callObj = call as? JsonObject ?: return@mapNotNull null
            val function = JsonAccess.obj(callObj, "function") ?: return@mapNotNull null
            val toolName = (function["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val arguments = (function["arguments"] as? JsonPrimitive)?.contentOrNull
            ContentPart.ToolCall(
                toolCallId = (callObj["id"] as? JsonPrimitive)?.contentOrNull ?: IdGenerator.generate("call"),
                toolName = toolName,
                input = ContentPart.ToolCall.parseOpenAIToolInput(arguments),
            )
        }
        return LanguageModelResult(
            text = content,
            toolCalls = toolCalls,
            finishReason = mapOpenAILikeFinishReason((choice?.get("finish_reason") as? JsonPrimitive)?.contentOrNull),
            usage = bedrockOpenAILikeUsage(obj["usage"]),
            request = LanguageModelRequestMetadata(body),
            response = LanguageModelResponseMetadata(
                id = (obj["id"] as? JsonPrimitive)?.contentOrNull,
                modelId = (obj["model"] as? JsonPrimitive)?.contentOrNull ?: modelId,
                headers = response.headers,
                body = response.value,
            ),
            rawFinishReason = (choice?.get("finish_reason") as? JsonPrimitive)?.contentOrNull,
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

    private fun bedrockOpenAILikeUsage(element: JsonElement?): Usage {
        val obj = element as? JsonObject ?: return Usage()
        return Usage.of(
            promptTokens = (obj["prompt_tokens"] as? JsonPrimitive)?.intOrNull ?: 0,
            completionTokens = (obj["completion_tokens"] as? JsonPrimitive)?.intOrNull ?: 0,
        )
    }

    private fun mapOpenAILikeFinishReason(reason: String?): FinishReason = when (reason) {
        "stop" -> FinishReason.Stop
        "length" -> FinishReason.Length
        "tool_calls" -> FinishReason.ToolCalls
        "content_filter" -> FinishReason.ContentFilter
        else -> FinishReason.Other
    }
}
