package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * HTTP transport base for OpenAI-compatible models: URL/query assembly, common
 * (auth) header resolution, and JSON / SSE / multipart / raw-bytes POST helpers.
 * Extracted verbatim from OpenAICompatibleProvider.kt.
 */
internal abstract class OpenAICompatibleHttpModel(
    protected val client: HttpClient,
    protected val settings: OpenAICompatibleProviderSettings,
    protected val json: Json,
    val modelId: String,
    private val modelType: String,
) {
    protected val providerName: String
        get() = "${settings.name}.$modelType"

    protected fun url(path: String): String {
        settings.urlBuilder?.let { return it(path, modelId) }
        val base = settings.baseUrl.trimEnd('/') + path
        if (settings.queryParams.isEmpty()) return base
        return base + "?" + settings.queryParams.entries.joinToString("&") { (key, value) ->
            "${UrlOps.encode(key)}=${UrlOps.encode(value)}"
        }
    }

    protected suspend fun commonHeaders(extra: Map<String, String> = emptyMap()): Map<String, String> {
        val dynamicAuthHeaders = settings.authHeadersProvider?.invoke()
        return ProviderHeaders.build(settings.headers, extra, settings.userAgentSuffix) { base ->
            if (dynamicAuthHeaders != null) {
                base.putAll(dynamicAuthHeaders)
            } else {
                settings.apiKey?.takeIf { it.isNotBlank() }?.let { base[HttpHeaders.Authorization] = "Bearer $it" }
            }
        }
    }

    protected suspend fun postJson(
        path: String,
        body: JsonElement,
        headers: Map<String, String> = emptyMap(),
        parseJson: Boolean = true,
    ): HttpJsonResponse =
        HttpTransport.requestJson(
            client = client,
            url = url(path),
            method = HttpMethod.Post,
            headers = commonHeaders(headers),
            body = body,
            json = json,
            parseJson = parseJson,
            errorMessage = OpenAICompatibleWire::openAICompatibleErrorMessage,
        )

    /**
     * Streaming counterpart of [postJson]: opens an SSE request and yields raw
     * response lines incrementally (see [streamSse]). The auth/common headers
     * are resolved inside the flow because [commonHeaders] is `suspend`.
     */
    protected fun postSse(
        path: String,
        body: JsonElement,
        headers: Map<String, String> = emptyMap(),
        onResponse: suspend (Map<String, String>) -> Unit = {},
    ): Flow<String> = flow {
        emitAll(
            HttpTransport.streamSse(client = client,
            url = url(path),
            method = HttpMethod.Post,
            headers = commonHeaders(headers),
            body = body,
            json = json,
            errorMessage = OpenAICompatibleWire::openAICompatibleErrorMessage,
            onResponse = onResponse,),
        )
    }

    protected suspend fun postMultipart(
        path: String,
        body: MultiPartFormDataContent,
        headers: Map<String, String> = emptyMap(),
    ): HttpJsonResponse {
        val response = client.request(url(path)) {
            method = HttpMethod.Post
            commonHeaders(headers).forEach { (name, value) -> header(name, value) }
            setBody(body)
        }
        return with(HttpTransport) { response.toJsonResponse(
            url = url(path),
            json = json,
            errorMessage = OpenAICompatibleWire::openAICompatibleErrorMessage,
        ) }
    }

    protected suspend fun postBytes(
        path: String,
        body: JsonElement,
        headers: Map<String, String> = emptyMap(),
    ): OpenAIBytesResponse {
        val response = client.request(url(path)) {
            method = HttpMethod.Post
            contentType(ContentType.Application.Json)
            commonHeaders(headers).forEach { (name, value) -> header(name, value) }
            setBody(json.encodeToString(JsonElement.serializer(), body))
        }
        val responseHeaders = with(HttpTransport) { response.flattenedHeaders() }
        val bytes = response.bodyAsBytes()
        if (response.status.value !in 200..299) {
            val raw = bytes.decodeToString()
            val parsed = runCatching { json.parseToJsonElement(raw) }.getOrNull()
            throw ApiCallError(
                url = url(path),
                statusCode = response.status.value,
                rawBody = raw,
                headers = responseHeaders,
                message = OpenAICompatibleWire.openAICompatibleErrorMessage(response.status.value, parsed, raw),
            )
        }
        return OpenAIBytesResponse(bytes = bytes, headers = responseHeaders)
    }
}

/** Raw-bytes HTTP response (audio/speech). Not a `data class`: a [ByteArray] member would give it broken structural equals/hashCode. */
internal class OpenAIBytesResponse(
    val bytes: ByteArray,
    val headers: Map<String, String>,
)
