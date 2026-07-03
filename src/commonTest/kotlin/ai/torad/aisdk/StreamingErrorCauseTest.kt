@file:OptIn(LowLevelLanguageModelApi::class)

package ai.torad.aisdk

import ai.torad.aisdk.AgentSessions.session
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class StreamingErrorCauseTest {
    private class ErrorStreamModel : LanguageModel {
        override val modelId = "m"
        override suspend fun generate(params: LanguageModelCallParams) =
            LanguageModelResult(text = "", finishReason = FinishReason.Error, usage = Usage())
        override fun stream(params: LanguageModelCallParams): Flow<StreamEvent> = flow {
            emit(StreamEvent.Error("boom", cause = IllegalStateException("root cause")))
        }
    }

    /**
     * Regression: the streaming session mapped a StreamEvent.Error to UiMessageStreamError(message)
     * and discarded event.cause — so a host inspecting state.error after a streamed turn lost the
     * chained root exception that the non-streaming generate() path preserves.
     */
    @Test
    fun `a streamed error preserves its underlying cause in session state`() = runTest {
        val agent = TestToolLoopAgent<Unit, String>(
            model = ErrorStreamModel(),
            instructions = "x",
            tools = ToolSet(),
        )
        val session = agent.session(this)

        session.submitStreaming(prompt = "go").join()

        assertEquals(AgentSessionStatus.Error, session.state.value.status)
        val error = assertIs<UiMessageStreamError>(session.state.value.error)
        assertEquals("root cause", error.cause?.message, "the StreamEvent.Error cause is chained, not dropped")
    }
}
