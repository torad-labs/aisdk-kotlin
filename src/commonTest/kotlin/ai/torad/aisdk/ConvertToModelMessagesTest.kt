package ai.torad.aisdk

import ai.torad.aisdk.ui.ModelMessageConversion.convertToModelMessages
import ai.torad.aisdk.ui.ToolCallState
import ai.torad.aisdk.ui.UIMessage
import ai.torad.aisdk.ui.UIMessagePart
import ai.torad.aisdk.ui.UIMessageRole
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Validates `convertToModelMessages` (gap #5 from
 * historical parity work). The converter is the inverse of
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
    fun `given a real tool named approval when converted then it remains a tool result not an approval response`() {
        val ui = listOf(
            UIMessage(
                id = "u1",
                role = UIMessageRole.User,
                parts = listOf(
                    UIMessagePart.ToolUI(
                        toolCallId = "call_approval",
                        toolName = "approval",
                        state = ToolCallState.OutputAvailable,
                        input = JsonPrimitive("normal input"),
                        output = JsonPrimitive("normal output"),
                    ),
                ),
            ),
        )

        val result = convertToModelMessages(ui)

        assertEquals(2, result.size)
        assertEquals(MessageRole.User, result[0].role)
        val toolCall = result[0].content.single() as ContentPart.ToolCall
        assertEquals("approval", toolCall.toolName)
        assertEquals(JsonPrimitive("normal input"), toolCall.input)
        assertEquals(MessageRole.Tool, result[1].role)
        val toolResult = result[1].content.single() as ContentPart.ToolResult
        assertEquals("approval", toolResult.toolName)
        assertEquals(JsonPrimitive("normal output"), toolResult.output)
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
    fun `given approval response UI messages when converted then they become tool approval responses`() {
        val approved = UIMessage(
            id = "approval_1",
            role = UIMessageRole.User,
            parts = listOf(
                UIMessagePart.ToolUI(
                    toolCallId = "call_1",
                    toolName = "approval",
                    state = ToolCallState.OutputAvailable,
                    approvalId = "call_1",
                ),
            ),
        )
        val denied = UIMessage(
            id = "approval_2",
            role = UIMessageRole.User,
            parts = listOf(
                UIMessagePart.ToolUI(
                    toolCallId = "call_2",
                    toolName = "approval",
                    state = ToolCallState.OutputDenied,
                    error = "not allowed",
                    approvalId = "call_2",
                ),
            ),
        )

        val result = convertToModelMessages(listOf(approved, denied))

        assertEquals(2, result.size)
        assertEquals(MessageRole.Tool, result[0].role)
        val approvedResponse = result[0].content.single() as ContentPart.ToolApprovalResponse
        assertEquals("call_1", approvedResponse.toolCallId)
        assertEquals(true, approvedResponse.approved)
        assertEquals("call_1", approvedResponse.approvalId)
        assertEquals(MessageRole.Tool, result[1].role)
        val deniedResponse = result[1].content.single() as ContentPart.ToolApprovalResponse
        assertEquals("call_2", deniedResponse.toolCallId)
        assertEquals(false, deniedResponse.approved)
        assertEquals("not allowed", deniedResponse.reason)
        assertEquals("call_2", deniedResponse.approvalId)
    }

    @Test
    fun `given approval marker when converted then approval id comes from the marker not the tool output`() {
        val malformed = UIMessage(
            id = "approval_bad",
            role = UIMessageRole.User,
            parts = listOf(
                UIMessagePart.ToolUI(
                    toolCallId = "call_1",
                    toolName = "approval",
                    state = ToolCallState.OutputAvailable,
                    output = buildJsonObject { put("bad", JsonPrimitive(true)) },
                    approvalId = "approval_1",
                ),
            ),
        )

        val response = convertToModelMessages(listOf(malformed)).single().content.single()
            as ContentPart.ToolApprovalResponse

        assertEquals("approval_1", response.approvalId)
    }

    @Test
    fun `given UI-only part types when converted then StepStart Data and Error drop but Source carries`() {
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
        // StepStart + Error are UI-only and drop; Source now carries to the model.
        assertEquals(2, result[0].content.size, "Source + Text survive; StepStart/Error drop")
        assertTrue(result[0].content.any { it is ContentPart.Text })
        assertTrue(result[0].content.any { it is ContentPart.Source })
    }

    @Test
    fun `given a user File part when converted then it carries to the model as a File content part`() {
        val ui = listOf(
            UIMessage(
                id = "u1",
                role = UIMessageRole.User,
                parts = listOf(
                    UIMessagePart.Text("see attached"),
                    UIMessagePart.File(mediaType = "image/png", base64 = "aW1n"),
                ),
            ),
        )
        val result = convertToModelMessages(ui)
        val file = result.single().content.filterIsInstance<ContentPart.File>().single()
        assertEquals("image/png", file.mediaType)
        assertEquals("aW1n", file.base64)
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
