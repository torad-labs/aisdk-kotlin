package ai.torad.aisdk

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PublicEntryPointCoverageTest {
    @Serializable
    private data class Person(val name: String = "", val age: Int = 0)

    private val personSchema: Schema<Person> = Schemas.jsonSchema(
        schema = buildJsonObject { put("type", JsonPrimitive("object")) },
        validate = { element -> aiSdkJson.decodeFromJsonElement(serializer<Person>(), element) },
    )

    private class RecordingCompletionTransport(
        private val deltas: List<String>,
    ) : CompletionTransport {
        private val capturedRequests: MutableList<CompletionRequest> = mutableListOf()

        val requests: List<CompletionRequest> get() = capturedRequests

        override fun complete(request: CompletionRequest): Flow<String> = flow {
            capturedRequests += request
            for (delta in deltas) emit(delta)
        }
    }

    private class RecordingStructuredObjectTransport(
        private val chunks: List<String>,
    ) : StructuredObjectTransport<String> {
        private val capturedRequests: MutableList<StructuredObjectRequest<String>> = mutableListOf()

        val requests: List<StructuredObjectRequest<String>> get() = capturedRequests

        override fun submit(request: StructuredObjectRequest<String>): Flow<String> = flow {
            capturedRequests += request
            for (chunk in chunks) emit(chunk)
        }
    }

    @Test
    fun `Completion wrapper updates state through public transport`() = runTest {
        val finishes = mutableListOf<Pair<String, String>>()
        val transport = RecordingCompletionTransport(listOf("hel", "lo"))
        val completion = Completion(
            UseCompletionOptions(block = {
                api("/completion")
                id("completion-test")
                initialInput("draft")
                initialCompletion("old")
                headers(mapOf("x-base" to "base"))
                body(mapOf("base" to JsonPrimitive(true)))
                streamProtocol(CompletionStreamProtocol.Text)
                transport(transport)
                onFinish { prompt, text -> finishes += prompt to text }
            }),
        )

        assertEquals("completion-test", completion.id)
        assertEquals("/completion", completion.api)
        assertEquals(CompletionStreamProtocol.Text, completion.streamProtocol)
        assertEquals("draft", completion.input)
        assertEquals("old", completion.completion)
        assertFalse(completion.loading)

        completion.setInput("typed")
        val result = completion.complete(
            prompt = completion.input,
            requestOptions = CompletionRequestOptions(block = {
                headers(mapOf("x-request" to "request"))
                body(mapOf("request" to JsonPrimitive("body")))
            }),
        )

        assertEquals("hello", result)
        assertEquals("hello", completion.completion)
        assertEquals("typed", completion.input)
        assertFalse(completion.loading)
        assertNull(completion.error)
        assertEquals(listOf("typed" to "hello"), finishes)
        val request = transport.requests.single()
        assertEquals("typed", request.prompt)
        assertEquals(mapOf("x-base" to "base", "x-request" to "request"), request.headers)
        assertEquals(JsonPrimitive(true), request.body["base"])
        assertEquals(JsonPrimitive("body"), request.body["request"])
        assertEquals(CompletionStreamProtocol.Text, request.streamProtocol)
    }

    @Test
    fun `StructuredObject wrapper updates state through public transport`() = runTest {
        val finishes = mutableListOf<StructuredObjectFinish<Person>>()
        val transport = RecordingStructuredObjectTransport(listOf("""{"name":"Ada",""", """"age":42}"""))
        val structured = StructuredObject(
            StructuredObjectOptions<Person, String>(block = {
                api("/object")
                id("object-test")
                schema(personSchema)
                initialValue(Person("Initial", 1))
                headers(mapOf("x-object" to "yes"))
                transport(transport)
                onFinish { finishes += it }
            }),
        )

        assertEquals("object-test", structured.id)
        assertEquals("/object", structured.api)
        assertEquals(Person("Initial", 1), structured.value)
        assertEquals(Person("Initial", 1), structured.objectValue)
        assertNull(structured.rawValue)
        assertNull(structured.error)
        assertFalse(structured.loading)

        structured.submit("make person")

        assertEquals(Person("Ada", 42), structured.value)
        assertEquals(structured.value, structured.objectValue)
        assertNotNull(structured.rawValue)
        assertNull(structured.error)
        assertFalse(structured.loading)
        assertEquals(Person("Ada", 42), finishes.single().value)
        assertNull(finishes.single().error)
        val request = transport.requests.single()
        assertEquals("make person", request.input)
        assertEquals(mapOf("x-object" to "yes"), request.headers)
        assertFalse(request.abortSignal.isAborted)

        structured.clear()

        assertIs<StructuredObjectPhase.Idle>(structured.state.value)
        assertNull(structured.value)
    }

    @Test
    fun `EmbeddingMath cosine similarity pins zero vector behavior`() {
        // Current contract returns 0 instead of NaN when either vector has no magnitude.
        assertEquals(0f, EmbeddingMath.cosineSimilarity(listOf(0f, 0f), listOf(1f, 2f)))
    }

    @Test
    fun `EmbeddingMath cosine similarity rejects mismatched dimensions`() {
        val error = assertFailsWith<IllegalArgumentException> {
            EmbeddingMath.cosineSimilarity(listOf(1f, 2f), listOf(1f))
        }

        assertEquals("Embedding vectors must have the same dimension", error.message)
    }
}
