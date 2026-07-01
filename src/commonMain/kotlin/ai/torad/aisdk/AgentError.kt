package ai.torad.aisdk

import kotlin.time.Duration

/** @since 0.3.0-beta01 */
public sealed class AgentError(
    message: String,
    cause: Throwable? = null,
) : AiSdkException(message, cause) {

    /** @since 0.3.0-beta01 */
    public class NoSuchTool(
        /** @since 0.3.0-beta01 */
        public val toolName: String,
        /** @since 0.3.0-beta01 */
        public val availableTools: List<String>,
    ) : AgentError(
        "Model called unknown tool '$toolName' " +
            "(available: ${availableTools.joinToString().ifBlank { "<none>" }})",
    )

    /** @since 0.3.0-beta01 */
    public class InvalidToolInput(
        /** @since 0.3.0-beta01 */
        public val toolName: String,
        /** @since 0.3.0-beta01 */
        public val rawArgs: String,
        /** @since 0.3.0-beta01 */
        public val parseError: Throwable,
    ) : AgentError(
        "Tool '$toolName' input failed to decode: ${parseError.message ?: "<no message>"} (raw=$rawArgs)",
        cause = parseError,
    )

    /** @since 0.3.0-beta01 */
    public class ToolExecution(
        /** @since 0.3.0-beta01 */
        public val toolName: String,
        /** @since 0.3.0-beta01 */
        public val toolCallId: String,
        /** @since 0.3.0-beta01 */
        public val executorError: Throwable,
    ) : AgentError(
        "Tool '$toolName' (callId=$toolCallId) executor threw: ${executorError.message ?: "<no message>"}",
        cause = executorError,
    )

    /** @since 0.3.0-beta01 */
    public class ToolCallRepairFailed(
        /** @since 0.3.0-beta01 */
        public val toolName: String,
        /** @since 0.3.0-beta01 */
        public val originalError: Throwable,
        /** @since 0.3.0-beta01 */
        public val repairError: Throwable?,
    ) : AgentError(
        "Tool '$toolName' repair failed " +
            "(original: ${originalError.message ?: "<no message>"}, " +
            "repair: ${repairError?.message ?: "returned null"})",
        cause = repairError ?: originalError,
    )

    /** @since 0.3.0-beta01 */
    public class InvalidApprovalResponse(
        /** @since 0.3.0-beta01 */
        public val toolCallId: String,
        /** @since 0.3.0-beta01 */
        public val knownPendingIds: List<String>,
    ) : AgentError(
        "Approval response references unknown tool call '$toolCallId' " +
            "(pending: ${knownPendingIds.joinToString().ifBlank { "<none>" }})",
    )

    /** @since 0.3.0-beta01 */
    public class InvalidToolApprovalSignature(
        /** @since 0.3.0-beta01 */
        public val approvalId: String,
        /** @since 0.3.0-beta01 */
        public val toolCallId: String,
        /** @since 0.3.0-beta01 */
        public val reason: String,
    ) : AgentError(
        "Tool approval signature verification failed for approval '$approvalId' " +
            "(tool call '$toolCallId'): $reason",
    )

    /** @since 0.3.0-beta01 */
    public class InvalidCallOptions(
        /** @since 0.3.0-beta01 */
        public val validationError: Throwable,
    ) : AgentError(
        "Type validation failed for options: ${validationError.message ?: "<no message>"}",
        cause = validationError,
    )

    /** @since 0.3.0-beta01 */
    public class MaxStepsReached(
        /** @since 0.3.0-beta01 */
        public val stepCount: Int,
    ) : AgentError(
        "Agent loop hit stop condition after $stepCount step(s) without a terminal finish reason",
    )

    /** @since 0.3.0-beta01 */
    public class MaxToolCallsPerStepExceeded(
        /** @since 0.3.0-beta01 */
        public val toolCallCount: Int,
        /** @since 0.3.0-beta01 */
        public val maxToolCallsPerStep: Int,
    ) : AgentError(
        "Model emitted $toolCallCount tool call(s) in one step, exceeding the configured " +
            "maxToolCallsPerStep=$maxToolCallsPerStep",
    )

    /** @since 0.3.0-beta01 */
    public class ToolExecutionTimedOut(
        /** @since 0.3.0-beta01 */
        public val toolName: String,
        /** @since 0.3.0-beta01 */
        public val toolCallId: String,
        /** @since 0.3.0-beta01 */
        public val timeout: Duration,
    ) : AgentError(
        "Tool '$toolName' (callId=$toolCallId) timed out after $timeout",
    )
}
