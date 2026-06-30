@file:OptIn(LowLevelLanguageModelApi::class)

package ai.torad.aisdk

import ai.torad.aisdk.providers.MockLanguageModelTextOnly
import ai.torad.aisdk.providers.MockLanguageModelToolThenText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertContentEquals
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

    @Test
    fun `approval secret is defensively copied on input and output`() {
        val secret = byteArrayOf(1, 2, 3)
        val agent = TestToolLoopAgent<Unit, String>(
            model = MockLanguageModelTextOnly("ok"),
            instructions = "test",
            tools = ToolSet(),
            experimental_toolApprovalSecret = secret,
        )
        secret[0] = 9

        val exposed = agent.experimental_toolApprovalSecret
        assertNotNull(exposed)
        exposed[1] = 9

        assertContentEquals(byteArrayOf(1, 2, 3), agent.experimental_toolApprovalSecret)
    }

    private class DuplicateApprovalModel : LanguageModel {
        override val modelId: String = "dup-approval"
        private var calls = 0

        override suspend fun generate(params: LanguageModelCallParams): LanguageModelResult =
            if (calls++ == 0) {
                val first = ContentPart.ToolCall(
                    toolCallId = "dup",
                    toolName = "send",
                    input = buildJsonObject { put("message", "first") },
                )
                val second = ContentPart.ToolCall(
                    toolCallId = "dup",
                    toolName = "send",
                    input = buildJsonObject { put("message", "second") },
                )
                LanguageModelResult(
                    text = "",
                    toolCalls = listOf(first, second),
                    finishReason = FinishReason.ToolCalls,
                    usage = Usage(),
                )
            } else {
                LanguageModelResult(text = "done", finishReason = FinishReason.Stop, usage = Usage())
            }

        override fun stream(params: LanguageModelCallParams): Flow<StreamEvent> = flow {
            if (calls++ == 0) {
                emit(StreamEvent.ToolCall("dup", "send", buildJsonObject { put("message", "first") }))
                emit(StreamEvent.ToolCall("dup", "send", buildJsonObject { put("message", "second") }))
                emit(StreamEvent.StepFinish(1, FinishReason.ToolCalls, Usage()))
                emit(StreamEvent.Finish(1, FinishReason.ToolCalls, Usage()))
            } else {
                emit(StreamEvent.TextStart("t1"))
                emit(StreamEvent.TextDelta("t1", "done"))
                emit(StreamEvent.TextEnd("t1"))
                emit(StreamEvent.Finish(2, FinishReason.Stop, Usage()))
            }
        }
    }

    private fun gatedAgent(
        state: GateState,
        secret: ByteArray?,
        needsApproval: Boolean = true,
    ) = TestToolLoopAgent<Unit, String>(
        model = MockLanguageModelToolThenText(
            toolName = "send",
            toolInput = buildJsonObject { put("message", "hello") },
            finalText = "sent",
        ),
        instructions = "use send",
        tools = ToolSet(
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
        val first = gatedAgent(state, secret).generate(prompt = "go").first()
        val pending = first.pendingApprovals.single()
        val signature = assertNotNull(pending.signature, "issuance must sign when a secret is configured")
        assertTrue(
            ToolApprovalSignature.verifyToolApprovalSignature(
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
        val first = agent.generate(prompt = "go").first()
        val pending = first.pendingApprovals.single()
        assertNull(pending.signature)

        val approval = ToolApprovalResponseMessage(pending.toolCallId, approved = true, approvalId = pending.approvalId)
        agent.generate(messages = first.messages + approval).first()
        assertEquals(listOf("hello"), state.executed)
    }

    @Test
    fun `a signed approval replays and executes`() = runTest {
        val state = GateState()
        val agent = gatedAgent(state, secret)
        val first = agent.generate(prompt = "go").first()
        val pending = first.pendingApprovals.single()

        val approval = ToolApprovalResponseMessage(pending.toolCallId, approved = true, approvalId = pending.approvalId)
        val second = agent.generate(messages = first.messages + approval).first()
        assertEquals(listOf("hello"), state.executed)
        assertEquals("sent", second.text)
    }

    @Test
    fun `an approved response with an unknown approval id fails closed`() = runTest {
        val state = GateState()
        val agent = gatedAgent(state, secret)
        val first = agent.generate(prompt = "go").first()
        val pending = first.pendingApprovals.single()
        val approval = ToolApprovalResponseMessage(
            pending.toolCallId,
            approved = true,
            approvalId = "forged-approval-id",
        )

        assertFailsWith<AgentError.InvalidApprovalResponse> {
            agent.generate(messages = first.messages + approval).first()
        }
        assertTrue(state.executed.isEmpty())
    }

    @Test
    fun `duplicate tool call ids get distinct approval ids and resume cleanly`() = runTest {
        val state = GateState()
        val agent = TestToolLoopAgent<Unit, String>(
            model = DuplicateApprovalModel(),
            instructions = "use send",
            tools = ToolSet(
                Tool<SendInput, String, Unit>(
                    name = "send",
                    description = "send a message",
                    inputSerializer = serializer(),
                    outputSerializer = serializer(),
                    needsApproval = { _, _ -> true },
                ) { input ->
                    state.executed += input.message
                    "sent:${input.message}"
                },
            ),
            experimental_toolApprovalSecret = secret,
        )

        val first = agent.generate(prompt = "go").first()
        assertEquals(2, first.pendingApprovals.size)
        val approvalIds = first.pendingApprovals.map { assertNotNull(it.approvalId) }
        assertEquals(2, approvalIds.toSet().size, "duplicate toolCallIds must be disambiguated by approvalId")

        val approvals = ModelMessage(
            MessageRole.Tool,
            first.pendingApprovals.map { pending ->
                ContentPart.ToolApprovalResponse(
                    toolCallId = pending.toolCallId,
                    approved = true,
                    approvalId = pending.approvalId,
                )
            },
        )
        val second = agent.generate(messages = first.messages + approvals).first()

        assertEquals(listOf("first", "second"), state.executed)
        assertEquals("done", second.text)
    }

    @Test
    fun `duplicate tool call ids can be approved out of order by approval id`() = runTest {
        val state = GateState()
        val agent = TestToolLoopAgent<Unit, String>(
            model = DuplicateApprovalModel(),
            instructions = "use send",
            tools = ToolSet(
                Tool<SendInput, String, Unit>(
                    name = "send",
                    description = "send a message",
                    inputSerializer = serializer(),
                    outputSerializer = serializer(),
                    needsApproval = { _, _ -> true },
                ) { input ->
                    state.executed += input.message
                    "sent:${input.message}"
                },
            ),
            experimental_toolApprovalSecret = secret,
        )

        val first = agent.generate(prompt = "go").first()
        val secondPending = first.pendingApprovals[1]
        val firstPending = first.pendingApprovals[0]
        val approvals = ModelMessage(
            MessageRole.Tool,
            listOf(secondPending, firstPending).map { pending ->
                ContentPart.ToolApprovalResponse(
                    toolCallId = pending.toolCallId,
                    approved = true,
                    approvalId = pending.approvalId,
                )
            },
        )
        val second = agent.generate(messages = first.messages + approvals).first()

        assertEquals(listOf("second", "first"), state.executed)
        assertEquals("done", second.text)
    }

    @Test
    fun `tampering with the replayed input fails closed`() = runTest {
        val state = GateState()
        val agent = gatedAgent(state, secret)
        val first = agent.generate(prompt = "go").first()
        val pending = first.pendingApprovals.single()

        // The "client" rewrites the tool call's input in the replayed history.
        val tamperedHistory = first.messages.map { message ->
            ModelMessage(
                role = message.role,
                content = message.content.map { part ->
                    if (part is ContentPart.ToolCall && part.toolCallId == pending.toolCallId) {
                        ContentPart.ToolCall(
                            toolCallId = part.toolCallId,
                            toolName = part.toolName,
                            input = buildJsonObject { put("message", "rm -rf /") },
                            providerExecuted = part.providerExecuted,
                            dynamic = part.dynamic,
                            providerMetadata = part.providerMetadata,
                        )
                    } else {
                        part
                    }
                },
            )
        }
        val approval = ToolApprovalResponseMessage(pending.toolCallId, approved = true, approvalId = pending.approvalId)
        val failure = assertFailsWith<AgentError.InvalidToolApprovalSignature> {
            agent.generate(messages = tamperedHistory + approval).first()
        }
        assertEquals(pending.toolCallId, failure.toolCallId)
        assertTrue(state.executed.isEmpty(), "the tampered call must never execute")
    }

    @Test
    fun `a replay missing its signature fails closed when a secret is configured`() = runTest {
        val state = GateState()
        // History minted WITHOUT a secret (no signature on the request part)...
        val unsigned = gatedAgent(state, secret = null).generate(prompt = "go").first()
        val pending = unsigned.pendingApprovals.single()
        val approval = ToolApprovalResponseMessage(pending.toolCallId, approved = true, approvalId = pending.approvalId)

        // ...replayed against an agent that REQUIRES signatures.
        val failure = assertFailsWith<AgentError.InvalidToolApprovalSignature> {
            gatedAgent(GateState(), secret).generate(messages = unsigned.messages + approval).first()
        }
        assertTrue(failure.reason.contains("missing"), "got: ${failure.reason}")
        assertTrue(state.executed.isEmpty())
    }

    @Test
    fun `an approval for a tool that no longer requires approval is denied not run`() = runTest {
        val state = GateState()
        val first = gatedAgent(state, secret).generate(prompt = "go").first()
        val pending = first.pendingApprovals.single()
        val approval = ToolApprovalResponseMessage(pending.toolCallId, approved = true, approvalId = pending.approvalId)

        // At replay time the tool's gate resolves false — the server would never have
        // issued this request, so the approval is treated as fabricated and denied.
        // The replay model is TEXT-ONLY so the only execution path is the stale approval.
        val replayAgent = TestToolLoopAgent<Unit, String>(
            model = MockLanguageModelTextOnly("ok"),
            instructions = "use send",
            tools = ToolSet(
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
        val second = replayAgent.generate(messages = first.messages + approval).first()
        assertTrue(state.executed.isEmpty(), "a no-longer-gated tool must not run off a stale approval")
        val denial = second.messages
            .flatMap { it.content }
            .filterIsInstance<ContentPart.ToolResult>()
            .firstOrNull { it.toolCallId == pending.toolCallId }
        assertNotNull(denial, "the denial lands as the tool result for the call")
        assertFalse(state.executed.contains("hello"))
    }
}
