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

// HTTP transport for the Google providers: JSON POST/GET (+retry), SSE streaming
// helpers, header assembly, and error-message extraction. Extracted verbatim
// from GoogleProvider.kt.
internal object GoogleHttp {
private val googleErrorExtractor: ErrorMessageExtractor = { _, parsed, raw -> googleErrorMessage(parsed, raw) }

    suspend fun HttpResponse.parseGoogleResponse(
    url: String,
    parseJson: Boolean,
    requestBodyValues: Any? = null,
): HttpJsonResponse =
    with(HttpTransport) { toJsonResponse(
        url = url,
        parseJson = parseJson,
        requestBodyValues = requestBodyValues,
        errorMessage = googleErrorExtractor,
    ) }

    suspend fun googlePostJson(
    client: HttpClient,
    url: String,
    body: JsonElement,
    headers: Map<String, String>,
    abortSignal: AbortSignal,
    parseJson: Boolean,
): HttpJsonResponse {
    abortSignal.throwIfAborted()
    val response = client.request(url) {
        method = HttpMethod.Post
        contentType(ContentType.Application.Json)
        headers.forEach { (name, value) -> header(name, value) }
        setBody(aiSdkJson.encodeToString(JsonElement.serializer(), body))
    }
    return response.parseGoogleResponse(url, parseJson, requestBodyValues = body)
}

/** Streaming counterpart of [googlePostJson]: reads the SSE body incrementally. */
    fun googleStreamSse(
    client: HttpClient,
    url: String,
    body: JsonElement,
    headers: Map<String, String>,
    abortSignal: AbortSignal,
): Flow<String> = flow {
    abortSignal.throwIfAborted()
    emitAll(
        HttpTransport.streamSse(client = client,
        url = url,
        method = HttpMethod.Post,
        headers = headers,
        body = body,
        json = aiSdkJson,
        requestBodyValues = body,
        errorMessage = googleErrorExtractor,),
    )
}

/** Streaming counterpart of [googleGetJson] (background-interaction polling). */
    fun googleStreamSseGet(
    client: HttpClient,
    url: String,
    headers: Map<String, String>,
    abortSignal: AbortSignal,
): Flow<String> = flow {
    abortSignal.throwIfAborted()
    emitAll(
        HttpTransport.streamSse(client = client,
        url = url,
        method = HttpMethod.Get,
        headers = headers,
        body = null,
        json = aiSdkJson,
        errorMessage = googleErrorExtractor,),
    )
}
    suspend fun googleGetJson(
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
    return response.parseGoogleResponse(url, parseJson = parseJson)
}

    suspend fun googleGetJsonWithRetry(
    client: HttpClient,
    url: String,
    headers: Map<String, String>,
    abortSignal: AbortSignal,
    parseJson: Boolean = true,
    maxRetries: Int = 2,
    retryDelayMillis: Long = 0,
): HttpJsonResponse {
    var attempt = 0
    while (true) {
        abortSignal.throwIfAborted()
        val response = client.request(url) {
            method = HttpMethod.Get
            headers.forEach { (name, value) -> header(name, value) }
        }
        if (response.status.value !in 500..599 || attempt >= maxRetries) {
            return response.parseGoogleResponse(url, parseJson = parseJson)
        }
        response.bodyAsText()
        attempt += 1
        if (retryDelayMillis > 0) delay(retryDelayMillis)
    }
}

    fun googleHeaders(settings: GoogleGenerativeAIProviderSettings, extra: Map<String, String>): Map<String, String> {
    val headers = linkedMapOf<String, String>()
    settings.apiKey?.let { headers["x-goog-api-key"] = it }
    headers.putAll(settings.headers)
    headers.putAll(extra)
    headers[HttpHeaders.UserAgent] = appendGoogleUserAgent(headers[HttpHeaders.UserAgent], "ai-sdk/google/$GOOGLE_VERSION")
    return headers
}

    fun googleInteractionsHeaders(settings: GoogleGenerativeAIProviderSettings, extra: Map<String, String>): Map<String, String> =
    googleHeaders(settings, extra) + ("Api-Revision" to "2026-05-20")
    fun googleErrorMessage(parsed: JsonElement?, raw: String): String {
    val obj = parsed as? JsonObject ?: return raw
    val error = obj["error"]
    return (error as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull
        ?: (error as? JsonPrimitive)?.contentOrNull
        ?: obj["message"]?.jsonPrimitive?.contentOrNull
        ?: raw
}
    fun appendGoogleUserAgent(existing: String?, suffix: String): String =
    existing?.takeIf { it.isNotBlank() }?.let { "$it $suffix" } ?: suffix
}
