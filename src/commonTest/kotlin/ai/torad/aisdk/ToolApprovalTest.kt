package ai.torad.aisdk

import ai.torad.aisdk.providers.MockLanguageModel
import ai.torad.aisdk.providers.mockLanguageModelToolThenText
import ai.torad.aisdk.providers.mockToolInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer

/**
 * Best practice #8 + v6 RPC semantics — tool with `needsApproval = true`
 * pauses the loop and the agent **returns** with `pendingApprovals`
 * populated and `messages` containing the assistant message with
 * [ContentPart.ToolApprovalRequest] parts. The host resumes by calling
 * [Agent.generate] again with `messages + toolApprovalResponseMessage(...)`.
 *
 * No `submitApproval` method — approval state lives in the message log
 * and can be serialized, persisted, replayed across process restarts.
 */
class ToolApprovalTest {

    @Serializable data class SendInput(val message: String)
    @Serializable data class SendResult(val sent: Boolean)

    @Test
    fun `needsApproval_returns_pending_then_resumes_on_approval_response`() = runTest {
        var executed = false
        val sendTool = tool<SendInput, SendResult, Unit>(
            name = "send",
            description = "send message",
            inputSerializer = serializer(),
            outputSerializer = serializer(),
            needsApproval = { _, _ -> true },
        ) { _ ->
            executed = true
            SendResult(sent = true)
        }

        val agent = ToolLoopAgent<Unit, String>(
            model = mockLanguageModelToolThenText(
                toolName = "send",
                toolInput = mockToolInput("message" to "hey friend"),
                finalText = "sent",
            ),
            instructions = "use send",
            tools = toolSetOf(sendTool),
        )

        val first = agent.generate(prompt = "trigger")
        assertEquals(FinishReason.ToolApprovalRequested, first.finishReason)
        assertEquals(1, first.pendingApprovals.size, "one pending approval")
        assertEquals("send", first.pendingApprovals[0].toolName)
        assertEquals(false, executed, "tool not executed before approval")

        val approval = toolApprovalResponseMessage(
            toolCallId = first.pendingApprovals[0].toolCallId,
            approved = true,
        )
        val resumed = agent.generate(messages = first.messages + approval)
        assertEquals(true, executed, "tool executed after approval")
        assertEquals("sent", resumed.text)
        assertEquals(FinishReason.Stop, resumed.finishReason)
        assertTrue(resumed.pendingApprovals.isEmpty())
    }

    @Test
    fun `needsApproval_denial_records_denial_in_message_log`() = runTest {
        var executed = false
        val sendTool = tool<SendInput, SendResult, Unit>(
            name = "send",
            description = "send message",
            inputSerializer = serializer(),
            outputSerializer = serializer(),
            needsApproval = { _, _ -> true },
        ) { _ ->
            executed = true
            SendResult(sent = true)
        }

        val agent = ToolLoopAgent<Unit, String>(
            model = mockLanguageModelToolThenText(
                toolName = "send",
                toolInput = mockToolInput("message" to "spam"),
                finalText = "ok skipped",
            ),
            instructions = "use send",
            tools = toolSetOf(sendTool),
        )

        val first = agent.generate(prompt = "trigger")
        assertEquals(1, first.pendingApprovals.size)

        val denial = toolApprovalResponseMessage(
            toolCallId = first.pendingApprovals[0].toolCallId,
            approved = false,
            reason = "user said no",
        )
        val resumed = agent.generate(messages = first.messages + denial)
        assertEquals(false, executed, "tool NOT executed after denial")
        assertEquals("ok skipped", resumed.text)
    }

    @Test
    fun `approval gate malformed input emits structured tool error`() = runTest {
        val sendTool = tool<SendInput, SendResult, Unit>(
            name = "send",
            description = "send message",
            inputSerializer = serializer(),
            outputSerializer = serializer(),
            needsApproval = { _, _ -> true },
        ) { _ ->
            SendResult(sent = true)
        }

        val agent = ToolLoopAgent<Unit, String>(
            model = mockLanguageModelToolThenText(
                toolName = "send",
                toolInput = JsonObject(emptyMap()),
                finalText = "sent",
            ),
            instructions = "use send",
            tools = toolSetOf(sendTool),
        )

        val events = agent.stream(prompt = "trigger").toList()

        val error = events.filterIsInstance<StreamEvent.ToolError>().single()
        assertEquals("send", error.toolName)
        assertIs<AgentError.InvalidToolInput>(error.error)
    }

    @Test
    fun `engine approval resume preserves original context for approved tool`() = runTest {
        val seenContexts = mutableListOf<String?>()
        val sendTool = tool<SendInput, SendResult, String>(
            name = "send",
            description = "send message",
            inputSerializer = serializer(),
            outputSerializer = serializer(),
            needsApproval = { _, _ -> true },
        ) { _ ->
            seenContexts += context
            SendResult(sent = true)
        }

        val agent = ToolLoopAgent<String, String>(
            model = mockLanguageModelToolThenText(
                toolName = "send",
                toolInput = mockToolInput("message" to "hey friend"),
                finalText = "sent",
            ),
            instructions = "use send",
            tools = toolSetOf(sendTool),
        )

        agent.dispatchEngineAction(ToolLoopAgentAction.UserSubmitPrompt("trigger", context = "request-context"))
        withContext(Dispatchers.Default) {
            withTimeout(1_000) {
                while (agent.engineState.value.pendingApprovals.isEmpty()) delay(10)
            }
        }

        val pending = agent.engineState.value.pendingApprovals.single()
        agent.dispatchEngineAction(ToolLoopAgentAction.ApproveToolCall(pending.toolCallId))
        withContext(Dispatchers.Default) {
            withTimeout(1_000) {
                while (seenContexts.isEmpty() || agent.engineState.value.isStreaming) delay(10)
            }
        }

        assertEquals(listOf<String?>("request-context"), seenContexts)
    }
}
