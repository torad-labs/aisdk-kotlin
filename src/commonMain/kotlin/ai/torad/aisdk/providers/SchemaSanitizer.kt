package ai.torad.aisdk.providers

import ai.torad.aisdk.JsonAccess
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

internal object SchemaSanitizer {
    enum class Target {
        Standard,
        GoogleOpenApi,
    }

    fun stripUnsupportedSchemaKeys(
        schema: JsonElement,
        dropAdditionalProperties: Boolean,
        target: Target = Target.Standard,
    ): JsonElement = when (target) {
        Target.Standard -> when (schema) {
            is JsonArray -> JsonArray(
                schema.map { SchemaSanitizer.stripUnsupportedSchemaKeys(it, dropAdditionalProperties, target) }
            )
            is JsonObject -> buildJsonObject {
                for ((key, value) in schema) {
                    when {
                        key == "\$schema" || key == "title" -> Unit
                        dropAdditionalProperties && key == "additionalProperties" -> Unit
                        else -> put(key, SchemaSanitizer.stripUnsupportedSchemaKeys(value, dropAdditionalProperties, target))
                    }
                }
            }
            else -> schema
        }
        Target.GoogleOpenApi -> when (schema) {
            is JsonArray -> JsonArray(schema.map {
                SchemaSanitizer.stripUnsupportedSchemaKeys(it, dropAdditionalProperties, Target.GoogleOpenApi)
            })
            is JsonObject -> buildJsonObject {
                val obj = schema
                for (key in listOf("description", "required", "format", "enum", "minimum", "maximum", "minLength", "maxLength", "minItems", "maxItems")) {
                    obj[key]?.let { put(key, it) }
                }
                if ("enum" !in obj) obj["const"]?.let { put("enum", JsonArray(listOf(it))) }
                when (val type = obj["type"]) {
                    is JsonArray -> {
                        val nonNullTypes = type.filter { (it as? JsonPrimitive)?.contentOrNull != "null" }
                        when (nonNullTypes.size) {
                            0 -> Unit
                            1 -> put("type", nonNullTypes.single())
                            else -> put(
                                "anyOf",
                                JsonArray(nonNullTypes.map { singleType -> buildJsonObject { put("type", singleType) } }),
                            )
                        }
                        if (nonNullTypes.size != type.size) put("nullable", JsonPrimitive(true))
                    }
                    null -> Unit
                    else -> put("type", type)
                }
                JsonAccess.obj(obj, "properties")?.let { props ->
                    put(
                        "properties",
                        buildJsonObject {
                            props.forEach { (key, value) ->
                                put(
                                    key,
                                    SchemaSanitizer.stripUnsupportedSchemaKeys(
                                        value,
                                        dropAdditionalProperties,
                                        Target.GoogleOpenApi,
                                    )
                                )
                            }
                        },
                    )
                }
                obj["items"]?.let {
                    put(
                        "items",
                        SchemaSanitizer.stripUnsupportedSchemaKeys(it, dropAdditionalProperties, Target.GoogleOpenApi)
                    )
                }
                for (combiner in listOf("allOf", "anyOf", "oneOf")) {
                    JsonAccess.arr(obj, combiner)?.let { arr ->
                        if (combiner != "anyOf" && combiner != "oneOf") {
                            put(combiner, JsonArray(arr.map {
                                SchemaSanitizer.stripUnsupportedSchemaKeys(
                                    it,
                                    dropAdditionalProperties,
                                    Target.GoogleOpenApi,
                                )
                            }))
                        } else {
                            val nonNullSchemas = arr.filterNot { ((it as? JsonObject)?.get("type") as? JsonPrimitive)?.contentOrNull == "null" }
                            if (nonNullSchemas.size == arr.size) {
                                put(combiner, JsonArray(arr.map {
                                    SchemaSanitizer.stripUnsupportedSchemaKeys(
                                        it,
                                        dropAdditionalProperties,
                                        Target.GoogleOpenApi,
                                    )
                                }))
                            } else {
                                if (nonNullSchemas.size == 1) {
                                    when (val converted = SchemaSanitizer.stripUnsupportedSchemaKeys(
                                        nonNullSchemas.single(),
                                        dropAdditionalProperties,
                                        Target.GoogleOpenApi,
                                    )) {
                                        is JsonObject -> converted.forEach { (key, value) -> put(key, value) }
                                        else -> put(combiner, JsonArray(listOf(converted)))
                                    }
                                } else if (nonNullSchemas.isNotEmpty()) {
                                    put(
                                        combiner,
                                        JsonArray(nonNullSchemas.map {
                                            SchemaSanitizer.stripUnsupportedSchemaKeys(
                                                it,
                                                dropAdditionalProperties,
                                                Target.GoogleOpenApi,
                                            )
                                        }),
                                    )
                                }
                                put("nullable", JsonPrimitive(true))
                            }
                        }
                    }
                }
            }
            else -> schema
        }
    }
}
