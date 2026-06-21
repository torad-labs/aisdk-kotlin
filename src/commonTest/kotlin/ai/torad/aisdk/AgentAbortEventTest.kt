package ai.torad.aisdk

import ai.torad.aisdk.testing.FlowDrain.drainAllItems
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun `a pre-aborted stream emits a terminal Abort event and fires onAbort`() = runTest {
        val controller = AbortController()
        controller.abort()
        var onAbortFired = false
        val agent = TestToolLoopAgent<Unit, String>(
            model = textModel("hi"),
            instructions = "x",
            tools = ToolSet(),
        )
        val events = drainAllItems(
            agent.stream(
                prompt = "go",
                abortSignal = controller.signal,
                hooks = AgentCallHooks(onAbort = { onAbortFired = true }),
            ),
        )
        // The loop-top abort poll emits StreamEvent.Abort as the terminal event.
        assertTrue(events.any { it is StreamEvent.Abort }, "Abort event emitted")
        assertTrue(onAbortFired, "onAbort hook fired")
    }

    @Test
    fun `a normal completion does not emit Abort`() = runTest {
        val agent = TestToolLoopAgent<Unit, String>(
            model = textModel("hi"),
            instructions = "x",
            tools = ToolSet(),
        )
        var onAbortFired = false
        val events = drainAllItems(
            agent.stream(prompt = "go", hooks = AgentCallHooks(onAbort = { onAbortFired = true })),
        )
        assertTrue(events.none { it is StreamEvent.Abort }, "no Abort on normal completion")
        assertTrue(events.any { it is StreamEvent.Finish }, "normal completion finishes")
        assertEquals(false, onAbortFired, "onAbort not fired on normal completion")
    }
}
