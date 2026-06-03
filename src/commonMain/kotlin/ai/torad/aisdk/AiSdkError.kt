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
) : AiSdkException(
    buildString {
        append("No ")
        append(modelType)
        append(" model registered for `")
        providerId?.let { append(it).append(":") }
        append(modelId)
        append("`")
    },
)

class NoOutputGeneratedError(message: String = "No output generated") : AiSdkException(message)
class NoObjectGeneratedError(message: String = "No object generated") : AiSdkException(message)
class NoImageGeneratedError(message: String = "No image generated") : AiSdkException(message)
class NoSpeechGeneratedError(message: String = "No speech generated") : AiSdkException(message)
class NoTranscriptGeneratedError(message: String = "No transcript generated") : AiSdkException(message)
class NoVideoGeneratedError(message: String = "No video generated") : AiSdkException(message)
class UiMessageStreamError(message: String, cause: Throwable? = null) : AiSdkException(message, cause)
