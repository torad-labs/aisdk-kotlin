package ai.torad.aisdk

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

typealias UrlHandlers = MutableMap<String, UrlHandler>

data class TestServerCallOptions(
    val callNumber: Int,
)

sealed interface UrlResponse {
    val headers: Map<String, String>

    data class JsonValue(
        val body: JsonElement,
        override val headers: Map<String, String> = emptyMap(),
    ) : UrlResponse

    data class StreamChunks(
        val chunks: List<String>,
        override val headers: Map<String, String> = emptyMap(),
    ) : UrlResponse

    data class Binary(
        val body: ByteArray,
        override val headers: Map<String, String> = emptyMap(),
    ) : UrlResponse {
        override fun equals(other: Any?): Boolean =
            other is Binary && body.contentEquals(other.body) && headers == other.headers

        override fun hashCode(): Int = 31 * body.contentHashCode() + headers.hashCode()
    }

    data class Empty(
        val status: Int = 200,
        override val headers: Map<String, String> = emptyMap(),
    ) : UrlResponse

    data class Error(
        val status: Int = 500,
        val body: String = "Error",
        override val headers: Map<String, String> = emptyMap(),
    ) : UrlResponse

    data class ControlledStream(
        val controller: TestResponseController,
        override val headers: Map<String, String> = emptyMap(),
    ) : UrlResponse
}

class UrlHandler private constructor(
    internal val initialResponse: UrlResponseParameter,
) {
    var response: UrlResponseParameter = initialResponse

    constructor(response: UrlResponse?) : this(UrlResponseParameter.Single(response))

    constructor(responses: List<UrlResponse?>) : this(UrlResponseParameter.Sequence(responses))

    constructor(factory: (TestServerCallOptions) -> UrlResponse?) : this(UrlResponseParameter.Factory(factory))

    constructor(factory: (TestServerHttpRequest, TestServerCallOptions) -> UrlResponse?) : this(UrlResponseParameter.RequestFactory(factory))
}

sealed interface UrlResponseParameter {
    fun responseFor(callNumber: Int, request: TestServerHttpRequest): UrlResponse?

    data class Single(val response: UrlResponse?) : UrlResponseParameter {
        override fun responseFor(callNumber: Int, request: TestServerHttpRequest): UrlResponse? = response
    }

    data class Sequence(val responses: List<UrlResponse?>) : UrlResponseParameter {
        override fun responseFor(callNumber: Int, request: TestServerHttpRequest): UrlResponse? = responses.getOrNull(callNumber)
    }

    class Factory(private val factory: (TestServerCallOptions) -> UrlResponse?) : UrlResponseParameter {
        override fun responseFor(callNumber: Int, request: TestServerHttpRequest): UrlResponse? = factory(TestServerCallOptions(callNumber))
    }

    class RequestFactory(private val factory: (TestServerHttpRequest, TestServerCallOptions) -> UrlResponse?) : UrlResponseParameter {
        override fun responseFor(callNumber: Int, request: TestServerHttpRequest): UrlResponse? = factory(request, TestServerCallOptions(callNumber))
    }
}

data class TestServerCall(
    val requestBodyText: String,
    val requestCredentials: String?,
    val requestHeaders: Map<String, String>,
    val requestUserAgent: String?,
    val requestUrlSearchParams: Map<String, List<String>>,
    val requestUrl: String,
    val requestMethod: String,
    private val json: Json = Json,
) {
    val requestBodyJson: JsonElement
        get() = try {
            json.parseToJsonElement(requestBodyText)
        } catch (error: SerializationException) {
            throw IllegalArgumentException("Request body is not valid JSON.", error)
        }

    val requestBodyMultipart: Map<String, String>?
        get() = if (requestHeaders.headerValue(HttpHeaders.ContentType)?.startsWith("multipart/form-data", ignoreCase = true) == true) {
            parseMultipartFormData(requestBodyText)
        } else {
            null
        }
}

data class TestServerHttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String = "",
    val credentials: String? = null,
)

data class TestServerHttpResponse(
    val status: Int,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteReadChannel,
)

class TestServer internal constructor(
    routes: UrlHandlers,
    private val json: Json = Json,
) {
    private val originalRoutes: Map<String, UrlResponseParameter> = routes.mapValues { it.value.initialResponse }

    val urls: UrlHandlers = routes

    private var started: Boolean = false
    private val recordedCalls = mutableListOf<TestServerCall>()

    val calls: List<TestServerCall>
        get() = recordedCalls.toList()

    fun start() {
        started = true
    }

    fun stop() {
        started = false
    }

    fun reset() {
        originalRoutes.forEach { (url, response) ->
            urls[url]?.response = response
        }
        recordedCalls.clear()
    }

    suspend fun handle(request: TestServerHttpRequest): TestServerHttpResponse {
        check(started) { "Test server must be started before handling requests." }

        val callNumber = recordedCalls.size
        recordedCalls += TestServerCall(
            requestBodyText = request.body,
            requestCredentials = request.credentials,
            requestHeaders = request.headers.filterKeys { !it.equals(HttpHeaders.UserAgent, ignoreCase = true) },
            requestUserAgent = request.headers.headerValue(HttpHeaders.UserAgent),
            requestUrlSearchParams = Url(request.url).parameters.entries().associate { it.key to it.value },
            requestUrl = request.url,
            requestMethod = request.method,
            json = json,
        )

        val response = urls[request.url]?.response?.responseFor(callNumber, request)
            ?: Url(request.url).let { parsedUrl ->
                urls[parsedUrl.encodedPath]?.response?.responseFor(callNumber, request)
            }
            ?: return TestServerHttpResponse(
                status = 404,
                headers = mapOf(HttpHeaders.ContentType to "application/json"),
                body = ByteReadChannel(json.encodeToString(JsonObject(mapOf("error" to kotlinx.serialization.json.JsonPrimitive("Not Found"))))),
            )

        return response.toHttpResponse(json)
    }

    fun httpClient(): HttpClient = HttpClient(MockEngine { request ->
        respondToKtor(request)
    })

    private suspend fun MockRequestHandleScope.respondToKtor(request: HttpRequestData) =
        handle(
            TestServerHttpRequest(
                method = request.method.value,
                url = request.url.toString(),
                headers = request.headers.entries().associate { it.key to it.value.joinToString(",") } +
                    request.body.contentType?.let { mapOf(HttpHeaders.ContentType to it.toString()) }.orEmpty(),
                body = request.body.bodyText(),
            ),
        ).toKtorResponse(this)
}

data class CreatedTestServer(
    val urls: UrlHandlers,
    val server: TestServer,
) {
    val calls: List<TestServerCall>
        get() = server.calls

    fun httpClient(): HttpClient = server.httpClient()
}

fun createTestServer(
    routes: UrlHandlers,
    json: Json = Json,
): CreatedTestServer {
    val server = TestServer(routes, json)
    return CreatedTestServer(urls = server.urls, server = server)
}

class TestResponseController {
    private val channel = ByteChannel(autoFlush = true)

    val stream: ByteReadChannel
        get() = channel

    suspend fun write(chunk: String) {
        channel.writeStringUtf8(chunk)
        channel.flush()
    }

    fun error(error: Throwable) {
        channel.cancel(error)
    }

    fun close() {
        channel.close()
    }
}

private suspend fun UrlResponse.toHttpResponse(json: Json): TestServerHttpResponse =
    when (this) {
        is UrlResponse.JsonValue -> TestServerHttpResponse(
            status = 200,
            headers = mapOf(HttpHeaders.ContentType to "application/json") + headers,
            body = ByteReadChannel(json.encodeToString(JsonElement.serializer(), body)),
        )
        is UrlResponse.StreamChunks -> TestServerHttpResponse(
            status = 200,
            headers = streamHeaders(headers),
            body = ByteReadChannel(chunks.joinToString(separator = "")),
        )
        is UrlResponse.Binary -> TestServerHttpResponse(
            status = 200,
            headers = headers,
            body = ByteReadChannel(body),
        )
        is UrlResponse.Empty -> TestServerHttpResponse(
            status = status,
            headers = headers,
            body = ByteReadChannel(ByteArray(0)),
        )
        is UrlResponse.Error -> TestServerHttpResponse(
            status = status,
            headers = headers,
            body = ByteReadChannel(body),
        )
        is UrlResponse.ControlledStream -> TestServerHttpResponse(
            status = 200,
            headers = streamHeaders(headers),
            body = controller.stream,
        )
    }

private fun streamHeaders(headers: Map<String, String>): Map<String, String> =
    mapOf(
        HttpHeaders.ContentType to "text/event-stream",
        HttpHeaders.CacheControl to "no-cache",
        HttpHeaders.Connection to "keep-alive",
    ) + headers

private fun TestServerHttpResponse.toKtorResponse(scope: MockRequestHandleScope) =
    scope.respond(
        content = body,
        status = HttpStatusCode.fromValue(status),
        headers = headers.toKtorHeaders(),
    )

private fun Map<String, String>.toKtorHeaders(): Headers =
    Headers.build {
        forEach { (name, value) -> append(name, value) }
    }

private suspend fun OutgoingContent.bodyText(): String =
    when (this) {
        is OutgoingContent.NoContent -> ""
        else -> toByteArray().decodeToString()
    }

private fun Map<String, String>.headerValue(name: String): String? =
    entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

private fun parseMultipartFormData(body: String): Map<String, String> {
    if (body.isBlank()) return emptyMap()
    val result = linkedMapOf<String, String>()
    val nameRegex = Regex("""name="([^"]+)"""")
    val sections = body.split("\r\n--", "\n--")
    for (section in sections) {
        val name = nameRegex.find(section)?.groupValues?.get(1) ?: continue
        val value = section.substringAfter("\r\n\r\n", section.substringAfter("\n\n", ""))
            .trim()
            .trimEnd('-')
            .trim()
        result[name] = value
    }
    return result
}
