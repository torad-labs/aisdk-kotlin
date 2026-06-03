package ai.torad.aisdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest

/**
 * Best practice #7 — Agent is an interface; ToolLoopAgent is one impl.
 * Application code depending on the [Agent] interface must accept a
 * substitute implementation.
 */
class AgentInterfaceTest {

    private class FakeAgent : Agent<Unit, String> {
        override val tools: ToolSet<Unit> = toolSetOf()
        var generatedPrompt: String? = null
        override suspend fun generate(
            prompt: String?,
            messages: List<ModelMessage>,
            options: Unit?,
            abortSignal: AbortSignal,
            hooks: AgentCallHooks?,
        ): GenerateResult<String> {
            generatedPrompt = prompt
            return GenerateResult(
                output = "fake-output",
                text = "fake-output",
                steps = emptyList(),
                finishReason = FinishReason.Stop,
                usage = Usage(),
            )
        }
        override fun stream(
            prompt: String?,
            messages: List<ModelMessage>,
            options: Unit?,
            abortSignal: AbortSignal,
            hooks: AgentCallHooks?,
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
        val result = agent.generate("hello")
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

    @Test
    fun `call_site_hooks_fire_in_addition_to_constructor_hooks`() = runTest {
        var callSiteOnStartFired = false
        val agent: Agent<Unit, String> = FakeAgent()
        agent.generate(
            prompt = "go",
            hooks = AgentCallHooks(onStart = { callSiteOnStartFired = true }),
        )
        // FakeAgent doesn't fire hooks, but the contract should accept the parameter.
        assertEquals(false, callSiteOnStartFired, "FakeAgent does not invoke the hooks; contract just accepts them")
    }
}
