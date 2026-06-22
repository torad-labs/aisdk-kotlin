package ai.torad.aisdk

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression: [retryableApiError] (the predicate the embedding/reranking retry layer uses)
 * must honor [GatewayError.isRetryable], not only [APICallError.isRetryable]. The two are
 * sibling subclasses of `AiSdkException`, so `(it as? APICallError)` is always null for a
 * gateway error — which previously meant gateway 429/5xx/408 never retried.
 */
class RetryableGatewayErrorTest {
    @Test
    fun `predicate honors a retryable base GatewayError`() {
        assertTrue(retryableApiError(GatewayError("rate limited", statusCode = 429)))
        assertTrue(retryableApiError(GatewayError("server error", statusCode = 503)))
        assertTrue(retryableApiError(GatewayError("timeout", statusCode = 408)))
    }

    @Test
    fun `predicate honors the GatewayError subclass hierarchy`() {
        assertTrue(retryableApiError(GatewayRateLimitError()))
        assertTrue(retryableApiError(GatewayInternalServerError()))
    }

    @Test
    fun `predicate rejects a non-retryable GatewayError`() {
        assertFalse(retryableApiError(GatewayError("bad request", statusCode = 400)))
        assertFalse(retryableApiError(GatewayModelNotFoundError()))
    }

    @Test
    fun `predicate still honors APICallError`() {
        assertTrue(
            retryableApiError(
                APICallError(message = "rate limited", url = "https://api.test", statusCode = 429),
            ),
        )
        assertFalse(
            retryableApiError(
                APICallError(message = "bad request", url = "https://api.test", statusCode = 400),
            ),
        )
    }

    @Test
    fun `RetryPolicy actually retries a retryable GatewayError through the embedding predicate`() = runTest {
        var attempt = 0
        val result = RetryPolicy(maxRetries = 2, baseDelayMs = 0).execute(retryableApiError) {
            if (attempt++ == 0) throw GatewayRateLimitError()
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(2, attempt, "the 429 GatewayError must trigger exactly one retry")
    }
}
