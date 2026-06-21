package ai.torad.aisdk

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Replay a recorded list of [StreamEvent]s as a fresh cold Flow with
 * configurable inter-chunk delay. Foundation for the caching middleware
 * pattern: cache stream parts as `List<StreamEvent>`, on cache hit replay
 * via `SimulateReadableStream` so the stream contract is preserved.
 *
 * Mirrors v6's `simulateReadableStream`.
 */
public fun SimulateReadableStream(
    events: List<StreamEvent>,
    initialDelayMs: Long = 0L,
    chunkDelayMs: Long = 10L,
): Flow<StreamEvent> = flow {
    if (initialDelayMs > 0) delay(initialDelayMs)
    for ((index, event) in events.withIndex()) {
        if (index > 0 && chunkDelayMs > 0) delay(chunkDelayMs)
        emit(event)
    }
}
