package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import io.ktor.client.HttpClient
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

public const val AMAZON_BEDROCK_VERSION: String = "4.0.112"

public typealias BedrockProviderOptions = JsonObject
public typealias AmazonBedrockLanguageModelOptions = JsonObject
public typealias AmazonBedrockEmbeddingModelOptions = JsonObject
public typealias AmazonBedrockRerankingModelOptions = JsonObject
public typealias BedrockRerankingOptions = JsonObject

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

public class AmazonBedrockProvider(
    private val client: HttpClient,
    public val settings: AmazonBedrockProviderSettings,
) : Provider {
    override val providerId: String = "amazon-bedrock"
    public val tools: AnthropicTools = anthropicTools

    public operator fun invoke(modelId: ModelId): LanguageModel = languageModel(modelId.value)

    override fun languageModel(modelId: String): LanguageModel =
        BedrockChatLanguageModel(client, settings, modelId, "amazon-bedrock")

    public fun embedding(modelId: ModelId): EmbeddingModel =
        BedrockEmbeddingModel(client, settings, modelId.value)

    public fun textEmbedding(modelId: ModelId): EmbeddingModel = embedding(modelId)
    public fun textEmbeddingModel(modelId: ModelId): EmbeddingModel = embedding(modelId)

    public fun image(modelId: ModelId): ImageModel =
        BedrockImageModel(client, settings, modelId.value)

    public fun reranking(modelId: ModelId): RerankingModel =
        BedrockRerankingModel(client, settings, modelId.value)

    override fun embeddingModel(modelId: String): EmbeddingModel = embedding(ModelId(modelId))
    override fun imageModel(modelId: String): ImageModel = image(ModelId(modelId))
    override fun rerankingModel(modelId: String): RerankingModel = reranking(ModelId(modelId))
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

    public operator fun invoke(modelId: ModelId): LanguageModel = languageModel(modelId.value)

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

    public operator fun invoke(modelId: ModelId): LanguageModel = chat(modelId)

    override fun languageModel(modelId: String): LanguageModel = chat(ModelId(modelId))

    public fun chat(modelId: ModelId): LanguageModel =
        BedrockMantleChatLanguageModel(client, settings, modelId.value, "bedrock-mantle.chat", "/chat/completions")

    public fun responses(modelId: ModelId): LanguageModel =
        BedrockMantleChatLanguageModel(client, settings, modelId.value, "bedrock-mantle.responses", "/responses")

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
        val prepared = BedrockRequest.bedrockChatRequestBody(modelId, params)
        val response = BedrockHttp.bedrockPostJson(
            client = client,
            url = "${BedrockHttp.bedrockRuntimeBaseURL(settings)}/model/${BedrockMapping.bedrockEncodeModelId(modelId)}/converse",
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
            url = "${BedrockHttp.bedrockRuntimeBaseURL(settings)}/model/${BedrockMapping.bedrockEncodeModelId(modelId)}/converse-stream",
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
        val options = params.providerOptions.toMap()["bedrock"] as? JsonObject ?: JsonObject(emptyMap())
        val body = BedrockRequest.bedrockEmbeddingBody(modelId, value, options)
        val response = BedrockHttp.bedrockPostJson(
            client = client,
            url = "${BedrockHttp.bedrockRuntimeBaseURL(settings)}/model/${BedrockMapping.bedrockEncodeModelId(modelId)}/invoke",
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
            url = "${BedrockHttp.bedrockRuntimeBaseURL(settings)}/model/${BedrockMapping.bedrockEncodeModelId(modelId)}/invoke",
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
        val options = params.providerOptions.toMap()["bedrock"] as? JsonObject ?: JsonObject(emptyMap())
        // Default region must match bedrockAgentRuntimeBaseURL's "us-west-2" default — the ARN
        // region is validated against the endpoint region, so a us-east-1 ARN on the us-west-2
        // endpoint failed every out-of-the-box rerank call.
        val body = BedrockRequest.bedrockRerankBody(settings.region ?: "us-west-2", modelId, params, options)
        val response = BedrockHttp.bedrockPostJson(
            client = client,
            url = "${BedrockHttp.bedrockAgentRuntimeBaseURL(settings)}/rerank",
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
            url = "${BedrockHttp.bedrockMantleBaseURL(settings)}$path",
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
            finishReason = BedrockMapping.mapOpenAILikeFinishReason(choice?.get("finish_reason")?.jsonPrimitive?.contentOrNull),
            usage = BedrockMapping.bedrockOpenAILikeUsage(obj["usage"]),
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

internal object BedrockUserAgent {
    fun appendUserAgent(existing: String?, suffix: String): String =
        existing?.takeIf { it.isNotBlank() }?.let { "$it $suffix" } ?: suffix
}
