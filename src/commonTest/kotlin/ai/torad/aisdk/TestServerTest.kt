package ai.torad.aisdk

import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class TestServerTest {
    @Test
    fun `json responses record request details and reset restores original routes`() = runTest {
        val fixture = createTestServer(
            mutableMapOf(
                "https://api.test/v1/items?filter=recent" to UrlHandler(
                    UrlResponse.JsonValue(
                        body = JsonObject(mapOf("ok" to JsonPrimitive(true))),
                        headers = mapOf("x-test" to "yes"),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val client = fixture.httpClient()

        val response = client.post("https://api.test/v1/items?filter=recent") {
            header(HttpHeaders.UserAgent, "aisdk-test")
            header("x-custom", "abc")
            contentType(io.ktor.http.ContentType.Application.Json)
            setBody("""{"query":"kotlin"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("""{"ok":true}""", response.bodyAsText())
        assertEquals("yes", response.headers["x-test"])
        val call = fixture.calls.single()
        assertEquals("POST", call.requestMethod)
        assertEquals("https://api.test/v1/items?filter=recent", call.requestUrl)
        assertEquals(listOf("recent"), call.requestUrlSearchParams["filter"])
        assertEquals("abc", call.requestHeaders["x-custom"])
        assertNull(call.requestHeaders[HttpHeaders.UserAgent])
        assertEquals("aisdk-test", call.requestUserAgent)
        assertEquals(JsonPrimitive("kotlin"), call.requestBodyJson.jsonObject["query"])

        fixture.urls.getValue("https://api.test/v1/items?filter=recent").response =
            UrlResponseParameter.Single(UrlResponse.Error(status = 418, body = "changed"))
        fixture.server.reset()

        val resetResponse = client.get("https://api.test/v1/items?filter=recent")
        assertEquals(HttpStatusCode.OK, resetResponse.status)
        assertEquals("""{"ok":true}""", resetResponse.bodyAsText())
        assertEquals(1, fixture.calls.size)
    }

    @Test
    fun `sequence and factory responses use global call number like upstream`() = runTest {
        val fixture = createTestServer(
            mutableMapOf(
                "/sequence" to UrlHandler(
                    listOf(
                        UrlResponse.Error(status = 429, body = "slow down"),
                        UrlResponse.JsonValue(JsonObject(mapOf("done" to JsonPrimitive(true)))),
                    ),
                ),
                "/factory" to UrlHandler { options ->
                    UrlResponse.JsonValue(JsonObject(mapOf("call" to JsonPrimitive(options.callNumber))))
                },
            ),
        )
        fixture.server.start()
        val client = fixture.httpClient()

        val first = client.get("https://api.test/sequence")
        val second = client.get("https://api.test/sequence")
        val factory = client.get("https://api.test/factory")

        assertEquals(HttpStatusCode.TooManyRequests, first.status)
        assertEquals("slow down", first.bodyAsText())
        assertEquals(HttpStatusCode.OK, second.status)
        assertEquals("""{"done":true}""", second.bodyAsText())
        assertEquals("""{"call":2}""", factory.bodyAsText())
    }

    @Test
    fun `binary empty stream and missing responses map to Ktor responses`() = runTest {
        val fixture = createTestServer(
            mutableMapOf(
                "/binary" to UrlHandler(UrlResponse.Binary(byteArrayOf(1, 2, 3), headers = mapOf("x-bin" to "1"))),
                "/empty" to UrlHandler(UrlResponse.Empty(status = 204)),
                "/stream" to UrlHandler(UrlResponse.StreamChunks(listOf("data: a\n\n", "data: b\n\n"))),
            ),
        )
        fixture.server.start()
        val client = fixture.httpClient()

        val binary = client.get("https://api.test/binary")
        val empty = client.get("https://api.test/empty")
        val stream = client.get("https://api.test/stream")
        val missing = client.get("https://api.test/missing")

        assertContentEquals(byteArrayOf(1, 2, 3), binary.bodyAsBytes())
        assertEquals("1", binary.headers["x-bin"])
        assertEquals(HttpStatusCode.NoContent, empty.status)
        assertEquals("", empty.bodyAsText())
        assertEquals("text/event-stream", stream.headers[HttpHeaders.ContentType])
        assertEquals("data: a\n\ndata: b\n\n", stream.bodyAsText())
        assertEquals(HttpStatusCode.NotFound, missing.status)
        assertEquals("""{"error":"Not Found"}""", missing.bodyAsText())
    }

    @Test
    fun `controlled stream can be written and closed by the controller`() = runTest {
        val controller = TestResponseController()
        val fixture = createTestServer(
            mutableMapOf(
                "/controlled" to UrlHandler(UrlResponse.ControlledStream(controller)),
            ),
        )
        fixture.server.start()
        val client = fixture.httpClient()

        val pending = async { client.get("https://api.test/controlled").body<String>() }
        controller.write("data: one\n\n")
        controller.write("data: two\n\n")
        controller.close()

        assertEquals("data: one\n\ndata: two\n\n", pending.await())
    }

    @Test
    fun `server rejects requests before start`() = runTest {
        val fixture = createTestServer(mutableMapOf("/ok" to UrlHandler(UrlResponse.Empty())))

        val error = assertFailsWith<IllegalStateException> {
            fixture.httpClient().get("https://api.test/ok")
        }

        assertEquals("Test server must be started before handling requests.", error.message)
    }
}
