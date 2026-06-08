package ai.torad.aisdk

import ai.torad.aisdk.ui.ToolCallState
import ai.torad.aisdk.ui.UIMessagePart
import ai.torad.aisdk.ui.streamToUiMessages
import app.cash.turbine.test
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive

/**
 * Validates the v6-aligned approval-flow additions from
 * historical parity work phase 4A items #6 + #7:
 *
 * - `approvalId` field on `ContentPart.ToolApprovalRequest`,
 *   `ContentPart.ToolApprovalResponse`, `StreamEvent.ToolApprovalRequest`,
 *   `PendingApproval`, and the `toolApprovalResponseMessage` factory.
 *   Nullable with `null` defaulting to `toolCallId` (common case).
 * - `StreamEvent.ToolOutputDenied(toolCallId, toolName, approvalId, reason?)`.
 * - `ToolCallState` enum 5→7 states: rename `ApprovalRequired →
 *   ApprovalRequested`, rename `Error → OutputError`, add
 *   `ApprovalResponded`, add `OutputDenied`.
 */
class ApprovalIdTest {

    @Test
    fun `given a PendingApproval without approvalId when read then effectiveApprovalId falls back to toolCallId`() {
        val approval = PendingApproval(
            toolCallId = "call_1",
            toolName = "saveNote",
            input = JsonPrimitive("data"),
        )
        assertEquals("call_1", effectiveApprovalId(approval))
    }

    @Test
    fun `given a PendingApproval with an explicit approvalId when read then effectiveApprovalId returns it`() {
        val approval = PendingApproval(
            toolCallId = "call_1",
            toolName = "saveNote",
            input = JsonPrimitive("data"),
            approvalId = "approval_xyz",
        )
        assertEquals("approval_xyz", effectiveApprovalId(approval))
    }

    @Test
    fun `given two PendingApprovals sharing a toolCallId but with distinct approvalIds when read then they correlate by approvalId`() {
        // GIVEN: two approvals on the same tool-call id (v6 allows this
        // — e.g., parallel batched tool calls). They MUST be
        // distinguishable by approvalId.
        val first = PendingApproval(
            toolCallId = "call_shared",
            toolName = "saveNote",
            input = JsonPrimitive("first"),
            approvalId = "approval_a",
        )
        val second = PendingApproval(
            toolCallId = "call_shared",
            toolName = "saveNote",
            input = JsonPrimitive("second"),
            approvalId = "approval_b",
        )
        assertEquals("approval_a", effectiveApprovalId(first))
        assertEquals("approval_b", effectiveApprovalId(second))
        // The two approvals are NOT equal — distinct approvalId disambiguates.
        assertEquals(false, first == second)
    }

    @Test
    fun `given toolApprovalResponseMessage with approvalId when constructed then the ContentPart carries it`() {
        val message = toolApprovalResponseMessage(
            toolCallId = "call_1",
            approved = true,
            approvalId = "approval_xyz",
        )
        val response = message.content[0] as ContentPart.ToolApprovalResponse
        assertEquals("approval_xyz", response.approvalId)
        assertEquals("call_1", response.toolCallId)
    }

    @Test
    fun `given a ToolOutputDenied event when converted to UIMessages then the part is OutputDenied with reason`() = runTest {
        // GIVEN
        val events = flowOf(
            StreamEvent.StreamStart(),
            StreamEvent.ToolApprovalRequest("call_1", "saveNote", JsonPrimitive("data"), approvalId = "approval_a"),
            StreamEvent.ToolOutputDenied("call_1", "saveNote", "approval_a", reason = "user denied"),
            StreamEvent.Finish(totalSteps = 1, finishReason = FinishReason.Stop, usage = Usage()),
        )

        // WHEN — collect all snapshots Turbine-style, then assert on the
        // final state. streamToUiMessages is a finite cold flow over the
        // input events so the loop terminates at awaitComplete().
        var finalToolUi: UIMessagePart.ToolUI? = null
        streamToUiMessages(events, assistantMessageId = "asst_1").test {
            var latest = awaitItem()
            while (true) {
                val event = awaitEvent()
                when {
                    event is app.cash.turbine.Event.Item<*> -> latest = event.value as ai.torad.aisdk.ui.UIMessage
                    event is app.cash.turbine.Event.Complete -> break
                    else -> error("unexpected event: $event")
                }
            }
            finalToolUi = latest.parts
                .filterIsInstance<UIMessagePart.ToolUI>()
                .firstOrNull { it.toolCallId == "call_1" }
        }

        // THEN
        val ui = assertNotNull(finalToolUi, "the ToolUI part should be in the final snapshot")
        assertEquals(ToolCallState.OutputDenied, ui.state)
        assertEquals("user denied", ui.error)
    }

    @Test
    fun `given a ToolApprovalRequest event when converted then the UI state is ApprovalRequested`() = runTest {
        val events = flowOf(
            StreamEvent.StreamStart(),
            StreamEvent.ToolApprovalRequest("call_1", "saveNote", JsonPrimitive("data")),
            StreamEvent.Finish(totalSteps = 1, finishReason = FinishReason.ToolApprovalRequested, usage = Usage()),
        )
        var finalUi: ai.torad.aisdk.ui.UIMessage? = null
        streamToUiMessages(events, assistantMessageId = "asst_1").test {
            var latest = awaitItem()
            while (true) {
                val event = awaitEvent()
                when {
                    event is app.cash.turbine.Event.Item<*> -> latest = event.value as ai.torad.aisdk.ui.UIMessage
                    event is app.cash.turbine.Event.Complete -> break
                    else -> error("unexpected event: $event")
                }
            }
            finalUi = latest
        }
        val ui = finalUi!!.parts.filterIsInstance<UIMessagePart.ToolUI>().single()
        assertEquals(ToolCallState.ApprovalRequested, ui.state)
    }
}
