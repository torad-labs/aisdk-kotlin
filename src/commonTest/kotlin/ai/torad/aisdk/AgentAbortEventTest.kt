@file:OptIn(LowLevelLanguageModelApi::class)

package ai.torad.aisdk

import ai.torad.aisdk.testing.FlowDrain.drainAllItems
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

class AgentAbortEventTest {
    private fun textModel(text: String) = object : LanguageModel {
        override val modelId = "m"
        override suspend fun generate(params: LanguageModelCallParams) =
            LanguageModelResult(text = text, finishReason = FinishReason.Stop, usage = Usage())
        override fun stream(params: LanguageModelCallParams): Flow<StreamEvent> = flow {
            emit(StreamEvent.TextStart("t"))
            emit(StreamEvent.TextDelta("t", text))
            emit(StreamEvent.TextEnd("t"))
            emit(StreamEvent.Finish(1, FinishReason.Stop, Usage()))
        }
    }

    @Test
    fun `a pre-aborted stream fires the Aborted lifecycle event`() = runTest {
        val controller = AbortController()
        controller.abort()
        val agent = TestToolLoopAgent<Unit, String>(
            model = textModel("hi"),
            instructions = "x",
            tools = ToolSet(),
        )
        val events = drainAllItems(
            agent.events(prompt = "go", abortSignal = controller.signal),
        )
        // The pre-aborted loop fires AgentEvent.Aborted — the events() equivalent of the former
        // terminal StreamEvent.Abort + onAbort hook.
        assertTrue(events.any { it is AgentEvent.Aborted }, "Aborted lifecycle event fired")
    }

    @Test
    fun `a normal completion does not emit Abort`() = runTest {
        val agent = TestToolLoopAgent<Unit, String>(
            model = textModel("hi"),
            instructions = "x",
            tools = ToolSet(),
        )
        val events = drainAllItems(agent.events(prompt = "go"))
        assertTrue(events.none { it is AgentEvent.Aborted }, "no Aborted on normal completion")
        assertTrue(events.any { it is AgentEvent.Finished<*, *> }, "normal completion finishes")
    }
}
