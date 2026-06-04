package ai.torad.aisdk

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class StreamTextResultTest {

    @Test
    fun `a cancelled fullStream collection does not corrupt the memoised replay`() = runTest {
        val upstream = flow {
            emit(StreamEvent.TextStart("t"))
            emit(StreamEvent.TextDelta("t", "a"))
            emit(StreamEvent.TextDelta("t", "b"))
            emit(StreamEvent.TextEnd("t"))
        }
        val result = StreamTextResult(sourceStream = upstream)

        // First collection is cancelled after 2 events (take cancels upstream).
        val partial = result.fullStream.take(2).toList()
        assertEquals(2, partial.size)

        // A full collection must yield the clean 4-event sequence...
        val full = result.fullStream.toList()
        assertEquals(4, full.size)

        // ...and the memoised replay must match it exactly — not the
        // [partial + full] concatenation the old shared-buffer capture produced.
        val replay = result.fullStream.toList()
        assertEquals(full, replay)
        assertEquals(4, replay.size)
        assertEquals(listOf("a", "b"), replay.filterIsInstance<StreamEvent.TextDelta>().map { it.text })
    }

    @Test
    fun `concurrent fullStream collectors both observe the full sequence`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val upstream = flow {
            emit(StreamEvent.TextStart("t"))
            emit(StreamEvent.TextDelta("t", "a"))
            gate.await()
            emit(StreamEvent.TextDelta("t", "b"))
            emit(StreamEvent.TextEnd("t"))
        }
        val result = StreamTextResult(sourceStream = upstream)

        val first = async { result.fullStream.toList() }
        runCurrent() // first becomes primary, parks at the gate after 2 events
        val second = async { result.fullStream.toList() } // must await + replay, not deadlock
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()

        val firstEvents = first.await()
        assertEquals(4, firstEvents.size)
        assertEquals(firstEvents, second.await()) // replay matches the live run
    }
}
