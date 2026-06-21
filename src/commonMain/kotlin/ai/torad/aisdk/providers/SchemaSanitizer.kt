package ai.torad.aisdk.providers

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

internal object SchemaSanitizer {
    fun stripUnsupportedSchemaKeys(
        schema: JsonElement,
        dropAdditionalProperties: Boolean,
    ): JsonElement = when (schema) {
        is JsonArray -> JsonArray(schema.map { SchemaSanitizer.stripUnsupportedSchemaKeys(it, dropAdditionalProperties) })
        is JsonObject -> buildJsonObject {
            for ((key, value) in schema) {
                when {
                    key == "\$schema" || key == "title" -> Unit
                    dropAdditionalProperties && key == "additionalProperties" -> Unit
                    else -> put(key, SchemaSanitizer.stripUnsupportedSchemaKeys(value, dropAdditionalProperties))
                }
            }
        }
        else -> schema
    }
}
