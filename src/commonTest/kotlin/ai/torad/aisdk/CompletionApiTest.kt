package ai.torad.aisdk

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class CompletionApiTest {
    @Test
    fun `callCompletionApi rethrows cancellation without reporting an error`() = runTest {
        var loading = false
        var error: Throwable? = null
        var onErrorCalls = 0
        val transport = DirectCompletionTransport {
            flow {
                throw CancellationException("cancelled")
            }
        }

        assertFailsWith<CancellationException> {
            CompletionApi.callCompletionApi(
                CallCompletionApiOptions {
                    prompt("hello")
                    transport(transport)
                    setLoading({ loading = it })
                    setError({ error = it })
                    onError({ onErrorCalls += 1 })
                },
            )
        }

        assertEquals(false, loading)
        assertNull(error)
        assertEquals(0, onErrorCalls)
    }

    @Test
    fun `a new request clears the previous run's completion instead of relabelling it as streaming`() = runTest {
        var runs = 0
        val secondResponseGate = CompletableDeferred<Unit>()
        val transport = DirectCompletionTransport {
            runs += 1
            if (runs == 1) {
                flowOf("Summary of A")
            } else {
                flow {
                    secondResponseGate.await()
                    emit("Summary of B")
                }
            }
        }
        val completion = Completion(UseCompletionOptions(block = { transport(transport) }))

        assertEquals("Summary of A", completion.complete("summarize A"))

        val second = async { completion.complete("summarize B") }
        runCurrent() // run 2 is connecting: loading, but no chunk has arrived yet.

        assertEquals(true, completion.loading)
        assertEquals("", completion.completion)

        secondResponseGate.complete(Unit)
        assertEquals("Summary of B", second.await())
    }
}
