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
import kotlinx.coroutines.flow.Flow
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
import kotlinx.serialization.json.jsonPrimitive

const val ANTHROPIC_VERSION: String = "3.0.81"

typealias AnthropicMessagesModelId = String
typealias AnthropicLanguageModelOptions = JsonObject
typealias AnthropicProviderOptions = AnthropicLanguageModelOptions
typealias AnthropicToolOptions = JsonObject
typealias AnthropicMessageMetadata = JsonObject
typealias AnthropicUsageIteration = JsonObject

data class AnthropicProviderSettings(
    val baseURL: String = "https://api.anthropic.com/v1",
    val apiKey: String? = null,
    val authToken: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val requestHeadersProvider: (suspend (url: String, body: String, headers: Map<String, String>) -> Map<String, String>)? = null,
    val buildRequestUrl: ((baseURL: String, modelId: String, isStreaming: Boolean) -> String)? = null,
    val transformRequestBody: ((modelId: String, body: JsonObject, isStreaming: Boolean) -> JsonObject)? = null,
    val supportedUrls: Map<String, List<String>>? = null,
    val generateId: () -> String = { ai.torad.aisdk.generateId() },
    val name: String = "anthropic.messages",
)

interface AnthropicProvider : Provider {
    val settings: AnthropicProviderSettings
    val tools: AnthropicTools

    operator fun invoke(modelId: AnthropicMessagesModelId): LanguageModel = languageModel(modelId)
    fun chat(modelId: AnthropicMessagesModelId): LanguageModel = languageModel(modelId)
    fun messages(modelId: AnthropicMessagesModelId): LanguageModel = languageModel(modelId)
    fun textEmbeddingModel(modelId: String): Nothing = throw NoSuchModelError(providerId, "embeddingModel", modelId)
}

fun createAnthropic(
    client: HttpClient,
    settings: AnthropicProviderSettings = AnthropicProviderSettings(),
): AnthropicProvider {
    if (!settings.apiKey.isNullOrBlank() && !settings.authToken.isNullOrBlank()) {
        throw InvalidArgumentError("apiKey/authToken", "Both apiKey and authToken were provided. Please use only one authentication method.")
    }
    return DefaultAnthropicProvider(client, settings)
}

val anthropic: AnthropicProvider = object : AnthropicProvider {
    override val providerId: String = "anthropic"
    override val settings: AnthropicProviderSettings = AnthropicProviderSettings()
    override val tools: AnthropicTools = AnthropicTools()
    override fun languageModel(modelId: String): LanguageModel =
        throw AiSdkException("Anthropic provider is not configured. Use createAnthropic(client, settings).")
    override fun embeddingModel(modelId: String): EmbeddingModel = throw NoSuchModelError(providerId, "embeddingModel", modelId)
    override fun imageModel(modelId: String): ImageModel = throw NoSuchModelError(providerId, "imageModel", modelId)
}

private class DefaultAnthropicProvider(
    private val client: HttpClient,
    override val settings: AnthropicProviderSettings,
) : AnthropicProvider {
    override val providerId: String = "anthropic"
    override val tools: AnthropicTools = anthropicTools

    override fun languageModel(modelId: String): LanguageModel =
        AnthropicMessagesLanguageModel(client, settings, modelId)

    override fun embeddingModel(modelId: String): EmbeddingModel = throw NoSuchModelError(providerId, "embeddingModel", modelId)
    override fun imageModel(modelId: String): ImageModel = throw NoSuchModelError(providerId, "imageModel", modelId)
}

class AnthropicMessagesLanguageModel(
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
        val prepared = anthropicRequestBody(settings, modelId, params, stream = false)
        val response = anthropicPost(prepared.body, prepared.betas, params.headers, acceptEventStream = false, parseJson = true)
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
        val prepared = anthropicRequestBody(settings, modelId, params, stream = true)
        val response = anthropicPost(prepared.body, prepared.betas, params.headers, acceptEventStream = true, parseJson = false)
        emit(StreamEvent.StreamStart(prepared.warnings))
        emit(StreamEvent.ResponseMetadata(headers = response.headers, body = JsonPrimitive(response.rawText)))
        val state = AnthropicStreamState(settings, aiSdkJson)
        for (event in parseJsonEventStream(response.rawText, jsonSchema<JsonElement>(JsonObject(emptyMap())), aiSdkJson)) {
            when (event) {
                is ParseResult.Success -> state.accept(event.value).forEach { emit(it) }
                is ParseResult.Failure -> emit(StreamEvent.Error("Failed to parse Anthropic stream event: ${event.error.message}"))
            }
        }
        state.finish().forEach { emit(it) }
    }

    override fun streamResult(params: LanguageModelCallParams): LanguageModelStreamResult {
        val prepared = anthropicRequestBody(settings, modelId, params, stream = true)
        return LanguageModelStreamResult(stream = stream(params), request = LanguageModelRequestMetadata(prepared.body))
    }

    private suspend fun anthropicPost(
        body: JsonObject,
        betas: Set<String>,
        extraHeaders: Map<String, String>,
        acceptEventStream: Boolean,
        parseJson: Boolean,
    ): AnthropicHttpResponse {
        val baseURL = settings.baseURL.trimEnd('/')
        val url = settings.buildRequestUrl?.invoke(baseURL, modelId, acceptEventStream) ?: "$baseURL/messages"
        val requestBody = settings.transformRequestBody?.invoke(modelId, body, acceptEventStream) ?: body
        val encodedBody = aiSdkJson.encodeToString(JsonElement.serializer(), requestBody)
        val baseHeaders = anthropicHeaders(settings, extraHeaders, betas)
        val requestHeaders = settings.requestHeadersProvider?.invoke(url, encodedBody, baseHeaders) ?: baseHeaders
        val response = client.request(url) {
            method = HttpMethod.Post
            contentType(ContentType.Application.Json)
            if (acceptEventStream) header(HttpHeaders.Accept, "text/event-stream")
            requestHeaders.forEach { (name, value) -> header(name, value) }
            setBody(encodedBody)
        }
        return response.parseAnthropicResponse(parseJson)
    }
}

data class AnthropicTools(
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
)

val anthropicTools: AnthropicTools = AnthropicTools()

fun forwardAnthropicContainerIdFromLastStep(
    steps: List<Map<String, JsonElement>>,
): Map<String, JsonElement>? {
    for (step in steps.asReversed()) {
        val containerId = step["anthropic"]?.jsonObject
            ?.get("container")?.jsonObject
            ?.get("id")?.jsonPrimitive?.contentOrNull
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

private data class AnthropicHttpResponse(
    val value: JsonElement,
    val rawText: String,
    val headers: Map<String, String>,
)

private data class PreparedAnthropicRequest(
    val body: JsonObject,
    val warnings: List<CallWarning>,
    val betas: Set<String>,
)

private data class AnthropicPrompt(
    val system: JsonArray?,
    val messages: JsonArray,
    val betas: Set<String>,
)

private data class PreparedAnthropicTools(
    val tools: JsonArray?,
    val toolChoice: JsonElement?,
    val warnings: List<CallWarning>,
    val betas: Set<String>,
)


private fun anthropicRequestBody(
    settings: AnthropicProviderSettings,
    modelId: String,
    params: LanguageModelCallParams,
    stream: Boolean,
): PreparedAnthropicRequest {
    val warnings = mutableListOf<CallWarning>()
    if (params.frequencyPenalty != null) warnings += CallWarning("unsupported", "frequencyPenalty")
    if (params.presencePenalty != null) warnings += CallWarning("unsupported", "presencePenalty")
    if (params.seed != null) warnings += CallWarning("unsupported", "seed")

    val options = anthropicOptions(params.providerOptions, settings.name)
    val betas = linkedSetOf<String>()
    (options["anthropicBeta"] as? JsonArray)?.forEach { it.jsonPrimitive.contentOrNull?.let(betas::add) }
    val sendReasoning = options["sendReasoning"]?.jsonPrimitive?.booleanOrNull ?: true
    val prompt = anthropicPrompt(params.messages, sendReasoning)
    betas += prompt.betas

    val thinking = options["thinking"] as? JsonObject
    val thinkingType = thinking?.get("type")?.jsonPrimitive?.contentOrNull
    val isThinking = thinkingType == "enabled" || thinkingType == "adaptive"
    var thinkingBudget = thinking?.get("budgetTokens")?.jsonPrimitive?.intOrNull
    val maxTokensBase = params.maxOutputTokens ?: 4096
    val maxTokens = if (isThinking && thinkingType == "enabled") {
        if (thinkingBudget == null) {
            thinkingBudget = 1024
            warnings += CallWarning("compatibility", "thinking budget is required when thinking is enabled. using default budget of 1024 tokens.")
        }
        maxTokensBase + thinkingBudget
    } else {
        maxTokensBase
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

    val preparedTools = anthropicPrepareTools(params.tools, params.toolChoice, options, params.responseFormat)
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

private fun anthropicPrompt(
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
                        anthropicCacheControl(it.providerMetadata)?.let { cache -> put("cache_control", cache) }
                    }
                }
            }
            MessageRole.User -> apiMessages += buildJsonObject {
                put("role", JsonPrimitive("user"))
                put("content", JsonArray(message.content.mapNotNull { anthropicUserPart(it, betas) }))
            }
            MessageRole.Assistant -> {
                val content = message.content.mapNotNull { anthropicAssistantPart(it, sendReasoning) }
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
        anthropicCacheControl(part.providerMetadata)?.let { put("cache_control", it) }
    }
    is ContentPart.Image -> buildJsonObject {
        put("type", JsonPrimitive("image"))
        put("source", buildJsonObject {
            put("type", JsonPrimitive("base64"))
            put("media_type", JsonPrimitive(if (part.mediaType == "image/*") "image/jpeg" else part.mediaType))
            put("data", JsonPrimitive(part.base64))
        })
    }
    is ContentPart.File -> when {
        part.mediaType.startsWith("image/") -> buildJsonObject {
            put("type", JsonPrimitive("image"))
            put("source", buildJsonObject {
                put("type", JsonPrimitive("base64"))
                put("media_type", JsonPrimitive(if (part.mediaType == "image/*") "image/jpeg" else part.mediaType))
                put("data", JsonPrimitive(part.base64))
            })
        }
        part.mediaType == "application/pdf" -> {
            betas += "pdfs-2024-09-25"
            buildJsonObject {
                put("type", JsonPrimitive("document"))
                put("source", buildJsonObject {
                    put("type", JsonPrimitive("base64"))
                    put("media_type", JsonPrimitive("application/pdf"))
                    put("data", JsonPrimitive(part.base64))
                })
                part.filename?.let { put("title", JsonPrimitive(it)) }
                anthropicFileOptions(part.providerMetadata)?.let { putJsonObjectFields(it) }
            }
        }
        part.mediaType == "text/plain" -> buildJsonObject {
            put("type", JsonPrimitive("document"))
            put("source", buildJsonObject {
                put("type", JsonPrimitive("text"))
                put("media_type", JsonPrimitive("text/plain"))
                put("data", JsonPrimitive(convertBase64ToByteArray(part.base64).decodeToString()))
            })
            part.filename?.let { put("title", JsonPrimitive(it)) }
            anthropicFileOptions(part.providerMetadata)?.let { putJsonObjectFields(it) }
        }
        else -> throw AiSdkException("Unsupported Anthropic file media type: ${part.mediaType}")
    }
    else -> null
}

private fun anthropicAssistantPart(part: ContentPart, sendReasoning: Boolean): JsonElement? = when (part) {
    is ContentPart.Text -> buildJsonObject {
        put("type", JsonPrimitive("text"))
        put("text", JsonPrimitive(part.text))
    }
    is ContentPart.Reasoning if sendReasoning -> buildJsonObject {
        val metadata = part.providerMetadata?.get("anthropic") as? JsonObject
        put("type", JsonPrimitive("thinking"))
        put("thinking", JsonPrimitive(part.text))
        metadata?.get("signature")?.let { put("signature", it) }
    }
    is ContentPart.ToolCall -> buildJsonObject {
        put("type", JsonPrimitive("tool_use"))
        put("id", JsonPrimitive(part.toolCallId))
        put("name", JsonPrimitive(part.toolName))
        put("input", part.input)
    }
    else -> null
}

private fun anthropicToolResultContent(result: ContentPart.ToolResult): JsonElement {
    val output = result.modelVisible
    return when {
        output is JsonPrimitive && output.isString -> JsonPrimitive(output.content)
        else -> JsonPrimitive(output.toString())
    }
}

private fun anthropicPrepareTools(
    tools: List<LanguageModelTool>,
    choice: ToolChoice,
    options: JsonObject,
    responseFormat: ResponseFormat,
): PreparedAnthropicTools {
    if (choice == ToolChoice.None) return PreparedAnthropicTools(null, null, emptyList(), emptySet())
    val warnings = mutableListOf<CallWarning>()
    val betas = linkedSetOf<String>()
    val disableParallel = options["disableParallelToolUse"]?.jsonPrimitive?.booleanOrNull
    val toolStreaming = options["toolStreaming"]?.jsonPrimitive?.booleanOrNull ?: true
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

private fun anthropicProviderExecutedTool(name: String, betas: MutableSet<String>): JsonObject? = when (name) {
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
    return JsonArray(servers.map { server ->
        val obj = server.jsonObject
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

private fun anthropicOptions(providerOptions: Map<String, JsonElement>, providerName: String): JsonObject {
    val canonical = providerOptions["anthropic"] as? JsonObject ?: JsonObject(emptyMap())
    val customName = providerName.substringBefore('.')
    val custom = if (customName != "anthropic") providerOptions[customName] as? JsonObject else null
    return mergeJsonObjects(canonical, custom ?: JsonObject(emptyMap()))
}

private fun anthropicCacheControl(metadata: Map<String, JsonElement>?): JsonElement? =
    (metadata?.get("anthropic") as? JsonObject)?.get("cacheControl")

private fun anthropicFileOptions(metadata: Map<String, JsonElement>?): JsonObject? {
    val options = metadata?.get("anthropic") as? JsonObject ?: return null
    return buildJsonObject {
        (options["citations"] as? JsonObject)?.let { put("citations", it) }
        options["title"]?.let { put("title", it) }
        options["context"]?.let { put("context", it) }
    }.takeIf { it.isNotEmpty() }
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
    for (part in (response["content"] as? JsonArray).orEmpty()) {
        val obj = part.jsonObject
        when (obj["type"]?.jsonPrimitive?.contentOrNull) {
            "text" -> {
                obj["text"]?.jsonPrimitive?.contentOrNull?.let { text -> content += ContentPart.Text(text) }
                for (citation in (obj["citations"] as? JsonArray).orEmpty()) {
                    anthropicCitationSource(citation.jsonObject, settings)?.let { content += it }
                }
            }
            "thinking" -> content += ContentPart.Reasoning(
                text = obj["thinking"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                providerMetadata = mapOf("anthropic" to buildJsonObject {
                    obj["signature"]?.let { put("signature", it) }
                }),
            )
            "redacted_thinking" -> content += ContentPart.Reasoning(
                text = "",
                providerMetadata = mapOf("anthropic" to buildJsonObject {
                    obj["data"]?.let { put("redactedData", it) }
                }),
            )
            "tool_use", "server_tool_use", "mcp_tool_use" -> {
                val toolCall = ContentPart.ToolCall(
                    toolCallId = obj["id"]?.jsonPrimitive?.contentOrNull ?: settings.generateId(),
                    toolName = obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    input = obj["input"] ?: JsonObject(emptyMap()),
                    providerMetadata = if (obj["type"]?.jsonPrimitive?.contentOrNull != "tool_use") {
                        mapOf("anthropic" to buildJsonObject { put("providerExecuted", JsonPrimitive(true)) })
                    } else {
                        null
                    },
                )
                toolCalls += toolCall
                content += toolCall
            }
            "mcp_tool_result", "web_search_tool_result", "web_fetch_tool_result", "code_execution_tool_result" -> {
                content += ContentPart.ToolResult(
                    toolCallId = obj["tool_use_id"]?.jsonPrimitive?.contentOrNull ?: obj["id"]?.jsonPrimitive?.contentOrNull ?: settings.generateId(),
                    toolName = obj["name"]?.jsonPrimitive?.contentOrNull ?: obj["type"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    output = obj["content"] ?: obj,
                    providerMetadata = mapOf("anthropic" to obj),
                )
            }
        }
    }

    val stopReason = response["stop_reason"]?.jsonPrimitive?.contentOrNull
    val usage = anthropicUsage(response["usage"])
    val text = content.filterIsInstance<ContentPart.Text>().joinToString("") { it.text }
    val metadata = buildJsonObject {
        response["usage"]?.let { put("usage", it) }
        put("cacheCreationInputTokens", JsonPrimitive(usage.inputTokens.cacheWrite))
        response["stop_sequence"]?.let { put("stopSequence", it) }
        response["container"]?.let { put("container", camelToSnakeJson(it)) }
        response["context_management"]?.let { put("contextManagement", it) }
    }
    return LanguageModelResult(
        text = text,
        toolCalls = toolCalls,
        finishReason = mapAnthropicStopReason(stopReason),
        usage = usage,
        providerMetadata = mapOf("anthropic" to metadata),
        content = content,
        rawFinishReason = stopReason,
        warnings = warnings,
        request = LanguageModelRequestMetadata(body = requestBody),
        response = LanguageModelResponseMetadata(
            id = response["id"]?.jsonPrimitive?.contentOrNull,
            modelId = response["model"]?.jsonPrimitive?.contentOrNull,
            headers = responseHeaders,
            body = responseBody,
        ),
    )
}

private class AnthropicStreamState(
    private val settings: AnthropicProviderSettings,
    private val json: Json,
) {
    private val blocks = mutableMapOf<Int, AnthropicStreamBlock>()
    private var finishReason = FinishReason.Other
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
                responseId = message["id"]?.jsonPrimitive?.contentOrNull
                modelId = message["model"]?.jsonPrimitive?.contentOrNull
                usage = anthropicUsage(message["usage"])
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
                val id = block["id"]?.jsonPrimitive?.contentOrNull ?: "block-$index"
                val toolName = block["name"]?.jsonPrimitive?.contentOrNull
                blocks[index] = AnthropicStreamBlock(id, blockType, toolName, anthropicInitialStreamInput(block["input"]))
                when (blockType) {
                    "text" -> events += StreamEvent.TextStart(id)
                    "thinking", "redacted_thinking" -> events += StreamEvent.ReasoningStart(id)
                    "tool_use", "server_tool_use", "mcp_tool_use" -> events += StreamEvent.ToolInputStart(id, toolName.orEmpty())
                }
            }
            "content_block_delta" -> {
                val index = try {
                    WireDecoder.required(obj, "index", "anthropic", "stream event")
                    WireDecoder.optionalInt(obj, "index", "anthropic", "stream event") ?: blocks.size
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
                when (val deltaType = WireDecoder.optionalString(delta, "type", "anthropic", "stream event", "$.delta")) {
                    "text_delta" -> events += StreamEvent.TextDelta(block.id, WireDecoder.requiredString(delta, "text", "anthropic", "stream event", "$.delta"))
                    "thinking_delta" -> events += StreamEvent.ReasoningDelta(block.id, WireDecoder.requiredString(delta, "thinking", "anthropic", "stream event", "$.delta"))
                    "input_json_delta" -> {
                        val text = WireDecoder.requiredString(delta, "partial_json", "anthropic", "stream event", "$.delta")
                        block.input += text
                        events += StreamEvent.ToolInputDelta(block.id, text)
                    }
                    null -> return listOf(StreamEvent.Error("Anthropic stream protocol error: content_block_delta missing delta.type."))
                    else -> return listOf(StreamEvent.Error("Anthropic stream protocol error: unsupported content_block_delta type `$deltaType`."))
                }
            }
            "content_block_stop" -> {
                val index = try {
                    WireDecoder.required(obj, "index", "anthropic", "stream event")
                    WireDecoder.optionalInt(obj, "index", "anthropic", "stream event") ?: blocks.size
                } catch (error: WireDecodeException) {
                    return listOf(StreamEvent.Error(error.message ?: "Anthropic stream protocol error"))
                }
                val block = blocks.remove(index)
                    ?: return listOf(StreamEvent.Error("Anthropic stream protocol error: content_block_stop for unknown block index $index."))
                when (block.type) {
                    "text" -> events += StreamEvent.TextEnd(block.id)
                    "thinking", "redacted_thinking" -> events += StreamEvent.ReasoningEnd(block.id)
                    "tool_use", "server_tool_use", "mcp_tool_use" -> {
                        events += StreamEvent.ToolInputEnd(block.id)
                        val inputJson = if (block.input.isBlank()) {
                            JsonObject(emptyMap())
                        } else {
                            runCatching { json.parseToJsonElement(block.input) }.getOrElse {
                                events += StreamEvent.Error("Anthropic stream protocol error: malformed tool input JSON for `${block.toolName.orEmpty()}`.")
                                return events
                            }
                        }
                        events += StreamEvent.ToolCall(
                            toolCallId = block.id,
                            toolName = block.toolName.orEmpty(),
                            inputJson = inputJson,
                            providerMetadata = if (block.type != "tool_use") mapOf("anthropic" to buildJsonObject { put("providerExecuted", JsonPrimitive(true)) }) else null,
                        )
                    }
                }
            }
            "message_delta" -> {
                val delta = obj["delta"]?.jsonObject ?: JsonObject(emptyMap())
                finishReason = mapAnthropicStopReason(delta["stop_reason"]?.jsonPrimitive?.contentOrNull)
                usage = anthropicUsage(obj["usage"])
            }
            "error" -> events += StreamEvent.Error(anthropicErrorMessage(obj["error"] ?: obj, obj.toString()))
        }
        return events
    }

    fun finish(): List<StreamEvent> = listOf(
        StreamEvent.Finish(
            totalSteps = 1,
            finishReason = finishReason,
            usage = usage,
            providerMetadata = mapOf("anthropic" to buildJsonObject {
                responseId?.let { put("responseId", JsonPrimitive(it)) }
            }),
        ),
    )
}

private data class AnthropicStreamBlock(
    val id: String,
    val type: String,
    val toolName: String?,
    var input: String,
)

private fun anthropicInitialStreamInput(input: JsonElement?): String = when (input) {
    null -> ""
    is JsonObject -> if (input.isEmpty()) "" else input.toString()
    else -> input.toString()
}

private suspend fun HttpResponse.parseAnthropicResponse(parseJson: Boolean): AnthropicHttpResponse {
    val raw = bodyAsText()
    val headers = responseHeaders()
    if (status.value !in 200..299) {
        val parsed = runCatching { aiSdkJson.parseToJsonElement(raw) }.getOrNull()
        throw AiSdkException(anthropicErrorMessage(parsed, raw))
    }
    return AnthropicHttpResponse(
        value = if (parseJson && raw.isNotBlank()) aiSdkJson.parseToJsonElement(raw) else JsonObject(emptyMap()),
        rawText = raw,
        headers = headers,
    )
}

private fun HttpResponse.responseHeaders(): Map<String, String> =
    headers.entries().associate { it.key to it.value.joinToString(",") }

private fun anthropicHeaders(
    settings: AnthropicProviderSettings,
    extra: Map<String, String>,
    betas: Set<String>,
): Map<String, String> {
    val headers = linkedMapOf<String, String?>()
    headers["anthropic-version"] = "2023-06-01"
    settings.authToken?.takeIf { it.isNotBlank() }?.let { headers[HttpHeaders.Authorization] = "Bearer $it" }
        ?: settings.apiKey?.takeIf { it.isNotBlank() }?.let { headers["x-api-key"] = it }
    headers.putAll(settings.headers)
    headers.putAll(extra)
    if (betas.isNotEmpty()) headers["anthropic-beta"] = betas.joinToString(",")
    return withUserAgentSuffix(headers, "ai-sdk/anthropic/$ANTHROPIC_VERSION")
}

private fun anthropicUsage(element: JsonElement?): Usage {
    val obj = element as? JsonObject ?: return Usage()
    val baseInput = obj["input_tokens"]?.jsonPrimitive?.intOrNull ?: 0
    val baseOutput = obj["output_tokens"]?.jsonPrimitive?.intOrNull ?: 0
    val cacheWrite = obj["cache_creation_input_tokens"]?.jsonPrimitive?.intOrNull ?: 0
    val cacheRead = obj["cache_read_input_tokens"]?.jsonPrimitive?.intOrNull ?: 0
    val iterations = obj["iterations"] as? JsonArray
    val executorIterations = iterations.orEmpty().mapNotNull { it as? JsonObject }
        .filter { it["type"]?.jsonPrimitive?.contentOrNull in setOf("compaction", "message") }
    val input = if (executorIterations.isNotEmpty()) {
        executorIterations.sumOf { it["input_tokens"]?.jsonPrimitive?.intOrNull ?: 0 }
    } else {
        baseInput
    }
    val output = if (executorIterations.isNotEmpty()) {
        executorIterations.sumOf { it["output_tokens"]?.jsonPrimitive?.intOrNull ?: 0 }
    } else {
        baseOutput
    }
    return Usage(
        inputTokens = Usage.InputTokenBreakdown(
            total = input + cacheWrite + cacheRead,
            noCache = input,
            cacheRead = cacheRead,
            cacheWrite = cacheWrite,
        ),
        outputTokens = Usage.OutputTokenBreakdown(total = output),
        raw = element,
    )
}

private fun mapAnthropicStopReason(reason: String?): FinishReason = when (reason) {
    "pause_turn", "end_turn", "stop_sequence" -> FinishReason.Stop
    "refusal" -> FinishReason.ContentFilter
    "tool_use" -> FinishReason.ToolCalls
    "max_tokens", "model_context_window_exceeded" -> FinishReason.Length
    else -> FinishReason.Other
}

private fun anthropicCitationSource(citation: JsonObject, settings: AnthropicProviderSettings): ContentPart.Source? =
    when (citation["type"]?.jsonPrimitive?.contentOrNull) {
        "web_search_result_location" -> ContentPart.Source(
            sourceType = StreamEvent.SourcePart.SourceType.Url,
            url = citation["url"]?.jsonPrimitive?.contentOrNull,
            title = citation["title"]?.jsonPrimitive?.contentOrNull,
            providerMetadata = mapOf("anthropic" to buildJsonObject {
                citation["cited_text"]?.let { put("citedText", it) }
                citation["encrypted_index"]?.let { put("encryptedIndex", it) }
                put("id", JsonPrimitive(settings.generateId()))
            }),
        )
        "page_location", "char_location" -> ContentPart.Source(
            sourceType = StreamEvent.SourcePart.SourceType.Document,
            title = citation["document_title"]?.jsonPrimitive?.contentOrNull,
            providerMetadata = mapOf("anthropic" to citation),
        )
        else -> null
    }

private fun anthropicErrorMessage(parsed: JsonElement?, raw: String): String {
    val obj = parsed as? JsonObject ?: return raw
    val error = obj["error"] as? JsonObject
    return error?.get("message")?.jsonPrimitive?.contentOrNull
        ?: error?.get("type")?.jsonPrimitive?.contentOrNull
        ?: obj["message"]?.jsonPrimitive?.contentOrNull
        ?: raw
}

private fun camelToSnakeJson(element: JsonElement): JsonElement = when (element) {
    is JsonObject -> JsonObject(element.mapKeys { camelToSnake(it.key) }.mapValues { camelToSnakeJson(it.value) })
    is JsonArray -> JsonArray(element.map(::camelToSnakeJson))
    else -> element
}

private fun camelToSnake(value: String): String =
    value.replace(Regex("([a-z0-9])([A-Z])")) { "${it.groupValues[1]}_${it.groupValues[2].lowercase()}" }

private fun JsonObjectBuilder.putJsonObjectFields(fields: JsonObject) {
    fields.forEach { (key, value) -> if (value !is JsonNull) put(key, value) }
}

private fun anthropicProviderTool(
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
