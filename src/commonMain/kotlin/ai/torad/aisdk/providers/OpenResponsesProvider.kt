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

/** @since 0.3.0-beta01 */
public val OPEN_RESPONSES_SUPPORTED_URLS: Map<String, List<String>> = mapOf("image/*" to listOf("^https?://.*$"))

/** @since 0.3.0-beta01 */
public class OpenResponsesProviderSettings internal constructor(
    /** @since 0.3.0-beta01 */
    public val url: String,
    /** @since 0.3.0-beta01 */
    public val name: String,
    /** @since 0.3.0-beta01 */
    public val apiKey: String? = null,
    /** @since 0.3.0-beta01 */
    public val headers: Map<String, String> = emptyMap(),
    /** @since 0.3.0-beta01 */
    public val authHeadersProvider: (suspend () -> Map<String, String>)? = null,
    /** @since 0.3.0-beta01 */
    public val userAgentSuffix: String? = "ai-sdk/open-responses/$OPEN_RESPONSES_VERSION",
    /** @since 0.3.0-beta01 */
    public val providerOptionsName: String? = null,
    /** @since 0.3.0-beta01 */
    public val supportedUrls: Map<String, List<String>> = OPEN_RESPONSES_SUPPORTED_URLS,
    /** @since 0.3.0-beta01 */
    public val fileIdPrefixes: List<String> = emptyList(),
)

/** @since 0.3.0-beta01 */
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

    /** @since 0.3.0-beta01 */
    public fun url(value: String): OpenResponsesProviderSettingsBuilder {
        url = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun name(value: String): OpenResponsesProviderSettingsBuilder {
        name = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun apiKey(value: String?): OpenResponsesProviderSettingsBuilder {
        apiKey = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun headers(value: Map<String, String>): OpenResponsesProviderSettingsBuilder {
        headers = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun authHeadersProvider(value: (suspend () -> Map<String, String>)?): OpenResponsesProviderSettingsBuilder {
        authHeadersProvider = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun userAgentSuffix(value: String?): OpenResponsesProviderSettingsBuilder {
        userAgentSuffix = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun providerOptionsName(value: String?): OpenResponsesProviderSettingsBuilder {
        providerOptionsName = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun supportedUrls(value: Map<String, List<String>>): OpenResponsesProviderSettingsBuilder {
        supportedUrls = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun fileIdPrefixes(value: List<String>): OpenResponsesProviderSettingsBuilder {
        fileIdPrefixes = value
        return this
    }

    /** @since 0.3.0-beta01 */
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

/** @since 0.3.0-beta01 */
public fun OpenResponsesProviderSettings(
    block: OpenResponsesProviderSettingsBuilder.() -> Unit = {},
): OpenResponsesProviderSettings =
    OpenResponsesProviderSettingsBuilder().apply(block).build()

@Serializable
@Poko
/** @since 0.3.0-beta01 */
public class OpenResponsesOptions internal constructor(
    /** @since 0.3.0-beta01 */
    public val conversation: String? = null,
    /** @since 0.3.0-beta01 */
    public val include: List<String>? = null,
    /** @since 0.3.0-beta01 */
    public val instructions: String? = null,
    /** @since 0.3.0-beta01 */
    public val logprobs: JsonElement? = null,
    /** @since 0.3.0-beta01 */
    public val maxToolCalls: Int? = null,
    /** @since 0.3.0-beta01 */
    public val metadata: JsonElement? = null,
    /** @since 0.3.0-beta01 */
    public val parallelToolCalls: Boolean? = null,
    /** @since 0.3.0-beta01 */
    public val previousResponseId: String? = null,
    /** @since 0.3.0-beta01 */
    public val promptCacheKey: String? = null,
    /** @since 0.3.0-beta01 */
    public val promptCacheRetention: String? = null,
    /** @since 0.3.0-beta01 */
    public val reasoningEffort: String? = null,
    /** @since 0.3.0-beta01 */
    public val reasoningSummary: String? = null,
    /** @since 0.3.0-beta01 */
    public val safetyIdentifier: String? = null,
    /** @since 0.3.0-beta01 */
    public val serviceTier: String? = null,
    /** @since 0.3.0-beta01 */
    public val store: Boolean? = null,
    /** @since 0.3.0-beta01 */
    public val passThroughUnsupportedFiles: Boolean? = null,
    /** @since 0.3.0-beta01 */
    public val strictJsonSchema: Boolean? = null,
    /** @since 0.3.0-beta01 */
    public val textVerbosity: String? = null,
    /** @since 0.3.0-beta01 */
    public val truncation: String? = null,
    /** @since 0.3.0-beta01 */
    public val user: String? = null,
    /** @since 0.3.0-beta01 */
    public val systemMessageMode: String? = null,
    /** @since 0.3.0-beta01 */
    public val forceReasoning: Boolean? = null,
    /** @since 0.3.0-beta01 */
    public val allowedTools: OpenResponsesAllowedTools? = null,
)

/** @since 0.3.0-beta01 */
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

    /** @since 0.3.0-beta01 */
    public fun conversation(value: String?): OpenResponsesOptionsBuilder {
        conversation = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun include(value: List<String>?): OpenResponsesOptionsBuilder {
        include = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun instructions(value: String?): OpenResponsesOptionsBuilder {
        instructions = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun logprobs(value: JsonElement?): OpenResponsesOptionsBuilder {
        logprobs = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun maxToolCalls(value: Int?): OpenResponsesOptionsBuilder {
        maxToolCalls = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun metadata(value: JsonElement?): OpenResponsesOptionsBuilder {
        metadata = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun parallelToolCalls(value: Boolean?): OpenResponsesOptionsBuilder {
        parallelToolCalls = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun previousResponseId(value: String?): OpenResponsesOptionsBuilder {
        previousResponseId = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun promptCacheKey(value: String?): OpenResponsesOptionsBuilder {
        promptCacheKey = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun promptCacheRetention(value: String?): OpenResponsesOptionsBuilder {
        promptCacheRetention = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun reasoningEffort(value: String?): OpenResponsesOptionsBuilder {
        reasoningEffort = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun reasoningSummary(value: String?): OpenResponsesOptionsBuilder {
        reasoningSummary = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun safetyIdentifier(value: String?): OpenResponsesOptionsBuilder {
        safetyIdentifier = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun serviceTier(value: String?): OpenResponsesOptionsBuilder {
        serviceTier = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun store(value: Boolean?): OpenResponsesOptionsBuilder {
        store = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun passThroughUnsupportedFiles(value: Boolean?): OpenResponsesOptionsBuilder {
        passThroughUnsupportedFiles = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun strictJsonSchema(value: Boolean?): OpenResponsesOptionsBuilder {
        strictJsonSchema = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun textVerbosity(value: String?): OpenResponsesOptionsBuilder {
        textVerbosity = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun truncation(value: String?): OpenResponsesOptionsBuilder {
        truncation = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun user(value: String?): OpenResponsesOptionsBuilder {
        user = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun systemMessageMode(value: String?): OpenResponsesOptionsBuilder {
        systemMessageMode = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun forceReasoning(value: Boolean?): OpenResponsesOptionsBuilder {
        forceReasoning = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun allowedTools(value: OpenResponsesAllowedTools?): OpenResponsesOptionsBuilder {
        allowedTools = value
        return this
    }

    /** @since 0.3.0-beta01 */
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

/** @since 0.3.0-beta01 */
public fun OpenResponsesOptions(
    block: OpenResponsesOptionsBuilder.() -> Unit = {},
): OpenResponsesOptions =
    OpenResponsesOptionsBuilder().apply(block).build()

@Serializable
@Poko
/** @since 0.3.0-beta01 */
public class OpenResponsesAllowedTools internal constructor(
    /** @since 0.3.0-beta01 */
    public val toolNames: List<String> = emptyList(),
    /** @since 0.3.0-beta01 */
    public val mode: String? = null,
)

/** @since 0.3.0-beta01 */
public class OpenResponsesAllowedToolsBuilder {
    private var toolNames: List<String> = emptyList()
    private var mode: String? = null

    /** @since 0.3.0-beta01 */
    public fun toolNames(value: List<String>): OpenResponsesAllowedToolsBuilder {
        toolNames = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun mode(value: String?): OpenResponsesAllowedToolsBuilder {
        mode = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun build(): OpenResponsesAllowedTools =
        OpenResponsesAllowedTools(
            toolNames = toolNames,
            mode = mode,
        )
}

/** @since 0.3.0-beta01 */
public fun OpenResponsesAllowedTools(
    block: OpenResponsesAllowedToolsBuilder.() -> Unit = {},
): OpenResponsesAllowedTools =
    OpenResponsesAllowedToolsBuilder().apply(block).build()

/** @since 0.3.0-beta01 */
public interface OpenResponsesProvider : Provider {
    public operator fun invoke(modelId: String): LanguageModel = languageModel(modelId)
    /** @since 0.3.0-beta01 */
    public fun responses(modelId: String): LanguageModel = languageModel(modelId)
}

/** @since 0.3.0-beta01 */
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

private data class PendingOpenResponsesToolCall(
    var toolName: String? = null,
    var toolCallId: String? = null,
    var arguments: String = "",
)

internal val openResponsesJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}
