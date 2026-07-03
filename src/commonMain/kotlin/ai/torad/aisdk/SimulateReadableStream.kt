package ai.torad.aisdk

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Creates a cold [Flow] that emits [chunks] in order with [delayMillis]
 * between chunks. The first chunk is emitted immediately; the delay applies
 * only between consecutive chunks.
 *
 * @since 0.3.0-beta01
 */
public fun <T> SimulateReadableStream(
    chunks: Iterable<T>,
    delayMillis: Long = 0L,
): Flow<T> = flow {
    chunks.forEachIndexed { index, chunk ->
        if (index > 0 && delayMillis > 0L) delay(delayMillis)
        emit(chunk)
    }
}
