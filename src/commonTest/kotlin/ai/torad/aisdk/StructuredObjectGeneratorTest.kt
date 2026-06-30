@file:OptIn(LowLevelLanguageModelApi::class)

package ai.torad.aisdk

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StructuredObjectGeneratorTest {
    @Serializable
    private data class Person(val name: String = "", val age: Int = 0)

    // A schema whose validate decodes JSON -> Person (throwing on a type mismatch, which is how a
    // schema-validation failure surfaces). The wire jsonSchema is a minimal object stub — the mock
    // model ignores it; only the generator's responseFormat wiring reads it.
    private val personSchema: Schema<Person> = Schemas.jsonSchema(
        schema = buildJsonObject { put("type", JsonPrimitive("object")) },
        validate = { element -> aiSdkJson.decodeFromJsonElement(serializer<Person>(), element) },
    )

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
    fun `generate returns the full typed object`() = runTest {
        val generator = StructuredObjectGenerator(
            model = streamingModel("""{"name":"Ann",""", """"age":30}"""),
            schema = personSchema,
        )

        val finish = generator.generate(GenerationInput.Prompt("make a person"))

        assertEquals(Person("Ann", 30), finish.value)
        assertNull(finish.error)
    }

    @Test
    fun `stream emits accumulating partials ending in Done`() = runTest {
        val generator = StructuredObjectGenerator(
            model = streamingModel("""{"name":"Bo""", """b","age":7}"""),
            schema = personSchema,
        )

        val phases = generator.stream(GenerationInput.Prompt("make a person")).toList()

        val done = phases.last()
        assertIs<StructuredObjectPhase.Done<Person>>(done)
        assertEquals(Person("Bob", 7), done.value, "final phase is the complete object")
        assertNull(done.error)
        // The middle Streaming phases accumulate — at least one carries a partial name prefix
        // before the age field arrives, proving partials build incrementally rather than appearing
        // whole at the end.
        val streamingPartials = phases.filterIsInstance<StructuredObjectPhase.Streaming<Person>>()
        assertTrue(
            streamingPartials.any { it.partial?.name?.startsWith("Bo") == true },
            "an intermediate partial exposes the building name",
        )
    }

    @Test
    fun `schema-validation failure surfaces in the finish error`() = runTest {
        val generator = StructuredObjectGenerator(
            // age is a JSON string, not an int — decoding into Person.age throws.
            model = streamingModel("""{"name":"X","age":"abc"}"""),
            schema = personSchema,
        )

        val finish = generator.generate(GenerationInput.Prompt("make a person"))

        assertNotNull(finish.error, "validation failure is reported on the finish")
        assertIs<TypeValidationError>(finish.error)
        assertNull(finish.value, "no value when the only parse failed validation")
    }

    @Test
    fun `schema-validation failure also rides the terminal phase`() = runTest {
        val generator = StructuredObjectGenerator(
            model = streamingModel("""{"name":"X","age":"abc"}"""),
            schema = personSchema,
        )

        val done = generator.stream(GenerationInput.Prompt("make a person")).toList().last()

        assertIs<StructuredObjectPhase.Done<Person>>(done)
        assertNotNull(done.error)
        assertIs<TypeValidationError>(done.error)
    }

    @Test
    fun `abort preserves the latest partial as a terminal Done`() = runTest {
        val controller = AbortController()
        // The model emits one good partial, fires the abort, then would emit more — the loop's
        // throwIfAborted trips before consuming the second delta, so the partial is preserved.
        val model = object : LanguageModel {
            override val modelId = "test/obj"
            override suspend fun generate(params: LanguageModelCallParams) =
                LanguageModelResult(text = "{}", finishReason = FinishReason.Stop, usage = Usage())

            override fun stream(params: LanguageModelCallParams): Flow<StreamEvent> = flow {
                emit(StreamEvent.TextStart("t"))
                emit(StreamEvent.TextDelta("t", """{"name":"Partial","age":1}"""))
                controller.abort()
                emit(StreamEvent.TextDelta("t", ""","ignored":true}"""))
                emit(StreamEvent.TextEnd("t"))
            }
        }
        val generator = StructuredObjectGenerator(
            model = model,
            schema = personSchema,
            config = CallConfig(abortSignal = controller.signal),
        )

        val done = generator.stream(GenerationInput.Prompt("make a person")).toList().last()

        assertIs<StructuredObjectPhase.Done<Person>>(done)
        assertEquals(Person("Partial", 1), done.value, "abort keeps the last good partial")
        assertNull(done.error, "an abort is a stop, not an error")
    }
}
