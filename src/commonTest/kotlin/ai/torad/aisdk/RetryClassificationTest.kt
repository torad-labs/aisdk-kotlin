package ai.torad.aisdk

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Characterization tests for the DEFAULT retry classifier.
 *
 * These pin the CURRENT behaviour of `IsDefaultRetryable` — which Throwables are retried
 * and which are not — through the public seam (`RetryPolicy.execute` with its default
 * `shouldRetry`). They exist because that predicate matches several third-party exception
 * types by fully-qualified NAME STRING, which is fragile in a way no other test covered:
 * the whole classifier could be rewritten, compile, and pass all 34 pre-existing retry
 * tests while silently changing which failures get retried.
 *
 * Each test asserts on the observed ATTEMPT COUNT rather than an internal predicate, so it
 * survives a refactor of the classifier's implementation and fails only if the
 * classification itself moves — which is the point.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RetryClassificationTest {

    private class NotRetryable(message: String) : IllegalStateException(message)

    /** Matches the `simpleName in transientNetworkExceptionNames` branch. */
    private class SocketTimeoutException(message: String) : Exception(message)

    /**
     * The `.endsWith(".IOException")` branch is NOT reachable from a test-local class: a
     * nested class's qualified name ends in `.RetryClassificationTest.CustomIOException`,
     * and even a top-level one in this package would end in `.IOException` only if it were
     * literally named `IOException`, which would collide with the imported kotlinx.io type.
     * Declaring one in a differently-named package is the only way to hit it, and that is
     * more test scaffolding than the branch is worth — kotlinx.io's own IOException already
     * covers the same classification outcome above. Recorded rather than silently skipped.
     */
    private class CustomIOException(message: String) : Exception(message)

    private fun policy(maxRetries: Int = 2) = RetryPolicy {
        maxRetries(maxRetries)
        baseDelayMs(1)
        maxDelayMs(2)
    }

    /** Runs [failure] on every attempt and reports how many attempts were made. */
    private suspend fun attemptsFor(failure: () -> Throwable): Int {
        var attempts = 0
        runCatching {
            policy().execute<String> {
                attempts++
                throw failure()
            }
        }
        return attempts
    }

    @Test
    fun `kotlinx io IOException is retried`() = runTest {
        assertEquals(3, attemptsFor { IOException("connection reset") }, "1 initial + 2 retries")
    }

    @Test
    fun `an exception named like a transient socket failure is retried`() = runTest {
        assertEquals(3, attemptsFor { SocketTimeoutException("timed out") })
    }

    @Test
    fun `a nested class merely named like an IO error is NOT retried`() = runTest {
        // Pins the boundary of the name-matching branch: classification uses the QUALIFIED
        // name, so a nested CustomIOException (qualified name ends in
        // `.RetryClassificationTest.CustomIOException`) does not match `.IOException` and is
        // correctly terminal. This is the assertion that would catch a rewrite loosening the
        // match to a simple-name or `contains` check.
        assertEquals(1, attemptsFor { CustomIOException("io") })
    }

    @Test
    fun `a retryable APICallError is retried`() = runTest {
        assertEquals(
            3,
            attemptsFor {
                APICallError(
                    message = "server error",
                    url = "https://api.test/v1",
                    requestBodyValues = null,
                    statusCode = 503,
                    isRetryable = true,
                )
            },
        )
    }

    @Test
    fun `a non-retryable APICallError is NOT retried`() = runTest {
        assertEquals(
            1,
            attemptsFor {
                APICallError(
                    message = "bad request",
                    url = "https://api.test/v1",
                    requestBodyValues = null,
                    statusCode = 400,
                    isRetryable = false,
                )
            },
            "a 400 must fail on the first attempt",
        )
    }

    @Test
    fun `an ordinary exception is NOT retried`() = runTest {
        assertEquals(1, attemptsFor { NotRetryable("nope") })
    }

    @Test
    fun `CancellationException is never retried and propagates`() = runTest {
        var attempts = 0
        assertFailsWith<CancellationException> {
            policy().execute<String> {
                attempts++
                throw CancellationException("cancelled")
            }
        }
        assertEquals(1, attempts, "cancellation must abort immediately, not retry")
    }

    @Test
    fun `retries stop at maxRetries`() = runTest {
        var attempts = 0
        runCatching {
            policy(maxRetries = 4).execute<String> {
                attempts++
                throw IOException("still failing")
            }
        }
        assertEquals(5, attempts, "1 initial + 4 retries")
    }

    @Test
    fun `a retryable failure that later succeeds returns the value`() = runTest {
        var attempts = 0
        val result = policy().execute<String> {
            attempts++
            if (attempts == 1) throw IOException("transient")
            "recovered"
        }
        assertEquals("recovered", result)
        assertEquals(2, attempts)
    }

    @Test
    fun `an explicit shouldRetry overrides the default classifier`() = runTest {
        var attempts = 0
        runCatching {
            policy().execute<String>(shouldRetry = { it is NotRetryable }) {
                attempts++
                throw NotRetryable("normally terminal")
            }
        }
        assertTrue(attempts > 1, "a custom predicate must be able to make a default-terminal error retryable")
    }
}
