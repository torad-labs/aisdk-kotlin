package ai.torad.aisdk

import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class ResultSurfaceParityTest {
    private fun call(id: String, dynamic: Boolean) =
        ContentPart.ToolCall(id, id, JsonObject(emptyMap()), dynamic = dynamic)

    private fun result(id: String, dynamic: Boolean) =
        ContentPart.ToolResult(id, id, JsonObject(emptyMap()), dynamic = dynamic)

    @Test
    fun `GenerateTextResult splits tool calls and results by static vs dynamic`() {
        val content = listOf(
            ContentPart.Text("hi"),
            call("a", dynamic = false),
            call("b", dynamic = true),
            result("a", dynamic = false),
            result("b", dynamic = true),
        )
        val r = GenerateTextResult(
            output = Unit,
            text = "hi",
            toolCalls = content.filterIsInstance<ContentPart.ToolCall>(),
            finishReason = FinishReason.ToolCalls,
            usage = Usage(),
            content = content,
        )
        assertEquals(listOf("a"), r.staticToolCalls.map { it.toolCallId })
        assertEquals(listOf("b"), r.dynamicToolCalls.map { it.toolCallId })
        // toolResults derived from content
        assertEquals(listOf("a", "b"), r.toolResults.map { it.toolCallId })
        assertEquals(listOf("a"), r.staticToolResults.map { it.toolCallId })
        assertEquals(listOf("b"), r.dynamicToolResults.map { it.toolCallId })
    }

    @Test
    fun `StepResult exposes reasoningText and static-dynamic splits`() {
        val step = StepResult(
            stepNumber = 1,
            text = "t",
            reasoning = "because",
            toolCalls = listOf(call("a", dynamic = false), call("b", dynamic = true)),
            toolResults = listOf(result("b", dynamic = true)),
            toolApprovalRequests = emptyList(),
            finishReason = FinishReason.ToolCalls,
            usage = Usage(),
        )
        assertEquals("because", step.reasoningText)
        assertEquals(listOf("a"), step.staticToolCalls.map { it.toolCallId })
        assertEquals(listOf("b"), step.dynamicToolCalls.map { it.toolCallId })
        assertEquals(listOf("b"), step.dynamicToolResults.map { it.toolCallId })
        assertEquals(emptyList(), step.staticToolResults.map { it.toolCallId })

        val empty = StepResult(
            stepNumber = step.stepNumber,
            text = step.text,
            reasoning = "",
            toolCalls = step.toolCalls,
            toolResults = step.toolResults,
            toolApprovalRequests = step.toolApprovalRequests,
            finishReason = step.finishReason,
            usage = step.usage,
            warnings = step.warnings,
            request = step.request,
            response = step.response,
            providerMetadata = step.providerMetadata,
            rawFinishReason = step.rawFinishReason,
            model = step.model,
            experimentalContext = step.experimentalContext,
        )
        assertEquals(null, empty.reasoningText)
    }
}
