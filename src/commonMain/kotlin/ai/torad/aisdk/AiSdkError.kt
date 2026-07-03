package ai.torad.aisdk

import dev.drewhamilton.poko.Poko
import kotlinx.serialization.json.JsonElement
import kotlin.time.Duration

/** @since 0.3.0-beta01 */
public sealed class AiSdkException(
    message: String?,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** @since 0.3.0-beta01 */
public class InvalidArgumentError(
    /** @since 0.3.0-beta01 */
    public val argument: String,
    reason: String,
    cause: Throwable? = null,
) : AiSdkException("Invalid argument `$argument`: $reason", cause)

/**
 * Provider HTTP/API failure with normalized request and response details.
 *
 * [statusCode] is the HTTP status when the failure came from a response; it is
 * null for client-side transport failures or provider errors that do not expose
 * a status. [responseHeaders] contains response headers exactly as the provider
 * adapter captured them, or null when no response was received. [isRetryable]
 * defaults to true for common transient statuses: 408, 409, 429, and 5xx.
 * Providers may override it when the response body carries stronger retry
 * semantics.
 * @since 0.3.0-beta01
 */
@Suppress("LongParameterList")
public class APICallError(
    message: String,
    /**
     * Fully resolved request URL.
     * @since 0.3.0-beta01
     */
    public val url: String,
    /**
     * JSON request body values when the provider adapter can expose them.
     * @since 0.3.0-beta01
     */
    public val requestBodyValues: JsonElement? = null,
    /**
     * HTTP status code for response-backed failures, or null for failures
     * before a response exists.
     * @since 0.3.0-beta01
     */
    public val statusCode: Int? = null,
    /**
     * Response headers captured from the provider, or null when unavailable.
     * @since 0.3.0-beta01
     */
    public val responseHeaders: Map<String, String>? = null,
    /**
     * Raw response body text when the provider returned one.
     * @since 0.3.0-beta01
     */
    public val responseBody: String? = null,
    cause: Throwable? = null,
    /**
     * Whether retry middleware should treat this failure as transient.
     * @since 0.3.0-beta01
     */
    public val isRetryable: Boolean = statusCode == 408 || statusCode == 409 || statusCode == 429 || (statusCode ?: 0) >= 500,
) : AiSdkException(message, cause)

/** @since 0.3.0-beta01 */
public class EmptyResponseBodyError(message: String = "Empty response body") : AiSdkException(message)

/** @since 0.3.0-beta01 */
public class CallTimeoutError(
    /** @since 0.3.0-beta01 */
    public val timeout: Duration,
    message: String = "Call timed out after $timeout.",
) : AiSdkException(message)

/** The platform cryptographic entropy source could not be read (native CSPRNG seam). */
internal class SecureRandomUnavailableError(
    message: String = "Secure random source unavailable",
    cause: Throwable? = null,
) : AiSdkException(message, cause)

/** @since 0.3.0-beta01 */
public class InvalidPromptError(
    /** @since 0.3.0-beta01 */
    public val prompt: String?,
    message: String,
    cause: Throwable? = null,
) : AiSdkException("Invalid prompt: $message", cause)

/** @since 0.3.0-beta01 */
public class InvalidResponseDataError(
    /** @since 0.3.0-beta01 */
    public val data: JsonElement?,
    message: String = "Invalid response data: $data.",
) : AiSdkException(message)

/** @since 0.3.0-beta01 */
public class JSONParseError(
    /** @since 0.3.0-beta01 */
    public val text: String,
    cause: Throwable,
) : AiSdkException(
    "JSON parsing failed: Text: $text.\nError message: ${ErrorMessages.of(cause)}",
    cause,
)

/** @since 0.3.0-beta01 */
public class LoadAPIKeyError(message: String) : AiSdkException(message)

/** @since 0.3.0-beta01 */
public class LoadSettingError(message: String) : AiSdkException(message)

/** @since 0.3.0-beta01 */
public class NoContentGeneratedError(message: String = "No content generated.") : AiSdkException(message)

/** @since 0.3.0-beta01 */
public class TooManyEmbeddingValuesForCallError(
    /** @since 0.3.0-beta01 */
    public val provider: String,
    /** @since 0.3.0-beta01 */
    public val modelId: String,
    /** @since 0.3.0-beta01 */
    public val maxEmbeddingsPerCall: Int,
    /** @since 0.3.0-beta01 */
    public val values: List<Any?>,
) : AiSdkException(
    "Too many values for a single embedding call. " +
        "The $provider model \"$modelId\" can only embed up to " +
        "$maxEmbeddingsPerCall values per call, but ${values.size} values were provided.",
)

@Poko
/** @since 0.3.0-beta01 */
public class TypeValidationContext(
    /** @since 0.3.0-beta01 */
    public val field: String? = null,
    /** @since 0.3.0-beta01 */
    public val entityName: String? = null,
    /** @since 0.3.0-beta01 */
    public val entityId: String? = null,
)

/** @since 0.3.0-beta01 */
public class TypeValidationError(
    /** @since 0.3.0-beta01 */
    public val value: JsonElement?,
    cause: Throwable,
    /** @since 0.3.0-beta01 */
    public val context: TypeValidationContext? = null,
) : AiSdkException(buildTypeValidationMessage(value, cause, context), cause) {
    public companion object {
        /** @since 0.3.0-beta01 */
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
            val entityQualifier: String? = buildList {
                context?.entityName?.let { add(it) }
                context?.entityId?.let { add("id: \"$it\"") }
            }.takeIf { it.isNotEmpty() }?.joinToString(", ")?.let { " ($it)" }
            val fieldQualifier: String? = context?.field?.let { " for $it" }
            return "Type validation failed${fieldQualifier.orEmpty()}${entityQualifier.orEmpty()}: Value: $value.\nError message: ${ErrorMessages.of(
                cause
            )}"
        }
    }
}

/** @since 0.3.0-beta01 */
public class UnsupportedFunctionalityError(
    /** @since 0.3.0-beta01 */
    public val functionality: String,
    message: String = "'$functionality' functionality not supported.",
) : AiSdkException(message)

/** @since 0.3.0-beta01 */
public class UnsupportedModelVersionError(
    modelId: String,
    version: String,
) : AiSdkException("Unsupported model version `$version` for `$modelId`")

/** @since 0.3.0-beta01 */
public class NoSuchProviderError(
    /** @since 0.3.0-beta01 */
    public val providerId: String,
    /** @since 0.3.0-beta01 */
    public val availableProviders: List<String> = emptyList(),
) : AiSdkException(
    "No provider registered for `$providerId`" +
        if (availableProviders.isEmpty()) "" else " (available: ${availableProviders.joinToString(", ")})",
)

/** @since 0.3.0-beta01 */
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

/** @since 0.3.0-beta01 */
public class NoOutputGeneratedError(message: String = "No output generated") : AiSdkException(message)

/** @since 0.3.0-beta01 */
public class NoObjectGeneratedError(
    message: String = "No object generated",
    /** @since 0.3.0-beta01 */
    public val text: String? = null,
    /** @since 0.3.0-beta01 */
    public val response: LanguageModelResponseMetadata? = null,
    /** @since 0.3.0-beta01 */
    public val usage: Usage? = null,
    /** @since 0.3.0-beta01 */
    public val finishReason: FinishReason? = null,
    cause: Throwable? = null,
) : AiSdkException(message, cause)

/** @since 0.3.0-beta01 */
public class NoImageGeneratedError(
    message: String = "No image generated",
    /** @since 0.3.0-beta01 */
    public val responses: List<LanguageModelResponseMetadata> = emptyList(),
    cause: Throwable? = null,
) : AiSdkException(message, cause)

/** @since 0.3.0-beta01 */
public class NoSpeechGeneratedError(
    message: String = "No speech generated",
    /** @since 0.3.0-beta01 */
    public val responses: List<LanguageModelResponseMetadata> = emptyList(),
    cause: Throwable? = null,
) : AiSdkException(message, cause)

/** @since 0.3.0-beta01 */
public class NoTranscriptGeneratedError(
    message: String = "No transcript generated",
    /** @since 0.3.0-beta01 */
    public val responses: List<LanguageModelResponseMetadata> = emptyList(),
    cause: Throwable? = null,
) : AiSdkException(message, cause)

/** @since 0.3.0-beta01 */
public class NoVideoGeneratedError(
    message: String = "No video generated",
    /** @since 0.3.0-beta01 */
    public val responses: List<LanguageModelResponseMetadata> = emptyList(),
    cause: Throwable? = null,
) : AiSdkException(message, cause)

/** @since 0.3.0-beta01 */
public class UiMessageStreamError(message: String, cause: Throwable? = null) : AiSdkException(message, cause)

/** @since 0.3.0-beta01 */
public class InvalidStreamPartError(
    /** @since 0.3.0-beta01 */
    public val chunk: String?,
    message: String,
) : AiSdkException(message)

/** @since 0.3.0-beta01 */
public class InvalidToolApprovalError(
    /** @since 0.3.0-beta01 */
    public val approvalId: String,
) : AiSdkException(
    "Tool approval response references unknown approvalId: \"$approvalId\". " +
        "No matching tool-approval-request found in message history.",
)

/** @since 0.3.0-beta01 */
public class InvalidToolInputError(
    /** @since 0.3.0-beta01 */
    public val toolInput: String,
    /** @since 0.3.0-beta01 */
    public val toolName: String,
    cause: Throwable,
    message: String = "Invalid input for tool $toolName: ${ErrorMessages.of(cause)}",
) : AiSdkException(message, cause)

/** @since 0.3.0-beta01 */
public class ToolCallNotFoundForApprovalError(
    /** @since 0.3.0-beta01 */
    public val toolCallId: String,
    /** @since 0.3.0-beta01 */
    public val approvalId: String,
) : AiSdkException("Tool call \"$toolCallId\" not found for approval request \"$approvalId\".")

/** @since 0.3.0-beta01 */
public class MissingToolResultsError(
    /** @since 0.3.0-beta01 */
    public val toolCallIds: List<String>,
) : AiSdkException(
    "Tool result${if (toolCallIds.size > 1) "s are" else " is"} missing for " +
        "tool call${if (toolCallIds.size > 1) "s" else ""} ${toolCallIds.joinToString(", ")}.",
)

/** @since 0.3.0-beta01 */
public class NoSuchToolError(
    /** @since 0.3.0-beta01 */
    public val toolName: String,
    /** @since 0.3.0-beta01 */
    public val availableTools: List<String>? = null,
    message: String = "Model tried to call unavailable tool '$toolName'. " +
        if (availableTools == null) {
            "No tools are available."
        } else {
            "Available tools: ${availableTools.joinToString(", ")}."
        },
) : AiSdkException(message)

/** @since 0.3.0-beta01 */
public class ToolCallRepairError(
    /** @since 0.3.0-beta01 */
    public val originalError: Throwable,
    cause: Throwable,
    message: String = "Error repairing tool call: ${ErrorMessages.of(cause)}",
) : AiSdkException(message, cause)

/** @since 0.3.0-beta01 */
public class InvalidDataContentError(
    /** @since 0.3.0-beta01 */
    public val content: String?,
    cause: Throwable? = null,
    message: String = "Invalid data content. Expected a base64 string, ByteArray, or platform byte buffer, " +
        "but got ${content ?: "null"}.",
) : AiSdkException(message, cause)

/** @since 0.3.0-beta01 */
public class InvalidMessageRoleError(
    /** @since 0.3.0-beta01 */
    public val role: String,
    message: String = "Invalid message role: '$role'. Must be one of: \"system\", \"user\", \"assistant\", \"tool\".",
) : AiSdkException(message)

/** @since 0.3.0-beta01 */
public class MessageConversionError(
    /** @since 0.3.0-beta01 */
    public val originalMessage: String?,
    message: String,
) : AiSdkException(message)

/** @since 0.3.0-beta01 */
public enum class RetryErrorReason {
    MaxRetriesExceeded,
    ErrorNotRetryable,
    Abort,
}

@Poko
/** @since 0.3.0-beta01 */
public class RetryAttemptDetail(
    /** @since 0.3.0-beta01 */
    public val attempt: Int,
    /** @since 0.3.0-beta01 */
    public val error: Throwable,
    /** @since 0.3.0-beta01 */
    public val retryable: Boolean,
    /** @since 0.3.0-beta01 */
    public val delayMs: Long?,
)

/** @since 0.3.0-beta01 */
public class RetryError(
    message: String,
    /** @since 0.3.0-beta01 */
    public val reason: RetryErrorReason,
    /** @since 0.3.0-beta01 */
    public val errors: List<Throwable>,
    /** @since 0.3.0-beta01 */
    public val attempts: List<RetryAttemptDetail> = errors.mapIndexed { index, error ->
        RetryAttemptDetail(
            attempt = index,
            error = error,
            retryable = index < errors.lastIndex,
            delayMs = null,
        )
    },
) : AiSdkException(message, errors.lastOrNull()) {
    /** @since 0.3.0-beta01 */
    public val lastError: Throwable? = errors.lastOrNull()
}
