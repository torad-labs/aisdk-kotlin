package ai.torad.aisdk

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class RetryBackoffTest {
    private class FixedClock(private val instant: Instant) : Clock {
        override fun now(): Instant = instant
    }

    private fun apiError(
        statusCode: Int,
        headers: Map<String, String> = emptyMap(),
    ): APICallError = APICallError(
        message = "api error $statusCode",
        url = "https://api.test/v1",
        statusCode = statusCode,
        responseHeaders = headers,
    )

    private fun rateLimitError(retryAfterSeconds: String): APICallError =
        apiError(429, mapOf("retry-after" to retryAfterSeconds))

    @Test
    fun `Retry-After is honored up to 60s and not clamped to maxDelayMs`() = runTest {
        // runTest auto-advances virtual time across delay(); currentTime measures the
        // total waited. The default policy has maxDelayMs=2000; the OLD code clamped
        // the server's Retry-After to that, so a `Retry-After: 30` would wait 2s. The
        // fix honors the full 30s (<60s ceiling) — the retry-storm bug.
        var attempt = 0
        val result = RetryPolicy(maxRetries = 1).execute<String> {
            if (attempt++ == 0) throw rateLimitError("30")
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
    fun `Title-Case Retry-After is honored via case-insensitive lookup`() = runTest {
        // HTTP/1.1 servers send `Retry-After` in Title-Case; the flattened header map preserves
        // wire casing, so a case-sensitive lookup would miss it and fall back to the tiny backoff.
        var attempt = 0
        val result = RetryPolicy(maxRetries = 1).execute<String> {
            if (attempt++ == 0) {
                throw APICallError(
                    message = "rate limited",
                    url = "https://api.test/v1",
                    statusCode = 429,
                    responseHeaders = mapOf("Retry-After" to "5"),
                    isRetryable = true,
                )
            }
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(5_000L, testScheduler.currentTime, "Title-Case Retry-After must be honored")
    }

    @Test
    fun `Retry-After above the 60s ceiling is capped - not dropped to backoff`() = runTest {
        var attempt = 0
        val result = RetryPolicy(maxRetries = 1).execute<String> {
            if (attempt++ == 0) throw rateLimitError("90")
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(
            60_000L,
            testScheduler.currentTime,
            "a 90s Retry-After must cap at the 60s ceiling, not fall back to the 2s backoff",
        )
    }

    @Test
    fun `HTTP-date Retry-After is honored instead of falling back to exponential backoff`() = runTest {
        var attempt = 0
        val result = RetryPolicy(maxRetries = 1).execute<String> {
            if (attempt++ == 0) {
                throw APICallError(
                    message = "rate limited",
                    url = "https://api.test/v1",
                    statusCode = 429,
                    responseHeaders = mapOf("Retry-After" to "Thu, 01 Jan 2099 00:01:30 GMT"),
                    isRetryable = true,
                )
            }
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(
            60_000L,
            testScheduler.currentTime,
            "a future HTTP-date Retry-After must be parsed and capped at the 60s ceiling",
        )
    }

    @Test
    fun `exhausting retries throws RetryError carrying every attempt error`() = runTest {
        val failure = assertFailsWith<RetryError> {
            RetryPolicy(maxRetries = 2, baseDelayMs = 0).execute<String> {
                throw apiError(500)
            }
        }
        assertEquals(RetryErrorReason.MaxRetriesExceeded, failure.reason)
        // first failure + maxRetries retries = 3 collected errors
        assertEquals(3, failure.errors.size)
        assertTrue(failure.errors.all { it is APICallError })
        assertEquals(3, failure.attempts.size)
        assertEquals(listOf(true, true, false), failure.attempts.map { it.retryable })
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

    @Test
    fun `default predicate retries 429 and 500 typed API errors`() = runTest {
        var attempts = 0
        val result429 = RetryPolicy(
            maxRetries = 1,
            baseDelayMs = 100,
            delayGenerator = RetryDelayGenerator.deterministic(0),
        ).execute<String> {
            attempts += 1
            if (attempts == 1) throw apiError(429)
            "ok-429"
        }
        assertEquals("ok-429", result429)

        attempts = 0
        val result500 = RetryPolicy(
            maxRetries = 1,
            baseDelayMs = 100,
            delayGenerator = RetryDelayGenerator.deterministic(0),
        ).execute<String> {
            attempts += 1
            if (attempts == 1) throw apiError(500)
            "ok-500"
        }
        assertEquals("ok-500", result500)
    }

    @Test
    fun `default predicate does not retry 401`() = runTest {
        var attempts = 0
        val failure = assertFailsWith<APICallError> {
            RetryPolicy(maxRetries = 3, baseDelayMs = 0).execute<String> {
                attempts += 1
                throw apiError(401)
            }
        }
        assertEquals(401, failure.statusCode)
        assertEquals(1, attempts)
    }

    @Test
    fun `retry-after-ms is honored`() = runTest {
        var attempt = 0
        val result = RetryPolicy(maxRetries = 1).execute<String> {
            if (attempt++ == 0) throw apiError(429, mapOf("retry-after-ms" to "250"))
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(250L, testScheduler.currentTime)
    }

    @Test
    fun `HTTP-date Retry-After uses the injected clock`() = runTest {
        var attempt = 0
        val result = RetryPolicy(
            maxRetries = 1,
            clock = FixedClock(Instant.fromEpochMilliseconds(0L)),
        ).execute<String> {
            if (attempt++ == 0) {
                throw apiError(429, mapOf("Retry-After" to "Thu, 01 Jan 1970 00:00:05 GMT"))
            }
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(5_000L, testScheduler.currentTime)
    }

    @Test
    fun `full jitter uses deterministic delay generator`() = runTest {
        var attempt = 0
        val result = RetryPolicy(
            maxRetries = 1,
            baseDelayMs = 100,
            maxDelayMs = 1_000,
            delayGenerator = RetryDelayGenerator.deterministic(37),
        ).execute<String> {
            if (attempt++ == 0) throw apiError(500)
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(37L, testScheduler.currentTime)
    }

    @Test
    fun `per-attempt timeout cancels a stalled attempt`() = runTest {
        assertFailsWith<TimeoutCancellationException> {
            RetryPolicy(perAttemptTimeoutMs = 100).execute<String> {
                delay(101)
                "late"
            }
        }
        assertEquals(100L, testScheduler.currentTime)
    }

    @Test
    fun `total timeout bounds retries and backoff`() = runTest {
        assertFailsWith<TimeoutCancellationException> {
            RetryPolicy(
                maxRetries = 10,
                baseDelayMs = 100,
                delayGenerator = RetryDelayGenerator.deterministic(90),
                totalTimeoutMs = 250,
            ).execute<String> {
                throw apiError(500)
            }
        }
        assertEquals(250L, testScheduler.currentTime)
    }

    @Test
    fun `CancellationException is rethrown without retry`() = runTest {
        var attempts = 0
        assertFailsWith<CancellationException> {
            RetryPolicy(maxRetries = 3, baseDelayMs = 0).execute<String> {
                attempts += 1
                throw CancellationException("stop")
            }
        }
        assertEquals(1, attempts)
    }
}
