package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

public data class OpenAICompatibleProviderSettings(
    val name: String,
    val baseUrl: String,
    val apiKey: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val queryParams: Map<String, String> = emptyMap(),
    val includeUsage: Boolean = false,
    val supportsStructuredOutputs: Boolean = false,
    val supportedUrls: Map<String, List<String>> = emptyMap(),
    val maxEmbeddingsPerCall: Int = 2048,
    val authHeadersProvider: (suspend () -> Map<String, String>)? = null,
    val urlBuilder: ((path: String, modelId: String) -> String)? = null,
    val userAgentSuffix: String? = "ai-sdk/openai-compatible-kotlin",
    val providerOptionsName: String? = null,
    val chatMaxOutputTokensKey: String = "max_tokens",
    val chatSeedKey: String = "seed",
    val transformChatRequestBody: ((JsonObject) -> JsonObject)? = null,
    val convertUsage: ((JsonElement?) -> Usage)? = null,
)

public interface OpenAICompatibleProvider : Provider {
    public fun chatModel(modelId: String): LanguageModel
    public fun completionModel(modelId: String): LanguageModel
    public fun textEmbeddingModel(modelId: String): EmbeddingModel = embeddingModel(modelId)
}

public fun createOpenAICompatible(
    client: HttpClient,
    settings: OpenAICompatibleProviderSettings,
    json: Json = aiSdkJson,
): OpenAICompatibleProvider = KtorOpenAICompatibleProvider(client, settings, json)

public fun createOpenAICompatibleProvider(
    client: HttpClient,
    settings: OpenAICompatibleProviderSettings,
    json: Json = aiSdkJson,
): OpenAICompatibleProvider = createOpenAICompatible(client, settings, json)

private class KtorOpenAICompatibleProvider(
    private val client: HttpClient,
    private val settings: OpenAICompatibleProviderSettings,
    private val json: Json,
) : OpenAICompatibleProvider {
    override val providerId: String = settings.name

    override fun languageModel(modelId: String): LanguageModel = chatModel(modelId)

    override fun chatModel(modelId: String): LanguageModel =
        OpenAICompatibleChatLanguageModel(client, settings, json, modelId)

    override fun completionModel(modelId: String): LanguageModel =
        OpenAICompatibleCompletionLanguageModel(client, settings, json, modelId)

    override fun embeddingModel(modelId: String): EmbeddingModel =
        OpenAICompatibleEmbeddingModel(client, settings, json, modelId)

    override fun imageModel(modelId: String): ImageModel =
        OpenAICompatibleImageModel(client, settings, json, modelId)

    override fun speechModel(modelId: String): SpeechModel =
        OpenAICompatibleSpeechModel(client, settings, json, modelId)

    override fun transcriptionModel(modelId: String): TranscriptionModel =
        OpenAICompatibleTranscriptionModel(client, settings, json, modelId)
}

private abstract class OpenAICompatibleHttpModel(
    protected val client: HttpClient,
    protected val settings: OpenAICompatibleProviderSettings,
    protected val json: Json,
    val modelId: String,
    private val modelType: String,
) {
    protected val providerName: String
        get() = "${settings.name}.$modelType"

    protected fun url(path: String): String {
        settings.urlBuilder?.let { return it(path, modelId) }
        val base = settings.baseUrl.trimEnd('/') + path
        if (settings.queryParams.isEmpty()) return base
        return base + "?" + settings.queryParams.entries.joinToString("&") { (key, value) ->
            "${urlEncode(key)}=${urlEncode(value)}"
        }
    }

    protected suspend fun commonHeaders(extra: Map<String, String> = emptyMap()): Map<String, String> {
        val dynamicAuthHeaders = settings.authHeadersProvider?.invoke()
        return buildProviderHeaders(settings.headers, extra, settings.userAgentSuffix) { base ->
            if (dynamicAuthHeaders != null) {
                base.putAll(dynamicAuthHeaders)
            } else {
                settings.apiKey?.takeIf { it.isNotBlank() }?.let { base[HttpHeaders.Authorization] = "Bearer $it" }
            }
        }
    }

    protected suspend fun postJson(
        path: String,
        body: JsonElement,
        headers: Map<String, String> = emptyMap(),
        parseJson: Boolean = true,
    ): HttpJsonResponse =
        requestJson(
            client = client,
            url = url(path),
            method = HttpMethod.Post,
            headers = commonHeaders(headers),
            body = body,
            json = json,
            parseJson = parseJson,
            errorMessage = ::openAICompatibleErrorMessage,
        )

    /**
     * Streaming counterpart of [postJson]: opens an SSE request and yields raw
     * response lines incrementally (see [streamSse]). The auth/common headers
     * are resolved inside the flow because [commonHeaders] is `suspend`.
     */
    protected fun postSse(
        path: String,
        body: JsonElement,
        headers: Map<String, String> = emptyMap(),
        onResponse: suspend (Map<String, String>) -> Unit = {},
    ): Flow<String> = flow {
        emitAll(
            streamSse(
                client = client,
                url = url(path),
                method = HttpMethod.Post,
                headers = commonHeaders(headers),
                body = body,
                json = json,
                errorMessage = ::openAICompatibleErrorMessage,
                onResponse = onResponse,
            ),
        )
    }

    protected suspend fun postMultipart(
        path: String,
        body: MultiPartFormDataContent,
        headers: Map<String, String> = emptyMap(),
    ): HttpJsonResponse {
        val response = client.request(url(path)) {
            method = HttpMethod.Post
            commonHeaders(headers).forEach { (name, value) -> header(name, value) }
            setBody(body)
        }
        return response.toJsonResponse(
            url = url(path),
            json = json,
            errorMessage = ::openAICompatibleErrorMessage,
        )
    }

    protected suspend fun postBytes(
        path: String,
        body: JsonElement,
        headers: Map<String, String> = emptyMap(),
    ): OpenAIBytesResponse {
        val response = client.request(url(path)) {
            method = HttpMethod.Post
            contentType(ContentType.Application.Json)
            commonHeaders(headers).forEach { (name, value) -> header(name, value) }
            setBody(json.encodeToString(JsonElement.serializer(), body))
        }
        val responseHeaders = response.flattenedHeaders()
        val bytes = response.bodyAsBytes()
        if (response.status.value !in 200..299) {
            val raw = bytes.decodeToString()
            val parsed = runCatching { json.parseToJsonElement(raw) }.getOrNull()
            throw apiCallError(
                url = url(path),
                statusCode = response.status.value,
                rawBody = raw,
                headers = responseHeaders,
                message = openAICompatibleErrorMessage(response.status.value, parsed, raw),
            )
        }
        return OpenAIBytesResponse(bytes = bytes, headers = responseHeaders)
    }
}

private class OpenAICompatibleChatLanguageModel(
    client: HttpClient,
    settings: OpenAICompatibleProviderSettings,
    json: Json,
    modelId: String,
) : OpenAICompatibleHttpModel(client, settings, json, modelId, "chat"), LanguageModel {
    override val provider: String
        get() = providerName

    override val supportedUrls: Map<String, List<String>>
        get() = settings.supportedUrls

    override suspend fun generate(params: LanguageModelCallParams): LanguageModelResult {
        val prepared = chatRequestBody(params, stream = false)
        val response = postJson("/chat/completions", prepared.body, params.headers)
        return chatResultFromJson(
            response.value,
            provider = providerName,
            requestBody = prepared.body,
            responseHeaders = response.headers,
            responseBody = response.value,
            warnings = prepared.warnings,
            convertUsage = settings.convertUsage,
        )
    }

    override fun stream(params: LanguageModelCallParams): Flow<StreamEvent> = flow {
        val prepared = chatRequestBody(params, stream = true)
        emit(StreamEvent.StreamStart(prepared.warnings))
        val state = OpenAIChatStreamState(provider = providerName, providerKey = providerOptionsKey(), convertUsage = settings.convertUsage)
        var sseHeaders: Map<String, String> = emptyMap()
        val rawLines = postSse(
            path = "/chat/completions",
            body = prepared.body,
            headers = params.headers + mapOf(HttpHeaders.Accept to "text/event-stream"),
            onResponse = { sseHeaders = it },
        )
        forwardSseEvents(
            events = parseJsonEventStream(rawLines, jsonSchema<JsonElement>(JsonObject(emptyMap())), json),
            capturedHeaders = { sseHeaders },
            parseErrorPrefix = "Failed to parse OpenAI-compatible stream event",
            onEvent = { state.accept(it).forEach { e -> emit(e) } },
        )
        state.finish().forEach { emit(it) }
    }

    private fun chatRequestBody(params: LanguageModelCallParams, stream: Boolean): PreparedOpenAIRequest {
        val warnings = mutableListOf<CallWarning>()
        if (params.topK != null) {
            warnings += CallWarning("unsupported", "topK is not supported by OpenAI-compatible chat models")
        }
        if (params.responseFormat is ResponseFormat.Json &&
            params.responseFormat.schemaJson != null &&
            !settings.supportsStructuredOutputs
        ) {
            warnings += CallWarning(
                "unsupported",
                "JSON response schema is only sent when supportsStructuredOutputs is true",
            )
        }
        val options = providerOptions(params.providerOptions)
        val strictJsonSchema = options["strictJsonSchema"]?.jsonPrimitive?.booleanOrNull ?: true
        val body = buildJsonObject {
            put("model", JsonPrimitive(modelId))
            put("messages", JsonArray(params.messages.flatMap(::openAIChatMessagesJson)))
            params.maxOutputTokens?.let { put(settings.chatMaxOutputTokensKey, JsonPrimitive(it)) }
            params.temperature?.let { put("temperature", JsonPrimitive(it)) }
            params.topP?.let { put("top_p", JsonPrimitive(it)) }
            params.frequencyPenalty?.let { put("frequency_penalty", JsonPrimitive(it)) }
            params.presencePenalty?.let { put("presence_penalty", JsonPrimitive(it)) }
            if (params.stopSequences.isNotEmpty()) put("stop", JsonArray(params.stopSequences.map(::JsonPrimitive)))
            params.seed?.let { put(settings.chatSeedKey, JsonPrimitive(it)) }
            val responseFormat = openAIResponseFormat(params.responseFormat, strictJsonSchema)
            if (responseFormat != null) put("response_format", responseFormat)
            if (params.tools.isNotEmpty()) put("tools", JsonArray(params.tools.map(::openAIToolJson)))
            val choice = openAIToolChoiceJson(params.toolChoice)
            if (choice != null) put("tool_choice", choice)
            if (stream) {
                put("stream", JsonPrimitive(true))
                if (settings.includeUsage) {
                    put("stream_options", buildJsonObject { put("include_usage", JsonPrimitive(true)) })
                }
            }
            // Canonical OpenAI options are reserved (so putProviderOptions skips their
            // camelCase forms), but must still be emitted under their snake_case wire keys.
            options["user"]?.takeIf { it !is JsonNull }?.let { put("user", it) }
            options["reasoningEffort"]?.takeIf { it !is JsonNull }?.let { put("reasoning_effort", it) }
            options["textVerbosity"]?.takeIf { it !is JsonNull }?.let { put("verbosity", it) }
            putProviderOptions(options, openAIChatReservedOptions)
        }
        return PreparedOpenAIRequest(settings.transformChatRequestBody?.invoke(body) ?: body, warnings)
    }

    private fun providerOptions(options: Map<String, JsonElement>): JsonObject =
        openAIProviderOptions(options, providerOptionsName())

    private fun providerOptionsKey(): String =
        providerOptionsName().substringBefore('.').trim().ifBlank { "openaiCompatible" }

    private fun providerOptionsName(): String = settings.providerOptionsName ?: settings.name
}

private class OpenAICompatibleCompletionLanguageModel(
    client: HttpClient,
    settings: OpenAICompatibleProviderSettings,
    json: Json,
    modelId: String,
) : OpenAICompatibleHttpModel(client, settings, json, modelId, "completion"), LanguageModel {
    override val provider: String
        get() = providerName

    override suspend fun generate(params: LanguageModelCallParams): LanguageModelResult {
        val prepared = completionRequestBody(params, stream = false)
        val response = postJson("/completions", prepared.body, params.headers)
        return completionResultFromJson(response.value, providerName, prepared.body, response.headers, response.value, prepared.warnings)
    }

    override fun stream(params: LanguageModelCallParams): Flow<StreamEvent> = flow {
        val prepared = completionRequestBody(params, stream = true)
        emit(StreamEvent.StreamStart(prepared.warnings))
        var activeText = false
        var finish = FinishReason.Other
        var rawFinish: String? = null
        var usage = Usage()
        var emittedResponseMetadata = false
        var sseHeaders: Map<String, String> = emptyMap()
        var headerMetaEmitted = false
        val rawLines = postSse(
            path = "/completions",
            body = prepared.body,
            headers = params.headers + mapOf(HttpHeaders.Accept to "text/event-stream"),
            onResponse = { sseHeaders = it },
        )
        parseJsonEventStream(rawLines, jsonSchema<JsonElement>(JsonObject(emptyMap())), json).collect { event ->
            if (!headerMetaEmitted) {
                emit(StreamEvent.ResponseMetadata(headers = sseHeaders))
                headerMetaEmitted = true
            }
            when (event) {
                is ParseResult.Failure -> emit(StreamEvent.Error("Failed to parse OpenAI-compatible completion stream event: ${event.error.message}"))
                is ParseResult.Success -> {
                    val value = event.value.jsonObject
                    if (!emittedResponseMetadata) {
                        streamResponseMetadata(value)?.let {
                            emit(it)
                            emittedResponseMetadata = true
                        }
                    }
                    value["usage"]?.let { usage = openAIUsage(it) }
                    val choice = value["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                    val text = choice?.get("text")?.jsonPrimitive?.contentOrNull
                    if (!text.isNullOrEmpty()) {
                        if (!activeText) {
                            emit(StreamEvent.TextStart("txt-0"))
                            activeText = true
                        }
                        emit(StreamEvent.TextDelta("txt-0", text))
                    }
                    choice?.get("finish_reason")?.jsonPrimitive?.contentOrNull?.let {
                        finish = openAIFinishReason(it)
                        rawFinish = it
                    }
                }
            }
        }
        if (!headerMetaEmitted) emit(StreamEvent.ResponseMetadata(headers = sseHeaders))
        if (activeText) emit(StreamEvent.TextEnd("txt-0"))
        emit(StreamEvent.Finish(totalSteps = 1, finishReason = finish, usage = usage, rawFinishReason = rawFinish))
    }

    private fun completionRequestBody(params: LanguageModelCallParams, stream: Boolean): PreparedOpenAIRequest {
        val warnings = mutableListOf<CallWarning>()
        if (params.tools.isNotEmpty()) warnings += CallWarning("unsupported", "tools are not supported by completion models")
        if (params.topK != null) warnings += CallWarning("unsupported", "topK is not supported by completion models")
        val options = openAIProviderOptions(params.providerOptions, settings.providerOptionsName ?: settings.name)
        val body = buildJsonObject {
            put("model", JsonPrimitive(modelId))
            put("prompt", JsonPrimitive(openAICompletionPrompt(params.messages)))
            params.maxOutputTokens?.let { put("max_tokens", JsonPrimitive(it)) }
            params.temperature?.let { put("temperature", JsonPrimitive(it)) }
            params.topP?.let { put("top_p", JsonPrimitive(it)) }
            params.frequencyPenalty?.let { put("frequency_penalty", JsonPrimitive(it)) }
            params.presencePenalty?.let { put("presence_penalty", JsonPrimitive(it)) }
            if (params.stopSequences.isNotEmpty()) put("stop", JsonArray(params.stopSequences.map(::JsonPrimitive)))
            params.seed?.let { put("seed", JsonPrimitive(it)) }
            if (stream) {
                put("stream", JsonPrimitive(true))
                if (settings.includeUsage) {
                    put("stream_options", buildJsonObject { put("include_usage", JsonPrimitive(true)) })
                }
            }
            putProviderOptions(options, openAICompletionReservedOptions)
        }
        return PreparedOpenAIRequest(body, warnings)
    }
}

private class OpenAICompatibleEmbeddingModel(
    client: HttpClient,
    settings: OpenAICompatibleProviderSettings,
    json: Json,
    modelId: String,
) : OpenAICompatibleHttpModel(client, settings, json, modelId, "embedding"), EmbeddingModel {
    override val provider: String
        get() = providerName

    override suspend fun embed(params: EmbeddingModelCallParams): EmbeddingModelResult {
        val max = params.maxEmbeddingsPerCall ?: settings.maxEmbeddingsPerCall
        if (params.values.size > max) {
            throw InvalidArgumentError("values", "embedding model ${settings.name}:$modelId supports at most $max values per call")
        }
        val options = openAIProviderOptions(params.providerOptions, settings.providerOptionsName ?: settings.name)
        val body = buildJsonObject {
            put("model", JsonPrimitive(modelId))
            put("input", JsonArray(params.values.map(::JsonPrimitive)))
            put("encoding_format", JsonPrimitive("float"))
            options["dimensions"]?.let { put("dimensions", it) }
            options["user"]?.let { put("user", it) }
            putProviderOptions(options, setOf("dimensions", "user"))
        }
        val response = postJson("/embeddings", body, params.headers)
        val value = response.value.jsonObject
        return EmbeddingModelResult(
            embeddings = value["data"]?.jsonArray.orEmpty()
                .sortedBy { it.jsonObject["index"]?.jsonPrimitive?.intOrNull ?: Int.MAX_VALUE }
                .map { item -> item.jsonObject["embedding"]?.jsonArray.orEmpty().map { embeddingFloat(it, provider) } },
            usage = EmbeddingUsage(
                tokens = value["usage"]?.jsonObject?.get("prompt_tokens")?.jsonPrimitive?.intOrNull ?: 0,
                raw = value["usage"],
            ),
            warnings = emptyList(),
            request = LanguageModelRequestMetadata(body),
            response = LanguageModelResponseMetadata(headers = response.headers, body = response.value),
            providerMetadata = openAIProviderMetadata(value["providerMetadata"], settings.name),
        )
    }
}

private class OpenAICompatibleImageModel(
    client: HttpClient,
    settings: OpenAICompatibleProviderSettings,
    json: Json,
    modelId: String,
) : OpenAICompatibleHttpModel(client, settings, json, modelId, "image"), ImageModel {
    override val provider: String
        get() = providerName

    override suspend fun generate(params: ImageGenerationParams): ImageModelResult {
        val warnings = mutableListOf<CallWarning>()
        if (params.aspectRatio != null) {
            warnings += CallWarning("unsupported", "aspectRatio is not supported by OpenAI-compatible image generation; use size")
        }
        if (params.seed != null) {
            warnings += CallWarning("unsupported", "seed is not supported by OpenAI-compatible image generation")
        }
        val options = openAIProviderOptions(params.providerOptions, settings.providerOptionsName ?: settings.name)
        val body = buildJsonObject {
            put("model", JsonPrimitive(modelId))
            put("prompt", JsonPrimitive(params.prompt))
            put("n", JsonPrimitive(params.n))
            params.size?.let { put("size", JsonPrimitive(it)) }
            put("response_format", JsonPrimitive("b64_json"))
            putProviderOptions(options, emptySet())
        }
        val response = postJson("/images/generations", body, params.headers)
        val responseObject = WireDecoder.objectValue(response.value, providerName, "image generation response")
        val data = WireDecoder.requiredArray(responseObject, "data", providerName, "image generation response")
        if (data.isEmpty()) throw NoImageGeneratedError("OpenAI-compatible image response contained no data.")
        return ImageModelResult(
            images = data.mapIndexed { index, image ->
                val obj = WireDecoder.objectValue(image, providerName, "image generation response", "$.data[$index]")
                val imageData = WireDecoder.requiredOneOfString(obj, providerName, "image generation response", "$.data[$index]", "b64_json", "url")
                GeneratedFile(
                    mediaType = obj["media_type"]?.jsonPrimitive?.contentOrNull ?: "image/png",
                    base64 = obj["b64_json"]?.let { imageData }.orEmpty(),
                    url = obj["url"]?.jsonPrimitive?.contentOrNull,
                )
            },
            warnings = warnings,
            response = LanguageModelResponseMetadata(modelId = modelId, headers = response.headers, body = response.value),
            providerMetadata = openAIProviderMetadata(responseObject["providerMetadata"], settings.name),
        )
    }
}

private class OpenAICompatibleSpeechModel(
    client: HttpClient,
    settings: OpenAICompatibleProviderSettings,
    json: Json,
    modelId: String,
) : OpenAICompatibleHttpModel(client, settings, json, modelId, "speech"), SpeechModel {
    override val provider: String
        get() = providerName

    override suspend fun generate(params: SpeechGenerationParams): SpeechModelResult {
        val options = openAIProviderOptions(params.providerOptions, settings.providerOptionsName ?: settings.name)
        val format = params.responseFormat ?: options["response_format"]?.jsonPrimitive?.contentOrNull ?: "mp3"
        val body = buildJsonObject {
            put("model", JsonPrimitive(modelId))
            put("input", JsonPrimitive(params.text))
            params.voice?.let { put("voice", JsonPrimitive(it)) }
            params.instructions?.let { put("instructions", JsonPrimitive(it)) }
            params.speed?.let { put("speed", JsonPrimitive(it)) }
            params.language?.let { put("language", JsonPrimitive(it)) }
            put("response_format", JsonPrimitive(format))
            putProviderOptions(options, setOf("response_format"))
        }
        val response = postBytes("/audio/speech", body, params.headers)
        return SpeechModelResult(
            audio = GeneratedFile(
                mediaType = response.headers[HttpHeaders.ContentType] ?: audioMediaType(format),
                base64 = convertByteArrayToBase64(response.bytes),
            ),
            response = LanguageModelResponseMetadata(modelId = modelId, headers = response.headers),
        )
    }
}

private class OpenAICompatibleTranscriptionModel(
    client: HttpClient,
    settings: OpenAICompatibleProviderSettings,
    json: Json,
    modelId: String,
) : OpenAICompatibleHttpModel(client, settings, json, modelId, "transcription"), TranscriptionModel {
    override val provider: String
        get() = providerName

    override suspend fun transcribe(params: TranscriptionParams): TranscriptionModelResult {
        val options = openAIProviderOptions(params.providerOptions, settings.providerOptionsName ?: settings.name)
        val multipart = MultiPartFormDataContent(
            formData {
                append("model", modelId)
                append("response_format", "json")
                params.language?.let { append("language", it) }
                params.prompt?.let { append("prompt", it) }
                for ((key, value) in options) {
                    if (key !in setOf("response_format", "file")) append(key, openAIFormValue(value))
                }
                append(
                    "file",
                    convertBase64ToByteArray(params.audio.base64),
                    Headers.build {
                        append(HttpHeaders.ContentType, params.audio.mediaType)
                        append(
                            HttpHeaders.ContentDisposition,
                            "filename=\"${params.audio.filename ?: "audio.${mediaTypeToExtension(params.audio.mediaType)}"}\"",
                        )
                    },
                )
            },
        )
        val response = postMultipart("/audio/transcriptions", multipart, params.headers)
        val value = WireDecoder.objectValue(response.value, providerName, "transcription response")
        return TranscriptionModelResult(
            text = WireDecoder.requiredString(value, "text", providerName, "transcription response"),
            segments = WireDecoder.optionalArray(value, "segments", providerName, "transcription response").orEmpty().mapIndexed { index, segment ->
                val obj = WireDecoder.objectValue(segment, providerName, "transcription response", "$.segments[$index]")
                TranscriptSegment(
                    text = WireDecoder.requiredString(obj, "text", providerName, "transcription response", "$.segments[$index]"),
                    startSeconds = WireDecoder.optionalFloat(obj, "start", providerName, "transcription response", "$.segments[$index]"),
                    endSeconds = WireDecoder.optionalFloat(obj, "end", providerName, "transcription response", "$.segments[$index]"),
                )
            },
            response = LanguageModelResponseMetadata(modelId = modelId, headers = response.headers, body = response.value),
            providerMetadata = openAIProviderMetadata(value["providerMetadata"], settings.name),
            // verbose_json responses carry the detected language + audio duration.
            language = WireDecoder.optionalString(value, "language", providerName, "transcription response"),
            durationInSeconds = WireDecoder.optionalFloat(value, "duration", providerName, "transcription response"),
        )
    }
}

private data class PreparedOpenAIRequest(
    val body: JsonObject,
    val warnings: List<CallWarning>,
)

private data class OpenAIBytesResponse(
    val bytes: ByteArray,
    val headers: Map<String, String>,
)


private val openAIChatReservedOptions = setOf(
    "user",
    "strictJsonSchema",
    "reasoningEffort",
    "textVerbosity",
)

private val openAICompletionReservedOptions = setOf(
    "user",
)

private fun chatResultFromJson(
    value: JsonElement,
    provider: String,
    requestBody: JsonElement,
    responseHeaders: Map<String, String>,
    responseBody: JsonElement,
    warnings: List<CallWarning>,
    convertUsage: ((JsonElement?) -> Usage)? = null,
): LanguageModelResult {
    val obj = WireDecoder.objectValue(value, provider, "chat completion response")
    val choice = WireDecoder.requiredArray(obj, "choices", provider, "chat completion response")
        .firstOrNull()
        ?.let { WireDecoder.objectValue(it, provider, "chat completion response", "$.choices[0]") }
        ?: WireDecoder.fail(provider, "chat completion response", "$.choices", "expected at least one choice")
    val message = WireDecoder.objectValue(
        WireDecoder.required(choice, "message", provider, "chat completion response", "$.choices[0]"),
        provider,
        "chat completion response",
        "$.choices[0].message",
    )
    val content = mutableListOf<ContentPart>()
    val text = openAITextContent(message["content"])
    if (text.isNotEmpty()) content += ContentPart.Text(text)
    val reasoning = message["reasoning_content"]?.jsonPrimitive?.contentOrNull
        ?: message["reasoning"]?.jsonPrimitive?.contentOrNull
    if (!reasoning.isNullOrEmpty()) content += ContentPart.Reasoning(reasoning)
    val toolCalls = WireDecoder.optionalArray(message, "tool_calls", provider, "chat completion response", "$.choices[0].message").orEmpty()
        .mapIndexed { index, call ->
        val callObj = WireDecoder.objectValue(call, provider, "chat completion response", "$.choices[0].message.tool_calls[$index]")
        val function = WireDecoder.objectValue(
            WireDecoder.required(callObj, "function", provider, "chat completion response", "$.choices[0].message.tool_calls[$index]"),
            provider,
            "chat completion response",
            "$.choices[0].message.tool_calls[$index].function",
        )
        ContentPart.ToolCall(
            toolCallId = callObj["id"]?.jsonPrimitive?.contentOrNull ?: generateId("call"),
            toolName = WireDecoder.requiredString(function, "name", provider, "chat completion response", "$.choices[0].message.tool_calls[$index].function"),
            input = parseOpenAIToolInput(WireDecoder.requiredString(function, "arguments", provider, "chat completion response", "$.choices[0].message.tool_calls[$index].function")),
            providerMetadata = thoughtSignatureMetadata(callObj),
        )
    }
    content += toolCalls
    val finishReason = openAIFinishReason(choice["finish_reason"]?.jsonPrimitive?.contentOrNull)
    return LanguageModelResult(
        text = text,
        toolCalls = toolCalls,
        finishReason = finishReason,
        usage = (convertUsage ?: ::openAIUsage).invoke(obj["usage"]),
        providerMetadata = openAIProviderMetadata(obj["providerMetadata"], "openaiCompatible"),
        content = content,
        rawFinishReason = choice["finish_reason"]?.jsonPrimitive?.contentOrNull,
        warnings = warnings,
        request = LanguageModelRequestMetadata(requestBody),
        response = LanguageModelResponseMetadata(
            id = obj["id"]?.jsonPrimitive?.contentOrNull,
            timestampMillis = obj["created"]?.jsonPrimitive?.doubleOrNull?.let { (it * 1000).toLong() },
            modelId = obj["model"]?.jsonPrimitive?.contentOrNull,
            headers = responseHeaders,
            body = responseBody,
        ),
    )
}

private fun completionResultFromJson(
    value: JsonElement,
    provider: String,
    requestBody: JsonElement,
    responseHeaders: Map<String, String>,
    responseBody: JsonElement,
    warnings: List<CallWarning>,
): LanguageModelResult {
    val obj = WireDecoder.objectValue(value, provider, "completion response")
    val choice = WireDecoder.requiredArray(obj, "choices", provider, "completion response")
        .firstOrNull()
        ?.let { WireDecoder.objectValue(it, provider, "completion response", "$.choices[0]") }
        ?: WireDecoder.fail(provider, "completion response", "$.choices", "expected at least one choice")
    val text = WireDecoder.requiredString(choice, "text", provider, "completion response", "$.choices[0]")
    return LanguageModelResult(
        text = text,
        finishReason = openAIFinishReason(choice["finish_reason"]?.jsonPrimitive?.contentOrNull),
        usage = openAIUsage(obj["usage"]),
        rawFinishReason = choice["finish_reason"]?.jsonPrimitive?.contentOrNull,
        warnings = warnings,
        request = LanguageModelRequestMetadata(requestBody),
        response = LanguageModelResponseMetadata(
            id = obj["id"]?.jsonPrimitive?.contentOrNull,
            timestampMillis = obj["created"]?.jsonPrimitive?.doubleOrNull?.let { (it * 1000).toLong() },
            modelId = obj["model"]?.jsonPrimitive?.contentOrNull,
            headers = responseHeaders,
            body = responseBody,
        ),
    )
}

private class OpenAIChatStreamState(
    private val provider: String,
    private val providerKey: String,
    private val convertUsage: ((JsonElement?) -> Usage)? = null,
) {
    private val toolCalls = linkedMapOf<Int, StreamingToolCall>()
    private var finishReason = FinishReason.Other
    private var rawFinishReason: String? = null
    private var usage = Usage()
    private var activeReasoning = false
    private var activeText = false
    private var emittedResponseMetadata = false

    fun accept(value: JsonElement): List<StreamEvent> {
        val events = mutableListOf<StreamEvent>()
        val obj = WireDecoder.objectValue(value, provider, "chat stream event")
        if (!emittedResponseMetadata) {
            streamResponseMetadata(obj)?.let {
                events += it
                emittedResponseMetadata = true
            }
        }
        if (obj["error"] != null) {
            events += StreamEvent.Error(
                obj["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
                    ?: obj["error"]?.jsonPrimitive?.contentOrNull
                    ?: "OpenAI-compatible stream error",
            )
            finishReason = FinishReason.Error
            return events
        }
        obj["usage"]?.let { usage = (convertUsage ?: ::openAIUsage).invoke(it) }
        val choice = obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: return events
        choice["finish_reason"]?.jsonPrimitive?.contentOrNull?.let {
            finishReason = openAIFinishReason(it)
            rawFinishReason = it
        }
        val delta = choice["delta"]?.jsonObject ?: return events
        val reasoning = delta["reasoning_content"]?.jsonPrimitive?.contentOrNull
            ?: delta["reasoning"]?.jsonPrimitive?.contentOrNull
        if (!reasoning.isNullOrEmpty()) {
            if (!activeReasoning) {
                events += StreamEvent.ReasoningStart("reasoning-0")
                activeReasoning = true
            }
            events += StreamEvent.ReasoningDelta("reasoning-0", reasoning)
        }
        val text = delta["content"]?.jsonPrimitive?.contentOrNull
        if (!text.isNullOrEmpty()) {
            if (activeReasoning) {
                events += StreamEvent.ReasoningEnd("reasoning-0")
                activeReasoning = false
            }
            if (!activeText) {
                events += StreamEvent.TextStart("txt-0")
                activeText = true
            }
            events += StreamEvent.TextDelta("txt-0", text)
        }
        val calls = WireDecoder.optionalArray(delta, "tool_calls", provider, "chat stream event", "$.choices[0].delta").orEmpty()
        if (calls.isNotEmpty() && activeReasoning) {
            events += StreamEvent.ReasoningEnd("reasoning-0")
            activeReasoning = false
        }
        for (call in calls) {
            events += acceptToolCallDelta(call)
        }
        return events
    }

    fun finish(): List<StreamEvent> = buildList {
        if (activeReasoning) add(StreamEvent.ReasoningEnd("reasoning-0"))
        if (activeText) add(StreamEvent.TextEnd("txt-0"))
        for (toolCall in toolCalls.values.filter { !it.finished }) {
            add(StreamEvent.ToolInputEnd(toolCall.id))
            add(
                StreamEvent.ToolCall(
                    toolCallId = toolCall.id,
                    toolName = toolCall.name,
                    inputJson = parseOpenAIToolInput(toolCall.arguments),
                    providerMetadata = toolCall.providerMetadata,
                ),
            )
            toolCall.finished = true
        }
        add(
            StreamEvent.Finish(
                totalSteps = 1,
                finishReason = finishReason,
                usage = usage,
                rawFinishReason = rawFinishReason,
            ),
        )
    }

    private fun acceptToolCallDelta(value: JsonElement): List<StreamEvent> {
        val obj = WireDecoder.objectValue(value, provider, "chat stream tool call")
        val index = WireDecoder.optionalInt(obj, "index", provider, "chat stream tool call") ?: toolCalls.size
        val function = obj["function"]?.let {
            WireDecoder.objectValue(it, provider, "chat stream tool call", "$.function")
        } ?: JsonObject(emptyMap())
        val existing = toolCalls[index]
        if (existing == null) {
            val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: generateId("call")
            val name = WireDecoder.requiredString(function, "name", provider, "chat stream tool call", "$.function")
            val arguments = WireDecoder.optionalString(function, "arguments", provider, "chat stream tool call", "$.function").orEmpty()
            val metadata = thoughtSignatureMetadata(obj)?.let { mapOf(providerKey to JsonObject(it)) }
            val toolCall = StreamingToolCall(id, name, arguments, metadata)
            toolCalls[index] = toolCall
            return buildList {
                add(StreamEvent.ToolInputStart(id, name, providerMetadata = metadata))
                if (arguments.isNotEmpty()) add(StreamEvent.ToolInputDelta(id, arguments, providerMetadata = metadata))
                if (isParsableOpenAIJson(arguments)) {
                    add(StreamEvent.ToolInputEnd(id, providerMetadata = metadata))
                    add(StreamEvent.ToolCall(id, name, parseOpenAIToolInput(arguments), providerMetadata = metadata))
                    toolCall.finished = true
                }
            }
        }
        if (existing.finished) return emptyList()
        val delta = function["arguments"]?.jsonPrimitive?.contentOrNull.orEmpty()
        existing.arguments += delta
        return buildList {
            if (delta.isNotEmpty()) add(StreamEvent.ToolInputDelta(existing.id, delta, providerMetadata = existing.providerMetadata))
            if (isParsableOpenAIJson(existing.arguments)) {
                add(StreamEvent.ToolInputEnd(existing.id, providerMetadata = existing.providerMetadata))
                add(
                    StreamEvent.ToolCall(
                        existing.id,
                        existing.name,
                        parseOpenAIToolInput(existing.arguments),
                        providerMetadata = existing.providerMetadata,
                    ),
                )
                existing.finished = true
            }
        }
    }
}

private fun streamResponseMetadata(obj: JsonObject): StreamEvent.ResponseMetadata? {
    val id = obj["id"]?.jsonPrimitive?.contentOrNull
    val modelId = obj["model"]?.jsonPrimitive?.contentOrNull
    val timestampMillis = obj["created"]?.jsonPrimitive?.doubleOrNull?.let { (it * 1000).toLong() }
    if (id == null && modelId == null && timestampMillis == null) return null
    return StreamEvent.ResponseMetadata(
        id = id,
        modelId = modelId,
        timestampMillis = timestampMillis,
    )
}

private data class StreamingToolCall(
    val id: String,
    val name: String,
    var arguments: String,
    val providerMetadata: Map<String, JsonElement>? = null,
    var finished: Boolean = false,
)

/**
 * One message as OpenAI chat-format JSON — or null when the message is SDK-internal bookkeeping that must
 * NOT reach the wire. OpenAI-format providers have no tool-approval concept: a Tool-role message carrying
 * only a [ContentPart.ToolApprovalResponse] (appended by the approval-resume cycle) used to serialize as
 * `{role:"tool", tool_call_id:"", content:""}`, which strict shims reject (Gemini:
 * `function_response.name: Name cannot be empty`). The wire sees the assistant's `tool_calls` entry and
 * the eventual real [ContentPart.ToolResult] — a consistent OpenAI conversation; approvals stay internal.
 */
private fun openAIChatMessagesJson(message: ModelMessage): List<JsonObject> = when (message.role) {
    MessageRole.System -> listOf(
        buildJsonObject {
            put("role", JsonPrimitive("system"))
            put("content", JsonPrimitive(message.content.filterIsInstance<ContentPart.Text>().joinToString("") { it.text }))
        },
    )
    MessageRole.User -> listOf(
        buildJsonObject {
            put("role", JsonPrimitive("user"))
            if (message.content.size == 1 && message.content.single() is ContentPart.Text) {
                put("content", JsonPrimitive((message.content.single() as ContentPart.Text).text))
            } else {
                put("content", JsonArray(message.content.mapNotNull(::openAIUserContentPartJson)))
            }
        },
    )
    MessageRole.Assistant -> listOf(
        buildJsonObject {
            put("role", JsonPrimitive("assistant"))
            val text = message.content.filterIsInstance<ContentPart.Text>().joinToString("") { it.text }
            val reasoning = message.content.filterIsInstance<ContentPart.Reasoning>().joinToString("") { it.text }
            val toolCalls = message.content.filterIsInstance<ContentPart.ToolCall>()
            put("content", if (toolCalls.isEmpty()) JsonPrimitive(text) else JsonPrimitive(text.takeIf { it.isNotEmpty() }))
            if (reasoning.isNotEmpty()) put("reasoning_content", JsonPrimitive(reasoning))
            if (toolCalls.isNotEmpty()) {
                put(
                    "tool_calls",
                    JsonArray(toolCalls.map { part ->
                        buildJsonObject {
                            put("id", JsonPrimitive(part.toolCallId))
                            put("type", JsonPrimitive("function"))
                            put(
                                "function",
                                buildJsonObject {
                                    put("name", JsonPrimitive(part.toolName))
                                    put("arguments", JsonPrimitive(part.input.toString()))
                                },
                            )
                        }
                    }),
                )
            }
        },
    )
    MessageRole.Tool -> openAIToolMessagesJson(message)
}

/**
 * A Tool-role message expands to one wire `tool` message per real [ContentPart.ToolResult] — matching
 * upstream's per-result loop (OpenAI requires one `tool` message per `tool_call_id`). Approval-response
 * parts carry no wire concept, so a message with no [ContentPart.ToolResult] (approval bookkeeping)
 * produces no wire messages and never reaches the wire.
 */
private fun openAIToolMessagesJson(message: ModelMessage): List<JsonObject> =
    message.content.filterIsInstance<ContentPart.ToolResult>().map { result ->
        buildJsonObject {
            put("role", JsonPrimitive("tool"))
            put("tool_call_id", JsonPrimitive(result.toolCallId))
            put("content", JsonPrimitive(openAIContentString(result.modelVisible)))
        }
    }

private fun openAIUserContentPartJson(part: ContentPart): JsonObject? = when (part) {
    is ContentPart.Text -> buildJsonObject {
        put("type", JsonPrimitive("text"))
        put("text", JsonPrimitive(part.text))
    }
    is ContentPart.Image -> buildJsonObject {
        put("type", JsonPrimitive("image_url"))
        val src = openAiImageUrl(part.url, part.mediaType, part.base64)
        put("image_url", buildJsonObject { put("url", JsonPrimitive(src)) })
    }
    is ContentPart.File -> when {
        part.mediaType.startsWith("image/") -> buildJsonObject {
            put("type", JsonPrimitive("image_url"))
            val src = openAiImageUrl(part.url, part.mediaType, part.base64)
            put("image_url", buildJsonObject { put("url", JsonPrimitive(src)) })
        }
        part.mediaType.startsWith("audio/") -> buildJsonObject {
            put("type", JsonPrimitive("input_audio"))
            put(
                "input_audio",
                buildJsonObject {
                    put("data", JsonPrimitive(part.base64))
                    put("format", JsonPrimitive(if (part.mediaType == "audio/wav") "wav" else "mp3"))
                },
            )
        }
        part.mediaType == "application/pdf" -> buildJsonObject {
            put("type", JsonPrimitive("file"))
            put(
                "file",
                buildJsonObject {
                    put("filename", JsonPrimitive(part.filename ?: "document.pdf"))
                    put("file_data", JsonPrimitive("data:application/pdf;base64,${part.base64}"))
                },
            )
        }
        part.mediaType.startsWith("text/") -> buildJsonObject {
            put("type", JsonPrimitive("text"))
            put("text", JsonPrimitive(convertBase64ToByteArray(part.base64).decodeToString()))
        }
        else -> null
    }
    else -> null
}

/**
 * The `image_url.url` value: a remote [url] is passed through directly (OpenAI
 * fetches it); otherwise the inline [base64] is wrapped as a data URL. Closes the
 * gap where a ContentPart carrying only a `url` produced `data:...;base64,` (empty).
 */
private fun openAiImageUrl(url: String?, mediaType: String, base64: String): String =
    url ?: "data:$mediaType;base64,$base64"

private fun openAIToolJson(tool: LanguageModelTool): JsonObject = buildJsonObject {
    put("type", JsonPrimitive("function"))
    put(
        "function",
        buildJsonObject {
            put("name", JsonPrimitive(tool.name))
            put("description", JsonPrimitive(tool.description))
            put("parameters", aiSdkJson.parseToJsonElement(tool.parametersSchemaJson))
            put("strict", JsonPrimitive(tool.strict))
        },
    )
    // Per-tool provider config (e.g. cache_control), merged at the top level.
    tool.providerOptions.forEach { (key, value) -> put(key, value) }
}

private fun openAIToolChoiceJson(choice: ToolChoice): JsonElement? = when (choice) {
    ToolChoice.Auto -> JsonPrimitive("auto")
    ToolChoice.None -> JsonPrimitive("none")
    ToolChoice.Required -> JsonPrimitive("required")
    is ToolChoice.Specific -> buildJsonObject {
        put("type", JsonPrimitive("function"))
        put("function", buildJsonObject { put("name", JsonPrimitive(choice.toolName)) })
    }
}

private fun openAIResponseFormat(format: ResponseFormat, strictJsonSchema: Boolean): JsonElement? = when (format) {
    ResponseFormat.Text -> null
    is ResponseFormat.Json -> {
        if (format.schemaJson != null) {
            buildJsonObject {
                put("type", JsonPrimitive("json_schema"))
                put(
                    "json_schema",
                    buildJsonObject {
                        put("name", JsonPrimitive(format.schemaName ?: "response"))
                        format.schemaDescription?.let { put("description", JsonPrimitive(it)) }
                        put("schema", format.schemaJson)
                        put("strict", JsonPrimitive(strictJsonSchema))
                    },
                )
            }
        } else {
            buildJsonObject { put("type", JsonPrimitive("json_object")) }
        }
    }
}

private fun openAICompletionPrompt(messages: List<ModelMessage>): String =
    messages.joinToString("\n") { message ->
        val role = message.role.name.lowercase()
        val content = message.content.joinToString("") { part ->
            when (part) {
                is ContentPart.Text -> part.text
                is ContentPart.Reasoning -> part.text
                is ContentPart.ToolResult -> openAIContentString(part.modelVisible)
                is ContentPart.ToolCall -> part.input.toString()
                else -> ""
            }
        }
        "$role: $content"
    }

private fun openAITextContent(value: JsonElement?): String = when (value) {
    null, JsonNull -> ""
    is JsonPrimitive -> value.contentOrNull.orEmpty()
    is JsonArray -> value.mapNotNull { item ->
        item.jsonObject.takeIf { it["type"]?.jsonPrimitive?.contentOrNull == "text" }
            ?.get("text")?.jsonPrimitive?.contentOrNull
    }.joinToString("")
    else -> value.toString()
}

private fun openAIContentString(value: JsonElement): String = when (value) {
    is JsonPrimitive -> value.contentOrNull ?: value.toString()
    else -> value.toString()
}

private fun openAIUsage(value: JsonElement?): Usage {
    val obj = value?.jsonObject ?: return Usage()
    val promptTokens = obj["prompt_tokens"]?.jsonPrimitive?.intOrNull ?: 0
    val completionTokens = obj["completion_tokens"]?.jsonPrimitive?.intOrNull ?: 0
    val cachedTokens = (obj["prompt_tokens_details"]?.jsonObject?.get("cached_tokens")?.jsonPrimitive?.intOrNull ?: 0)
        .coerceIn(0, promptTokens)
    val reasoningTokens = (obj["completion_tokens_details"]?.jsonObject?.get("reasoning_tokens")?.jsonPrimitive?.intOrNull ?: 0)
        .coerceAtLeast(0)
    val outputTotal = if (reasoningTokens > completionTokens) {
        completionTokens + reasoningTokens
    } else {
        completionTokens
    }
    return Usage(
        inputTokens = Usage.InputTokenBreakdown(
            total = promptTokens,
            noCache = promptTokens - cachedTokens,
            cacheRead = cachedTokens,
        ),
        outputTokens = Usage.OutputTokenBreakdown(
            total = outputTotal,
            text = outputTotal - reasoningTokens,
            reasoning = reasoningTokens,
        ),
        raw = value,
    )
}

private fun openAIFinishReason(value: String?): FinishReason = when (value) {
    "stop" -> FinishReason.Stop
    "length" -> FinishReason.Length
    "tool_calls", "function_call" -> FinishReason.ToolCalls
    "content_filter" -> FinishReason.ContentFilter
    else -> FinishReason.Other
}

private fun parseOpenAIToolInput(value: String?): JsonElement =
    if (value.isNullOrBlank()) {
        JsonObject(emptyMap())
    } else {
        runCatching { aiSdkJson.parseToJsonElement(value) }.getOrElse { JsonPrimitive(value) }
    }

private fun isParsableOpenAIJson(value: String): Boolean =
    value.isNotBlank() && runCatching { aiSdkJson.parseToJsonElement(value) }.isSuccess

private fun thoughtSignatureMetadata(value: JsonObject): Map<String, JsonElement>? {
    val signature = value["extra_content"]?.jsonObject
        ?.get("google")?.jsonObject
        ?.get("thought_signature")?.jsonPrimitive?.contentOrNull
    return signature?.let { mapOf("thoughtSignature" to JsonPrimitive(it)) }
}

private fun openAIProviderOptions(options: Map<String, JsonElement>, providerName: String): JsonObject {
    val keys = listOf("openai-compatible", "openaiCompatible", providerName, toOpenAICamelCase(providerName))
    var merged = JsonObject(emptyMap())
    for (key in keys.distinct()) {
        val obj = options[key] as? JsonObject ?: continue
        merged = mergeJsonObjects(merged, obj)
    }
    return merged
}

private fun JsonObjectBuilder.putProviderOptions(options: JsonObject, reserved: Set<String>) {
    for ((key, value) in options) {
        if (key !in reserved && value !is JsonNull) put(key, value)
    }
}

private fun openAIProviderMetadata(value: JsonElement?, providerName: String): Map<String, JsonElement> =
    when (value) {
        is JsonObject -> value.toMap()
        null -> emptyMap()
        else -> mapOf(providerName to value)
    }

private fun openAIFormValue(value: JsonElement): String = when (value) {
    is JsonPrimitive -> value.contentOrNull ?: value.toString()
    else -> value.toString()
}

private fun audioMediaType(format: String): String = when (format.lowercase()) {
    "wav" -> "audio/wav"
    "opus" -> "audio/ogg"
    "aac" -> "audio/aac"
    "flac" -> "audio/flac"
    else -> "audio/mpeg"
}

private fun openAICompatibleErrorMessage(statusCode: Int, parsed: JsonElement?, raw: String): String {
    val error = (parsed as? JsonObject)?.get("error")?.jsonObject
    val message = error?.get("message")?.jsonPrimitive?.contentOrNull ?: raw.ifBlank { "OpenAI-compatible request failed" }
    return "OpenAI-compatible request failed ($statusCode): $message"
}

private fun toOpenAICamelCase(value: String): String =
    value.split('-', '_', '.', ' ')
        .filter { it.isNotBlank() }
        .mapIndexed { index, part -> if (index == 0) part.lowercase() else part.replaceFirstChar { it.uppercase() } }
        .joinToString("")

