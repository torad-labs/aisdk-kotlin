package ai.torad.aisdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer

/**
 * Validates the v6-aligned `presencePenalty` + `frequencyPenalty`
 * fields wired into the prepareCall resolution chain (gap #3 in
 * historical parity work). Same `Step ?: Agent ?: agent-default
 * ?: provider-default` resolution as the existing sampler params.
 */
class PrepareCallPenaltiesTest {

    @Serializable
    data class StructuredAnswer(val value: String)

    /** A LanguageModel that just records the params it was called with. */
    private class CapturingModel(private val text: String = "") : LanguageModel {
        var captured: LanguageModelCallParams? = null
        override val modelId: String = "test/capture"
        override suspend fun generate(params: LanguageModelCallParams): LanguageModelResult {
            captured = params
            return LanguageModelResult(
                text = text,
                finishReason = FinishReason.Stop,
                usage = Usage(),
            )
        }
        override fun stream(params: LanguageModelCallParams): Flow<StreamEvent> = flow {
            captured = params
            if (text.isNotEmpty()) {
                emit(StreamEvent.TextStart("t1"))
                emit(StreamEvent.TextDelta("t1", text))
                emit(StreamEvent.TextEnd("t1"))
            }
            emit(StreamEvent.StepFinish(1, FinishReason.Stop, Usage()))
        }
    }

    @Test
    fun `given agent penalty defaults when no prepareCall override then params carry the agent defaults`() = runTest {
        // GIVEN
        val capture = CapturingModel()
        val agent = TestToolLoopAgent<Unit, String>(
            model = capture,
            instructions = "",
            tools = toolSetOf(),
            presencePenalty = 0.2f,
            frequencyPenalty = 0.4f,
        )

        // WHEN
        agent.generate(prompt = "hi").first()

        // THEN
        assertEquals(0.2f, capture.captured?.presencePenalty)
        assertEquals(0.4f, capture.captured?.frequencyPenalty)
    }

    @Test
    fun `given prepareCall override when invoked then call-level penalties win over agent defaults`() = runTest {
        // GIVEN
        val capture = CapturingModel()
        val agent = TestToolLoopAgent<Unit, String>(
            model = capture,
            instructions = "",
            tools = toolSetOf(),
            presencePenalty = 0.2f,
            frequencyPenalty = 0.4f,
            prepareCall = {
                AgentSettings(
                    presencePenalty = 0.7f,
                    frequencyPenalty = 0.9f,
                )
            },
        )

        // WHEN
        agent.generate(prompt = "hi").first()

        // THEN
        assertEquals(0.7f, capture.captured?.presencePenalty, "prepareCall wins over agent default")
        assertEquals(0.9f, capture.captured?.frequencyPenalty)
    }

    @Test
    fun `given prepareStep override when invoked then step-level penalties win over prepareCall`() = runTest {
        // GIVEN
        val capture = CapturingModel()
        val agent = TestToolLoopAgent<Unit, String>(
            model = capture,
            instructions = "",
            tools = toolSetOf(),
            presencePenalty = 0.2f,
            prepareCall = { AgentSettings(presencePenalty = 0.5f) },
            prepareStep = { StepSettings(presencePenalty = 0.9f) },
        )

        // WHEN
        agent.generate(prompt = "hi").first()

        // THEN
        assertEquals(0.9f, capture.captured?.presencePenalty, "prepareStep wins over prepareCall + agent default")
    }

    @Test
    fun `given agent structured output when invoked then call params carry JSON response format`() = runTest {
        // GIVEN
        val capture = CapturingModel("""{"value":"ok"}""")
        val agent = TestToolLoopAgent<Unit, StructuredAnswer>(
            model = capture,
            instructions = "",
            tools = toolSetOf(),
            output = outputObj(serializer(), name = "StructuredAnswer"),
        )

        // WHEN
        agent.generate(prompt = "hi").first()

        // THEN
        val responseFormat = capture.captured?.responseFormat
        kotlin.test.assertIs<ResponseFormat.Json>(responseFormat)
        assertEquals("StructuredAnswer", responseFormat.schemaName)
        assertEquals("object", responseFormat.schemaJson?.jsonObject?.get("type")?.jsonPrimitive?.content)
    }

    @Test
    fun `given call and step response format overrides when invoked then step-level response format wins`() = runTest {
        // GIVEN
        val capture = CapturingModel()
        val callFormat = ResponseFormat.Json(schemaName = "Call")
        val stepFormat = ResponseFormat.Json(schemaName = "Step")
        val agent = TestToolLoopAgent<Unit, String>(
            model = capture,
            instructions = "",
            tools = toolSetOf(),
            prepareCall = { AgentSettings(responseFormat = callFormat) },
            prepareStep = { StepSettings(responseFormat = stepFormat) },
        )

        // WHEN
        agent.generate(prompt = "hi").first()

        // THEN
        assertEquals(stepFormat, capture.captured?.responseFormat)
    }

    @Test
    fun `given provider options from prepareCall and prepareStep when invoked then maps are merged`() = runTest {
        // GIVEN
        val capture = CapturingModel()
        val agent = TestToolLoopAgent<Unit, String>(
            model = capture,
            instructions = "",
            tools = toolSetOf(),
            prepareCall = {
                AgentSettings(providerOptions = mapOf("common" to JsonPrimitive("call"), "callOnly" to JsonPrimitive(true)))
            },
            prepareStep = {
                StepSettings(providerOptions = mapOf("common" to JsonPrimitive("step"), "stepOnly" to JsonPrimitive(1)))
            },
        )

        // WHEN
        agent.generate(prompt = "hi").first()

        // THEN
        assertEquals(JsonPrimitive("step"), capture.captured?.providerOptions?.get("common"))
        assertEquals(JsonPrimitive(true), capture.captured?.providerOptions?.get("callOnly"))
        assertEquals(JsonPrimitive(1), capture.captured?.providerOptions?.get("stepOnly"))
    }
}
