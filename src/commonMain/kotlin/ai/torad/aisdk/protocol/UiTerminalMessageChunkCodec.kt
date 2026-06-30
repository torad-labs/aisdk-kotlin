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
        is StreamEvent.StreamStart,
        is StreamEvent.ResponseMetadata,
        is StreamEvent.StepStart,
        is StreamEvent.TextStart,
        is StreamEvent.TextDelta,
        is StreamEvent.TextEnd,
        is StreamEvent.ReasoningStart,
        is StreamEvent.ReasoningDelta,
        is StreamEvent.ReasoningEnd,
        is StreamEvent.SourcePart,
        is StreamEvent.FilePart,
        is StreamEvent.ToolInputStart,
        is StreamEvent.ToolInputDelta,
        is StreamEvent.ToolInputEnd,
        is StreamEvent.ToolCall,
        is StreamEvent.ToolResult,
        is StreamEvent.ToolError,
        is StreamEvent.ToolApprovalRequest,
        is StreamEvent.ToolOutputDenied,
        is StreamEvent.Raw,
        -> null
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
