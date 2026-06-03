package ai.torad.aisdk

import ai.torad.aisdk.ui.ToolCallState
import ai.torad.aisdk.ui.UIMessage
import ai.torad.aisdk.ui.UIMessagePart
import ai.torad.aisdk.ui.UIMessageRole
import ai.torad.aisdk.ui.convertToModelMessages
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive

/**
 * Validates `convertToModelMessages` (gap #5 from
 * `docs/AISDK_PORT_GAPS.md`). The converter is the inverse of
 * `streamToUiMessages` — UI-shape history back to model-shape
 * history, so a persisted chat can resume the agent.
 */
class ConvertToModelMessagesTest {

    @Test
    fun `given an empty list when converted then result is empty`() {
        assertEquals(emptyList(), convertToModelMessages(emptyList()))
    }

    @Test
    fun `given a user text message when converted then it becomes ModelMessage of User role`() {
        val ui = listOf(
            UIMessage(
                id = "u1",
                role = UIMessageRole.User,
                parts = listOf(UIMessagePart.Text("hello agent")),
            ),
        )
        val result = convertToModelMessages(ui)
        assertEquals(1, result.size)
        assertEquals(MessageRole.User, result[0].role)
        assertEquals(1, result[0].content.size)
        val text = result[0].content[0] as ContentPart.Text
        assertEquals("hello agent", text.text)
    }

    @Test
    fun `given an assistant text message when converted then it becomes ModelMessage of Assistant role`() {
        val ui = listOf(
            UIMessage(
                id = "a1",
                role = UIMessageRole.Assistant,
                parts = listOf(UIMessagePart.Text("answer")),
            ),
        )
        val result = convertToModelMessages(ui)
        assertEquals(1, result.size)
        assertEquals(MessageRole.Assistant, result[0].role)
    }

    @Test
    fun `given an assistant with a completed tool call when converted then ToolCall is in-message and ToolResult follows`() {
        val ui = listOf(
            UIMessage(
                id = "a1",
                role = UIMessageRole.Assistant,
                parts = listOf(
                    UIMessagePart.Text("looking up"),
                    UIMessagePart.ToolUI(
                        toolCallId = "call_1",
                        toolName = "weather",
                        state = ToolCallState.OutputAvailable,
                        input = JsonPrimitive("Paris"),
                        output = JsonPrimitive("sunny"),
                    ),
                ),
            ),
        )
        val result = convertToModelMessages(ui)
        assertEquals(2, result.size, "assistant message + tool message")
        // Assistant carries text + ToolCall.
        assertEquals(MessageRole.Assistant, result[0].role)
        assertEquals(2, result[0].content.size)
        assertTrue(result[0].content[0] is ContentPart.Text)
        val toolCall = result[0].content[1] as ContentPart.ToolCall
        assertEquals("call_1", toolCall.toolCallId)
        assertEquals("weather", toolCall.toolName)
        // Follow-up tool message carries ToolResult.
        assertEquals(MessageRole.Tool, result[1].role)
        val toolResult = result[1].content[0] as ContentPart.ToolResult
        assertEquals("call_1", toolResult.toolCallId)
        assertEquals(JsonPrimitive("sunny"), toolResult.output)
    }

    @Test
    fun `given a preliminary tool result when converted then it is dropped only final results feed the model`() {
        val ui = listOf(
            UIMessage(
                id = "a1",
                role = UIMessageRole.Assistant,
                parts = listOf(
                    UIMessagePart.ToolUI(
                        toolCallId = "call_1",
                        toolName = "weather",
                        state = ToolCallState.OutputAvailable,
                        input = JsonPrimitive("Paris"),
                        output = JsonPrimitive("loading…"),
                        preliminary = true, // <- preliminary
                    ),
                ),
            ),
        )
        val result = convertToModelMessages(ui)
        assertEquals(0, result.size, "preliminary results are UI-only; nothing flows to model")
    }

    @Test
    fun `given an incomplete tool call with ignoreIncompleteToolCalls true when converted then the part is dropped`() {
        val ui = listOf(
            UIMessage(
                id = "a1",
                role = UIMessageRole.Assistant,
                parts = listOf(
                    UIMessagePart.Text("calling"),
                    UIMessagePart.ToolUI(
                        toolCallId = "call_1",
                        toolName = "weather",
                        state = ToolCallState.InputStreaming,
                        input = null,
                    ),
                ),
            ),
        )
        val result = convertToModelMessages(ui, ignoreIncompleteToolCalls = true)
        assertEquals(1, result.size, "incomplete tool dropped — only the text part survives")
        assertEquals(MessageRole.Assistant, result[0].role)
        assertEquals(1, result[0].content.size)
        assertTrue(result[0].content[0] is ContentPart.Text)
    }

    @Test
    fun `given an incomplete tool call with ignoreIncompleteToolCalls false when converted then it throws`() {
        val ui = listOf(
            UIMessage(
                id = "a1",
                role = UIMessageRole.Assistant,
                parts = listOf(
                    UIMessagePart.ToolUI(
                        toolCallId = "call_1",
                        toolName = "weather",
                        state = ToolCallState.InputStreaming,
                    ),
                ),
            ),
        )
        assertFailsWith<IllegalStateException> {
            convertToModelMessages(ui, ignoreIncompleteToolCalls = false)
        }
    }

    @Test
    fun `given an approval-required tool call when converted then it becomes a ToolApprovalRequest`() {
        val ui = listOf(
            UIMessage(
                id = "a1",
                role = UIMessageRole.Assistant,
                parts = listOf(
                    UIMessagePart.ToolUI(
                        toolCallId = "call_1",
                        toolName = "saveNote",
                        state = ToolCallState.ApprovalRequested,
                        input = JsonPrimitive("remember"),
                    ),
                ),
            ),
        )
        val result = convertToModelMessages(ui)
        assertEquals(1, result.size, "approval is in-message; no separate tool result yet")
        val approval = result[0].content[0] as ContentPart.ToolApprovalRequest
        assertEquals("call_1", approval.toolCallId)
        assertEquals("saveNote", approval.toolName)
    }

    @Test
    fun `given dropped part types when converted then they vanish without affecting other parts`() {
        val ui = listOf(
            UIMessage(
                id = "a1",
                role = UIMessageRole.Assistant,
                parts = listOf(
                    UIMessagePart.StepStart(stepNumber = 2),
                    UIMessagePart.SourceUrl(
                        sourceId = "src_1",
                        url = "https://example.com",
                    ),
                    UIMessagePart.Error("something happened"),
                    UIMessagePart.Text("real content"),
                ),
            ),
        )
        val result = convertToModelMessages(ui)
        assertEquals(1, result.size)
        assertEquals(1, result[0].content.size, "only Text survives — StepStart/Source/Error drop")
        assertTrue(result[0].content[0] is ContentPart.Text)
    }

    @Test
    fun `given DynamicToolUI with output when converted then it produces ToolCall + Tool follow-up like ToolUI`() {
        val ui = listOf(
            UIMessage(
                id = "a1",
                role = UIMessageRole.Assistant,
                parts = listOf(
                    UIMessagePart.DynamicToolUI(
                        toolCallId = "call_d1",
                        toolName = "subagentTool",
                        state = ToolCallState.OutputAvailable,
                        input = JsonPrimitive("q"),
                        output = JsonPrimitive("a"),
                    ),
                ),
            ),
        )
        val result = convertToModelMessages(ui)
        assertEquals(2, result.size, "DynamicToolUI converts the same way as ToolUI")
        assertEquals(MessageRole.Assistant, result[0].role)
        assertEquals(MessageRole.Tool, result[1].role)
    }
}
