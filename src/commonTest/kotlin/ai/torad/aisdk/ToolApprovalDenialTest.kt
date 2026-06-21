package ai.torad.aisdk

import ai.torad.aisdk.providers.MockLanguageModelToolThenText
import ai.torad.aisdk.providers.MockToolInput
import ai.torad.aisdk.testing.FlowDrain.drainAllItems
import ai.torad.aisdk.ui.ToolCallState
import ai.torad.aisdk.ui.UIMessagePart
import ai.torad.aisdk.ui.StreamToUiMessages
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer

class ToolApprovalDenialTest {

    @Serializable
    data class SendInput(val message: String)

    @Serializable
    data class SendResult(val sent: Boolean)

    @Test
    fun `approval denial emits ToolOutputDenied instead of ToolError`() = runTest {
        var executed = false
        val sendTool = Tool<SendInput, SendResult, Unit>(
            name = "send",
            description = "send message",
            inputSerializer = serializer(),
            outputSerializer = serializer(),
            needsApproval = { _, _ -> true },
        ) {
            executed = true
            SendResult(sent = true)
        }
        val agent = TestToolLoopAgent<Unit, String>(
            model = MockLanguageModelToolThenText(
                toolName = "send",
                toolInput = MockToolInput("message" to "spam"),
                finalText = "skipped",
            ),
            instructions = "use send",
            tools = ToolSet(sendTool),
        )
        val first = agent.generate(prompt = "trigger", options = Unit).first()
        val pending = first.pendingApprovals.single()
        val denial = ToolApprovalResponseMessage(
            toolCallId = pending.toolCallId,
            approved = false,
            reason = "user said no",
            approvalId = "approval_send_1",
        )

        val events = drainAllItems(agent.stream(messages = first.messages + denial, options = Unit))
        val ui = drainAllItems(StreamToUiMessages(events.asFlow(), "assistant_1"))
            .last()
            .parts
            .filterIsInstance<UIMessagePart.ToolUI>()
            .single { it.toolCallId == pending.toolCallId }

        assertFalse(executed, "denied tool must not execute")
        assertTrue(events.any {
            it is StreamEvent.ToolOutputDenied &&
                it.toolCallId == pending.toolCallId &&
                it.approvalId == "approval_send_1" &&
                it.reason == "user said no"
        })
        assertFalse(events.any { it is StreamEvent.ToolError }, "denial is not a tool error")
        assertEquals(ToolCallState.OutputDenied, ui.state)
        assertEquals(true, events.filterIsInstance<StreamEvent.ToolResult>().single().isError)
    }
}
