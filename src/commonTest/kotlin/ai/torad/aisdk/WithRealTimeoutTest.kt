package ai.torad.aisdk

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds

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
        val error = assertFailsWith<CallTimeoutError> {
            HttpTransport.withRealTimeout(timeoutMs = 50) {
                delay(10_000)
                "never reached"
            }
        }
        assertEquals(50.milliseconds, error.timeout)
    }

    @Test
    fun `returns the value when the block finishes within the deadline`() = runTest {
        // A generous real-time budget around near-instant work returns normally —
        // i.e. a healthy fast request is never spuriously cancelled.
        val result = HttpTransport.withRealTimeout(timeoutMs = 10_000) { "ok" }
        assertEquals("ok", result)
    }

    @Test
    fun `a fired deadline is not a CancellationException`() = runTest {
        // The SDK-internal ceiling firing is a FAILURE, not cooperative cancellation.
        // As a CancellationException it passed RetryPolicy's rethrow unclassified and
        // every `catch (ce: CancellationException) { throw ce }` guard read it as the
        // caller cancelling.
        val error = assertFailsWith<CallTimeoutError> {
            HttpTransport.withRealTimeout(timeoutMs = 50) { delay(10_000) }
        }
        assertNull(
            CancellationExceptions.asCancellationExceptionOrNull(error),
            "a stalled-server timeout must not be misread as user cancellation",
        )
    }

    @Test
    fun `a fired deadline reaches the consumer's CoroutineExceptionHandler`() = runTest {
        // The consumer-visible half of the same defect: an uncaught CancellationException
        // completing a `scope.launch { … }` job is treated as cancellation by structured
        // concurrency — it is never handed to a CoroutineExceptionHandler, so the request
        // vanishes with no error anywhere.
        val seen = CompletableDeferred<Throwable>()
        val handler = CoroutineExceptionHandler { _, error -> seen.complete(error) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + handler)
        try {
            scope.launch {
                HttpTransport.withRealTimeout(timeoutMs = 50) { delay(10_000) }
            }.join()
            val failure = withContext(Dispatchers.Default) { withTimeout(5_000) { seen.await() } }
            assertIs<CallTimeoutError>(failure)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `an outer timeout still propagates as a cancellation`() = runTest {
        // Only THIS deadline is converted: a TimeoutCancellationException from an
        // enclosing timeout is the caller's cancellation and must pass through
        // unchanged, so cancellation-aware call sites still see cancellation.
        assertFailsWith<TimeoutCancellationException> {
            withContext(Dispatchers.Default) {
                withTimeout(50) {
                    HttpTransport.withRealTimeout(timeoutMs = 10_000) { delay(10_000) }
                }
            }
        }
    }
}
