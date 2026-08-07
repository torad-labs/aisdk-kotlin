package ai.torad.aisdk

import ai.torad.aisdk.ui.Chat
import ai.torad.aisdk.ui.DirectChatTransport
import ai.torad.aisdk.ui.ToolCallState
import ai.torad.aisdk.ui.UIMessage
import ai.torad.aisdk.ui.UIMessagePart
import ai.torad.aisdk.ui.UIMessageRole
import ai.torad.aisdk.ui.UiMessageStreams
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatApprovalResponseTest {
    @Test
    fun `approval response ids do not collide with existing messages`() {
        val chat = Chat(
            initialMessages = listOf(
                UIMessage(
                    id = "tool_2",
                    role = UIMessageRole.User,
                    parts = listOf(UIMessagePart.Text("existing")),
                ),
            ),
            transport = DirectChatTransport { emptyFlow() },
        )

        chat.addToolApprovalResponse(toolCallId = "call_1", approved = true)

        assertEquals(2, chat.messages.map { it.id }.toSet().size)
    }

    @Test
    fun `setMessages resets approval response id cursor from rehydrated messages`() {
        val chat = Chat(
            initialMessages = listOf(
                UIMessage(
                    id = "tool_approval_50",
                    role = UIMessageRole.User,
                    parts = listOf(UIMessagePart.Text("old"))
                ),
            ),
            transport = DirectChatTransport { emptyFlow() },
        )
        chat.setMessages(
            listOf(
                UIMessage(
                    id = "tool_approval_3",
                    role = UIMessageRole.User,
                    parts = listOf(UIMessagePart.Text("saved"))
                ),
            ),
        )

        chat.addToolApprovalResponse(toolCallId = "call_1", approved = true, approvalId = "approval_1")

        val response = chat.messages.last()
        val part = response.parts.single() as UIMessagePart.ToolUI
        assertEquals("tool_approval_4", response.id)
        assertEquals("approval_1", part.output?.jsonPrimitive?.content)
    }

    @Test
    fun `approval response opens the approval-completion gate`() {
        val chat = chatWithPendingToolPart(ToolCallState.ApprovalRequested, approvalId = "approval_1")

        assertFalse(UiMessageStreams.lastAssistantMessageIsCompleteWithApprovalResponses(chat.messages))

        chat.addToolApprovalResponse(toolCallId = "call_1", approved = true, approvalId = "approval_1")

        assertTrue(UiMessageStreams.lastAssistantMessageIsCompleteWithApprovalResponses(chat.messages))
    }

    @Test
    fun `tool output opens the tool-call-completion gate`() {
        val chat = chatWithPendingToolPart(ToolCallState.InputAvailable, approvalId = null)

        assertFalse(UiMessageStreams.lastAssistantMessageIsCompleteWithToolCalls(chat.messages))

        chat.addToolOutput(toolCallId = "call_1", output = JsonPrimitive("done"), toolName = "getWeather")

        assertTrue(UiMessageStreams.lastAssistantMessageIsCompleteWithToolCalls(chat.messages))
    }

    private fun chatWithPendingToolPart(state: ToolCallState, approvalId: String?): Chat =
        Chat(
            initialMessages = listOf(
                UIMessage(
                    id = "a1",
                    role = UIMessageRole.Assistant,
                    parts = listOf(
                        UIMessagePart.ToolUI(
                            toolCallId = "call_1",
                            toolName = "getWeather",
                            state = state,
                            input = JsonPrimitive("Paris"),
                            approvalId = approvalId,
                        ),
                    ),
                ),
            ),
            transport = DirectChatTransport { emptyFlow() },
        )
}
