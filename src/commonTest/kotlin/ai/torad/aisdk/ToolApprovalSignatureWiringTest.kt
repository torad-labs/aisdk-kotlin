package ai.torad.aisdk

import ai.torad.aisdk.providers.mockLanguageModelTextOnly
import ai.torad.aisdk.providers.mockLanguageModelToolThenText
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * v6.0.202 signed tool approvals, end to end through the loop: signing at
 * issuance, the signature riding [PendingApproval] and the message log, and
 * the FAIL-CLOSED re-validation before a replayed approval executes.
 */
class ToolApprovalSignatureWiringTest {

    @Serializable
    private data class SendInput(val message: String)

    private class GateState {
        val executed = mutableListOf<String>()
    }

    private fun gatedAgent(
        state: GateState,
        secret: ByteArray?,
        needsApproval: Boolean = true,
    ) = TestToolLoopAgent<Unit, String>(
        model = mockLanguageModelToolThenText(
            toolName = "send",
            toolInput = buildJsonObject { put("message", "hello") },
            finalText = "sent",
        ),
        instructions = "use send",
        tools = toolSetOf(
            Tool<SendInput, String, Unit>(
                name = "send",
                description = "send a message",
                inputSerializer = serializer(),
                outputSerializer = serializer(),
                needsApproval = { _, _ -> needsApproval },
            ) { input ->
                state.executed += input.message
                "sent:${input.message}"
            },
        ),
        experimental_toolApprovalSecret = secret,
    )

    private val secret = "approval-secret".encodeToByteArray()

    @Test
    fun `with a secret the issued approval carries a verifiable signature`() = runTest {
        val state = GateState()
        val first = gatedAgent(state, secret).generate(prompt = "go")
        val pending = first.pendingApprovals.single()
        val signature = assertNotNull(pending.signature, "issuance must sign when a secret is configured")
        assertTrue(
            verifyToolApprovalSignature(
                secret = secret,
                signature = signature,
                approvalId = pending.approvalId ?: pending.toolCallId,
                toolCallId = pending.toolCallId,
                toolName = pending.toolName,
                input = pending.input,
            ),
        )
        // The signature also rides the persisted message log (the replay source of truth).
        val requestPart = first.messages
            .flatMap { it.content }
            .filterIsInstance<ContentPart.ToolApprovalRequest>()
            .single()
        assertEquals(signature, requestPart.signature)
    }

    @Test
    fun `without a secret nothing is signed and the flow is unchanged`() = runTest {
        val state = GateState()
        val agent = gatedAgent(state, secret = null)
        val first = agent.generate(prompt = "go")
        val pending = first.pendingApprovals.single()
        assertNull(pending.signature)

        val approval = toolApprovalResponseMessage(pending.toolCallId, approved = true, approvalId = pending.approvalId)
        agent.generate(messages = first.messages + approval)
        assertEquals(listOf("hello"), state.executed)
    }

    @Test
    fun `a signed approval replays and executes`() = runTest {
        val state = GateState()
        val agent = gatedAgent(state, secret)
        val first = agent.generate(prompt = "go")
        val pending = first.pendingApprovals.single()

        val approval = toolApprovalResponseMessage(pending.toolCallId, approved = true, approvalId = pending.approvalId)
        val second = agent.generate(messages = first.messages + approval)
        assertEquals(listOf("hello"), state.executed)
        assertEquals("sent", second.text)
    }

    @Test
    fun `tampering with the replayed input fails closed`() = runTest {
        val state = GateState()
        val agent = gatedAgent(state, secret)
        val first = agent.generate(prompt = "go")
        val pending = first.pendingApprovals.single()

        // The "client" rewrites the tool call's input in the replayed history.
        val tamperedHistory = first.messages.map { message ->
            message.copy(
                content = message.content.map { part ->
                    if (part is ContentPart.ToolCall && part.toolCallId == pending.toolCallId) {
                        part.copy(input = buildJsonObject { put("message", "rm -rf /") })
                    } else {
                        part
                    }
                },
            )
        }
        val approval = toolApprovalResponseMessage(pending.toolCallId, approved = true, approvalId = pending.approvalId)
        val failure = assertFailsWith<AgentError.InvalidToolApprovalSignature> {
            agent.generate(messages = tamperedHistory + approval)
        }
        assertEquals(pending.toolCallId, failure.toolCallId)
        assertTrue(state.executed.isEmpty(), "the tampered call must never execute")
    }

    @Test
    fun `a replay missing its signature fails closed when a secret is configured`() = runTest {
        val state = GateState()
        // History minted WITHOUT a secret (no signature on the request part)...
        val unsigned = gatedAgent(state, secret = null).generate(prompt = "go")
        val pending = unsigned.pendingApprovals.single()
        val approval = toolApprovalResponseMessage(pending.toolCallId, approved = true, approvalId = pending.approvalId)

        // ...replayed against an agent that REQUIRES signatures.
        val failure = assertFailsWith<AgentError.InvalidToolApprovalSignature> {
            gatedAgent(GateState(), secret).generate(messages = unsigned.messages + approval)
        }
        assertTrue(failure.reason.contains("missing"), "got: ${failure.reason}")
        assertTrue(state.executed.isEmpty())
    }

    @Test
    fun `an approval for a tool that no longer requires approval is denied not run`() = runTest {
        val state = GateState()
        val first = gatedAgent(state, secret).generate(prompt = "go")
        val pending = first.pendingApprovals.single()
        val approval = toolApprovalResponseMessage(pending.toolCallId, approved = true, approvalId = pending.approvalId)

        // At replay time the tool's gate resolves false — the server would never have
        // issued this request, so the approval is treated as fabricated and denied.
        // The replay model is TEXT-ONLY so the only execution path is the stale approval.
        val replayAgent = TestToolLoopAgent<Unit, String>(
            model = mockLanguageModelTextOnly("ok"),
            instructions = "use send",
            tools = toolSetOf(
                Tool<SendInput, String, Unit>(
                    name = "send",
                    description = "send a message",
                    inputSerializer = serializer(),
                    outputSerializer = serializer(),
                    needsApproval = { _, _ -> false },
                ) { input ->
                    state.executed += input.message
                    "sent:${input.message}"
                },
            ),
            experimental_toolApprovalSecret = secret,
        )
        val second = replayAgent.generate(messages = first.messages + approval)
        assertTrue(state.executed.isEmpty(), "a no-longer-gated tool must not run off a stale approval")
        val denial = second.messages
            .flatMap { it.content }
            .filterIsInstance<ContentPart.ToolResult>()
            .firstOrNull { it.toolCallId == pending.toolCallId }
        assertNotNull(denial, "the denial lands as the tool result for the call")
        assertFalse(state.executed.contains("hello"))
    }
}
