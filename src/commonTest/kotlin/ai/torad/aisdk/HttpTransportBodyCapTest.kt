package ai.torad.aisdk

import io.ktor.client.request.request
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HttpTransportBodyCapTest {
    @Test
    fun `bodyAsBytesCapped returns exact bytes under cap`() = runTest {
        val bytes = byteArrayOf(0, 1, 2, 3, 127, -128)
        val url = "https://transport.test/bytes"
        val fixture = TestServer.createTestServer(
            mutableMapOf(url to UrlHandler(UrlResponse.Binary(bytes))),
        )
        fixture.server.start()

        val response = fixture.httpClient().request(url)
        val actual = with(HttpTransport) { response.bodyAsBytesCapped(url, maxBytes = bytes.size.toLong()) }

        assertContentEquals(bytes, actual)
    }

    @Test
    fun `bodyAsBytesCapped throws typed error over cap`() = runTest {
        val url = "https://transport.test/too-large"
        val fixture = TestServer.createTestServer(
            mutableMapOf(url to UrlHandler(UrlResponse.Binary(byteArrayOf(1, 2, 3, 4)))),
        )
        fixture.server.start()

        val response = fixture.httpClient().request(url)
        val error = assertFailsWith<APICallError> {
            with(HttpTransport) { response.bodyAsBytesCapped(url, maxBytes = 3) }
        }

        assertEquals(url, error.url)
        assertEquals(200, error.statusCode)
        assertEquals(false, error.isRetryable)
        assertTrue(error.message.orEmpty().contains("exceeded 3 bytes limit"))
    }
}
