@file:OptIn(ai.torad.aisdk.LowLevelLanguageModelApi::class)

package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.request
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

internal const val OPENAI_COMPATIBLE_MAX_IMAGES_PER_CALL: Int = 10

internal val openAIChatReservedOptions = setOf(
    "user",
    "strictJsonSchema",
    "reasoningEffort",
    "textVerbosity",
)

internal val openAICompletionReservedOptions = setOf(
    "user",
)

internal val openAICompatibleImageEditReservedOptions = setOf(
    "model",
    "prompt",
    "image",
    "mask",
    "n",
    "size",
)

internal data class PreparedOpenAIRequest(
    val body: JsonObject,
    val warnings: List<CallWarning>,
)

internal class OpenAICompatibleChatLanguageModel(
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
        val transformed = applyChatResponseTransform(settings, response.value)
        openAICompatibleInBandError(transformed)?.let { error ->
            throw toApiCallError(
                error,
                url = url("/chat/completions"),
                requestBody = prepared.body,
                responseBody = response.rawText,
                responseHeaders = response.headers,
            )
        }
        return chatResultFromJson(
            transformed,
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
        val state = OpenAIChatStreamState(
            provider = providerName,
            providerKey = providerOptionsKey(),
            convertUsage = settings.convertUsage
        )
        var sseHeaders: Map<String, String> = emptyMap()
        val rawLines = postSse(
            path = "/chat/completions",
            body = prepared.body,
            headers = params.headers + mapOf(HttpHeaders.Accept to "text/event-stream"),
            onResponse = { sseHeaders = it },
        )
        with(HttpTransport) {
            forwardSseEvents(
                events = EventStreamParser.parse(
                    rawLines,
                    Schemas.jsonSchema<JsonElement>(JsonObject(emptyMap())),
                    json
                )
                    .map { it.transformChatStreamEvent() },
                capturedHeaders = { sseHeaders },
                parseErrorPrefix = "Failed to parse OpenAI-compatible stream event",
                onEvent = { state.accept(it).forEach { e -> emit(e) } },
            )
        }
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
        val options = providerOptions(params.providerOptions.toMap())
        val strictJsonSchema = (options["strictJsonSchema"] as? JsonPrimitive)?.booleanOrNull ?: true
        val body = buildJsonObject {
            put("model", JsonPrimitive(modelId))
            put("messages", JsonArray(params.messages.flatMap { openAIChatMessagesJson(it) }))
            params.maxOutputTokens?.let { put(settings.chatMaxOutputTokensKey, JsonPrimitive(it)) }
            params.temperature?.let { put("temperature", JsonPrimitive(it)) }
            params.topP?.let { put("top_p", JsonPrimitive(it)) }
            params.frequencyPenalty?.let { put("frequency_penalty", JsonPrimitive(it)) }
            params.presencePenalty?.let { put("presence_penalty", JsonPrimitive(it)) }
            if (params.stopSequences.isNotEmpty()) put("stop", JsonArray(params.stopSequences.map(::JsonPrimitive)))
            params.seed?.let { put(settings.chatSeedKey, JsonPrimitive(it)) }
            val responseFormat = openAIResponseFormat(
                params.responseFormat,
                ResponseFormatCapabilities(strictJsonSchema, settings.supportsStructuredOutputs),
            )
            if (responseFormat != null) put("response_format", responseFormat)
            if (params.tools.isNotEmpty()) {
                put("tools", JsonArray(params.tools.map { openAIToolJson(it) }))
                // tool_choice is only valid alongside tools — emitting "auto" on a tool-less
                // request makes strict OpenAI-compatible servers reject it.
                openAIToolChoiceJson(params.toolChoice)?.let { put("tool_choice", it) }
            }
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
            putProviderOptions(this, options, openAIChatReservedOptions)
        }
        return PreparedOpenAIRequest(settings.transformChatRequestBody?.invoke(body) ?: body, warnings)
    }

    private fun providerOptions(options: Map<String, JsonElement>): JsonObject =
        openAIProviderOptions(options, providerOptionsName())

    private fun providerOptionsKey(): String =
        providerOptionsName().substringBefore('.').trim().ifBlank { "openaiCompatible" }

    private fun providerOptionsName(): String = settings.providerOptionsName ?: settings.name

    private fun ParseResult<JsonElement>.transformChatStreamEvent(): ParseResult<JsonElement> = when (this) {
        is ParseResult.Failure -> this
        is ParseResult.Success -> ParseResult.Success(applyChatResponseTransform(settings, value))
    }
}

internal class OpenAICompatibleCompletionLanguageModel(
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
        return completionResultFromJson(
            response.value,
            providerName,
            prepared.body,
            response.headers,
            response.value,
            prepared.warnings
        )
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
        EventStreamParser.parse(
            rawLines,
            Schemas.jsonSchema<JsonElement>(JsonObject(emptyMap())),
            json
        ).collect { event ->
            if (!headerMetaEmitted) {
                emit(StreamEvent.ResponseMetadata(headers = sseHeaders))
                headerMetaEmitted = true
            }
            when (event) {
                is ParseResult.Failure -> emit(
                    StreamEvent.Error(
                        "Failed to parse OpenAI-compatible completion stream event: ${event.error.message}"
                    )
                )
                is ParseResult.Success -> {
                    val value = event.value.jsonObject
                    if (!emittedResponseMetadata) {
                        StreamEvent.ResponseMetadata.fromOpenAI(value)?.let {
                            emit(it)
                            emittedResponseMetadata = true
                        }
                    }
                    value["usage"]?.let { usage = Usage.fromOpenAI(it) }
                    val choice = ((JsonAccess.arr(value, "choices"))?.firstOrNull() as? JsonObject)
                    val text = (choice?.get("text") as? JsonPrimitive)?.contentOrNull
                    if (!text.isNullOrEmpty()) {
                        if (!activeText) {
                            emit(StreamEvent.TextStart("txt-0"))
                            activeText = true
                        }
                        emit(StreamEvent.TextDelta("txt-0", text))
                    }
                    (choice?.get("finish_reason") as? JsonPrimitive)?.contentOrNull?.let {
                        finish = FinishReason.fromOpenAI(it)
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
        val options = openAIProviderOptions(
            params.providerOptions.toMap(),
            settings.providerOptionsName ?: settings.name
        )
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
            putProviderOptions(this, options, openAICompletionReservedOptions)
        }
        return PreparedOpenAIRequest(body, warnings)
    }
}

internal class OpenAICompatibleEmbeddingModel(
    client: HttpClient,
    settings: OpenAICompatibleProviderSettings,
    json: Json,
    modelId: String,
) : OpenAICompatibleHttpModel(client, settings, json, modelId, "embedding"), EmbeddingModel {
    override val provider: String
        get() = providerName
    override val maxEmbeddingsPerCall: Int = settings.maxEmbeddingsPerCall
    override val supportsParallelCalls: Boolean = true

    override suspend fun embed(params: EmbeddingModelCallParams): EmbeddingModelResult {
        val max = params.maxEmbeddingsPerCall ?: settings.maxEmbeddingsPerCall
        if (params.values.size > max) {
            throw InvalidArgumentError(
                "values",
                "embedding model ${settings.name}:$modelId supports at most $max values per call"
            )
        }
        val options = openAIProviderOptions(
            params.providerOptions.toMap(),
            settings.providerOptionsName ?: settings.name
        )
        val body = buildJsonObject {
            put("model", JsonPrimitive(modelId))
            put("input", JsonArray(params.values.map(::JsonPrimitive)))
            put("encoding_format", JsonPrimitive("float"))
            options["dimensions"]?.let { put("dimensions", it) }
            options["user"]?.let { put("user", it) }
            putProviderOptions(this, options, setOf("dimensions", "user"))
        }
        val response = postJson("/embeddings", body, params.headers)
        val value = response.value.jsonObject
        return EmbeddingModelResult(
            embeddings = (JsonAccess.arr(value, "data")).orEmpty()
                .map { item ->
                    val row = JsonAccess.arr(item as? JsonObject, "embedding").orEmpty()
                    row.map { WireDecoder.embeddingFloat(it, provider) }
                },
            usage = EmbeddingUsage(
                tokens = (JsonAccess.obj(value, "usage")?.get("prompt_tokens") as? JsonPrimitive)?.intOrNull
                    ?: 0,
                raw = value["usage"],
            ),
            warnings = emptyList(),
            request = LanguageModelRequestMetadata(body),
            response = LanguageModelResponseMetadata(headers = response.headers, body = response.value),
            providerMetadata = openAIProviderMetadata(
                value["providerMetadata"],
                settings.name
            ).let { m -> if (m.isEmpty()) ProviderMetadata.None else ProviderMetadata.Raw(JsonObject(m)) },
        )
    }
}

internal class OpenAICompatibleImageModel(
    client: HttpClient,
    settings: OpenAICompatibleProviderSettings,
    json: Json,
    modelId: String,
) : OpenAICompatibleHttpModel(client, settings, json, modelId, "image"), ImageModel {
    override val provider: String
        get() = providerName
    override val maxImagesPerCall: Int = OPENAI_COMPATIBLE_MAX_IMAGES_PER_CALL

    override suspend fun generate(params: ImageGenerationParams): ImageModelResult {
        val warnings = mutableListOf<CallWarning>()
        if (params.aspectRatio != null) {
            warnings += CallWarning("unsupported", "aspectRatio is not supported by OpenAI-compatible image generation; use size")
        }
        if (params.seed != null) {
            warnings += CallWarning("unsupported", "seed is not supported by OpenAI-compatible image generation")
        }
        val options = openAIProviderOptions(
            params.providerOptions.toMap(),
            settings.providerOptionsName ?: settings.name
        )
        val response = openAICompatibleImageResponse(params, options)
        val responseObject = WireDecoder.objectValue(response.value, providerName, "image generation response")
        val data = WireDecoder.requiredArray(responseObject, "data", providerName, "image generation response")
        if (data.isEmpty()) throw NoImageGeneratedError("OpenAI-compatible image response contained no data.")
        return ImageModelResult(
            images = data.mapIndexed { index, image ->
                val obj = WireDecoder.objectValue(image, providerName, "image generation response", "$.data[$index]")
                val imageData = WireDecoder.requiredOneOfString(
                    obj,
                    providerName,
                    "image generation response",
                    "$.data[$index]",
                    "b64_json",
                    "url"
                )
                GeneratedFile(
                    mediaType = (obj["media_type"] as? JsonPrimitive)?.contentOrNull ?: "image/png",
                    base64 = obj["b64_json"]?.let { imageData }.orEmpty(),
                    url = (obj["url"] as? JsonPrimitive)?.contentOrNull,
                )
            },
            warnings = warnings,
            response = LanguageModelResponseMetadata(
                modelId = modelId,
                headers = response.headers,
                body = response.value
            ),
            providerMetadata = openAIProviderMetadata(
                responseObject["providerMetadata"],
                settings.name
            ).let { m -> if (m.isEmpty()) ProviderMetadata.None else ProviderMetadata.Raw(JsonObject(m)) },
            usage = ImageModelUsage.fromOpenAI(responseObject["usage"]),
        )
    }

    private suspend fun openAICompatibleImageResponse(
        params: ImageGenerationParams,
        options: JsonObject,
    ): HttpJsonResponse =
        if (params.files.isNotEmpty()) {
            postMultipart("/images/edits", openAICompatibleImageEditMultipart(params, options), params.headers)
        } else {
            postJson("/images/generations", openAICompatibleImageGenerationBody(params, options), params.headers)
        }

    private fun openAICompatibleImageGenerationBody(
        params: ImageGenerationParams,
        options: JsonObject,
    ): JsonObject = buildJsonObject {
        put("model", JsonPrimitive(modelId))
        put("prompt", JsonPrimitive(params.prompt))
        put("n", JsonPrimitive(params.n))
        params.size?.let { put("size", JsonPrimitive(it)) }
        put("response_format", JsonPrimitive("b64_json"))
        putProviderOptions(this, options, emptySet())
    }

    private suspend fun openAICompatibleImageEditMultipart(
        params: ImageGenerationParams,
        options: JsonObject,
    ): MultiPartFormDataContent = MultiPartFormDataContent(
        formData {
            append("model", modelId)
            append("prompt", params.prompt)
            append("n", params.n.toString())
            params.size?.let { append("size", it) }
            for ((key, value) in options) {
                if (key !in openAICompatibleImageEditReservedOptions) append(key, openAIFormValue(value))
            }
            params.files.forEachIndexed { index, file ->
                append("image", openAICompatibleImageFileBytes(file), openAICompatibleImageFileHeaders(file, index))
            }
            params.mask?.let { mask ->
                append("mask", openAICompatibleImageFileBytes(mask), openAICompatibleImageFileHeaders(mask, 0))
            }
        },
    )

    private suspend fun openAICompatibleImageFileBytes(file: ImageGenerationFile): ByteArray = when {
        file.base64 != null -> Base64Codec.decode(file.base64)
        file.url != null -> client.request(file.url).bodyAsBytes()
        else -> throw InvalidArgumentError("files", "OpenAI-compatible image edits require file data or URL.")
    }

    private fun openAICompatibleImageFileHeaders(file: ImageGenerationFile, index: Int): Headers =
        Headers.build {
            val mediaType = file.mediaType ?: "image/png"
            append(HttpHeaders.ContentType, mediaType)
            append(
                HttpHeaders.ContentDisposition,
                "filename=\"${file.filename ?: "image-$index.${MediaTypes.toExtension(mediaType)}"}\"",
            )
        }
}

internal class OpenAICompatibleSpeechModel(
    client: HttpClient,
    settings: OpenAICompatibleProviderSettings,
    json: Json,
    modelId: String,
) : OpenAICompatibleHttpModel(client, settings, json, modelId, "speech"), SpeechModel {
    override val provider: String
        get() = providerName

    override suspend fun generate(params: SpeechGenerationParams): SpeechModelResult {
        val options = openAIProviderOptions(
            params.providerOptions.toMap(),
            settings.providerOptionsName ?: settings.name
        )
        val format = params.responseFormat ?: (options["response_format"] as? JsonPrimitive)?.contentOrNull ?: "mp3"
        val body = buildJsonObject {
            put("model", JsonPrimitive(modelId))
            put("input", JsonPrimitive(params.text))
            params.voice?.let { put("voice", JsonPrimitive(it)) }
            params.instructions?.let { put("instructions", JsonPrimitive(it)) }
            params.speed?.let { put("speed", JsonPrimitive(it)) }
            params.language?.let { put("language", JsonPrimitive(it)) }
            put("response_format", JsonPrimitive(format))
            putProviderOptions(this, options, setOf("response_format"))
        }
        val response = postBytes("/audio/speech", body, params.headers)
        return SpeechModelResult(
            audio = GeneratedFile(
                mediaType = with(FacadeHttp) { response.headers.headerValue(HttpHeaders.ContentType) }
                    ?: audioMediaType(format),
                base64 = Base64Codec.encode(response.bytes),
            ),
            response = LanguageModelResponseMetadata(modelId = modelId, headers = response.headers),
        )
    }
}

internal class OpenAICompatibleTranscriptionModel(
    client: HttpClient,
    settings: OpenAICompatibleProviderSettings,
    json: Json,
    modelId: String,
) : OpenAICompatibleHttpModel(client, settings, json, modelId, "transcription"), TranscriptionModel {
    override val provider: String
        get() = providerName

    override suspend fun transcribe(params: TranscriptionParams): TranscriptionModelResult {
        val options = openAIProviderOptions(
            params.providerOptions.toMap(),
            settings.providerOptionsName ?: settings.name
        )
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
                    Base64Codec.decode(params.audio.base64),
                    Headers.build {
                        append(HttpHeaders.ContentType, params.audio.mediaType)
                        append(
                            HttpHeaders.ContentDisposition,
                            "filename=\"${params.audio.filename ?: "audio.${MediaTypes.toExtension(params.audio.mediaType)}"}\"",
                        )
                    },
                )
            },
        )
        val response = postMultipart("/audio/transcriptions", multipart, params.headers)
        val value = WireDecoder.objectValue(response.value, providerName, "transcription response")
        return TranscriptionModelResult(
            text = WireDecoder.requiredString(value, "text", providerName, "transcription response"),
            segments = WireDecoder.optionalArray(
                value,
                "segments",
                providerName,
                "transcription response"
            ).orEmpty().mapIndexed { index, segment ->
                val obj = WireDecoder.objectValue(segment, providerName, "transcription response", "$.segments[$index]")
                TranscriptSegment(
                    text = WireDecoder.requiredString(
                        obj,
                        "text",
                        providerName,
                        "transcription response",
                        "$.segments[$index]"
                    ),
                    startSeconds = WireDecoder.optionalFloat(
                        obj,
                        "start",
                        providerName,
                        "transcription response",
                        "$.segments[$index]"
                    ),
                    endSeconds = WireDecoder.optionalFloat(
                        obj,
                        "end",
                        providerName,
                        "transcription response",
                        "$.segments[$index]"
                    ),
                )
            },
            response = LanguageModelResponseMetadata(
                modelId = modelId,
                headers = response.headers,
                body = response.value
            ),
            providerMetadata = openAIProviderMetadata(
                value["providerMetadata"],
                settings.name
            ).let { m -> if (m.isEmpty()) ProviderMetadata.None else ProviderMetadata.Raw(JsonObject(m)) },
            // verbose_json responses carry the detected language + audio duration.
            language = WireDecoder.optionalString(value, "language", providerName, "transcription response"),
            durationInSeconds = WireDecoder.optionalFloat(value, "duration", providerName, "transcription response"),
        )
    }
}
