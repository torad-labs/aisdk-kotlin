package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import io.ktor.client.HttpClient
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpMethod
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

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
        with(HttpTransport) {
            toJsonResponse(
                url = url,
                parseJson = parseJson,
                requestBodyValues = requestBodyValues,
                errorMessage = googleErrorExtractor,
            )
        }

    // Goes through the shared pipeline (not a hand-rolled client.request) so the round-trip is
    // bounded by DEFAULT_REQUEST_TIMEOUT_MS and an abort fired mid-flight actually cancels the
    // call — a pre-flight throwIfAborted alone left AbortSignal inert once the request was away.
    suspend fun googlePostJson(
        client: HttpClient,
        url: String,
        body: JsonElement,
        headers: Map<String, String>,
        abortSignal: AbortSignal,
        parseJson: Boolean,
    ): HttpJsonResponse =
        AbortSignalRuntime.withAbortCancellation(abortSignal) {
            HttpTransport.requestJson(
                client = client,
                url = url,
                method = HttpMethod.Post,
                headers = headers,
                body = body,
                parseJson = parseJson,
                requestBodyValues = body,
                errorMessage = googleErrorExtractor,
                abortSignal = abortSignal,
            )
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
            HttpTransport.streamSse(
                client = client,
                url = url,
                method = HttpMethod.Post,
                headers = headers,
                body = body,
                json = aiSdkJson,
                requestBodyValues = body,
                errorMessage = googleErrorExtractor,
            ),
        )
    }

    fun googleErrorMessage(parsed: JsonElement?, raw: String): String {
        val obj = parsed as? JsonObject ?: return raw
        val error = obj["error"]
        return ((error as? JsonObject)?.get("message") as? JsonPrimitive)?.contentOrNull
            ?: (error as? JsonPrimitive)?.contentOrNull
            ?: (obj["message"] as? JsonPrimitive)?.contentOrNull
            ?: raw
    }
    fun appendGoogleUserAgent(existing: String?, suffix: String): String =
        existing?.takeIf { it.isNotBlank() }?.let { "$it $suffix" } ?: suffix
}
