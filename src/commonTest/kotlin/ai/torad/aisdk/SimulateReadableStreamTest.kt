package ai.torad.aisdk

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class SimulateReadableStreamTest {
    @Test
    fun `emits chunks in order with virtual inter chunk delay`() = runTest {
        val emissions = mutableListOf<Pair<Long, String>>()

        val chunks = SimulateReadableStream(
            chunks = listOf("a", "b", "c"),
            delayMillis = 25L,
        ).onEach { value ->
            emissions += testScheduler.currentTime to value
        }.toList()

        assertEquals(listOf("a", "b", "c"), chunks)
        assertEquals(
            listOf(0L to "a", 25L to "b", 50L to "c"),
            emissions,
        )
        assertEquals(50L, testScheduler.currentTime)
    }

    @Test
    fun `returns a cold flow`() = runTest {
        val stream = SimulateReadableStream(listOf(1, 2), delayMillis = 10L)

        assertEquals(listOf(1, 2), stream.toList())
        assertEquals(listOf(1, 2), stream.toList())
    }
}
