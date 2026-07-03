package ai.torad.aisdk.providers

import ai.torad.aisdk.JsonAccess
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

internal object AnthropicRequestJson {
    fun container(options: JsonObject): JsonElement? {
        val container = JsonAccess.obj(options, "container") ?: return null
        val skills = JsonAccess.arr(container, "skills")
        return if (skills == null || skills.isEmpty()) {
            container["id"]
        } else {
            buildJsonObject {
                container["id"]?.let { put("id", it) }
                put("skills", JsonArray(skills.map { skill -> PreparedAnthropicRequest.camelToSnakeJson(skill) }))
            }
        }
    }
}
