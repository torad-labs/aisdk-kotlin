package ai.torad.aisdk

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

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
}
