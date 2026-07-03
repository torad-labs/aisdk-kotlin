package ai.torad.aisdk

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration

internal object CallTimeout {
    suspend fun <T> run(
        timeout: Duration?,
        block: suspend () -> T,
    ): T {
        if (timeout == null || timeout.isInfinite()) return block()
        val result = withTimeoutOrNull(timeout) { TimeoutValue(block()) }
        return result?.value ?: throw CallTimeoutError(timeout)
    }

    fun <T> flow(upstream: Flow<T>, timeout: Duration?): Flow<T> {
        if (timeout == null || timeout.isInfinite()) return upstream
        return flow {
            val completed = withTimeoutOrNull(timeout) {
                upstream.collect { emit(it) }
                true
            }
            if (completed != true) throw CallTimeoutError(timeout)
        }
    }

    private class TimeoutValue<T>(val value: T)
}
