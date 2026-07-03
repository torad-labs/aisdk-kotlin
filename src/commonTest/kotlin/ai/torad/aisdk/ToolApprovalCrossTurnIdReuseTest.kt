package ai.torad.aisdk

import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression for the approval-resume idempotency scope.
 *
 * The "already resolved" set must be built only from tool results belonging to the
 * pending turn (after the assistant message that issued the calls). A `toolCallId`
 * that was resolved in a PRIOR, unrelated turn and then reused by the model in the
 * current turn must NOT mask the currently-pending approval — masking it would skip
 * execution and leave a dangling tool call that wedges the next model turn.
 */
class ToolApprovalCrossTurnIdReuseTest {

    @Serializable private data class In(val x: String)

    @Serializable private data class Out(val ok: Boolean)

    @Test
    fun `reused toolCallId from a prior resolved turn does not skip the pending approval`() = runTest {
        val reusedId = "call_dup"
        val input = JsonObject(mapOf("x" to JsonPrimitive("hi")))

        val tool = Tool<In, Out, Unit>(
            name = "t",
            description = "d",
            inputSerializer = serializer(),
            outputSerializer = serializer(),
            needsApproval = { _, _ -> true },
        ) { Out(true) }
        val tools = ToolSet(tool)

        val coordinator = ToolApprovalCoordinator<Unit>(
            approvalSecret = null,
            repairer = ToolCallRepairer(repairFunction = null, tools = tools),
        )

        val messages = mutableListOf(
            UserMessage("first"),
            // Prior, unrelated turn: same id, already answered by a tool result.
            ModelMessage(MessageRole.Assistant, listOf(ContentPart.ToolCall(reusedId, "t", input))),
            ModelMessage(
                MessageRole.Tool,
                listOf(ContentPart.ToolResult(reusedId, "t", JsonObject(mapOf("ok" to JsonPrimitive(true))))),
            ),
            UserMessage("second"),
            // Pending turn: model reuses the id and the call needs approval.
            ModelMessage(
                MessageRole.Assistant,
                listOf(
                    ContentPart.ToolCall(reusedId, "t", input),
                    ContentPart.ToolApprovalRequest(reusedId, "t", input),
                ),
            ),
            // Host resumes with an approval response for the pending call.
            ToolApprovalResponseMessage(toolCallId = reusedId, approved = true),
        )

        var executed = false
        val events = mutableListOf<StreamEvent>()
        coordinator.applyToolApprovalResponses(
            out = FlowCollector { events.add(it) },
            messages = messages,
            tools = tools,
            options = null,
            abortSignal = AbortSignalNever,
            hooks = null,
            feed = null,
        ) { _, _, _, _, _, _, _, _, _ ->
            executed = true
            ToolExecutionResult.Success(
                toolName = "send",
                outputJson = JsonObject(mapOf("ok" to JsonPrimitive(true))),
            )
        }

        assertTrue(executed, "approved tool must execute even though the id was resolved in a prior turn")
        // A fresh tool result for the pending turn is appended (not skipped).
        val results = messages.last().content.filterIsInstance<ContentPart.ToolResult>()
        assertEquals(1, results.size)
        assertEquals(reusedId, results.single().toolCallId)
    }
}
