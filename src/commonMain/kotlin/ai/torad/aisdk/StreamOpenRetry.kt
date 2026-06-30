package ai.torad.aisdk

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow

internal object StreamOpenRetry {
    fun <T> wrap(maxRetries: Int, upstream: () -> Flow<T>): Flow<T> = flow {
        var emitted = false
        try {
            RetryPolicy {
                maxRetries(maxRetries)
            }.execute {
                try {
                    upstream().collect { event ->
                        emitted = true
                        emit(event)
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    if (emitted) throw RetryDisabledAfterFirstEmission(t)
                    throw t
                }
            }
        } catch (disabled: RetryDisabledAfterFirstEmission) {
            throw disabled.original
        }
    }

    private class RetryDisabledAfterFirstEmission(
        val original: Throwable,
    ) : CancellationException(original.message)
}
