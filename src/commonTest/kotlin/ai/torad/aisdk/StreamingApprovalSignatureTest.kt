package ai.torad.aisdk

import ai.torad.aisdk.AgentSessions.session
import ai.torad.aisdk.providers.MockLanguageModelToolThenText
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Regression: the STREAMING approval path (AgentSession.submitStreaming) used to drop the HMAC
 * signature both from the host-facing PendingApproval and from the persisted message log, so an
 * agent configured with experimental_toolApprovalSecret could never resume a streamed approval
 * (verifySignature fails closed on the missing signature). The non-streaming generate() path was
 * unaffected, so the hardened streaming config was the one silently broken.
 */
class StreamingApprovalSignatureTest {
    @Serializable
    private data class SendInput(val message: String)

    private val secret = "approval-secret".encodeToByteArray()

    private fun gatedAgent(executed: MutableList<String>) = TestToolLoopAgent<Unit, String>(
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
                needsApproval = { _, _ -> true },
            ) { input ->
                executed += input.message
                "sent:${input.message}"
            },
        ),
        experimental_toolApprovalSecret = secret,
    )

    @Test
    fun `streaming approval preserves the HMAC signature so the resume validates`() = runTest {
        val executed = mutableListOf<String>()
        val session = gatedAgent(executed).session(this)

        session.submitStreaming(prompt = "go").join()

        assertEquals(AgentSessionStatus.AwaitingApproval, session.state.value.status)
        val pending = session.state.value.pendingApprovals.single()
        // (a) The host-facing PendingApproval carries the issued signature.
        assertNotNull(pending.signature, "streamed approval must carry the issued signature")
        // (b) The persisted message log carries it too — the replay source verifySignature reads.
        val requestPart = session.state.value.messages
            .flatMap { it.content }
            .filterIsInstance<ContentPart.ToolApprovalRequest>()
            .single()
        assertNotNull(requestPart.signature, "streamed message log must persist the signature")

        // (c) Fail-closed resume validates: with the signature present, approve runs the tool
        // instead of throwing InvalidToolApprovalSignature("missing signature") into session error.
        session.approve(pending).join()
        assertEquals(AgentSessionStatus.Ready, session.state.value.status)
        assertEquals(listOf("hello"), executed)
    }
}
