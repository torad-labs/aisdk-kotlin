package ai.torad.aisdk

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The instrument for the claim `AssetDownload.capped` makes: that its cap bounds MEMORY, not the
 * size of the value it hands back.
 *
 * This distinction is not academic and a MockEngine test cannot see it. Ktor 3.x installs the
 * `SaveBody` plugin on every [HttpClient] unconditionally, and it calls `readRemaining()` on the
 * raw channel before any user code runs — so on a `client.request(...)` response the entire body is
 * already resident by the time a chunked reader looks at it, and Ktor 3.5 removed the escape hatch
 * (`skipSavingBody()` throws, pointing at `prepareRequest { }.execute { }`). A cap applied to a
 * saved response is a content check wearing a memory defense's description.
 *
 * So the server here declares a 512 MiB body and streams until the client hangs up, and the test
 * asserts on how much it MANAGED TO SEND. A streaming reader aborts after roughly the cap; a
 * buffering one would have to swallow all 512 MiB before the cap could fire, which is both the
 * memory exhaustion being defended against and a test that would never finish. The byte counter is
 * the expected-delta instrument: if the helper ever regresses to `client.request(...)`, this
 * fails on time or on volume rather than passing quietly.
 */
class CappedDownloadIsStreamingTest {
    private companion object {
        const val CAP_BYTES = 256L * 1024
        const val DECLARED_BODY_BYTES = 512L * 1024 * 1024
        // Generous: the point is 512 MiB never arrives, not that the cutoff is byte-exact.
        // Socket buffers and the 8 KiB read loop both overshoot slightly past the cap.
        const val STREAMED_CEILING_BYTES = 32L * 1024 * 1024
    }

    @Test
    // runBlocking, not runTest: runTest's scheduler advances VIRTUAL time, so the withTimeout
    // below fires instantly and the test reports a timeout before a single byte moves. This
    // measures real socket I/O and needs a real clock.
    fun `downloadCapped stops the transfer at the cap instead of buffering the whole body`() = runBlocking {
        val sent = AtomicLong(0)
        val server = ServerSocket(0)
        val serverThread = thread(isDaemon = true) {
            try {
                server.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader()
                    do {
                        val line = input.readLine()
                    } while (!line.isNullOrEmpty())
                    val out = socket.getOutputStream()
                    out.write(
                        (
                            "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: application/octet-stream\r\n" +
                                "Content-Length: $DECLARED_BODY_BYTES\r\n\r\n"
                            ).toByteArray(),
                    )
                    val chunk = ByteArray(64 * 1024)
                    while (sent.get() < DECLARED_BODY_BYTES) {
                        out.write(chunk)
                        sent.addAndGet(chunk.size.toLong())
                    }
                    out.flush()
                }
            } catch (_: SocketException) {
                // Expected: the client aborts the transfer once the cap trips.
            } catch (_: java.io.IOException) {
                // Expected on some JDKs when the peer closes mid-write.
            }
        }

        val client = HttpClient(CIO)
        val url = "http://127.0.0.1:${server.localPort}/big"
        try {
            val error = withTimeout(60_000) {
                assertFailsWith<APICallError> {
                    AssetDownload.capped(client, url, maxBytes = CAP_BYTES)
                }
            }
            assertTrue(
                "exceeded" in error.message.orEmpty(),
                "expected the cap to be the failure reason, got: ${error.message}",
            )
        } finally {
            client.close()
            server.close()
            serverThread.join(5_000)
        }

        val streamed = sent.get()
        assertTrue(
            streamed < STREAMED_CEILING_BYTES,
            "the transfer was not cut short: server pushed $streamed bytes of a declared " +
                "$DECLARED_BODY_BYTES with a $CAP_BYTES cap, which means the body was being " +
                "buffered before the cap could apply",
        )
    }
}
