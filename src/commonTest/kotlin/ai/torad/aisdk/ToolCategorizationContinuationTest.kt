@file:OptIn(LowLevelLanguageModelApi::class)

package ai.torad.aisdk

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression: when EVERY tool call in a step fails categorization (NoSuchTool, undecodable args,
 * repair failure), the failed calls were written as tool-error results but NOT counted as local
 * tool work — so the natural-termination check ended the loop even though fresh tool-error results
 * were waiting for the model to react. A tool that DECODES then THROWS got retries; a malformed
 * call got zero. One malformed tool call could silently end the conversation.
 */
class ToolCategorizationContinuationTest {
    // Step 1 calls an unknown tool ("ghost" -> NoSuchTool, a categorization failure); step 2,
    // reached only if the loop continues, returns recovery text.
    private class GhostToolThenText : LanguageModel {
        override val modelId = "m"
        private var calls = 0
        override suspend fun generate(params: LanguageModelCallParams) =
            if (calls++ == 0) {
                val call = ContentPart.ToolCall("c1", "ghost", JsonObject(emptyMap()))
                LanguageModelResult(
                    text = "",
                    toolCalls = listOf(call),
                    finishReason = FinishReason.ToolCalls,
                    usage = Usage(),
                )
            } else {
                LanguageModelResult(text = "recovered", finishReason = FinishReason.Stop, usage = Usage())
            }
        override fun stream(params: LanguageModelCallParams): Flow<StreamEvent> = flow {
            if (calls++ == 0) {
                emit(StreamEvent.ToolCall("c1", "ghost", JsonObject(emptyMap())))
                emit(StreamEvent.StepFinish(1, FinishReason.ToolCalls, Usage()))
                emit(StreamEvent.Finish(1, FinishReason.ToolCalls, Usage()))
            } else {
                emit(StreamEvent.TextStart("t"))
                emit(StreamEvent.TextDelta("t", "recovered"))
                emit(StreamEvent.TextEnd("t"))
                emit(StreamEvent.Finish(2, FinishReason.Stop, Usage()))
            }
        }
    }

    @Test
    fun `a step whose only tool call fails categorization still continues the loop`() = runTest {
        val agent = TestToolLoopAgent<Unit, String>(
            model = GhostToolThenText(),
            instructions = "x",
            tools = ToolSet(), // "ghost" is unknown -> NoSuchTool -> categorization failure for every call
        )
        val result = agent.generate(prompt = "go").first()

        // The malformed call produced a tool-error result, so the model must be re-invoked to react
        // to it — the loop continues to a second step instead of silently ending.
        assertEquals(2, result.steps.size, "loop continues to a second step after the failed call")
        assertEquals("recovered", result.text)
        // And that tool-error result is flagged in the log (bug-2 invariant holds here too).
        val err = result.messages
            .flatMap { it.content }
            .filterIsInstance<ContentPart.ToolResult>()
            .single { it.toolCallId == "c1" }
        assertTrue(err.isError, "the categorization failure is recorded as an error result")
    }
}
