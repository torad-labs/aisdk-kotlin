package ai.torad.aisdk.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object UiMessageChunkJson {
    fun jsonChunk(type: String, body: JsonObjectBuilder.() -> Unit = {}): JsonObject =
        buildJsonObject {
            put("type", type)
            body()
        }
}
