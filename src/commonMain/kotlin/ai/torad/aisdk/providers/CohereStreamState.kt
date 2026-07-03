@file:OptIn(ai.torad.aisdk.LowLevelLanguageModelApi::class)

package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

internal class CohereChatStreamState(
    private val parseToolInput: (String?) -> JsonElement,
    private val parseUsage: (JsonObject?) -> Usage,
    private val parseFinishReason: (String?) -> FinishReason,
) {
    private enum class ContentKind { Text, Reasoning }

    private class PendingToolCall(
        val id: String,
        val name: String,
        var arguments: String,
    )

    private val contentKinds = mutableMapOf<Int, ContentKind>()
    private val pendingToolCallOccurrences = mutableMapOf<Int, PendingToolCall>()
    private var finishReason: FinishReason = FinishReason.Other
    private var rawFinishReason: String? = null
    private var usage: Usage = Usage()

    fun accept(value: JsonObject): List<StreamEvent> =
        when ((value["type"] as? JsonPrimitive)?.contentOrNull) {
            "message-start" -> acceptMessageStart(value)
            "content-start" -> acceptContentStart(value)
            "content-delta" -> acceptContentDelta(value)
            "content-end" -> acceptContentEnd(value)
            "tool-call-start" -> acceptToolCallStart(value)
            "tool-call-delta" -> acceptToolCallDelta(value)
            "tool-call-end" -> acceptToolCallEnd(value)
            "message-end" -> acceptMessageEnd(value)
            else -> listOf(StreamEvent.Raw(value))
        }

    fun markError() {
        finishReason = FinishReason.Error
    }

    fun finish(): List<StreamEvent> = listOf(
        StreamEvent.Finish(
            totalSteps = 1,
            finishReason = finishReason,
            usage = usage,
            rawFinishReason = rawFinishReason,
        ),
    )

    private fun acceptMessageStart(value: JsonObject): List<StreamEvent> {
        val id = (value["id"] as? JsonPrimitive)?.contentOrNull ?: return emptyList()
        return listOf(StreamEvent.ResponseMetadata(id = id))
    }

    private fun acceptContentStart(value: JsonObject): List<StreamEvent> {
        val index = streamIndex(value)
        val content = streamContent(value) ?: return emptyList()
        val id = index.toString()
        return if ((content["type"] as? JsonPrimitive)?.contentOrNull == "thinking") {
            contentKinds[index] = ContentKind.Reasoning
            listOf(StreamEvent.ReasoningStart(id))
        } else {
            contentKinds[index] = ContentKind.Text
            listOf(StreamEvent.TextStart(id))
        }
    }

    private fun acceptContentDelta(value: JsonObject): List<StreamEvent> {
        val index = streamIndex(value)
        val content = streamContent(value) ?: return emptyList()
        val id = index.toString()
        val thinking = (content["thinking"] as? JsonPrimitive)?.contentOrNull
        if (thinking != null) return listOf(StreamEvent.ReasoningDelta(id, thinking))
        val text = (content["text"] as? JsonPrimitive)?.contentOrNull ?: return emptyList()
        return listOf(StreamEvent.TextDelta(id, text))
    }

    private fun acceptContentEnd(value: JsonObject): List<StreamEvent> {
        val index = streamIndex(value)
        val id = index.toString()
        return when (contentKinds.remove(index)) {
            ContentKind.Reasoning -> listOf(StreamEvent.ReasoningEnd(id))
            ContentKind.Text,
            null,
            -> listOf(StreamEvent.TextEnd(id))
        }
    }

    private fun acceptToolCallStart(value: JsonObject): List<StreamEvent> {
        val index = streamIndex(value)
        val toolCall = streamToolCall(value) ?: return emptyList()
        val function = JsonAccess.obj(toolCall, "function") ?: JsonObject(emptyMap())
        val id = (toolCall["id"] as? JsonPrimitive)?.contentOrNull ?: IdGenerator.generate("call")
        val name = (function["name"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val arguments = (function["arguments"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        pendingToolCallOccurrences[index] = PendingToolCall(id = id, name = name, arguments = arguments)
        return buildList {
            add(StreamEvent.ToolInputStart(id, name))
            if (arguments.isNotEmpty()) add(StreamEvent.ToolInputDelta(id, arguments))
        }
    }

    private fun acceptToolCallDelta(value: JsonObject): List<StreamEvent> {
        val pending = pendingToolCallOccurrences[streamIndex(value)] ?: return emptyList()
        val function = JsonAccess.obj(streamToolCall(value), "function") ?: return emptyList()
        val delta = (function["arguments"] as? JsonPrimitive)?.contentOrNull ?: return emptyList()
        pending.arguments += delta
        return listOf(StreamEvent.ToolInputDelta(pending.id, delta))
    }

    private fun acceptToolCallEnd(value: JsonObject): List<StreamEvent> {
        val pending = pendingToolCallOccurrences.remove(streamIndex(value)) ?: return emptyList()
        return listOf(
            StreamEvent.ToolInputEnd(pending.id),
            StreamEvent.ToolCall(
                toolCallId = pending.id,
                toolName = pending.name,
                inputJson = parseToolInput(pending.arguments.trim().ifBlank { "{}" }),
            ),
        )
    }

    private fun acceptMessageEnd(value: JsonObject): List<StreamEvent> {
        val delta = JsonAccess.obj(value, "delta") ?: JsonObject(emptyMap())
        rawFinishReason = (delta["finish_reason"] as? JsonPrimitive)?.contentOrNull
        finishReason = parseFinishReason(rawFinishReason)
        usage = parseUsage(JsonAccess.obj(delta, "usage"))
        return emptyList()
    }

    private fun streamIndex(value: JsonObject): Int =
        (value["index"] as? JsonPrimitive)?.intOrNull ?: 0

    private fun streamContent(value: JsonObject): JsonObject? {
        val delta = JsonAccess.obj(value, "delta")
        val message = JsonAccess.obj(delta, "message")
        return JsonAccess.obj(message, "content")
    }

    private fun streamToolCall(value: JsonObject): JsonObject? {
        val delta = JsonAccess.obj(value, "delta")
        val message = JsonAccess.obj(delta, "message")
        return JsonAccess.obj(message, "tool_calls")
    }
}
