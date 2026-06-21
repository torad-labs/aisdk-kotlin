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

public fun CreateGatewayHttpProvider(
    client: HttpClient,
    settings: GatewayProviderSettings = GatewayProviderSettings(),
    json: Json = aiSdkJson,
): GatewayProvider = GatewayProvider(settings.copy(transport = KtorGatewayTransport(client, json)))

public class KtorGatewayTransport(
    private val client: HttpClient,
    private val json: Json = aiSdkJson,
) : GatewayTransport {
    override suspend fun generateText(
        context: GatewayRequestContext,
        modelId: ModelId,
        params: LanguageModelCallParams,
    ): LanguageModelResult {
        val body = languageModelRequestBody(params)
        val response = postJson(
            context = context,
            path = "/language-model",
            body = body,
            headers = languageModelHeaders(modelId.value, streaming = false),
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
        modelId: ModelId,
        params: LanguageModelCallParams,
    ): Flow<StreamEvent> {
        val body = languageModelRequestBody(params)
        val url = context.baseUrl.trimEnd('/') + "/language-model"
        val headers = context.headers +
            languageModelHeaders(modelId.value, streaming = true) +
            mapOf(HttpHeaders.Accept to "text/event-stream")
        // Route through the incremental streamSse() helper so SSE events are
        // emitted as they arrive instead of buffering the whole body first.
        var sseHeaders: Map<String, String> = emptyMap()
        val rawLines = HttpTransport.streamSse(
            client = client,
            url = url,
            headers = headers,
            body = body,
            json = json,
            errorFromResponse = gatewayError,
            onResponse = { sseHeaders = it },
        )
        val events = EventStreamParser.parse(rawLines, Schemas.jsonSchema<JsonElement>(JsonObject(emptyMap())), json)
        return flow {
            with(HttpTransport) {
                forwardSseEvents(
                    events = events,
                    capturedHeaders = { sseHeaders },
                    parseErrorPrefix = "Failed to parse gateway stream event",
                    onEvent = { emit(GatewayWire.streamEventFromJson(it)) },
                )
            }
        }
    }

    override suspend fun embed(
        context: GatewayRequestContext,
        modelId: ModelId,
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
                "ai-model-id" to modelId.value,
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
        modelId: ModelId,
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
                "ai-model-id" to modelId.value,
            ) + params.headers,
        )
        val value = response.value.jsonObject
        return ImageModelResult(
            images = value["images"]?.jsonArray.orEmpty().map {
                GeneratedFile(mediaType = "image/png", base64 = it.jsonPrimitive.content)
            },
            warnings = GatewayWire.callWarnings(value["warnings"]),
            response = LanguageModelResponseMetadata(
                modelId = modelId.value,
                headers = response.headers,
                body = response.value
            ),
            providerMetadata = value["providerMetadata"].let { if (it is JsonObject) ProviderMetadata.Raw(it) else ProviderMetadata.None },
        )
    }

    override suspend fun generateVideo(
        context: GatewayRequestContext,
        modelId: ModelId,
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
        val rawLines = HttpTransport.streamSse(
            client = client,
            url = context.baseUrl.trimEnd('/') + "/video-model",
            headers = context.headers + mapOf(
                "ai-video-model-specification-version" to "3",
                "ai-model-id" to modelId.value,
                HttpHeaders.Accept to "text/event-stream",
            ) + params.headers,
            body = body,
            json = json,
            errorFromResponse = gatewayError,
            onResponse = { sseHeaders = it },
        )
        val event = when (
            val first = EventStreamParser.parse(rawLines, Schemas.jsonSchema<JsonElement>(JsonObject(emptyMap())), json)
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
            warnings = GatewayWire.callWarnings(event["warnings"]),
            response = LanguageModelResponseMetadata(modelId = modelId.value, headers = sseHeaders),
            providerMetadata = event["providerMetadata"].let { if (it is JsonObject) ProviderMetadata.Raw(it) else ProviderMetadata.None },
        )
    }

    override suspend fun rerank(
        context: GatewayRequestContext,
        modelId: ModelId,
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
                "ai-model-id" to modelId.value,
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
                modelType = GatewayWire.gatewayModelType(obj["modelType"]?.jsonPrimitive?.contentOrNull),
            )
        }
        return GatewayFetchMetadataResponse(models)
    }

    override suspend fun getCredits(context: GatewayRequestContext): GatewayCreditsResponse {
        val response = getJson(context.copy(baseUrl = GatewayWire.gatewayOrigin(context.baseUrl)), "/v1/credits")
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
        val response = getJson(context.copy(baseUrl = GatewayWire.gatewayOrigin(context.baseUrl)), "/v1/report?$query")
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
                    credentialType = GatewayWire.gatewayCredentialType(
                        obj["credential_type"]?.jsonPrimitive?.contentOrNull
                            ?: obj["credentialType"]?.jsonPrimitive?.contentOrNull,
                    ),
                    totalCost = TypedJsonOps.jsonNumber(obj, "total_cost", "totalCost"),
                    marketCost = TypedJsonOps.jsonNumberOrNull(obj, "market_cost", "marketCost"),
                    inputTokens = TypedJsonOps.jsonIntOrNull(obj, "input_tokens", "inputTokens"),
                    outputTokens = TypedJsonOps.jsonIntOrNull(obj, "output_tokens", "outputTokens"),
                    cachedInputTokens = TypedJsonOps.jsonIntOrNull(obj, "cached_input_tokens", "cachedInputTokens"),
                    cacheCreationInputTokens = TypedJsonOps.jsonIntOrNull(
                        obj,
                        "cache_creation_input_tokens",
                        "cacheCreationInputTokens"
                    ),
                    reasoningTokens = TypedJsonOps.jsonIntOrNull(obj, "reasoning_tokens", "reasoningTokens"),
                    requestCount = TypedJsonOps.jsonIntOrNull(obj, "request_count", "requestCount"),
                )
            },
        )
    }

    override suspend fun getGenerationInfo(
        context: GatewayRequestContext,
        params: GatewayGenerationInfoParams,
    ): GatewayGenerationInfo {
        val response = getJson(
            context.copy(baseUrl = GatewayWire.gatewayOrigin(context.baseUrl)),
            "/v1/generation?id=${UrlOps.encode(params.id)}",
        )
        val data = response.value.jsonObject["data"]?.jsonObject ?: response.value.jsonObject
        return GatewayGenerationInfo(
            id = data["id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            totalCost = TypedJsonOps.jsonNumber(data, "total_cost", "totalCost"),
            upstreamInferenceCost = TypedJsonOps.jsonNumber(data, "upstream_inference_cost", "upstreamInferenceCost"),
            usage = TypedJsonOps.jsonNumber(data, "usage"),
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
            latency = TypedJsonOps.jsonInt(data, "latency"),
            generationTime = TypedJsonOps.jsonInt(data, "generation_time", "generationTime"),
            promptTokens = TypedJsonOps.jsonInt(data, "native_tokens_prompt", "promptTokens"),
            completionTokens = TypedJsonOps.jsonInt(data, "native_tokens_completion", "completionTokens"),
            reasoningTokens = TypedJsonOps.jsonInt(data, "native_tokens_reasoning", "reasoningTokens"),
            cachedTokens = TypedJsonOps.jsonInt(data, "native_tokens_cached", "cachedTokens"),
            cacheCreationTokens = TypedJsonOps.jsonInt(data, "native_tokens_cache_creation", "cacheCreationTokens"),
            billableWebSearchCalls = TypedJsonOps.jsonInt(data, "billable_web_search_calls", "billableWebSearchCalls"),
        )
    }

    private suspend fun postJson(
        context: GatewayRequestContext,
        path: String,
        body: JsonElement,
        headers: Map<String, String> = emptyMap(),
        parseJson: Boolean = true,
    ): HttpJsonResponse =
        HttpTransport.requestJson(
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
        HttpTransport.requestJson(
            client = client,
            url = context.baseUrl.trimEnd('/') + path,
            method = HttpMethod.Get,
            headers = context.headers,
            json = json,
            errorFromResponse = gatewayError,
        )

    private fun languageModelRequestBody(params: LanguageModelCallParams): JsonObject = buildJsonObject {
        put("prompt", JsonArray(params.messages.map(GatewayWire::modelMessageJson)))
        if (params.tools.isNotEmpty()) {
            put("tools", JsonArray(params.tools.map(GatewayWire::languageModelToolJson)))
        }
        put("toolChoice", GatewayWire.toolChoiceJson(params.toolChoice))
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
        put("responseFormat", GatewayWire.responseFormatJson(params.responseFormat))
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
        val content = GatewayWire.contentParts(obj["content"])
        val text = obj["text"]?.jsonPrimitive?.contentOrNull
            ?: content.filterIsInstance<ContentPart.Text>().joinToString("") { it.text }
        return LanguageModelResult(
            text = text,
            toolCalls = content.filterIsInstance<ContentPart.ToolCall>(),
            finishReason = GatewayWire.finishReason(obj["finishReason"]?.jsonPrimitive?.contentOrNull),
            usage = GatewayWire.usageFromJson(obj["usage"]),
            providerMetadata = obj["providerMetadata"].let { if (it is JsonObject) ProviderMetadata.Raw(it) else ProviderMetadata.None },
            content = content.ifEmpty { if (text.isNotEmpty()) listOf(ContentPart.Text(text)) else emptyList() },
            rawFinishReason = obj["finishReason"]?.jsonPrimitive?.contentOrNull,
            warnings = GatewayWire.callWarnings(obj["warnings"]),
            request = LanguageModelRequestMetadata(body = requestBody),
            response = LanguageModelResponseMetadata(headers = responseHeaders, body = responseBody),
        )
    }
}

/** Adapter for the shared transport's `errorFromResponse` hook — keeps the rich [GatewayError] hierarchy. */
private val gatewayError: ResponseErrorFactory = { statusCode, _, raw, _ ->
    GatewayWire.gatewayErrorFromResponse(statusCode, raw)
}
