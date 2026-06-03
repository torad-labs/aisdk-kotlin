package ai.torad.aisdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.serializer

class ToolLoopAgentUsageAggregationTest {

    @Serializable
    data class EmptyInput(val value: String = "")

    @Serializable
    data class ToolOutput(val ok: Boolean)

    @Test
    fun `multi-step agent aggregation preserves rich usage fields`() = runTest {
        val firstUsage = Usage(
            inputTokens = Usage.InputTokenBreakdown(
                total = 10,
                noCache = 4,
                cacheRead = 5,
                cacheWrite = 1,
            ),
            outputTokens = Usage.OutputTokenBreakdown(total = 7, text = 3, reasoning = 4),
            raw = buildJsonObject { put("step", JsonPrimitive("first")) },
        )
        val secondUsage = Usage(
            inputTokens = Usage.InputTokenBreakdown(
                total = 20,
                noCache = 8,
                cacheRead = 9,
                cacheWrite = 3,
            ),
            outputTokens = Usage.OutputTokenBreakdown(total = 11, text = 6, reasoning = 5),
            raw = buildJsonObject { put("step", JsonPrimitive("second")) },
        )
        val model = CountingStreamModel(
            responses = listOf(
                TestStreamResponse(
                    events = listOf(
                        StreamEvent.ToolCall(
                            toolCallId = "call_1",
                            toolName = "done",
                            inputJson = buildJsonObject { put("value", JsonPrimitive("")) },
                        ),
                    ),
                    finishReason = FinishReason.ToolCalls,
                    usage = firstUsage,
                ),
                TestStreamResponse(
                    events = listOf(
                        StreamEvent.TextStart("t1"),
                        StreamEvent.TextDelta("t1", "complete"),
                        StreamEvent.TextEnd("t1"),
                    ),
                    finishReason = FinishReason.Stop,
                    usage = secondUsage,
                ),
            ),
        )
        val doneTool = tool<EmptyInput, ToolOutput, Unit>(
            name = "done",
            description = "complete",
            inputSerializer = serializer(),
            outputSerializer = serializer(),
        ) {
            ToolOutput(ok = true)
        }
        val agent = ToolLoopAgent<Unit, String>(
            model = model,
            instructions = "use tool",
            tools = toolSetOf(doneTool),
        )

        val result = agent.generate(prompt = "run", options = Unit)

        assertEquals(30, result.usage.inputTokens.total)
        assertEquals(12, result.usage.inputTokens.noCache)
        assertEquals(14, result.usage.inputTokens.cacheRead)
        assertEquals(4, result.usage.inputTokens.cacheWrite)
        assertEquals(18, result.usage.outputTokens.total)
        assertEquals(9, result.usage.outputTokens.text)
        assertEquals(9, result.usage.outputTokens.reasoning)
        assertEquals(secondUsage.raw, result.usage.raw)
    }
}
