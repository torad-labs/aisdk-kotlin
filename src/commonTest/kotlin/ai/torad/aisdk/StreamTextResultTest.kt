package ai.torad.aisdk

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
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

        // First collection is cancelled after 2 events.
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
    fun `last collector leaving before terminal cancels upstream and restarts cleanly`() = runTest {
        var collections = 0
        val firstRunCancelled = CompletableDeferred<Unit>()
        val upstream = flow {
            collections++
            try {
                emit(StreamEvent.TextStart("t"))
                emit(StreamEvent.TextDelta("t", "a"))
                if (collections == 1) {
                    awaitCancellation()
                }
                emit(StreamEvent.TextDelta("t", "b"))
                emit(StreamEvent.TextEnd("t"))
            } finally {
                if (collections == 1) firstRunCancelled.complete(Unit)
            }
        }
        val result = StreamTextResult(sourceStream = upstream)

        assertEquals(2, result.fullStream.take(2).toList().size)
        withTimeout(5_000) { firstRunCancelled.await() }

        val restarted = result.fullStream.toList()
        assertEquals(2, collections)
        assertEquals(4, restarted.size)
        assertEquals(listOf("a", "b"), restarted.filterIsInstance<StreamEvent.TextDelta>().map { it.text })
        assertEquals(restarted, result.fullStream.toList())
    }

    @Test
    fun `upstream is collected in the collector context`() = runTest {
        val upstreamContextName = CompletableDeferred<String?>()
        val upstream = flow {
            upstreamContextName.complete(currentCoroutineContext()[CoroutineName]?.name)
            emit(StreamEvent.TextStart("t"))
            emit(StreamEvent.TextDelta("t", "a"))
            emit(StreamEvent.TextEnd("t"))
        }
        val result = StreamTextResult(sourceStream = upstream)

        withContext(CoroutineName("stream-consumer")) {
            result.fullStream.toList()
        }

        assertEquals("stream-consumer", upstreamContextName.await())
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
        runCurrent() // first collector parks at the gate after 2 events
        val second = async { result.fullStream.toList() } // must await + replay, not deadlock
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()

        val firstEvents = first.await()
        assertEquals(4, firstEvents.size)
        assertEquals(firstEvents, second.await()) // replay matches the live run
    }

    @Test
    fun `cancelling one concurrent fullStream collector does not cancel another collector`() = runTest {
        var collections = 0
        val firstDeltaSeen = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val upstream = flow {
            collections++
            emit(StreamEvent.TextStart("t"))
            emit(StreamEvent.TextDelta("t", "a"))
            gate.await()
            emit(StreamEvent.TextDelta("t", "b"))
            emit(StreamEvent.TextEnd("t"))
        }
        val result = StreamTextResult(sourceStream = upstream)

        val first = async(Dispatchers.Default) {
            result.fullStream.collect { event ->
                if (event is StreamEvent.TextDelta && event.text == "a") {
                    firstDeltaSeen.complete(Unit)
                    awaitCancellation()
                }
            }
        }
        withContext(Dispatchers.Default) { withTimeout(5_000) { firstDeltaSeen.await() } }

        val secondStarted = CompletableDeferred<Unit>()
        val second = async(Dispatchers.Default) {
            result.fullStream.onEach { secondStarted.complete(Unit) }.toList()
        }
        // second must be registered before first is cancelled, else abandonment legitimately restarts the upstream.
        withContext(Dispatchers.Default) { withTimeout(5_000) { secondStarted.await() } }
        first.cancelAndJoin()
        gate.complete(Unit)

        val secondEvents = withContext(Dispatchers.Default) { withTimeout(5_000) { second.await() } }
        assertEquals(1, collections)
        assertEquals(4, secondEvents.size)
        assertEquals(listOf("a", "b"), secondEvents.filterIsInstance<StreamEvent.TextDelta>().map { it.text })
    }

    @Test
    fun `a terminal error event remains memoised when the live collector throws`() = runTest {
        var collections = 0
        val upstream = flow {
            collections++
            emit(StreamEvent.TextStart("t"))
            emit(StreamEvent.TextDelta("t", "partial"))
            emit(StreamEvent.Error("provider failed"))
        }
        val result = StreamTextResult(sourceStream = upstream)

        assertFailsWith<UiMessageStreamError> {
            result.fullStream.collect { event ->
                if (event is StreamEvent.Error) throw UiMessageStreamError(event.message, event.cause)
            }
        }

        val replay = result.fullStream.toList()
        assertEquals(1, collections)
        assertEquals(3, replay.size)
        assertTrue(replay.last() is StreamEvent.Error)
    }

    @Test
    fun `thrown upstream errors are memoised and replayed without recollecting`() = runTest {
        var collections = 0
        val upstream = flow {
            collections++
            emit(StreamEvent.TextStart("t"))
            emit(StreamEvent.TextDelta("t", "partial"))
            throw IllegalStateException("boom")
        }
        val result = StreamTextResult(sourceStream = upstream)

        val firstEvents = mutableListOf<StreamEvent>()
        val first = assertFailsWith<IllegalStateException> {
            result.fullStream.collect { firstEvents += it }
        }
        val secondEvents = mutableListOf<StreamEvent>()
        val second = assertFailsWith<IllegalStateException> {
            result.fullStream.collect { secondEvents += it }
        }

        assertEquals("boom", first.message)
        assertEquals("boom", second.message)
        assertEquals(1, collections)
        assertEquals(firstEvents, secondEvents)
        assertEquals(listOf("partial"), secondEvents.filterIsInstance<StreamEvent.TextDelta>().map { it.text })
    }
}
