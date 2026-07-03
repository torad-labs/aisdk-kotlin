package ai.torad.aisdk.protocol

import ai.torad.aisdk.WireDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

internal object ProtocolJson {
    fun JsonObject.stringFromAny(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key -> (this[key] as? JsonPrimitive)?.contentOrNull }

    fun requiredOneOfString(
        obj: JsonObject,
        provider: String,
        operation: String,
        vararg keys: String,
    ): String =
        WireDecoder.requiredOneOfString(obj, provider, operation, "$", *keys)

    fun intOrNull(obj: JsonObject, vararg keys: String): Int? =
        keys.firstNotNullOfOrNull { key -> (obj[key] as? JsonPrimitive)?.intOrNull }
}
