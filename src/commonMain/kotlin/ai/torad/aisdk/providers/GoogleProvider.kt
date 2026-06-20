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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
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
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

public const val GOOGLE_VERSION: String = "3.0.80"

public typealias GoogleGenerativeAIModelId = String
public typealias GoogleGenerativeAIEmbeddingModelId = String
public typealias GoogleGenerativeAIImageModelId = String
public typealias GoogleGenerativeAIVideoModelId = String
public typealias GoogleInteractionsModelId = String
public typealias GoogleInteractionsAgentName = String
public typealias GoogleLanguageModelOptions = JsonObject
public typealias GoogleGenerativeAIProviderOptions = JsonObject
public typealias GoogleEmbeddingModelOptions = JsonObject
public typealias GoogleGenerativeAIEmbeddingProviderOptions = JsonObject
public typealias GoogleImageModelOptions = JsonObject
public typealias GoogleGenerativeAIImageProviderOptions = JsonObject
public typealias GoogleVideoModelOptions = JsonObject
public typealias GoogleGenerativeAIVideoProviderOptions = JsonObject
public typealias GoogleLanguageModelInteractionsOptions = JsonObject
public typealias GoogleGenerativeAIProviderMetadata = JsonObject
public typealias GoogleInteractionsProviderMetadata = JsonObject
public typealias GoogleErrorData = JsonObject
public typealias GroundingMetadataSchema = JsonObject
public typealias UrlContextMetadataSchema = JsonObject
public typealias UsageMetadataSchema = JsonObject
public typealias SafetyRatingSchema = JsonObject
public typealias PromptFeedbackSchema = JsonObject

public sealed interface GoogleInteractionsModelInput {
    public val name: String

    public data class Model(override val name: String) : GoogleInteractionsModelInput
    public data class Agent(override val name: String) : GoogleInteractionsModelInput
    public data class ManagedAgent(override val name: String) : GoogleInteractionsModelInput
}

@Serializable
public data class GoogleGenerativeAIProviderSettings(
    val baseURL: String = "https://generativelanguage.googleapis.com/v1beta",
    val apiKey: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val generateId: () -> String = { IdGenerator.generate() },
    val name: String = "google.generative-ai",
    val videoPollIntervalMillis: Long = 1_000L,
    val videoMaxPollAttempts: Int = 120,
)

public class GoogleGenerativeAIProvider(
    private val client: HttpClient,
    public val settings: GoogleGenerativeAIProviderSettings = GoogleGenerativeAIProviderSettings(),
) : Provider {
    override val providerId: String = "google"
    public val tools: GoogleTools = GoogleTools()

    public operator fun invoke(modelId: GoogleGenerativeAIModelId): LanguageModel = languageModel(modelId)

    override fun languageModel(modelId: String): LanguageModel =
        GoogleGenerativeAILanguageModel(client, settings, modelId)

    public fun chat(modelId: GoogleGenerativeAIModelId): LanguageModel = languageModel(modelId)
    public fun generativeAI(modelId: GoogleGenerativeAIModelId): LanguageModel = languageModel(modelId)

    public fun embedding(modelId: GoogleGenerativeAIEmbeddingModelId): EmbeddingModel =
        GoogleGenerativeAIEmbeddingModel(client, settings, modelId)
    public fun textEmbedding(modelId: GoogleGenerativeAIEmbeddingModelId): EmbeddingModel = embedding(modelId)
    public fun textEmbeddingModel(modelId: GoogleGenerativeAIEmbeddingModelId): EmbeddingModel = embedding(modelId)

    public fun image(modelId: GoogleGenerativeAIImageModelId): ImageModel =
        GoogleGenerativeAIImageModel(client, settings, modelId)

    public fun video(modelId: GoogleGenerativeAIVideoModelId): VideoModel =
        GoogleGenerativeAIVideoModel(client, settings, modelId)

    public fun interactions(modelIdOrAgent: GoogleInteractionsModelId): LanguageModel =
        interactions(GoogleInteractionsModelInput.Model(modelIdOrAgent))
    public fun interactions(modelIdOrAgent: GoogleInteractionsModelInput): LanguageModel =
        GoogleInteractionsLanguageModel(client, settings, modelIdOrAgent)
    public fun agentInteraction(agentName: GoogleInteractionsAgentName): LanguageModel =
        interactions(GoogleInteractionsModelInput.Agent(agentName))
    public fun managedAgentInteraction(agentName: String): LanguageModel =
        interactions(GoogleInteractionsModelInput.ManagedAgent(agentName))

    override fun embeddingModel(modelId: String): EmbeddingModel = embedding(modelId)
    override fun imageModel(modelId: String): ImageModel = image(modelId)
    override fun videoModel(modelId: String): VideoModel = video(modelId)
}

// Source/binary-compat alias for the factory below — the constructor now lives on the merged class.
private typealias DefaultGoogleGenerativeAIProvider = GoogleGenerativeAIProvider

/** PascalCase factory — mirrors the OpenAI(...) reference faux-constructor. */
public fun GoogleGenerativeAI(
    client: HttpClient,
    settings: GoogleGenerativeAIProviderSettings = GoogleGenerativeAIProviderSettings(),
): GoogleGenerativeAIProvider = GoogleGenerativeAIProvider(client, settings)

public fun createGoogleGenerativeAI(
    client: HttpClient,
    settings: GoogleGenerativeAIProviderSettings = GoogleGenerativeAIProviderSettings(),
): GoogleGenerativeAIProvider = DefaultGoogleGenerativeAIProvider(client, settings)

public data class GoogleTools(
    val googleSearch: Tool<JsonElement, JsonElement, Any?> =
        googleProviderTool("google_search", "google.google_search", "Ground responses with Google Search."),
    val enterpriseWebSearch: Tool<JsonElement, JsonElement, Any?> =
        googleProviderTool("enterprise_web_search", "google.enterprise_web_search", "Ground responses with Enterprise Web Search."),
    val googleMaps: Tool<JsonElement, JsonElement, Any?> =
        googleProviderTool("google_maps", "google.google_maps", "Ground responses with Google Maps."),
    val urlContext: Tool<JsonElement, JsonElement, Any?> =
        googleProviderTool("url_context", "google.url_context", "Fetch URL context through Google."),
    val fileSearch: Tool<JsonElement, JsonElement, Any?> =
        googleProviderTool("file_search", "google.file_search", "Use Gemini File Search."),
    val codeExecution: Tool<JsonElement, JsonElement, Any?> =
        googleProviderTool("code_execution", "google.code_execution", "Use Google hosted code execution."),
    val vertexRagStore: Tool<JsonElement, JsonElement, Any?> =
        googleProviderTool("vertex_rag_store", "google.vertex_rag_store", "Use Vertex RAG Store."),
)

public val googleTools: GoogleTools = GoogleTools()

private class GoogleGenerativeAILanguageModel(
    private val client: HttpClient,
    private val settings: GoogleGenerativeAIProviderSettings,
    override val modelId: String,
) : LanguageModel {
    override val provider: String = settings.name
    override val supportedUrls: Map<String, List<String>> = mapOf(
        "*" to listOf(
            "^${settings.baseURL.trimEnd('/')}/files/.*$",
            "^https://(?:www\\.)?youtube\\.com/watch\\?v=[\\w-]+(?:&[\\w=&.-]*)?$",
            "^https://youtu\\.be/[\\w-]+(?:\\?[\\w=&.-]*)?$",
        ),
    )

    override suspend fun generate(params: LanguageModelCallParams): LanguageModelResult {
        val prepared = googleGenerateContentBody(modelId, settings, params, stream = false)
        val response = googlePostJson(
            client = client,
            url = "${settings.baseURL.trimEnd('/')}/models/$modelId:generateContent",
            body = prepared.body,
            headers = googleHeaders(settings, params.headers),
            abortSignal = params.abortSignal,
            parseJson = true,
        )
        return googleLanguageResult(response.value.jsonObject, prepared.body, response.headers, response.value, prepared.warnings, settings)
    }

    override fun stream(params: LanguageModelCallParams): Flow<StreamEvent> = flow {
        val prepared = googleGenerateContentBody(modelId, settings, params, stream = true)
        emit(StreamEvent.StreamStart(prepared.warnings))
        val state = GoogleStreamState(settings.generateId)
        val rawLines = googleStreamSse(
            client = client,
            url = "${settings.baseURL.trimEnd('/')}/models/$modelId:streamGenerateContent?alt=sse",
            body = prepared.body,
            headers = googleHeaders(settings, params.headers) + (HttpHeaders.Accept to "text/event-stream"),
            abortSignal = params.abortSignal,
        )
        EventStreamParser.parse(rawLines, jsonSchema<JsonElement>(JsonObject(emptyMap())), aiSdkJson).collect { event ->
            when (event) {
                is ParseResult.Success -> state.accept(event.value.jsonObject).forEach { emit(it) }
                is ParseResult.Failure -> emit(StreamEvent.Error("Failed to parse Google stream event: ${event.error.message}"))
            }
        }
        state.finish().forEach { emit(it) }
    }

    override fun streamResult(params: LanguageModelCallParams): LanguageModelStreamResult {
        val prepared = googleGenerateContentBody(modelId, settings, params, stream = true)
        return LanguageModelStreamResult(stream = stream(params), request = LanguageModelRequestMetadata(prepared.body))
    }
}

private class GoogleGenerativeAIEmbeddingModel(
    private val client: HttpClient,
    private val settings: GoogleGenerativeAIProviderSettings,
    override val modelId: String,
) : EmbeddingModel {
    override val provider: String = settings.name
    override val maxEmbeddingsPerCall: Int = 2048
    override val supportsParallelCalls: Boolean = true

    override suspend fun embed(params: EmbeddingModelCallParams): EmbeddingModelResult {
        params.abortSignal.throwIfAborted()
        if (params.values.size > maxEmbeddingsPerCall) {
            throw TooManyEmbeddingValuesForCallError(provider, modelId, maxEmbeddingsPerCall, params.values)
        }
        val options = params.providerOptions["google"] as? JsonObject ?: JsonObject(emptyMap())
        val single = params.values.size == 1
        val body = if (single) {
            googleSingleEmbeddingBody(modelId, params.values.single(), options)
        } else {
            googleBatchEmbeddingBody(modelId, params.values, options)
        }
        val response = googlePostJson(
            client = client,
            url = "${settings.baseURL.trimEnd('/')}/models/$modelId:${if (single) "embedContent" else "batchEmbedContents"}",
            body = body,
            headers = googleHeaders(settings, params.headers),
            abortSignal = params.abortSignal,
            parseJson = true,
        )
        val embeddings = if (single) {
            listOf(response.value.jsonObject["embedding"]?.jsonObject?.get("values")?.jsonArray.orEmpty().map { WireDecoder.embeddingFloat(it, provider) })
        } else {
            response.value.jsonObject["embeddings"]?.jsonArray.orEmpty().map { item ->
                item.jsonObject["values"]?.jsonArray.orEmpty().map { WireDecoder.embeddingFloat(it, provider) }
            }
        }
        return EmbeddingModelResult(
            embeddings = embeddings,
            usage = EmbeddingUsage(raw = response.value),
            request = LanguageModelRequestMetadata(body),
            response = LanguageModelResponseMetadata(modelId = modelId, headers = response.headers, body = response.value),
        )
    }
}

private class GoogleGenerativeAIImageModel(
    private val client: HttpClient,
    private val settings: GoogleGenerativeAIProviderSettings,
    override val modelId: String,
) : ImageModel {
    override val provider: String = settings.name

    override suspend fun generate(params: ImageGenerationParams): ImageModelResult {
        params.abortSignal.throwIfAborted()
        return if (modelId.startsWith("gemini", ignoreCase = true)) {
            generateGeminiImage(params)
        } else {
            generateImagen(params)
        }
    }

    private suspend fun generateImagen(params: ImageGenerationParams): ImageModelResult {
        if (params.files.isNotEmpty()) throw UnsupportedFunctionalityError("imageEditing", "Google Generative AI Imagen models do not support image editing. Use Google Vertex AI for image editing.")
        if (params.mask != null) throw UnsupportedFunctionalityError("imageMask", "Google Generative AI Imagen models do not support masks. Use Google Vertex AI for image editing.")
        val warnings = mutableListOf<CallWarning>()
        if (params.size != null) warnings += CallWarning("unsupported", "size")
        if (params.seed != null) warnings += CallWarning("unsupported", "seed")
        val options = params.providerOptions["google"] as? JsonObject ?: JsonObject(emptyMap())
        val body = buildJsonObject {
            put("instances", JsonArray(listOf(buildJsonObject { put("prompt", JsonPrimitive(params.prompt)) })))
            put(
                "parameters",
                buildJsonObject {
                    put("sampleCount", JsonPrimitive(params.n))
                    put("aspectRatio", options["aspectRatio"] ?: JsonPrimitive(params.aspectRatio ?: "1:1"))
                    putJsonObjectFields(options, excluded = setOf("googleSearch"))
                },
            )
        }
        val response = googlePostJson(
            client = client,
            url = "${settings.baseURL.trimEnd('/')}/models/$modelId:predict",
            body = body,
            headers = googleHeaders(settings, params.headers),
            abortSignal = params.abortSignal,
            parseJson = true,
        )
        val responseObject = WireDecoder.objectValue(response.value, provider, "image generation response")
        val predictions = WireDecoder.requiredArray(responseObject, "predictions", provider, "image generation response")
        val images = predictions.mapIndexed { index, prediction ->
            val obj = WireDecoder.objectValue(prediction, provider, "image generation response", "$.predictions[$index]")
            GeneratedFile(
                mediaType = "image/png",
                base64 = WireDecoder.requiredString(obj, "bytesBase64Encoded", provider, "image generation response", "$.predictions[$index]"),
            )
        }
        if (images.isEmpty()) throw NoImageGeneratedError("Google image response contained no predictions.")
        return ImageModelResult(images, warnings, LanguageModelResponseMetadata(modelId = modelId, headers = response.headers, body = response.value), mapOf("google" to response.value))
    }

    private suspend fun generateGeminiImage(params: ImageGenerationParams): ImageModelResult {
        if (params.n > 1) throw UnsupportedFunctionalityError("imageMultiSample", "Gemini image models do not support n > 1.")
        if (params.mask != null) throw UnsupportedFunctionalityError("imageMask", "Gemini image models do not support mask-based image editing.")
        val message = ModelMessage(
            MessageRole.User,
            buildList {
                add(ContentPart.Text(params.prompt))
                params.files.forEach { file ->
                    add(ContentPart.File(file.mediaType ?: "image/png", file.base64 ?: throw UnsupportedFunctionalityError("imageInputUrl", "Gemini image input URLs are not supported in this facade."), file.filename))
                }
            },
        )
        val result = GoogleGenerativeAILanguageModel(client, settings, modelId).generate(
            LanguageModelCallParams(
                messages = listOf(message),
                seed = params.seed,
                providerOptions = mapOf(
                    "google" to buildJsonObject {
                        put("responseModalities", JsonArray(listOf(JsonPrimitive("IMAGE"))))
                        params.aspectRatio?.let {
                            put("imageConfig", buildJsonObject { put("aspectRatio", JsonPrimitive(it)) })
                        }
                    },
                ),
                headers = params.headers,
                abortSignal = params.abortSignal,
            ),
        )
        val images = result.content.filterIsInstance<ContentPart.File>()
            .map { GeneratedFile(mediaType = it.mediaType, base64 = it.base64, filename = it.filename, providerMetadata = it.providerMetadata.orEmpty()) }
        if (images.isEmpty()) throw NoImageGeneratedError("Gemini image response contained no image file parts.")
        return ImageModelResult(images, result.warnings, result.response, result.providerMetadata)
    }
}

private class GoogleGenerativeAIVideoModel(
    private val client: HttpClient,
    private val settings: GoogleGenerativeAIProviderSettings,
    override val modelId: String,
) : VideoModel {
    override val provider: String = settings.name

    override suspend fun generate(params: VideoGenerationParams): VideoModelResult {
        params.abortSignal.throwIfAborted()
        val options = params.providerOptions["google"] as? JsonObject ?: JsonObject(emptyMap())
        val body = googleVideoRequestBody(params, options)
        val operation = googlePostJson(
            client = client,
            url = "${settings.baseURL.trimEnd('/')}/models/$modelId:predictLongRunning",
            body = body,
            headers = googleHeaders(settings, params.headers),
            abortSignal = params.abortSignal,
            parseJson = true,
        ).value.let { WireDecoder.objectValue(it, provider, "video operation response") }
        val operationName = WireDecoder.requiredString(operation, "name", provider, "video operation response")
        var current = operation
        val pollInterval = options["pollIntervalMs"]?.jsonPrimitive?.intOrNull?.toLong() ?: settings.videoPollIntervalMillis
        val maxAttempts = (options["maxPollAttempts"]?.jsonPrimitive?.intOrNull ?: settings.videoMaxPollAttempts).coerceAtLeast(1)
        var headers = emptyMap<String, String>()
        repeat(maxAttempts) {
            if (current["done"]?.jsonPrimitive?.booleanOrNull == true) return@repeat
            if (pollInterval > 0) delay(pollInterval)
            val poll = googleGetJsonWithRetry(
                client = client,
                url = "${settings.baseURL.trimEnd('/')}/$operationName",
                headers = googleHeaders(settings, params.headers),
                abortSignal = params.abortSignal,
                retryDelayMillis = pollInterval,
            )
            current = WireDecoder.objectValue(poll.value, provider, "video poll response")
            headers = poll.headers
        }
        if (current["done"]?.jsonPrimitive?.booleanOrNull != true) {
            throw AiSdkRuntimeException("Google video generation timed out after $maxAttempts poll attempts.")
        }
        current["error"]?.jsonObject?.let { throw AiSdkRuntimeException("Google video generation failed: ${it["message"]?.jsonPrimitive?.contentOrNull ?: it}") }
        val responseObject = WireDecoder.objectValue(
            WireDecoder.required(current, "response", provider, "video poll response"),
            provider,
            "video poll response",
            "$.response",
        )
        val videoResponse = WireDecoder.objectValue(
            WireDecoder.required(responseObject, "generateVideoResponse", provider, "video poll response", "$.response"),
            provider,
            "video poll response",
            "$.response.generateVideoResponse",
        )
        val samples = WireDecoder.requiredArray(videoResponse, "generatedSamples", provider, "video poll response", "$.response.generateVideoResponse")
        val videos = samples.mapIndexed { index, sample ->
            val sampleObject = WireDecoder.objectValue(sample, provider, "video poll response", "$.response.generateVideoResponse.generatedSamples[$index]")
            val video = WireDecoder.objectValue(
                WireDecoder.required(sampleObject, "video", provider, "video poll response", "$.response.generateVideoResponse.generatedSamples[$index]"),
                provider,
                "video poll response",
                "$.response.generateVideoResponse.generatedSamples[$index].video",
            )
            val uri = WireDecoder.requiredString(video, "uri", provider, "video poll response", "$.response.generateVideoResponse.generatedSamples[$index].video")
            GeneratedFile(
                mediaType = "video/mp4",
                base64 = "",
                url = uri,
                providerMetadata = mapOf(
                    "google" to buildJsonObject {
                        put("uri", JsonPrimitive(uri))
                        put("requiresApiKey", JsonPrimitive(settings.apiKey != null))
                    },
                ),
            )
        }
        if (videos.isEmpty()) throw NoVideoGeneratedError("Google video response contained no videos.")
        return VideoModelResult(videos = videos, response = LanguageModelResponseMetadata(modelId = modelId, headers = headers, body = current), providerMetadata = mapOf("google" to current))
    }
}

private class GoogleInteractionsLanguageModel(
    private val client: HttpClient,
    private val settings: GoogleGenerativeAIProviderSettings,
    private val modelInput: GoogleInteractionsModelInput,
) : LanguageModel {
    override val modelId: String = modelInput.name
    override val provider: String = "${settings.name}.interactions"
    override val supportedUrls: Map<String, List<String>> = mapOf(
        "image/*" to listOf("^https?://.+"),
        "application/pdf" to listOf("^https?://.+"),
        "audio/*" to listOf("^https?://.+"),
        "video/*" to listOf(
            "^https?://(www\\.)?youtube\\.com/watch\\?v=.+",
            "^https?://youtu\\.be/.+",
            "^gs://.+",
        ),
    )

    override suspend fun generate(params: LanguageModelCallParams): LanguageModelResult {
        val prepared = googleInteractionsRequestBody(modelInput, params, stream = false)
        val response = googlePostJson(
            client = client,
            url = "${settings.baseURL.trimEnd('/')}/interactions",
            body = prepared.body,
            headers = googleInteractionsHeaders(settings, params.headers),
            abortSignal = params.abortSignal,
            parseJson = true,
        )
        var body = response.value.jsonObject
        if (modelInput !is GoogleInteractionsModelInput.Model && !googleInteractionsTerminal(body["status"]?.jsonPrimitive?.contentOrNull)) {
            body = googlePollInteraction(
                client = client,
                settings = settings,
                interactionId = body["id"]?.jsonPrimitive?.contentOrNull
                    ?: throw InvalidResponseDataError(body, "google.interactions: background response did not include an interaction id."),
                headers = googleInteractionsHeaders(settings, params.headers),
                abortSignal = params.abortSignal,
                timeoutMillis = prepared.pollingTimeoutMillis,
            ).value.jsonObject
        }
        return googleInteractionsResult(body, prepared.body, response.headers, response.value, prepared.warnings, settings)
    }

    override fun stream(params: LanguageModelCallParams): Flow<StreamEvent> = flow {
        val prepared = googleInteractionsRequestBody(modelInput, params, stream = true)
        emit(StreamEvent.StreamStart(prepared.warnings))
        val state = GoogleInteractionsStreamState(settings.generateId)
        if (prepared.isBackground) {
            val post = googlePostJson(
                client = client,
                url = "${settings.baseURL.trimEnd('/')}/interactions",
                body = prepared.body,
                headers = googleInteractionsHeaders(settings, params.headers),
                abortSignal = params.abortSignal,
                parseJson = true,
            )
            val postBody = post.value.jsonObject
            if (googleInteractionsTerminal(postBody["status"]?.jsonPrimitive?.contentOrNull)) {
                state.synthesize(postBody).forEach { emit(it) }
            } else {
                val interactionId = postBody["id"]?.jsonPrimitive?.contentOrNull
                    ?: throw InvalidResponseDataError(postBody, "google.interactions: background response did not include an interaction id.")
                val rawLines = googleStreamSseGet(
                    client = client,
                    url = "${settings.baseURL.trimEnd('/')}/interactions/$interactionId?stream=true",
                    headers = googleInteractionsHeaders(settings, params.headers) + (HttpHeaders.Accept to "text/event-stream"),
                    abortSignal = params.abortSignal,
                )
                collectGoogleInteractions(rawLines, state)
            }
        } else {
            val rawLines = googleStreamSse(
                client = client,
                url = "${settings.baseURL.trimEnd('/')}/interactions",
                body = prepared.body,
                headers = googleInteractionsHeaders(settings, params.headers) + (HttpHeaders.Accept to "text/event-stream"),
                abortSignal = params.abortSignal,
            )
            collectGoogleInteractions(rawLines, state)
        }
        state.finishIfNeeded().forEach { emit(it) }
    }

    override fun streamResult(params: LanguageModelCallParams): LanguageModelStreamResult {
        val prepared = googleInteractionsRequestBody(modelInput, params, stream = true)
        return LanguageModelStreamResult(stream = stream(params), request = LanguageModelRequestMetadata(prepared.body))
    }
}

private data class GoogleInteractionsPreparedRequest(
    val body: JsonObject,
    val warnings: List<CallWarning>,
    val pollingTimeoutMillis: Long?,
    val isBackground: Boolean,
)

private data class GooglePreparedRequest(
    val body: JsonObject,
    val warnings: List<CallWarning>,
)

private data class GoogleConvertedMessages(
    val contents: JsonArray,
    val systemInstruction: JsonObject?,
    val warnings: List<CallWarning>,
)


private fun googleInteractionsRequestBody(
    input: GoogleInteractionsModelInput,
    params: LanguageModelCallParams,
    stream: Boolean,
): GoogleInteractionsPreparedRequest {
    val warnings = mutableListOf<CallWarning>()
    val options = params.providerOptions["google"] as? JsonObject ?: JsonObject(emptyMap())
    val isAgent = input !is GoogleInteractionsModelInput.Model
    val isBackground = options["background"]?.jsonPrimitive?.booleanOrNull == true
    val converted = googleInteractionsInput(
        messages = params.messages,
        mediaResolution = options["mediaResolution"]?.jsonPrimitive?.contentOrNull,
    )
    warnings += converted.warnings

    val responseFormat = googleInteractionsResponseFormat(params.responseFormat, options, isAgent, warnings)
    val tools = if (isAgent) {
        if (params.tools.isNotEmpty()) {
            warnings += CallWarning("other", "google.interactions: tools are not supported when an agent is set; tools will be omitted from the request body.")
        }
        JsonArray(emptyList())
    } else {
        googleInteractionsTools(params.tools, warnings)
    }
    val toolChoice = if (isAgent || tools.isEmpty()) null else googleInteractionsToolChoice(params.toolChoice)
    val generationConfig = if (isAgent) {
        val dropped = buildList {
            if (params.temperature != null) add("temperature")
            if (params.topP != null) add("topP")
            if (params.seed != null) add("seed")
            if (params.stopSequences.isNotEmpty()) add("stopSequences")
            if (params.maxOutputTokens != null) add("maxOutputTokens")
            if (options["thinkingLevel"] != null) add("thinkingLevel")
            if (options["thinkingSummaries"] != null) add("thinkingSummaries")
            if (options["imageConfig"] != null) add("imageConfig")
        }
        if (dropped.isNotEmpty()) {
            warnings += CallWarning("other", "google.interactions: ${dropped.joinToString()} are not supported when an agent is set; use providerOptions.google.agentConfig instead. Dropped from the request body.")
        }
        null
    } else {
        buildJsonObject {
            params.temperature?.let { put("temperature", JsonPrimitive(it)) }
            params.topP?.let { put("top_p", JsonPrimitive(it)) }
            params.seed?.let { put("seed", JsonPrimitive(it)) }
            if (params.stopSequences.isNotEmpty()) put("stop_sequences", JsonArray(params.stopSequences.map(::JsonPrimitive)))
            params.maxOutputTokens?.let { put("max_output_tokens", JsonPrimitive(it)) }
            options["thinkingLevel"]?.let { put("thinking_level", it) }
            options["thinkingSummaries"]?.let { put("thinking_summaries", it) }
            toolChoice?.let { put("tool_choice", it) }
        }.takeIf { it.isNotEmpty() }
    }

    val systemInstruction = converted.systemInstruction
        ?: options["systemInstruction"]?.jsonPrimitive?.contentOrNull

    return GoogleInteractionsPreparedRequest(
        body = buildJsonObject {
            when (input) {
                is GoogleInteractionsModelInput.Model -> put("model", JsonPrimitive(input.name))
                is GoogleInteractionsModelInput.Agent -> put("agent", JsonPrimitive(input.name))
                is GoogleInteractionsModelInput.ManagedAgent -> put("agent", JsonPrimitive(input.name))
            }
            put("input", converted.input)
            systemInstruction?.let { put("system_instruction", JsonPrimitive(it)) }
            if (tools.isNotEmpty()) put("tools", tools)
            if (responseFormat.isNotEmpty()) put("response_format", responseFormat)
            options["responseModalities"]?.let { put("response_modalities", it) }
            options["previousInteractionId"]?.let { put("previous_interaction_id", it) }
            options["serviceTier"]?.let { put("service_tier", it) }
            options["store"]?.let { put("store", it) }
            generationConfig?.let { put("generation_config", it) }
            googleInteractionsAgentConfig(options)?.let { put("agent_config", it) }
            googleInteractionsEnvironment(options["environment"], isAgent, warnings)?.let { put("environment", it) }
            options["background"]?.let { put("background", it) }
            if (stream && !isBackground) put("stream", JsonPrimitive(true))
        },
        warnings = warnings,
        pollingTimeoutMillis = options["pollingTimeoutMs"]?.jsonPrimitive?.intOrNull?.toLong(),
        isBackground = isBackground,
    )
}

private data class GoogleInteractionsConvertedInput(
    val input: JsonArray,
    val systemInstruction: String?,
    val warnings: List<CallWarning>,
)

private fun googleInteractionsInput(
    messages: List<ModelMessage>,
    mediaResolution: String?,
): GoogleInteractionsConvertedInput {
    val warnings = mutableListOf<CallWarning>()
    val steps = mutableListOf<JsonElement>()
    val systemTexts = mutableListOf<String>()
    for (message in messages) {
        when (message.role) {
            MessageRole.System -> systemTexts += message.content.filterIsInstance<ContentPart.Text>().map { it.text }
            MessageRole.User -> {
                val content = message.content.mapNotNull { part ->
                    when (part) {
                        is ContentPart.Text -> buildJsonObject {
                            put("type", JsonPrimitive("text"))
                            put("text", JsonPrimitive(part.text))
                        }
                        is ContentPart.File -> googleInteractionsFileContent(part.mediaType, part.base64, mediaResolution)
                        is ContentPart.Image -> googleInteractionsFileContent(part.mediaType, part.base64, mediaResolution)
                        else -> null
                    }
                }
                if (content.isNotEmpty()) steps += buildJsonObject {
                    put("type", JsonPrimitive("user_input"))
                    put("content", JsonArray(content))
                }
            }
            MessageRole.Assistant -> {
                val pending = mutableListOf<JsonElement>()
                fun flush() {
                    if (pending.isNotEmpty()) {
                        steps += buildJsonObject {
                            put("type", JsonPrimitive("model_output"))
                            put("content", JsonArray(pending.toList()))
                        }
                        pending.clear()
                    }
                }
                for (part in message.content) {
                    when (part) {
                        is ContentPart.Text -> pending += buildJsonObject {
                            put("type", JsonPrimitive("text"))
                            put("text", JsonPrimitive(part.text))
                        }
                        is ContentPart.File -> pending += googleInteractionsFileContent(part.mediaType, part.base64, mediaResolution)
                        is ContentPart.Image -> pending += googleInteractionsFileContent(part.mediaType, part.base64, mediaResolution)
                        is ContentPart.Reasoning -> {
                            flush()
                            steps += buildJsonObject {
                                put("type", JsonPrimitive("thought"))
                                googleInteractionsSignature(part.providerMetadata)?.let { put("signature", JsonPrimitive(it)) }
                                if (part.text.isNotBlank()) {
                                    put(
                                        "summary",
                                        JsonArray(
                                            listOf(
                                                buildJsonObject {
                                                    put("type", JsonPrimitive("text"))
                                                    put("text", JsonPrimitive(part.text))
                                                },
                                            ),
                                        ),
                                    )
                                }
                            }
                        }
                        is ContentPart.ToolCall -> {
                            flush()
                            steps += buildJsonObject {
                                put("type", JsonPrimitive("function_call"))
                                put("id", JsonPrimitive(part.toolCallId))
                                put("name", JsonPrimitive(part.toolName))
                                put("arguments", part.input)
                                googleInteractionsSignature(part.providerMetadata)?.let { put("signature", JsonPrimitive(it)) }
                            }
                        }
                        else -> warnings += CallWarning("other", "google.interactions: unsupported assistant content part; part dropped.")
                    }
                }
                flush()
            }
            MessageRole.Tool -> {
                val content = message.content.filterIsInstance<ContentPart.ToolResult>().map { part ->
                    buildJsonObject {
                        put("type", JsonPrimitive("function_result"))
                        put("call_id", JsonPrimitive(part.toolCallId))
                        put("name", JsonPrimitive(part.toolName))
                        put("result", part.modelVisible)
                        put("is_error", JsonPrimitive(part.isError))
                        googleInteractionsSignature(part.providerMetadata)?.let { put("signature", JsonPrimitive(it)) }
                    }
                }
                if (content.isNotEmpty()) steps += buildJsonObject {
                    put("type", JsonPrimitive("user_input"))
                    put("content", JsonArray(content))
                }
            }
        }
    }
    return GoogleInteractionsConvertedInput(
        input = JsonArray(steps),
        systemInstruction = systemTexts.takeIf { it.isNotEmpty() }?.joinToString("\n\n"),
        warnings = warnings,
    )
}

private fun googleInteractionsFileContent(mediaType: String, base64: String, mediaResolution: String?): JsonObject {
    val type = when (mediaType.substringBefore('/')) {
        "image" -> "image"
        "audio" -> "audio"
        "video" -> "video"
        else -> "document"
    }
    return buildJsonObject {
        put("type", JsonPrimitive(type))
        put("data", JsonPrimitive(base64))
        put("mime_type", JsonPrimitive(mediaType))
        if ((type == "image" || type == "video") && mediaResolution != null) {
            put("resolution", JsonPrimitive(mediaResolution))
        }
    }
}

private fun googleInteractionsResponseFormat(
    responseFormat: ResponseFormat,
    options: JsonObject,
    isAgent: Boolean,
    warnings: MutableList<CallWarning>,
): JsonArray {
    val entries = mutableListOf<JsonElement>()
    if (responseFormat is ResponseFormat.Json) {
        if (isAgent) {
            warnings += CallWarning("other", "google.interactions: structured output is not supported when an agent is set; responseFormat will be ignored.")
        } else {
            entries += buildJsonObject {
                put("type", JsonPrimitive("text"))
                put("mime_type", JsonPrimitive("application/json"))
                responseFormat.schemaJson?.let { put("schema", it) }
            }
        }
    }
    (options["responseFormat"] as? JsonArray)?.forEach { entry ->
        val obj = entry.jsonObject
        entries += buildJsonObject {
            obj["type"]?.let { put("type", it) }
            obj["mimeType"]?.let { put("mime_type", it) }
            obj["schema"]?.let { put("schema", it) }
            obj["aspectRatio"]?.let { put("aspect_ratio", it) }
            obj["imageSize"]?.let { put("image_size", it) }
        }
    }
    options["imageConfig"]?.jsonObject?.let { image ->
        warnings += CallWarning("other", "google.interactions: providerOptions.google.imageConfig is deprecated. Use providerOptions.google.responseFormat with an image entry instead.")
        if (entries.none { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "image" }) {
            entries += buildJsonObject {
                put("type", JsonPrimitive("image"))
                put("mime_type", JsonPrimitive("image/png"))
                image["aspectRatio"]?.let { put("aspect_ratio", it) }
                image["imageSize"]?.let { put("image_size", it) }
            }
        }
    }
    return JsonArray(entries)
}

private fun googleInteractionsTools(
    tools: List<LanguageModelTool>,
    warnings: MutableList<CallWarning>,
): JsonArray = JsonArray(
    tools.mapNotNull { tool ->
        if (!tool.providerExecuted) {
            buildJsonObject {
                put("type", JsonPrimitive("function"))
                put("name", JsonPrimitive(tool.name))
                if (tool.description.isNotBlank()) put("description", JsonPrimitive(tool.description))
                put("parameters", aiSdkJson.parseToJsonElement(tool.parametersSchemaJson))
            }
        } else {
            when (tool.metadata["providerToolId"]?.jsonPrimitive?.contentOrNull) {
                "google.google_search" -> buildJsonObject { put("type", JsonPrimitive("google_search")) }
                "google.code_execution" -> buildJsonObject { put("type", JsonPrimitive("code_execution")) }
                "google.url_context" -> buildJsonObject { put("type", JsonPrimitive("url_context")) }
                "google.file_search" -> buildJsonObject { put("type", JsonPrimitive("file_search")) }
                "google.google_maps" -> buildJsonObject { put("type", JsonPrimitive("google_maps")) }
                "google.mcp_server" -> buildJsonObject { put("type", JsonPrimitive("mcp_server")) }
                "google.retrieval" -> buildJsonObject {
                    put("type", JsonPrimitive("retrieval"))
                    put("retrieval_types", JsonArray(listOf(JsonPrimitive("vertex_ai_search"))))
                }
                else -> {
                    warnings += CallWarning("unsupported", "provider-defined tool ${tool.name} is not supported by google.interactions; tool dropped.")
                    null
                }
            }
        }
    },
)

private fun googleInteractionsToolChoice(choice: ToolChoice): JsonElement? = when (choice) {
    ToolChoice.Auto -> JsonPrimitive("auto")
    ToolChoice.Required -> JsonPrimitive("any")
    ToolChoice.None -> JsonPrimitive("none")
    is ToolChoice.Specific -> buildJsonObject {
        put(
            "allowed_tools",
            buildJsonObject {
                put("mode", JsonPrimitive("validated"))
                put("tools", JsonArray(listOf(JsonPrimitive(choice.toolName))))
            },
        )
    }
}

private fun googleInteractionsAgentConfig(options: JsonObject): JsonObject? {
    val config = options["agentConfig"]?.jsonObject ?: return null
    val type = config["type"]?.jsonPrimitive?.contentOrNull ?: return null
    return buildJsonObject {
        put("type", JsonPrimitive(type))
        if (type == "deep-research") {
            config["thinkingSummaries"]?.let { put("thinking_summaries", it) }
            config["visualization"]?.let { put("visualization", it) }
            config["collaborativePlanning"]?.let { put("collaborative_planning", it) }
        }
    }
}

private fun googleInteractionsEnvironment(
    element: JsonElement?,
    isAgent: Boolean,
    warnings: MutableList<CallWarning>,
): JsonElement? {
    if (element == null) return null
    if (!isAgent) {
        warnings += CallWarning("other", "google.interactions: environment is only supported when an agent is set; environment will be omitted from the request body.")
        return null
    }
    if (element is JsonPrimitive) return element
    val obj = element.jsonObject
    return buildJsonObject {
        put("type", obj["type"] ?: JsonPrimitive("remote"))
        obj["sources"]?.let { put("sources", it) }
        obj["network"]?.let { put("network", googleInteractionsNetwork(it)) }
    }
}

private fun googleInteractionsNetwork(element: JsonElement): JsonElement =
    if (element is JsonPrimitive) {
        element
    } else {
        val obj = element.jsonObject
        buildJsonObject { obj["allowlist"]?.let { put("allowlist", it) } }
    }

private fun googleInteractionsResult(
    response: JsonObject,
    requestBody: JsonObject,
    headers: Map<String, String>,
    rawBody: JsonElement,
    warnings: List<CallWarning>,
    settings: GoogleGenerativeAIProviderSettings,
): LanguageModelResult {
    val interactionId = response["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    val parsed = googleInteractionsContent(response["steps"] as? JsonArray, settings.generateId, interactionId)
    val text = parsed.content.filterIsInstance<ContentPart.Text>().joinToString("") { it.text }
    val status = response["status"]?.jsonPrimitive?.contentOrNull
    val serviceTier = response["service_tier"]?.jsonPrimitive?.contentOrNull ?: headers["x-gemini-service-tier"]
    val providerMetadata = buildJsonObject {
        interactionId?.let { put("interactionId", JsonPrimitive(it)) }
        serviceTier?.let { put("serviceTier", JsonPrimitive(it)) }
    }
    return LanguageModelResult(
        text = text,
        toolCalls = parsed.content.filterIsInstance<ContentPart.ToolCall>(),
        finishReason = googleInteractionsFinishReason(status, parsed.hasFunctionCall),
        usage = googleInteractionsUsage(response["usage"]),
        providerMetadata = if (providerMetadata.isEmpty()) emptyMap() else mapOf("google" to providerMetadata),
        content = parsed.content,
        rawFinishReason = status,
        warnings = warnings,
        request = LanguageModelRequestMetadata(requestBody),
        response = LanguageModelResponseMetadata(
            id = interactionId,
            modelId = response["model"]?.jsonPrimitive?.contentOrNull,
            headers = headers,
            body = rawBody,
        ),
    )
}

private data class GoogleInteractionsParsedContent(
    val content: List<ContentPart>,
    val hasFunctionCall: Boolean,
)

private fun googleInteractionsContent(
    steps: JsonArray?,
    generateId: () -> String,
    interactionId: String?,
): GoogleInteractionsParsedContent {
    val content = mutableListOf<ContentPart>()
    var hasFunctionCall = false
    steps.orEmpty().forEach { stepElement ->
        val step = stepElement.jsonObject
        when (val type = step["type"]?.jsonPrimitive?.contentOrNull) {
            "model_output" -> {
                step["content"]?.jsonArray.orEmpty().forEach { blockElement ->
                    val block = blockElement.jsonObject
                    when (block["type"]?.jsonPrimitive?.contentOrNull) {
                        "text" -> {
                            val metadata = googleInteractionsMetadata(interactionId = interactionId)
                            content += ContentPart.Text(block["text"]?.jsonPrimitive?.contentOrNull.orEmpty(), metadata)
                            content += googleInteractionsAnnotationSources(block["annotations"] as? JsonArray, generateId, metadata)
                        }
                        "image" -> {
                            val metadata = googleInteractionsMetadata(
                                interactionId = interactionId,
                                extra = block["uri"]?.jsonPrimitive?.contentOrNull?.let { mapOf("imageUri" to JsonPrimitive(it)) }.orEmpty(),
                            )
                            content += ContentPart.File(
                                mediaType = block["mime_type"]?.jsonPrimitive?.contentOrNull ?: "image/png",
                                base64 = block["data"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                                providerMetadata = metadata,
                            )
                        }
                    }
                }
            }
            "thought" -> {
                content += ContentPart.Reasoning(
                    text = step["summary"]?.jsonArray.orEmpty()
                        .mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
                        .joinToString("\n"),
                    providerMetadata = googleInteractionsMetadata(
                        signature = step["signature"]?.jsonPrimitive?.contentOrNull,
                        interactionId = interactionId,
                    ),
                )
            }
            "function_call" -> {
                hasFunctionCall = true
                content += ContentPart.ToolCall(
                    toolCallId = step["id"]?.jsonPrimitive?.contentOrNull ?: IdGenerator.generate(),
                    toolName = step["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    input = step["arguments"] ?: JsonObject(emptyMap()),
                    providerMetadata = googleInteractionsMetadata(
                        signature = step["signature"]?.jsonPrimitive?.contentOrNull,
                        interactionId = interactionId,
                    ),
                )
            }
            else -> {
                if (type != null && type.endsWith("_call")) {
                    hasFunctionCall = true
                    content += ContentPart.ToolCall(
                        toolCallId = step["id"]?.jsonPrimitive?.contentOrNull ?: IdGenerator.generate(),
                        toolName = if (type == "mcp_server_tool_call") {
                            step["name"]?.jsonPrimitive?.contentOrNull ?: "mcp_server_tool"
                        } else {
                            type.removeSuffix("_call")
                        },
                        input = step["arguments"] ?: JsonObject(emptyMap()),
                        providerMetadata = mapOf("google" to buildJsonObject { put("providerExecuted", JsonPrimitive(true)) }),
                    )
                } else if (type != null && type.endsWith("_result")) {
                    content += ContentPart.ToolResult(
                        toolCallId = step["call_id"]?.jsonPrimitive?.contentOrNull ?: IdGenerator.generate(),
                        toolName = if (type == "mcp_server_tool_result") {
                            step["name"]?.jsonPrimitive?.contentOrNull ?: "mcp_server_tool"
                        } else {
                            type.removeSuffix("_result")
                        },
                        output = step["result"] ?: JsonNull,
                        isError = step["is_error"]?.jsonPrimitive?.booleanOrNull == true,
                        providerMetadata = mapOf("google" to buildJsonObject { put("providerExecuted", JsonPrimitive(true)) }),
                    )
                }
            }
        }
    }
    return GoogleInteractionsParsedContent(content, hasFunctionCall)
}

private fun googleInteractionsAnnotationSources(
    annotations: JsonArray?,
    generateId: () -> String,
    metadata: Map<String, JsonElement>?,
): List<ContentPart.Source> = annotations.orEmpty().mapNotNull { annotationElement ->
    val annotation = annotationElement.jsonObject
    val type = annotation["type"]?.jsonPrimitive?.contentOrNull
    val url = annotation["url"]?.jsonPrimitive?.contentOrNull
        ?: annotation["document_uri"]?.jsonPrimitive?.contentOrNull
        ?: return@mapNotNull null
    when (type) {
        "url_citation", "place_citation" -> ContentPart.Source(
            sourceType = StreamEvent.SourcePart.SourceType.Url,
            url = url,
            title = annotation["title"]?.jsonPrimitive?.contentOrNull ?: annotation["name"]?.jsonPrimitive?.contentOrNull,
            providerMetadata = metadata ?: mapOf("google" to buildJsonObject { put("id", JsonPrimitive(IdGenerator.generate())) }),
        )
        "file_citation" -> ContentPart.Source(
            sourceType = StreamEvent.SourcePart.SourceType.Document,
            url = url,
            title = annotation["file_name"]?.jsonPrimitive?.contentOrNull,
            providerMetadata = metadata ?: mapOf("google" to buildJsonObject { put("id", JsonPrimitive(IdGenerator.generate())) }),
        )
        else -> null
    }
}

private class GoogleInteractionsStreamState(
    private val generateId: () -> String,
) {
    private var textId: String? = null
    private var textCounter = 0
    private var usage = Usage()
    private var finishReason = FinishReason.Other
    private var rawFinishReason: String? = null
    private var hasFunctionCall = false
    private var finished = false

    fun accept(event: JsonObject): List<StreamEvent> {
        val events = mutableListOf<StreamEvent>()
        val interaction = event["interaction"]?.jsonObject
        val interactionId = interaction?.get("id")?.jsonPrimitive?.contentOrNull
        if (interaction != null) {
            events += StreamEvent.ResponseMetadata(
                id = interactionId,
                modelId = interaction["model"]?.jsonPrimitive?.contentOrNull,
                body = interaction,
            )
        }
        val step = event["step"]?.jsonObject
        if (step != null) {
            events += acceptStep(step, interactionId)
        }
        if (event["type"]?.jsonPrimitive?.contentOrNull == "interaction.complete" && interaction != null) {
            usage = googleInteractionsUsage(interaction["usage"])
            rawFinishReason = interaction["status"]?.jsonPrimitive?.contentOrNull
            finishReason = googleInteractionsFinishReason(rawFinishReason, hasFunctionCall)
            events += closeText()
            events += StreamEvent.Finish(
                1,
                finishReason,
                usage,
                googleInteractionsMetadata(interactionId = interactionId),
                rawFinishReason = rawFinishReason,
            )
            finished = true
        }
        return events
    }

    fun synthesize(response: JsonObject): List<StreamEvent> {
        val events = mutableListOf<StreamEvent>()
        val interactionId = response["id"]?.jsonPrimitive?.contentOrNull
        events += StreamEvent.ResponseMetadata(
            id = interactionId,
            modelId = response["model"]?.jsonPrimitive?.contentOrNull,
            body = response,
        )
        response["steps"]?.jsonArray.orEmpty().forEach { step ->
            events += acceptStep(step.jsonObject, interactionId)
        }
        usage = googleInteractionsUsage(response["usage"])
        rawFinishReason = response["status"]?.jsonPrimitive?.contentOrNull
        finishReason = googleInteractionsFinishReason(rawFinishReason, hasFunctionCall)
        events += closeText()
        events += StreamEvent.Finish(
            1,
            finishReason,
            usage,
            googleInteractionsMetadata(interactionId = interactionId),
            rawFinishReason = rawFinishReason,
        )
        finished = true
        return events
    }

    private fun acceptStep(step: JsonObject, interactionId: String?): List<StreamEvent> {
        val events = mutableListOf<StreamEvent>()
        when (val type = step["type"]?.jsonPrimitive?.contentOrNull) {
            "model_output" -> {
                step["content"]?.jsonArray.orEmpty().forEachIndexed { index, blockElement ->
                    val block = try {
                        WireDecoder.objectValue(blockElement, "google", "interactions stream step", "$.content[$index]")
                    } catch (error: WireDecodeException) {
                        return listOf(StreamEvent.Error(error.message ?: "Google stream protocol error"))
                    }
                    when (val blockType = WireDecoder.optionalString(block, "type", "google", "interactions stream step", "$.content[$index]")) {
                        "text" -> {
                            val id = textId ?: (textCounter++).toString().also {
                                textId = it
                                events += StreamEvent.TextStart(it, googleInteractionsMetadata(interactionId = interactionId))
                            }
                            val text = try {
                                WireDecoder.requiredString(block, "text", "google", "interactions stream step", "$.content[$index]")
                            } catch (error: WireDecodeException) {
                                return listOf(StreamEvent.Error(error.message ?: "Google stream protocol error"))
                            }
                            events += StreamEvent.TextDelta(id, text, googleInteractionsMetadata(interactionId = interactionId))
                        }
                        "image" -> events += StreamEvent.FilePart(
                            id = IdGenerator.generate(),
                            mediaType = block["mime_type"]?.jsonPrimitive?.contentOrNull ?: "image/png",
                            base64 = try {
                                WireDecoder.requiredString(block, "data", "google", "interactions stream step", "$.content[$index]")
                            } catch (error: WireDecodeException) {
                                return listOf(StreamEvent.Error(error.message ?: "Google stream protocol error"))
                            },
                            providerMetadata = googleInteractionsMetadata(interactionId = interactionId),
                        )
                        null -> return listOf(StreamEvent.Error("Google stream protocol error: model_output content block missing type."))
                        else -> return listOf(StreamEvent.Error("Google stream protocol error: unsupported model_output content block type `$blockType`."))
                    }
                }
            }
            "thought" -> {
                val id = IdGenerator.generate()
                val metadata = googleInteractionsMetadata(
                    signature = step["signature"]?.jsonPrimitive?.contentOrNull,
                    interactionId = interactionId,
                )
                events += StreamEvent.ReasoningStart(id, metadata)
                events += StreamEvent.ReasoningDelta(
                    id,
                    step["summary"]?.jsonArray.orEmpty()
                        .mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
                        .joinToString("\n"),
                    metadata,
                )
                events += StreamEvent.ReasoningEnd(id, metadata)
            }
            "function_call" -> {
                hasFunctionCall = true
                val id = step["id"]?.jsonPrimitive?.contentOrNull ?: IdGenerator.generate()
                val name = try {
                    WireDecoder.requiredString(step, "name", "google", "interactions stream step")
                } catch (error: WireDecodeException) {
                    return listOf(StreamEvent.Error(error.message ?: "Google stream protocol error"))
                }
                val input = step["arguments"] ?: JsonObject(emptyMap())
                val metadata = googleInteractionsMetadata(
                    signature = step["signature"]?.jsonPrimitive?.contentOrNull,
                    interactionId = interactionId,
                )
                events += StreamEvent.ToolInputStart(id, name, metadata)
                events += StreamEvent.ToolInputDelta(id, input.toString(), metadata)
                events += StreamEvent.ToolInputEnd(id, metadata)
                events += StreamEvent.ToolCall(id, name, input, metadata)
            }
            else -> if (type != null && type.endsWith("_call")) {
                hasFunctionCall = true
                val id = step["id"]?.jsonPrimitive?.contentOrNull ?: IdGenerator.generate()
                val name = if (type == "mcp_server_tool_call") {
                    WireDecoder.optionalString(step, "name", "google", "interactions stream step") ?: "mcp_server_tool"
                } else {
                    type.removeSuffix("_call")
                }
                val input = step["arguments"] ?: JsonObject(emptyMap())
                val metadata = mapOf("google" to buildJsonObject { put("providerExecuted", JsonPrimitive(true)) })
                events += StreamEvent.ToolInputStart(id, name, metadata)
                events += StreamEvent.ToolInputDelta(id, input.toString(), metadata)
                events += StreamEvent.ToolInputEnd(id, metadata)
                events += StreamEvent.ToolCall(id, name, input, metadata)
            }
        }
        return events
    }

    fun finishIfNeeded(): List<StreamEvent> =
        if (finished) {
            emptyList()
        } else {
            closeText() + StreamEvent.Finish(1, finishReason, usage, rawFinishReason = rawFinishReason)
        }

    private fun closeText(): List<StreamEvent> =
        textId?.let {
            textId = null
            listOf(StreamEvent.TextEnd(it))
        }.orEmpty()
}

private fun googleInteractionsUsage(element: JsonElement?): Usage {
    val obj = element as? JsonObject ?: return Usage()
    val input = obj["total_input_tokens"]?.jsonPrimitive?.intOrNull ?: 0
    val output = obj["total_output_tokens"]?.jsonPrimitive?.intOrNull ?: 0
    val thought = obj["total_thought_tokens"]?.jsonPrimitive?.intOrNull ?: 0
    val cached = (obj["total_cached_tokens"]?.jsonPrimitive?.intOrNull ?: 0).coerceIn(0, input)
    return Usage(
        inputTokens = Usage.InputTokenBreakdown(
            total = input,
            noCache = (input - cached).coerceAtLeast(0),
            cacheRead = cached,
        ),
        outputTokens = Usage.OutputTokenBreakdown(
            total = output + thought,
            text = output,
            reasoning = thought,
        ),
        raw = element,
    )
}

private fun googleInteractionsFinishReason(status: String?, hasFunctionCall: Boolean): FinishReason = when (status) {
    "completed" -> if (hasFunctionCall) FinishReason.ToolCalls else FinishReason.Stop
    "requires_action" -> FinishReason.ToolCalls
    "failed" -> FinishReason.Error
    "incomplete" -> FinishReason.Length
    "cancelled" -> FinishReason.Other
    else -> FinishReason.Other
}

private fun googleInteractionsTerminal(status: String?): Boolean =
    status in setOf("completed", "failed", "cancelled", "incomplete")

private suspend fun googlePollInteraction(
    client: HttpClient,
    settings: GoogleGenerativeAIProviderSettings,
    interactionId: String,
    headers: Map<String, String>,
    abortSignal: AbortSignal,
    timeoutMillis: Long?,
): HttpJsonResponse {
    val maxAttempts = ((timeoutMillis ?: 30 * 60 * 1_000L) / settings.videoPollIntervalMillis.coerceAtLeast(1L)).coerceAtLeast(1L).toInt()
    var current = googleGetJson(client, "${settings.baseURL.trimEnd('/')}/interactions/$interactionId", headers, abortSignal)
    repeat(maxAttempts) {
        if (googleInteractionsTerminal(current.value.jsonObject["status"]?.jsonPrimitive?.contentOrNull)) return current
        if (settings.videoPollIntervalMillis > 0) delay(settings.videoPollIntervalMillis)
        current = googleGetJson(client, "${settings.baseURL.trimEnd('/')}/interactions/$interactionId", headers, abortSignal)
    }
    throw AiSdkRuntimeException("google.interactions: polling timed out for interaction $interactionId.")
}

private fun googleInteractionsMetadata(
    signature: String? = null,
    interactionId: String? = null,
    extra: Map<String, JsonElement> = emptyMap(),
): Map<String, JsonElement>? {
    val google = buildJsonObject {
        signature?.let { put("signature", JsonPrimitive(it)) }
        interactionId?.let { put("interactionId", JsonPrimitive(it)) }
        extra.forEach { (key, value) -> put(key, value) }
    }
    return if (google.isEmpty()) null else mapOf("google" to google)
}

private fun googleInteractionsSignature(metadata: Map<String, JsonElement>?): String? =
    (metadata?.get("google") as? JsonObject)?.get("signature")?.jsonPrimitive?.contentOrNull


private fun googleGenerateContentBody(
    modelId: String,
    settings: GoogleGenerativeAIProviderSettings,
    params: LanguageModelCallParams,
    stream: Boolean,
): GooglePreparedRequest {
    val warnings = mutableListOf<CallWarning>()
    val options = params.providerOptions["google"] as? JsonObject ?: JsonObject(emptyMap())
    val converted = googleMessages(params.messages, isGemini3 = modelId.contains("gemini-3"))
    warnings += converted.warnings
    val tools = googleToolsJson(params.tools, params.toolChoice, options)
    val generationConfig = buildJsonObject {
        params.maxOutputTokens?.let { put("maxOutputTokens", JsonPrimitive(it)) }
        params.temperature?.let { put("temperature", JsonPrimitive(it)) }
        params.topK?.let { put("topK", JsonPrimitive(it)) }
        params.topP?.let { put("topP", JsonPrimitive(it)) }
        params.frequencyPenalty?.let { put("frequencyPenalty", JsonPrimitive(it)) }
        params.presencePenalty?.let { put("presencePenalty", JsonPrimitive(it)) }
        if (params.stopSequences.isNotEmpty()) put("stopSequences", JsonArray(params.stopSequences.map(::JsonPrimitive)))
        params.seed?.let { put("seed", JsonPrimitive(it)) }
        if (params.responseFormat is ResponseFormat.Json) {
            put("responseMimeType", JsonPrimitive("application/json"))
            params.responseFormat.schemaJson?.let { put("responseSchema", googleSchema(it)) }
        }
        options["responseModalities"]?.let { put("responseModalities", it) }
        options["thinkingConfig"]?.let { put("thinkingConfig", it) }
        options["audioTimestamp"]?.let { put("audioTimestamp", it) }
        options["mediaResolution"]?.let { put("mediaResolution", it) }
        options["imageConfig"]?.let { put("imageConfig", it) }
    }
    return GooglePreparedRequest(
        body = buildJsonObject {
            put("generationConfig", generationConfig)
            put("contents", converted.contents)
            if (!modelId.startsWith("gemma-", ignoreCase = true)) converted.systemInstruction?.let { put("systemInstruction", it) }
            options["safetySettings"]?.let { put("safetySettings", it) }
            if (tools.isNotEmpty()) put("tools", tools)
            if (tools.isNotEmpty()) googleToolConfig(params.toolChoice, options)?.let { put("toolConfig", it) }
            options["cachedContent"]?.let { put("cachedContent", it) }
            options["labels"]?.let { put("labels", it) }
            options["serviceTier"]?.let { put("serviceTier", it) }
        },
        warnings = warnings + if (stream && options["streamFunctionCallArguments"] != null) {
            listOf(CallWarning("other", "streamFunctionCallArguments is only supported on Vertex AI and is ignored by the Gemini API facade."))
        } else {
            emptyList()
        },
    )
}

private fun googleMessages(messages: List<ModelMessage>, isGemini3: Boolean = false): GoogleConvertedMessages {
    val contents = mutableListOf<JsonElement>()
    val systemParts = mutableListOf<JsonElement>()
    val warnings = mutableListOf<CallWarning>()
    for (message in messages) {
        when (message.role) {
            MessageRole.System -> systemParts += message.content.mapNotNull(::googleContentPart)
            MessageRole.User -> contents += buildJsonObject {
                put("role", JsonPrimitive("user"))
                put("parts", JsonArray(message.content.mapNotNull(::googleContentPart)))
            }
            MessageRole.Assistant -> contents += buildJsonObject {
                put("role", JsonPrimitive("model"))
                put("parts", JsonArray(message.content.mapNotNull { googleAssistantPart(it, isGemini3) }))
            }
            MessageRole.Tool -> {
                // Approval bookkeeping (ToolApprovalResponse) is SDK-internal and never reaches the wire;
                // a Tool message that maps to no real parts produces NO content entry at all (an empty
                // `parts` array is itself invalid to Google).
                val parts = message.content.mapNotNull(::googleToolPart)
                if (parts.isNotEmpty()) {
                    contents += buildJsonObject {
                        put("role", JsonPrimitive("function"))
                        put("parts", JsonArray(parts))
                    }
                }
            }
        }
    }
    return GoogleConvertedMessages(
        contents = JsonArray(contents),
        systemInstruction = if (systemParts.isEmpty()) null else buildJsonObject { put("parts", JsonArray(systemParts)) },
        warnings = warnings,
    )
}

private fun googleContentPart(part: ContentPart): JsonElement? = when (part) {
    is ContentPart.Text -> buildJsonObject {
        put("text", JsonPrimitive(part.text))
        googleThoughtMetadata(part.providerMetadata)?.let { putJsonObjectFields(it) }
    }
    is ContentPart.File -> buildJsonObject {
        put("inlineData", buildJsonObject {
            put("mimeType", JsonPrimitive(part.mediaType))
            put("data", JsonPrimitive(part.base64))
        })
    }
    is ContentPart.Image -> buildJsonObject {
        put("inlineData", buildJsonObject {
            put("mimeType", JsonPrimitive(part.mediaType))
            put("data", JsonPrimitive(part.base64))
        })
    }
    else -> null
}

private const val GOOGLE_SKIP_THOUGHT_SIGNATURE = "skip_thought_signature_validator"

private fun googleAssistantPart(part: ContentPart, isGemini3: Boolean = false): JsonElement? = when (part) {
    is ContentPart.Text -> buildJsonObject {
        put("text", JsonPrimitive(part.text))
        googleThoughtMetadata(part.providerMetadata)?.let { putJsonObjectFields(it) }
    }
    is ContentPart.Reasoning -> buildJsonObject {
        put("text", JsonPrimitive(part.text))
        put("thought", JsonPrimitive(true))
        googleThoughtMetadata(part.providerMetadata)?.let { putJsonObjectFields(it) }
    }
    is ContentPart.File -> googleContentPart(part)
    is ContentPart.ToolCall -> buildJsonObject {
        put("functionCall", buildJsonObject {
            put("id", JsonPrimitive(part.toolCallId))
            put("name", JsonPrimitive(part.toolName))
            put("args", part.input)
        })
        // Gemini 3 rejects (HTTP 400) a replayed functionCall lacking a thoughtSignature.
        // Use the captured signature, else inject the documented sentinel for Gemini 3.
        val sig = (part.providerMetadata?.get("google") as? JsonObject)?.get("thoughtSignature")
        when {
            sig != null -> put("thoughtSignature", sig)
            isGemini3 -> put("thoughtSignature", JsonPrimitive(GOOGLE_SKIP_THOUGHT_SIGNATURE))
        }
    }
    else -> null
}

// ToolApprovalResponse is deliberately NOT serialized (falls to the null arm): tool approvals are an
// SDK-internal gate with no Google wire concept — serializing one produced a functionResponse whose `name`
// was the call id (not a declared function), which the API rejects. The wire sees only real results.
private fun googleToolPart(part: ContentPart): JsonElement? = when (part) {
    is ContentPart.ToolResult -> buildJsonObject {
        // Google requires response to be a Struct of {name, content} — emitting the
        // bare output (esp. a primitive) is rejected.
        val response = buildJsonObject {
            put("name", JsonPrimitive(part.toolName))
            put("content", part.modelVisible)
        }
        put("functionResponse", buildJsonObject {
            put("id", JsonPrimitive(part.toolCallId))
            put("name", JsonPrimitive(part.toolName))
            put("response", response)
        })
    }
    else -> null
}

private fun googleToolsJson(
    tools: List<LanguageModelTool>,
    choice: ToolChoice,
    options: JsonObject,
): JsonArray {
    val declarations = if (choice is ToolChoice.Specific) tools.filter { it.name == choice.toolName } else tools
    val result = mutableListOf<JsonElement>()
    val functionDeclarations = declarations.filterNot { it.providerExecuted }.map { tool ->
        buildJsonObject {
            put("name", JsonPrimitive(tool.name))
            if (tool.description.isNotBlank()) put("description", JsonPrimitive(tool.description))
            put("parameters", googleSchema(aiSdkJson.parseToJsonElement(tool.parametersSchemaJson)))
        }
    }
    if (functionDeclarations.isNotEmpty()) {
        result += buildJsonObject { put("functionDeclarations", JsonArray(functionDeclarations)) }
    }
    declarations.filter { it.providerExecuted }.forEach { tool ->
        when (tool.name) {
            "google_search" -> result += buildJsonObject { put("googleSearch", buildJsonObject { }) }
            "enterprise_web_search" -> result += buildJsonObject { put("enterpriseWebSearch", buildJsonObject { }) }
            "google_maps" -> result += buildJsonObject { put("googleMaps", buildJsonObject { }) }
            "url_context" -> result += buildJsonObject { put("urlContext", buildJsonObject { }) }
            "file_search" -> result += buildJsonObject { put("fileSearch", buildJsonObject { }) }
            "code_execution" -> result += buildJsonObject { put("codeExecution", buildJsonObject { }) }
            "vertex_rag_store" -> result += buildJsonObject { put("retrieval", buildJsonObject { put("vertexRagStore", buildJsonObject { }) }) }
        }
    }
    options["googleSearch"]?.let { result += buildJsonObject { put("googleSearch", it) } }
    return JsonArray(result)
}

private fun googleToolConfig(choice: ToolChoice, options: JsonObject): JsonObject? {
    options["retrievalConfig"]?.let { retrieval ->
        return buildJsonObject { put("retrievalConfig", retrieval) }
    }
    return when (choice) {
        ToolChoice.Auto -> null
        ToolChoice.None -> buildJsonObject {
            put("functionCallingConfig", buildJsonObject { put("mode", JsonPrimitive("NONE")) })
        }
        ToolChoice.Required -> buildJsonObject {
            put("functionCallingConfig", buildJsonObject { put("mode", JsonPrimitive("ANY")) })
        }
        is ToolChoice.Specific -> buildJsonObject {
            put(
                "functionCallingConfig",
                buildJsonObject {
                    put("mode", JsonPrimitive("ANY"))
                    put("allowedFunctionNames", JsonArray(listOf(JsonPrimitive(choice.toolName))))
                },
            )
        }
    }
}

private fun googleLanguageResult(
    response: JsonObject,
    requestBody: JsonObject,
    headers: Map<String, String>,
    rawBody: JsonElement,
    warnings: List<CallWarning>,
    settings: GoogleGenerativeAIProviderSettings,
): LanguageModelResult {
    val candidate = response["candidates"]?.jsonArray?.firstOrNull()?.jsonObject ?: JsonObject(emptyMap())
    val contentParts = candidate["content"]?.jsonObject?.get("parts")?.jsonArray.orEmpty()
    val content = mutableListOf<ContentPart>()
    val toolCalls = mutableListOf<ContentPart.ToolCall>()
    var lastCodeExecutionId: String? = null
    for (part in contentParts) {
        val obj = part.jsonObject
        obj["executableCode"]?.jsonObject?.let { code ->
            val id = settings.generateId()
            lastCodeExecutionId = id
            val call = ContentPart.ToolCall(id, "code_execution", code, providerMetadata = mapOf("google" to buildJsonObject { put("providerExecuted", JsonPrimitive(true)) }))
            content += call
            toolCalls += call
        }
        obj["codeExecutionResult"]?.jsonObject?.let { result ->
            content += ContentPart.ToolResult(lastCodeExecutionId ?: settings.generateId(), "code_execution", result, providerMetadata = mapOf("google" to buildJsonObject { put("providerExecuted", JsonPrimitive(true)) }))
            lastCodeExecutionId = null
        }
        obj["text"]?.jsonPrimitive?.contentOrNull?.let { text ->
            val metadata = googlePartMetadata(obj)
            content += if (obj["thought"]?.jsonPrimitive?.booleanOrNull == true) {
                ContentPart.Reasoning(text, metadata)
            } else {
                ContentPart.Text(text, metadata)
            }
        }
        obj["functionCall"]?.jsonObject?.let { callObj ->
            val call = ContentPart.ToolCall(
                toolCallId = callObj["id"]?.jsonPrimitive?.contentOrNull ?: settings.generateId(),
                toolName = callObj["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                input = callObj["args"] ?: JsonObject(emptyMap()),
                providerMetadata = googlePartMetadata(obj),
            )
            content += call
            toolCalls += call
        }
        obj["inlineData"]?.jsonObject?.let { data ->
            content += ContentPart.File(
                mediaType = data["mimeType"]?.jsonPrimitive?.contentOrNull ?: "application/octet-stream",
                base64 = data["data"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                providerMetadata = googlePartMetadata(obj),
            )
        }
    }
    googleSources(candidate, settings.generateId).forEach { content += it }
    val text = content.filterIsInstance<ContentPart.Text>().joinToString("") { it.text }
    val finish = candidate["finishReason"]?.jsonPrimitive?.contentOrNull
    val metadata = buildJsonObject {
        response["promptFeedback"]?.let { put("promptFeedback", it) }
        candidate["groundingMetadata"]?.let { put("groundingMetadata", it) }
        candidate["urlContextMetadata"]?.let { put("urlContextMetadata", it) }
        candidate["safetyRatings"]?.let { put("safetyRatings", it) }
        response["usageMetadata"]?.let { put("usageMetadata", it) }
        candidate["finishMessage"]?.let { put("finishMessage", it) }
    }
    return LanguageModelResult(
        text = text,
        toolCalls = toolCalls,
        finishReason = mapGoogleFinishReason(finish, toolCalls.isNotEmpty()),
        usage = googleUsage(response["usageMetadata"]),
        providerMetadata = mapOf("google" to metadata),
        content = content,
        rawFinishReason = finish,
        warnings = warnings,
        request = LanguageModelRequestMetadata(requestBody),
        response = LanguageModelResponseMetadata(headers = headers, body = rawBody),
    )
}

private class GoogleStreamState(
    private val generateId: () -> String,
) {
    private var finishReason = FinishReason.Other
    private var rawFinishReason: String? = null
    private var usage = Usage()
    private var textId: String? = null
    private var reasoningId: String? = null
    private var blockCounter = 0
    private var hasToolCalls = false

    fun accept(value: JsonObject): List<StreamEvent> {
        val events = mutableListOf<StreamEvent>()
        value["usageMetadata"]?.let { usage = googleUsage(it) }
        val candidate = value["candidates"]?.jsonArray?.firstOrNull()?.jsonObject ?: return events
        val parts = candidate["content"]?.jsonObject?.get("parts")?.jsonArray.orEmpty()
        for ((index, part) in parts.withIndex()) {
            val obj = try {
                WireDecoder.objectValue(part, "google", "generateContent stream part", "$.candidates[0].content.parts[$index]")
            } catch (error: WireDecodeException) {
                return listOf(StreamEvent.Error(error.message ?: "Google stream protocol error"))
            }
            val text = try {
                WireDecoder.optionalString(obj, "text", "google", "generateContent stream part", "$.candidates[0].content.parts[$index]")
            } catch (error: WireDecodeException) {
                return listOf(StreamEvent.Error(error.message ?: "Google stream protocol error"))
            }
            text?.let {
                if (obj["thought"]?.jsonPrimitive?.booleanOrNull == true) {
                    if (textId != null) {
                        events += StreamEvent.TextEnd(textId.orEmpty())
                        textId = null
                    }
                    if (reasoningId == null) {
                        reasoningId = (blockCounter++).toString()
                        events += StreamEvent.ReasoningStart(reasoningId.orEmpty(), googlePartMetadata(obj))
                    }
                    events += StreamEvent.ReasoningDelta(reasoningId.orEmpty(), it, googlePartMetadata(obj))
                } else {
                    if (reasoningId != null) {
                        events += StreamEvent.ReasoningEnd(reasoningId.orEmpty())
                        reasoningId = null
                    }
                    if (textId == null) {
                        textId = (blockCounter++).toString()
                        events += StreamEvent.TextStart(textId.orEmpty(), googlePartMetadata(obj))
                    }
                    events += StreamEvent.TextDelta(textId.orEmpty(), it, googlePartMetadata(obj))
                }
            }
            obj["functionCall"]?.let { callElement ->
                val call = try {
                    WireDecoder.objectValue(callElement, "google", "generateContent stream part", "$.candidates[0].content.parts[$index].functionCall")
                } catch (error: WireDecodeException) {
                    return listOf(StreamEvent.Error(error.message ?: "Google stream protocol error"))
                }
                val id = call["id"]?.jsonPrimitive?.contentOrNull ?: IdGenerator.generate()
                val name = try {
                    WireDecoder.requiredString(call, "name", "google", "generateContent stream part", "$.candidates[0].content.parts[$index].functionCall")
                } catch (error: WireDecodeException) {
                    return listOf(StreamEvent.Error(error.message ?: "Google stream protocol error"))
                }
                val input = call["args"] ?: JsonObject(emptyMap())
                hasToolCalls = true
                events += StreamEvent.ToolInputStart(id, name, googlePartMetadata(obj))
                events += StreamEvent.ToolInputDelta(id, input.toString(), googlePartMetadata(obj))
                events += StreamEvent.ToolInputEnd(id, googlePartMetadata(obj))
                events += StreamEvent.ToolCall(id, name, input, googlePartMetadata(obj))
            }
            obj["inlineData"]?.let { dataElement ->
                val data = try {
                    WireDecoder.objectValue(dataElement, "google", "generateContent stream part", "$.candidates[0].content.parts[$index].inlineData")
                } catch (error: WireDecodeException) {
                    return listOf(StreamEvent.Error(error.message ?: "Google stream protocol error"))
                }
                events += StreamEvent.FilePart(
                    id = IdGenerator.generate(),
                    mediaType = data["mimeType"]?.jsonPrimitive?.contentOrNull ?: "application/octet-stream",
                    base64 = try {
                        WireDecoder.requiredString(data, "data", "google", "generateContent stream part", "$.candidates[0].content.parts[$index].inlineData")
                    } catch (error: WireDecodeException) {
                        return listOf(StreamEvent.Error(error.message ?: "Google stream protocol error"))
                    },
                    providerMetadata = googlePartMetadata(obj),
                )
            }
        }
        googleSources(candidate, generateId).forEach { source ->
            events += StreamEvent.SourcePart(
                id = source.providerMetadata?.get("google")?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull ?: IdGenerator.generate(),
                sourceType = source.sourceType,
                url = source.url,
                title = source.title,
                providerMetadata = source.providerMetadata,
            )
        }
        candidate["finishReason"]?.jsonPrimitive?.contentOrNull?.let {
            rawFinishReason = it
            finishReason = mapGoogleFinishReason(it, hasToolCalls)
        }
        return events
    }

    fun finish(): List<StreamEvent> {
        val events = mutableListOf<StreamEvent>()
        textId?.let { events += StreamEvent.TextEnd(it) }
        reasoningId?.let { events += StreamEvent.ReasoningEnd(it) }
        events += StreamEvent.Finish(1, finishReason, usage, rawFinishReason = rawFinishReason)
        return events
    }
}

private fun googleSingleEmbeddingBody(modelId: String, value: String, options: JsonObject): JsonObject = buildJsonObject {
    put("model", JsonPrimitive("models/$modelId"))
    put("content", buildJsonObject { put("parts", JsonArray(listOf(buildJsonObject { put("text", JsonPrimitive(value)) }))) })
    options["outputDimensionality"]?.let { put("outputDimensionality", it) }
    options["taskType"]?.let { put("taskType", it) }
}

private fun googleBatchEmbeddingBody(modelId: String, values: List<String>, options: JsonObject): JsonObject = buildJsonObject {
    put(
        "requests",
        JsonArray(
            values.map { value ->
                buildJsonObject {
                    put("model", JsonPrimitive("models/$modelId"))
                    put("content", buildJsonObject {
                        put("role", JsonPrimitive("user"))
                        put("parts", JsonArray(listOf(buildJsonObject { put("text", JsonPrimitive(value)) })))
                    })
                    options["outputDimensionality"]?.let { put("outputDimensionality", it) }
                    options["taskType"]?.let { put("taskType", it) }
                }
            },
        ),
    )
}

private fun googleVideoRequestBody(params: VideoGenerationParams, options: JsonObject): JsonObject = buildJsonObject {
    put(
        "instances",
        JsonArray(
            listOf(
                buildJsonObject {
                    put("prompt", JsonPrimitive(params.prompt))
                    params.image?.let { image ->
                        put(
                            "image",
                            buildJsonObject {
                                put("inlineData", buildJsonObject {
                                    put("mimeType", JsonPrimitive(image.mediaType))
                                    put("data", JsonPrimitive(image.base64))
                                })
                            },
                        )
                    }
                    options["referenceImages"]?.let { put("referenceImages", it) }
                },
            ),
        ),
    )
    put(
        "parameters",
        buildJsonObject {
            put("sampleCount", JsonPrimitive(params.n))
            params.aspectRatio?.let { put("aspectRatio", JsonPrimitive(it)) }
            params.durationSeconds?.let { put("durationSeconds", JsonPrimitive(it)) }
            params.seed?.let { put("seed", JsonPrimitive(it)) }
            params.resolution?.let { put("resolution", JsonPrimitive(googleVideoResolution(it))) }
            options["personGeneration"]?.let { put("personGeneration", it) }
            options["negativePrompt"]?.let { put("negativePrompt", it) }
        },
    )
}

private val googleErrorExtractor: ErrorMessageExtractor = { _, parsed, raw -> googleErrorMessage(parsed, raw) }

private suspend fun HttpResponse.parseGoogleResponse(
    url: String,
    parseJson: Boolean,
    requestBodyValues: Any? = null,
): HttpJsonResponse =
    toJsonResponse(
        url = url,
        parseJson = parseJson,
        requestBodyValues = requestBodyValues,
        errorMessage = googleErrorExtractor,
    )

private suspend fun googlePostJson(
    client: HttpClient,
    url: String,
    body: JsonElement,
    headers: Map<String, String>,
    abortSignal: AbortSignal,
    parseJson: Boolean,
): HttpJsonResponse {
    abortSignal.throwIfAborted()
    val response = client.request(url) {
        method = HttpMethod.Post
        contentType(ContentType.Application.Json)
        headers.forEach { (name, value) -> header(name, value) }
        setBody(aiSdkJson.encodeToString(JsonElement.serializer(), body))
    }
    return response.parseGoogleResponse(url, parseJson, requestBodyValues = body)
}

/** Streaming counterpart of [googlePostJson]: reads the SSE body incrementally. */
private fun googleStreamSse(
    client: HttpClient,
    url: String,
    body: JsonElement,
    headers: Map<String, String>,
    abortSignal: AbortSignal,
): Flow<String> = flow {
    abortSignal.throwIfAborted()
    emitAll(
        streamSse(
            client = client,
            url = url,
            method = HttpMethod.Post,
            headers = headers,
            body = body,
            json = aiSdkJson,
            requestBodyValues = body,
            errorMessage = googleErrorExtractor,
        ),
    )
}

/** Streaming counterpart of [googleGetJson] (background-interaction polling). */
private fun googleStreamSseGet(
    client: HttpClient,
    url: String,
    headers: Map<String, String>,
    abortSignal: AbortSignal,
): Flow<String> = flow {
    abortSignal.throwIfAborted()
    emitAll(
        streamSse(
            client = client,
            url = url,
            method = HttpMethod.Get,
            headers = headers,
            body = null,
            json = aiSdkJson,
            errorMessage = googleErrorExtractor,
        ),
    )
}

/** Parse the interactions SSE [rawLines] and feed each event to [state],
 *  emitting its produced [StreamEvent]s (shared by both stream branches). */
private suspend fun FlowCollector<StreamEvent>.collectGoogleInteractions(
    rawLines: Flow<String>,
    state: GoogleInteractionsStreamState,
) {
    EventStreamParser.parse(rawLines, jsonSchema<JsonElement>(JsonObject(emptyMap())), aiSdkJson).collect { event ->
        when (event) {
            is ParseResult.Success -> state.accept(event.value.jsonObject).forEach { emit(it) }
            is ParseResult.Failure -> emit(
                StreamEvent.Error("Failed to parse Google Interactions stream event: ${event.error.message}"),
            )
        }
    }
}

private suspend fun googleGetJson(
    client: HttpClient,
    url: String,
    headers: Map<String, String>,
    abortSignal: AbortSignal,
    parseJson: Boolean = true,
): HttpJsonResponse {
    abortSignal.throwIfAborted()
    val response = client.request(url) {
        method = HttpMethod.Get
        headers.forEach { (name, value) -> header(name, value) }
    }
    return response.parseGoogleResponse(url, parseJson = parseJson)
}

private suspend fun googleGetJsonWithRetry(
    client: HttpClient,
    url: String,
    headers: Map<String, String>,
    abortSignal: AbortSignal,
    parseJson: Boolean = true,
    maxRetries: Int = 2,
    retryDelayMillis: Long = 0,
): HttpJsonResponse {
    var attempt = 0
    while (true) {
        abortSignal.throwIfAborted()
        val response = client.request(url) {
            method = HttpMethod.Get
            headers.forEach { (name, value) -> header(name, value) }
        }
        if (response.status.value !in 500..599 || attempt >= maxRetries) {
            return response.parseGoogleResponse(url, parseJson = parseJson)
        }
        response.bodyAsText()
        attempt += 1
        if (retryDelayMillis > 0) delay(retryDelayMillis)
    }
}

private fun googleHeaders(settings: GoogleGenerativeAIProviderSettings, extra: Map<String, String>): Map<String, String> {
    val headers = linkedMapOf<String, String>()
    settings.apiKey?.let { headers["x-goog-api-key"] = it }
    headers.putAll(settings.headers)
    headers.putAll(extra)
    headers[HttpHeaders.UserAgent] = appendGoogleUserAgent(headers[HttpHeaders.UserAgent], "ai-sdk/google/$GOOGLE_VERSION")
    return headers
}

private fun googleInteractionsHeaders(settings: GoogleGenerativeAIProviderSettings, extra: Map<String, String>): Map<String, String> =
    googleHeaders(settings, extra) + ("Api-Revision" to "2026-05-20")

private fun googleUsage(element: JsonElement?): Usage {
    val obj = element as? JsonObject ?: return Usage()
    val prompt = obj["promptTokenCount"]?.jsonPrimitive?.intOrNull ?: 0
    val candidates = obj["candidatesTokenCount"]?.jsonPrimitive?.intOrNull ?: 0
    val thoughts = obj["thoughtsTokenCount"]?.jsonPrimitive?.intOrNull ?: 0
    val cached = obj["cachedContentTokenCount"]?.jsonPrimitive?.intOrNull ?: 0
    // Match upstream: output total = candidates + thoughts (not clamped); cached input
    // tokens map to cacheRead with noCache = prompt - cached.
    return Usage(
        inputTokens = Usage.InputTokenBreakdown(
            total = prompt,
            noCache = prompt - cached,
            cacheRead = cached,
        ),
        outputTokens = Usage.OutputTokenBreakdown(
            total = candidates + thoughts,
            reasoning = thoughts,
            text = candidates,
        ),
        raw = element,
    )
}

/**
 * Convert a JSON Schema (draft-07) to the OpenAPI 3.0 subset Google's GenAI API
 * accepts. Ports upstream's convertJSONSchemaToOpenAPISchema: rebuilds keeping only
 * OpenAPI-compatible keys (dropping `$schema`, `additionalProperties`, `title`),
 * recurses into properties/items/allOf/anyOf/oneOf, and maps a nullable type array
 * (`["string","null"]`) to `nullable: true`. Without this, our schemas (which carry
 * `$schema`/`additionalProperties`/`title`) are rejected, breaking structured output
 * and tool calling.
 */
private fun googleSchema(element: JsonElement): JsonElement {
    val obj = element as? JsonObject ?: return element
    return buildJsonObject {
        for (key in GOOGLE_SCHEMA_PASSTHROUGH) {
            obj[key]?.let { put(key, it) }
        }
        googleSchemaType(obj)?.let { (typeEl, nullable) ->
            typeEl?.let { put("type", it) }
            if (nullable) put("nullable", JsonPrimitive(true))
        }
        (obj["properties"] as? JsonObject)?.let { props ->
            put("properties", buildJsonObject { props.forEach { (k, v) -> put(k, googleSchema(v)) } })
        }
        obj["items"]?.let { put("items", googleSchema(it)) }
        for (combiner in listOf("allOf", "anyOf", "oneOf")) {
            (obj[combiner] as? JsonArray)?.let { arr ->
                put(combiner, JsonArray(arr.map { googleSchema(it) }))
            }
        }
    }
}

/** Resolves the OpenAPI `type` + nullable flag from a JSON Schema `type` (which may be an array incl. "null"). */
private fun googleSchemaType(obj: JsonObject): Pair<JsonElement?, Boolean>? {
    val type = obj["type"] ?: return null
    return if (type is JsonArray) {
        val nonNull = type.filter { (it as? JsonPrimitive)?.content != "null" }
        val hasNull = type.size != nonNull.size
        (if (nonNull.size == 1) nonNull.single() else JsonArray(nonNull)) to hasNull
    } else {
        type to false
    }
}

private val GOOGLE_SCHEMA_PASSTHROUGH = listOf(
    "description", "required", "format", "enum", "const",
    "minLength", "maxLength", "minItems", "maxItems", "minimum", "maximum",
)

private fun mapGoogleFinishReason(reason: String?, hasToolCalls: Boolean): FinishReason =
    if (reason == "STOP" && hasToolCalls) {
        FinishReason.ToolCalls
    } else {
        when (reason) {
            "STOP" -> FinishReason.Stop
            "MAX_TOKENS" -> FinishReason.Length
            "SAFETY", "IMAGE_SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII",
            -> FinishReason.ContentFilter
            // Upstream maps MALFORMED_FUNCTION_CALL to 'error', not content-filter.
            "MALFORMED_FUNCTION_CALL" -> FinishReason.Error
            else -> FinishReason.Other
        }
    }

private fun googlePartMetadata(part: JsonObject): Map<String, JsonElement>? {
    val metadata = buildJsonObject {
        part["thought"]?.let { put("thought", it) }
        part["thoughtSignature"]?.let { put("thoughtSignature", it) }
    }
    return if (metadata.isEmpty()) null else mapOf("google" to metadata)
}

private fun googleThoughtMetadata(metadata: Map<String, JsonElement>?): JsonObject? {
    val google = metadata?.get("google") as? JsonObject ?: return null
    return buildJsonObject {
        google["thought"]?.let { put("thought", it) }
        google["thoughtSignature"]?.let { put("thoughtSignature", it) }
    }.takeIf { it.isNotEmpty() }
}

private fun googleSources(candidate: JsonObject, generateId: () -> String): List<ContentPart.Source> {
    val chunks = candidate["groundingMetadata"]?.jsonObject?.get("groundingChunks")?.jsonArray.orEmpty()
    return chunks.mapNotNull { chunk ->
        val web = chunk.jsonObject["web"]?.jsonObject ?: return@mapNotNull null
        ContentPart.Source(
            sourceType = StreamEvent.SourcePart.SourceType.Url,
            url = web["uri"]?.jsonPrimitive?.contentOrNull,
            title = web["title"]?.jsonPrimitive?.contentOrNull,
            providerMetadata = mapOf("google" to buildJsonObject {
                put("id", JsonPrimitive(IdGenerator.generate()))
                put("groundingChunk", chunk)
            }),
        )
    }
}

private fun googleVideoResolution(resolution: String): String = when (resolution) {
    "1280x720" -> "720p"
    "1920x1080" -> "1080p"
    "3840x2160" -> "4k"
    else -> resolution
}

private fun googleErrorMessage(parsed: JsonElement?, raw: String): String {
    val obj = parsed as? JsonObject ?: return raw
    val error = obj["error"]
    return (error as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull
        ?: (error as? JsonPrimitive)?.contentOrNull
        ?: obj["message"]?.jsonPrimitive?.contentOrNull
        ?: raw
}

private fun googleProviderTool(
    name: String,
    id: String,
    description: String,
): Tool<JsonElement, JsonElement, Any?> =
    ProviderExecutedTool(
        name = name,
        description = description,
        inputSerializer = JsonElement.serializer(),
        outputSerializer = JsonElement.serializer(),
        metadata = mapOf("providerToolId" to JsonPrimitive(id)),
    )

private fun JsonObjectBuilder.putJsonObjectFields(fields: JsonObject, excluded: Set<String> = emptySet()) {
    fields.forEach { (key, value) -> if (value !is JsonNull && key !in excluded) put(key, value) }
}

private fun appendGoogleUserAgent(existing: String?, suffix: String): String =
    existing?.takeIf { it.isNotBlank() }?.let { "$it $suffix" } ?: suffix
