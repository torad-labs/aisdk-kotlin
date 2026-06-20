package ai.torad.aisdk

import kotlinx.serialization.json.JsonElement

public sealed class AiSdkException(
    message: String?,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

public class InvalidArgumentError(
    public val argument: String,
    reason: String,
    cause: Throwable? = null,
) : AiSdkException("Invalid argument `$argument`: $reason", cause)

public class APICallError(
    message: String,
    public val url: String,
    public val requestBodyValues: Any? = null,
    public val statusCode: Int? = null,
    public val responseHeaders: Map<String, String>? = null,
    public val responseBody: String? = null,
    cause: Throwable? = null,
    public val isRetryable: Boolean = statusCode == 408 || statusCode == 409 || statusCode == 429 || (statusCode ?: 0) >= 500,
) : AiSdkException(message, cause)

public class EmptyResponseBodyError(message: String = "Empty response body") : AiSdkException(message)

public class InvalidPromptError(
    public val prompt: String?,
    message: String,
    cause: Throwable? = null,
) : AiSdkException("Invalid prompt: $message", cause)

public class InvalidResponseDataError(
    public val data: JsonElement?,
    message: String = "Invalid response data: $data.",
) : AiSdkException(message)

public class JSONParseError(
    public val text: String,
    cause: Throwable,
) : AiSdkException(
    "JSON parsing failed: Text: $text.\nError message: ${getErrorMessage(cause)}",
    cause,
)

public class LoadAPIKeyError(message: String) : AiSdkException(message)

public class LoadSettingError(message: String) : AiSdkException(message)

public class NoContentGeneratedError(message: String = "No content generated.") : AiSdkException(message)

public class TooManyEmbeddingValuesForCallError(
    public val provider: String,
    public val modelId: String,
    public val maxEmbeddingsPerCall: Int,
    public val values: List<Any?>,
) : AiSdkException(
    "Too many values for a single embedding call. " +
        "The $provider model \"$modelId\" can only embed up to " +
        "$maxEmbeddingsPerCall values per call, but ${values.size} values were provided.",
)

public data class TypeValidationContext(
    val field: String? = null,
    val entityName: String? = null,
    val entityId: String? = null,
)

public class TypeValidationError(
    public val value: JsonElement?,
    cause: Throwable,
    public val context: TypeValidationContext? = null,
) : AiSdkException(buildTypeValidationMessage(value, cause, context), cause) {
    public companion object {
        public fun wrap(
            value: JsonElement?,
            cause: Throwable,
            context: TypeValidationContext? = null,
        ): TypeValidationError =
            if (cause is TypeValidationError && cause.value == value && cause.context == context) {
                cause
            } else {
                TypeValidationError(value, cause, context)
            }

        internal fun buildTypeValidationMessage(
            value: JsonElement?,
            cause: Throwable,
            context: TypeValidationContext?,
        ): String {
            val entityQualifier = buildList {
                context?.entityName?.let { add(it) }
                context?.entityId?.let { add("id: \"$it\"") }
            }.takeIf { it.isNotEmpty() }?.joinToString(", ")?.let { " ($it)" } ?: ""
            val fieldQualifier = context?.field?.let { " for $it" } ?: ""
            return "Type validation failed$fieldQualifier$entityQualifier: Value: $value.\nError message: ${getErrorMessage(cause)}"
        }
    }
}

public class UnsupportedFunctionalityError(
    public val functionality: String,
    message: String = "'$functionality' functionality not supported.",
) : AiSdkException(message)

public class UnsupportedModelVersionError(
    modelId: String,
    version: String,
) : AiSdkException("Unsupported model version `$version` for `$modelId`")

public class NoSuchProviderError(
    public val providerId: String,
    public val availableProviders: List<String> = emptyList(),
) : AiSdkException(
    "No provider registered for `$providerId`" +
        if (availableProviders.isEmpty()) "" else " (available: ${availableProviders.joinToString(", ")})",
)

public class NoSuchModelError(
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
) : AiSdkException(message)

public class NoOutputGeneratedError(message: String = "No output generated") : AiSdkException(message)

public class NoObjectGeneratedError(
    message: String = "No object generated",
    public val text: String? = null,
    public val response: LanguageModelResponseMetadata? = null,
    public val usage: Usage? = null,
    public val finishReason: FinishReason? = null,
    cause: Throwable? = null,
) : AiSdkException(message, cause)

public class NoImageGeneratedError(
    message: String = "No image generated",
    public val responses: List<LanguageModelResponseMetadata> = emptyList(),
    cause: Throwable? = null,
) : AiSdkException(message, cause)

public class NoSpeechGeneratedError(
    message: String = "No speech generated",
    public val responses: List<LanguageModelResponseMetadata> = emptyList(),
    cause: Throwable? = null,
) : AiSdkException(message, cause)

public class NoTranscriptGeneratedError(
    message: String = "No transcript generated",
    public val responses: List<LanguageModelResponseMetadata> = emptyList(),
    cause: Throwable? = null,
) : AiSdkException(message, cause)

public class NoVideoGeneratedError(
    message: String = "No video generated",
    public val responses: List<LanguageModelResponseMetadata> = emptyList(),
    cause: Throwable? = null,
) : AiSdkException(message, cause)

public class UiMessageStreamError(message: String, cause: Throwable? = null) : AiSdkException(message, cause)

public class InvalidStreamPartError(
    public val chunk: String?,
    message: String,
) : AiSdkException(message)

public class InvalidToolApprovalError(
    public val approvalId: String,
) : AiSdkException(
    "Tool approval response references unknown approvalId: \"$approvalId\". " +
        "No matching tool-approval-request found in message history.",
)

public class InvalidToolInputError(
    public val toolInput: String,
    public val toolName: String,
    cause: Throwable,
    message: String = "Invalid input for tool $toolName: ${getErrorMessage(cause)}",
) : AiSdkException(message, cause)

public class ToolCallNotFoundForApprovalError(
    public val toolCallId: String,
    public val approvalId: String,
) : AiSdkException("Tool call \"$toolCallId\" not found for approval request \"$approvalId\".")

public class MissingToolResultsError(
    public val toolCallIds: List<String>,
) : AiSdkException(
    "Tool result${if (toolCallIds.size > 1) "s are" else " is"} missing for " +
        "tool call${if (toolCallIds.size > 1) "s" else ""} ${toolCallIds.joinToString(", ")}.",
)

public class NoSuchToolError(
    public val toolName: String,
    public val availableTools: List<String>? = null,
    message: String = "Model tried to call unavailable tool '$toolName'. " +
        if (availableTools == null) {
            "No tools are available."
        } else {
            "Available tools: ${availableTools.joinToString(", ")}."
        },
) : AiSdkException(message)

public class ToolCallRepairError(
    public val originalError: Throwable,
    cause: Throwable,
    message: String = "Error repairing tool call: ${getErrorMessage(cause)}",
) : AiSdkException(message, cause)

public class InvalidDataContentError(
    public val content: String?,
    cause: Throwable? = null,
    message: String = "Invalid data content. Expected a base64 string, ByteArray, or platform byte buffer, " +
        "but got ${content ?: "null"}.",
) : AiSdkException(message, cause)

public class InvalidMessageRoleError(
    public val role: String,
    message: String = "Invalid message role: '$role'. Must be one of: \"system\", \"user\", \"assistant\", \"tool\".",
) : AiSdkException(message)

public class MessageConversionError(
    public val originalMessage: String?,
    message: String,
) : AiSdkException(message)

public enum class RetryErrorReason {
    MaxRetriesExceeded,
    ErrorNotRetryable,
    Abort,
}

public class RetryError(
    message: String,
    public val reason: RetryErrorReason,
    public val errors: List<Throwable>,
) : AiSdkException(message, errors.lastOrNull()) {
    public val lastError: Throwable? = errors.lastOrNull()
}

/** Unclassified provider/core error — replaces bare `throw AiSdkException(...)` at sites that
 *  haven't yet been migrated to a typed leaf. open so provider packages can subclass while in
 *  migration. Layer-8 providers will eliminate these. */
public open class AiSdkRuntimeException(
    message: String,
    cause: Throwable? = null,
) : AiSdkException(message, cause)
