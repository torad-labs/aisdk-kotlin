package ai.torad.aisdk

import ai.torad.aisdk.providers.MockLanguageModel
import ai.torad.aisdk.providers.MockLanguageModelToolThenText
import ai.torad.aisdk.providers.MockToolInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

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

    @Serializable data class Receipt(val status: String)

    private fun duplicateApprovalAgent(executed: MutableList<String>) = TestToolLoopAgent<Unit, String>(
        model = MockLanguageModel(
            responses = listOf(
                ai.torad.aisdk.providers.ScriptedResponse(
                    events = listOf(
                        StreamEvent.ToolCall("dup", "send", buildJsonObject { put("message", "first") }),
                        StreamEvent.ToolCall("dup", "send", buildJsonObject { put("message", "second") }),
                    ),
                    finishReason = FinishReason.ToolCalls,
                ),
                ai.torad.aisdk.providers.ScriptedResponse(
                    events = listOf(
                        StreamEvent.TextStart("t1"),
                        StreamEvent.TextDelta("t1", "done"),
                        StreamEvent.TextEnd("t1"),
                    ),
                ),
            ),
        ),
        instructions = "use send",
        tools = ToolSet(
            Tool<SendInput, SendResult, Unit>(
                name = "send",
                description = "send message",
                inputSerializer = serializer(),
                outputSerializer = serializer(),
                needsApproval = { _, _ -> true },
            ) { input ->
                executed += input.message
                SendResult(sent = true)
            },
        ),
        experimental_toolApprovalSecret = "approval-secret".encodeToByteArray(),
    )

    @Test
    fun `needsApproval_returns_pending_then_resumes_on_approval_response`() = runTest {
        var executed = false
        val sendTool = Tool<SendInput, SendResult, Unit>(
            name = "send",
            description = "send message",
            inputSerializer = serializer(),
            outputSerializer = serializer(),
            needsApproval = { _, _ -> true },
        ) { _ ->
            executed = true
            SendResult(sent = true)
        }

        val agent = TestToolLoopAgent<Unit, String>(
            model = MockLanguageModelToolThenText(
                toolName = "send",
                toolInput = MockToolInput("message" to "hey friend"),
                finalText = "sent",
            ),
            instructions = "use send",
            tools = ToolSet(sendTool),
        )

        val first = agent.generate(prompt = "trigger").first()
        assertEquals(FinishReason.ToolApprovalRequested, first.finishReason)
        assertEquals(1, first.pendingApprovals.size, "one pending approval")
        assertEquals("send", first.pendingApprovals[0].toolName)
        assertEquals(false, executed, "tool not executed before approval")

        val approval = ToolApprovalResponseMessage(
            toolCallId = first.pendingApprovals[0].toolCallId,
            approved = true,
        )
        val resumed = agent.generate(messages = first.messages + approval).first()
        assertEquals(true, executed, "tool executed after approval")
        assertEquals("sent", resumed.text)
        assertEquals(FinishReason.Stop, resumed.finishReason)
        assertTrue(resumed.pendingApprovals.isEmpty())
    }

    @Test
    fun `needsApproval_denial_records_denial_in_message_log`() = runTest {
        var executed = false
        val sendTool = Tool<SendInput, SendResult, Unit>(
            name = "send",
            description = "send message",
            inputSerializer = serializer(),
            outputSerializer = serializer(),
            needsApproval = { _, _ -> true },
        ) { _ ->
            executed = true
            SendResult(sent = true)
        }

        val agent = TestToolLoopAgent<Unit, String>(
            model = MockLanguageModelToolThenText(
                toolName = "send",
                toolInput = MockToolInput("message" to "spam"),
                finalText = "ok skipped",
            ),
            instructions = "use send",
            tools = ToolSet(sendTool),
        )

        val first = agent.generate(prompt = "trigger").first()
        assertEquals(1, first.pendingApprovals.size)

        val denial = ToolApprovalResponseMessage(
            toolCallId = first.pendingApprovals[0].toolCallId,
            approved = false,
            reason = "user said no",
        )
        val resumed = agent.generate(messages = first.messages + denial).first()
        assertEquals(false, executed, "tool NOT executed after denial")
        assertEquals("ok skipped", resumed.text)
    }

    @Test
    fun `approval gate malformed input emits structured tool error`() = runTest {
        val sendTool = Tool<SendInput, SendResult, Unit>(
            name = "send",
            description = "send message",
            inputSerializer = serializer(),
            outputSerializer = serializer(),
            needsApproval = { _, _ -> true },
        ) { _ ->
            SendResult(sent = true)
        }

        val agent = TestToolLoopAgent<Unit, String>(
            model = MockLanguageModelToolThenText(
                toolName = "send",
                toolInput = JsonObject(emptyMap()),
                finalText = "sent",
            ),
            instructions = "use send",
            tools = ToolSet(sendTool),
        )

        val events = agent.stream(prompt = "trigger").toList()

        val error = events.filterIsInstance<StreamEvent.ToolError>().single()
        assertEquals("send", error.toolName)
        assertIs<AgentError.InvalidToolInput>(error.error)
    }

    @Test
    fun `engine approval resume preserves original context for approved tool`() = runTest {
        val seenContexts = mutableListOf<String?>()
        val sendTool = Tool<SendInput, SendResult, String>(
            name = "send",
            description = "send message",
            inputSerializer = serializer(),
            outputSerializer = serializer(),
            needsApproval = { _, _ -> true },
        ) { _ ->
            seenContexts += context
            SendResult(sent = true)
        }

        val agent = TestToolLoopAgent<String, String>(
            model = MockLanguageModelToolThenText(
                toolName = "send",
                toolInput = MockToolInput("message" to "hey friend"),
                finalText = "sent",
            ),
            instructions = "use send",
            tools = ToolSet(sendTool),
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
                while (seenContexts.isEmpty() || agent.engineState.value.phase == ToolLoopAgentState.Phase.Streaming) delay(
                    10
                )
            }
        }

        assertEquals(listOf<String?>("request-context"), seenContexts)
    }

    @Test
    fun `engine approval resume uses the latest prepareStep experimental context`() = runTest {
        val seenContexts = mutableListOf<String?>()
        val sendTool = Tool<SendInput, SendResult, String>(
            name = "send",
            description = "send message",
            inputSerializer = serializer(),
            outputSerializer = serializer(),
            needsApproval = { _, _ -> true },
        ) { _ ->
            seenContexts += context
            SendResult(sent = true)
        }

        val agent = TestToolLoopAgent<String, String>(
            model = MockLanguageModelToolThenText(
                toolName = "send",
                toolInput = MockToolInput("message" to "hey friend"),
                finalText = "sent",
            ),
            instructions = "use send",
            tools = ToolSet(sendTool),
            prepareStep = {
                StepSettings {
                    experimental_context("step-context")
                }
            },
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
                while (seenContexts.isEmpty() || agent.engineState.value.phase == ToolLoopAgentState.Phase.Streaming) delay(
                    10
                )
            }
        }

        assertEquals(listOf<String?>("step-context"), seenContexts)
    }

    @Test
    fun `engine approval resume preserves approval ids for duplicate signed tool calls`() = runTest {
        val executed = mutableListOf<String>()
        val agent = duplicateApprovalAgent(executed)

        agent.dispatchEngineAction(ToolLoopAgentAction.UserSubmitPrompt("trigger"))
        withContext(Dispatchers.Default) {
            withTimeout(1_000) {
                while (agent.engineState.value.pendingApprovals.size < 2) delay(10)
            }
        }

        agent.dispatchEngineAction(ToolLoopAgentAction.ApproveToolCall("dup"))
        withContext(Dispatchers.Default) {
            withTimeout(1_000) {
                while (
                    executed.size < 2 ||
                    agent.engineState.value.phase == ToolLoopAgentState.Phase.Streaming
                ) {
                    delay(10)
                }
            }
        }

        assertEquals(listOf("first", "second"), executed)
        val responses = agent.engineState.value.messages
            .flatMap { it.content }
            .filterIsInstance<ContentPart.ToolApprovalResponse>()
        assertEquals(2, responses.size)
        assertTrue(responses.all { it.approvalId != null })
    }

    @Test
    fun `approval paused structured generate keeps pending approvals and blocks output access`() =
        runTest {
            val sendTool = Tool<SendInput, SendResult, Unit>(
                name = "send",
                description = "send message",
                inputSerializer = serializer(),
                outputSerializer = serializer(),
                needsApproval = { _, _ -> true },
            ) { _ ->
                SendResult(sent = true)
            }

            val agent = TestToolLoopAgent<Unit, Receipt>(
                model = MockLanguageModelToolThenText(
                    toolName = "send",
                    toolInput = MockToolInput("message" to "hey friend"),
                    finalText = """{"status":"sent"}""",
                ),
                instructions = "use send",
                tools = ToolSet(sendTool),
                output = Output.obj(serializer<Receipt>()),
            )

            val first = agent.generate(prompt = "trigger").first()

            assertEquals(FinishReason.ToolApprovalRequested, first.finishReason)
            assertEquals(1, first.pendingApprovals.size)
            assertFailsWith<NoOutputGeneratedError> { first.output }
        }

    @Test
    fun `engine cancel aborts the live tool signal`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val aborted = CompletableDeferred<Boolean>()
        val sendTool = Tool<SendInput, SendResult, Unit>(
            name = "send",
            description = "send message",
            inputSerializer = serializer(),
            outputSerializer = serializer(),
        ) { _ ->
            entered.complete(Unit)
            try {
                CompletableDeferred<Unit>().await()
                SendResult(sent = true)
            } catch (cancelled: CancellationException) {
                aborted.complete(abortSignal.isAborted)
                throw cancelled
            } finally {
                if (!aborted.isCompleted) aborted.complete(abortSignal.isAborted)
            }
        }

        val agent = TestToolLoopAgent<Unit, String>(
            model = MockLanguageModelToolThenText(
                toolName = "send",
                toolInput = MockToolInput("message" to "hey friend"),
                finalText = "sent",
            ),
            instructions = "use send",
            tools = ToolSet(sendTool),
        )

        agent.dispatchEngineAction(ToolLoopAgentAction.UserSubmitPrompt("trigger", context = Unit))
        entered.await()
        agent.dispatchEngineAction(ToolLoopAgentAction.Cancel)

        withContext(Dispatchers.Default) {
            withTimeout(1_000) {
                assertTrue(aborted.await(), "engine cancel must flip the tool abort signal")
            }
        }
    }

    @Test
    fun `engine approval resume with an unknown id preserves the real pending approvals`() = runTest {
        val sendTool = Tool<SendInput, SendResult, Unit>(
            name = "send",
            description = "send message",
            inputSerializer = serializer(),
            outputSerializer = serializer(),
            needsApproval = { _, _ -> true },
        ) { _ ->
            SendResult(sent = true)
        }

        val agent = TestToolLoopAgent<Unit, String>(
            model = MockLanguageModelToolThenText(
                toolName = "send",
                toolInput = MockToolInput("message" to "hey friend"),
                finalText = "sent",
            ),
            instructions = "use send",
            tools = ToolSet(sendTool),
        )

        agent.dispatchEngineAction(ToolLoopAgentAction.UserSubmitPrompt("trigger", context = Unit))
        withContext(Dispatchers.Default) {
            withTimeout(1_000) {
                while (agent.engineState.value.pendingApprovals.isEmpty()) delay(10)
            }
        }

        val originalPending = agent.engineState.value.pendingApprovals
        agent.dispatchEngineAction(ToolLoopAgentAction.ApproveToolCall("missing-approval"))

        assertEquals(originalPending, agent.engineState.value.pendingApprovals)
        assertIs<ToolLoopAgentState.Phase.Error>(agent.engineState.value.phase)
    }
}
