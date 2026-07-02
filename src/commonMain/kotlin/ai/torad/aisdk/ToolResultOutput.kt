package ai.torad.aisdk

import dev.drewhamilton.poko.Poko
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject

/**
 * Structured return type for [Tool.toModelOutput].
 * @since 0.3.0-beta01
 */
@Serializable
public sealed class ToolResultOutput {
    @Serializable
    @SerialName("text")
    @Poko
    /** @since 0.3.0-beta01 */
    public class Text(public val text: String) : ToolResultOutput()

    @Serializable
    @SerialName("json")
    @Poko
    /** @since 0.3.0-beta01 */
    public class Json(public val json: JsonElement) : ToolResultOutput()

    @Serializable
    @SerialName("error")
    @Poko
    /** @since 0.3.0-beta01 */
    public class Error(public val message: String) : ToolResultOutput()

    @Serializable
    @SerialName("error-json")
    @Poko
    /** @since 0.3.0-beta01 */
    public class ErrorJson(public val json: JsonElement) : ToolResultOutput()

    @Serializable
    @SerialName("execution-denied")
    @Poko
    /** @since 0.3.0-beta01 */
    public class ExecutionDenied(public val reason: String? = null) : ToolResultOutput()

    @Serializable
    @SerialName("content")
    @Poko
    /** @since 0.3.0-beta01 */
    public class Content(
        /** @since 0.3.0-beta01 */
        public val value: List<JsonElement>,
        /** @since 0.3.0-beta01 */
        public val isError: Boolean = false,
    ) : ToolResultOutput()
}

/** @since 0.3.0-beta01 */
public object ToolResultOutputs {
    internal fun toolResultOutputFromJson(json: JsonElement): ToolResultOutput =
        if (json is JsonPrimitive && json.isString) {
            ToolResultOutput.Text(json.content)
        } else {
            ToolResultOutput.Json(json)
        }

    internal fun toolResultOutputFromWire(json: JsonElement): ToolResultOutput {
        val obj = json as? JsonObject ?: return ToolResultOutputs.toolResultOutputFromJson(json)
        val type = stringFieldOrNull(obj, "type") ?: return ToolResultOutputs.toolResultOutputFromJson(json)
        // Decode only when the object matches the exact shape toJsonElement() emits for this tag.
        // modelVisible is often a RAW success output (it defaults to the tool's output), so a payload
        // that merely collides on `type` — e.g. a tool returning {"type":"text", ...} or {"type":"json"}
        // with no matching `value` — carries no companion field and falls through to be preserved
        // verbatim as Json, instead of throwing (a hard model-call failure) or silently dropping data.
        // This keeps toJsonElement()/toolResultOutputFromWire() inverse for every variant.
        return taggedOutputOrNull(obj, type) ?: ToolResultOutputs.toolResultOutputFromJson(json)
    }

    private fun taggedOutputOrNull(obj: JsonObject, type: String): ToolResultOutput? = when (type) {
        "text" -> stringFieldOrNull(obj, "value")?.let { ToolResultOutput.Text(it) }
        "json" -> obj["value"]?.let { ToolResultOutput.Json(it) }
        "error-text" -> stringFieldOrNull(obj, "value")?.let { ToolResultOutput.Error(it) }
        "error-json" -> obj["value"]?.let { ToolResultOutput.ErrorJson(it) }
        "execution-denied" -> executionDeniedOrNull(obj)
        "content" -> contentOutputOrNull(obj)
        else -> null
    }

    private fun contentOutputOrNull(obj: JsonObject): ToolResultOutput.Content? =
        (JsonAccess.arr(obj, "value"))?.let { value ->
            ToolResultOutput.Content(
                value = value.toList(),
                isError = (obj["isError"] as? JsonPrimitive)?.booleanOrNull ?: false,
            )
        }

    private fun stringFieldOrNull(obj: JsonObject, key: String): String? =
        (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun executionDeniedOrNull(obj: JsonObject): ToolResultOutput.ExecutionDenied? {
        // Exactly {"type":"execution-denied"} optionally plus a string "reason"; any other field
        // means this is a raw success that merely collides on the discriminator, so it is preserved
        // verbatim rather than masquerading as a denial.
        val foreignKey = obj.keys.any { it != "type" && it != "reason" }
        val malformedReason = obj.containsKey("reason") && stringFieldOrNull(obj, "reason") == null
        if (foreignKey || malformedReason) return null
        return ToolResultOutput.ExecutionDenied(stringFieldOrNull(obj, "reason"))
    }

    /** @since 0.3.0-beta01 */
    public fun ToolResultOutput.isToolResultError(): Boolean = when (this) {
        is ToolResultOutput.Error,
        is ToolResultOutput.ErrorJson,
        is ToolResultOutput.ExecutionDenied -> true
        is ToolResultOutput.Content -> isError
        is ToolResultOutput.Text,
        is ToolResultOutput.Json -> false
    }

    /** @since 0.3.0-beta01 */
    public fun ToolResultOutput.toJsonElement(): JsonElement = when (this) {
        is ToolResultOutput.Text -> JsonPrimitive(text)
        is ToolResultOutput.Json -> json
        // Typed discriminators so the three error/denial subtypes round-trip through
        // toolResultOutputFromWire() (which keys on `type`). Bare values fell through
        // its fallback and lost their error/denial identity (and baked-in "Error: ").
        is ToolResultOutput.Error -> buildJsonObject {
            put("type", JsonPrimitive("error-text"))
            put("value", JsonPrimitive(message))
        }
        is ToolResultOutput.ErrorJson -> buildJsonObject {
            put("type", JsonPrimitive("error-json"))
            put("value", json)
        }
        is ToolResultOutput.ExecutionDenied -> buildJsonObject {
            put("type", JsonPrimitive("execution-denied"))
            reason?.let { put("reason", JsonPrimitive(it)) }
        }
        is ToolResultOutput.Content -> buildJsonObject {
            put("type", JsonPrimitive("content"))
            put("value", JsonArray(value))
            if (isError) {
                put("isError", JsonPrimitive(true))
            }
        }
    }
}
