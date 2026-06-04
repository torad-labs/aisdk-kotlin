package ai.torad.aisdk

import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
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
}
