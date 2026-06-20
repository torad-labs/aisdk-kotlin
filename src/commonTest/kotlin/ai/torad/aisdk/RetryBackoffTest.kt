package ai.torad.aisdk

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RetryBackoffTest {
    private fun apiError(retryAfterSeconds: String) = APICallError(
        message = "rate limited",
        url = "https://api.test/v1",
        statusCode = 429,
        responseHeaders = mapOf("retry-after" to retryAfterSeconds),
        isRetryable = true,
    )

    @Test
    fun `Retry-After is honored up to 60s and not clamped to maxDelayMs`() = runTest {
        // runTest auto-advances virtual time across delay(); currentTime measures the
        // total waited. The default policy has maxDelayMs=2000; the OLD code clamped
        // the server's Retry-After to that, so a `Retry-After: 30` would wait 2s. The
        // fix honors the full 30s (<60s ceiling) — the retry-storm bug.
        var attempt = 0
        val result = RetryPolicy(maxRetries = 1).execute<String> {
            if (attempt++ == 0) throw apiError("30")
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(
            30_000L,
            testScheduler.currentTime,
            "must wait the full server Retry-After, not the 2s maxDelay clamp",
        )
    }

    @Test
    fun `exhausting retries throws RetryError carrying every attempt error`() = runTest {
        val failure = assertFailsWith<RetryError> {
            RetryPolicy(maxRetries = 2, baseDelayMs = 0).execute<String> {
                error("boom")
            }
        }
        assertEquals(RetryErrorReason.MaxRetriesExceeded, failure.reason)
        // first failure + maxRetries retries = 3 collected errors
        assertEquals(3, failure.errors.size)
        assertTrue(failure.errors.all { it is IllegalStateException })
    }

    @Test
    fun `a non-retryable error on a later attempt wraps as ErrorNotRetryable with history`() = runTest {
        var attempt = 0
        val failure = assertFailsWith<RetryError> {
            RetryPolicy(maxRetries = 3, baseDelayMs = 0).execute<String>(
                shouldRetry = { it.message != "fatal" },
            ) {
                if (attempt++ == 0) error("transient")
                error("fatal")
            }
        }
        assertEquals(RetryErrorReason.ErrorNotRetryable, failure.reason)
        assertEquals(2, failure.errors.size) // [transient, fatal]
    }

    @Test
    fun `a non-retryable error on the first attempt is thrown unwrapped`() = runTest {
        assertFailsWith<IllegalStateException> {
            RetryPolicy(maxRetries = 3, baseDelayMs = 0).execute<String>(
                shouldRetry = { false },
            ) {
                error("first-try fatal")
            }
        }
    }

    @Test
    fun `maxRetries 0 throws the bare error unwrapped`() = runTest {
        assertFailsWith<IllegalStateException> {
            RetryPolicy(maxRetries = 0).execute<String> {
                error("no retries")
            }
        }
    }
}
