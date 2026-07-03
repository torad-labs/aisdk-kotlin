package ai.torad.aisdk

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * [withRealTimeout] is the load-bearing primitive for the non-streaming request
 * and MCP-handshake timeouts: it must measure WALL-CLOCK time even when the
 * caller runs under `runTest`'s virtual scheduler. A naive `withTimeout` here
 * would either fire instantly against virtual time (breaking the mock suite) or
 * never fire against a real hang. These tests pin both halves of that contract.
 */
class WithRealTimeoutTest {
    @Test
    fun `fires on a real hang past the deadline`() = runTest {
        // A real-time delay far longer than a short real-time timeout must trip,
        // even though under runTest virtual time the delay would be "instant".
        assertFailsWith<TimeoutCancellationException> {
            HttpTransport.withRealTimeout(timeoutMs = 50) {
                delay(10_000)
                "never reached"
            }
        }
    }

    @Test
    fun `returns the value when the block finishes within the deadline`() = runTest {
        // A generous real-time budget around near-instant work returns normally —
        // i.e. a healthy fast request is never spuriously cancelled.
        val result = HttpTransport.withRealTimeout(timeoutMs = 10_000) { "ok" }
        assertEquals("ok", result)
    }
}
