package ai.torad.aisdk

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Best practice #7 — Agent is an interface; ToolLoopAgent is one impl.
 * Application code depending on the [Agent] interface must accept a
 * substitute implementation.
 */
class AgentInterfaceTest {

    private class FakeAgent : Agent<Unit, String> {
        override val tools: ToolSet<Unit> = ToolSet()
        var generatedPrompt: String? = null
        override fun generate(
            prompt: String?,
            messages: List<ModelMessage>,
            options: Unit?,
            abortSignal: AbortSignal,
        ): Flow<GenerateResult<String>> = flow {
            generatedPrompt = prompt
            emit(
                GenerateResult(
                    "fake-output",
                    text = "fake-output",
                    steps = emptyList(),
                    finishReason = FinishReason.Stop,
                    usage = Usage(),
                )
            )
        }
        override fun stream(
            prompt: String?,
            messages: List<ModelMessage>,
            options: Unit?,
            abortSignal: AbortSignal,
        ): Flow<StreamEvent> = flowOf(
            StreamEvent.TextStart("t1"),
            StreamEvent.TextDelta("t1", "fake-output"),
            StreamEvent.TextEnd("t1"),
            StreamEvent.Finish(1, FinishReason.Stop, Usage()),
        )
    }

    @Test
    fun `fake_agent_satisfies_Agent_contract`() = runTest {
        val agent: Agent<Unit, String> = FakeAgent()
        val result = agent.generate("hello").first()
        assertEquals("fake-output", result.output)
        assertEquals(FinishReason.Stop, result.finishReason)
    }

    @Test
    fun `fake_agent_streaming_emits_text_then_finish`() = runTest {
        val agent: Agent<Unit, String> = FakeAgent()
        val events = mutableListOf<StreamEvent>()
        agent.stream("hi").collect { events.add(it) }
        assertTrue(events.any { it is StreamEvent.TextDelta })
        assertTrue(events.last() is StreamEvent.Finish)
    }
}
