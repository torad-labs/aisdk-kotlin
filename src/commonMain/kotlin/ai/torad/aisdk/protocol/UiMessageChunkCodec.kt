package ai.torad.aisdk.protocol

import ai.torad.aisdk.StreamEvent
import ai.torad.aisdk.protocol.UiMessageChunkJson.jsonChunk
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put

internal object UiMessageChunkCodec {
    fun chunk(event: StreamEvent): JsonObject? =
        lifecycle(event)
            ?: text(event)
            ?: reasoning(event)
            ?: UiMediaMessageChunkCodec.chunk(event)
            ?: UiToolMessageChunkCodec.chunk(event)
            ?: UiTerminalMessageChunkCodec.chunk(event)

    private fun lifecycle(event: StreamEvent): JsonObject? = when (event) {
        is StreamEvent.StreamStart -> jsonChunk("start")
        is StreamEvent.StepStart -> jsonChunk("start-step") {
            put("stepNumber", event.stepNumber)
        }
        else -> null
    }

    private fun text(event: StreamEvent): JsonObject? = when (event) {
        is StreamEvent.TextStart -> jsonChunk("text-start") { put("id", event.id) }
        is StreamEvent.TextDelta -> jsonChunk("text-delta") {
            put("id", event.id)
            put("delta", event.text)
        }
        is StreamEvent.TextEnd -> jsonChunk("text-end") { put("id", event.id) }
        else -> null
    }

    private fun reasoning(event: StreamEvent): JsonObject? = when (event) {
        is StreamEvent.ReasoningStart -> jsonChunk("reasoning-start") { put("id", event.id) }
        is StreamEvent.ReasoningDelta -> jsonChunk("reasoning-delta") {
            put("id", event.id)
            put("delta", event.text)
        }
        is StreamEvent.ReasoningEnd -> jsonChunk("reasoning-end") { put("id", event.id) }
        else -> null
    }
}
