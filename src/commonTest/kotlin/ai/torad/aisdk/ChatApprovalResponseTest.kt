package ai.torad.aisdk

import ai.torad.aisdk.ui.Chat
import ai.torad.aisdk.ui.DirectChatTransport
import ai.torad.aisdk.ui.UIMessage
import ai.torad.aisdk.ui.UIMessagePart
import ai.torad.aisdk.ui.UIMessageRole
import kotlinx.coroutines.flow.emptyFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.jsonPrimitive

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
                UIMessage(id = "tool_approval_50", role = UIMessageRole.User, parts = listOf(UIMessagePart.Text("old"))),
            ),
            transport = DirectChatTransport { emptyFlow() },
        )
        chat.setMessages(
            listOf(
                UIMessage(id = "tool_approval_3", role = UIMessageRole.User, parts = listOf(UIMessagePart.Text("saved"))),
            ),
        )

        chat.addToolApprovalResponse(toolCallId = "call_1", approved = true, approvalId = "approval_1")

        val response = chat.messages.last()
        val part = response.parts.single() as UIMessagePart.ToolUI
        assertEquals("tool_approval_4", response.id)
        assertEquals("approval_1", part.output?.jsonPrimitive?.content)
    }
}
