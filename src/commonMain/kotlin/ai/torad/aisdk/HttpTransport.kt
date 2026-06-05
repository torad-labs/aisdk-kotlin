package ai.torad.aisdk

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.utils.io.readLine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** HTTP 2xx success range — the contract for "the request succeeded". */
private val successStatusRange = 200..299

/**
 * Result of a shared HTTP+JSON round-trip: the parsed body element, the raw
 * response text (preserved for SSE/stream consumers), and the flattened
 * response headers. One shape replaces the ~25 per-provider `XxxJsonResponse`
 * carriers.
 */
internal data class HttpJsonResponse(
    val value: JsonElement,
    val rawText: String,
    val headers: Map<String, String>,
)

/** Flatten Ktor's multi-valued headers into the `Map<String, String>` providers carry. */
internal fun HttpResponse.flattenedHeaders(): Map<String, String> =
    headers.entries().associate { it.key to it.value.joinToString(",") }

/**
 * Extracts a human-readable error message from a non-2xx response body. The
 * wire shape varies per provider (`error.message`, `message`, `detail[]`, …),
 * so each call site supplies its own extractor; the `raw` body is the fallback.
 */
internal typealias ErrorMessageExtractor = (statusCode: Int, parsed: JsonElement?, raw: String) -> String

/**
 * Fully overrides error construction from a non-2xx response. Returning null
 * falls back to the default rich [APICallError]; returning a [Throwable] throws
 * it instead (used by providers with their own typed error hierarchy or an
 * in-band control signal).
 */
internal typealias ResponseErrorFactory =
    (statusCode: Int, parsed: JsonElement?, raw: String, headers: Map<String, String>) -> Throwable?

/**
 * Builds an [APICallError] from a non-2xx response, populating the rich fields
 * (url, statusCode, responseHeaders, responseBody) so callers and the retry
 * layer can branch on `statusCode`/`isRetryable` instead of parsing a string.
 *
 * `isRetryable` is left to [APICallError]'s default (408/409/429 or 5xx).
 */
internal fun apiCallError(
    url: String,
    statusCode: Int,
    rawBody: String,
    headers: Map<String, String>,
    message: String,
    requestBodyValues: Any? = null,
): APICallError =
    APICallError(
        message = message,
        url = url,
        requestBodyValues = requestBodyValues,
        statusCode = statusCode,
        responseHeaders = headers,
        responseBody = rawBody,
    )

/**
 * Shared request→read→status-check→parse pipeline. On a non-2xx status it throws
 * a rich [APICallError]; otherwise it returns the parsed [HttpJsonResponse].
 *
 * @param parseJson when false the body is returned raw (`value` is an empty
 *   object) — for SSE/streaming responses the caller parses itself.
 * @param errorMessage extracts the provider-specific error message from the body.
 * @param errorFromResponse fully overrides error construction (e.g. providers
 *   that throw a non-[APICallError] for an in-band control signal). When it
 *   returns null the default [APICallError] is thrown.
 */
internal suspend fun requestJson(
    client: HttpClient,
    url: String,
    method: HttpMethod = HttpMethod.Post,
    headers: Map<String, String> = emptyMap(),
    body: JsonElement? = null,
    json: Json = aiSdkJson,
    parseJson: Boolean = true,
    requestBodyValues: Any? = body,
    errorMessage: ErrorMessageExtractor = ::defaultErrorMessage,
    errorFromResponse: ResponseErrorFactory? = null,
): HttpJsonResponse {
    val response = client.request(url) {
        this.method = method
        if (body != null) {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(JsonElement.serializer(), body))
        }
        headers.forEach { (name, value) -> header(name, value) }
    }
    return response.toJsonResponse(
        url = url,
        json = json,
        parseJson = parseJson,
        requestBodyValues = requestBodyValues,
        errorMessage = errorMessage,
        errorFromResponse = errorFromResponse,
    )
}

/**
 * Applies the shared read→status-check→parse pipeline to a response the caller
 * already obtained (multipart bodies, abort-signal-wrapped requests, …). Throws
 * a rich [APICallError] on a non-2xx status.
 */
internal suspend fun HttpResponse.toJsonResponse(
    url: String,
    json: Json = aiSdkJson,
    parseJson: Boolean = true,
    requestBodyValues: Any? = null,
    errorMessage: ErrorMessageExtractor = ::defaultErrorMessage,
    errorFromResponse: ResponseErrorFactory? = null,
): HttpJsonResponse {
    val raw = bodyAsText()
    val flattened = flattenedHeaders()
    if (status.value !in successStatusRange) {
        val parsed = runCatching { json.parseToJsonElement(raw) }.getOrNull()
        errorFromResponse?.invoke(status.value, parsed, raw, flattened)?.let { throw it }
        throw apiCallError(
            url = url,
            statusCode = status.value,
            rawBody = raw,
            headers = flattened,
            message = errorMessage(status.value, parsed, raw),
            requestBodyValues = requestBodyValues,
        )
    }
    return HttpJsonResponse(
        value = if (parseJson && raw.isNotBlank()) json.parseToJsonElement(raw) else JsonObject(emptyMap()),
        rawText = raw,
        headers = flattened,
    )
}

/**
 * Streaming sibling of [requestJson]. Opens the request with
 * `prepareRequest{}.execute{}` so the response body stays a **live channel**,
 * then emits each raw SSE text line *as it arrives off the wire* — the flow
 * yields incrementally, long before the full body is received (unlike
 * [bodyAsText], which suspends until the whole body lands). Pipe the result
 * through the [parseJsonEventStream] `Flow<String>` overload to recover JSON
 * events. Mirrors the incremental channel read already used by the MCP SSE
 * transport (`processMcpSse`).
 *
 * Each emission is one wire line with its terminator re-appended, so the
 * downstream parser sees the same `data:` / blank-line framing it would from a
 * buffered body. Cancelling the collector cancels the suspended `readLine`,
 * which unwinds through `execute {}` and aborts the request (structured
 * concurrency — no leaked connection).
 *
 * On a non-2xx status this preserves the exact [requestJson] error contract:
 * read the body, run [errorFromResponse] (if any), then throw the rich
 * [apiCallError].
 *
 * @param onResponse invoked once with the flattened response headers as soon as
 *   the 2xx response head arrives, before any body line — lets callers emit a
 *   `ResponseMetadata` event without buffering the body.
 */
internal fun streamSse(
    client: HttpClient,
    url: String,
    method: HttpMethod = HttpMethod.Post,
    headers: Map<String, String> = emptyMap(),
    body: JsonElement? = null,
    json: Json = aiSdkJson,
    requestBodyValues: Any? = body,
    errorMessage: ErrorMessageExtractor = ::defaultErrorMessage,
    errorFromResponse: ResponseErrorFactory? = null,
    onResponse: suspend (Map<String, String>) -> Unit = {},
): Flow<String> = channelFlow {
    // channelFlow (not flow): `execute {}` may run its body — and the channel
    // reads/sends — in a different coroutine/context than the collector
    // (notably on Kotlin/Native engines). A plain `flow { emit() }` enforces
    // same-context emission and throws "Flow invariant is violated" there;
    // `channelFlow { send() }` is concurrency-safe across that boundary.
    val statement = client.prepareRequest(url) {
        this.method = method
        if (body != null) {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(JsonElement.serializer(), body))
        }
        headers.forEach { (name, value) -> header(name, value) }
    }
    statement.execute { response ->
        val flattened = response.flattenedHeaders()
        if (response.status.value !in successStatusRange) {
            val raw = response.bodyAsText()
            val parsed = runCatching { json.parseToJsonElement(raw) }.getOrNull()
            errorFromResponse?.invoke(response.status.value, parsed, raw, flattened)?.let { throw it }
            throw apiCallError(
                url = url,
                statusCode = response.status.value,
                rawBody = raw,
                headers = flattened,
                message = errorMessage(response.status.value, parsed, raw),
                requestBodyValues = requestBodyValues,
            )
        }
        onResponse(flattened)
        val channel = response.bodyAsChannel()
        while (true) {
            val line = channel.readLine() ?: break
            send(line + "\n")
        }
    }
}

/**
 * Drive an SSE provider stream into a `Flow<StreamEvent>` the Kotlin/Native-safe
 * way. The transport's `onResponse` callback runs in the [streamSse]
 * `channelFlow` *producer* coroutine, so emitting a `StreamEvent` from inside it
 * violates Flow's emission-context invariant (it throws on Native). Instead the
 * provider stores the headers from `onResponse` and passes a reader as
 * [capturedHeaders]; this helper emits the leading [StreamEvent.ResponseMetadata]
 * **in the collector's own coroutine** — once, before the first event (or on
 * completion if the stream was empty so the metadata is never dropped).
 *
 * [events] is the already-parsed event flow (`parseJsonEventStream(rawLines,
 * …)`). Each [ParseResult.Success] is handed to [onEvent]; each failure becomes a
 * wire [StreamEvent.Error] prefixed with [parseErrorPrefix].
 */
internal suspend fun FlowCollector<StreamEvent>.forwardSseEvents(
    events: Flow<ParseResult<JsonElement>>,
    capturedHeaders: () -> Map<String, String>,
    parseErrorPrefix: String,
    onEvent: suspend FlowCollector<StreamEvent>.(JsonElement) -> Unit,
) {
    var metadataEmitted = false
    events.collect { result ->
        if (!metadataEmitted) {
            emit(StreamEvent.ResponseMetadata(headers = capturedHeaders()))
            metadataEmitted = true
        }
        when (result) {
            is ParseResult.Success -> onEvent(result.value)
            is ParseResult.Failure -> emit(StreamEvent.Error("$parseErrorPrefix: ${result.error.message}"))
        }
    }
    if (!metadataEmitted) emit(StreamEvent.ResponseMetadata(headers = capturedHeaders()))
}

/**
 * Default error-message extractor: pulls `error.message`, then `error.type`,
 * then a top-level `message`, falling back to the raw body or a generic note.
 * Covers the common OpenAI/Anthropic/Alibaba-style error envelopes.
 */
internal fun defaultErrorMessage(statusCode: Int, parsed: JsonElement?, raw: String): String {
    val obj = parsed as? JsonObject
    val error = obj?.get("error") as? JsonObject
    val detail = error?.jsonStringOrNull("message")
        ?: error?.jsonStringOrNull("type")
        ?: obj?.jsonStringOrNull("message")
        ?: raw.ifBlank { "request failed" }
    return "request failed ($statusCode): $detail"
}

private fun JsonObject.jsonStringOrNull(key: String): String? =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
