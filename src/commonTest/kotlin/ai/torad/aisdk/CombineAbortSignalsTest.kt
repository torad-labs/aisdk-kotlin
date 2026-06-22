package ai.torad.aisdk

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CombineAbortSignalsTest {
    @Test
    fun `combined signal is inert until a source fires then aborts`() {
        val a = AbortController()
        val b = AbortController()
        val combined = CombineAbortSignals(a.signal, b.signal)

        assertFalse(combined.isAborted)
        a.abort()
        assertTrue(combined.isAborted)
    }

    /**
     * Regression (data-race / UB hazard): the teardown callback iterated the registration set while
     * the wiring loop mutated it. A source firing from another thread DURING wiring races a plain
     * MutableList (ConcurrentModificationException on JVM, memory-unsafe on Native). The fix
     * publishes the registrations via an immutable snapshot (mirrors AbortController). Probabilistic
     * pre-fix, always safe post-fix — stress the wiring window from a different thread.
     */
    @Test
    fun `safe when a source fires concurrently during wiring`() = runTest {
        repeat(300) {
            val sources = List(8) { AbortController() }
            val firing = sources.map { src -> async(Dispatchers.Default) { src.abort() } }
            val combined = withContext(Dispatchers.Default) {
                CombineAbortSignals(*Array(sources.size) { sources[it].signal })
            }
            firing.awaitAll()
            assertTrue(combined.isAborted, "at least one source aborted, so the combination must be aborted")
        }
    }
}
