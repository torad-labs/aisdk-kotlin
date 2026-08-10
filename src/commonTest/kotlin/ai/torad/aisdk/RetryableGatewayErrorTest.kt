package ai.torad.aisdk

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression: [retryableApiError] (the predicate the embedding/reranking retry layer uses)
 * must honor [GatewayError.isRetryable], not only [APICallError.isRetryable]. The two are
 * sibling subclasses of `AiSdkException`, so `(it as? APICallError)` is always null for a
 * gateway error — which previously meant gateway 429/5xx/408 never retried.
 */
@OptIn(ExperimentalCoroutinesApi::class)
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

    /**
     * The other half of the same blind spot: retryability was plumbed, the server's backoff
     * guidance was not. [RetryPolicy] reads `Retry-After` off `APICallError.responseHeaders`,
     * so a gateway error carrying no headers made every gateway 429 fall back to the ~100ms
     * exponential backoff — re-hitting the rate limit and exhausting the retries in under a
     * second instead of waiting the 30s the server asked for.
     */
    @Test
    fun `a gateway 429 carries the response headers so Retry-After reaches the retry layer`() = runTest {
        val client = HttpClient(
            MockEngine {
                respond(
                    content = """{"error":{"type":"rate_limit_exceeded","message":"slow down"}}""",
                    status = HttpStatusCode.TooManyRequests,
                    headers = headersOf(HttpHeaders.RetryAfter, "30"),
                )
            },
        )
        val gateway = CreateGatewayHttpProvider(client, GatewayProviderSettings { apiKey("key") })

        val error = assertFailsWith<GatewayRateLimitError> { gateway.getCredits() }

        assertEquals(
            "30",
            error.responseHeaders?.entries?.firstOrNull { it.key.equals("retry-after", ignoreCase = true) }?.value,
            "the gateway error factory is handed the response headers and must keep them",
        )
    }

    @Test
    fun `RetryPolicy honors a Retry-After carried by a GatewayError`() = runTest {
        var attempt = 0
        val result = RetryPolicy {
            maxRetries(1)
        }.execute(retryableApiError) {
            if (attempt++ == 0) throw GatewayRateLimitError(responseHeaders = mapOf("Retry-After" to "30"))
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(
            30_000L,
            testScheduler.currentTime,
            "a gateway 429 must wait the server's Retry-After, not the 100ms exponential backoff",
        )
    }

    @Test
    fun `RetryPolicy actually retries a retryable GatewayError through the embedding predicate`() = runTest {
        var attempt = 0
        val result = RetryPolicy {
            maxRetries(2)
            baseDelayMs(0)
        }.execute(retryableApiError) {
            if (attempt++ == 0) throw GatewayRateLimitError()
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(2, attempt, "the 429 GatewayError must trigger exactly one retry")
    }
}
