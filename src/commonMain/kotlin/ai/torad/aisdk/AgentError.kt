package ai.torad.aisdk

import kotlin.time.Duration

public sealed class AgentError(
    message: String,
    cause: Throwable? = null,
) : AiSdkException(message, cause) {

    public class NoSuchTool(
        public val toolName: String,
        public val availableTools: List<String>,
    ) : AgentError(
        "Model called unknown tool '$toolName' " +
            "(available: ${availableTools.joinToString().ifBlank { "<none>" }})",
    )

    public class InvalidToolInput(
        public val toolName: String,
        public val rawArgs: String,
        public val parseError: Throwable,
    ) : AgentError(
        "Tool '$toolName' input failed to decode: ${parseError.message ?: "<no message>"} (raw=$rawArgs)",
        cause = parseError,
    )

    public class ToolExecution(
        public val toolName: String,
        public val toolCallId: String,
        public val executorError: Throwable,
    ) : AgentError(
        "Tool '$toolName' (callId=$toolCallId) executor threw: ${executorError.message ?: "<no message>"}",
        cause = executorError,
    )

    public class ToolCallRepairFailed(
        public val toolName: String,
        public val originalError: Throwable,
        public val repairError: Throwable?,
    ) : AgentError(
        "Tool '$toolName' repair failed " +
            "(original: ${originalError.message ?: "<no message>"}, " +
            "repair: ${repairError?.message ?: "returned null"})",
        cause = repairError ?: originalError,
    )

    public class InvalidApprovalResponse(
        public val toolCallId: String,
        public val knownPendingIds: List<String>,
    ) : AgentError(
        "Approval response references unknown tool call '$toolCallId' " +
            "(pending: ${knownPendingIds.joinToString().ifBlank { "<none>" }})",
    )

    public class InvalidToolApprovalSignature(
        public val approvalId: String,
        public val toolCallId: String,
        public val reason: String,
    ) : AgentError(
        "Tool approval signature verification failed for approval '$approvalId' " +
            "(tool call '$toolCallId'): $reason",
    )

    public class InvalidCallOptions(
        public val validationError: Throwable,
    ) : AgentError(
        "Type validation failed for options: ${validationError.message ?: "<no message>"}",
        cause = validationError,
    )

    public class MaxStepsReached(
        public val stepCount: Int,
    ) : AgentError(
        "Agent loop hit stop condition after $stepCount step(s) without a terminal finish reason",
    )

    public class MaxToolCallsPerStepExceeded(
        public val toolCallCount: Int,
        public val maxToolCallsPerStep: Int,
    ) : AgentError(
        "Model emitted $toolCallCount tool call(s) in one step, exceeding the configured " +
            "maxToolCallsPerStep=$maxToolCallsPerStep",
    )

    public class ToolExecutionTimedOut(
        public val toolName: String,
        public val toolCallId: String,
        public val timeout: Duration,
    ) : AgentError(
        "Tool '$toolName' (callId=$toolCallId) timed out after $timeout",
    )
}
