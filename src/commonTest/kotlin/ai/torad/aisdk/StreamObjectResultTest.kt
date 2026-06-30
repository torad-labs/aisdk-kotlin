@file:OptIn(LowLevelLanguageModelApi::class)

package ai.torad.aisdk

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StreamObjectResultTest {
    @Serializable
    private data class Person(val name: String = "", val age: Int = 0)

    @Serializable
    private data class StrictPerson(val name: String, val age: Int)

    private fun streamingModel(vararg deltas: String) = object : LanguageModel {
        override val modelId = "test/obj"
        override suspend fun generate(params: LanguageModelCallParams) =
            LanguageModelResult(text = deltas.joinToString(""), finishReason = FinishReason.Stop, usage = Usage())

        override fun stream(params: LanguageModelCallParams): Flow<StreamEvent> = flow {
            emit(StreamEvent.TextStart("t"))
            deltas.forEach { emit(StreamEvent.TextDelta("t", it)) }
            emit(StreamEvent.TextEnd("t"))
            emit(StreamEvent.Finish(1, FinishReason.Stop, Usage()))
        }
    }

    private fun outOfOrderDeltaModel(vararg deltas: Pair<String, String>) = object : LanguageModel {
        override val modelId = "test/out-of-order-blocks"
        override suspend fun generate(params: LanguageModelCallParams) =
            LanguageModelResult(text = "{}", finishReason = FinishReason.Stop, usage = Usage())

        override fun stream(params: LanguageModelCallParams): Flow<StreamEvent> = flow {
            emit(StreamEvent.TextStart("a"))
            emit(StreamEvent.TextStart("b"))
            deltas.forEach { (id, text) -> emit(StreamEvent.TextDelta(id, text)) }
            emit(StreamEvent.TextEnd("a"))
            emit(StreamEvent.TextEnd("b"))
            emit(StreamEvent.Finish(1, FinishReason.Stop, Usage()))
        }
    }

    @Test
    fun `objectValue returns the final typed object from the stream`() = runTest {
        val result = StreamObjectResult(
            model = streamingModel("""{"name":"An""", """n","age":30}"""),
            output = Output.obj(serializer<Person>()),
            prompt = "make a person",
        )
        assertEquals(Person("Ann", 30), result.objectValue())
    }

    @Test
    fun `partialObjectStream emits the object as it builds and ends at the complete value`() = runTest {
        val result = StreamObjectResult(
            model = streamingModel("""{"name":"Bo""", """b",""", """"age":7}"""),
            output = Output.obj(serializer<Person>()),
            prompt = "make a person",
        )
        val partials = result.partialObjectStream.toList()
        assertTrue(partials.isNotEmpty(), "at least one partial emitted")
        assertEquals(Person("Bob", 7), partials.last(), "final emission is the complete object")
        // partials are monotonic best-effort parses; the first should already have a name prefix.
        assertTrue(partials.first().name.startsWith("Bo"))
    }

    @Test
    fun `repairText salvages a final object wrapped in markdown fences`() = runTest {
        val result = StreamObjectResult(
            // The model wrapped the JSON in a ```json fence — invalid as-is.
            model = streamingModel("```json\n", """{"name":"Cy","age":9}""", "\n```"),
            output = Output.obj(serializer<Person>()),
            prompt = "make a person",
            repairText = { raw ->
                val start = raw.indexOf('{')
                val end = raw.lastIndexOf('}')
                if (start in 0 until end) raw.substring(start, end + 1) else null
            },
        )
        assertEquals(Person("Cy", 9), result.objectValue())
    }

    @Test
    fun `finish returns the object plus terminal usage and finishReason`() = runTest {
        val model = object : LanguageModel {
            override val modelId = "test/obj"
            override suspend fun generate(params: LanguageModelCallParams) =
                LanguageModelResult(text = "{}", finishReason = FinishReason.Stop, usage = Usage())
            override fun stream(params: LanguageModelCallParams): Flow<StreamEvent> = flow {
                emit(StreamEvent.TextStart("t"))
                emit(StreamEvent.TextDelta("t", """{"name":"Zed","age":5}"""))
                emit(StreamEvent.TextEnd("t"))
                emit(StreamEvent.Finish(1, FinishReason.ToolCalls, Usage(), rawFinishReason = "stop"))
            }
        }
        val finish = StreamObjectResult(model, Output.obj(serializer<Person>()), prompt = "go").finish()
        assertEquals(Person("Zed", 5), finish.value)
        assertEquals(FinishReason.ToolCalls, finish.finishReason)
    }

    @Test
    fun `elementStream emits each array element as it completes`() = runTest {
        val arrayOutput = Output.Arr(serializer<Person>())
        val result = StreamObjectResult(
            model = streamingModel(
                """{"elements":[{"name":"A","age":1},""",
                """{"name":"B","age":2},""",
                """{"name":"C","age":3}]}""",
            ),
            output = arrayOutput,
            prompt = "make people",
        )
        val elements = result.elementStream(arrayOutput).toList()
        assertEquals(listOf(Person("A", 1), Person("B", 2), Person("C", 3)), elements)
    }

    @Test
    fun `finish reconstructs JSON by text block id order instead of raw delta order`() = runTest {
        val model = object : LanguageModel {
            override val modelId = "test/multi-block"
            override suspend fun generate(params: LanguageModelCallParams) =
                LanguageModelResult(text = "{}", finishReason = FinishReason.Stop, usage = Usage())

            override fun stream(params: LanguageModelCallParams): Flow<StreamEvent> = flow {
                emit(StreamEvent.TextStart("a"))
                emit(StreamEvent.TextDelta("a", "{\"name\":\"Ann\""))
                emit(StreamEvent.TextStart("b"))
                emit(StreamEvent.TextDelta("b", "}"))
                emit(StreamEvent.TextDelta("a", ",\"age\":30"))
                emit(StreamEvent.TextEnd("a"))
                emit(StreamEvent.TextEnd("b"))
                emit(StreamEvent.Finish(1, FinishReason.Stop, Usage()))
            }
        }

        val result = StreamObjectResult(model, Output.obj(serializer<Person>()), prompt = "go")
        assertEquals(Person("Ann", 30), result.objectValue())
    }

    @Test
    fun `finish respects TextStart block order when later block delta arrives first`() = runTest {
        val result = StreamObjectResult(
            outOfOrderDeltaModel("b" to "}", "a" to """{"name":"Ann","age":30"""),
            Output.obj(serializer<Person>()),
            prompt = "go",
        )

        assertEquals(Person("Ann", 30), result.objectValue())
    }

    @Test
    fun `partialObjectStream respects TextStart block order when deltas are out of order`() = runTest {
        val result = StreamObjectResult(
            outOfOrderDeltaModel("b" to "}", "a" to """{"name":"Bea","age":4"""),
            Output.obj(serializer<Person>()),
            prompt = "go",
        )

        assertEquals(Person("Bea", 4), result.partialObjectStream.toList().last())
    }

    @Test
    fun `elementStream respects TextStart block order when deltas are out of order`() = runTest {
        val arrayOutput = Output.Arr(serializer<Person>())
        val result = StreamObjectResult(
            outOfOrderDeltaModel(
                "b" to """{"name":"B","age":2}]}""",
                "a" to """{"elements":[{"name":"A","age":1},""",
            ),
            arrayOutput,
            prompt = "go",
        )

        assertEquals(listOf(Person("A", 1), Person("B", 2)), result.elementStream(arrayOutput).toList())
    }

    @Test
    fun `elementStream retries an out of order element that failed partial decoding`() = runTest {
        val arrayOutput = Output.Arr(serializer<StrictPerson>())
        val result = StreamObjectResult(
            outOfOrderDeltaModel(
                "a" to "{\"elements\":[{\"name\":\"A\"",
                "b" to """},{"name":"B","age":2}]}""",
                "a" to ",\"age\":1",
            ),
            arrayOutput,
            prompt = "go",
        )

        assertEquals(
            listOf(StrictPerson("A", 1), StrictPerson("B", 2)),
            result.elementStream(arrayOutput).toList(),
        )
    }

    @Test
    fun `terminal stream errors are replayed without recollecting the model stream`() = runTest {
        var streamCollections = 0
        val model = object : LanguageModel {
            override val modelId = "test/error-replay"
            override suspend fun generate(params: LanguageModelCallParams) =
                LanguageModelResult(text = "{}", finishReason = FinishReason.Stop, usage = Usage())

            override fun stream(params: LanguageModelCallParams): Flow<StreamEvent> = flow {
                streamCollections++
                emit(StreamEvent.TextStart("t"))
                emit(StreamEvent.TextDelta("t", "{\"name\":\"Err\""))
                emit(StreamEvent.Error("provider failed", IllegalStateException("root cause")))
            }
        }
        val result = StreamObjectResult(model, Output.obj(serializer<Person>()), prompt = "go")

        assertFailsWith<UiMessageStreamError> { result.partialObjectStream.toList() }
        val second = assertFailsWith<UiMessageStreamError> { result.objectValue() }

        assertEquals("root cause", second.cause?.message)
        assertEquals(1, streamCollections)
    }

    @Test
    fun `textStream emits stable block ordered deltas for interleaved object streams`() = runTest {
        val model = object : LanguageModel {
            override val modelId = "test/multi-block"
            override suspend fun generate(params: LanguageModelCallParams) =
                LanguageModelResult(text = "{}", finishReason = FinishReason.Stop, usage = Usage())

            override fun stream(params: LanguageModelCallParams): Flow<StreamEvent> = flow {
                emit(StreamEvent.TextStart("a"))
                emit(StreamEvent.TextDelta("a", "{\"name\":\"Ann\""))
                emit(StreamEvent.TextStart("b"))
                emit(StreamEvent.TextDelta("b", "}"))
                emit(StreamEvent.TextDelta("a", ",\"age\":30"))
                emit(StreamEvent.TextEnd("a"))
                emit(StreamEvent.TextEnd("b"))
                emit(StreamEvent.Finish(1, FinishReason.Stop, Usage()))
            }
        }

        val result = StreamObjectResult(model, Output.obj(serializer<Person>()), prompt = "go")
        assertEquals(listOf("{\"name\":\"Ann\"", ",\"age\":30", "}"), result.textStream.toList())
    }

    @Test
    fun `elementStream supports top level arrays during live streaming`() = runTest {
        val arrayOutput = Output.Arr(serializer<Person>())
        val result = StreamObjectResult(
            model = streamingModel(
                """[{"name":"A","age":1},""",
                """{"name":"B","age":2},""",
                """{"name":"C","age":3}]""",
            ),
            output = arrayOutput,
            prompt = "make people",
        )

        val elements = result.elementStream(arrayOutput).toList()
        assertEquals(listOf(Person("A", 1), Person("B", 2), Person("C", 3)), elements)
    }
}
