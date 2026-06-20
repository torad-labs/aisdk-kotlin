package ai.torad.aisdk

import io.ktor.client.HttpClient
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

public fun createGatewayHttpProvider(
    client: HttpClient,
    settings: GatewayProviderSettings = GatewayProviderSettings(),
    json: Json = aiSdkJson,
): GatewayProvider = createGatewayProvider(
    settings.copy(transport = KtorGatewayTransport(client, json)),
)

public class KtorGatewayTransport(
    private val client: HttpClient,
    private val json: Json = aiSdkJson,
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
    ): Flow<StreamEvent> {
        val body = languageModelRequestBody(params)
        val url = context.baseUrl.trimEnd('/') + "/language-model"
        val headers = context.headers +
            languageModelHeaders(modelId, streaming = true) +
            mapOf(HttpHeaders.Accept to "text/event-stream")
        // Route through the incremental streamSse() helper so SSE events are
        // emitted as they arrive instead of buffering the whole body first.
        var sseHeaders: Map<String, String> = emptyMap()
        val rawLines = streamSse(
            client = client,
            url = url,
            headers = headers,
            body = body,
            json = json,
            errorFromResponse = gatewayError,
            onResponse = { sseHeaders = it },
        )
        val events = EventStreamParser.parse(rawLines, jsonSchema<JsonElement>(JsonObject(emptyMap())), json)
        return flow {
            forwardSseEvents(
                events = events,
                capturedHeaders = { sseHeaders },
                parseErrorPrefix = "Failed to parse gateway stream event",
                onEvent = { emit(streamEventFromJson(it)) },
            )
        }
    }

    override suspend fun embed(
        context: GatewayRequestContext,
        modelId: GatewayEmbeddingModelId,
        params: EmbeddingModelCallParams,
    ): EmbeddingModelResult {
        val body = buildJsonObject {
            put("values", JsonArray(params.values.map(::JsonPrimitive)))
            if (params.providerOptions.toMap().isNotEmpty()) put("providerOptions", JsonObject(params.providerOptions.toMap()))
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
                row.jsonArray.map { WireDecoder.embeddingFloat(it, "gateway.embedding") }
            },
            usage = EmbeddingUsage(tokens = value["usage"]?.jsonObject?.get("tokens")?.jsonPrimitive?.intOrNull ?: 0),
            response = LanguageModelResponseMetadata(headers = response.headers, body = response.value),
            providerMetadata = value["providerMetadata"].let { if (it is JsonObject) ProviderMetadata.Raw(it) else ProviderMetadata.None },
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
            if (params.providerOptions.toMap().isNotEmpty()) put("providerOptions", JsonObject(params.providerOptions.toMap()))
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
            response = LanguageModelResponseMetadata(
                modelId = modelId,
                headers = response.headers,
                body = response.value
            ),
            providerMetadata = value["providerMetadata"].let { if (it is JsonObject) ProviderMetadata.Raw(it) else ProviderMetadata.None },
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
            if (params.providerOptions.toMap().isNotEmpty()) put("providerOptions", JsonObject(params.providerOptions.toMap()))
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
        // Consume the SSE response incrementally via streamSse() and stop at the
        // first data event — firstOrNull() cancels the upstream, whose finally
        // releases the connection — instead of buffering the whole body first.
        var sseHeaders: Map<String, String> = emptyMap()
        val rawLines = streamSse(
            client = client,
            url = context.baseUrl.trimEnd('/') + "/video-model",
            headers = context.headers + mapOf(
                "ai-video-model-specification-version" to "3",
                "ai-model-id" to modelId,
                HttpHeaders.Accept to "text/event-stream",
            ) + params.headers,
            body = body,
            json = json,
            errorFromResponse = gatewayError,
            onResponse = { sseHeaders = it },
        )
        val event = when (
            val first = EventStreamParser.parse(rawLines, jsonSchema<JsonElement>(JsonObject(emptyMap())), json)
                .firstOrNull()
        ) {
            is ParseResult.Success -> first.value.jsonObject
            is ParseResult.Failure -> throw GatewayResponseError(
                message = "Failed to parse gateway video event: ${first.error.message}",
                response = JsonPrimitive(first.text),
                cause = first.error,
            )
            null -> throw GatewayResponseError("SSE stream ended without a data event")
        }
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
            response = LanguageModelResponseMetadata(modelId = modelId, headers = sseHeaders),
            providerMetadata = event["providerMetadata"].let { if (it is JsonObject) ProviderMetadata.Raw(it) else ProviderMetadata.None },
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
            if (params.providerOptions.toMap().isNotEmpty()) put("providerOptions", JsonObject(params.providerOptions.toMap()))
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
            providerMetadata = value["providerMetadata"].let { if (it is JsonObject) ProviderMetadata.Raw(it) else ProviderMetadata.None },
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
            add("start_date=${UrlOps.encode(params.startDate)}")
            add("end_date=${UrlOps.encode(params.endDate)}")
            params.groupBy?.let { add("group_by=${UrlOps.encode(it.wireValue)}") }
            params.datePart?.let { add("date_part=${UrlOps.encode(it.wireValue)}") }
            params.userId?.let { add("user_id=${UrlOps.encode(it)}") }
            params.model?.let { add("model=${UrlOps.encode(it)}") }
            params.provider?.let { add("provider=${UrlOps.encode(it)}") }
            params.credentialType?.let { add("credential_type=${UrlOps.encode(it.wireValue)}") }
            if (params.tags.isNotEmpty()) add("tags=${UrlOps.encode(params.tags.joinToString(","))}")
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
                    cacheCreationInputTokens = jsonIntOrNull(
                        obj,
                        "cache_creation_input_tokens",
                        "cacheCreationInputTokens"
                    ),
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
            "/v1/generation?id=${UrlOps.encode(params.id)}",
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
    ): HttpJsonResponse =
        requestJson(
            client = client,
            url = context.baseUrl.trimEnd('/') + path,
            method = HttpMethod.Post,
            headers = context.headers + headers,
            body = body,
            json = json,
            parseJson = parseJson,
            errorFromResponse = gatewayError,
        )

    private suspend fun getJson(
        context: GatewayRequestContext,
        path: String,
    ): HttpJsonResponse =
        requestJson(
            client = client,
            url = context.baseUrl.trimEnd('/') + path,
            method = HttpMethod.Get,
            headers = context.headers,
            json = json,
            errorFromResponse = gatewayError,
        )

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
        if (params.providerOptions.toMap().isNotEmpty()) put("providerOptions", JsonObject(params.providerOptions.toMap()))
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
            providerMetadata = obj["providerMetadata"].let { if (it is JsonObject) ProviderMetadata.Raw(it) else ProviderMetadata.None },
            content = content.ifEmpty { if (text.isNotEmpty()) listOf(ContentPart.Text(text)) else emptyList() },
            rawFinishReason = obj["finishReason"]?.jsonPrimitive?.contentOrNull,
            warnings = callWarnings(obj["warnings"]),
            request = LanguageModelRequestMetadata(body = requestBody),
            response = LanguageModelResponseMetadata(headers = responseHeaders, body = responseBody),
        )
    }
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
            val sourceType =
                if (part.sourceType == StreamEvent.SourcePart.SourceType.Url) "source-url" else "source-document"
            put("type", JsonPrimitive(sourceType))
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
    val pm = part.metadata
    if (pm is ProviderMetadata.Raw) put("providerMetadata", pm.metadata)
}

private fun languageModelToolJson(tool: LanguageModelTool): JsonObject = buildJsonObject {
    // The gateway forwards LanguageModelV3CallOptions verbatim, so tools must use the
    // v3 FunctionTool wire shape: `type: "function"` + `inputSchema` (was emitting
    // `parameters` with no type, which the gateway/downstream provider doesn't read).
    put("type", JsonPrimitive("function"))
    put("name", JsonPrimitive(tool.name))
    put("description", JsonPrimitive(tool.description))
    put("inputSchema", aiSdkJson.parseToJsonElement(tool.parametersSchemaJson))
    put("strict", JsonPrimitive(tool.strict))
    if (tool.providerExecuted) put("providerExecuted", JsonPrimitive(true))
    if (tool.providerOptions.toMap().isNotEmpty()) put("providerOptions", JsonObject(tool.providerOptions.toMap()))
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
    value?.let { WireDecoder.arrayValue(it, "gateway", "content parts").mapNotNull(::contentPartFromJson) }.orEmpty()

private fun contentPartFromJson(value: JsonElement): ContentPart? {
    val obj = WireDecoder.objectValue(value, "gateway", "content part")
    return when (WireDecoder.requiredString(obj, "type", "gateway", "content part")) {
        "text" -> ContentPart.Text(
            text = WireDecoder.requiredString(obj, "text", "gateway", "content part"),
            providerMetadata = obj["providerMetadata"].let { if (it is JsonObject) ProviderMetadata.Raw(it) else ProviderMetadata.None },
        )
        "reasoning" -> ContentPart.Reasoning(
            text = WireDecoder.requiredString(obj, "text", "gateway", "content part"),
            providerMetadata = obj["providerMetadata"].let { if (it is JsonObject) ProviderMetadata.Raw(it) else ProviderMetadata.None },
        )
        "tool-call" -> ContentPart.ToolCall(
            toolCallId = WireDecoder.requiredString(obj, "toolCallId", "gateway", "content part"),
            toolName = WireDecoder.requiredString(obj, "toolName", "gateway", "content part"),
            input = WireDecoder.required(obj, "input", "gateway", "content part"),
            providerMetadata = obj["providerMetadata"].let { if (it is JsonObject) ProviderMetadata.Raw(it) else ProviderMetadata.None },
        )
        "tool-result" -> ContentPart.ToolResult(
            toolCallId = WireDecoder.requiredString(obj, "toolCallId", "gateway", "content part"),
            toolName = WireDecoder.requiredString(obj, "toolName", "gateway", "content part"),
            output = WireDecoder.required(obj, "output", "gateway", "content part"),
            isError = WireDecoder.optionalBoolean(obj, "isError", "gateway", "content part") ?: false,
            providerMetadata = obj["providerMetadata"].let { if (it is JsonObject) ProviderMetadata.Raw(it) else ProviderMetadata.None },
        )
        "source-url" -> ContentPart.Source(
            sourceType = StreamEvent.SourcePart.SourceType.Url,
            url = WireDecoder.requiredString(obj, "url", "gateway", "content part"),
            title = WireDecoder.optionalString(obj, "title", "gateway", "content part"),
            providerMetadata = obj["providerMetadata"].let { if (it is JsonObject) ProviderMetadata.Raw(it) else ProviderMetadata.None },
        )
        "file" -> ContentPart.File(
            mediaType = WireDecoder.optionalString(obj, "mediaType", "gateway", "content part") ?: "application/octet-stream",
            base64 = requiredOneOfString(obj, "gateway", "content part", "data", "base64"),
            filename = WireDecoder.optionalString(obj, "filename", "gateway", "content part"),
            providerMetadata = obj["providerMetadata"].let { if (it is JsonObject) ProviderMetadata.Raw(it) else ProviderMetadata.None },
        )
        else -> null
    }
}

private fun streamEventFromJson(value: JsonElement): StreamEvent {
    val obj = WireDecoder.objectValue(value, "gateway", "stream event")
    return when (val type = WireDecoder.requiredString(obj, "type", "gateway", "stream event")) {
        "stream-start" -> StreamEvent.StreamStart(callWarnings(obj["warnings"]))
        "response-metadata" -> StreamEvent.ResponseMetadata(
            id = WireDecoder.optionalString(obj, "id", "gateway", "stream event"),
            timestampMillis = obj["timestampMillis"]?.jsonPrimitive?.longOrNull
                ?: obj["timestamp"]?.jsonPrimitive?.doubleOrNull?.let { (it * 1000).toLong() },
            modelId = WireDecoder.optionalString(obj, "modelId", "gateway", "stream event"),
            headers = (obj["headers"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.content }.orEmpty(),
            body = obj["body"],
        )
        "text-start" -> StreamEvent.TextStart(
            WireDecoder.optionalString(obj, "id", "gateway", "stream event") ?: "text"
        )
        "text-delta" -> StreamEvent.TextDelta(
            id = WireDecoder.optionalString(obj, "id", "gateway", "stream event") ?: "text",
            text = requiredOneOfString(obj, "gateway", "stream event", "delta", "text"),
        )
        "text-end" -> StreamEvent.TextEnd(WireDecoder.optionalString(obj, "id", "gateway", "stream event") ?: "text")
        "reasoning-start" -> StreamEvent.ReasoningStart(
            WireDecoder.optionalString(obj, "id", "gateway", "stream event") ?: "reasoning"
        )
        "reasoning-delta" -> StreamEvent.ReasoningDelta(
            id = WireDecoder.optionalString(obj, "id", "gateway", "stream event") ?: "reasoning",
            text = requiredOneOfString(obj, "gateway", "stream event", "delta", "text"),
        )
        "reasoning-end" -> StreamEvent.ReasoningEnd(
            WireDecoder.optionalString(obj, "id", "gateway", "stream event") ?: "reasoning"
        )
        "tool-input-start" -> StreamEvent.ToolInputStart(
            id = requiredOneOfString(obj, "gateway", "stream event", "id", "toolCallId"),
            toolName = WireDecoder.requiredString(obj, "toolName", "gateway", "stream event"),
        )
        "tool-input-delta" -> StreamEvent.ToolInputDelta(
            id = requiredOneOfString(obj, "gateway", "stream event", "id", "toolCallId"),
            delta = WireDecoder.requiredString(obj, "delta", "gateway", "stream event"),
        )
        "tool-input-end" -> StreamEvent.ToolInputEnd(
            id = requiredOneOfString(obj, "gateway", "stream event", "id", "toolCallId"),
        )
        "tool-call" -> StreamEvent.ToolCall(
            toolCallId = WireDecoder.requiredString(obj, "toolCallId", "gateway", "stream event"),
            toolName = WireDecoder.requiredString(obj, "toolName", "gateway", "stream event"),
            inputJson = WireDecoder.required(obj, "input", "gateway", "stream event"),
        )
        "tool-result" -> {
            val output = toolResultOutputFromWire(WireDecoder.required(obj, "output", "gateway", "stream event"))
            StreamEvent.ToolResult(
                toolCallId = WireDecoder.requiredString(obj, "toolCallId", "gateway", "stream event"),
                toolName = WireDecoder.requiredString(obj, "toolName", "gateway", "stream event"),
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
        "error" -> StreamEvent.Error(WireDecoder.requiredString(obj, "message", "gateway", "stream event"))
        "raw" -> StreamEvent.Raw(obj["rawValue"] ?: value)
        else -> StreamEvent.Raw(
            buildJsonObject {
                put("type", JsonPrimitive(type))
                put("data", value)
            }
        )
    }
}

private fun requiredOneOfString(
    obj: JsonObject,
    provider: String,
    operation: String,
    vararg keys: String,
): String =
    keys.firstNotNullOfOrNull { key -> WireDecoder.optionalString(obj, key, provider, operation) }
        ?: WireDecoder.fail(
            provider = provider,
            operation = operation,
            path = "$",
            message = "missing one required field: ${keys.joinToString(" or ")}",
            value = obj,
        )

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

/** Adapter for the shared transport's `errorFromResponse` hook — keeps the rich [GatewayError] hierarchy. */
private val gatewayError: ResponseErrorFactory = { statusCode, _, raw, _ ->
    gatewayErrorFromResponse(statusCode, raw)
}

private fun gatewayErrorFromResponse(statusCode: Int, raw: String): GatewayError {
    val parsed = runCatching { aiSdkJson.parseToJsonElement(raw).jsonObject }.getOrNull()
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
        else -> GatewayResponseError(
            message = message,
            statusCode = statusCode,
            response = parsed,
            generationId = generationId
        )
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
