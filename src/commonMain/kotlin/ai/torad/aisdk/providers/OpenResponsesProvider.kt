@file:OptIn(ai.torad.aisdk.LowLevelLanguageModelApi::class)

package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import dev.drewhamilton.poko.Poko
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
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

public const val OPEN_RESPONSES_VERSION: String = "1.0.16"
public const val OPEN_RESPONSES_TOP_LOGPROBS_MAX: Int = 20

public val OPEN_RESPONSES_SUPPORTED_URLS: Map<String, List<String>> = mapOf("image/*" to listOf("^https?://.*$"))

public class OpenResponsesProviderSettings internal constructor(
    public val url: String,
    public val name: String,
    public val apiKey: String? = null,
    public val headers: Map<String, String> = emptyMap(),
    public val authHeadersProvider: (suspend () -> Map<String, String>)? = null,
    public val userAgentSuffix: String? = "ai-sdk/open-responses/$OPEN_RESPONSES_VERSION",
    public val providerOptionsName: String? = null,
    public val supportedUrls: Map<String, List<String>> = OPEN_RESPONSES_SUPPORTED_URLS,
    public val fileIdPrefixes: List<String> = emptyList(),
)

public class OpenResponsesProviderSettingsBuilder {
    private var url: String? = null
    private var name: String? = null
    private var apiKey: String? = null
    private var headers: Map<String, String> = emptyMap()
    private var authHeadersProvider: (suspend () -> Map<String, String>)? = null
    private var userAgentSuffix: String? = "ai-sdk/open-responses/$OPEN_RESPONSES_VERSION"
    private var providerOptionsName: String? = null
    private var supportedUrls: Map<String, List<String>> = OPEN_RESPONSES_SUPPORTED_URLS
    private var fileIdPrefixes: List<String> = emptyList()

    public fun url(value: String): OpenResponsesProviderSettingsBuilder {
        url = value
        return this
    }

    public fun name(value: String): OpenResponsesProviderSettingsBuilder {
        name = value
        return this
    }

    public fun apiKey(value: String?): OpenResponsesProviderSettingsBuilder {
        apiKey = value
        return this
    }

    public fun headers(value: Map<String, String>): OpenResponsesProviderSettingsBuilder {
        headers = value
        return this
    }

    public fun authHeadersProvider(value: (suspend () -> Map<String, String>)?): OpenResponsesProviderSettingsBuilder {
        authHeadersProvider = value
        return this
    }

    public fun userAgentSuffix(value: String?): OpenResponsesProviderSettingsBuilder {
        userAgentSuffix = value
        return this
    }

    public fun providerOptionsName(value: String?): OpenResponsesProviderSettingsBuilder {
        providerOptionsName = value
        return this
    }

    public fun supportedUrls(value: Map<String, List<String>>): OpenResponsesProviderSettingsBuilder {
        supportedUrls = value
        return this
    }

    public fun fileIdPrefixes(value: List<String>): OpenResponsesProviderSettingsBuilder {
        fileIdPrefixes = value
        return this
    }

    public fun build(): OpenResponsesProviderSettings =
        OpenResponsesProviderSettings(
            url = requireNotNull(url) { "OpenResponsesProviderSettings.url is required" },
            name = requireNotNull(name) { "OpenResponsesProviderSettings.name is required" },
            apiKey = apiKey,
            headers = headers,
            authHeadersProvider = authHeadersProvider,
            userAgentSuffix = userAgentSuffix,
            providerOptionsName = providerOptionsName,
            supportedUrls = supportedUrls,
            fileIdPrefixes = fileIdPrefixes,
        )
}

public fun OpenResponsesProviderSettings(
    block: OpenResponsesProviderSettingsBuilder.() -> Unit = {},
): OpenResponsesProviderSettings =
    OpenResponsesProviderSettingsBuilder().apply(block).build()

@Serializable
@Poko
public class OpenResponsesOptions internal constructor(
    public val conversation: String? = null,
    public val include: List<String>? = null,
    public val instructions: String? = null,
    public val logprobs: JsonElement? = null,
    public val maxToolCalls: Int? = null,
    public val metadata: JsonElement? = null,
    public val parallelToolCalls: Boolean? = null,
    public val previousResponseId: String? = null,
    public val promptCacheKey: String? = null,
    public val promptCacheRetention: String? = null,
    public val reasoningEffort: String? = null,
    public val reasoningSummary: String? = null,
    public val safetyIdentifier: String? = null,
    public val serviceTier: String? = null,
    public val store: Boolean? = null,
    public val passThroughUnsupportedFiles: Boolean? = null,
    public val strictJsonSchema: Boolean? = null,
    public val textVerbosity: String? = null,
    public val truncation: String? = null,
    public val user: String? = null,
    public val systemMessageMode: String? = null,
    public val forceReasoning: Boolean? = null,
    public val allowedTools: OpenResponsesAllowedTools? = null,
)

public class OpenResponsesOptionsBuilder {
    private var conversation: String? = null
    private var include: List<String>? = null
    private var instructions: String? = null
    private var logprobs: JsonElement? = null
    private var maxToolCalls: Int? = null
    private var metadata: JsonElement? = null
    private var parallelToolCalls: Boolean? = null
    private var previousResponseId: String? = null
    private var promptCacheKey: String? = null
    private var promptCacheRetention: String? = null
    private var reasoningEffort: String? = null
    private var reasoningSummary: String? = null
    private var safetyIdentifier: String? = null
    private var serviceTier: String? = null
    private var store: Boolean? = null
    private var passThroughUnsupportedFiles: Boolean? = null
    private var strictJsonSchema: Boolean? = null
    private var textVerbosity: String? = null
    private var truncation: String? = null
    private var user: String? = null
    private var systemMessageMode: String? = null
    private var forceReasoning: Boolean? = null
    private var allowedTools: OpenResponsesAllowedTools? = null

    public fun conversation(value: String?): OpenResponsesOptionsBuilder {
        conversation = value
        return this
    }

    public fun include(value: List<String>?): OpenResponsesOptionsBuilder {
        include = value
        return this
    }

    public fun instructions(value: String?): OpenResponsesOptionsBuilder {
        instructions = value
        return this
    }

    public fun logprobs(value: JsonElement?): OpenResponsesOptionsBuilder {
        logprobs = value
        return this
    }

    public fun maxToolCalls(value: Int?): OpenResponsesOptionsBuilder {
        maxToolCalls = value
        return this
    }

    public fun metadata(value: JsonElement?): OpenResponsesOptionsBuilder {
        metadata = value
        return this
    }

    public fun parallelToolCalls(value: Boolean?): OpenResponsesOptionsBuilder {
        parallelToolCalls = value
        return this
    }

    public fun previousResponseId(value: String?): OpenResponsesOptionsBuilder {
        previousResponseId = value
        return this
    }

    public fun promptCacheKey(value: String?): OpenResponsesOptionsBuilder {
        promptCacheKey = value
        return this
    }

    public fun promptCacheRetention(value: String?): OpenResponsesOptionsBuilder {
        promptCacheRetention = value
        return this
    }

    public fun reasoningEffort(value: String?): OpenResponsesOptionsBuilder {
        reasoningEffort = value
        return this
    }

    public fun reasoningSummary(value: String?): OpenResponsesOptionsBuilder {
        reasoningSummary = value
        return this
    }

    public fun safetyIdentifier(value: String?): OpenResponsesOptionsBuilder {
        safetyIdentifier = value
        return this
    }

    public fun serviceTier(value: String?): OpenResponsesOptionsBuilder {
        serviceTier = value
        return this
    }

    public fun store(value: Boolean?): OpenResponsesOptionsBuilder {
        store = value
        return this
    }

    public fun passThroughUnsupportedFiles(value: Boolean?): OpenResponsesOptionsBuilder {
        passThroughUnsupportedFiles = value
        return this
    }

    public fun strictJsonSchema(value: Boolean?): OpenResponsesOptionsBuilder {
        strictJsonSchema = value
        return this
    }

    public fun textVerbosity(value: String?): OpenResponsesOptionsBuilder {
        textVerbosity = value
        return this
    }

    public fun truncation(value: String?): OpenResponsesOptionsBuilder {
        truncation = value
        return this
    }

    public fun user(value: String?): OpenResponsesOptionsBuilder {
        user = value
        return this
    }

    public fun systemMessageMode(value: String?): OpenResponsesOptionsBuilder {
        systemMessageMode = value
        return this
    }

    public fun forceReasoning(value: Boolean?): OpenResponsesOptionsBuilder {
        forceReasoning = value
        return this
    }

    public fun allowedTools(value: OpenResponsesAllowedTools?): OpenResponsesOptionsBuilder {
        allowedTools = value
        return this
    }

    public fun build(): OpenResponsesOptions =
        OpenResponsesOptions(
            conversation = conversation,
            include = include,
            instructions = instructions,
            logprobs = logprobs,
            maxToolCalls = maxToolCalls,
            metadata = metadata,
            parallelToolCalls = parallelToolCalls,
            previousResponseId = previousResponseId,
            promptCacheKey = promptCacheKey,
            promptCacheRetention = promptCacheRetention,
            reasoningEffort = reasoningEffort,
            reasoningSummary = reasoningSummary,
            safetyIdentifier = safetyIdentifier,
            serviceTier = serviceTier,
            store = store,
            passThroughUnsupportedFiles = passThroughUnsupportedFiles,
            strictJsonSchema = strictJsonSchema,
            textVerbosity = textVerbosity,
            truncation = truncation,
            user = user,
            systemMessageMode = systemMessageMode,
            forceReasoning = forceReasoning,
            allowedTools = allowedTools,
        )
}

public fun OpenResponsesOptions(
    block: OpenResponsesOptionsBuilder.() -> Unit = {},
): OpenResponsesOptions =
    OpenResponsesOptionsBuilder().apply(block).build()

@Serializable
@Poko
public class OpenResponsesAllowedTools internal constructor(
    public val toolNames: List<String> = emptyList(),
    public val mode: String? = null,
)

public class OpenResponsesAllowedToolsBuilder {
    private var toolNames: List<String> = emptyList()
    private var mode: String? = null

    public fun toolNames(value: List<String>): OpenResponsesAllowedToolsBuilder {
        toolNames = value
        return this
    }

    public fun mode(value: String?): OpenResponsesAllowedToolsBuilder {
        mode = value
        return this
    }

    public fun build(): OpenResponsesAllowedTools =
        OpenResponsesAllowedTools(
            toolNames = toolNames,
            mode = mode,
        )
}

public fun OpenResponsesAllowedTools(
    block: OpenResponsesAllowedToolsBuilder.() -> Unit = {},
): OpenResponsesAllowedTools =
    OpenResponsesAllowedToolsBuilder().apply(block).build()

public interface OpenResponsesProvider : Provider {
    public operator fun invoke(modelId: String): LanguageModel = languageModel(modelId)
    public fun responses(modelId: String): LanguageModel = languageModel(modelId)
}

public fun OpenResponses(
    client: HttpClient,
    settings: OpenResponsesProviderSettings,
    json: Json = openResponsesJson,
): OpenResponsesProvider = KtorOpenResponsesProvider(client, settings, json)

private class KtorOpenResponsesProvider(
    private val client: HttpClient,
    private val settings: OpenResponsesProviderSettings,
    private val json: Json,
) : OpenResponsesProvider {
    override val providerId: String = settings.name

    override fun languageModel(modelId: String): LanguageModel =
        OpenResponsesLanguageModel(client, settings, json, modelId)
}

private class OpenResponsesLanguageModel(
    private val client: HttpClient,
    private val settings: OpenResponsesProviderSettings,
    private val json: Json,
    override val modelId: String,
) : LanguageModel {
    override val provider: String = "${settings.name}.responses"
    override val supportedUrls: Map<String, List<String>> = settings.supportedUrls

    override suspend fun generate(params: LanguageModelCallParams): LanguageModelResult {
        val prepared = PreparedOpenResponsesRequest.from(
            params,
            stream = false,
            providerOptionsName = settings.providerOptionsName ?: settings.name,
            modelId,
            settings.fileIdPrefixes,
        )
        val response = postJson(prepared.body, headers = params.headers)
        return openResponsesGenerateResult(
            response = response.value.jsonObject,
            requestBody = prepared.body,
            responseHeaders = response.headers,
            responseBody = response.value,
            warnings = prepared.warnings,
            json = json,
            providerMetadataKey = settings.providerOptionsName ?: settings.name,
        )
    }

    override fun stream(params: LanguageModelCallParams): Flow<StreamEvent> = flow {
        val prepared = PreparedOpenResponsesRequest.from(
            params,
            stream = true,
            providerOptionsName = settings.providerOptionsName ?: settings.name,
            modelId,
            settings.fileIdPrefixes,
        )
        emit(StreamEvent.StreamStart(prepared.warnings))
        val state = OpenResponsesStreamState()
        var sseHeaders: Map<String, String> = emptyMap()
        val rawLines = streamResponsesSse(prepared.body, params.headers) { sseHeaders = it }
        with(HttpTransport) {
            forwardSseEvents(
                events = EventStreamParser.parse(rawLines, Schemas.jsonSchema<JsonElement>(JsonObject(emptyMap())), json),
                capturedHeaders = { sseHeaders },
                parseErrorPrefix = "Failed to parse Open Responses stream event",
                onEvent = { state.accept(it).forEach { e -> emit(e) } },
            )
        }
        state.finish().forEach { emit(it) }
    }

    override fun streamResult(params: LanguageModelCallParams): LanguageModelStreamResult {
        val prepared = PreparedOpenResponsesRequest.from(
            params,
            stream = true,
            providerOptionsName = settings.providerOptionsName ?: settings.name,
            modelId,
            settings.fileIdPrefixes,
        )
        return LanguageModelStreamResult(
            stream = stream(params),
            request = LanguageModelRequestMetadata(body = prepared.body),
        )
    }

    private suspend fun postJson(
        body: JsonElement,
        headers: Map<String, String> = emptyMap(),
    ): OpenResponsesHttpResponse {
        val response = client.request(settings.url) {
            method = HttpMethod.Post
            contentType(ContentType.Application.Json)
            requestHeaders(headers).forEach { (name, value) -> header(name, value) }
            setBody(json.encodeToString(JsonElement.serializer(), body))
        }
        return parseResponse(response, parseJson = true)
    }

    /** Streaming counterpart of [postJson]: reads the SSE body incrementally,
     *  surfacing non-2xx as the same rich [APICallError] as [parseResponse]. */
    private fun streamResponsesSse(
        body: JsonElement,
        headers: Map<String, String>,
        onResponse: suspend (Map<String, String>) -> Unit,
    ): Flow<String> = flow {
        emitAll(
            HttpTransport.streamSse(client = client,
            url = settings.url,
            method = HttpMethod.Post,
            headers = requestHeaders(headers) + (HttpHeaders.Accept to "text/event-stream"),
            body = body,
            json = json,
            requestBodyValues = body,
            errorMessage = { _, parsed, raw ->
                val errorObj = (parsed as? JsonObject)?.get("error") as? JsonObject
                (errorObj?.get("message") as? JsonPrimitive)?.contentOrNull
                    ?: raw.ifBlank { "Open Responses request failed" }
            },
            onResponse = onResponse,),
        )
    }

    private suspend fun requestHeaders(extra: Map<String, String>): Map<String, String> {
        val base = linkedMapOf<String, String>()
        val dynamicAuthHeaders = settings.authHeadersProvider?.invoke()
        if (dynamicAuthHeaders != null) {
            base.putAll(dynamicAuthHeaders)
        } else {
            settings.apiKey?.takeIf { it.isNotBlank() }?.let { base[HttpHeaders.Authorization] = "Bearer $it" }
        }
        base.putAll(settings.headers)
        base.putAll(extra)
        return settings.userAgentSuffix
            ?.let { ProviderHeaders.withUserAgentSuffix(base, it) }
            ?: ProviderHeaders.normalize(base)
    }

    private suspend fun parseResponse(
        response: HttpResponse,
        parseJson: Boolean,
    ): OpenResponsesHttpResponse {
        val raw = response.bodyAsText()
        val headers = response.headers.entries().associate { it.key to it.value.joinToString(",") }
        if (response.status.value !in 200..299) {
            throw openResponsesErrorFromResponse(response, raw, headers)
        }
        return OpenResponsesHttpResponse(
            value = if (parseJson && raw.isNotBlank()) json.parseToJsonElement(raw) else JsonObject(emptyMap()),
            rawText = raw,
            headers = headers,
        )
    }

    private fun openResponsesResultProviderMetadata(
        response: JsonObject,
        providerMetadataKey: String,
        logprobs: List<JsonElement>,
    ): ProviderMetadata {
        val metadata = buildJsonObject {
            (response["id"] as? JsonPrimitive)?.contentOrNull?.let { put("responseId", JsonPrimitive(it)) }
            if (logprobs.isNotEmpty()) put("logprobs", JsonArray(logprobs))
        }
        return if (metadata.isEmpty()) ProviderMetadata.None
        else ProviderMetadata.Raw(JsonObject(mapOf(providerMetadataKey to metadata)))
    }

    private fun openResponsesPartMetadata(
        providerMetadataKey: String,
        itemId: String?,
        obj: JsonObject,
    ): ProviderMetadata {
        val metadata = buildJsonObject {
            itemId?.let { put("itemId", JsonPrimitive(it)) }
            (JsonAccess.arr(obj, "annotations"))?.takeIf { it.isNotEmpty() }?.let { put("annotations", it) }
            obj["logprobs"]?.let { put("logprobs", it) }
            obj["encrypted_content"]?.let { put("encryptedContent", it) }
        }
        return if (metadata.isEmpty()) ProviderMetadata.None
        else ProviderMetadata.Raw(JsonObject(mapOf(providerMetadataKey to metadata)))
    }

    private fun openResponsesGenerateResult(
        response: JsonObject,
        requestBody: JsonElement,
        responseHeaders: Map<String, String>,
        responseBody: JsonElement,
        warnings: List<CallWarning>,
        json: Json,
        providerMetadataKey: String,
    ): LanguageModelResult {
        val content = mutableListOf<ContentPart>()
        val toolCalls = mutableListOf<ContentPart.ToolCall>()
        val logprobs = mutableListOf<JsonElement>()
        var hasToolCalls = false

        (JsonAccess.arr(response, "output")).orEmpty().forEachIndexed { index, part ->
            val obj = part as? JsonObject ?: return@forEachIndexed
            val itemId = (obj["id"] as? JsonPrimitive)?.contentOrNull
            val path = "$.output[$index]"
            when ((obj["type"] as? JsonPrimitive)?.contentOrNull) {
                "reasoning" -> {
                    val reasoningParts = JsonAccess.arr(obj, "content") ?: JsonAccess.arr(obj, "summary").orEmpty()
                    reasoningParts.forEach { reasoning ->
                        val reasoningObj = reasoning as? JsonObject ?: return@forEach
                        val text = (reasoningObj["text"] as? JsonPrimitive)?.contentOrNull
                        if (!text.isNullOrEmpty()) {
                            content += ContentPart.Reasoning(
                                text,
                                openResponsesPartMetadata(providerMetadataKey, itemId, reasoningObj),
                            )
                        }
                    }
                }
                "message" -> {
                    (JsonAccess.arr(obj, "content")).orEmpty().forEach { messagePart ->
                        val messageObj = messagePart as? JsonObject ?: return@forEach
                        val text = (messageObj["text"] as? JsonPrimitive)?.contentOrNull
                            ?: (messageObj["refusal"] as? JsonPrimitive)?.contentOrNull
                        messageObj["logprobs"]?.let { logprobs += it }
                        if (!text.isNullOrEmpty()) {
                            content += ContentPart.Text(text, openResponsesPartMetadata(providerMetadataKey, itemId, messageObj))
                        }
                    }
                }
                "function_call" -> {
                    hasToolCalls = true
                    val toolCallId = WireDecoder.requiredString(obj, "call_id", "Open Responses", "response output", path)
                    if (toolCallId.isBlank()) {
                        WireDecoder.fail(
                            "Open Responses",
                            "response output",
                            WireDecoder.child(path, "call_id"),
                            "expected non-blank string",
                            obj["call_id"],
                        )
                    }
                    val toolName = WireDecoder.requiredString(obj, "name", "Open Responses", "response output", path)
                    if (toolName.isBlank()) {
                        WireDecoder.fail(
                            "Open Responses",
                            "response output",
                            WireDecoder.child(path, "name"),
                            "expected non-blank string",
                            obj["name"],
                        )
                    }
                    val toolCall = ContentPart.ToolCall(
                        toolCallId = toolCallId,
                        toolName = toolName,
                        input = parseToolInput((obj["arguments"] as? JsonPrimitive)?.contentOrNull, json),
                        providerMetadata = openResponsesPartMetadata(providerMetadataKey, itemId, obj),
                    )
                    toolCalls += toolCall
                    content += toolCall
                }
                "web_search_call" -> {
                    val toolCallId = WireDecoder.requiredString(obj, "id", "Open Responses", "response output", path)
                    if (toolCallId.isBlank()) {
                        WireDecoder.fail(
                            "Open Responses",
                            "response output",
                            WireDecoder.child(path, "id"),
                            "expected non-blank string",
                            obj["id"],
                        )
                    }
                    val action = obj["action"] as? JsonObject
                    val output = when ((action?.get("type") as? JsonPrimitive)?.contentOrNull) {
                        "search" -> buildJsonObject {
                            put("action", buildJsonObject {
                                put("type", JsonPrimitive("search"))
                                action["query"]?.let { put("query", it) }
                                action["queries"]?.let { put("queries", it) }
                            })
                            action["sources"]?.let { put("sources", it) }
                        }
                        "open_page" -> buildJsonObject {
                            put("action", buildJsonObject {
                                put("type", JsonPrimitive("openPage"))
                                action["url"]?.let { put("url", it) }
                            })
                        }
                        "find_in_page" -> buildJsonObject {
                            put("action", buildJsonObject {
                                put("type", JsonPrimitive("findInPage"))
                                action["url"]?.let { put("url", it) }
                                action["pattern"]?.let { put("pattern", it) }
                            })
                        }
                        else -> action?.let { buildJsonObject { put("action", it) } } ?: JsonObject(emptyMap())
                    }
                    val metadata = openResponsesPartMetadata(providerMetadataKey, toolCallId, obj)
                    val toolCall = ContentPart.ToolCall(
                        toolCallId = toolCallId,
                        toolName = "web_search",
                        input = JsonObject(emptyMap()),
                        providerExecuted = true,
                        providerMetadata = metadata,
                    )
                    toolCalls += toolCall
                    content += toolCall
                    content += ContentPart.ToolResult(
                        toolCallId = toolCallId,
                        toolName = "web_search",
                        output = output,
                        providerExecuted = true,
                        providerMetadata = metadata,
                    )
                }
            }
        }

        val reasonElement = (JsonAccess.obj(response, "incomplete_details"))?.get("reason")
        val incompleteReason = (reasonElement as? JsonPrimitive)?.contentOrNull
        val text = content.filterIsInstance<ContentPart.Text>().joinToString("") { it.text }
        return LanguageModelResult(
            text = text,
            toolCalls = toolCalls,
            finishReason = mapOpenResponsesFinishReason(incompleteReason, hasToolCalls),
            usage = openResponsesUsage(response["usage"]),
            content = content,
            rawFinishReason = incompleteReason,
            warnings = warnings,
            providerMetadata = openResponsesResultProviderMetadata(response, providerMetadataKey, logprobs),
            request = LanguageModelRequestMetadata(body = requestBody),
            response = LanguageModelResponseMetadata(
                id = (response["id"] as? JsonPrimitive)?.contentOrNull,
                // doubleOrNull, not intOrNull: a Unix-seconds timestamp exceeds Int.MAX_VALUE from
                // 2038-01-19, where intOrNull would silently yield null. Mirrors the chat wire.
                timestampMillis = (response["created_at"] as? JsonPrimitive)?.doubleOrNull
                    ?.let { (it * 1000).toLong() },
                modelId = (response["model"] as? JsonPrimitive)?.contentOrNull,
                headers = responseHeaders,
                body = responseBody,
            ),
        )
    }

    private fun openResponsesUsage(element: JsonElement?): Usage {
        val obj = element as? JsonObject ?: return Usage()
        val inputTokens = (obj["input_tokens"] as? JsonPrimitive)?.intOrNull ?: 0
        val cachedInputTokens = (((JsonAccess.obj(obj, "input_tokens_details"))?.get("cached_tokens") as? JsonPrimitive)?.intOrNull ?: 0)
            .coerceIn(0, inputTokens)
        val outputTokens = (obj["output_tokens"] as? JsonPrimitive)?.intOrNull ?: 0
        val reasoningTokens = (((JsonAccess.obj(obj, "output_tokens_details"))?.get("reasoning_tokens") as? JsonPrimitive)?.intOrNull ?: 0)
            .coerceAtLeast(0)
        val outputTotal = if (reasoningTokens > outputTokens) outputTokens + reasoningTokens else outputTokens
        return Usage(
            inputTokens = Usage.InputTokenBreakdown(
                total = inputTokens,
                noCache = (inputTokens - cachedInputTokens).coerceAtLeast(0),
                cacheRead = cachedInputTokens,
            ),
            outputTokens = Usage.OutputTokenBreakdown(
                total = outputTotal,
                text = outputTotal - reasoningTokens,
                reasoning = reasoningTokens,
            ),
            raw = element,
        )
    }

    private fun mapOpenResponsesFinishReason(reason: String?, hasToolCalls: Boolean): FinishReason = when (reason) {
        null -> if (hasToolCalls) FinishReason.ToolCalls else FinishReason.Stop
        "max_output_tokens" -> FinishReason.Length
        "content_filter" -> FinishReason.ContentFilter
        else -> if (hasToolCalls) FinishReason.ToolCalls else FinishReason.Other
    }

    private fun parseToolInput(arguments: String?, json: Json): JsonElement =
        if (arguments.isNullOrBlank()) {
            JsonObject(emptyMap())
        } else {
            runCatching { json.parseToJsonElement(arguments) }.getOrElse { JsonPrimitive(arguments) }
        }

    private fun openResponsesErrorFromResponse(
        response: HttpResponse,
        raw: String,
        headers: Map<String, String>,
    ): APICallError {
        val message = runCatching {
            val obj = openResponsesJson.parseToJsonElement(raw).jsonObject
            ((JsonAccess.obj(obj, "error"))?.get("message") as? JsonPrimitive)?.contentOrNull
        }.getOrNull()
        return ApiCallError(
            url = response.call.request.url.toString(),
            statusCode = response.status.value,
            rawBody = raw,
            headers = headers,
            message = message ?: raw.ifBlank { "Open Responses request failed" },
        )
    }

    private inner class OpenResponsesStreamState {
        private val toolCallsByItemId = mutableMapOf<String, PendingOpenResponsesToolCall>()
        private var finishReason = FinishReason.Other
        private var rawFinishReason: String? = null
        private var usage = Usage()
        private var hasToolCalls = false
        private var activeReasoningId: String? = null

        fun accept(chunk: JsonElement): List<StreamEvent> {
            val obj = chunk as? JsonObject ?: return emptyList()
            val type = (obj["type"] as? JsonPrimitive)?.contentOrNull ?: return emptyList()
            val events = mutableListOf<StreamEvent>()
            when (type) {
                "response.output_item.added" -> {
                    val item = JsonAccess.obj(obj, "item")
                        ?: return listOf(StreamEvent.Error("Open Responses stream protocol error: response.output_item.added missing item."))
                    val itemType = (item["type"] as? JsonPrimitive)?.contentOrNull
                    when (itemType) {
                        "function_call" -> {
                            val itemId = itemIdFromItem(item, obj) ?: return listOf(missingIdentityError(type, "item_id"))
                            toolCallsByItemId[itemId] = PendingOpenResponsesToolCall(
                                toolName = (item["name"] as? JsonPrimitive)?.contentOrNull,
                                toolCallId = (item["call_id"] as? JsonPrimitive)?.contentOrNull,
                                arguments = (item["arguments"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                            )
                        }
                        "web_search_call" -> {
                            val itemId = itemIdFromItem(item, obj) ?: return listOf(missingIdentityError(type, "item_id"))
                            events += StreamEvent.ToolCall(
                                toolCallId = itemId,
                                toolName = "web_search",
                                inputJson = JsonObject(emptyMap()),
                                providerMetadata = openResponsesPartMetadata(settings.providerOptionsName ?: settings.name, itemId, item),
                            )
                        }
                        "reasoning" -> {
                            val itemId = (item["id"] as? JsonPrimitive)?.contentOrNull
                                ?: (obj["item_id"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                            activeReasoningId = itemId
                            events += StreamEvent.ReasoningStart(itemId)
                        }
                        "message" -> {
                            val itemId = (item["id"] as? JsonPrimitive)?.contentOrNull
                                ?: (obj["item_id"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                            events += StreamEvent.TextStart(itemId)
                        }
                    }
                }
                "response.function_call_arguments.delta" -> {
                    val itemId = itemIdFromEvent(obj) ?: return listOf(missingIdentityError(type, "item_id"))
                    val pending = toolCallsByItemId.getOrPut(itemId) { PendingOpenResponsesToolCall() }
                    pending.arguments += (obj["delta"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                }
                "response.function_call_arguments.done" -> {
                    val itemId = itemIdFromEvent(obj) ?: return listOf(missingIdentityError(type, "item_id"))
                    val pending = toolCallsByItemId.getOrPut(itemId) { PendingOpenResponsesToolCall() }
                    pending.arguments = (obj["arguments"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                }
                "response.output_item.done" -> {
                    val item = JsonAccess.obj(obj, "item")
                        ?: return listOf(StreamEvent.Error("Open Responses stream protocol error: response.output_item.done missing item."))
                    val itemType = (item["type"] as? JsonPrimitive)?.contentOrNull
                    when (itemType) {
                        "function_call" -> {
                            val itemId = itemIdFromItem(item, obj) ?: return listOf(missingIdentityError(type, "item_id"))
                            val pending = toolCallsByItemId.remove(itemId)
                            val toolName = pending?.toolName?.takeIf { it.isNotBlank() }
                                ?: (item["name"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                                ?: return listOf(missingIdentityError(type, "name"))
                            val toolCallId = pending?.toolCallId?.takeIf { it.isNotBlank() }
                                ?: (item["call_id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                                ?: return listOf(missingIdentityError(type, "call_id"))
                            val arguments = pending?.arguments?.takeIf { it.isNotEmpty() }
                                ?: (item["arguments"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
                                ?: pending?.arguments
                            events += StreamEvent.ToolCall(toolCallId, toolName, parseToolInput(arguments, json))
                            hasToolCalls = true
                        }
                        "web_search_call" -> {
                            val itemId = itemIdFromItem(item, obj) ?: return listOf(missingIdentityError(type, "item_id"))
                            val action = item["action"] as? JsonObject
                            val output = when ((action?.get("type") as? JsonPrimitive)?.contentOrNull) {
                                "search" -> buildJsonObject {
                                    put("action", buildJsonObject {
                                        put("type", JsonPrimitive("search"))
                                        action["query"]?.let { put("query", it) }
                                        action["queries"]?.let { put("queries", it) }
                                    })
                                    action["sources"]?.let { put("sources", it) }
                                }
                                "open_page" -> buildJsonObject {
                                    put("action", buildJsonObject {
                                        put("type", JsonPrimitive("openPage"))
                                        action["url"]?.let { put("url", it) }
                                    })
                                }
                                "find_in_page" -> buildJsonObject {
                                    put("action", buildJsonObject {
                                        put("type", JsonPrimitive("findInPage"))
                                        action["url"]?.let { put("url", it) }
                                        action["pattern"]?.let { put("pattern", it) }
                                    })
                                }
                                else -> action?.let { buildJsonObject { put("action", it) } } ?: JsonObject(emptyMap())
                            }
                            events += StreamEvent.ToolResult(
                                toolCallId = itemId,
                                toolName = "web_search",
                                outputJson = output,
                                providerMetadata = openResponsesPartMetadata(settings.providerOptionsName ?: settings.name, itemId, item),
                            )
                        }
                        "reasoning" -> {
                            val itemId = (item["id"] as? JsonPrimitive)?.contentOrNull
                                ?: (obj["item_id"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                            events += StreamEvent.ReasoningEnd(itemId)
                            activeReasoningId = null
                        }
                        "message" -> {
                            val itemId = (item["id"] as? JsonPrimitive)?.contentOrNull
                                ?: (obj["item_id"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                            events += StreamEvent.TextEnd(itemId)
                        }
                    }
                }
                "response.reasoning_summary_text.delta", "response.reasoning_text.delta" ->
                    events += StreamEvent.ReasoningDelta(
                        id = (obj["item_id"] as? JsonPrimitive)?.contentOrNull ?: activeReasoningId ?: "reasoning-0",
                        text = (obj["delta"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                    )
                "response.output_text.delta" -> events += StreamEvent.TextDelta(
                    id = (obj["item_id"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                    text = (obj["delta"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                )
                "response.completed",
                "response.incomplete",
                -> {
                    val response = (JsonAccess.obj(obj, "response")) ?: JsonObject(emptyMap())
                    val reasonElement = (JsonAccess.obj(response, "incomplete_details"))?.get("reason")
                    rawFinishReason = (reasonElement as? JsonPrimitive)?.contentOrNull
                    finishReason = mapOpenResponsesFinishReason(rawFinishReason, hasToolCalls)
                    usage = openResponsesUsage(response["usage"])
                }
                "response.failed" -> {
                    val response = (JsonAccess.obj(obj, "response")) ?: JsonObject(emptyMap())
                    finishReason = FinishReason.Error
                    rawFinishReason = (JsonAccess.obj(response, "error")?.get("code") as? JsonPrimitive)?.contentOrNull
                        ?: (response["status"] as? JsonPrimitive)?.contentOrNull
                    usage = openResponsesUsage(response["usage"])
                    events += StreamEvent.Error(OpenResponsesStreamFailure.message(response))
                }
            }
            return events
        }

        private fun itemIdFromItem(item: JsonObject, event: JsonObject): String? =
            (item["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: itemIdFromEvent(event)

        private fun itemIdFromEvent(event: JsonObject): String? =
            (event["item_id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

        private fun missingIdentityError(eventType: String, field: String): StreamEvent.Error =
            StreamEvent.Error("Open Responses stream protocol error: $eventType missing required $field.")

        fun finish(): List<StreamEvent> = buildList {
            activeReasoningId?.let { add(StreamEvent.ReasoningEnd(it)) }
            add(
                StreamEvent.Finish(
                    totalSteps = 1,
                    finishReason = finishReason,
                    usage = usage,
                    rawFinishReason = rawFinishReason,
                ),
            )
        }
    }
}

private data class OpenResponsesHttpResponse(
    val value: JsonElement,
    val rawText: String,
    val headers: Map<String, String>,
)

private data class OpenResponsesRequestBuildContext(
    val params: LanguageModelCallParams,
    val stream: Boolean,
    val modelId: String,
    val convertedInput: ConvertedOpenResponsesInput,
    val providerOptions: OpenResponsesOptions?,
    val include: JsonArray?,
    val topLogprobs: Int?,
)

internal data class PreparedOpenResponsesRequest(
    val body: JsonObject,
    val warnings: List<CallWarning>,
) {
    companion object {
        internal fun from(
            params: LanguageModelCallParams,
            stream: Boolean,
            providerOptionsName: String,
            modelId: String,
            fileIdPrefixes: List<String> = emptyList(),
        ): PreparedOpenResponsesRequest {
            val warnings = unsupportedWarnings(params)
            val convertedInput = ConvertedOpenResponsesInput.from(params.messages, warnings, fileIdPrefixes)
            val providerOptions = openResponsesProviderOptions(params.providerOptions, providerOptionsName)
            val topLogprobs = openResponsesTopLogprobs(providerOptions?.logprobs)
            val include = openResponsesInclude(providerOptions, params.tools, modelId, topLogprobs)
            val context = OpenResponsesRequestBuildContext(
                params = params,
                stream = stream,
                modelId = modelId,
                convertedInput = convertedInput,
                providerOptions = providerOptions,
                include = include,
                topLogprobs = topLogprobs,
            )
            return PreparedOpenResponsesRequest(
                body = openResponsesRequestBody(context),
                warnings = warnings,
            )
        }

        private fun unsupportedWarnings(params: LanguageModelCallParams): MutableList<CallWarning> = buildList {
            if (params.stopSequences.isNotEmpty()) {
                add(CallWarning("unsupported", "stopSequences are not supported by Open Responses models"))
            }
            params.topK?.let {
                add(CallWarning("unsupported", "topK is not supported by Open Responses models"))
            }
            params.seed?.let {
                add(CallWarning("unsupported", "seed is not supported by Open Responses models"))
            }
        }.toMutableList()

        private fun openResponsesRequestBody(context: OpenResponsesRequestBuildContext): JsonObject =
            buildJsonObject {
                putCoreRequestFields(this, context)
                putToolAndTextFields(this, context)
                putProviderOptionFields(this, context)
            }

        private fun putCoreRequestFields(builder: JsonObjectBuilder, context: OpenResponsesRequestBuildContext) {
            val params = context.params
            val providerOptions = context.providerOptions
            builder.put("model", JsonPrimitive(context.modelId))
            builder.put("input", context.convertedInput.input)
            val instructions = when (providerOptions?.systemMessageMode) {
                "remove" -> providerOptions.instructions
                else -> providerOptions?.instructions ?: context.convertedInput.instructions
            }
            instructions?.let { builder.put("instructions", JsonPrimitive(it)) }
            params.maxOutputTokens?.let { builder.put("max_output_tokens", JsonPrimitive(it)) }
            params.temperature?.let { builder.put("temperature", JsonPrimitive(it)) }
            params.topP?.let { builder.put("top_p", JsonPrimitive(it)) }
            params.presencePenalty?.let { builder.put("presence_penalty", JsonPrimitive(it)) }
            params.frequencyPenalty?.let { builder.put("frequency_penalty", JsonPrimitive(it)) }
            if (context.stream) builder.put("stream", JsonPrimitive(true))
        }

        private fun putToolAndTextFields(builder: JsonObjectBuilder, context: OpenResponsesRequestBuildContext) {
            val params = context.params
            val providerOptions = context.providerOptions
            val reasoning = openResponsesReasoning(providerOptions)
            if (reasoning.isNotEmpty()) builder.put("reasoning", JsonObject(reasoning))
            if (params.tools.isNotEmpty()) builder.put("tools", JsonArray(params.tools.map(::openResponsesToolJson)))
            openResponsesToolChoice(params.toolChoice, providerOptions)?.let { builder.put("tool_choice", it) }
            val text = openResponsesText(params.responseFormat, providerOptions)
            if (text.isNotEmpty()) builder.put("text", JsonObject(text))
        }

        private fun putProviderOptionFields(builder: JsonObjectBuilder, context: OpenResponsesRequestBuildContext) {
            val providerOptions = context.providerOptions
            putStringOption(builder, "conversation", providerOptions?.conversation)
            putIntOption(builder, "max_tool_calls", providerOptions?.maxToolCalls)
            putJsonOption(builder, "metadata", providerOptions?.metadata)
            putBooleanOption(builder, "parallel_tool_calls", providerOptions?.parallelToolCalls)
            putStringOption(builder, "previous_response_id", providerOptions?.previousResponseId)
            putStringOption(builder, "prompt_cache_key", providerOptions?.promptCacheKey)
            putStringOption(builder, "prompt_cache_retention", providerOptions?.promptCacheRetention)
            putStringOption(builder, "safety_identifier", providerOptions?.safetyIdentifier)
            putStringOption(builder, "service_tier", providerOptions?.serviceTier)
            putBooleanOption(builder, "store", providerOptions?.store)
            putStringOption(builder, "truncation", providerOptions?.truncation)
            putStringOption(builder, "user", providerOptions?.user)
            putJsonOption(builder, "include", context.include)
            putIntOption(builder, "top_logprobs", context.topLogprobs)
        }

        private fun putStringOption(builder: JsonObjectBuilder, name: String, value: String?) {
            if (value != null) builder.put(name, JsonPrimitive(value))
        }

        private fun putIntOption(builder: JsonObjectBuilder, name: String, value: Int?) {
            if (value != null) builder.put(name, JsonPrimitive(value))
        }

        private fun putBooleanOption(builder: JsonObjectBuilder, name: String, value: Boolean?) {
            if (value != null) builder.put(name, JsonPrimitive(value))
        }

        private fun putJsonOption(builder: JsonObjectBuilder, name: String, value: JsonElement?) {
            if (value != null) builder.put(name, value)
        }

        private fun openResponsesProviderOptions(
            providerOptions: ProviderOptions,
            providerOptionsName: String,
        ): OpenResponsesOptions? {
            val poMap = providerOptions.toMap()
            val element = poMap[providerOptionsName] ?: poMap["open-responses"] ?: return null
            // Surface a malformed options block instead of swallowing it to null — getOrNull() here
            // silently dropped EVERY user option (instructions, reasoningEffort, store, …) on a single
            // wrong-typed field, with no error and no clue why the request behaved as if unconfigured.
            return try {
                WireDecoder.decode(
                    OpenResponsesOptions.serializer(),
                    element,
                    provider = "Open Responses",
                    operation = "provider options",
                    path = "$.providerOptions.$providerOptionsName",
                )
            } catch (e: WireDecodeException) {
                throw InvalidArgumentError(
                    "providerOptions.$providerOptionsName",
                    "could not decode OpenResponses provider options: ${e.message ?: "<no message>"}",
                    e,
                )
            }
        }

        private fun openResponsesReasoning(options: OpenResponsesOptions?): Map<String, JsonElement> = buildMap {
            options?.reasoningEffort?.let { put("effort", JsonPrimitive(it)) }
            options?.reasoningSummary?.let { put("summary", JsonPrimitive(it)) }
        }

        private fun openResponsesToolJson(tool: LanguageModelTool): JsonObject =
            if (tool.providerExecuted) openResponsesProviderToolJson(tool) else openResponsesFunctionToolJson(tool)

        private fun openResponsesFunctionToolJson(tool: LanguageModelTool): JsonObject = buildJsonObject {
            put("type", JsonPrimitive("function"))
            put("name", JsonPrimitive(tool.name))
            put("description", JsonPrimitive(tool.description))
            put("parameters", openResponsesJson.parseToJsonElement(tool.parametersSchemaJson))
            tool.strict?.let { put("strict", JsonPrimitive(it)) }
        }

        private fun openResponsesProviderToolJson(tool: LanguageModelTool): JsonObject = buildJsonObject {
            val args = openResponsesProviderToolArgs(tool)
            when (val type = openResponsesProviderToolType(tool)) {
                "file_search" -> {
                    put("type", JsonPrimitive(type))
                    putOpenResponsesField("vector_store_ids", args["vectorStoreIds"] ?: args["vector_store_ids"])
                    putOpenResponsesField("max_num_results", args["maxNumResults"] ?: args["max_num_results"])
                    openResponsesRankingOptions(args)?.let { put("ranking_options", it) }
                    putOpenResponsesField("filters", args["filters"])
                }
                "web_search", "web_search_preview" -> {
                    put("type", JsonPrimitive(type))
                    putOpenResponsesField("external_web_access", args["externalWebAccess"] ?: args["external_web_access"])
                    putOpenResponsesField("filters", openResponsesWebSearchFilters(args["filters"]))
                    putOpenResponsesField("search_context_size", args["searchContextSize"] ?: args["search_context_size"])
                    putOpenResponsesField("user_location", args["userLocation"] ?: args["user_location"])
                }
                "code_interpreter" -> {
                    put("type", JsonPrimitive(type))
                    put("container", openResponsesCodeInterpreterContainer(args["container"]))
                }
                "image_generation" -> {
                    put("type", JsonPrimitive(type))
                    putOpenResponsesField("background", args["background"])
                    putOpenResponsesField("input_fidelity", args["inputFidelity"] ?: args["input_fidelity"])
                    putOpenResponsesField("input_image_mask", openResponsesInputImageMask(args["inputImageMask"] ?: args["input_image_mask"]))
                    putOpenResponsesField("model", args["model"])
                    putOpenResponsesField("moderation", args["moderation"])
                    putOpenResponsesField("partial_images", args["partialImages"] ?: args["partial_images"])
                    putOpenResponsesField("quality", args["quality"])
                    putOpenResponsesField("output_compression", args["outputCompression"] ?: args["output_compression"])
                    putOpenResponsesField("output_format", args["outputFormat"] ?: args["output_format"])
                    putOpenResponsesField("size", args["size"])
                }
                "mcp" -> {
                    put("type", JsonPrimitive(type))
                    putOpenResponsesField("server_label", args["serverLabel"] ?: args["server_label"])
                    putOpenResponsesField("allowed_tools", openResponsesAllowedMcpTools(args["allowedTools"] ?: args["allowed_tools"]))
                    putOpenResponsesField("authorization", args["authorization"])
                    putOpenResponsesField("connector_id", args["connectorId"] ?: args["connector_id"])
                    putOpenResponsesField("headers", args["headers"])
                    putOpenResponsesField("require_approval", openResponsesRequireApproval(args["requireApproval"] ?: args["require_approval"]))
                    putOpenResponsesField("server_description", args["serverDescription"] ?: args["server_description"])
                    putOpenResponsesField("server_url", args["serverUrl"] ?: args["server_url"])
                }
                "shell" -> {
                    put("type", JsonPrimitive(type))
                    putOpenResponsesField("environment", openResponsesShellEnvironment(args["environment"]))
                }
                "tool_search" -> {
                    put("type", JsonPrimitive(type))
                    putOpenResponsesField("execution", args["execution"])
                    putOpenResponsesField("description", args["description"])
                    putOpenResponsesField("parameters", args["parameters"])
                }
                "custom" -> {
                    put("type", JsonPrimitive(type))
                    putOpenResponsesField("name", args["name"] ?: JsonPrimitive(tool.name))
                    putOpenResponsesField("description", args["description"] ?: JsonPrimitive(tool.description))
                    putOpenResponsesField("format", args["format"])
                }
                else -> put("type", JsonPrimitive(type))
            }
        }

        private fun openResponsesToolChoice(
            choice: ToolChoice,
            options: OpenResponsesOptions?,
        ): JsonElement? {
            options?.allowedTools?.takeIf { it.toolNames.isNotEmpty() }?.let { allowed ->
                return buildJsonObject {
                    put("type", JsonPrimitive("allowed_tools"))
                    put("mode", JsonPrimitive(allowed.mode ?: "auto"))
                    put(
                        "tools",
                        JsonArray(
                            allowed.toolNames.map { name ->
                                buildJsonObject {
                                    put("type", JsonPrimitive("function"))
                                    put("name", JsonPrimitive(name))
                                }
                            },
                        ),
                    )
                }
            }
            return when (choice) {
                ToolChoice.Auto -> JsonPrimitive("auto")
                ToolChoice.None -> JsonPrimitive("none")
                ToolChoice.Required -> JsonPrimitive("required")
                is ToolChoice.Specific -> buildJsonObject {
                    val providerToolType = openResponsesProviderToolTypeOrNull(choice.toolName)
                    if (providerToolType != null) {
                        put("type", JsonPrimitive(providerToolType))
                    } else {
                        put("type", JsonPrimitive("function"))
                        put("name", JsonPrimitive(choice.toolName))
                    }
                }
            }
        }

        private fun openResponsesText(
            format: ResponseFormat,
            options: OpenResponsesOptions?,
        ): Map<String, JsonElement> = buildMap {
            openResponsesTextFormat(format, options?.strictJsonSchema ?: true)?.let { put("format", it) }
            options?.textVerbosity?.let { put("verbosity", JsonPrimitive(it)) }
        }

        private fun openResponsesTextFormat(format: ResponseFormat, strict: Boolean): JsonElement? = when (format) {
            ResponseFormat.Text -> null
            // No schema → plain json_object mode. A json_schema format with no `schema` key is malformed.
            is ResponseFormat.Json -> if (format.schemaJson == null) {
                buildJsonObject { put("type", JsonPrimitive("json_object")) }
            } else {
                buildJsonObject {
                    put("type", JsonPrimitive("json_schema"))
                    put("name", JsonPrimitive(format.schemaName ?: "response"))
                    format.schemaDescription?.let { put("description", JsonPrimitive(it)) }
                    put("schema", format.schemaJson)
                    put("strict", JsonPrimitive(strict))
                }
            }
        }

        private fun openResponsesTopLogprobs(logprobs: JsonElement?): Int? {
            val primitive = (logprobs as? JsonPrimitive) ?: return null
            primitive.booleanOrNull?.let { return if (it) OPEN_RESPONSES_TOP_LOGPROBS_MAX else null }
            return primitive.intOrNull?.coerceIn(1, OPEN_RESPONSES_TOP_LOGPROBS_MAX)
        }

        private fun openResponsesInclude(
            options: OpenResponsesOptions?,
            tools: List<LanguageModelTool>,
            modelId: String,
            topLogprobs: Int?,
        ): JsonArray? {
            val include = linkedSetOf<String>()
            options?.include.orEmpty().forEach { include += it }
            if (topLogprobs != null) include += "message.output_text.logprobs"
            if (tools.any { it.providerExecuted && it.name in setOf("web_search", "web_search_preview") }) {
                include += "web_search_call.action.sources"
            }
            if (tools.any { it.providerExecuted && it.name == "code_interpreter" }) {
                include += "code_interpreter_call.outputs"
            }
            if (options?.store == false && isOpenResponsesReasoningModel(modelId, options)) {
                include += "reasoning.encrypted_content"
            }
            return include.takeIf { it.isNotEmpty() }?.let { JsonArray(it.map(::JsonPrimitive)) }
        }

        private fun JsonObjectBuilder.putOpenResponsesField(name: String, value: JsonElement?) {
            if (value != null && value !is JsonNull) put(name, value)
        }

        private fun openResponsesProviderToolArgs(tool: LanguageModelTool): JsonObject =
            (JsonAccess.obj(tool.metadata, "providerToolArgs"))
                ?: (JsonAccess.obj(tool.metadata, "providerOptions"))
                ?: JsonObject(emptyMap())

        private fun openResponsesProviderToolType(tool: LanguageModelTool): String {
            val providerToolId = (tool.metadata["providerToolId"] as? JsonPrimitive)?.contentOrNull
            return providerToolId?.removePrefix("openai.") ?: openResponsesProviderToolTypeOrNull(tool.name) ?: "custom"
        }

        private fun openResponsesProviderToolTypeOrNull(toolName: String): String? = when (toolName) {
            "apply_patch",
            "code_interpreter",
            "file_search",
            "image_generation",
            "local_shell",
            "mcp",
            "shell",
            "tool_search",
            "web_search",
            "web_search_preview",
            -> toolName
            else -> null
        }

        private fun openResponsesRankingOptions(args: JsonObject): JsonObject? {
            val ranking = JsonAccess.obj(args, "ranking") ?: return null
            val mapped = buildJsonObject {
                putOpenResponsesField("ranker", ranking["ranker"])
                putOpenResponsesField("score_threshold", ranking["scoreThreshold"] ?: ranking["score_threshold"])
            }
            return mapped.takeIf { it.isNotEmpty() }
        }

        private fun openResponsesWebSearchFilters(value: JsonElement?): JsonElement? {
            val obj = value as? JsonObject ?: return value
            val allowedDomains = obj["allowedDomains"] ?: obj["allowed_domains"]
            return if (allowedDomains == null) value else buildJsonObject { put("allowed_domains", allowedDomains) }
        }

        private fun openResponsesCodeInterpreterContainer(value: JsonElement?): JsonElement =
            when (value) {
                null, JsonNull -> buildJsonObject { put("type", JsonPrimitive("auto")) }
                is JsonPrimitive -> value
                is JsonObject -> buildJsonObject {
                    put("type", JsonPrimitive("auto"))
                    putOpenResponsesField("file_ids", value["fileIds"] ?: value["file_ids"])
                }
                else -> value
            }

        private fun openResponsesInputImageMask(value: JsonElement?): JsonElement? {
            val obj = value as? JsonObject ?: return value
            return buildJsonObject {
                putOpenResponsesField("file_id", obj["fileId"] ?: obj["file_id"])
                putOpenResponsesField("image_url", obj["imageUrl"] ?: obj["image_url"])
            }.takeIf { it.isNotEmpty() }
        }

        private fun openResponsesAllowedMcpTools(value: JsonElement?): JsonElement? {
            val obj = value as? JsonObject ?: return value
            return buildJsonObject {
                putOpenResponsesField("read_only", obj["readOnly"] ?: obj["read_only"])
                putOpenResponsesField("tool_names", obj["toolNames"] ?: obj["tool_names"])
            }.takeIf { it.isNotEmpty() }
        }

        private fun openResponsesRequireApproval(value: JsonElement?): JsonElement? {
            val obj = value as? JsonObject ?: return value
            val never = JsonAccess.obj(obj, "never") ?: return value
            return buildJsonObject {
                put(
                    "never",
                    buildJsonObject {
                        putOpenResponsesField("tool_names", never["toolNames"] ?: never["tool_names"])
                    },
                )
            }
        }

        private fun openResponsesShellEnvironment(value: JsonElement?): JsonElement? {
            val obj = value as? JsonObject ?: return value
            return when ((obj["type"] as? JsonPrimitive)?.contentOrNull) {
                "containerReference" -> buildJsonObject {
                    put("type", JsonPrimitive("container_reference"))
                    putOpenResponsesField("container_id", obj["containerId"] ?: obj["container_id"])
                }
                "containerAuto" -> buildJsonObject {
                    put("type", JsonPrimitive("container_auto"))
                    putOpenResponsesField("file_ids", obj["fileIds"] ?: obj["file_ids"])
                    putOpenResponsesField("memory_limit", obj["memoryLimit"] ?: obj["memory_limit"])
                    putOpenResponsesField("network_policy", obj["networkPolicy"] ?: obj["network_policy"])
                    putOpenResponsesField("skills", obj["skills"])
                }
                else -> value
            }
        }

        private fun isOpenResponsesReasoningModel(modelId: String, options: OpenResponsesOptions?): Boolean =
            options?.forceReasoning == true ||
                modelId == "o1" ||
                modelId.startsWith("o1-") ||
                modelId == "o3" ||
                modelId.startsWith("o3-") ||
                modelId == "o3-mini" ||
                modelId.startsWith("o3-mini-") ||
                modelId == "o4-mini" ||
                modelId.startsWith("o4-mini-") ||
                modelId == "gpt-5" ||
                modelId.startsWith("gpt-5-") ||
                modelId.startsWith("gpt-5.") ||
                modelId.startsWith("gpt-5_")
    }
}

internal data class ConvertedOpenResponsesInput(
    val input: JsonArray,
    val instructions: String?,
) {
    companion object {
        internal fun from(
            messages: List<ModelMessage>,
            warnings: MutableList<CallWarning>,
            fileIdPrefixes: List<String> = emptyList(),
        ): ConvertedOpenResponsesInput {
            val input = mutableListOf<JsonElement>()
            val systemMessages = mutableListOf<String>()

            for (message in messages) {
                when (message.role) {
                    MessageRole.System -> systemMessages += message.content.textContent()
                    MessageRole.User -> input += buildJsonObject {
                        put("type", JsonPrimitive("message"))
                        put("role", JsonPrimitive("user"))
                        put("content", JsonArray(message.content.mapNotNull { openResponsesUserContentPart(it, fileIdPrefixes) }))
                    }
                    MessageRole.Assistant -> {
                        val assistantContent = message.content.mapNotNull(::openResponsesAssistantContentPart)
                        if (assistantContent.isNotEmpty()) {
                            input += buildJsonObject {
                                put("type", JsonPrimitive("message"))
                                put("role", JsonPrimitive("assistant"))
                                put("content", JsonArray(assistantContent))
                            }
                        }
                        message.content.filterIsInstance<ContentPart.ToolCall>().forEach { toolCall ->
                            input += buildJsonObject {
                                put("type", JsonPrimitive("function_call"))
                                put("call_id", JsonPrimitive(toolCall.toolCallId))
                                put("name", JsonPrimitive(toolCall.toolName))
                                put("arguments", JsonPrimitive(toolCall.input.toString()))
                            }
                        }
                    }
                    MessageRole.Tool -> message.content.filterIsInstance<ContentPart.ToolResult>().forEach { toolResult ->
                        input += buildJsonObject {
                            put("type", JsonPrimitive("function_call_output"))
                            put("call_id", JsonPrimitive(toolResult.toolCallId))
                            put(
                                "output",
                                openResponsesToolOutput(
                                    ToolResultOutputs.toolResultOutputFromWire(toolResult.modelVisible),
                                    warnings,
                                ),
                            )
                        }
                    }
                }
            }

            return ConvertedOpenResponsesInput(
                input = JsonArray(input),
                instructions = systemMessages.takeIf { it.isNotEmpty() }?.joinToString("\n"),
            )
        }

        private fun List<ContentPart>.textContent(): String =
            joinToString("") { part ->
                when (part) {
                    is ContentPart.Text -> part.text
                    is ContentPart.Reasoning -> part.text
                    is ContentPart.ToolCall,
                    is ContentPart.ToolResult,
                    is ContentPart.ToolApprovalRequest,
                    is ContentPart.ToolApprovalResponse,
                    is ContentPart.Source,
                    is ContentPart.File,
                    is ContentPart.Image,
                    is ContentPart.Raw,
                    -> ""
                }
            }

        private fun isOpenResponsesFileId(value: String, prefixes: List<String>): Boolean =
            prefixes.any { prefix -> prefix.isNotEmpty() && value.startsWith(prefix) } && !isOpenResponsesBase64Payload(value)

        private fun isOpenResponsesBase64Payload(value: String): Boolean =
            runCatching { Base64Codec.decode(value) }.isSuccess

        private fun openResponsesFileId(
            value: String,
            prefixes: List<String>,
            providerMetadata: ProviderMetadata,
        ): String? =
            explicitOpenResponsesFileId(providerMetadata.toMap())
                ?: value.takeIf { isOpenResponsesFileId(it, prefixes) }

        private fun explicitOpenResponsesFileId(providerMetadata: Map<String, JsonElement>?): String? {
            val openai = providerMetadata?.get("openai") as? JsonObject
            return openai?.get("file_id").metadataString()
                ?: openai?.get("fileId").metadataString()
                ?: providerMetadata?.get("file_id").metadataString()
                ?: providerMetadata?.get("fileId").metadataString()
        }

        private fun JsonElement?.metadataString(): String? =
            (this as? JsonPrimitive)?.contentOrNull

        private fun openResponsesUserContentPart(
            part: ContentPart,
            fileIdPrefixes: List<String>,
        ): JsonElement? = when (part) {
            is ContentPart.Text -> buildJsonObject {
                put("type", JsonPrimitive("input_text"))
                put("text", JsonPrimitive(part.text))
            }
            is ContentPart.Image -> buildJsonObject {
                put("type", JsonPrimitive("input_image"))
                val fileId = openResponsesFileId(part.base64, fileIdPrefixes, part.providerMetadata)
                if (fileId != null) {
                    put("file_id", JsonPrimitive(fileId))
                } else {
                    put("image_url", JsonPrimitive(part.url ?: "data:${part.mediaType};base64,${part.base64}"))
                }
            }
            is ContentPart.File -> if (part.mediaType.startsWith("image/")) {
                buildJsonObject {
                    put("type", JsonPrimitive("input_image"))
                    val fileId = openResponsesFileId(part.base64, fileIdPrefixes, part.providerMetadata)
                    if (fileId != null) {
                        put("file_id", JsonPrimitive(fileId))
                    } else {
                        put("image_url", JsonPrimitive(part.url ?: "data:${part.mediaType};base64,${part.base64}"))
                    }
                }
            } else {
                buildJsonObject {
                    put("type", JsonPrimitive("input_file"))
                    val fileId = openResponsesFileId(part.base64, fileIdPrefixes, part.providerMetadata)
                    if (fileId != null) {
                        put("filename", JsonPrimitive(part.filename ?: "data"))
                        put("file_id", JsonPrimitive(fileId))
                    } else if (part.url != null) {
                        put("file_url", JsonPrimitive(part.url))
                    } else {
                        put("filename", JsonPrimitive(part.filename ?: "data"))
                        put("file_data", JsonPrimitive("data:${part.mediaType};base64,${part.base64}"))
                    }
                }
            }
            is ContentPart.Reasoning,
            is ContentPart.ToolCall,
            is ContentPart.ToolResult,
            is ContentPart.ToolApprovalRequest,
            is ContentPart.ToolApprovalResponse,
            is ContentPart.Source,
            is ContentPart.Raw,
            -> null
        }

        private fun openResponsesAssistantContentPart(part: ContentPart): JsonElement? = when (part) {
            is ContentPart.Text -> buildJsonObject {
                put("type", JsonPrimitive("output_text"))
                put("text", JsonPrimitive(part.text))
            }
            is ContentPart.Reasoning,
            is ContentPart.ToolCall,
            is ContentPart.ToolResult,
            is ContentPart.ToolApprovalRequest,
            is ContentPart.ToolApprovalResponse,
            is ContentPart.Source,
            is ContentPart.File,
            is ContentPart.Image,
            is ContentPart.Raw,
            -> null
        }

        private fun openResponsesToolOutput(
            output: ToolResultOutput,
            warnings: MutableList<CallWarning>,
        ): JsonElement = when (output) {
            is ToolResultOutput.Text -> JsonPrimitive(output.text)
            is ToolResultOutput.Error -> JsonPrimitive(output.message)
            is ToolResultOutput.ExecutionDenied -> JsonPrimitive(output.reason ?: "Tool execution denied.")
            is ToolResultOutput.Json -> JsonPrimitive(output.json.toString())
            is ToolResultOutput.ErrorJson -> JsonPrimitive(output.json.toString())
            is ToolResultOutput.Content -> JsonArray(output.value.mapNotNull { item ->
                val obj = item as? JsonObject
                when ((obj?.get("type") as? JsonPrimitive)?.contentOrNull) {
                    "text" -> buildJsonObject {
                        put("type", JsonPrimitive("input_text"))
                        put("text", obj["text"] ?: JsonPrimitive(""))
                    }
                    "image-data" -> buildJsonObject {
                        put("type", JsonPrimitive("input_image"))
                        val mediaType = (obj["mediaType"] as? JsonPrimitive)?.contentOrNull
                            ?: "application/octet-stream"
                        val data = (obj["data"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                        put("image_url", JsonPrimitive("data:$mediaType;base64,$data"))
                    }
                    "image-url" -> buildJsonObject {
                        put("type", JsonPrimitive("input_image"))
                        put("image_url", obj["url"] ?: JsonPrimitive(""))
                    }
                    "file-data" -> buildJsonObject {
                        put("type", JsonPrimitive("input_file"))
                        put("filename", obj["filename"] ?: JsonPrimitive("data"))
                        val mediaType = (obj["mediaType"] as? JsonPrimitive)?.contentOrNull
                            ?: "application/octet-stream"
                        val data = (obj["data"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                        put("file_data", JsonPrimitive("data:$mediaType;base64,$data"))
                    }
                    else -> {
                        warnings += CallWarning("other", "unsupported tool content part type: ${obj?.get("type")}")
                        null
                    }
                }
            })
        }
    }
}

private data class PendingOpenResponsesToolCall(
    var toolName: String? = null,
    var toolCallId: String? = null,
    var arguments: String = "",
)

private val openResponsesJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}
