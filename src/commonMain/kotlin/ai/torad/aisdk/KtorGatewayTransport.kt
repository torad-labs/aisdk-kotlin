package ai.torad.aisdk

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.headers
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
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
import kotlinx.serialization.json.longOrNull

fun createGatewayHttpProvider(
    client: HttpClient,
    settings: GatewayProviderSettings = GatewayProviderSettings(),
    json: Json = gatewayJson,
): GatewayProvider = createGatewayProvider(
    settings.copy(transport = KtorGatewayTransport(client, json)),
)

class KtorGatewayTransport(
    private val client: HttpClient,
    private val json: Json = gatewayJson,
) : GatewayTransport {
    override suspend fun generateText(
        context: GatewayRequestContext,
        modelId: GatewayModelId,
        params: LanguageModelCallParams,
    ): LanguageModelResult {
        val body = languageModelRequestBody(params)
        val response = postJson(
            context = context,
            path = "/language-model",
            body = body,
            headers = languageModelHeaders(modelId, streaming = false),
        )
        return languageModelResultFromJson(
            value = response.value,
            requestBody = body,
            responseHeaders = response.headers,
            responseBody = response.value,
        )
    }

    override fun streamText(
        context: GatewayRequestContext,
        modelId: GatewayModelId,
        params: LanguageModelCallParams,
    ): Flow<StreamEvent> = flow {
        val body = languageModelRequestBody(params)
        val response = postJson(
            context = context,
            path = "/language-model",
            body = body,
            headers = languageModelHeaders(modelId, streaming = true) + mapOf(HttpHeaders.Accept to "text/event-stream"),
            parseJson = false,
        )
        val events = parseJsonEventStream(response.rawText, jsonSchema<JsonElement>(JsonObject(emptyMap())), json)
        for (event in events) {
            when (event) {
                is ParseResult.Success -> emit(streamEventFromJson(event.value))
                is ParseResult.Failure -> throw GatewayResponseError(
                    message = "Failed to parse gateway stream event: ${event.error.message}",
                    response = JsonPrimitive(event.text),
                    cause = event.error,
                )
            }
        }
    }

    override suspend fun embed(
        context: GatewayRequestContext,
        modelId: GatewayEmbeddingModelId,
        params: EmbeddingModelCallParams,
    ): EmbeddingModelResult {
        val body = buildJsonObject {
            put("values", JsonArray(params.values.map(::JsonPrimitive)))
            if (params.providerOptions.isNotEmpty()) put("providerOptions", JsonObject(params.providerOptions))
        }
        val response = postJson(
            context = context,
            path = "/embedding-model",
            body = body,
            headers = mapOf(
                "ai-embedding-model-specification-version" to "3",
                "ai-model-id" to modelId,
            ) + params.headers,
        )
        val value = response.value.jsonObject
        return EmbeddingModelResult(
            embeddings = value["embeddings"]?.jsonArray.orEmpty().map { row ->
                row.jsonArray.map { it.jsonPrimitive.floatOrNull ?: 0f }
            },
            usage = EmbeddingUsage(tokens = value["usage"]?.jsonObject?.get("tokens")?.jsonPrimitive?.intOrNull ?: 0),
            response = LanguageModelResponseMetadata(headers = response.headers, body = response.value),
            providerMetadata = jsonObjectMap(value["providerMetadata"]),
        )
    }

    override suspend fun generateImage(
        context: GatewayRequestContext,
        modelId: GatewayImageModelId,
        params: ImageGenerationParams,
    ): ImageModelResult {
        val body = buildJsonObject {
            put("prompt", JsonPrimitive(params.prompt))
            put("n", JsonPrimitive(params.n))
            params.size?.let { put("size", JsonPrimitive(it)) }
            params.aspectRatio?.let { put("aspectRatio", JsonPrimitive(it)) }
            if (params.providerOptions.isNotEmpty()) put("providerOptions", JsonObject(params.providerOptions))
        }
        val response = postJson(
            context = context,
            path = "/image-model",
            body = body,
            headers = mapOf(
                "ai-image-model-specification-version" to "3",
                "ai-model-id" to modelId,
            ) + params.headers,
        )
        val value = response.value.jsonObject
        return ImageModelResult(
            images = value["images"]?.jsonArray.orEmpty().map {
                GeneratedFile(mediaType = "image/png", base64 = it.jsonPrimitive.content)
            },
            warnings = callWarnings(value["warnings"]),
            response = LanguageModelResponseMetadata(modelId = modelId, headers = response.headers, body = response.value),
            providerMetadata = jsonObjectMap(value["providerMetadata"]),
        )
    }

    override suspend fun generateVideo(
        context: GatewayRequestContext,
        modelId: GatewayVideoModelId,
        params: VideoGenerationParams,
    ): VideoModelResult {
        val body = buildJsonObject {
            put("prompt", JsonPrimitive(params.prompt))
            put("n", JsonPrimitive(params.n))
            params.aspectRatio?.let { put("aspectRatio", JsonPrimitive(it)) }
            params.durationSeconds?.let { put("duration", JsonPrimitive(it)) }
            params.seed?.let { put("seed", JsonPrimitive(it)) }
            params.fps?.let { put("fps", JsonPrimitive(it)) }
            params.resolution?.let { put("resolution", JsonPrimitive(it)) }
            params.size?.let { put("size", JsonPrimitive(it)) }
            if (params.providerOptions.isNotEmpty()) put("providerOptions", JsonObject(params.providerOptions))
            params.image?.let { image ->
                put(
                    "image",
                    buildJsonObject {
                        if (image.url != null) {
                            put("type", JsonPrimitive("url"))
                            put("url", JsonPrimitive(image.url))
                        } else {
                            put("type", JsonPrimitive("file"))
                            put("mediaType", JsonPrimitive(image.mediaType))
                            put("data", JsonPrimitive(image.base64))
                        }
                    },
                )
            }
        }
        val response = postJson(
            context = context,
            path = "/video-model",
            body = body,
            headers = mapOf(
                "ai-video-model-specification-version" to "3",
                "ai-model-id" to modelId,
                HttpHeaders.Accept to "text/event-stream",
            ) + params.headers,
            parseJson = false,
        )
        val event = parseJsonEventStream(response.rawText, jsonSchema<JsonElement>(JsonObject(emptyMap())), json)
            .firstNotNullOfOrNull { result ->
                when (result) {
                    is ParseResult.Success -> result.value.jsonObject
                    is ParseResult.Failure -> throw GatewayResponseError(
                        message = "Failed to parse gateway video event: ${result.error.message}",
                        response = JsonPrimitive(result.text),
                        cause = result.error,
                    )
                }
            } ?: throw GatewayResponseError("SSE stream ended without a data event")
        if (event["type"]?.jsonPrimitive?.contentOrNull == "error") {
            throw GatewayResponseError(
                message = event["message"]?.jsonPrimitive?.contentOrNull ?: "Gateway video generation failed",
                response = JsonObject(event),
            )
        }
        return VideoModelResult(
            videos = event["videos"]?.jsonArray.orEmpty().map { video ->
                val obj = video.jsonObject
                GeneratedFile(
                    mediaType = obj["mediaType"]?.jsonPrimitive?.contentOrNull ?: "video/mp4",
                    base64 = obj["data"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    url = obj["url"]?.jsonPrimitive?.contentOrNull,
                )
            },
            warnings = callWarnings(event["warnings"]),
            response = LanguageModelResponseMetadata(modelId = modelId, headers = response.headers),
            providerMetadata = jsonObjectMap(event["providerMetadata"]),
        )
    }

    override suspend fun rerank(
        context: GatewayRequestContext,
        modelId: GatewayRerankingModelId,
        params: RerankingParams,
    ): RerankingModelResult {
        val body = buildJsonObject {
            put("documents", JsonArray(params.documents.map(::JsonPrimitive)))
            put("query", JsonPrimitive(params.query))
            params.topN?.let { put("topN", JsonPrimitive(it)) }
            if (params.providerOptions.isNotEmpty()) put("providerOptions", JsonObject(params.providerOptions))
        }
        val response = postJson(
            context = context,
            path = "/reranking-model",
            body = body,
            headers = mapOf(
                "ai-reranking-model-specification-version" to "3",
                "ai-model-id" to modelId,
            ) + params.headers,
        )
        val value = response.value.jsonObject
        return RerankingModelResult(
            results = value["ranking"]?.jsonArray.orEmpty().map { item ->
                val obj = item.jsonObject
                val index = obj["index"]?.jsonPrimitive?.intOrNull ?: 0
                RerankedItem(
                    value = params.documents.getOrElse(index) { "" },
                    score = obj["relevanceScore"]?.jsonPrimitive?.floatOrNull ?: 0f,
                    index = index,
                )
            },
            response = LanguageModelResponseMetadata(headers = response.headers, body = response.value),
            providerMetadata = jsonObjectMap(value["providerMetadata"]),
        )
    }

    override suspend fun getAvailableModels(context: GatewayRequestContext): GatewayFetchMetadataResponse {
        val response = getJson(context, "/config")
        val models = response.value.jsonObject["models"]?.jsonArray.orEmpty().mapNotNull { model ->
            val obj = model.jsonObject
            val spec = obj["specification"]?.jsonObject ?: return@mapNotNull null
            GatewayLanguageModelEntry(
                id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                name = obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                description = obj["description"]?.jsonPrimitive?.contentOrNull,
                pricing = obj["pricing"]?.jsonObject?.let { pricing ->
                    GatewayPricing(
                        input = pricing["input"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        output = pricing["output"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        cachedInputTokens = pricing["cachedInputTokens"]?.jsonPrimitive?.contentOrNull
                            ?: pricing["input_cache_read"]?.jsonPrimitive?.contentOrNull,
                        cacheCreationInputTokens = pricing["cacheCreationInputTokens"]?.jsonPrimitive?.contentOrNull
                            ?: pricing["input_cache_write"]?.jsonPrimitive?.contentOrNull,
                    )
                },
                specification = GatewayLanguageModelSpecification(
                    specificationVersion = spec["specificationVersion"]?.jsonPrimitive?.contentOrNull ?: "v3",
                    provider = spec["provider"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    modelId = spec["modelId"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                ),
                modelType = gatewayModelType(obj["modelType"]?.jsonPrimitive?.contentOrNull),
            )
        }
        return GatewayFetchMetadataResponse(models)
    }

    override suspend fun getCredits(context: GatewayRequestContext): GatewayCreditsResponse {
        val response = getJson(context.copy(baseUrl = gatewayOrigin(context.baseUrl)), "/v1/credits")
        val obj = response.value.jsonObject
        return GatewayCreditsResponse(
            balance = obj["balance"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            totalUsed = obj["total_used"]?.jsonPrimitive?.contentOrNull
                ?: obj["totalUsed"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        )
    }

    override suspend fun getSpendReport(
        context: GatewayRequestContext,
        params: GatewaySpendReportParams,
    ): GatewaySpendReportResponse {
        val query = buildList {
            add("start_date=${urlEncode(params.startDate)}")
            add("end_date=${urlEncode(params.endDate)}")
            params.groupBy?.let { add("group_by=${urlEncode(it.wireValue)}") }
            params.datePart?.let { add("date_part=${urlEncode(it.wireValue)}") }
            params.userId?.let { add("user_id=${urlEncode(it)}") }
            params.model?.let { add("model=${urlEncode(it)}") }
            params.provider?.let { add("provider=${urlEncode(it)}") }
            params.credentialType?.let { add("credential_type=${urlEncode(it.wireValue)}") }
            if (params.tags.isNotEmpty()) add("tags=${urlEncode(params.tags.joinToString(","))}")
        }.joinToString("&")
        val response = getJson(context.copy(baseUrl = gatewayOrigin(context.baseUrl)), "/v1/report?$query")
        return GatewaySpendReportResponse(
            results = response.value.jsonObject["results"]?.jsonArray.orEmpty().map { row ->
                val obj = row.jsonObject
                GatewaySpendReportRow(
                    day = obj["day"]?.jsonPrimitive?.contentOrNull,
                    hour = obj["hour"]?.jsonPrimitive?.contentOrNull,
                    user = obj["user"]?.jsonPrimitive?.contentOrNull,
                    model = obj["model"]?.jsonPrimitive?.contentOrNull,
                    tag = obj["tag"]?.jsonPrimitive?.contentOrNull,
                    provider = obj["provider"]?.jsonPrimitive?.contentOrNull,
                    credentialType = gatewayCredentialType(
                        obj["credential_type"]?.jsonPrimitive?.contentOrNull
                            ?: obj["credentialType"]?.jsonPrimitive?.contentOrNull,
                    ),
                    totalCost = jsonNumber(obj, "total_cost", "totalCost"),
                    marketCost = jsonNumberOrNull(obj, "market_cost", "marketCost"),
                    inputTokens = jsonIntOrNull(obj, "input_tokens", "inputTokens"),
                    outputTokens = jsonIntOrNull(obj, "output_tokens", "outputTokens"),
                    cachedInputTokens = jsonIntOrNull(obj, "cached_input_tokens", "cachedInputTokens"),
                    cacheCreationInputTokens = jsonIntOrNull(obj, "cache_creation_input_tokens", "cacheCreationInputTokens"),
                    reasoningTokens = jsonIntOrNull(obj, "reasoning_tokens", "reasoningTokens"),
                    requestCount = jsonIntOrNull(obj, "request_count", "requestCount"),
                )
            },
        )
    }

    override suspend fun getGenerationInfo(
        context: GatewayRequestContext,
        params: GatewayGenerationInfoParams,
    ): GatewayGenerationInfo {
        val response = getJson(
            context.copy(baseUrl = gatewayOrigin(context.baseUrl)),
            "/v1/generation?id=${urlEncode(params.id)}",
        )
        val data = response.value.jsonObject["data"]?.jsonObject ?: response.value.jsonObject
        return GatewayGenerationInfo(
            id = data["id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            totalCost = jsonNumber(data, "total_cost", "totalCost"),
            upstreamInferenceCost = jsonNumber(data, "upstream_inference_cost", "upstreamInferenceCost"),
            usage = jsonNumber(data, "usage"),
            createdAt = data["created_at"]?.jsonPrimitive?.contentOrNull
                ?: data["createdAt"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            model = data["model"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            isByok = data["is_byok"]?.jsonPrimitive?.booleanOrNull
                ?: data["isByok"]?.jsonPrimitive?.booleanOrNull ?: false,
            providerName = data["provider_name"]?.jsonPrimitive?.contentOrNull
                ?: data["providerName"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            streamed = data["streamed"]?.jsonPrimitive?.booleanOrNull ?: false,
            finishReason = data["finish_reason"]?.jsonPrimitive?.contentOrNull
                ?: data["finishReason"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            latency = jsonInt(data, "latency"),
            generationTime = jsonInt(data, "generation_time", "generationTime"),
            promptTokens = jsonInt(data, "native_tokens_prompt", "promptTokens"),
            completionTokens = jsonInt(data, "native_tokens_completion", "completionTokens"),
            reasoningTokens = jsonInt(data, "native_tokens_reasoning", "reasoningTokens"),
            cachedTokens = jsonInt(data, "native_tokens_cached", "cachedTokens"),
            cacheCreationTokens = jsonInt(data, "native_tokens_cache_creation", "cacheCreationTokens"),
            billableWebSearchCalls = jsonInt(data, "billable_web_search_calls", "billableWebSearchCalls"),
        )
    }

    private suspend fun postJson(
        context: GatewayRequestContext,
        path: String,
        body: JsonElement,
        headers: Map<String, String> = emptyMap(),
        parseJson: Boolean = true,
    ): GatewayHttpJsonResponse {
        val response = client.request(context.baseUrl.trimEnd('/') + path) {
            method = HttpMethod.Post
            contentType(ContentType.Application.Json)
            context.headers.forEach { (name, value) -> header(name, value) }
            headers.forEach { (name, value) -> header(name, value) }
            setBody(json.encodeToString(JsonElement.serializer(), body))
        }
        return parseResponse(response, parseJson)
    }

    private suspend fun getJson(
        context: GatewayRequestContext,
        path: String,
    ): GatewayHttpJsonResponse {
        val response = client.request(context.baseUrl.trimEnd('/') + path) {
            method = HttpMethod.Get
            headers {
                context.headers.forEach { (name, value) -> append(name, value) }
            }
        }
        return parseResponse(response, parseJson = true)
    }

    private suspend fun parseResponse(response: HttpResponse, parseJson: Boolean): GatewayHttpJsonResponse {
        val raw = response.bodyAsText()
        val headers = response.headers.entries().associate { it.key to it.value.joinToString(",") }
        if (response.status.value !in 200..299) {
            throw gatewayErrorFromResponse(response.status.value, raw)
        }
        return GatewayHttpJsonResponse(
            value = if (parseJson && raw.isNotBlank()) json.parseToJsonElement(raw) else JsonObject(emptyMap()),
            rawText = raw,
            headers = headers,
        )
    }

    private fun languageModelRequestBody(params: LanguageModelCallParams): JsonObject = buildJsonObject {
        put("prompt", JsonArray(params.messages.map(::modelMessageJson)))
        if (params.tools.isNotEmpty()) {
            put("tools", JsonArray(params.tools.map(::languageModelToolJson)))
        }
        put("toolChoice", toolChoiceJson(params.toolChoice))
        params.temperature?.let { put("temperature", JsonPrimitive(it)) }
        params.topP?.let { put("topP", JsonPrimitive(it)) }
        params.topK?.let { put("topK", JsonPrimitive(it)) }
        params.maxOutputTokens?.let { put("maxOutputTokens", JsonPrimitive(it)) }
        if (params.stopSequences.isNotEmpty()) {
            put("stopSequences", JsonArray(params.stopSequences.map(::JsonPrimitive)))
        }
        params.seed?.let { put("seed", JsonPrimitive(it)) }
        params.presencePenalty?.let { put("presencePenalty", JsonPrimitive(it)) }
        params.frequencyPenalty?.let { put("frequencyPenalty", JsonPrimitive(it)) }
        if (params.providerOptions.isNotEmpty()) put("providerOptions", JsonObject(params.providerOptions))
        put("responseFormat", responseFormatJson(params.responseFormat))
    }

    private fun languageModelHeaders(modelId: String, streaming: Boolean): Map<String, String> =
        mapOf(
            "ai-language-model-specification-version" to "3",
            "ai-language-model-id" to modelId,
            "ai-language-model-streaming" to streaming.toString(),
        )

    private fun languageModelResultFromJson(
        value: JsonElement,
        requestBody: JsonElement,
        responseHeaders: Map<String, String>,
        responseBody: JsonElement,
    ): LanguageModelResult {
        val obj = value.jsonObject
        val content = contentParts(obj["content"])
        val text = obj["text"]?.jsonPrimitive?.contentOrNull
            ?: content.filterIsInstance<ContentPart.Text>().joinToString("") { it.text }
        return LanguageModelResult(
            text = text,
            toolCalls = content.filterIsInstance<ContentPart.ToolCall>(),
            finishReason = finishReason(obj["finishReason"]?.jsonPrimitive?.contentOrNull),
            usage = usageFromJson(obj["usage"]),
            providerMetadata = jsonObjectMap(obj["providerMetadata"]),
            content = content.ifEmpty { if (text.isNotEmpty()) listOf(ContentPart.Text(text)) else emptyList() },
            rawFinishReason = obj["finishReason"]?.jsonPrimitive?.contentOrNull,
            warnings = callWarnings(obj["warnings"]),
            request = LanguageModelRequestMetadata(body = requestBody),
            response = LanguageModelResponseMetadata(headers = responseHeaders, body = responseBody),
        )
    }
}

private data class GatewayHttpJsonResponse(
    val value: JsonElement,
    val rawText: String,
    val headers: Map<String, String>,
)

private val gatewayJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
}

private fun modelMessageJson(message: ModelMessage): JsonObject = buildJsonObject {
    put("role", JsonPrimitive(message.role.name.lowercase()))
    put("content", JsonArray(message.content.map(::contentPartJson)))
}

private fun contentPartJson(part: ContentPart): JsonObject = buildJsonObject {
    when (part) {
        is ContentPart.Text -> {
            put("type", JsonPrimitive("text"))
            put("text", JsonPrimitive(part.text))
        }
        is ContentPart.Reasoning -> {
            put("type", JsonPrimitive("reasoning"))
            put("text", JsonPrimitive(part.text))
        }
        is ContentPart.ToolCall -> {
            put("type", JsonPrimitive("tool-call"))
            put("toolCallId", JsonPrimitive(part.toolCallId))
            put("toolName", JsonPrimitive(part.toolName))
            put("input", part.input)
        }
        is ContentPart.ToolResult -> {
            put("type", JsonPrimitive("tool-result"))
            put("toolCallId", JsonPrimitive(part.toolCallId))
            put("toolName", JsonPrimitive(part.toolName))
            put("output", part.modelVisible)
            put("isError", JsonPrimitive(part.isError))
        }
        is ContentPart.ToolApprovalRequest -> {
            put("type", JsonPrimitive("tool-approval-request"))
            put("toolCallId", JsonPrimitive(part.toolCallId))
            put("toolName", JsonPrimitive(part.toolName))
            put("input", part.input)
            part.approvalId?.let { put("approvalId", JsonPrimitive(it)) }
        }
        is ContentPart.ToolApprovalResponse -> {
            put("type", JsonPrimitive("tool-approval-response"))
            put("toolCallId", JsonPrimitive(part.toolCallId))
            put("approved", JsonPrimitive(part.approved))
            part.reason?.let { put("reason", JsonPrimitive(it)) }
            part.approvalId?.let { put("approvalId", JsonPrimitive(it)) }
        }
        is ContentPart.Source -> {
            put("type", JsonPrimitive(if (part.sourceType == StreamEvent.SourcePart.SourceType.Url) "source-url" else "source-document"))
            part.url?.let { put("url", JsonPrimitive(it)) }
            part.title?.let { put("title", JsonPrimitive(it)) }
        }
        is ContentPart.File -> {
            put("type", JsonPrimitive("file"))
            put("mediaType", JsonPrimitive(part.mediaType))
            put("data", JsonPrimitive(part.base64))
            part.filename?.let { put("filename", JsonPrimitive(it)) }
        }
        is ContentPart.Image -> {
            put("type", JsonPrimitive("file"))
            put("mediaType", JsonPrimitive(part.mediaType))
            put("data", JsonPrimitive(part.base64))
        }
    }
    providerMetadata(part)?.let { put("providerMetadata", JsonObject(it)) }
}

private fun providerMetadata(part: ContentPart): Map<String, JsonElement>? = when (part) {
    is ContentPart.Text -> part.providerMetadata
    is ContentPart.Reasoning -> part.providerMetadata
    is ContentPart.ToolCall -> part.providerMetadata
    is ContentPart.ToolResult -> part.providerMetadata
    is ContentPart.ToolApprovalRequest -> part.providerMetadata
    is ContentPart.Source -> part.providerMetadata
    is ContentPart.File -> part.providerMetadata
    is ContentPart.Image -> part.providerMetadata
    is ContentPart.ToolApprovalResponse -> null
}

private fun languageModelToolJson(tool: LanguageModelTool): JsonObject = buildJsonObject {
    put("name", JsonPrimitive(tool.name))
    put("description", JsonPrimitive(tool.description))
    put("parameters", gatewayJson.parseToJsonElement(tool.parametersSchemaJson))
    if (tool.providerExecuted) put("providerExecuted", JsonPrimitive(true))
}

private fun toolChoiceJson(choice: ToolChoice): JsonElement = when (choice) {
    ToolChoice.Auto -> JsonPrimitive("auto")
    ToolChoice.None -> JsonPrimitive("none")
    ToolChoice.Required -> JsonPrimitive("required")
    is ToolChoice.Specific -> buildJsonObject {
        put("type", JsonPrimitive("tool"))
        put("toolName", JsonPrimitive(choice.toolName))
    }
}

private fun responseFormatJson(format: ResponseFormat): JsonElement = when (format) {
    ResponseFormat.Text -> buildJsonObject { put("type", JsonPrimitive("text")) }
    is ResponseFormat.Json -> buildJsonObject {
        put("type", JsonPrimitive("json"))
        format.schemaJson?.let { put("schema", it) }
        format.schemaName?.let { put("name", JsonPrimitive(it)) }
        format.schemaDescription?.let { put("description", JsonPrimitive(it)) }
    }
}

private fun contentParts(value: JsonElement?): List<ContentPart> =
    (value as? JsonArray).orEmpty().mapNotNull { contentPartFromJson(it) }

private fun contentPartFromJson(value: JsonElement): ContentPart? {
    val obj = value.jsonObject
    return when (obj["type"]?.jsonPrimitive?.contentOrNull) {
        "text" -> ContentPart.Text(
            text = obj["text"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            providerMetadata = jsonObjectMapOrNull(obj["providerMetadata"]),
        )
        "reasoning" -> ContentPart.Reasoning(
            text = obj["text"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            providerMetadata = jsonObjectMapOrNull(obj["providerMetadata"]),
        )
        "tool-call" -> ContentPart.ToolCall(
            toolCallId = obj["toolCallId"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            toolName = obj["toolName"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            input = obj["input"] ?: JsonObject(emptyMap()),
            providerMetadata = jsonObjectMapOrNull(obj["providerMetadata"]),
        )
        "tool-result" -> ContentPart.ToolResult(
            toolCallId = obj["toolCallId"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            toolName = obj["toolName"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            output = obj["output"] ?: JsonNull,
            isError = obj["isError"]?.jsonPrimitive?.booleanOrNull ?: false,
            providerMetadata = jsonObjectMapOrNull(obj["providerMetadata"]),
        )
        "source-url" -> ContentPart.Source(
            sourceType = StreamEvent.SourcePart.SourceType.Url,
            url = obj["url"]?.jsonPrimitive?.contentOrNull,
            title = obj["title"]?.jsonPrimitive?.contentOrNull,
            providerMetadata = jsonObjectMapOrNull(obj["providerMetadata"]),
        )
        "file" -> ContentPart.File(
            mediaType = obj["mediaType"]?.jsonPrimitive?.contentOrNull ?: "application/octet-stream",
            base64 = obj["data"]?.jsonPrimitive?.contentOrNull
                ?: obj["base64"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            filename = obj["filename"]?.jsonPrimitive?.contentOrNull,
            providerMetadata = jsonObjectMapOrNull(obj["providerMetadata"]),
        )
        else -> null
    }
}

private fun streamEventFromJson(value: JsonElement): StreamEvent {
    val obj = value.jsonObject
    return when (val type = obj["type"]?.jsonPrimitive?.contentOrNull) {
        "stream-start" -> StreamEvent.StreamStart(callWarnings(obj["warnings"]))
        "response-metadata" -> StreamEvent.ResponseMetadata(
            id = obj["id"]?.jsonPrimitive?.contentOrNull,
            timestampMillis = obj["timestampMillis"]?.jsonPrimitive?.longOrNull
                ?: obj["timestamp"]?.jsonPrimitive?.doubleOrNull?.let { (it * 1000).toLong() },
            modelId = obj["modelId"]?.jsonPrimitive?.contentOrNull,
            headers = (obj["headers"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.content }.orEmpty(),
            body = obj["body"],
        )
        "text-start" -> StreamEvent.TextStart(obj["id"]?.jsonPrimitive?.contentOrNull ?: "text")
        "text-delta" -> StreamEvent.TextDelta(
            id = obj["id"]?.jsonPrimitive?.contentOrNull ?: "text",
            text = obj["delta"]?.jsonPrimitive?.contentOrNull
                ?: obj["text"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        )
        "text-end" -> StreamEvent.TextEnd(obj["id"]?.jsonPrimitive?.contentOrNull ?: "text")
        "reasoning-start" -> StreamEvent.ReasoningStart(obj["id"]?.jsonPrimitive?.contentOrNull ?: "reasoning")
        "reasoning-delta" -> StreamEvent.ReasoningDelta(
            id = obj["id"]?.jsonPrimitive?.contentOrNull ?: "reasoning",
            text = obj["delta"]?.jsonPrimitive?.contentOrNull
                ?: obj["text"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        )
        "reasoning-end" -> StreamEvent.ReasoningEnd(obj["id"]?.jsonPrimitive?.contentOrNull ?: "reasoning")
        "tool-input-start" -> StreamEvent.ToolInputStart(
            id = obj["id"]?.jsonPrimitive?.contentOrNull ?: obj["toolCallId"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            toolName = obj["toolName"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        )
        "tool-input-delta" -> StreamEvent.ToolInputDelta(
            id = obj["id"]?.jsonPrimitive?.contentOrNull ?: obj["toolCallId"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            delta = obj["delta"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        )
        "tool-input-end" -> StreamEvent.ToolInputEnd(
            id = obj["id"]?.jsonPrimitive?.contentOrNull ?: obj["toolCallId"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        )
        "tool-call" -> StreamEvent.ToolCall(
            toolCallId = obj["toolCallId"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            toolName = obj["toolName"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            inputJson = obj["input"] ?: JsonObject(emptyMap()),
        )
        "tool-result" -> {
            val output = toolResultOutputFromWire(obj["output"] ?: JsonNull)
            StreamEvent.ToolResult(
                toolCallId = obj["toolCallId"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                toolName = obj["toolName"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                outputJson = output.toJsonElement(),
                output = output,
                modelOutput = output,
                isError = output.isToolResultError(),
            )
        }
        "finish-step" -> StreamEvent.StepFinish(
            stepNumber = obj["stepNumber"]?.jsonPrimitive?.intOrNull ?: 1,
            finishReason = finishReason(obj["finishReason"]?.jsonPrimitive?.contentOrNull),
            usage = usageFromJson(obj["usage"]),
        )
        "finish" -> StreamEvent.Finish(
            totalSteps = obj["totalSteps"]?.jsonPrimitive?.intOrNull ?: 1,
            finishReason = finishReason(obj["finishReason"]?.jsonPrimitive?.contentOrNull),
            usage = usageFromJson(obj["usage"]),
        )
        "error" -> StreamEvent.Error(obj["message"]?.jsonPrimitive?.contentOrNull ?: "Gateway stream error")
        "raw" -> StreamEvent.Raw(obj["rawValue"] ?: value)
        else -> StreamEvent.Raw(buildJsonObject {
            put("type", JsonPrimitive(type ?: "unknown"))
            put("data", value)
        })
    }
}

private fun finishReason(value: String?): FinishReason = when (value) {
    "stop" -> FinishReason.Stop
    "length" -> FinishReason.Length
    "tool-calls", "toolCalls" -> FinishReason.ToolCalls
    "content-filter", "contentFilter" -> FinishReason.ContentFilter
    "error" -> FinishReason.Error
    "tool-approval-requested", "toolApprovalRequested" -> FinishReason.ToolApprovalRequested
    else -> FinishReason.Other
}

private fun usageFromJson(value: JsonElement?): Usage {
    val obj = value?.jsonObject ?: return Usage()
    val prompt = jsonIntOrNull(obj, "promptTokens") ?: jsonIntOrNull(obj, "inputTokens") ?: 0
    val completion = jsonIntOrNull(obj, "completionTokens") ?: jsonIntOrNull(obj, "outputTokens") ?: 0
    return Usage(promptTokens = prompt, completionTokens = completion)
}

private fun callWarnings(value: JsonElement?): List<CallWarning> =
    (value as? JsonArray).orEmpty().map { warning ->
        val obj = warning.jsonObject
        CallWarning(
            type = obj["type"]?.jsonPrimitive?.contentOrNull ?: "other",
            message = obj["message"]?.jsonPrimitive?.contentOrNull
                ?: obj["details"]?.jsonPrimitive?.contentOrNull,
            details = warning,
        )
    }

private fun jsonObjectMap(value: JsonElement?): Map<String, JsonElement> =
    jsonObjectMapOrNull(value).orEmpty()

private fun jsonObjectMapOrNull(value: JsonElement?): Map<String, JsonElement>? =
    (value as? JsonObject)?.toMap()

private fun gatewayModelType(value: String?): GatewayModelType? = when (value) {
    "embedding" -> GatewayModelType.Embedding
    "image" -> GatewayModelType.Image
    "language" -> GatewayModelType.Language
    "reranking" -> GatewayModelType.Reranking
    "video" -> GatewayModelType.Video
    else -> null
}

private fun gatewayCredentialType(value: String?): GatewayCredentialType? = when (value) {
    "byok" -> GatewayCredentialType.Byok
    "system" -> GatewayCredentialType.System
    else -> null
}

private fun gatewayOrigin(baseUrl: String): String =
    Regex("^(https?://[^/]+)").find(baseUrl)?.groupValues?.get(1) ?: baseUrl.trimEnd('/')

private fun gatewayErrorFromResponse(statusCode: Int, raw: String): GatewayError {
    val parsed = runCatching { gatewayJson.parseToJsonElement(raw).jsonObject }.getOrNull()
    val error = parsed?.get("error")?.jsonObject
    val type = error?.get("type")?.jsonPrimitive?.contentOrNull
    val message = error?.get("message")?.jsonPrimitive?.contentOrNull ?: raw.ifBlank { "Gateway request failed" }
    val generationId = parsed?.get("generationId")?.jsonPrimitive?.contentOrNull
    return when (type) {
        "authentication_error" -> GatewayAuthenticationError(message, statusCode, generationId)
        "invalid_request_error" -> GatewayInvalidRequestError(message, statusCode, generationId)
        "rate_limit_exceeded" -> GatewayRateLimitError(message, statusCode, generationId)
        "model_not_found" -> GatewayModelNotFoundError(message, statusCode, generationId = generationId)
        "internal_server_error" -> GatewayInternalServerError(message, statusCode, generationId)
        else -> GatewayResponseError(message = message, statusCode = statusCode, response = parsed, generationId = generationId)
    }
}

private fun jsonNumber(obj: JsonObject, vararg names: String): Double =
    jsonNumberOrNull(obj, *names) ?: 0.0

private fun jsonNumberOrNull(obj: JsonObject, vararg names: String): Double? =
    names.firstNotNullOfOrNull { name -> obj[name]?.jsonPrimitive?.doubleOrNull }

private fun jsonInt(obj: JsonObject, vararg names: String): Int =
    jsonIntOrNull(obj, *names) ?: 0

private fun jsonIntOrNull(obj: JsonObject, vararg names: String): Int? =
    names.firstNotNullOfOrNull { name -> obj[name]?.jsonPrimitive?.intOrNull }

private fun urlEncode(value: String): String =
    buildString {
        value.encodeToByteArray().forEach { byte ->
            val unsigned = byte.toInt() and 0xff
            val char = unsigned.toChar()
            if (char.isLetterOrDigit() || char in setOf('-', '_', '.', '~')) {
                append(char)
            } else {
                append('%')
                append(unsigned.toString(16).uppercase().padStart(2, '0'))
            }
        }
    }
