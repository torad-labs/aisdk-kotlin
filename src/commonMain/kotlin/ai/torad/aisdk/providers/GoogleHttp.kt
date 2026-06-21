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

// Slim shared HTTP transport for the Google providers: the genuinely cross-cutting
// JSON POST + SSE streaming calls used by multiple unrelated Google model classes,
// plus their internal response-parsing / error-extraction helpers. Single-consumer
// transport fns (GET, GET-with-retry, background-poll SSE) and the header builders
// were re-homed onto their owning model classes / settings type.
internal object GoogleHttp {
internal val googleErrorExtractor: ErrorMessageExtractor = { _, parsed, raw -> googleErrorMessage(parsed, raw) }

    suspend fun HttpResponse.parseGoogleResponse(
    url: String,
    parseJson: Boolean,
    requestBodyValues: JsonElement? = null,
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
