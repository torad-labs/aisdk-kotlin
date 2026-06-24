package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
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
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

public const val ANTHROPIC_VERSION: String = "3.0.81"

public typealias AnthropicLanguageModelOptions = JsonObject
public typealias AnthropicProviderOptions = AnthropicLanguageModelOptions
public typealias AnthropicToolOptions = JsonObject
public typealias AnthropicMessageMetadata = JsonObject
public typealias AnthropicUsageIteration = JsonObject

public data class AnthropicProviderSettings(
    val baseURL: String = "https://api.anthropic.com/v1",
    val apiKey: String? = null,
    val authToken: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val requestHeadersProvider: (suspend (url: String, body: String, headers: Map<String, String>) -> Map<String, String>)? = null,
    val buildRequestUrl: ((baseURL: String, modelId: String, isStreaming: Boolean) -> String)? = null,
    val transformRequestBody: ((modelId: String, body: JsonObject, isStreaming: Boolean) -> JsonObject)? = null,
    val supportedUrls: Map<String, List<String>>? = null,
    val generateId: () -> String = { IdGenerator.generate() },
    val name: String = "anthropic.messages",
) {
    internal fun anthropicHeaders(
        extra: Map<String, String>,
        betas: Set<String>,
    ): Map<String, String> {
        val headers = linkedMapOf<String, String?>()
        headers["anthropic-version"] = "2023-06-01"
        authToken?.takeIf { it.isNotBlank() }?.let { headers[HttpHeaders.Authorization] = "Bearer $it" }
            ?: apiKey?.takeIf { it.isNotBlank() }?.let { headers["x-api-key"] = it }
        headers.putAll(this.headers)
        headers.putAll(extra)
        if (betas.isNotEmpty()) headers["anthropic-beta"] = betas.joinToString(",")
        return ProviderHeaders.withUserAgentSuffix(headers, "ai-sdk/anthropic/$ANTHROPIC_VERSION")
    }

    internal fun anthropicOptions(providerOptions: ProviderOptions): JsonObject {
        val canonical = providerOptions.toMap()["anthropic"] as? JsonObject ?: JsonObject(emptyMap())
        val customName = name.substringBefore('.')
        val custom = if (customName != "anthropic") providerOptions.toMap()[customName] as? JsonObject else null
        return canonical.deepMergedWith(custom ?: JsonObject(emptyMap()))
    }

    private fun JsonObject.deepMergedWith(other: JsonObject): JsonObject {
        val merged = toMutableMap()
        for ((key, value) in other) {
            val prior = merged[key]
            merged[key] = if (prior is JsonObject && value is JsonObject) prior.deepMergedWith(value) else value
        }
        return JsonObject(merged)
    }

    internal fun anthropicCitationSource(citation: JsonObject): ContentPart.Source? =
        when ((citation["type"] as? JsonPrimitive)?.contentOrNull) {
            "web_search_result_location" -> ContentPart.Source(
                sourceType = StreamEvent.SourcePart.SourceType.Url,
                url = (citation["url"] as? JsonPrimitive)?.contentOrNull,
                title = (citation["title"] as? JsonPrimitive)?.contentOrNull,
                providerMetadata = ProviderMetadata.Raw(JsonObject(mapOf("anthropic" to buildJsonObject {
                    citation["cited_text"]?.let { put("citedText", it) }
                    citation["encrypted_index"]?.let { put("encryptedIndex", it) }
                    put("id", JsonPrimitive(generateId()))
                }))),
            )
            "page_location", "char_location" -> ContentPart.Source(
                sourceType = StreamEvent.SourcePart.SourceType.Document,
                title = (citation["document_title"] as? JsonPrimitive)?.contentOrNull,
                providerMetadata = ProviderMetadata.Raw(JsonObject(mapOf("anthropic" to citation))),
            )
            else -> null
        }

    internal companion object {
        internal fun anthropicCacheControl(metadata: Map<String, JsonElement>?): JsonElement? =
            (metadata?.get("anthropic") as? JsonObject)?.get("cacheControl")

        internal fun anthropicFileOptions(metadata: Map<String, JsonElement>?): JsonObject? {
            val options = metadata?.get("anthropic") as? JsonObject ?: return null
            return buildJsonObject {
                (options["citations"] as? JsonObject)?.let { put("citations", it) }
                options["title"]?.let { put("title", it) }
                options["context"]?.let { put("context", it) }
            }.takeIf { it.isNotEmpty() }
        }

        /**
         * The default `max_tokens` for a Claude model when the caller omits maxOutputTokens.
         * Ports upstream's getModelCapabilities table — hardcoding 4096 truncated output on
         * every modern Claude model.
         */
        @Suppress("MagicNumber") // the values ARE the per-model max-output-token limits
        internal fun anthropicMaxOutputTokensForModel(modelId: String): Int = when {
            modelId.contains("claude-opus-4-8") || modelId.contains("claude-opus-4-7") -> 128_000
            modelId.contains("claude-sonnet-4-6") || modelId.contains("claude-opus-4-6") -> 128_000
            modelId.contains("claude-sonnet-4-5") || modelId.contains("claude-opus-4-5") ||
                modelId.contains("claude-haiku-4-5") -> 64_000
            modelId.contains("claude-opus-4-1") -> 32_000
            modelId.contains("claude-sonnet-4-") -> 64_000
            modelId.contains("claude-opus-4-") -> 32_000
            else -> 4_096
        }
    }
}

public class AnthropicProvider(
    private val client: HttpClient,
    public val settings: AnthropicProviderSettings = AnthropicProviderSettings(),
) : Provider {
    init {
        if (!settings.apiKey.isNullOrBlank() && !settings.authToken.isNullOrBlank()) {
            throw InvalidArgumentError("apiKey/authToken", "Both apiKey and authToken were provided. Please use only one authentication method.")
        }
    }

    override val providerId: String = "anthropic"
    public val tools: AnthropicTools = anthropicTools

    public operator fun invoke(modelId: ModelId): LanguageModel = languageModel(modelId.value)
    public fun chat(modelId: ModelId): LanguageModel = languageModel(modelId.value)
    public fun messages(modelId: ModelId): LanguageModel = languageModel(modelId.value)

    override fun languageModel(modelId: String): LanguageModel =
        AnthropicMessagesLanguageModel(client, settings, modelId)

    override fun embeddingModel(modelId: String): EmbeddingModel = throw NoSuchModelError(providerId, "embeddingModel", modelId)
    override fun imageModel(modelId: String): ImageModel = throw NoSuchModelError(providerId, "imageModel", modelId)
    public fun textEmbeddingModel(modelId: String): Nothing = throw NoSuchModelError(providerId, "embeddingModel", modelId)
}

/** PascalCase factory — mirrors the reference `OpenAI(...)` faux-constructor. */
public fun Anthropic(
    client: HttpClient,
    settings: AnthropicProviderSettings = AnthropicProviderSettings(),
): AnthropicProvider = AnthropicProvider(client, settings)

public class AnthropicMessagesLanguageModel(
    private val client: HttpClient,
    private val settings: AnthropicProviderSettings,
    override val modelId: String,
) : LanguageModel {
    override val provider: String = settings.name
    override val supportedUrls: Map<String, List<String>> = settings.supportedUrls ?: mapOf(
        "image/*" to listOf("^https://.*$"),
        "application/pdf" to listOf("^https://.*$"),
    )

    override suspend fun generate(params: LanguageModelCallParams): LanguageModelResult {
        val prepared = PreparedAnthropicRequest.anthropicRequestBody(settings, modelId, params, stream = false)
        val response = anthropicPost(prepared.body, prepared.betas, params.headers)
        return anthropicGenerateResult(
            response = response.value.jsonObject,
            requestBody = prepared.body,
            responseHeaders = response.headers,
            responseBody = response.value,
            warnings = prepared.warnings,
            settings = settings,
            json = aiSdkJson,
        )
    }

    override fun stream(params: LanguageModelCallParams): Flow<StreamEvent> = flow {
        val prepared = PreparedAnthropicRequest.anthropicRequestBody(settings, modelId, params, stream = true)
        emit(StreamEvent.StreamStart(prepared.warnings))
        val state = AnthropicStreamState(settings, aiSdkJson)
        var sseHeaders: Map<String, String> = emptyMap()
        val rawLines = anthropicStreamSse(prepared.body, prepared.betas, params.headers) { sseHeaders = it }
        with(HttpTransport) {
            forwardSseEvents(
                events = EventStreamParser.parse(rawLines, Schemas.jsonSchema<JsonElement>(JsonObject(emptyMap())), aiSdkJson),
                capturedHeaders = { sseHeaders },
                parseErrorPrefix = "Failed to parse Anthropic stream event",
                onEvent = { state.accept(it).forEach { e -> emit(e) } },
            )
        }
        state.finish().forEach { emit(it) }
    }

    override fun streamResult(params: LanguageModelCallParams): LanguageModelStreamResult {
        val prepared = PreparedAnthropicRequest.anthropicRequestBody(settings, modelId, params, stream = true)
        return LanguageModelStreamResult(stream = stream(params), request = LanguageModelRequestMetadata(prepared.body))
    }

    private suspend fun anthropicPost(
        body: JsonObject,
        betas: Set<String>,
        extraHeaders: Map<String, String>,
    ): HttpJsonResponse {
        val baseURL = settings.baseURL.trimEnd('/')
        val url = settings.buildRequestUrl?.invoke(baseURL, modelId, false) ?: "$baseURL/messages"
        val requestBody = settings.transformRequestBody?.invoke(modelId, body, false) ?: body
        val encodedBody = aiSdkOutputJson.encodeToString(JsonElement.serializer(), requestBody)
        val baseHeaders = settings.anthropicHeaders(extraHeaders, betas)
        val requestHeaders = settings.requestHeadersProvider?.invoke(url, encodedBody, baseHeaders) ?: baseHeaders
        val response = client.request(url) {
            method = HttpMethod.Post
            contentType(ContentType.Application.Json)
            requestHeaders.forEach { (name, value) -> header(name, value) }
            setBody(encodedBody)
        }
        return with(HttpTransport) { response.toJsonResponse(
            url = url,
            parseJson = true,
            requestBodyValues = requestBody,
            errorMessage = { _, parsed, raw -> anthropicErrorMessage(parsed, raw) },
        ) }
    }

    /** Streaming counterpart of [anthropicPost]: same URL/header/transform path,
     *  but reads the SSE body incrementally off the wire (see `streamSse`). */
    private fun anthropicStreamSse(
        body: JsonObject,
        betas: Set<String>,
        extraHeaders: Map<String, String>,
        onResponse: suspend (Map<String, String>) -> Unit,
    ): Flow<String> = flow {
        val baseURL = settings.baseURL.trimEnd('/')
        val url = settings.buildRequestUrl?.invoke(baseURL, modelId, true) ?: "$baseURL/messages"
        val requestBody = settings.transformRequestBody?.invoke(modelId, body, true) ?: body
        val encodedBody = aiSdkOutputJson.encodeToString(JsonElement.serializer(), requestBody)
        val baseHeaders = settings.anthropicHeaders(extraHeaders, betas)
        val requestHeaders = settings.requestHeadersProvider?.invoke(url, encodedBody, baseHeaders) ?: baseHeaders
        emitAll(
            HttpTransport.streamSse(client = client,
            url = url,
            method = HttpMethod.Post,
            headers = requestHeaders + (HttpHeaders.Accept to "text/event-stream"),
            body = requestBody,
            json = aiSdkJson,
            requestBodyValues = requestBody,
            errorMessage = { _, parsed, raw -> anthropicErrorMessage(parsed, raw) },
            onResponse = onResponse,),
        )
    }

    private fun anthropicGenerateResult(
        response: JsonObject,
        requestBody: JsonElement,
        responseHeaders: Map<String, String>,
        responseBody: JsonElement,
        warnings: List<CallWarning>,
        settings: AnthropicProviderSettings,
        json: Json,
    ): LanguageModelResult {
        val content = mutableListOf<ContentPart>()
        val toolCalls = mutableListOf<ContentPart.ToolCall>()
        (response["content"] as? JsonArray).orEmpty().forEachIndexed { index, part ->
            val obj = part as? JsonObject ?: return@forEachIndexed
            val path = "$.content[$index]"
            when ((obj["type"] as? JsonPrimitive)?.contentOrNull) {
                "text" -> {
                    (obj["text"] as? JsonPrimitive)?.contentOrNull?.let { text -> content += ContentPart.Text(text) }
                    for (citation in (obj["citations"] as? JsonArray).orEmpty()) {
                        val citationObj = citation as? JsonObject ?: continue
                        settings.anthropicCitationSource(citationObj)?.let { content += it }
                    }
                }
                "thinking" -> content += ContentPart.Reasoning(
                    text = (obj["thinking"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                    providerMetadata = ProviderMetadata.Raw(JsonObject(mapOf("anthropic" to buildJsonObject {
                        obj["signature"]?.let { put("signature", it) }
                    }))),
                )
                "redacted_thinking" -> content += ContentPart.Reasoning(
                    text = "",
                    providerMetadata = ProviderMetadata.Raw(JsonObject(mapOf("anthropic" to buildJsonObject {
                        obj["data"]?.let { put("redactedData", it) }
                    }))),
                )
                "tool_use", "server_tool_use", "mcp_tool_use" -> {
                    val toolCallId = WireDecoder.requiredString(obj, "id", "anthropic", "response content", path)
                    if (toolCallId.isBlank()) {
                        WireDecoder.fail(
                            "anthropic",
                            "response content",
                            WireDecoder.child(path, "id"),
                            "expected non-blank string",
                            obj["id"],
                        )
                    }
                    val toolName = WireDecoder.requiredString(obj, "name", "anthropic", "response content", path)
                    if (toolName.isBlank()) {
                        WireDecoder.fail(
                            "anthropic",
                            "response content",
                            WireDecoder.child(path, "name"),
                            "expected non-blank string",
                            obj["name"],
                        )
                    }
                    val toolCall = ContentPart.ToolCall(
                        toolCallId = toolCallId,
                        toolName = toolName,
                        input = obj["input"] ?: JsonObject(emptyMap()),
                        providerMetadata = if ((obj["type"] as? JsonPrimitive)?.contentOrNull != "tool_use") {
                            ProviderMetadata.Raw(JsonObject(mapOf("anthropic" to buildJsonObject { put("providerExecuted", JsonPrimitive(true)) })))
                        } else {
                            ProviderMetadata.None
                        },
                    )
                    toolCalls += toolCall
                    content += toolCall
                }
                "mcp_tool_result", "web_search_tool_result", "web_fetch_tool_result", "code_execution_tool_result" -> {
                    val toolCallId = WireDecoder.optionalString(obj, "tool_use_id", "anthropic", "response content", path)
                        ?.takeIf { it.isNotBlank() }
                        ?: WireDecoder.optionalString(obj, "id", "anthropic", "response content", path)
                            ?.takeIf { it.isNotBlank() }
                        ?: WireDecoder.fail(
                            "anthropic",
                            "response content",
                            path,
                            "missing non-blank required field: tool_use_id or id",
                            obj,
                        )
                    val toolName = WireDecoder.requiredString(obj, "name", "anthropic", "response content", path)
                    if (toolName.isBlank()) {
                        WireDecoder.fail(
                            "anthropic",
                            "response content",
                            WireDecoder.child(path, "name"),
                            "expected non-blank string",
                            obj["name"],
                        )
                    }
                    content += ContentPart.ToolResult(
                        toolCallId = toolCallId,
                        toolName = toolName,
                        output = obj["content"] ?: obj,
                        providerMetadata = ProviderMetadata.Raw(JsonObject(mapOf("anthropic" to obj))),
                    )
                }
            }
        }

        val stopReason = (response["stop_reason"] as? JsonPrimitive)?.contentOrNull
        val usage = Usage.fromAnthropic(response["usage"])
        val text = content.filterIsInstance<ContentPart.Text>().joinToString("") { it.text }
        val metadata = buildJsonObject {
            response["usage"]?.let { put("usage", it) }
            put("cacheCreationInputTokens", JsonPrimitive(usage.inputTokens.cacheWrite))
            response["stop_sequence"]?.let { put("stopSequence", it) }
            response["container"]?.let { put("container", PreparedAnthropicRequest.camelToSnakeJson(it)) }
            response["context_management"]?.let { put("contextManagement", it) }
        }
        return LanguageModelResult(
            text = text,
            toolCalls = toolCalls,
            finishReason = FinishReason.fromAnthropicStopReason(stopReason),
            usage = usage,
            providerMetadata = ProviderMetadata.Raw(JsonObject(mapOf("anthropic" to metadata))),
            content = content,
            rawFinishReason = stopReason,
            warnings = warnings,
            request = LanguageModelRequestMetadata(body = requestBody),
            response = LanguageModelResponseMetadata(
                id = (response["id"] as? JsonPrimitive)?.contentOrNull,
                modelId = (response["model"] as? JsonPrimitive)?.contentOrNull,
                headers = responseHeaders,
                body = responseBody,
            ),
        )
    }

    public companion object {
        public fun forwardAnthropicContainerIdFromLastStep(
            steps: List<Map<String, JsonElement>>,
        ): Map<String, JsonElement>? {
            for (step in steps.asReversed()) {
                val containerObj = (step["anthropic"] as? JsonObject)?.get("container") as? JsonObject
                val idElement = containerObj?.get("id")
                val containerId = (idElement as? JsonPrimitive)?.contentOrNull
                if (!containerId.isNullOrBlank()) {
                    return mapOf(
                        "anthropic" to buildJsonObject {
                            put("container", buildJsonObject { put("id", JsonPrimitive(containerId)) })
                        },
                    )
                }
            }
            return null
        }

        internal fun anthropicErrorMessage(parsed: JsonElement?, raw: String): String {
            val obj = parsed as? JsonObject ?: return raw
            val error = obj["error"] as? JsonObject
            return (error?.get("message") as? JsonPrimitive)?.contentOrNull
                ?: (error?.get("type") as? JsonPrimitive)?.contentOrNull
                ?: (obj["message"] as? JsonPrimitive)?.contentOrNull
                ?: raw
        }
    }
}

public data class AnthropicTools(
    val advisor_20260301: Tool<JsonElement, JsonElement, Any?> =
        anthropicProviderTool("advisor", "anthropic.advisor_20260301", "Consult an Anthropic advisor model during generation."),
    val bash_20241022: Tool<JsonElement, JsonElement, Any?> =
        anthropicProviderTool("bash", "anthropic.bash_20241022", "Use Anthropic's hosted Bash tool."),
    val bash_20250124: Tool<JsonElement, JsonElement, Any?> =
        anthropicProviderTool("bash", "anthropic.bash_20250124", "Use Anthropic's hosted Bash tool."),
    val codeExecution_20250522: Tool<JsonElement, JsonElement, Any?> =
        anthropicProviderTool("code_execution", "anthropic.code_execution_20250522", "Use Anthropic hosted code execution."),
    val codeExecution_20250825: Tool<JsonElement, JsonElement, Any?> =
        anthropicProviderTool("code_execution", "anthropic.code_execution_20250825", "Use Anthropic hosted code execution."),
    val codeExecution_20260120: Tool<JsonElement, JsonElement, Any?> =
        anthropicProviderTool("code_execution", "anthropic.code_execution_20260120", "Use Anthropic hosted code execution."),
    val computer_20241022: Tool<JsonElement, JsonElement, Any?> =
        anthropicProviderTool("computer", "anthropic.computer_20241022", "Use Anthropic computer control."),
    val computer_20250124: Tool<JsonElement, JsonElement, Any?> =
        anthropicProviderTool("computer", "anthropic.computer_20250124", "Use Anthropic computer control."),
    val computer_20251124: Tool<JsonElement, JsonElement, Any?> =
        anthropicProviderTool("computer", "anthropic.computer_20251124", "Use Anthropic computer control with zoom."),
    val memory_20250818: Tool<JsonElement, JsonElement, Any?> =
        anthropicProviderTool("memory", "anthropic.memory_20250818", "Use Anthropic memory."),
    val textEditor_20241022: Tool<JsonElement, JsonElement, Any?> =
        anthropicProviderTool("str_replace_editor", "anthropic.text_editor_20241022", "Use Anthropic text editor."),
    val textEditor_20250124: Tool<JsonElement, JsonElement, Any?> =
        anthropicProviderTool("str_replace_editor", "anthropic.text_editor_20250124", "Use Anthropic text editor."),
    val textEditor_20250429: Tool<JsonElement, JsonElement, Any?> =
        anthropicProviderTool("str_replace_based_edit_tool", "anthropic.text_editor_20250429", "Use Anthropic text editor."),
    val textEditor_20250728: Tool<JsonElement, JsonElement, Any?> =
        anthropicProviderTool("str_replace_based_edit_tool", "anthropic.text_editor_20250728", "Use Anthropic text editor."),
    val webFetch_20250910: Tool<JsonElement, JsonElement, Any?> =
        anthropicProviderTool("web_fetch", "anthropic.web_fetch_20250910", "Fetch web content through Anthropic."),
    val webFetch_20260209: Tool<JsonElement, JsonElement, Any?> =
        anthropicProviderTool("web_fetch", "anthropic.web_fetch_20260209", "Fetch web content through Anthropic."),
    val webSearch_20250305: Tool<JsonElement, JsonElement, Any?> =
        anthropicProviderTool("web_search", "anthropic.web_search_20250305", "Search the web through Anthropic."),
    val webSearch_20260209: Tool<JsonElement, JsonElement, Any?> =
        anthropicProviderTool("web_search", "anthropic.web_search_20260209", "Search the web through Anthropic."),
    val toolSearchRegex_20251119: Tool<JsonElement, JsonElement, Any?> =
        anthropicProviderTool("tool_search_tool_regex", "anthropic.tool_search_regex_20251119", "Search deferred tools with regex."),
    val toolSearchBm25_20251119: Tool<JsonElement, JsonElement, Any?> =
        anthropicProviderTool("tool_search_tool_bm25", "anthropic.tool_search_bm25_20251119", "Search deferred tools with BM25."),
) {
    internal companion object {
        internal fun anthropicPrepareTools(
            tools: List<LanguageModelTool>,
            choice: ToolChoice,
            options: JsonObject,
            responseFormat: ResponseFormat,
        ): PreparedAnthropicTools {
            if (choice == ToolChoice.None) return PreparedAnthropicTools(null, null, emptyList(), emptySet())
            val warnings = mutableListOf<CallWarning>()
            val betas = linkedSetOf<String>()
            val disableParallel = (options["disableParallelToolUse"] as? JsonPrimitive)?.booleanOrNull
            val toolStreaming = (options["toolStreaming"] as? JsonPrimitive)?.booleanOrNull ?: true
            val prepared = mutableListOf<JsonElement>()

            for (tool in tools) {
                if (tool.providerExecuted) {
                    val mapped = anthropicProviderExecutedTool(tool.name, betas)
                    if (mapped == null) warnings += CallWarning("unsupported", "provider-defined tool ${tool.name}") else prepared += mapped
                } else {
                    prepared += buildJsonObject {
                        put("name", JsonPrimitive(tool.name))
                        put("description", JsonPrimitive(tool.description))
                        put("input_schema", aiSdkJson.parseToJsonElement(tool.parametersSchemaJson))
                        if (toolStreaming) put("eager_input_streaming", JsonPrimitive(true))
                        put("strict", JsonPrimitive(tool.strict))
                    }
                    betas += "structured-outputs-2025-11-13"
                }
            }

            if (responseFormat is ResponseFormat.Json && responseFormat.schemaJson != null) {
                // Native output_config handles structured output; no JSON tool fallback needed for the KMP facade.
            }

            val toolChoice = when (choice) {
                ToolChoice.Auto -> if (prepared.isEmpty() && disableParallel != true) null else buildJsonObject {
                    put("type", JsonPrimitive("auto"))
                    disableParallel?.let { put("disable_parallel_tool_use", JsonPrimitive(it)) }
                }
                ToolChoice.Required -> buildJsonObject {
                    put("type", JsonPrimitive("any"))
                    disableParallel?.let { put("disable_parallel_tool_use", JsonPrimitive(it)) }
                }
                is ToolChoice.Specific -> buildJsonObject {
                    put("type", JsonPrimitive("tool"))
                    put("name", JsonPrimitive(choice.toolName))
                    disableParallel?.let { put("disable_parallel_tool_use", JsonPrimitive(it)) }
                }
                ToolChoice.None -> null
            }

            return PreparedAnthropicTools(
                tools = prepared.takeIf { it.isNotEmpty() }?.let(::JsonArray),
                toolChoice = toolChoice,
                warnings = warnings,
                betas = betas,
            )
        }

        internal fun anthropicProviderExecutedTool(name: String, betas: MutableSet<String>): JsonObject? = when (name) {
            "code_execution" -> buildJsonObject { put("type", JsonPrimitive("code_execution_20260120")); put("name", JsonPrimitive("code_execution")) }
            "bash" -> {
                betas += "computer-use-2025-01-24"
                buildJsonObject { put("type", JsonPrimitive("bash_20250124")); put("name", JsonPrimitive("bash")) }
            }
            "computer" -> {
                betas += "computer-use-2025-11-24"
                buildJsonObject { put("type", JsonPrimitive("computer_20251124")); put("name", JsonPrimitive("computer")) }
            }
            "memory" -> {
                betas += "context-management-2025-06-27"
                buildJsonObject { put("type", JsonPrimitive("memory_20250818")); put("name", JsonPrimitive("memory")) }
            }
            "web_search" -> {
                betas += "code-execution-web-tools-2026-02-09"
                buildJsonObject { put("type", JsonPrimitive("web_search_20260209")); put("name", JsonPrimitive("web_search")) }
            }
            "web_fetch" -> {
                betas += "code-execution-web-tools-2026-02-09"
                buildJsonObject { put("type", JsonPrimitive("web_fetch_20260209")); put("name", JsonPrimitive("web_fetch")) }
            }
            "str_replace_editor", "str_replace_based_edit_tool" -> buildJsonObject {
                put("type", JsonPrimitive("text_editor_20250728"))
                put("name", JsonPrimitive("str_replace_based_edit_tool"))
            }
            "tool_search_tool_regex" -> buildJsonObject { put("type", JsonPrimitive("tool_search_tool_regex_20251119")); put("name", JsonPrimitive("tool_search_tool_regex")) }
            "tool_search_tool_bm25" -> buildJsonObject { put("type", JsonPrimitive("tool_search_tool_bm25_20251119")); put("name", JsonPrimitive("tool_search_tool_bm25")) }
            "advisor" -> {
                betas += "advisor-tool-2026-03-01"
                buildJsonObject { put("type", JsonPrimitive("advisor_20260301")); put("name", JsonPrimitive("advisor")) }
            }
            else -> null
        }

        internal fun anthropicProviderTool(
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
}

public val anthropicTools: AnthropicTools = AnthropicTools()

internal data class PreparedAnthropicRequest(
    val body: JsonObject,
    val warnings: List<CallWarning>,
    val betas: Set<String>,
) {
    internal companion object {
        internal fun anthropicRequestBody(
            settings: AnthropicProviderSettings,
            modelId: String,
            params: LanguageModelCallParams,
            stream: Boolean,
        ): PreparedAnthropicRequest {
            val warnings = mutableListOf<CallWarning>()
            if (params.frequencyPenalty != null) warnings += CallWarning("unsupported", "frequencyPenalty")
            if (params.presencePenalty != null) warnings += CallWarning("unsupported", "presencePenalty")
            if (params.seed != null) warnings += CallWarning("unsupported", "seed")

            val options = settings.anthropicOptions(params.providerOptions)
            val betas = linkedSetOf<String>()
            (options["anthropicBeta"] as? JsonArray)?.forEach { (it as? JsonPrimitive)?.contentOrNull?.let(betas::add) }
            val sendReasoning = (options["sendReasoning"] as? JsonPrimitive)?.booleanOrNull ?: true
            val prompt = AnthropicPrompt.anthropicPrompt(params.messages, sendReasoning)
            betas += prompt.betas

            val thinking = options["thinking"] as? JsonObject
            val thinkingType = (thinking?.get("type") as? JsonPrimitive)?.contentOrNull
            val isThinking = thinkingType == "enabled" || thinkingType == "adaptive"
            val rawThinkingBudget = (thinking?.get("budgetTokens") as? JsonPrimitive)?.intOrNull
            val maxTokensBase = params.maxOutputTokens ?: AnthropicProviderSettings.anthropicMaxOutputTokensForModel(modelId)
            // `thinkingBudget` is the effective budget (defaulting to 1024 only when thinking is
            // explicitly enabled and the caller omitted it); `maxTokens` folds it into the base.
            val thinkingBudget: Int?
            val maxTokens: Int
            if (isThinking && thinkingType == "enabled") {
                val budget = rawThinkingBudget ?: run {
                    warnings += CallWarning("compatibility", "thinking budget is required when thinking is enabled. using default budget of 1024 tokens.")
                    1024
                }
                thinkingBudget = budget
                maxTokens = maxTokensBase + budget
            } else {
                thinkingBudget = rawThinkingBudget
                maxTokens = maxTokensBase
            }

            val temperature = params.temperature?.coerceIn(0f, 1f)?.also {
                if (params.temperature != it) warnings += CallWarning("unsupported", "temperature")
            }
            val topP = if (isThinking) {
                if (params.topP != null) warnings += CallWarning("unsupported", "topP")
                null
            } else if (temperature != null && params.topP != null && modelId.startsWith("claude-")) {
                warnings += CallWarning("unsupported", "topP")
                null
            } else {
                params.topP
            }
            val topK = if (isThinking) {
                if (params.topK != null) warnings += CallWarning("unsupported", "topK")
                null
            } else {
                params.topK
            }
            val finalTemperature = if (isThinking) {
                if (temperature != null) warnings += CallWarning("unsupported", "temperature")
                null
            } else {
                temperature
            }

            val preparedTools = AnthropicTools.anthropicPrepareTools(params.tools, params.toolChoice, options, params.responseFormat)
            warnings += preparedTools.warnings
            betas += preparedTools.betas
            val outputConfig = anthropicOutputConfig(options, params.responseFormat)
            if (outputConfig != null) betas += "structured-outputs-2025-11-13"

            return PreparedAnthropicRequest(
                body = buildJsonObject {
                    put("model", JsonPrimitive(modelId))
                    put("max_tokens", JsonPrimitive(maxTokens))
                    finalTemperature?.let { put("temperature", JsonPrimitive(it)) }
                    topK?.let { put("top_k", JsonPrimitive(it)) }
                    topP?.let { put("top_p", JsonPrimitive(it)) }
                    if (params.stopSequences.isNotEmpty()) put("stop_sequences", JsonArray(params.stopSequences.map(::JsonPrimitive)))
                    if (isThinking) {
                        put(
                            "thinking",
                            buildJsonObject {
                                put("type", JsonPrimitive(thinkingType))
                                thinkingBudget?.let { put("budget_tokens", JsonPrimitive(it)) }
                                thinking["display"]?.let { put("display", it) }
                            },
                        )
                    }
                    outputConfig?.let { put("output_config", it) }
                    options["speed"]?.let { put("speed", it) }
                    options["inferenceGeo"]?.let { put("inference_geo", it) }
                    options["cacheControl"]?.let { put("cache_control", it) }
                    anthropicMetadata(options)?.let { put("metadata", it) }
                    anthropicMcpServers(options)?.let {
                        put("mcp_servers", it)
                        betas += "mcp-client-2025-04-04"
                    }
                    anthropicContainer(options)?.let { container ->
                        put("container", container)
                        if (container is JsonObject && container["skills"] != null) {
                            betas += setOf("code-execution-2025-08-25", "skills-2025-10-02", "files-api-2025-04-14")
                        }
                    }
                    options["contextManagement"]?.let {
                        put("context_management", camelToSnakeJson(it))
                        betas += "context-management-2025-06-27"
                    }
                    prompt.system?.let { put("system", it) }
                    put("messages", prompt.messages)
                    preparedTools.tools?.let { put("tools", it) }
                    preparedTools.toolChoice?.let { put("tool_choice", it) }
                    if (stream) put("stream", JsonPrimitive(true))
                },
                warnings = warnings,
                betas = betas,
            )
        }

        private fun anthropicOutputConfig(options: JsonObject, responseFormat: ResponseFormat): JsonObject? {
            val fields = linkedMapOf<String, JsonElement>()
            options["effort"]?.let { fields["effort"] = it }
            options["taskBudget"]?.let { fields["task_budget"] = camelToSnakeJson(it) }
            if (responseFormat is ResponseFormat.Json && responseFormat.schemaJson != null) {
                fields["format"] = buildJsonObject {
                    put("type", JsonPrimitive("json_schema"))
                    put("schema", responseFormat.schemaJson)
                }
            }
            return fields.takeIf { it.isNotEmpty() }?.let(::JsonObject)
        }

        private fun anthropicMetadata(options: JsonObject): JsonObject? {
            val metadata = options["metadata"] as? JsonObject ?: return null
            val userId = metadata["userId"] ?: return null
            return buildJsonObject { put("user_id", userId) }
        }

        private fun anthropicMcpServers(options: JsonObject): JsonArray? {
            val servers = options["mcpServers"] as? JsonArray ?: return null
            if (servers.isEmpty()) return null
            return JsonArray(servers.mapNotNull { server ->
                val obj = server as? JsonObject ?: return@mapNotNull null
                buildJsonObject {
                    put("type", obj["type"] ?: JsonPrimitive("url"))
                    put("name", obj["name"] ?: JsonPrimitive(""))
                    put("url", obj["url"] ?: JsonPrimitive(""))
                    obj["authorizationToken"]?.let { put("authorization_token", it) }
                    (obj["toolConfiguration"] as? JsonObject)?.let { put("tool_configuration", camelToSnakeJson(it)) }
                }
            })
        }

        private fun anthropicContainer(options: JsonObject): JsonElement? {
            val container = options["container"] as? JsonObject ?: return null
            val skills = container["skills"] as? JsonArray
            return if (skills != null && skills.isNotEmpty()) {
                buildJsonObject {
                    container["id"]?.let { put("id", it) }
                    put("skills", JsonArray(skills.map { skill -> camelToSnakeJson(skill) }))
                }
            } else {
                container["id"]
            }
        }

        internal fun camelToSnakeJson(element: JsonElement): JsonElement = when (element) {
            is JsonObject -> JsonObject(element.mapKeys { camelToSnake(it.key) }.mapValues { camelToSnakeJson(it.value) })
            is JsonArray -> JsonArray(element.map(::camelToSnakeJson))
            else -> element
        }

        internal fun camelToSnake(value: String): String =
            value.replace(Regex("([a-z0-9])([A-Z])")) { "${it.groupValues[1]}_${it.groupValues[2].lowercase()}" }
    }
}

internal data class AnthropicPrompt(
    val system: JsonArray?,
    val messages: JsonArray,
    val betas: Set<String>,
) {
    internal companion object {
        internal fun anthropicPrompt(
            messages: List<ModelMessage>,
            sendReasoning: Boolean,
        ): AnthropicPrompt {
            val system = mutableListOf<JsonElement>()
            val apiMessages = mutableListOf<JsonElement>()
            val betas = linkedSetOf<String>()

            for (message in messages) {
                when (message.role) {
                    MessageRole.System -> system += message.content.mapNotNull { part ->
                        (part as? ContentPart.Text)?.let {
                            buildJsonObject {
                                put("type", JsonPrimitive("text"))
                                put("text", JsonPrimitive(it.text))
                                AnthropicProviderSettings.anthropicCacheControl(it.providerMetadata.toMap())?.let { cache -> put("cache_control", cache) }
                            }
                        }
                    }
                    MessageRole.User -> apiMessages += buildJsonObject {
                        put("role", JsonPrimitive("user"))
                        put("content", JsonArray(message.content.mapNotNull { anthropicUserPart(it, betas) }))
                    }
                    MessageRole.Assistant -> {
                        // Anthropic rejects trailing whitespace in a pre-filled assistant turn, so
                        // trim the LAST text part of the LAST message (the pre-fill), per upstream.
                        val isLastMessage = message === messages.last()
                        val lastTextIndex =
                            if (isLastMessage) message.content.indexOfLast { it is ContentPart.Text } else -1
                        val content = message.content.mapIndexedNotNull { index, part ->
                            anthropicAssistantPart(part, sendReasoning, currentIndex = index, lastTextIndex = lastTextIndex)
                        }
                        if (content.isNotEmpty()) {
                            apiMessages += buildJsonObject {
                                put("role", JsonPrimitive("assistant"))
                                put("content", JsonArray(content))
                            }
                        }
                    }
                    MessageRole.Tool -> {
                        val content = message.content.filterIsInstance<ContentPart.ToolResult>().map { result ->
                            buildJsonObject {
                                put("type", JsonPrimitive("tool_result"))
                                put("tool_use_id", JsonPrimitive(result.toolCallId))
                                put("content", anthropicToolResultContent(result))
                                if (result.isError) put("is_error", JsonPrimitive(true))
                            }
                        }
                        if (content.isNotEmpty()) {
                            apiMessages += buildJsonObject {
                                put("role", JsonPrimitive("user"))
                                put("content", JsonArray(content))
                            }
                        }
                    }
                }
            }

            return AnthropicPrompt(
                system = system.takeIf { it.isNotEmpty() }?.let(::JsonArray),
                messages = JsonArray(apiMessages),
                betas = betas,
            )
        }

        private fun anthropicUserPart(part: ContentPart, betas: MutableSet<String>): JsonElement? = when (part) {
            is ContentPart.Text -> buildJsonObject {
                put("type", JsonPrimitive("text"))
                put("text", JsonPrimitive(part.text))
                AnthropicProviderSettings.anthropicCacheControl(part.providerMetadata.toMap())?.let { put("cache_control", it) }
            }
            is ContentPart.Image -> buildJsonObject {
                put("type", JsonPrimitive("image"))
                put("source", anthropicMediaSource(part.url, part.mediaType, part.base64))
            }
            is ContentPart.File -> when {
                part.mediaType.startsWith("image/") -> buildJsonObject {
                    put("type", JsonPrimitive("image"))
                    put("source", anthropicMediaSource(part.url, part.mediaType, part.base64))
                }
                part.mediaType == "application/pdf" -> {
                    betas += "pdfs-2024-09-25"
                    buildJsonObject {
                        put("type", JsonPrimitive("document"))
                        put("source", anthropicMediaSource(part.url, "application/pdf", part.base64))
                        part.filename?.let { put("title", JsonPrimitive(it)) }
                        AnthropicProviderSettings.anthropicFileOptions(part.providerMetadata.toMap())?.let { putJsonObjectFields(it) }
                    }
                }
                part.mediaType == "text/plain" -> buildJsonObject {
                    put("type", JsonPrimitive("document"))
                    put("source", buildJsonObject {
                        put("type", JsonPrimitive("text"))
                        put("media_type", JsonPrimitive("text/plain"))
                        put("data", JsonPrimitive(Base64Codec.decode(part.base64).decodeToString()))
                    })
                    part.filename?.let { put("title", JsonPrimitive(it)) }
                    AnthropicProviderSettings.anthropicFileOptions(part.providerMetadata.toMap())?.let { putJsonObjectFields(it) }
                }
                else -> throw UnsupportedFunctionalityError("file media type ${part.mediaType}", "Unsupported Anthropic file media type: ${part.mediaType}")
            }
            is ContentPart.Reasoning,
            is ContentPart.ToolCall,
            is ContentPart.ToolResult,
            is ContentPart.ToolApprovalRequest,
            is ContentPart.ToolApprovalResponse,
            is ContentPart.Source,
            -> null
        }

        /**
         * Anthropic media source: a remote [url] is sent as a `url` source (Anthropic
         * fetches it); otherwise the inline [base64] bytes are sent. Closes the gap where
         * a ContentPart carrying only a `url` was previously serialized with empty data.
         */
        private fun anthropicMediaSource(url: String?, mediaType: String, base64: String): JsonObject = buildJsonObject {
            if (url != null) {
                put("type", JsonPrimitive("url"))
                put("url", JsonPrimitive(url))
            } else {
                put("type", JsonPrimitive("base64"))
                put("media_type", JsonPrimitive(if (mediaType == "image/*") "image/jpeg" else mediaType))
                put("data", JsonPrimitive(base64))
            }
        }

        private fun anthropicAssistantPart(
            part: ContentPart,
            sendReasoning: Boolean,
            currentIndex: Int = -1,
            lastTextIndex: Int = -1,
        ): JsonElement? = when (part) {
            is ContentPart.Text -> buildJsonObject {
                put("type", JsonPrimitive("text"))
                put("text", JsonPrimitive(if (currentIndex == lastTextIndex && lastTextIndex >= 0) part.text.trim() else part.text))
            }
            is ContentPart.Reasoning -> if (sendReasoning) buildJsonObject {
                val metadata = part.providerMetadata.toMap()["anthropic"] as? JsonObject
                put("type", JsonPrimitive("thinking"))
                put("thinking", JsonPrimitive(part.text))
                metadata?.get("signature")?.let { put("signature", it) }
            } else null
            is ContentPart.ToolCall -> buildJsonObject {
                put("type", JsonPrimitive("tool_use"))
                put("id", JsonPrimitive(part.toolCallId))
                put("name", JsonPrimitive(part.toolName))
                put("input", part.input)
            }
            is ContentPart.ToolResult,
            is ContentPart.ToolApprovalRequest,
            is ContentPart.ToolApprovalResponse,
            is ContentPart.Source,
            is ContentPart.File,
            is ContentPart.Image,
            -> null
        }

        /**
         * Decode [ContentPart.ToolResult.modelVisible] off the wire and render it as an Anthropic
         * `tool_result.content` value. Text/json/error/denied collapse to a string; `Content`
         * (the MCP shape) maps to the native content-block array, preserving image blocks. The old
         * implementation `toString()`'d the raw element, which dropped MCP content/images and leaked
         * the error/denial wrapper objects into the prompt — mirrors OpenResponsesProvider's
         * `openResponsesToolOutput`.
         */
        private fun anthropicToolResultContent(result: ContentPart.ToolResult): JsonElement =
            when (val output = ToolResultOutputs.toolResultOutputFromWire(result.modelVisible)) {
                is ToolResultOutput.Text -> JsonPrimitive(output.text)
                is ToolResultOutput.Error -> JsonPrimitive(output.message)
                is ToolResultOutput.ExecutionDenied -> JsonPrimitive(output.reason ?: "Tool execution denied.")
                is ToolResultOutput.Json -> JsonPrimitive(output.json.toString())
                is ToolResultOutput.ErrorJson -> JsonPrimitive(output.json.toString())
                is ToolResultOutput.Content -> JsonArray(output.value.mapNotNull(::anthropicToolResultContentBlock))
            }

        /** Map one MCP/`Content` item to an Anthropic `tool_result` content block. Anthropic
         *  tool_result content supports text + image blocks only; other item types are skipped. */
        private fun anthropicToolResultContentBlock(item: JsonElement): JsonObject? {
            val obj = item as? JsonObject
            return when ((obj?.get("type") as? JsonPrimitive)?.contentOrNull) {
                "text" -> buildJsonObject {
                    put("type", JsonPrimitive("text"))
                    put("text", obj["text"] ?: JsonPrimitive(""))
                }
                "image-data" -> buildJsonObject {
                    put("type", JsonPrimitive("image"))
                    put(
                        "source",
                        buildJsonObject {
                            put("type", JsonPrimitive("base64"))
                            put("media_type", obj["mediaType"] ?: JsonPrimitive("application/octet-stream"))
                            put("data", obj["data"] ?: JsonPrimitive(""))
                        },
                    )
                }
                "image-url" -> buildJsonObject {
                    put("type", JsonPrimitive("image"))
                    put(
                        "source",
                        buildJsonObject {
                            put("type", JsonPrimitive("url"))
                            put("url", obj["url"] ?: JsonPrimitive(""))
                        },
                    )
                }
                else -> null
            }
        }

        private fun JsonObjectBuilder.putJsonObjectFields(fields: JsonObject) {
            fields.forEach { (key, value) -> if (value !is JsonNull) put(key, value) }
        }
    }
}

internal data class PreparedAnthropicTools(
    val tools: JsonArray?,
    val toolChoice: JsonElement?,
    val warnings: List<CallWarning>,
    val betas: Set<String>,
)

private class AnthropicStreamState(
    private val settings: AnthropicProviderSettings,
    private val json: Json,
) {
    private val blocks = mutableMapOf<Int, AnthropicStreamBlock>()
    private var finishReason = FinishReason.Other
    private var rawStopReason: String? = null
    private var usage = Usage()
    private var responseId: String? = null
    private var modelId: String? = null

    fun accept(chunk: JsonElement): List<StreamEvent> {
        val obj = try {
            WireDecoder.objectValue(chunk, "anthropic", "stream event")
        } catch (error: WireDecodeException) {
            return listOf(StreamEvent.Error(error.message ?: "Anthropic stream protocol error"))
        }
        val type = try {
            WireDecoder.requiredString(obj, "type", "anthropic", "stream event")
        } catch (error: WireDecodeException) {
            return listOf(StreamEvent.Error(error.message ?: "Anthropic stream protocol error"))
        }
        val events = mutableListOf<StreamEvent>()
        when (type) {
            "message_start" -> {
                val message = try {
                    WireDecoder.objectValue(WireDecoder.required(obj, "message", "anthropic", "stream event"), "anthropic", "stream event", "$.message")
                } catch (error: WireDecodeException) {
                    return listOf(StreamEvent.Error(error.message ?: "Anthropic stream protocol error"))
                }
                responseId = (message["id"] as? JsonPrimitive)?.contentOrNull
                modelId = (message["model"] as? JsonPrimitive)?.contentOrNull
                usage = Usage.fromAnthropic(message["usage"])
                events += StreamEvent.ResponseMetadata(id = responseId, modelId = modelId)
            }
            "content_block_start" -> {
                val index = try {
                    WireDecoder.optionalInt(obj, "index", "anthropic", "stream event") ?: blocks.size
                } catch (error: WireDecodeException) {
                    return listOf(StreamEvent.Error(error.message ?: "Anthropic stream protocol error"))
                }
                val block = try {
                    WireDecoder.objectValue(WireDecoder.required(obj, "content_block", "anthropic", "stream event"), "anthropic", "stream event", "$.content_block")
                } catch (error: WireDecodeException) {
                    return listOf(StreamEvent.Error(error.message ?: "Anthropic stream protocol error"))
                }
                val blockType = try {
                    WireDecoder.requiredString(block, "type", "anthropic", "stream event", "$.content_block")
                } catch (error: WireDecodeException) {
                    return listOf(StreamEvent.Error(error.message ?: "Anthropic stream protocol error"))
                }
                val isToolBlock = blockType == "tool_use" || blockType == "server_tool_use" || blockType == "mcp_tool_use"
                val id = if (isToolBlock) {
                    (block["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                        ?: return listOf(missingToolIdentityError(type, "id"))
                } else {
                    (block["id"] as? JsonPrimitive)?.contentOrNull ?: "block-$index"
                }
                val toolName = if (isToolBlock) {
                    (block["name"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                        ?: return listOf(missingToolIdentityError(type, "name"))
                } else {
                    (block["name"] as? JsonPrimitive)?.contentOrNull
                }
                blocks[index] = AnthropicStreamBlock(id, blockType, toolName, anthropicInitialStreamInput(block["input"]))
                when (blockType) {
                    "text" -> events += StreamEvent.TextStart(id)
                    "thinking", "redacted_thinking" -> events += StreamEvent.ReasoningStart(id)
                    "tool_use", "server_tool_use", "mcp_tool_use" -> {
                        events += StreamEvent.ToolInputStart(id, toolName ?: return listOf(missingToolIdentityError(type, "name")))
                    }
                }
            }
            "content_block_delta" -> {
                val index = try {
                    // index is REQUIRED for delta/stop (unlike content_block_start, which CREATES a
                    // block at blocks.size) — a missing index references no block, so it is a wire
                    // error. This single strict read replaces the old required()+optionalInt double
                    // read whose `?: blocks.size` fallback was dead code.
                    WireDecoder.intValue(
                        WireDecoder.required(obj, "index", "anthropic", "stream event"),
                        "anthropic",
                        "stream event",
                        "$.index",
                    )
                } catch (error: WireDecodeException) {
                    return listOf(StreamEvent.Error(error.message ?: "Anthropic stream protocol error"))
                }
                val block = blocks[index]
                    ?: return listOf(StreamEvent.Error("Anthropic stream protocol error: content_block_delta for unknown block index $index."))
                val delta = try {
                    WireDecoder.objectValue(WireDecoder.required(obj, "delta", "anthropic", "stream event"), "anthropic", "stream event", "$.delta")
                } catch (error: WireDecodeException) {
                    return listOf(StreamEvent.Error(error.message ?: "Anthropic stream protocol error"))
                }
                when (WireDecoder.optionalString(delta, "type", "anthropic", "stream event", "$.delta")) {
                    "text_delta" -> events += StreamEvent.TextDelta(block.id, WireDecoder.requiredString(delta, "text", "anthropic", "stream event", "$.delta"))
                    "thinking_delta" -> events += StreamEvent.ReasoningDelta(block.id, WireDecoder.requiredString(delta, "thinking", "anthropic", "stream event", "$.delta"))
                    // Capture the streamed thinking signature onto the block; it surfaces on the
                    // eventual ReasoningEnd providerMetadata (content_block_stop) for replay parity
                    // with the non-streaming `signature` capture. Not a fatal stream error.
                    "signature_delta" ->
                        (delta["signature"] as? JsonPrimitive)?.contentOrNull?.let { block.signature = it }
                    // Mid-stream citation: emit a Source, mirroring the non-streaming citation path.
                    "citations_delta" -> citationSourceEvent(delta)?.let { events += it }
                    "input_json_delta" -> {
                        val text = WireDecoder.requiredString(delta, "partial_json", "anthropic", "stream event", "$.delta")
                        block.input += text
                        events += StreamEvent.ToolInputDelta(block.id, text)
                    }
                    null -> return listOf(StreamEvent.Error("Anthropic stream protocol error: content_block_delta missing delta.type."))
                    // Forward-compatible: ignore unknown delta subtypes (matches upstream Vercel AI
                    // SDK) rather than aborting generation on a delta Anthropic adds later.
                    else -> return emptyList()
                }
            }
            "content_block_stop" -> {
                val index = try {
                    // index is REQUIRED for delta/stop (unlike content_block_start, which CREATES a
                    // block at blocks.size) — a missing index references no block, so it is a wire
                    // error. This single strict read replaces the old required()+optionalInt double
                    // read whose `?: blocks.size` fallback was dead code.
                    WireDecoder.intValue(
                        WireDecoder.required(obj, "index", "anthropic", "stream event"),
                        "anthropic",
                        "stream event",
                        "$.index",
                    )
                } catch (error: WireDecodeException) {
                    return listOf(StreamEvent.Error(error.message ?: "Anthropic stream protocol error"))
                }
                val block = blocks.remove(index)
                    ?: return listOf(StreamEvent.Error("Anthropic stream protocol error: content_block_stop for unknown block index $index."))
                when (block.type) {
                    "text" -> events += StreamEvent.TextEnd(block.id)
                    // Surface the streamed signature for replay, mirroring the non-streaming
                    // `signature` capture. ProviderMetadata.None when no signature_delta arrived.
                    "thinking", "redacted_thinking" -> events += StreamEvent.ReasoningEnd(
                        block.id,
                        providerMetadata = reasoningEndMetadata(block.signature),
                    )
                    "tool_use", "server_tool_use", "mcp_tool_use" -> {
                        events += StreamEvent.ToolInputEnd(block.id)
                        val toolName = block.toolName ?: return listOf(missingToolIdentityError("content_block_stop", "name"))
                        val inputJson = if (block.input.isBlank()) {
                            JsonObject(emptyMap())
                        } else {
                            runCatching { json.parseToJsonElement(block.input) }.getOrElse {
                                events += StreamEvent.Error("Anthropic stream protocol error: malformed tool input JSON for `$toolName`.")
                                return events
                            }
                        }
                        events += StreamEvent.ToolCall(
                            toolCallId = block.id,
                            toolName = toolName,
                            inputJson = inputJson,
                            providerMetadata = if (block.type != "tool_use") ProviderMetadata.Raw(JsonObject(mapOf("anthropic" to buildJsonObject { put("providerExecuted", JsonPrimitive(true)) }))) else ProviderMetadata.None,
                        )
                    }
                }
            }
            "message_delta" -> {
                val delta = (obj["delta"] as? JsonObject) ?: JsonObject(emptyMap())
                rawStopReason = (delta["stop_reason"] as? JsonPrimitive)?.contentOrNull
                finishReason = FinishReason.fromAnthropicStopReason(rawStopReason)
                // Merge onto the message_start usage (delta usually has only output_tokens).
                usage = Usage.mergeAnthropic(usage, obj["usage"])
            }
            "error" -> events += StreamEvent.Error(AnthropicMessagesLanguageModel.anthropicErrorMessage(obj["error"] ?: obj, obj.toString()))
        }
        return events
    }

    private fun anthropicInitialStreamInput(input: JsonElement?): String = when (input) {
        null -> ""
        is JsonObject -> if (input.isEmpty()) "" else input.toString()
        else -> input.toString()
    }

    /** Wrap a captured thinking [signature] as `anthropic.signature` provider metadata for
     *  [StreamEvent.ReasoningEnd]; [ProviderMetadata.None] when none was streamed. */
    private fun reasoningEndMetadata(signature: String?): ProviderMetadata =
        signature?.let { sig ->
            val anthropic = buildJsonObject { put("signature", JsonPrimitive(sig)) }
            ProviderMetadata.Raw(JsonObject(mapOf("anthropic" to anthropic)))
        } ?: ProviderMetadata.None

    /** Map a streamed `citations_delta` to a [StreamEvent.SourcePart], reusing the non-streaming
     *  citation decoder; null when the delta carries no parsable citation. */
    private fun citationSourceEvent(delta: JsonObject): StreamEvent.SourcePart? =
        (delta["citation"] as? JsonObject)
            ?.let { settings.anthropicCitationSource(it) }
            ?.let { source ->
                StreamEvent.SourcePart(
                    id = settings.generateId(),
                    sourceType = source.sourceType,
                    url = source.url,
                    title = source.title,
                    providerMetadata = source.providerMetadata,
                )
            }

    private fun missingToolIdentityError(eventType: String, field: String): StreamEvent.Error =
        StreamEvent.Error("Anthropic stream protocol error: $eventType missing required $field.")

    fun finish(): List<StreamEvent> = listOf(
        StreamEvent.Finish(
            totalSteps = 1,
            finishReason = finishReason,
            usage = usage,
            providerMetadata = ProviderMetadata.Raw(JsonObject(mapOf("anthropic" to buildJsonObject {
                responseId?.let { put("responseId", JsonPrimitive(it)) }
            }))),
            rawFinishReason = rawStopReason,
        ),
    )
}

private data class AnthropicStreamBlock(
    val id: String,
    val type: String,
    val toolName: String?,
    var input: String,
    /** Captured from streamed `signature_delta` on a thinking block; surfaced on the
     *  eventual [StreamEvent.ReasoningEnd] so streamed reasoning carries its signature
     *  for replay (mirrors the non-streaming `signature` capture). */
    var signature: String? = null,
)
