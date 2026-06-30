@file:OptIn(ai.torad.aisdk.LowLevelLanguageModelApi::class)

package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import ai.torad.aisdk.providers.GoogleHttp.googlePostJson
import ai.torad.aisdk.providers.GoogleHttp.googleStreamSse
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
import kotlinx.serialization.json.jsonObject

internal data class GooglePreparedRequest(
    val body: JsonObject,
    val warnings: List<CallWarning>,
)

internal data class GoogleConvertedMessages(
    val contents: JsonArray,
    val systemInstruction: JsonObject?,
    val warnings: List<CallWarning>,
)

internal class GoogleGenerativeAILanguageModel(
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
            headers = settings.googleHeaders(params.headers),
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
            headers = settings.googleHeaders(params.headers) + (HttpHeaders.Accept to "text/event-stream"),
            abortSignal = params.abortSignal,
        )
        EventStreamParser.parse(rawLines, Schemas.jsonSchema<JsonElement>(JsonObject(emptyMap())), aiSdkJson).collect { event ->
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

    // Wire conversion for the Google generateContent (Gemini) family: request body
    // assembly, message/content/tool mapping, JSON-Schema -> OpenAPI subset, and
    // response/usage/finish-reason decoding.
    private fun googleGenerateContentBody(
        modelId: String,
        settings: GoogleGenerativeAIProviderSettings,
        params: LanguageModelCallParams,
        stream: Boolean,
    ): GooglePreparedRequest {
        val warnings = mutableListOf<CallWarning>()
        val options = JsonAccess.obj(params.providerOptions.toMap(), "google") ?: JsonObject(emptyMap())
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
            googleThoughtMetadata(part.providerMetadata.toMap())?.let { putJsonObjectFields(it) }
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
        is ContentPart.Reasoning,
        is ContentPart.ToolCall,
        is ContentPart.ToolResult,
        is ContentPart.ToolApprovalRequest,
        is ContentPart.ToolApprovalResponse,
        is ContentPart.Source,
        -> null
    }

    private fun googleAssistantPart(part: ContentPart, isGemini3: Boolean = false): JsonElement? = when (part) {
        is ContentPart.Text -> buildJsonObject {
            put("text", JsonPrimitive(part.text))
            googleThoughtMetadata(part.providerMetadata.toMap())?.let { putJsonObjectFields(it) }
        }
        is ContentPart.Reasoning -> buildJsonObject {
            put("text", JsonPrimitive(part.text))
            put("thought", JsonPrimitive(true))
            googleThoughtMetadata(part.providerMetadata.toMap())?.let { putJsonObjectFields(it) }
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
            val sig = (JsonAccess.obj(part.providerMetadata.toMap(), "google"))?.get("thoughtSignature")
            when {
                sig != null -> put("thoughtSignature", sig)
                isGemini3 -> put("thoughtSignature", JsonPrimitive(GOOGLE_SKIP_THOUGHT_SIGNATURE))
            }
        }
        is ContentPart.ToolResult,
        is ContentPart.ToolApprovalRequest,
        is ContentPart.ToolApprovalResponse,
        is ContentPart.Source,
        is ContentPart.Image,
        -> null
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
        is ContentPart.Text,
        is ContentPart.Reasoning,
        is ContentPart.ToolCall,
        is ContentPart.ToolApprovalRequest,
        is ContentPart.ToolApprovalResponse,
        is ContentPart.Source,
        is ContentPart.File,
        is ContentPart.Image,
        -> null
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
        // retrievalConfig and functionCallingConfig are INDEPENDENT ToolConfig fields. The old early
        // return on retrievalConfig skipped the choice handling, so `ToolChoice.None` was silently
        // dropped when retrievalConfig was also set (tools stayed enabled). Merge both instead.
        val functionCallingConfig = when (choice) {
            ToolChoice.Auto -> null
            ToolChoice.None -> buildJsonObject { put("mode", JsonPrimitive("NONE")) }
            ToolChoice.Required -> buildJsonObject { put("mode", JsonPrimitive("ANY")) }
            is ToolChoice.Specific -> buildJsonObject {
                put("mode", JsonPrimitive("ANY"))
                put("allowedFunctionNames", JsonArray(listOf(JsonPrimitive(choice.toolName))))
            }
        }
        val retrieval = options["retrievalConfig"]
        if (functionCallingConfig == null && retrieval == null) return null
        return buildJsonObject {
            retrieval?.let { put("retrievalConfig", it) }
            functionCallingConfig?.let { put("functionCallingConfig", it) }
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
        val candidate = (JsonAccess.arr(response, "candidates")?.firstOrNull() as? JsonObject) ?: JsonObject(emptyMap())
        val contentParts = (JsonAccess.obj(candidate, "content")?.get("parts") as? JsonArray).orEmpty()
        val content = mutableListOf<ContentPart>()
        val toolCalls = mutableListOf<ContentPart.ToolCall>()
        var lastCodeExecutionId: String? = null
        for (part in contentParts) {
            val obj = part as? JsonObject ?: continue
            (JsonAccess.obj(obj, "executableCode"))?.let { code ->
                val id = settings.generateId()
                lastCodeExecutionId = id
                val call = ContentPart.ToolCall(id, "code_execution", code, providerMetadata = ProviderMetadata.Raw(JsonObject(mapOf("google" to buildJsonObject { put("providerExecuted", JsonPrimitive(true)) }))))
                content += call
                toolCalls += call
            }
            (JsonAccess.obj(obj, "codeExecutionResult"))?.let { result ->
                content += ContentPart.ToolResult(lastCodeExecutionId ?: settings.generateId(), "code_execution", result, providerMetadata = ProviderMetadata.Raw(JsonObject(mapOf("google" to buildJsonObject { put("providerExecuted", JsonPrimitive(true)) }))))
                lastCodeExecutionId = null
            }
            (obj["text"] as? JsonPrimitive)?.contentOrNull?.let { text ->
                val metadata = googlePartMetadata(obj)?.let { ProviderMetadata.Raw(JsonObject(it)) } ?: ProviderMetadata.None
                content += if ((obj["thought"] as? JsonPrimitive)?.booleanOrNull == true) {
                    ContentPart.Reasoning(text, metadata)
                } else {
                    ContentPart.Text(text, metadata)
                }
            }
            (JsonAccess.obj(obj, "functionCall"))?.let { callObj ->
                val call = ContentPart.ToolCall(
                    toolCallId = (callObj["id"] as? JsonPrimitive)?.contentOrNull ?: settings.generateId(),
                    // Fail loudly on a missing/blank functionCall.name (matching the streaming path)
                    // rather than fabricating toolName="" that fails downstream as a confusing
                    // "tool not found", masking the real wire problem.
                    toolName = WireDecoder.requiredString(callObj, "name", "google", "generateContent response", "$.functionCall"),
                    input = callObj["args"] ?: JsonObject(emptyMap()),
                    providerMetadata = googlePartMetadata(obj)?.let { ProviderMetadata.Raw(JsonObject(it)) } ?: ProviderMetadata.None,
                )
                content += call
                toolCalls += call
            }
            (JsonAccess.obj(obj, "inlineData"))?.let { data ->
                content += ContentPart.File(
                    mediaType = (data["mimeType"] as? JsonPrimitive)?.contentOrNull ?: "application/octet-stream",
                    base64 = (data["data"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                    providerMetadata = googlePartMetadata(obj)?.let { ProviderMetadata.Raw(JsonObject(it)) } ?: ProviderMetadata.None,
                )
            }
        }
        googleSources(candidate, settings.generateId).forEach { content += it }
        val text = content.filterIsInstance<ContentPart.Text>().joinToString("") { it.text }
        val finish = (candidate["finishReason"] as? JsonPrimitive)?.contentOrNull
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
            providerMetadata = ProviderMetadata.Raw(JsonObject(mapOf("google" to metadata))),
            content = content,
            rawFinishReason = finish,
            warnings = warnings,
            request = LanguageModelRequestMetadata(requestBody),
            response = LanguageModelResponseMetadata(headers = headers, body = rawBody),
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
            // OpenAPI 3.0 has no `const`; map it to a single-value `enum` (enum wins if both present).
            if ("enum" !in obj) obj["const"]?.let { put("enum", JsonArray(listOf(it))) }
            googleSchemaType(obj)?.let { (typeEl, nullable) ->
                typeEl?.let { put("type", it) }
                if (nullable) put("nullable", JsonPrimitive(true))
            }
            (JsonAccess.obj(obj, "properties"))?.let { props ->
                put("properties", buildJsonObject { props.forEach { (k, v) -> put(k, googleSchema(v)) } })
            }
            obj["items"]?.let { put("items", googleSchema(it)) }
            for (combiner in listOf("allOf", "anyOf", "oneOf")) {
                (JsonAccess.arr(obj, combiner))?.let { arr ->
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

    private fun googleThoughtMetadata(metadata: Map<String, JsonElement>?): JsonObject? {
        val google = metadata?.get("google") as? JsonObject ?: return null
        return buildJsonObject {
            google["thought"]?.let { put("thought", it) }
            google["thoughtSignature"]?.let { put("thoughtSignature", it) }
        }.takeIf { it.isNotEmpty() }
    }

    private fun JsonObjectBuilder.putJsonObjectFields(fields: JsonObject, excluded: Set<String> = emptySet()) {
        fields.forEach { (key, value) -> if (value !is JsonNull && key !in excluded) put(key, value) }
    }

    internal companion object {
        private const val GOOGLE_SKIP_THOUGHT_SIGNATURE = "skip_thought_signature_validator"

        private val GOOGLE_SCHEMA_PASSTHROUGH = listOf(
            "description", "required", "format", "enum",
            "minLength", "maxLength", "minItems", "maxItems", "minimum", "maximum",
        )

        internal fun googleUsage(element: JsonElement?): Usage {
            val obj = element as? JsonObject ?: return Usage()
            val prompt = (obj["promptTokenCount"] as? JsonPrimitive)?.intOrNull ?: 0
            val candidates = (obj["candidatesTokenCount"] as? JsonPrimitive)?.intOrNull ?: 0
            val thoughts = (obj["thoughtsTokenCount"] as? JsonPrimitive)?.intOrNull ?: 0
            val cached = (obj["cachedContentTokenCount"] as? JsonPrimitive)?.intOrNull ?: 0
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

        internal fun mapGoogleFinishReason(reason: String?, hasToolCalls: Boolean): FinishReason =
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

        internal fun googlePartMetadata(part: JsonObject): Map<String, JsonElement>? {
            val metadata = buildJsonObject {
                part["thought"]?.let { put("thought", it) }
                part["thoughtSignature"]?.let { put("thoughtSignature", it) }
            }
            return if (metadata.isEmpty()) null else mapOf("google" to metadata)
        }

        internal fun googleSources(candidate: JsonObject, generateId: () -> String): List<ContentPart.Source> {
            val groundingMetadata = JsonAccess.obj(candidate, "groundingMetadata")
            val chunks = (groundingMetadata?.get("groundingChunks") as? JsonArray).orEmpty()
            return chunks.mapNotNull { chunk ->
                val web = ((chunk as? JsonObject)?.get("web") as? JsonObject) ?: return@mapNotNull null
                ContentPart.Source(
                    sourceType = StreamEvent.SourcePart.SourceType.Url,
                    url = (web["uri"] as? JsonPrimitive)?.contentOrNull,
                    title = (web["title"] as? JsonPrimitive)?.contentOrNull,
                    providerMetadata = ProviderMetadata.Raw(JsonObject(mapOf("google" to buildJsonObject {
                        put("id", JsonPrimitive(IdGenerator.generate()))
                        put("groundingChunk", chunk)
                    }))),
                )
            }
        }
    }
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
        value["usageMetadata"]?.let { usage = GoogleGenerativeAILanguageModel.googleUsage(it) }
        val candidate = ((JsonAccess.arr(value, "candidates"))?.firstOrNull() as? JsonObject) ?: return events
        val parts = ((JsonAccess.obj(candidate, "content"))?.get("parts") as? JsonArray).orEmpty()
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
                if ((obj["thought"] as? JsonPrimitive)?.booleanOrNull == true) {
                    if (textId != null) {
                        events += StreamEvent.TextEnd(textId.orEmpty())
                        textId = null
                    }
                    if (reasoningId == null) {
                        reasoningId = (blockCounter++).toString()
                        events += StreamEvent.ReasoningStart(reasoningId.orEmpty(), GoogleGenerativeAILanguageModel.googlePartMetadata(obj)?.let { pm -> ProviderMetadata.Raw(JsonObject(pm)) } ?: ProviderMetadata.None)
                    }
                    events += StreamEvent.ReasoningDelta(reasoningId.orEmpty(), it, GoogleGenerativeAILanguageModel.googlePartMetadata(obj)?.let { pm -> ProviderMetadata.Raw(JsonObject(pm)) } ?: ProviderMetadata.None)
                } else {
                    if (reasoningId != null) {
                        events += StreamEvent.ReasoningEnd(reasoningId.orEmpty())
                        reasoningId = null
                    }
                    if (textId == null) {
                        textId = (blockCounter++).toString()
                        events += StreamEvent.TextStart(textId.orEmpty(), GoogleGenerativeAILanguageModel.googlePartMetadata(obj)?.let { pm -> ProviderMetadata.Raw(JsonObject(pm)) } ?: ProviderMetadata.None)
                    }
                    events += StreamEvent.TextDelta(textId.orEmpty(), it, GoogleGenerativeAILanguageModel.googlePartMetadata(obj)?.let { pm -> ProviderMetadata.Raw(JsonObject(pm)) } ?: ProviderMetadata.None)
                }
            }
            obj["functionCall"]?.let { callElement ->
                val call = try {
                    WireDecoder.objectValue(callElement, "google", "generateContent stream part", "$.candidates[0].content.parts[$index].functionCall")
                } catch (error: WireDecodeException) {
                    return listOf(StreamEvent.Error(error.message ?: "Google stream protocol error"))
                }
                val id = (call["id"] as? JsonPrimitive)?.contentOrNull ?: IdGenerator.generate()
                val name = try {
                    WireDecoder.requiredString(call, "name", "google", "generateContent stream part", "$.candidates[0].content.parts[$index].functionCall")
                } catch (error: WireDecodeException) {
                    return listOf(StreamEvent.Error(error.message ?: "Google stream protocol error"))
                }
                val input = call["args"] ?: JsonObject(emptyMap())
                hasToolCalls = true
                val partMetadata = GoogleGenerativeAILanguageModel.googlePartMetadata(obj)?.let { ProviderMetadata.Raw(JsonObject(it)) } ?: ProviderMetadata.None
                events += StreamEvent.ToolInputStart(id, name, partMetadata)
                events += StreamEvent.ToolInputDelta(id, input.toString(), partMetadata)
                events += StreamEvent.ToolInputEnd(id, partMetadata)
                events += StreamEvent.ToolCall(id, name, input, partMetadata)
            }
            obj["inlineData"]?.let { dataElement ->
                val data = try {
                    WireDecoder.objectValue(dataElement, "google", "generateContent stream part", "$.candidates[0].content.parts[$index].inlineData")
                } catch (error: WireDecodeException) {
                    return listOf(StreamEvent.Error(error.message ?: "Google stream protocol error"))
                }
                events += StreamEvent.FilePart(
                    id = IdGenerator.generate(),
                    mediaType = (data["mimeType"] as? JsonPrimitive)?.contentOrNull ?: "application/octet-stream",
                    base64 = try {
                        WireDecoder.requiredString(data, "data", "google", "generateContent stream part", "$.candidates[0].content.parts[$index].inlineData")
                    } catch (error: WireDecodeException) {
                        return listOf(StreamEvent.Error(error.message ?: "Google stream protocol error"))
                    },
                    providerMetadata = GoogleGenerativeAILanguageModel.googlePartMetadata(obj)?.let { ProviderMetadata.Raw(JsonObject(it)) } ?: ProviderMetadata.None,
                )
            }
        }
        GoogleGenerativeAILanguageModel.googleSources(candidate, generateId).forEach { source ->
            val googleMeta = JsonAccess.obj(source.providerMetadata.toMap(), "google")
            events += StreamEvent.SourcePart(
                id = (googleMeta?.get("id") as? JsonPrimitive)?.contentOrNull
                    ?: IdGenerator.generate(),
                sourceType = source.sourceType,
                url = source.url,
                title = source.title,
                providerMetadata = source.providerMetadata,
            )
        }
        (candidate["finishReason"] as? JsonPrimitive)?.contentOrNull?.let {
            rawFinishReason = it
            finishReason = GoogleGenerativeAILanguageModel.mapGoogleFinishReason(it, hasToolCalls)
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
