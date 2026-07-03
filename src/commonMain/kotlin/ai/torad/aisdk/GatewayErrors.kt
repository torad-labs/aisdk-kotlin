package ai.torad.aisdk

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** @since 0.3.0-beta01 */
public open class GatewayError(
    message: String,
    /** @since 0.3.0-beta01 */
    public val statusCode: Int = 500,
    /** @since 0.3.0-beta01 */
    public val type: String = "gateway_error",
    /** @since 0.3.0-beta01 */
    public val generationId: String? = null,
    cause: Throwable? = null,
    /** @since 0.3.0-beta01 */
    public val isRetryable: Boolean = statusCode == 408 || statusCode == 409 || statusCode == 429 || statusCode >= 500,
) : AiSdkException(if (generationId == null) message else "$message [$generationId]", cause) {
    internal companion object {
        internal fun fromResponse(statusCode: Int, raw: String): GatewayError {
            val parsed = runCatching { aiSdkJson.parseToJsonElement(raw).jsonObject }.getOrNull()
            val error = (parsed?.get("error") as? JsonObject)
            val type = (error?.get("type") as? JsonPrimitive)?.contentOrNull
            val message = (error?.get("message") as? JsonPrimitive)?.contentOrNull
                ?: raw.ifBlank { "Gateway request failed" }
            val generationId = (parsed?.get("generationId") as? JsonPrimitive)?.contentOrNull
            return when (type) {
                "authentication_error" -> GatewayAuthenticationError(message, statusCode, generationId)
                "invalid_request_error" -> GatewayInvalidRequestError(message, statusCode, generationId)
                "rate_limit_exceeded" -> GatewayRateLimitError(message, statusCode, generationId)
                "model_not_found" -> GatewayModelNotFoundError(message, statusCode, generationId = generationId)
                "internal_server_error" -> GatewayInternalServerError(message, statusCode, generationId)
                else -> GatewayResponseError(
                    message = message,
                    statusCode = statusCode,
                    response = parsed,
                    generationId = generationId,
                )
            }
        }
    }
}

/** @since 0.3.0-beta01 */
public class GatewayAuthenticationError(
    message: String = "Authentication failed",
    statusCode: Int = 401,
    generationId: String? = null,
    cause: Throwable? = null,
) : GatewayError(message, statusCode, "authentication_error", generationId, cause)

/** @since 0.3.0-beta01 */
public class GatewayInvalidRequestError(
    message: String = "Invalid request",
    statusCode: Int = 400,
    generationId: String? = null,
    cause: Throwable? = null,
) : GatewayError(message, statusCode, "invalid_request_error", generationId, cause)

/** @since 0.3.0-beta01 */
public class GatewayRateLimitError(
    message: String = "Rate limit exceeded",
    statusCode: Int = 429,
    generationId: String? = null,
    cause: Throwable? = null,
) : GatewayError(message, statusCode, "rate_limit_exceeded", generationId, cause)

/** @since 0.3.0-beta01 */
public class GatewayModelNotFoundError(
    message: String = "Model not found",
    statusCode: Int = 404,
    /** @since 0.3.0-beta01 */
    public val modelId: String? = null,
    generationId: String? = null,
    cause: Throwable? = null,
) : GatewayError(message, statusCode, "model_not_found", generationId, cause)

/** @since 0.3.0-beta01 */
public class GatewayInternalServerError(
    message: String = "Internal server error",
    statusCode: Int = 500,
    generationId: String? = null,
    cause: Throwable? = null,
) : GatewayError(message, statusCode, "internal_server_error", generationId, cause)

/** @since 0.3.0-beta01 */
public class GatewayResponseError(
    message: String = "Invalid response from Gateway",
    statusCode: Int = 502,
    /** @since 0.3.0-beta01 */
    public val response: JsonElement? = null,
    generationId: String? = null,
    cause: Throwable? = null,
) : GatewayError(message, statusCode, "response_error", generationId, cause)

/** @since 0.3.0-beta01 */
public class GatewayTimeoutError(
    message: String = "Gateway request timed out",
    statusCode: Int = 408,
    generationId: String? = null,
    cause: Throwable? = null,
) : GatewayError(message, statusCode, "timeout_error", generationId, cause)
