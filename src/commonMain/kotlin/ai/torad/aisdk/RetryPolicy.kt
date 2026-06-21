package ai.torad.aisdk

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

public data class RetryPolicy(
    val maxRetries: Int = 2,
    val baseDelayMs: Long = 100L,
    val maxDelayMs: Long = 2_000L,
) {
    /**
     * Execute [block] with exponential backoff, honoring server `Retry-After` headers,
     * and surfacing the full attempt history as a [RetryError] on exhaustion.
     *
     * Terminal outcomes:
     * - retries disabled (`maxRetries == 0`) → the bare original error, unwrapped;
     * - a non-retryable error on the first attempt → the bare error, unwrapped;
     * - a non-retryable error on a later attempt → [RetryError] (`ErrorNotRetryable`);
     * - retries exhausted → [RetryError] (`MaxRetriesExceeded`).
     */
    public suspend fun <T> execute(
        shouldRetry: (Throwable) -> Boolean = { true },
        block: suspend (attempt: Int) -> T,
    ): T {
        val errors = mutableListOf<Throwable>()
        var nextDelay = baseDelayMs
        while (true) {
            @Suppress("SwallowedException")
            val waitMs = try {
                return block(errors.size)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                classifyFailure(t, shouldRetry, errors, nextDelay)
            }
            if (waitMs > 0) delay(waitMs)
            nextDelay = (nextDelay * 2).coerceAtMost(maxDelayMs)
        }
    }

    @Suppress("ThrowsCount")
    private fun classifyFailure(
        t: Throwable,
        shouldRetry: (Throwable) -> Boolean,
        errors: MutableList<Throwable>,
        exponentialBackoffDelay: Long,
    ): Long {
        if (maxRetries == 0) throw t
        errors += t
        val tryNumber = errors.size
        if (tryNumber > maxRetries) {
            throw RetryError(
                "Failed after $tryNumber attempts. Last error: ${t.message}",
                RetryErrorReason.MaxRetriesExceeded,
                errors.toList(),
            )
        }
        if (!shouldRetry(t)) {
            if (tryNumber == 1) throw t
            throw RetryError(
                "Failed after $tryNumber attempts with non-retryable error: '${t.message}'",
                RetryErrorReason.ErrorNotRetryable,
                errors.toList(),
            )
        }
        return retryDelayMs(t, exponentialBackoffDelay)
    }

    private fun retryDelayMs(t: Throwable, exponentialBackoffDelay: Long): Long {
        val serverMs = retryAfterDelayMs(t) ?: return exponentialBackoffDelay
        // Honor the server's Retry-After, CAPPED at the 60s ceiling. A server asking for 90s must
        // wait 60s — not fall back to the tiny exponential backoff, which would immediately
        // re-hit the rate limit (MAX_RETRY_AFTER_MS names the cap; the old code discarded it).
        return serverMs.coerceAtMost(MAX_RETRY_AFTER_MS)
    }

    private fun retryAfterDelayMs(t: Throwable): Long? {
        val headers = (t as? APICallError)?.responseHeaders ?: return null
        // The flattened header map preserves the server's wire casing, and HTTP/1.1 servers send
        // `Retry-After` / `Retry-After-Ms` in Title-Case — so these MUST be looked up
        // case-insensitively, or the server's backoff guidance is silently dropped over HTTP/1.1.
        fun header(name: String): String? =
            headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
        val delayMs = header("retry-after-ms")?.toLongOrNull()?.takeIf { it > 0 }
            ?: header("retry-after")?.trim()?.toLongOrNull()?.takeIf { it >= 0 }
                ?.let { it * MILLIS_PER_SECOND }
        return delayMs
    }

    private companion object {
        private const val MILLIS_PER_SECOND: Long = 1_000L
        private const val MAX_RETRY_AFTER_MS: Long = 60L * 1_000L
    }
}
