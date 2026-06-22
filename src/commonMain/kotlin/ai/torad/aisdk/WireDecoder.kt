package ai.torad.aisdk

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

public class WireDecodeException(
    public val provider: String,
    public val operation: String,
    public val path: String,
    message: String,
    public val value: JsonElement? = null,
    cause: Throwable? = null,
) : AiSdkException(
    "Invalid $provider wire data for $operation at $path: $message" +
        value?.let { " Value: $it." }.orEmpty(),
    cause,
)

internal object WireDecoder {
    val json: Json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        encodeDefaults = true
        explicitNulls = false
    }

    fun parse(text: String, provider: String, operation: String, path: String = "$"): JsonElement =
        try {
            json.parseToJsonElement(text)
        } catch (error: SerializationException) {
            fail(provider, operation, path, "expected valid JSON", cause = error)
        } catch (error: IllegalArgumentException) {
            fail(provider, operation, path, "expected valid JSON", cause = error)
        }

    fun parseObject(text: String, provider: String, operation: String, path: String = "$"): JsonObject =
        objectValue(parse(text, provider, operation, path), provider, operation, path)

    fun <T> decode(
        serializer: KSerializer<T>,
        value: JsonElement,
        provider: String,
        operation: String,
        path: String = "$",
    ): T =
        try {
            json.decodeFromJsonElement(serializer, value)
        } catch (error: SerializationException) {
            fail(provider, operation, path, "schema decode failed: ${ErrorMessages.of(error)}", value, error)
        } catch (error: IllegalArgumentException) {
            fail(provider, operation, path, "schema decode failed: ${ErrorMessages.of(error)}", value, error)
        }

    fun required(obj: JsonObject, key: String, provider: String, operation: String, path: String = "$"): JsonElement =
        obj[key] ?: fail(provider, operation, child(path, key), "missing required field")

    fun objectValue(value: JsonElement, provider: String, operation: String, path: String = "$"): JsonObject =
        value as? JsonObject ?: fail(provider, operation, path, "expected object", value)

    fun arrayValue(value: JsonElement, provider: String, operation: String, path: String = "$"): JsonArray =
        value as? JsonArray ?: fail(provider, operation, path, "expected array", value)

    fun requiredArray(obj: JsonObject, key: String, provider: String, operation: String, path: String = "$"): JsonArray =
        arrayValue(required(obj, key, provider, operation, path), provider, operation, child(path, key))

    fun optionalArray(obj: JsonObject, key: String, provider: String, operation: String, path: String = "$"): JsonArray? =
        obj[key]?.let { arrayValue(it, provider, operation, child(path, key)) }

    fun requiredString(obj: JsonObject, key: String, provider: String, operation: String, path: String = "$"): String =
        stringValue(required(obj, key, provider, operation, path), provider, operation, child(path, key))

    fun optionalString(obj: JsonObject, key: String, provider: String, operation: String, path: String = "$"): String? =
        obj[key]?.let { stringValue(it, provider, operation, child(path, key)) }

    fun requiredOneOfString(
        obj: JsonObject,
        provider: String,
        operation: String,
        path: String = "$",
        vararg keys: String,
    ): String =
        keys.firstNotNullOfOrNull { key -> optionalString(obj, key, provider, operation, path) }
            ?: fail(provider, operation, path, "missing one required field: ${keys.joinToString(" or ")}")

    fun stringValue(value: JsonElement, provider: String, operation: String, path: String = "$"): String =
        (value as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: fail(provider, operation, path, "expected string", value)

    fun optionalBoolean(obj: JsonObject, key: String, provider: String, operation: String, path: String = "$"): Boolean? =
        obj[key]?.let { booleanValue(it, provider, operation, child(path, key)) }

    fun booleanValue(value: JsonElement, provider: String, operation: String, path: String = "$"): Boolean =
        (value as? JsonPrimitive)?.booleanOrNull
            ?: fail(provider, operation, path, "expected boolean", value)

    fun optionalInt(obj: JsonObject, key: String, provider: String, operation: String, path: String = "$"): Int? =
        obj[key]?.let { intValue(it, provider, operation, child(path, key)) }

    fun intValue(value: JsonElement, provider: String, operation: String, path: String = "$"): Int =
        (value as? JsonPrimitive)?.intOrNull
            ?: fail(provider, operation, path, "expected integer", value)

    fun optionalDouble(obj: JsonObject, key: String, provider: String, operation: String, path: String = "$"): Double? =
        obj[key]?.let { doubleValue(it, provider, operation, child(path, key)) }

    fun doubleValue(value: JsonElement, provider: String, operation: String, path: String = "$"): Double =
        (value as? JsonPrimitive)?.doubleOrNull
            ?: fail(provider, operation, path, "expected number", value)

    fun floatValue(value: JsonElement, provider: String, operation: String, path: String = "$"): Float =
        (value as? JsonPrimitive)?.floatOrNull
            ?: fail(provider, operation, path, "expected number", value)

    fun optionalFloat(obj: JsonObject, key: String, provider: String, operation: String, path: String = "$"): Float? =
        obj[key]?.let { floatValue(it, provider, operation, child(path, key)) }

    fun embeddingFloat(value: JsonElement, provider: String, operation: String = "embedding response", path: String = "$"): Float =
        floatValue(value, provider, operation, path)

    fun embeddingVector(value: JsonElement, provider: String, operation: String = "embedding response", path: String = "$"): List<Float> =
        arrayValue(value, provider, operation, path).mapIndexed { index, item ->
            WireDecoder.embeddingFloat(item, provider, operation, "$path[$index]")
        }

    fun child(path: String, key: String): String = if (path == "$") "$.$key" else "$path.$key"

    fun fail(
        provider: String,
        operation: String,
        path: String,
        message: String,
        value: JsonElement? = null,
        cause: Throwable? = null,
    ): Nothing = throw WireDecodeException(provider, operation, path, message, value, cause)
}
