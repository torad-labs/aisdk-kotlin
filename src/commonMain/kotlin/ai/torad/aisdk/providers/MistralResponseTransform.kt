package ai.torad.aisdk.providers

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal fun mistralTransformChatResponse(body: JsonObject): JsonObject {
    val choices = body["choices"] as? JsonArray
    return if (choices == null) {
        body
    } else {
        JsonObject(body + ("choices" to JsonArray(choices.map(::mistralTransformResponseChoice))))
    }
}

private fun mistralTransformResponseChoice(choice: JsonElement): JsonElement {
    val obj = choice as? JsonObject
    val updates = obj?.let {
        buildMap<String, JsonElement> {
            (it["message"] as? JsonObject)?.let(::mistralTransformResponseContent)?.let { message ->
                put("message", message)
            }
            (it["delta"] as? JsonObject)?.let(::mistralTransformResponseContent)?.let { delta ->
                put("delta", delta)
            }
        }
    }.orEmpty()

    return if (obj == null || updates.isEmpty()) choice else JsonObject(obj + updates)
}

private fun mistralTransformResponseContent(value: JsonObject): JsonObject {
    val content = value["content"] as? JsonArray
    return if (content == null) {
        value
    } else {
        val text = mistralTextContent(content)
        val reasoning = mistralReasoningContent(content)
        val transformed = value.toMutableMap()
        if (text.isEmpty()) {
            transformed.remove("content")
        } else {
            transformed["content"] = JsonPrimitive(text)
        }
        if (reasoning.isNotEmpty()) transformed["reasoning_content"] = JsonPrimitive(reasoning)
        JsonObject(transformed)
    }
}

private fun mistralTextContent(content: JsonArray): String =
    content.mapNotNull { part ->
        val obj = part as? JsonObject ?: return@mapNotNull null
        obj.takeIf { (it["type"] as? JsonPrimitive)?.contentOrNull == "text" }
            ?.get("text")
            ?.let { it as? JsonPrimitive }
            ?.contentOrNull
    }.joinToString("")

private fun mistralReasoningContent(content: JsonArray): String =
    content.joinToString("") { part ->
        val obj = part as? JsonObject
        if ((obj?.get("type") as? JsonPrimitive)?.contentOrNull == "thinking") {
            mistralThinkingText(obj["thinking"])
        } else {
            ""
        }
    }

private fun mistralThinkingText(value: JsonElement?): String =
    (value as? JsonArray).orEmpty().mapNotNull { chunk ->
        val obj = chunk as? JsonObject ?: return@mapNotNull null
        obj.takeIf { (it["type"] as? JsonPrimitive)?.contentOrNull == "text" }
            ?.get("text")
            ?.let { it as? JsonPrimitive }
            ?.contentOrNull
    }.joinToString("")
