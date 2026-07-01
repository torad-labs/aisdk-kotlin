package ai.torad.aisdk

import dev.drewhamilton.poko.Poko
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * A pending tool-call approval surfaced from [Agent.generate] /
 * [Agent.stream]. Per v6 RPC semantics, generation completes when one or
 * more tools require approval — the host inspects [GenerateResult.pendingApprovals],
 * presents UI, and resumes by calling [Agent.generate] again with
 * `messages = result.messages + ToolApprovalResponseMessage(...)`.
 *
 * There is no `Agent.submitApproval` — approval flows entirely through
 * the message log so it is serializable, persistable, and resumable
 * across process restarts.
 */
@Serializable
@Poko
/** @since 0.3.0-beta01 */
public class PendingApproval(
    /** @since 0.3.0-beta01 */
    public val toolCallId: String,
    /** @since 0.3.0-beta01 */
    public val toolName: String,
    /** @since 0.3.0-beta01 */
    public val input: JsonElement,
    /**
     * Approval-identity key. Mirrors v6's `approvalId` (per
     * historical parity gap #7). Distinct from [toolCallId] because
     * multiple approvals can share a `toolCallId` (e.g., parallel
     * tool-call batches where the model emits one tool-call with the
     * SAME id twice and each side needs separate approval). When
     * null, the host treats `approvalId = toolCallId` — adequate for
     * the common single-approval case.
      * @since 0.3.0-beta01
     */
    public val approvalId: String? = null,
    /**
     * HMAC-SHA256 signature binding this approval to its tool call
     * (v6.0.202). Present only when the issuing agent holds an
     * `experimental_toolApprovalSecret`; the host persists and replays
     * it untouched — only the secret holder can mint or verify it.
      * @since 0.3.0-beta01
     */
    public val signature: String? = null,
)

/**
 * Approval-identity helpers for [PendingApproval].
 * @since 0.3.0-beta01
 */
public object ApprovalIds {
    /** The effective approval ID — explicit [PendingApproval.approvalId] or
      * @since 0.3.0-beta01
     *  fallback to [PendingApproval.toolCallId]. */
    public fun effectiveApprovalId(approval: PendingApproval): String =
        approval.approvalId ?: approval.toolCallId
}
