package ai.torad.aisdk

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlin.random.Random
import kotlin.time.Clock

public fun interface RetryDelayGenerator {
    public fun nextDelayMs(maxDelayMs: Long): Long

    public companion object {
        public fun fullJitter(random: Random = Random.Default): RetryDelayGenerator =
            RetryDelayGenerator { maxDelayMs ->
                when {
                    maxDelayMs <= 0L -> 0L
                    maxDelayMs == Long.MAX_VALUE -> random.nextLong(Long.MAX_VALUE)
                    else -> random.nextLong(maxDelayMs + 1L)
                }
            }

        public fun deterministic(vararg delaysMs: Long): RetryDelayGenerator =
            object : RetryDelayGenerator {
                private var index: Int = 0
                override fun nextDelayMs(maxDelayMs: Long): Long {
                    val raw = delaysMs.getOrNull(index) ?: delaysMs.lastOrNull() ?: 0L
                    index += 1
                    return raw.coerceIn(0L, maxDelayMs.coerceAtLeast(0L))
                }
            }
    }
}

@Suppress("TooManyFunctions")
public data class RetryPolicy(
    val maxRetries: Int = 2,
    val baseDelayMs: Long = 100L,
    val maxDelayMs: Long = 2_000L,
    val clock: Clock = Clock.System,
    val delayGenerator: RetryDelayGenerator = RetryDelayGenerator.fullJitter(),
    val totalTimeoutMs: Long? = null,
    val perAttemptTimeoutMs: Long? = null,
) {
    init {
        require(maxRetries >= 0) { "maxRetries must be >= 0" }
        require(baseDelayMs >= 0) { "baseDelayMs must be >= 0" }
        require(maxDelayMs >= 0) { "maxDelayMs must be >= 0" }
        totalTimeoutMs?.let { require(it > 0L) { "totalTimeoutMs must be > 0 when set" } }
        perAttemptTimeoutMs?.let { require(it > 0L) { "perAttemptTimeoutMs must be > 0 when set" } }
    }

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
        shouldRetry: (Throwable) -> Boolean = RetryPolicy::isDefaultRetryable,
        block: suspend (attempt: Int) -> T,
    ): T = if (totalTimeoutMs == null) {
        executeLoop(shouldRetry, block)
    } else {
        withTimeout(totalTimeoutMs) {
            executeLoop(shouldRetry, block)
        }
    }

    private suspend fun <T> executeLoop(
        shouldRetry: (Throwable) -> Boolean,
        block: suspend (attempt: Int) -> T,
    ): T {
        val errors = mutableListOf<Throwable>()
        val attempts = mutableListOf<RetryAttemptDetail>()
        while (true) {
            @Suppress("SwallowedException")
            val waitMs = try {
                return executeAttempt(errors.size, block)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                classifyFailure(t, shouldRetry, errors, attempts)
            }
            if (waitMs > 0) delay(waitMs)
        }
    }

    private suspend fun <T> executeAttempt(attempt: Int, block: suspend (attempt: Int) -> T): T =
        if (perAttemptTimeoutMs == null) {
            block(attempt)
        } else {
            withTimeout(perAttemptTimeoutMs) {
                block(attempt)
            }
        }

    @Suppress("ThrowsCount")
    private fun classifyFailure(
        t: Throwable,
        shouldRetry: (Throwable) -> Boolean,
        errors: MutableList<Throwable>,
        attempts: MutableList<RetryAttemptDetail>,
    ): Long {
        if (maxRetries == 0) throw t
        errors += t
        val tryNumber = errors.size
        val retryable = shouldRetry(t)
        if (tryNumber > maxRetries) {
            attempts += RetryAttemptDetail(tryNumber - 1, t, retryable = false, delayMs = null)
            throw RetryError(
                "Failed after $tryNumber attempts. Last error: ${t.message}. " +
                    "Retry decisions: ${attempts.summary()}",
                RetryErrorReason.MaxRetriesExceeded,
                errors.toList(),
                attempts.toList(),
            )
        }
        if (!retryable) {
            attempts += RetryAttemptDetail(tryNumber - 1, t, retryable = false, delayMs = null)
            if (tryNumber == 1) throw t
            throw RetryError(
                "Failed after $tryNumber attempts with non-retryable error: '${t.message}'. " +
                    "Retry decisions: ${attempts.summary()}",
                RetryErrorReason.ErrorNotRetryable,
                errors.toList(),
                attempts.toList(),
            )
        }
        val delayMs = retryDelayMs(t, retryIndex = tryNumber - 1)
        attempts += RetryAttemptDetail(tryNumber - 1, t, retryable = true, delayMs = delayMs)
        return delayMs
    }

    private fun retryDelayMs(t: Throwable, retryIndex: Int): Long {
        val backoffCeiling = exponentialBackoffDelay(retryIndex)
        val jitterMs = delayGenerator.nextDelayMs(backoffCeiling).coerceIn(0L, backoffCeiling)
        val serverMs = retryAfterDelayMs(t) ?: return jitterMs
        // Honor the server's Retry-After, CAPPED at the 60s ceiling. A server asking for 90s must
        // wait 60s — not fall back to the tiny exponential backoff, which would immediately
        // re-hit the rate limit (MAX_RETRY_AFTER_MS names the cap; the old code discarded it).
        return maxOf(jitterMs, serverMs.coerceAtMost(MAX_RETRY_AFTER_MS)).coerceAtMost(MAX_RETRY_AFTER_MS)
    }

    private fun exponentialBackoffDelay(retryIndex: Int): Long {
        var delayMs = baseDelayMs
        repeat(retryIndex) {
            delayMs = (delayMs * 2L).takeUnless { it < 0L }?.coerceAtMost(maxDelayMs) ?: maxDelayMs
        }
        return delayMs.coerceAtMost(maxDelayMs)
    }

    private fun retryAfterDelayMs(t: Throwable): Long? {
        val headers = (t as? APICallError)?.responseHeaders ?: return null
        // The flattened header map preserves the server's wire casing, and HTTP/1.1 servers send
        // `Retry-After` / `Retry-After-Ms` in Title-Case — so these MUST be looked up
        // case-insensitively, or the server's backoff guidance is silently dropped over HTTP/1.1.
        fun header(name: String): String? =
            headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
        val delayMs = header("retry-after-ms")?.toLongOrNull()?.takeIf { it > 0 }
            ?: header("retry-after")?.trim()?.let(::parseRetryAfterValueMs)
        return delayMs
    }

    private fun parseRetryAfterValueMs(value: String): Long? =
        value.toLongOrNull()?.takeIf { it >= 0 }?.let { it * MILLIS_PER_SECOND }
            ?: retryAfterHttpDateMs(value)

    private fun retryAfterHttpDateMs(value: String): Long? {
        val epochMs = parseHttpDateEpochMilliseconds(value) ?: return null
        return (epochMs - clock.now().toEpochMilliseconds()).coerceAtLeast(0L)
    }

    private fun List<RetryAttemptDetail>.summary(): String =
        joinToString(prefix = "[", postfix = "]") { detail ->
            "attempt=${detail.attempt},retryable=${detail.retryable},delayMs=${detail.delayMs}"
        }

    @Suppress("ReturnCount", "MagicNumber", "ComplexCondition")
    private fun parseHttpDateEpochMilliseconds(value: String): Long? {
        val match = HTTP_DATE_REGEX.matchEntire(value.trim()) ?: return null
        val day = match.groupValues[1].toIntOrNull() ?: return null
        val month = httpMonth(match.groupValues[2]) ?: return null
        val year = match.groupValues[3].toIntOrNull() ?: return null
        val hour = match.groupValues[4].toIntOrNull() ?: return null
        val minute = match.groupValues[5].toIntOrNull() ?: return null
        val second = match.groupValues[6].toIntOrNull() ?: return null
        if (day !in 1..31 || hour !in 0..23 || minute !in 0..59 || second !in 0..59) return null
        val daysSinceEpoch = daysFromCivil(year, month, day)
        val secondsSinceEpoch = daysSinceEpoch * SECONDS_PER_DAY +
            hour * SECONDS_PER_HOUR +
            minute * SECONDS_PER_MINUTE +
            second
        return secondsSinceEpoch * MILLIS_PER_SECOND
    }

    @Suppress("MagicNumber")
    private fun httpMonth(value: String): Int? = when (value) {
        "Jan" -> 1
        "Feb" -> 2
        "Mar" -> 3
        "Apr" -> 4
        "May" -> 5
        "Jun" -> 6
        "Jul" -> 7
        "Aug" -> 8
        "Sep" -> 9
        "Oct" -> 10
        "Nov" -> 11
        "Dec" -> 12
        else -> null
    }

    @Suppress("MagicNumber")
    private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        var y = year
        y -= if (month <= 2) 1 else 0
        val era = if (y >= 0) y / 400 else (y - 399) / 400
        val yearOfEra = y - era * 400
        val monthPrime = month + if (month > 2) -3 else 9
        val dayOfYear = (153 * monthPrime + 2) / 5 + day - 1
        val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
        return era.toLong() * DAYS_PER_ERA + dayOfEra.toLong() - DAYS_FROM_CIVIL_TO_UNIX_EPOCH
    }

    private companion object {
        private const val MILLIS_PER_SECOND: Long = 1_000L
        private const val SECONDS_PER_MINUTE: Long = 60L
        private const val SECONDS_PER_HOUR: Long = 60L * SECONDS_PER_MINUTE
        private const val SECONDS_PER_DAY: Long = 24L * SECONDS_PER_HOUR
        private const val DAYS_PER_ERA: Long = 146_097L
        private const val DAYS_FROM_CIVIL_TO_UNIX_EPOCH: Long = 719_468L
        private const val MAX_RETRY_AFTER_MS: Long = 60L * 1_000L
        private val HTTP_DATE_REGEX =
            Regex("^[A-Za-z]{3}, (\\d{2}) ([A-Za-z]{3}) (\\d{4}) (\\d{2}):(\\d{2}):(\\d{2}) GMT$")

        private fun isDefaultRetryable(t: Throwable): Boolean =
            (t as? APICallError)?.isRetryable == true ||
                (t as? GatewayError)?.isRetryable == true
    }
}
