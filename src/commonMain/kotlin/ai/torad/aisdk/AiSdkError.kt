package ai.torad.aisdk

open class AiSdkException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

class InvalidArgumentError(
    argument: String,
    reason: String,
) : AiSdkException("Invalid argument `$argument`: $reason")

class UnsupportedModelVersionError(
    modelId: String,
    version: String,
) : AiSdkException("Unsupported model version `$version` for `$modelId`")

class NoSuchProviderError(providerId: String) :
    AiSdkException("No provider registered for `$providerId`")

class NoSuchModelError(
    providerId: String?,
    modelType: String,
    modelId: String,
    message: String = buildString {
        append("No ")
        append(modelType)
        append(" model registered for `")
        providerId?.let { append(it).append(":") }
        append(modelId)
        append("`")
    },
) : AiSdkException(
    message,
)

class NoOutputGeneratedError(message: String = "No output generated") : AiSdkException(message)
class NoObjectGeneratedError(message: String = "No object generated") : AiSdkException(message)
class NoImageGeneratedError(message: String = "No image generated") : AiSdkException(message)
class NoSpeechGeneratedError(message: String = "No speech generated") : AiSdkException(message)
class NoTranscriptGeneratedError(message: String = "No transcript generated") : AiSdkException(message)
class NoVideoGeneratedError(message: String = "No video generated") : AiSdkException(message)
class UiMessageStreamError(message: String, cause: Throwable? = null) : AiSdkException(message, cause)

class InvalidStreamPartError(
    val chunk: Any?,
    message: String,
) : AiSdkException(message)

class InvalidToolApprovalError(
    val approvalId: String,
) : AiSdkException(
    "Tool approval response references unknown approvalId: \"$approvalId\". " +
        "No matching tool-approval-request found in message history.",
)

class InvalidToolInputError(
    val toolInput: String,
    val toolName: String,
    cause: Throwable,
    message: String = "Invalid input for tool $toolName: ${getErrorMessage(cause)}",
) : AiSdkException(message, cause)

class ToolCallNotFoundForApprovalError(
    val toolCallId: String,
    val approvalId: String,
) : AiSdkException("Tool call \"$toolCallId\" not found for approval request \"$approvalId\".")

class MissingToolResultsError(
    val toolCallIds: List<String>,
) : AiSdkException(
    "Tool result${if (toolCallIds.size > 1) "s are" else " is"} missing for " +
        "tool call${if (toolCallIds.size > 1) "s" else ""} ${toolCallIds.joinToString(", ")}.",
)

class NoSuchToolError(
    val toolName: String,
    val availableTools: List<String>? = null,
    message: String = "Model tried to call unavailable tool '$toolName'. " +
        if (availableTools == null) {
            "No tools are available."
        } else {
            "Available tools: ${availableTools.joinToString(", ")}."
        },
) : AiSdkException(message)

class ToolCallRepairError(
    val originalError: Throwable,
    cause: Throwable,
    message: String = "Error repairing tool call: ${getErrorMessage(cause)}",
) : AiSdkException(message, cause)

class InvalidDataContentError(
    val content: Any?,
    cause: Throwable? = null,
    message: String = "Invalid data content. Expected a base64 string, ByteArray, or platform byte buffer, " +
        "but got ${content?.let { it::class.simpleName } ?: "null"}.",
) : AiSdkException(message, cause)

class InvalidMessageRoleError(
    val role: String,
    message: String = "Invalid message role: '$role'. Must be one of: \"system\", \"user\", \"assistant\", \"tool\".",
) : AiSdkException(message)

class MessageConversionError(
    val originalMessage: Any?,
    message: String,
) : AiSdkException(message)

enum class RetryErrorReason {
    MaxRetriesExceeded,
    ErrorNotRetryable,
    Abort,
}

class RetryError(
    message: String,
    val reason: RetryErrorReason,
    val errors: List<Throwable>,
) : AiSdkException(message, errors.lastOrNull()) {
    val lastError: Throwable? = errors.lastOrNull()
}
