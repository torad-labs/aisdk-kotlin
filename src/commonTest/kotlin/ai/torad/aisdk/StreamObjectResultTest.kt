package ai.torad.aisdk

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StreamObjectResultTest {
    @Serializable
    private data class Person(val name: String = "", val age: Int = 0)

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

    @Test
    fun `objectValue returns the final typed object from the stream`() = runTest {
        val result = streamObjectResult(
            model = streamingModel("""{"name":"An""", """n","age":30}"""),
            output = Output.obj(serializer<Person>()),
            prompt = "make a person",
        )
        assertEquals(Person("Ann", 30), result.objectValue())
    }

    @Test
    fun `partialObjectStream emits the object as it builds and ends at the complete value`() = runTest {
        val result = streamObjectResult(
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
        val result = streamObjectResult(
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
    fun `elementStream emits each array element as it completes`() = runTest {
        val arrayOutput = Output.Arr(serializer<Person>())
        val result = streamObjectResult(
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
}
