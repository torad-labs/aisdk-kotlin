package ai.torad.aisdk

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

internal object JsonAccess {
    fun obj(container: JsonObject?, key: String): JsonObject? =
        objectValue(container?.get(key))

    fun obj(container: Map<String, JsonElement>?, key: String): JsonObject? =
        objectValue(container?.get(key))

    fun arr(container: JsonObject?, key: String): JsonArray? =
        arrayValue(container?.get(key))

    fun arr(container: Map<String, JsonElement>?, key: String): JsonArray? =
        arrayValue(container?.get(key))

    private fun objectValue(value: JsonElement?): JsonObject? = when (value) {
        is JsonObject -> value
        else -> null
    }

    private fun arrayValue(value: JsonElement?): JsonArray? = when (value) {
        is JsonArray -> value
        else -> null
    }
}
