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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
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

const val GOOGLE_VERSION: String = "3.0.80"

typealias GoogleGenerativeAIModelId = String
typealias GoogleGenerativeAIEmbeddingModelId = String
typealias GoogleGenerativeAIImageModelId = String
typealias GoogleGenerativeAIVideoModelId = String
typealias GoogleInteractionsModelId = String
typealias GoogleInteractionsAgentName = String
typealias GoogleLanguageModelOptions = JsonObject
typealias GoogleGenerativeAIProviderOptions = JsonObject
typealias GoogleEmbeddingModelOptions = JsonObject
typealias GoogleGenerativeAIEmbeddingProviderOptions = JsonObject
typealias GoogleImageModelOptions = JsonObject
typealias GoogleGenerativeAIImageProviderOptions = JsonObject
typealias GoogleVideoModelOptions = JsonObject
typealias GoogleGenerativeAIVideoProviderOptions = JsonObject
typealias GoogleGenerativeAIProviderMetadata = JsonObject
typealias GoogleInteractionsProviderMetadata = JsonObject
typealias GoogleErrorData = JsonObject
typealias GroundingMetadataSchema = JsonObject
typealias UrlContextMetadataSchema = JsonObject
typealias UsageMetadataSchema = JsonObject
typealias SafetyRatingSchema = JsonObject
typealias PromptFeedbackSchema = JsonObject

@Serializable
data class GoogleGenerativeAIProviderSettings(
    val baseURL: String = "https://generativelanguage.googleapis.com/v1beta",
    val apiKey: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val generateId: () -> String = { ai.torad.aisdk.generateId() },
    val name: String = "google.generative-ai",
    val videoPollIntervalMillis: Long = 1_000L,
    val videoMaxPollAttempts: Int = 120,
)

interface GoogleGenerativeAIProvider : Provider {
    val settings: GoogleGenerativeAIProviderSettings
    val tools: GoogleTools

    operator fun invoke(modelId: GoogleGenerativeAIModelId): LanguageModel = languageModel(modelId)
    fun chat(modelId: GoogleGenerativeAIModelId): LanguageModel = languageModel(modelId)
    fun generativeAI(modelId: GoogleGenerativeAIModelId): LanguageModel = languageModel(modelId)
    fun embedding(modelId: GoogleGenerativeAIEmbeddingModelId): EmbeddingModel
    fun textEmbedding(modelId: GoogleGenerativeAIEmbeddingModelId): EmbeddingModel = embedding(modelId)
    fun textEmbeddingModel(modelId: GoogleGenerativeAIEmbeddingModelId): EmbeddingModel = embedding(modelId)
    fun image(modelId: GoogleGenerativeAIImageModelId): ImageModel
    fun video(modelId: GoogleGenerativeAIVideoModelId): VideoModel
    fun interactions(modelIdOrAgent: GoogleInteractionsModelId): LanguageModel

    override fun embeddingModel(modelId: String): EmbeddingModel = embedding(modelId)
    override fun imageModel(modelId: String): ImageModel = image(modelId)
    override fun videoModel(modelId: String): VideoModel = video(modelId)
}

fun createGoogleGenerativeAI(
    client: HttpClient,
    settings: GoogleGenerativeAIProviderSettings = GoogleGenerativeAIProviderSettings(),
): GoogleGenerativeAIProvider = DefaultGoogleGenerativeAIProvider(client, settings)

val google: GoogleGenerativeAIProvider = object : GoogleGenerativeAIProvider {
    override val providerId: String = "google"
    override val settings: GoogleGenerativeAIProviderSettings = GoogleGenerativeAIProviderSettings()
    override val tools: GoogleTools = GoogleTools()
    override fun languageModel(modelId: String): LanguageModel =
        throw AiSdkException("Google Generative AI provider is not configured. Use createGoogleGenerativeAI(client, settings).")
    override fun embedding(modelId: String): EmbeddingModel =
        throw AiSdkException("Google Generative AI provider is not configured. Use createGoogleGenerativeAI(client, settings).")
    override fun image(modelId: String): ImageModel =
        throw AiSdkException("Google Generative AI provider is not configured. Use createGoogleGenerativeAI(client, settings).")
    override fun video(modelId: String): VideoModel =
        throw AiSdkException("Google Generative AI provider is not configured. Use createGoogleGenerativeAI(client, settings).")
    override fun interactions(modelIdOrAgent: String): LanguageModel =
        throw AiSdkException("Google Generative AI provider is not configured. Use createGoogleGenerativeAI(client, settings).")
}

private class DefaultGoogleGenerativeAIProvider(
    private val client: HttpClient,
    override val settings: GoogleGenerativeAIProviderSettings,
) : GoogleGenerativeAIProvider {
    override val providerId: String = "google"
    override val tools: GoogleTools = googleTools

    override fun languageModel(modelId: String): LanguageModel =
        GoogleGenerativeAILanguageModel(client, settings, modelId)

    override fun embedding(modelId: String): EmbeddingModel =
        GoogleGenerativeAIEmbeddingModel(client, settings, modelId)

    override fun image(modelId: String): ImageModel =
        GoogleGenerativeAIImageModel(client, settings, modelId)

    override fun video(modelId: String): VideoModel =
        GoogleGenerativeAIVideoModel(client, settings, modelId)

    override fun interactions(modelIdOrAgent: String): LanguageModel =
        GoogleInteractionsPlaceholderLanguageModel(modelIdOrAgent, settings.name)
}

data class GoogleTools(
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

val googleTools: GoogleTools = GoogleTools()

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
        val response = googlePostJson(
            client = client,
            url = "${settings.baseURL.trimEnd('/')}/models/$modelId:streamGenerateContent?alt=sse",
            body = prepared.body,
            headers = googleHeaders(settings, params.headers) + (HttpHeaders.Accept to "text/event-stream"),
            abortSignal = params.abortSignal,
            parseJson = false,
        )
        emit(StreamEvent.StreamStart(prepared.warnings))
        val state = GoogleStreamState(settings.generateId)
        for (event in parseJsonEventStream(response.rawText, jsonSchema<JsonElement>(JsonObject(emptyMap())), googleJson)) {
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

    override suspend fun embed(params: EmbeddingModelCallParams): EmbeddingModelResult {
        params.abortSignal.throwIfAborted()
        if (params.values.size > 2048) throw AiSdkException("Google embedding models support at most 2048 values per call.")
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
            listOf(response.value.jsonObject["embedding"]?.jsonObject?.get("values")?.jsonArray.orEmpty().map { it.jsonPrimitive.floatOrNull ?: 0f })
        } else {
            response.value.jsonObject["embeddings"]?.jsonArray.orEmpty().map { item ->
                item.jsonObject["values"]?.jsonArray.orEmpty().map { it.jsonPrimitive.floatOrNull ?: 0f }
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
        if (params.files.isNotEmpty()) throw AiSdkException("Google Generative AI Imagen models do not support image editing. Use Google Vertex AI for image editing.")
        if (params.mask != null) throw AiSdkException("Google Generative AI Imagen models do not support masks. Use Google Vertex AI for image editing.")
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
        val images = response.value.jsonObject["predictions"]?.jsonArray.orEmpty().mapNotNull {
            it.jsonObject["bytesBase64Encoded"]?.jsonPrimitive?.contentOrNull
        }.map { GeneratedFile(mediaType = "image/png", base64 = it) }
        if (images.isEmpty()) throw NoImageGeneratedError("Google image response contained no predictions.")
        return ImageModelResult(images, warnings, LanguageModelResponseMetadata(modelId = modelId, headers = response.headers, body = response.value), mapOf("google" to response.value))
    }

    private suspend fun generateGeminiImage(params: ImageGenerationParams): ImageModelResult {
        if (params.n > 1) throw AiSdkException("Gemini image models do not support n > 1.")
        if (params.mask != null) throw AiSdkException("Gemini image models do not support mask-based image editing.")
        val message = ModelMessage(
            MessageRole.User,
            buildList {
                add(ContentPart.Text(params.prompt))
                params.files.forEach { file ->
                    add(ContentPart.File(file.mediaType ?: "image/png", file.base64 ?: throw AiSdkException("Gemini image input URLs are not supported in this facade."), file.filename))
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
        ).value.jsonObject
        val operationName = operation["name"]?.jsonPrimitive?.contentOrNull ?: throw AiSdkException("Google video response is missing operation name.")
        var current = operation
        val pollInterval = options["pollIntervalMs"]?.jsonPrimitive?.intOrNull?.toLong() ?: settings.videoPollIntervalMillis
        val maxAttempts = (options["maxPollAttempts"]?.jsonPrimitive?.intOrNull ?: settings.videoMaxPollAttempts).coerceAtLeast(1)
        var headers = emptyMap<String, String>()
        repeat(maxAttempts) {
            if (current["done"]?.jsonPrimitive?.booleanOrNull == true) return@repeat
            if (pollInterval > 0) delay(pollInterval)
            val poll = googleGetJson(
                client = client,
                url = "${settings.baseURL.trimEnd('/')}/$operationName",
                headers = googleHeaders(settings, params.headers),
                abortSignal = params.abortSignal,
            )
            current = poll.value.jsonObject
            headers = poll.headers
        }
        if (current["done"]?.jsonPrimitive?.booleanOrNull != true) {
            throw AiSdkException("Google video generation timed out after $maxAttempts poll attempts.")
        }
        current["error"]?.jsonObject?.let { throw AiSdkException("Google video generation failed: ${it["message"]?.jsonPrimitive?.contentOrNull ?: it}") }
        val samples = current["response"]?.jsonObject
            ?.get("generateVideoResponse")?.jsonObject
            ?.get("generatedSamples")?.jsonArray.orEmpty()
        val videos = samples.mapNotNull { sample ->
            sample.jsonObject["video"]?.jsonObject?.get("uri")?.jsonPrimitive?.contentOrNull
        }.map { uri ->
            val url = settings.apiKey?.let { key -> "$uri${if (uri.contains("?")) "&" else "?"}key=$key" } ?: uri
            GeneratedFile(mediaType = "video/mp4", base64 = "", url = url, providerMetadata = mapOf("google" to buildJsonObject { put("uri", JsonPrimitive(uri)) }))
        }
        if (videos.isEmpty()) throw NoVideoGeneratedError("Google video response contained no videos.")
        return VideoModelResult(videos = videos, response = LanguageModelResponseMetadata(modelId = modelId, headers = headers, body = current), providerMetadata = mapOf("google" to current))
    }
}

private class GoogleInteractionsPlaceholderLanguageModel(
    override val modelId: String,
    private val providerName: String,
) : LanguageModel {
    override val provider: String = "$providerName.interactions"
    override suspend fun generate(params: LanguageModelCallParams): LanguageModelResult =
        throw AiSdkException("Google Interactions API is not implemented in this facade yet.")
    override fun stream(params: LanguageModelCallParams): Flow<StreamEvent> = flow {
        emit(StreamEvent.Error("Google Interactions API is not implemented in this facade yet."))
    }
}

private data class GooglePreparedRequest(
    val body: JsonObject,
    val warnings: List<CallWarning>,
)

private data class GoogleConvertedMessages(
    val contents: JsonArray,
    val systemInstruction: JsonObject?,
    val warnings: List<CallWarning>,
)

private data class GoogleHttpResponse(
    val value: JsonElement,
    val rawText: String,
    val headers: Map<String, String>,
)

private val googleJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
}

private fun googleGenerateContentBody(
    modelId: String,
    settings: GoogleGenerativeAIProviderSettings,
    params: LanguageModelCallParams,
    stream: Boolean,
): GooglePreparedRequest {
    val warnings = mutableListOf<CallWarning>()
    val options = params.providerOptions["google"] as? JsonObject ?: JsonObject(emptyMap())
    val converted = googleMessages(params.messages)
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
            params.responseFormat.schemaJson?.let { put("responseSchema", it) }
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
            googleToolConfig(params.toolChoice, options)?.let { put("toolConfig", it) }
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

private fun googleMessages(messages: List<ModelMessage>): GoogleConvertedMessages {
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
                put("parts", JsonArray(message.content.mapNotNull(::googleAssistantPart)))
            }
            MessageRole.Tool -> contents += buildJsonObject {
                put("role", JsonPrimitive("function"))
                put("parts", JsonArray(message.content.mapNotNull(::googleToolPart)))
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

private fun googleAssistantPart(part: ContentPart): JsonElement? = when (part) {
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
    }
    else -> null
}

private fun googleToolPart(part: ContentPart): JsonElement? = when (part) {
    is ContentPart.ToolResult -> buildJsonObject {
        put("functionResponse", buildJsonObject {
            put("id", JsonPrimitive(part.toolCallId))
            put("name", JsonPrimitive(part.toolName))
            put("response", part.modelVisible)
        })
    }
    is ContentPart.ToolApprovalResponse -> buildJsonObject {
        put("functionResponse", buildJsonObject {
            put("id", JsonPrimitive(part.toolCallId))
            put("name", JsonPrimitive(part.toolCallId))
            put("response", buildJsonObject {
                put("approved", JsonPrimitive(part.approved))
                part.reason?.let { put("reason", JsonPrimitive(it)) }
            })
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
            put("parameters", googleJson.parseToJsonElement(tool.parametersSchemaJson))
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
            val call = ContentPart.ToolCall(id, "code_execution", code, mapOf("google" to buildJsonObject { put("providerExecuted", JsonPrimitive(true)) }))
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
        for (part in parts) {
            val obj = part.jsonObject
            obj["text"]?.jsonPrimitive?.contentOrNull?.let { text ->
                if (obj["thought"]?.jsonPrimitive?.booleanOrNull == true) {
                    if (textId != null) {
                        events += StreamEvent.TextEnd(textId.orEmpty())
                        textId = null
                    }
                    if (reasoningId == null) {
                        reasoningId = (blockCounter++).toString()
                        events += StreamEvent.ReasoningStart(reasoningId.orEmpty(), googlePartMetadata(obj))
                    }
                    events += StreamEvent.ReasoningDelta(reasoningId.orEmpty(), text, googlePartMetadata(obj))
                } else {
                    if (reasoningId != null) {
                        events += StreamEvent.ReasoningEnd(reasoningId.orEmpty())
                        reasoningId = null
                    }
                    if (textId == null) {
                        textId = (blockCounter++).toString()
                        events += StreamEvent.TextStart(textId.orEmpty(), googlePartMetadata(obj))
                    }
                    events += StreamEvent.TextDelta(textId.orEmpty(), text, googlePartMetadata(obj))
                }
            }
            obj["functionCall"]?.jsonObject?.let { call ->
                val id = call["id"]?.jsonPrimitive?.contentOrNull ?: generateId()
                val name = call["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val input = call["args"] ?: JsonObject(emptyMap())
                hasToolCalls = true
                events += StreamEvent.ToolInputStart(id, name, googlePartMetadata(obj))
                events += StreamEvent.ToolInputDelta(id, input.toString(), googlePartMetadata(obj))
                events += StreamEvent.ToolInputEnd(id, googlePartMetadata(obj))
                events += StreamEvent.ToolCall(id, name, input, googlePartMetadata(obj))
            }
            obj["inlineData"]?.jsonObject?.let { data ->
                events += StreamEvent.FilePart(
                    id = generateId(),
                    mediaType = data["mimeType"]?.jsonPrimitive?.contentOrNull ?: "application/octet-stream",
                    base64 = data["data"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    providerMetadata = googlePartMetadata(obj),
                )
            }
        }
        googleSources(candidate, generateId).forEach { source ->
            events += StreamEvent.SourcePart(
                id = source.providerMetadata?.get("google")?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull ?: generateId(),
                sourceType = source.sourceType,
                url = source.url,
                title = source.title,
                providerMetadata = source.providerMetadata,
            )
        }
        candidate["finishReason"]?.jsonPrimitive?.contentOrNull?.let { finishReason = mapGoogleFinishReason(it, hasToolCalls) }
        return events
    }

    fun finish(): List<StreamEvent> {
        val events = mutableListOf<StreamEvent>()
        textId?.let { events += StreamEvent.TextEnd(it) }
        reasoningId?.let { events += StreamEvent.ReasoningEnd(it) }
        events += StreamEvent.Finish(1, finishReason, usage)
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

private suspend fun googlePostJson(
    client: HttpClient,
    url: String,
    body: JsonElement,
    headers: Map<String, String>,
    abortSignal: AbortSignal,
    parseJson: Boolean,
): GoogleHttpResponse {
    abortSignal.throwIfAborted()
    val response = client.request(url) {
        method = HttpMethod.Post
        contentType(ContentType.Application.Json)
        headers.forEach { (name, value) -> header(name, value) }
        setBody(googleJson.encodeToString(JsonElement.serializer(), body))
    }
    return response.parseGoogleResponse(parseJson)
}

private suspend fun googleGetJson(
    client: HttpClient,
    url: String,
    headers: Map<String, String>,
    abortSignal: AbortSignal,
): GoogleHttpResponse {
    abortSignal.throwIfAborted()
    val response = client.request(url) {
        method = HttpMethod.Get
        headers.forEach { (name, value) -> header(name, value) }
    }
    return response.parseGoogleResponse(parseJson = true)
}

private suspend fun HttpResponse.parseGoogleResponse(parseJson: Boolean): GoogleHttpResponse {
    val raw = bodyAsText()
    val headers = responseHeaders()
    if (status.value !in 200..299) {
        val parsed = runCatching { googleJson.parseToJsonElement(raw) }.getOrNull()
        throw AiSdkException(googleErrorMessage(parsed, raw))
    }
    return GoogleHttpResponse(
        value = if (parseJson && raw.isNotBlank()) googleJson.parseToJsonElement(raw) else JsonObject(emptyMap()),
        rawText = raw,
        headers = headers,
    )
}

private fun googleHeaders(settings: GoogleGenerativeAIProviderSettings, extra: Map<String, String>): Map<String, String> {
    val headers = linkedMapOf<String, String>()
    settings.apiKey?.let { headers["x-goog-api-key"] = it }
    headers.putAll(settings.headers)
    headers.putAll(extra)
    headers[HttpHeaders.UserAgent] = appendGoogleUserAgent(headers[HttpHeaders.UserAgent], "ai-sdk/google/$GOOGLE_VERSION")
    return headers
}

private fun googleUsage(element: JsonElement?): Usage {
    val obj = element as? JsonObject ?: return Usage()
    val prompt = obj["promptTokenCount"]?.jsonPrimitive?.intOrNull ?: 0
    val candidates = obj["candidatesTokenCount"]?.jsonPrimitive?.intOrNull ?: 0
    val thoughts = obj["thoughtsTokenCount"]?.jsonPrimitive?.intOrNull ?: 0
    return Usage(
        inputTokens = Usage.InputTokenBreakdown(total = prompt),
        outputTokens = Usage.OutputTokenBreakdown(total = candidates, reasoning = thoughts, text = (candidates - thoughts).coerceAtLeast(0)),
        raw = element,
    )
}

private fun mapGoogleFinishReason(reason: String?, hasToolCalls: Boolean): FinishReason =
    if (hasToolCalls) {
        FinishReason.ToolCalls
    } else {
        when (reason) {
            "STOP" -> FinishReason.Stop
            "MAX_TOKENS" -> FinishReason.Length
            "SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII" -> FinishReason.ContentFilter
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
                put("id", JsonPrimitive(generateId()))
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
    return obj["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
        ?: obj["message"]?.jsonPrimitive?.contentOrNull
        ?: raw
}

private fun googleProviderTool(
    name: String,
    id: String,
    description: String,
): Tool<JsonElement, JsonElement, Any?> =
    providerExecutedTool(
        name = name,
        description = description,
        inputSerializer = JsonElement.serializer(),
        outputSerializer = JsonElement.serializer(),
        metadata = mapOf("providerToolId" to JsonPrimitive(id)),
    )

private fun HttpResponse.responseHeaders(): Map<String, String> =
    headers.entries().associate { it.key to it.value.joinToString(",") }

private fun JsonObjectBuilder.putJsonObjectFields(fields: JsonObject, excluded: Set<String> = emptySet()) {
    fields.forEach { (key, value) -> if (value !is JsonNull && key !in excluded) put(key, value) }
}

private fun appendGoogleUserAgent(existing: String?, suffix: String): String =
    existing?.takeIf { it.isNotBlank() }?.let { "$it $suffix" } ?: suffix
