package ai.torad.aisdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest

/**
 * Validates the v6-aligned `presencePenalty` + `frequencyPenalty`
 * fields wired into the prepareCall resolution chain (gap #3 in
 * `docs/AISDK_PORT_GAPS.md`). Same `Step ?: Agent ?: agent-default
 * ?: provider-default` resolution as the existing sampler params.
 */
class PrepareCallPenaltiesTest {

    /** A LanguageModel that just records the params it was called with. */
    private class CapturingModel : LanguageModel {
        var captured: LanguageModelCallParams? = null
        override val modelId: String = "test/capture"
        override suspend fun generate(params: LanguageModelCallParams): LanguageModelResult {
            captured = params
            return LanguageModelResult(
                text = "",
                finishReason = FinishReason.Stop,
                usage = Usage(),
            )
        }
        override fun stream(params: LanguageModelCallParams): Flow<StreamEvent> = flow {
            captured = params
            emit(StreamEvent.StepFinish(1, FinishReason.Stop, Usage()))
        }
    }

    @Test
    fun `given agent penalty defaults when no prepareCall override then params carry the agent defaults`() = runTest {
        // GIVEN
        val capture = CapturingModel()
        val agent = ToolLoopAgent<Unit, String>(
            model = capture,
            instructions = "",
            tools = toolSetOf(),
            presencePenalty = 0.2f,
            frequencyPenalty = 0.4f,
        )

        // WHEN
        agent.generate(prompt = "hi")

        // THEN
        assertEquals(0.2f, capture.captured?.presencePenalty)
        assertEquals(0.4f, capture.captured?.frequencyPenalty)
    }

    @Test
    fun `given prepareCall override when invoked then call-level penalties win over agent defaults`() = runTest {
        // GIVEN
        val capture = CapturingModel()
        val agent = ToolLoopAgent<Unit, String>(
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
        agent.generate(prompt = "hi")

        // THEN
        assertEquals(0.7f, capture.captured?.presencePenalty, "prepareCall wins over agent default")
        assertEquals(0.9f, capture.captured?.frequencyPenalty)
    }

    @Test
    fun `given prepareStep override when invoked then step-level penalties win over prepareCall`() = runTest {
        // GIVEN
        val capture = CapturingModel()
        val agent = ToolLoopAgent<Unit, String>(
            model = capture,
            instructions = "",
            tools = toolSetOf(),
            presencePenalty = 0.2f,
            prepareCall = { AgentSettings(presencePenalty = 0.5f) },
            prepareStep = { StepSettings(presencePenalty = 0.9f) },
        )

        // WHEN
        agent.generate(prompt = "hi")

        // THEN
        assertEquals(0.9f, capture.captured?.presencePenalty, "prepareStep wins over prepareCall + agent default")
    }
}
