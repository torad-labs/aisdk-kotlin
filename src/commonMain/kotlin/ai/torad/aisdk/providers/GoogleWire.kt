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

internal data class GooglePreparedRequest(
    val body: JsonObject,
    val warnings: List<CallWarning>,
)

internal data class GoogleConvertedMessages(
    val contents: JsonArray,
    val systemInstruction: JsonObject?,
    val warnings: List<CallWarning>,
)

// Wire conversion for the Google generateContent (Gemini) family: request body
// assembly, message/content/tool mapping, JSON-Schema -> OpenAPI subset, and
// response/usage/finish-reason decoding. Extracted verbatim from GoogleProvider.kt.
internal object GoogleWire {
    fun googleGenerateContentBody(
    modelId: String,
    settings: GoogleGenerativeAIProviderSettings,
    params: LanguageModelCallParams,
    stream: Boolean,
): GooglePreparedRequest {
    val warnings = mutableListOf<CallWarning>()
    val options = params.providerOptions.toMap()["google"] as? JsonObject ?: JsonObject(emptyMap())
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

    fun googleMessages(messages: List<ModelMessage>, isGemini3: Boolean = false): GoogleConvertedMessages {
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

    fun googleContentPart(part: ContentPart): JsonElement? = when (part) {
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

private const val GOOGLE_SKIP_THOUGHT_SIGNATURE = "skip_thought_signature_validator"

    fun googleAssistantPart(part: ContentPart, isGemini3: Boolean = false): JsonElement? = when (part) {
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
        val sig = (part.providerMetadata.toMap()["google"] as? JsonObject)?.get("thoughtSignature")
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
    fun googleToolPart(part: ContentPart): JsonElement? = when (part) {
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

    fun googleToolsJson(
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

    fun googleToolConfig(choice: ToolChoice, options: JsonObject): JsonObject? {
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

    fun googleLanguageResult(
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
            val call = ContentPart.ToolCall(id, "code_execution", code, providerMetadata = ProviderMetadata.Raw(JsonObject(mapOf("google" to buildJsonObject { put("providerExecuted", JsonPrimitive(true)) }))))
            content += call
            toolCalls += call
        }
        obj["codeExecutionResult"]?.jsonObject?.let { result ->
            content += ContentPart.ToolResult(lastCodeExecutionId ?: settings.generateId(), "code_execution", result, providerMetadata = ProviderMetadata.Raw(JsonObject(mapOf("google" to buildJsonObject { put("providerExecuted", JsonPrimitive(true)) }))))
            lastCodeExecutionId = null
        }
        obj["text"]?.jsonPrimitive?.contentOrNull?.let { text ->
            val metadata = googlePartMetadata(obj)?.let { ProviderMetadata.Raw(JsonObject(it)) } ?: ProviderMetadata.None
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
                providerMetadata = googlePartMetadata(obj)?.let { ProviderMetadata.Raw(JsonObject(it)) } ?: ProviderMetadata.None,
            )
            content += call
            toolCalls += call
        }
        obj["inlineData"]?.jsonObject?.let { data ->
            content += ContentPart.File(
                mediaType = data["mimeType"]?.jsonPrimitive?.contentOrNull ?: "application/octet-stream",
                base64 = data["data"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                providerMetadata = googlePartMetadata(obj)?.let { ProviderMetadata.Raw(JsonObject(it)) } ?: ProviderMetadata.None,
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
        providerMetadata = ProviderMetadata.Raw(JsonObject(mapOf("google" to metadata))),
        content = content,
        rawFinishReason = finish,
        warnings = warnings,
        request = LanguageModelRequestMetadata(requestBody),
        response = LanguageModelResponseMetadata(headers = headers, body = rawBody),
    )
}
    fun googleUsage(element: JsonElement?): Usage {
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
    fun googleSchema(element: JsonElement): JsonElement {
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
    fun googleSchemaType(obj: JsonObject): Pair<JsonElement?, Boolean>? {
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

    fun mapGoogleFinishReason(reason: String?, hasToolCalls: Boolean): FinishReason =
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

    fun googlePartMetadata(part: JsonObject): Map<String, JsonElement>? {
    val metadata = buildJsonObject {
        part["thought"]?.let { put("thought", it) }
        part["thoughtSignature"]?.let { put("thoughtSignature", it) }
    }
    return if (metadata.isEmpty()) null else mapOf("google" to metadata)
}

    fun googleThoughtMetadata(metadata: Map<String, JsonElement>?): JsonObject? {
    val google = metadata?.get("google") as? JsonObject ?: return null
    return buildJsonObject {
        google["thought"]?.let { put("thought", it) }
        google["thoughtSignature"]?.let { put("thoughtSignature", it) }
    }.takeIf { it.isNotEmpty() }
}

    fun googleSources(candidate: JsonObject, generateId: () -> String): List<ContentPart.Source> {
    val chunks = candidate["groundingMetadata"]?.jsonObject?.get("groundingChunks")?.jsonArray.orEmpty()
    return chunks.mapNotNull { chunk ->
        val web = chunk.jsonObject["web"]?.jsonObject ?: return@mapNotNull null
        ContentPart.Source(
            sourceType = StreamEvent.SourcePart.SourceType.Url,
            url = web["uri"]?.jsonPrimitive?.contentOrNull,
            title = web["title"]?.jsonPrimitive?.contentOrNull,
            providerMetadata = ProviderMetadata.Raw(JsonObject(mapOf("google" to buildJsonObject {
                put("id", JsonPrimitive(IdGenerator.generate()))
                put("groundingChunk", chunk)
            }))),
        )
    }
}

    fun JsonObjectBuilder.putJsonObjectFields(fields: JsonObject, excluded: Set<String> = emptySet()) {
        fields.forEach { (key, value) -> if (value !is JsonNull && key !in excluded) put(key, value) }
    }

    fun providerTool(
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
}
