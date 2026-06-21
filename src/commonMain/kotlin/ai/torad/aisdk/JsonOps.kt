package ai.torad.aisdk

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

internal object JsonOps {

    fun merge(vararg objects: JsonObject): JsonObject {
        val merged = linkedMapOf<String, JsonElement>()
        for (obj in objects) {
            for ((key, value) in obj) {
                val prior = merged[key]
                merged[key] = if (prior is JsonObject && value is JsonObject) merge(prior, value) else value
            }
        }
        return JsonObject(merged)
    }

    fun deepMerge(base: JsonObject, override: JsonObject): JsonObject {
        val merged = base.toMutableMap()
        for ((key, value) in override) {
            val existing = merged[key]
            merged[key] = if (existing is JsonObject && value is JsonObject) deepMerge(existing, value) else value
        }
        return JsonObject(merged)
    }

    fun mergeProviderOptions(
        defaults: Map<String, JsonElement>,
        overrides: Map<String, JsonElement>,
    ): Map<String, JsonElement> {
        val merged = defaults.toMutableMap()
        for ((key, value) in overrides) {
            val existing = merged[key]
            merged[key] = if (existing is JsonObject && value is JsonObject) deepMerge(existing, value) else value
        }
        return merged
    }

    fun isDeepEqual(left: JsonElement?, right: JsonElement?): Boolean = when {
        left === right -> true
        left == null || right == null -> false
        left is JsonNull && right is JsonNull -> true
        left is JsonPrimitive && right is JsonPrimitive -> primitiveEquals(left, right)
        left is JsonArray && right is JsonArray ->
            left.size == right.size && left.indices.all { isDeepEqual(left[it], right[it]) }
        left is JsonObject && right is JsonObject ->
            left.keys == right.keys && left.keys.all { key -> isDeepEqual(left[key], right[key]) }
        else -> false
    }

    fun removeUndefinedEntries(values: Map<String, JsonElement?>): Map<String, JsonElement> =
        buildMap { values.forEach { (k, v) -> if (v != null) put(k, v) } }

    private fun primitiveEquals(left: JsonPrimitive, right: JsonPrimitive): Boolean {
        val l = left.jsonPrimitive
        val r = right.jsonPrimitive
        return when {
            l.booleanOrNull != null || r.booleanOrNull != null -> l.booleanOrNull == r.booleanOrNull
            l.doubleOrNull != null || r.doubleOrNull != null -> l.doubleOrNull == r.doubleOrNull
            else -> l.content == r.content
        }
    }
}
