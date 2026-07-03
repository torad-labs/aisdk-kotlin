@file:OptIn(ai.torad.aisdk.LowLevelLanguageModelApi::class)

package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.http.HttpMethod
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
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

internal object GoogleInteractions {
    fun googleInteractionsRequestBody(
        input: GoogleInteractionsModelInput,
        params: LanguageModelCallParams,
        stream: Boolean,
    ): GoogleInteractionsPreparedRequest {
        val warnings = mutableListOf<CallWarning>()
        val options = JsonAccess.obj(params.providerOptions.toMap(), "google") ?: JsonObject(emptyMap())
        val isAgent = input !is GoogleInteractionsModelInput.Model
        val isBackground = (options["background"] as? JsonPrimitive)?.booleanOrNull == true
        val converted = googleInteractionsInput(
            messages = params.messages,
            mediaResolution = (options["mediaResolution"] as? JsonPrimitive)?.contentOrNull,
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
        val hasFunctionTool = params.tools.any { !it.providerExecuted }
        val toolChoice = if (isAgent || !hasFunctionTool) null else googleInteractionsToolChoice(params.toolChoice)
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
                if (params.stopSequences.isNotEmpty()) put(
                    "stop_sequences",
                    JsonArray(params.stopSequences.map(::JsonPrimitive))
                )
                params.maxOutputTokens?.let { put("max_output_tokens", JsonPrimitive(it)) }
                options["thinkingLevel"]?.let { put("thinking_level", it) }
                options["thinkingSummaries"]?.let { put("thinking_summaries", it) }
                toolChoice?.let { put("tool_choice", it) }
            }.takeIf { it.isNotEmpty() }
        }

        val systemInstruction = converted.systemInstruction
            ?: (options["systemInstruction"] as? JsonPrimitive)?.contentOrNull

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
            pollingTimeoutMillis = (options["pollingTimeoutMs"] as? JsonPrimitive)?.intOrNull?.toLong(),
            isBackground = isBackground,
        )
    }
    fun googleInteractionsInput(
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
                            is ContentPart.File -> googleInteractionsFileContent(
                                part.mediaType,
                                part.base64,
                                part.url,
                                mediaResolution
                            )
                            is ContentPart.Image -> googleInteractionsFileContent(
                                part.mediaType,
                                part.base64,
                                part.url,
                                mediaResolution
                            )
                            is ContentPart.Reasoning,
                            is ContentPart.ToolCall,
                            is ContentPart.ToolResult,
                            is ContentPart.ToolApprovalRequest,
                            is ContentPart.ToolApprovalResponse,
                            is ContentPart.Source,
                            is ContentPart.Raw,
                            -> null
                        }
                    }
                    if (content.isNotEmpty()) {
                        steps += buildJsonObject {
                            put("type", JsonPrimitive("user_input"))
                            put("content", JsonArray(content))
                        }
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
                            is ContentPart.File -> pending += googleInteractionsFileContent(part.mediaType, part.base64, part.url, mediaResolution)
                            is ContentPart.Image -> pending += googleInteractionsFileContent(part.mediaType, part.base64, part.url, mediaResolution)
                            is ContentPart.Reasoning -> {
                                flush()
                                steps += buildJsonObject {
                                    put("type", JsonPrimitive("thought"))
                                    googleInteractionsSignature(part.providerMetadata.toMap())?.let { put("signature", JsonPrimitive(it)) }
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
                                    googleInteractionsSignature(part.providerMetadata.toMap())?.let { put("signature", JsonPrimitive(it)) }
                                }
                            }
                            is ContentPart.ToolResult,
                            is ContentPart.ToolApprovalRequest,
                            is ContentPart.ToolApprovalResponse,
                            is ContentPart.Source,
                            is ContentPart.Raw,
                            -> warnings += CallWarning("other", "google.interactions: unsupported assistant content part; part dropped.")
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
                            googleInteractionsSignature(
                                part.providerMetadata.toMap()
                            )?.let { put("signature", JsonPrimitive(it)) }
                        }
                    }
                    if (content.isNotEmpty()) {
                        steps += buildJsonObject {
                            put("type", JsonPrimitive("user_input"))
                            put("content", JsonArray(content))
                        }
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

    fun googleInteractionsFileContent(mediaType: String, base64: String, url: String?, mediaResolution: String?): JsonObject {
        val type = when (mediaType.substringBefore('/')) {
            "image" -> "image"
            "audio" -> "audio"
            "video" -> "video"
            else -> "document"
        }
        return buildJsonObject {
            put("type", JsonPrimitive(type))
            if (url != null) {
                put("uri", JsonPrimitive(url))
            } else {
                put("data", JsonPrimitive(base64))
            }
            put("mime_type", JsonPrimitive(mediaType))
            if ((type == "image" || type == "video") && mediaResolution != null) {
                put("resolution", JsonPrimitive(mediaResolution))
            }
        }
    }

    // Map one caller responseFormat entry, skipping a non-object element (Wave 7b). Extracted so
    // googleInteractionsResponseFormat stays under the cyclomatic-complexity threshold.
    private fun googleInteractionsFormatEntry(entry: JsonElement): JsonObject? {
        val obj = entry as? JsonObject ?: return null
        return buildJsonObject {
            obj["type"]?.let { put("type", it) }
            obj["mimeType"]?.let { put("mime_type", it) }
            obj["schema"]?.let { put("schema", it) }
            obj["aspectRatio"]?.let { put("aspect_ratio", it) }
            obj["imageSize"]?.let { put("image_size", it) }
        }
    }

    fun googleInteractionsResponseFormat(
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
                    responseFormat.schemaJson?.let {
                        put("schema", SchemaSanitizer.stripUnsupportedSchemaKeys(it, dropAdditionalProperties = true, googleOpenApi = true))
                    }
                }
            }
        }
        entries += (JsonAccess.arr(options, "responseFormat")).orEmpty().mapNotNull(::googleInteractionsFormatEntry)
        (JsonAccess.obj(options, "imageConfig"))?.let { image ->
            warnings += CallWarning("other", "google.interactions: providerOptions.google.imageConfig is deprecated. Use providerOptions.google.responseFormat with an image entry instead.")
            if (entries.none { (it.jsonObject["type"] as? JsonPrimitive)?.contentOrNull == "image" }) {
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

    fun googleInteractionsTools(
        tools: List<LanguageModelTool>,
        warnings: MutableList<CallWarning>,
    ): JsonArray = JsonArray(
        tools.mapNotNull { tool ->
            if (!tool.providerExecuted) {
                buildJsonObject {
                    put("type", JsonPrimitive("function"))
                    put("name", JsonPrimitive(tool.name))
                    if (tool.description.isNotBlank()) put("description", JsonPrimitive(tool.description))
                    put(
                        "parameters",
                        SchemaSanitizer.stripUnsupportedSchemaKeys(
                            aiSdkJson.parseToJsonElement(tool.parametersSchemaJson),
                            dropAdditionalProperties = true,
                            googleOpenApi = true,
                        ),
                    )
                }
            } else {
                when ((tool.metadata["providerToolId"] as? JsonPrimitive)?.contentOrNull) {
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
                    else -> null.also {
                        warnings += CallWarning("unsupported", "provider-defined tool ${tool.name} is not supported by google.interactions; tool dropped.")
                    }
                }
            }
        },
    )

    fun googleInteractionsToolChoice(choice: ToolChoice): JsonElement? = when (choice) {
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

    fun googleInteractionsAgentConfig(options: JsonObject): JsonObject? {
        val config = (JsonAccess.obj(options, "agentConfig")) ?: return null
        val type = (config["type"] as? JsonPrimitive)?.contentOrNull ?: return null
        return buildJsonObject {
            put("type", JsonPrimitive(type))
            if (type == "deep-research") {
                config["thinkingSummaries"]?.let { put("thinking_summaries", it) }
                config["visualization"]?.let { put("visualization", it) }
                config["collaborativePlanning"]?.let { put("collaborative_planning", it) }
            }
        }
    }

    fun googleInteractionsEnvironment(
        element: JsonElement?,
        isAgent: Boolean,
        warnings: MutableList<CallWarning>,
    ): JsonElement? {
        if (element == null) return null
        if (!isAgent) {
            warnings += CallWarning("other", "google.interactions: environment is only supported when an agent is set; environment will be omitted from the request body.")
            return null
        }
        return when (element) {
            !is JsonObject -> element
            else -> buildJsonObject {
                put("type", element["type"] ?: JsonPrimitive("remote"))
                element["sources"]?.let { put("sources", it) }
                element["network"]?.let { put("network", googleInteractionsNetwork(it)) }
            }
        }
    }

    fun googleInteractionsNetwork(element: JsonElement): JsonElement =
        if (element is JsonPrimitive) {
            element
        } else {
            (element as? JsonObject)?.let { obj ->
                buildJsonObject { obj["allowlist"]?.let { put("allowlist", it) } }
            } ?: element
        }

    fun googleInteractionsResult(
        response: JsonObject,
        requestBody: JsonObject,
        headers: Map<String, String>,
        rawBody: JsonElement,
        warnings: List<CallWarning>,
        settings: GoogleGenerativeAIProviderSettings,
    ): LanguageModelResult {
        val interactionId = (response["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        val parsed = googleInteractionsContent(JsonAccess.arr(response, "steps"), settings.generateId, interactionId)
        val text = parsed.content.filterIsInstance<ContentPart.Text>().joinToString("") { it.text }
        val status = (response["status"] as? JsonPrimitive)?.contentOrNull
        val serviceTier = (response["service_tier"] as? JsonPrimitive)?.contentOrNull ?: headers["x-gemini-service-tier"]
        val providerMetadata = buildJsonObject {
            interactionId?.let { put("interactionId", JsonPrimitive(it)) }
            serviceTier?.let { put("serviceTier", JsonPrimitive(it)) }
        }
        return LanguageModelResult(
            text = text,
            toolCalls = parsed.content.filterIsInstance<ContentPart.ToolCall>(),
            finishReason = googleInteractionsFinishReason(status, parsed.hasFunctionCall),
            usage = googleInteractionsUsage(response["usage"]),
            providerMetadata = if (providerMetadata.isEmpty()) ProviderMetadata.None else ProviderMetadata.Raw(
                JsonObject(mapOf("google" to providerMetadata))
            ),
            content = parsed.content,
            rawFinishReason = status,
            warnings = warnings,
            request = LanguageModelRequestMetadata(requestBody),
            response = LanguageModelResponseMetadata(
                id = interactionId,
                modelId = (response["model"] as? JsonPrimitive)?.contentOrNull,
                headers = headers,
                body = rawBody,
            ),
        )
    }
    fun googleInteractionsContent(
        steps: JsonArray?,
        generateId: () -> String,
        interactionId: String?,
    ): GoogleInteractionsParsedContent {
        val content = mutableListOf<ContentPart>()
        var hasFunctionCall = false
        steps.orEmpty().forEach { stepElement ->
            val step = stepElement as? JsonObject ?: return@forEach
            when (val type = (step["type"] as? JsonPrimitive)?.contentOrNull) {
                "model_output" -> {
                    (JsonAccess.arr(step, "content")).orEmpty().forEach { blockElement ->
                        val block = blockElement as? JsonObject ?: return@forEach
                        when ((block["type"] as? JsonPrimitive)?.contentOrNull) {
                            "text" -> {
                                val metadata = googleInteractionsMetadata(interactionId = interactionId)
                                val blockText = (block["text"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                                content += ContentPart.Text(blockText, metadata)
                                content += googleInteractionsAnnotationSources(
                                    JsonAccess.arr(block, "annotations"), generateId, metadata.toMap().ifEmpty { null },
                                )
                            }
                            "image" -> {
                                val metadata = googleInteractionsMetadata(
                                    interactionId = interactionId,
                                    extra = (block["uri"] as? JsonPrimitive)?.contentOrNull
                                        ?.let { mapOf("imageUri" to JsonPrimitive(it)) }.orEmpty(),
                                )
                                content += ContentPart.File(
                                    mediaType = (block["mime_type"] as? JsonPrimitive)?.contentOrNull ?: "image/png",
                                    base64 = (block["data"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                                    providerMetadata = metadata,
                                )
                            }
                        }
                    }
                }
                "thought" -> {
                    content += ContentPart.Reasoning(
                        text = (JsonAccess.arr(step, "summary")).orEmpty()
                            .mapNotNull { ((it as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull }
                            .joinToString("\n"),
                        providerMetadata = googleInteractionsMetadata(
                            signature = (step["signature"] as? JsonPrimitive)?.contentOrNull,
                            interactionId = interactionId,
                        ),
                    )
                }
                "function_call" -> {
                    hasFunctionCall = true
                    content += ContentPart.ToolCall(
                        toolCallId = (step["id"] as? JsonPrimitive)?.contentOrNull ?: IdGenerator.generate(),
                        // Fail loudly on a missing/blank function_call name instead of fabricating "".
                        toolName = WireDecoder.requiredString(step, "name", "google", "interactions response", "$.function_call"),
                        input = step["arguments"] ?: JsonObject(emptyMap()),
                        providerMetadata = googleInteractionsMetadata(
                            signature = (step["signature"] as? JsonPrimitive)?.contentOrNull,
                            interactionId = interactionId,
                        ),
                    )
                }
                else -> {
                    if (type != null && type.endsWith("_call")) {
                        hasFunctionCall = true
                        content += ContentPart.ToolCall(
                            toolCallId = (step["id"] as? JsonPrimitive)?.contentOrNull ?: IdGenerator.generate(),
                            toolName = if (type == "mcp_server_tool_call") {
                                (step["name"] as? JsonPrimitive)?.contentOrNull ?: "mcp_server_tool"
                            } else {
                                type.removeSuffix("_call")
                            },
                            input = step["arguments"] ?: JsonObject(emptyMap()),
                            providerMetadata = ProviderMetadata.Raw(JsonObject(mapOf("google" to buildJsonObject {
                                put("providerExecuted", JsonPrimitive(true))
                            }))),
                        )
                    } else if (type != null && type.endsWith("_result")) {
                        content += ContentPart.ToolResult(
                            toolCallId = (step["call_id"] as? JsonPrimitive)?.contentOrNull ?: IdGenerator.generate(),
                            toolName = if (type == "mcp_server_tool_result") {
                                (step["name"] as? JsonPrimitive)?.contentOrNull ?: "mcp_server_tool"
                            } else {
                                type.removeSuffix("_result")
                            },
                            output = step.getOrElse("result") { JsonNull },
                            isError = (step["is_error"] as? JsonPrimitive)?.booleanOrNull == true,
                            providerMetadata = ProviderMetadata.Raw(JsonObject(mapOf("google" to buildJsonObject {
                                put("providerExecuted", JsonPrimitive(true))
                            }))),
                        )
                    }
                }
            }
        }
        return GoogleInteractionsParsedContent(content, hasFunctionCall)
    }

    fun googleInteractionsAnnotationSources(
        annotations: JsonArray?,
        generateId: () -> String,
        metadata: Map<String, JsonElement>?,
    ): List<ContentPart.Source> = annotations.orEmpty().mapNotNull { annotationElement ->
        val annotation = annotationElement as? JsonObject ?: return@mapNotNull null
        val type = (annotation["type"] as? JsonPrimitive)?.contentOrNull
        val url = (annotation["url"] as? JsonPrimitive)?.contentOrNull
            ?: (annotation["document_uri"] as? JsonPrimitive)?.contentOrNull
            ?: return@mapNotNull null
        when (type) {
            "url_citation", "place_citation" -> ContentPart.Source(
                sourceType = StreamEvent.SourcePart.SourceType.Url,
                url = url,
                title = (annotation["title"] as? JsonPrimitive)?.contentOrNull
                    ?: (annotation["name"] as? JsonPrimitive)?.contentOrNull,
                providerMetadata = metadata?.let {
                    ProviderMetadata.Raw(JsonObject(it))
                } ?: ProviderMetadata.Raw(JsonObject(mapOf("google" to buildJsonObject {
                    put("id", JsonPrimitive(IdGenerator.generate()))
                }))),
            )
            "file_citation" -> ContentPart.Source(
                sourceType = StreamEvent.SourcePart.SourceType.Document,
                url = url,
                title = (annotation["file_name"] as? JsonPrimitive)?.contentOrNull,
                providerMetadata = metadata?.let {
                    ProviderMetadata.Raw(JsonObject(it))
                } ?: ProviderMetadata.Raw(JsonObject(mapOf("google" to buildJsonObject {
                    put("id", JsonPrimitive(IdGenerator.generate()))
                }))),
            )
            else -> googleUnknownAnnotationSource(annotation, url, metadata)
        }
    }

    private fun googleUnknownAnnotationSource(
        annotation: JsonObject,
        url: String,
        metadata: Map<String, JsonElement>?,
    ): ContentPart.Source =
        ContentPart.Source(
            sourceType = if (annotation["document_uri"] != null) {
                StreamEvent.SourcePart.SourceType.Document
            } else {
                StreamEvent.SourcePart.SourceType.Url
            },
            url = url,
            title = (annotation["title"] as? JsonPrimitive)?.contentOrNull
                ?: (annotation["name"] as? JsonPrimitive)?.contentOrNull
                ?: (annotation["file_name"] as? JsonPrimitive)?.contentOrNull,
            providerMetadata = googleUnknownAnnotationMetadata(annotation, metadata),
        )

    private fun googleUnknownAnnotationMetadata(
        annotation: JsonObject,
        metadata: Map<String, JsonElement>?,
    ): ProviderMetadata {
        val base = metadata.orEmpty()
        val google = (base["google"] as? JsonObject)
            ?.let { JsonObject(it + ("annotation" to annotation)) }
            ?: annotation
        return ProviderMetadata.Raw(JsonObject(base + ("google" to google)))
    }

    fun googleInteractionsUsage(element: JsonElement?): Usage {
        val obj = element as? JsonObject ?: return Usage()
        val input = (obj["total_input_tokens"] as? JsonPrimitive)?.intOrNull ?: 0
        val output = (obj["total_output_tokens"] as? JsonPrimitive)?.intOrNull ?: 0
        val thought = (obj["total_thought_tokens"] as? JsonPrimitive)?.intOrNull ?: 0
        val cached = ((obj["total_cached_tokens"] as? JsonPrimitive)?.intOrNull ?: 0).coerceIn(0, input)
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

    fun googleInteractionsFinishReason(status: String?, hasFunctionCall: Boolean): FinishReason = when (status) {
        "completed" -> if (hasFunctionCall) FinishReason.ToolCalls else FinishReason.Stop
        "requires_action" -> FinishReason.ToolCalls
        "failed" -> FinishReason.Error
        "incomplete" -> FinishReason.Length
        "cancelled" -> FinishReason.Other
        else -> FinishReason.Other
    }

    fun googleInteractionsTerminal(status: String?): Boolean =
        status in setOf("completed", "failed", "cancelled", "incomplete")

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
        return with(GoogleHttp) { response.parseGoogleResponse(url, parseJson = parseJson) }
    }

    suspend fun googlePollInteraction(
        client: HttpClient,
        settings: GoogleGenerativeAIProviderSettings,
        interactionId: String,
        headers: Map<String, String>,
        abortSignal: AbortSignal,
        timeoutMillis: Long?,
    ): HttpJsonResponse {
        val maxAttempts = ((timeoutMillis ?: 30 * 60 * 1_000L) / settings.videoPollIntervalMillis.coerceAtLeast(1L)).coerceAtLeast(
            1L
        ).toInt()
        var current =
            googleGetJson(client, "${settings.baseURL.trimEnd('/')}/interactions/$interactionId", headers, abortSignal)
        repeat(maxAttempts) {
            val status = (current.value.jsonObject["status"] as? JsonPrimitive)?.contentOrNull
            if (googleInteractionsTerminal(status)) return current
            if (settings.videoPollIntervalMillis > 0) delay(settings.videoPollIntervalMillis)
            current = googleGetJson(client, "${settings.baseURL.trimEnd('/')}/interactions/$interactionId", headers, abortSignal)
        }
        // The loop checks terminal status at the TOP of each iteration, so the final fetch is never
        // inspected — check it here (mirrors the video-polling post-loop guard) before timing out.
        val finalStatus = (current.value.jsonObject["status"] as? JsonPrimitive)?.contentOrNull
        if (googleInteractionsTerminal(finalStatus)) return current
        throw InvalidResponseDataError(null, "google.interactions: polling timed out for interaction $interactionId.")
    }

    fun googleInteractionsMetadata(
        signature: String? = null,
        interactionId: String? = null,
        extra: Map<String, JsonElement> = emptyMap(),
    ): ProviderMetadata {
        val google = buildJsonObject {
            signature?.let { put("signature", JsonPrimitive(it)) }
            interactionId?.let { put("interactionId", JsonPrimitive(it)) }
            extra.forEach { (key, value) -> put(key, value) }
        }
        return if (google.isEmpty()) ProviderMetadata.None else ProviderMetadata.Raw(JsonObject(mapOf("google" to google)))
    }

    fun googleInteractionsSignature(metadata: Map<String, JsonElement>?): String? =
        ((metadata?.get("google") as? JsonObject)?.get("signature") as? JsonPrimitive)?.contentOrNull

/** Parse the interactions SSE [rawLines] and feed each event to [state],
     *  emitting its produced [StreamEvent]s (shared by both stream branches). */
    suspend fun FlowCollector<StreamEvent>.collectGoogleInteractions(
        rawLines: Flow<String>,
        state: GoogleInteractionsStreamState,
    ) {
        EventStreamParser.parse(
            rawLines,
            Schemas.jsonSchema<JsonElement>(JsonObject(emptyMap())),
            aiSdkJson
        ).collect { event ->
            when (event) {
                is ParseResult.Success -> state.accept(event.value.jsonObject).forEach { emit(it) }
                is ParseResult.Failure -> emit(
                    StreamEvent.Error("Failed to parse Google Interactions stream event: ${event.error.message}"),
                )
            }
        }
    }
}
