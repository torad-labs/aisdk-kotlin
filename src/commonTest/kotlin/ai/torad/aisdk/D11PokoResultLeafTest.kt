package ai.torad.aisdk

import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class D11PokoResultLeafTest {
    @Test
    fun `D11 generate result types keep value semantics`() {
        assertValueSemantics(
            GenerateResult(
                rawOutput = "output",
                text = "done",
                steps = emptyList(),
                finishReason = FinishReason.Stop,
                usage = Usage.of(promptTokens = 1, completionTokens = 2),
            ),
            GenerateResult(
                rawOutput = "output",
                text = "done",
                steps = emptyList(),
                finishReason = FinishReason.Stop,
                usage = Usage.of(promptTokens = 1, completionTokens = 2),
            ),
            GenerateResult(
                rawOutput = "different",
                text = "done",
                steps = emptyList(),
                finishReason = FinishReason.Stop,
                usage = Usage.of(promptTokens = 1, completionTokens = 2),
            ),
        )

        val toolCall = ContentPart.ToolCall(
            toolCallId = "call_1",
            toolName = "lookup",
            input = JsonObject(emptyMap()),
        )
        assertValueSemantics(
            GenerateTextResult(
                output = "answer",
                text = "answer",
                toolCalls = listOf(toolCall),
                finishReason = FinishReason.ToolCalls,
                usage = Usage.of(promptTokens = 3, completionTokens = 4),
            ),
            GenerateTextResult(
                output = "answer",
                text = "answer",
                toolCalls = listOf(toolCall),
                finishReason = FinishReason.ToolCalls,
                usage = Usage.of(promptTokens = 3, completionTokens = 4),
            ),
            GenerateTextResult(
                output = "answer",
                text = "different",
                toolCalls = listOf(toolCall),
                finishReason = FinishReason.ToolCalls,
                usage = Usage.of(promptTokens = 3, completionTokens = 4),
            ),
        )

        assertValueSemantics(
            GenerateObjectResult(
                value = mapOf("answer" to "yes"),
                text = """{"answer":"yes"}""",
                finishReason = FinishReason.Stop,
                usage = Usage.of(promptTokens = 5, completionTokens = 6),
            ),
            GenerateObjectResult(
                value = mapOf("answer" to "yes"),
                text = """{"answer":"yes"}""",
                finishReason = FinishReason.Stop,
                usage = Usage.of(promptTokens = 5, completionTokens = 6),
            ),
            GenerateObjectResult(
                value = mapOf("answer" to "no"),
                text = """{"answer":"no"}""",
                finishReason = FinishReason.Stop,
                usage = Usage.of(promptTokens = 5, completionTokens = 6),
            ),
        )
    }

    @Test
    fun `D11 loop and phase leaves keep value semantics`() {
        val toolCall = ContentPart.ToolCall(
            toolCallId = "call_1",
            toolName = "lookup",
            input = JsonObject(emptyMap()),
        )
        assertValueSemantics(
            LoopState(
                stepNumber = 1,
                totalSteps = 2,
                lastFinishReason = FinishReason.ToolCalls,
                toolCallsThisStep = listOf(toolCall),
                toolCallsAllSteps = listOf(toolCall),
            ),
            LoopState(
                stepNumber = 1,
                totalSteps = 2,
                lastFinishReason = FinishReason.ToolCalls,
                toolCallsThisStep = listOf(toolCall),
                toolCallsAllSteps = listOf(toolCall),
            ),
            LoopState(
                stepNumber = 2,
                totalSteps = 2,
                lastFinishReason = FinishReason.ToolCalls,
                toolCallsThisStep = listOf(toolCall),
                toolCallsAllSteps = listOf(toolCall),
            ),
        )

        assertValueSemantics(
            CompletionPhase.Streaming("hel"),
            CompletionPhase.Streaming("hel"),
            CompletionPhase.Streaming("hello"),
        )
        assertValueSemantics(
            CompletionPhase.Done("hello"),
            CompletionPhase.Done("hello"),
            CompletionPhase.Done("goodbye"),
        )

        val cause = IllegalStateException("boom")
        assertValueSemantics(
            CompletionPhase.Failed("partial", cause),
            CompletionPhase.Failed("partial", cause),
            CompletionPhase.Failed("different", cause),
        )
        assertValueSemantics(
            ToolLoopAgentState.Phase.Error("failed"),
            ToolLoopAgentState.Phase.Error("failed"),
            ToolLoopAgentState.Phase.Error("different"),
        )
    }

    private fun <T> assertValueSemantics(value: T, equal: T, different: T) {
        assertEquals(value, equal)
        assertEquals(value.hashCode(), equal.hashCode())
        assertNotEquals(value, different)
    }
}
