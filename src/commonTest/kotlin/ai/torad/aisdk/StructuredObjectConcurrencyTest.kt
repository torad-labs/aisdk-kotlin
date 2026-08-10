package ai.torad.aisdk

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class StructuredObjectConcurrencyTest {
    @Serializable
    private data class Person(val name: String = "", val age: Int = 0)

    private val personSchema: Schema<Person> = Schemas.jsonSchema(
        schema = buildJsonObject { put("type", JsonPrimitive("object")) },
        validate = { element -> aiSdkJson.decodeFromJsonElement(serializer<Person>(), element) },
    )

    private class GatedStructuredObjectTransport(
        private val gates: List<CompletableDeferred<Unit>>,
    ) : StructuredObjectTransport<String> {
        private val capturedRequests: MutableList<StructuredObjectRequest<String>> = mutableListOf()
        private var calls = 0

        val requests: List<StructuredObjectRequest<String>> get() = capturedRequests

        override fun submit(request: StructuredObjectRequest<String>): Flow<String> = flow {
            val gate = gates[calls++]
            capturedRequests += request
            gate.await()
            emit("""{"name":"Ada","age":42}""")
        }
    }

    private fun structuredObject(transport: StructuredObjectTransport<String>): StructuredObject<Person, String> =
        StructuredObject(
            StructuredObjectOptions<Person, String>(block = {
                api("/object")
                id("object-concurrency")
                schema(personSchema)
                transport(transport)
            }),
        )

    @Test
    fun `stop aborts a single in-flight submit`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val transport = GatedStructuredObjectTransport(listOf(gate))
        val structured = structuredObject(transport)

        val job = launch { structured.submit("only") }
        runCurrent()
        assertEquals(1, transport.requests.size)
        assertFalse(transport.requests[0].abortSignal.isAborted)

        structured.stop()

        assertTrue(transport.requests[0].abortSignal.isAborted)
        gate.complete(Unit)
        job.join()
    }

    @Test
    fun `a finished submit does not clobber the newer submit's abort controller`() = runTest {
        val firstGate = CompletableDeferred<Unit>()
        val secondGate = CompletableDeferred<Unit>()
        val transport = GatedStructuredObjectTransport(listOf(firstGate, secondGate))
        val structured = structuredObject(transport)

        // Two overlapping submits on one holder (the ordinary double-click shape).
        val first = launch { structured.submit("first") }
        runCurrent()
        val second = launch { structured.submit("second") }
        runCurrent()
        assertEquals(2, transport.requests.size)

        // The superseded submit completes. Its `finally` must leave the abort slot
        // alone — the slot belongs to the newer submit now.
        firstGate.complete(Unit)
        first.join()

        structured.stop()

        assertTrue(transport.requests[1].abortSignal.isAborted)
        secondGate.complete(Unit)
        second.join()
    }
}
