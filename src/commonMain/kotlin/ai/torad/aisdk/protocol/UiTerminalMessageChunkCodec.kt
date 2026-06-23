package ai.torad.aisdk.protocol

import ai.torad.aisdk.FinishReason
import ai.torad.aisdk.StreamEvent
import ai.torad.aisdk.protocol.UiMessageChunkJson.jsonChunk
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put

internal object UiTerminalMessageChunkCodec {
    fun chunk(event: StreamEvent): JsonObject? = when (event) {
        is StreamEvent.StepFinish -> jsonChunk("finish-step") {
            put("stepNumber", event.stepNumber)
            put("finishReason", event.finishReason.toWireValue())
        }
        is StreamEvent.Finish -> jsonChunk("finish") {
            put("finishReason", event.finishReason.toWireValue())
        }
        is StreamEvent.Error -> jsonChunk("error") { put("error", event.message) }
        is StreamEvent.Abort -> jsonChunk("abort")
        else -> null
    }

    private fun FinishReason.toWireValue(): String = when (this) {
        FinishReason.Stop -> "stop"
        FinishReason.Length -> "length"
        FinishReason.ToolCalls,
        FinishReason.ToolApprovalRequested,
        -> "tool-calls"
        FinishReason.ContentFilter -> "content-filter"
        FinishReason.Error -> "error"
        FinishReason.Other -> "other"
    }
}
