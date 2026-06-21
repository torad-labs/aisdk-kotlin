package ai.torad.aisdk

import ai.torad.aisdk.testing.FlowDrain.drainAllItems
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest

class ToolLoopAgentStreamErrorTest {

    @Test
    fun `provider stream error is terminal and not followed by success events`() = runTest {
        val model = object : LanguageModel {
            override val modelId: String = "test/error-stream"
            override val provider: String = "test"

            override suspend fun generate(params: LanguageModelCallParams): LanguageModelResult =
                error("stream only")

            override fun stream(params: LanguageModelCallParams): Flow<StreamEvent> = flow {
                emit(StreamEvent.TextStart("t1"))
                emit(StreamEvent.TextDelta("t1", "partial"))
                emit(StreamEvent.Error("provider failed"))
            }
        }
        val agent = TestToolLoopAgent<Unit, String>(
            model = model,
            instructions = "stream",
            tools = ToolSet(),
        )

        val events = drainAllItems(agent.stream(prompt = "run", options = Unit))

        assertTrue(events.any { it is StreamEvent.Error })
        assertFalse(events.any { it is StreamEvent.StepFinish }, "error must not be followed by StepFinish")
        assertFalse(events.any { it is StreamEvent.Finish }, "error must not be followed by Finish")
    }
}
