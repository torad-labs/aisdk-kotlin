package ai.torad.aisdk

/**
 * Tool-loop error taxonomy (Phase 4C gap #25). The agent loop's failure
 * modes were previously all `String` messages on [StreamEvent.Error] /
 * [StreamEvent.ToolError]; catch sites had no way to discriminate
 * "model called an unknown tool" from "executor threw" from "input
 * decode failed" without parsing the message.
 *
 * Six variants, mirroring v6's `error/` directory subset that the
 * tool-loop emits (`packages/ai/src/error/`). Tests + future
 * pattern-matching can `when (e)` on the sealed class instead of
 * substring-matching strings.
 *
 * Surface:
 *  - [NoSuchTool] — model emitted a `ToolCall` for a name not in
 *    `ToolSet`. Available names attached so the host can offer "did
 *    you mean…" feedback.
 *  - [InvalidToolInput] — JSON parse / schema mismatch on the
 *    decoded arguments. Wraps the underlying parser exception so
 *    `cause` chains still work.
 *  - [ToolExecution] — the tool's executor body threw. Carries the
 *    tool call id so traces can correlate.
 *  - [ToolCallRepairFailed] — `experimental_repairToolCall` itself
 *    threw OR returned a corrected call that still failed to decode.
 *    Wraps the original decode error AND the repair error.
 *  - [InvalidApprovalResponse] — `toolApprovalResponseMessage(...)`
 *    referenced a `toolCallId` the agent has no pending request for.
 *  - [InvalidCallOptions] — per-call `options` failed the
 *    constructor's `callOptionsSchema` validation before any model call.
 *  - [MaxStepsReached] — `stopWhen` fired without a model emitting a
 *    terminal finish reason.
 *
 * All variants extend [RuntimeException] so they can be `throw`n and
 * caught by existing `Throwable`-tolerant handlers; pattern-match on
 * the sealed parent for typed handling.
 *
 * Wiring to existing call sites (replacing bare `String` messages in
 * `StreamEvent.Error.message`) is a follow-up to keep this change
 * additive — the taxonomy is in place; downstream call sites adopt it
 * incrementally.
 */
public sealed class AgentError(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {

    public data class NoSuchTool(
        val toolName: String,
        val availableTools: List<String>,
    ) : AgentError(
        "Model called unknown tool '$toolName' " +
            "(available: ${availableTools.joinToString().ifBlank { "<none>" }})",
    )

    public data class InvalidToolInput(
        val toolName: String,
        val rawArgs: String,
        val parseError: Throwable,
    ) : AgentError(
        "Tool '$toolName' input failed to decode: ${parseError.message ?: "<no message>"} (raw=$rawArgs)",
        cause = parseError,
    )

    public data class ToolExecution(
        val toolName: String,
        val toolCallId: String,
        val executorError: Throwable,
    ) : AgentError(
        "Tool '$toolName' (callId=$toolCallId) executor threw: ${executorError.message ?: "<no message>"}",
        cause = executorError,
    )

    public data class ToolCallRepairFailed(
        val toolName: String,
        val originalError: Throwable,
        val repairError: Throwable?,
    ) : AgentError(
        "Tool '$toolName' repair failed " +
            "(original: ${originalError.message ?: "<no message>"}, " +
            "repair: ${repairError?.message ?: "returned null"})",
        cause = repairError ?: originalError,
    )

    public data class InvalidApprovalResponse(
        val toolCallId: String,
        val knownPendingIds: List<String>,
    ) : AgentError(
        "Approval response references unknown tool call '$toolCallId' " +
            "(pending: ${knownPendingIds.joinToString().ifBlank { "<none>" }})",
    )

    public data class InvalidCallOptions(
        val validationError: Throwable,
    ) : AgentError(
        "Type validation failed for options: ${validationError.message ?: "<no message>"}",
        cause = validationError,
    )

    public data class MaxStepsReached(
        val stepCount: Int,
    ) : AgentError(
        "Agent loop hit stop condition after $stepCount step(s) without a terminal finish reason",
    )
}
