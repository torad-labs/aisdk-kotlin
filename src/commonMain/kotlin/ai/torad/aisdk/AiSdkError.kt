package ai.torad.aisdk

open class AiSdkException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

typealias AISDKError = AiSdkException

class InvalidArgumentError(
    val argument: String,
    reason: String,
    cause: Throwable? = null,
) : AiSdkException("Invalid argument `$argument`: $reason", cause)

class APICallError(
    message: String,
    val url: String,
    val requestBodyValues: Any? = null,
    val statusCode: Int? = null,
    val responseHeaders: Map<String, String>? = null,
    val responseBody: String? = null,
    cause: Throwable? = null,
    val isRetryable: Boolean = statusCode == 408 || statusCode == 409 || statusCode == 429 || (statusCode ?: 0) >= 500,
    val data: Any? = null,
) : AiSdkException(message, cause)

class EmptyResponseBodyError(message: String = "Empty response body") : AiSdkException(message)

class InvalidPromptError(
    val prompt: Any?,
    message: String,
    cause: Throwable? = null,
) : AiSdkException("Invalid prompt: $message", cause)

class InvalidResponseDataError(
    val data: Any?,
    message: String = "Invalid response data: $data.",
) : AiSdkException(message)

class JSONParseError(
    val text: String,
    cause: Throwable,
) : AiSdkException(
    "JSON parsing failed: Text: $text.\nError message: ${getErrorMessage(cause)}",
    cause,
)

class LoadAPIKeyError(message: String) : AiSdkException(message)

class LoadSettingError(message: String) : AiSdkException(message)

class NoContentGeneratedError(message: String = "No content generated.") : AiSdkException(message)

class TooManyEmbeddingValuesForCallError(
    val provider: String,
    val modelId: String,
    val maxEmbeddingsPerCall: Int,
    val values: List<Any?>,
) : AiSdkException(
    "Too many values for a single embedding call. " +
        "The $provider model \"$modelId\" can only embed up to " +
        "$maxEmbeddingsPerCall values per call, but ${values.size} values were provided.",
)

data class TypeValidationContext(
    val field: String? = null,
    val entityName: String? = null,
    val entityId: String? = null,
)

class TypeValidationError(
    val value: Any?,
    cause: Throwable,
    val context: TypeValidationContext? = null,
) : AiSdkException(typeValidationMessage(value, cause, context), cause) {
    companion object {
        fun wrap(
            value: Any?,
            cause: Throwable,
            context: TypeValidationContext? = null,
        ): TypeValidationError =
            if (cause is TypeValidationError && cause.value == value && cause.context == context) {
                cause
            } else {
                TypeValidationError(value, cause, context)
            }
    }
}

class UnsupportedFunctionalityError(
    val functionality: String,
    message: String = "'$functionality' functionality not supported.",
) : AiSdkException(message)

private fun typeValidationMessage(
    value: Any?,
    cause: Throwable,
    context: TypeValidationContext?,
): String {
    var prefix = "Type validation failed"
    context?.field?.let { prefix += " for $it" }
    if (context?.entityName != null || context?.entityId != null) {
        val parts = buildList {
            context.entityName?.let { add(it) }
            context.entityId?.let { add("id: \"$it\"") }
        }
        prefix += " (${parts.joinToString(", ")})"
    }
    return "$prefix: Value: $value.\nError message: ${getErrorMessage(cause)}"
}

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
