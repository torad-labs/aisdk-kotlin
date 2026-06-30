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
        is StreamEvent.ResponseMetadata,
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
        is StreamEvent.StepFinish,
        is StreamEvent.Finish,
        StreamEvent.Abort,
        is StreamEvent.Error,
        is StreamEvent.Raw,
        -> null
    }

    private fun text(event: StreamEvent): JsonObject? = when (event) {
        is StreamEvent.TextStart -> jsonChunk("text-start") { put("id", event.id) }
        is StreamEvent.TextDelta -> jsonChunk("text-delta") {
            put("id", event.id)
            put("delta", event.text)
        }
        is StreamEvent.TextEnd -> jsonChunk("text-end") { put("id", event.id) }
        is StreamEvent.StreamStart,
        is StreamEvent.ResponseMetadata,
        is StreamEvent.StepStart,
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
        is StreamEvent.StepFinish,
        is StreamEvent.Finish,
        StreamEvent.Abort,
        is StreamEvent.Error,
        is StreamEvent.Raw,
        -> null
    }

    private fun reasoning(event: StreamEvent): JsonObject? = when (event) {
        is StreamEvent.ReasoningStart -> jsonChunk("reasoning-start") { put("id", event.id) }
        is StreamEvent.ReasoningDelta -> jsonChunk("reasoning-delta") {
            put("id", event.id)
            put("delta", event.text)
        }
        is StreamEvent.ReasoningEnd -> jsonChunk("reasoning-end") { put("id", event.id) }
        is StreamEvent.StreamStart,
        is StreamEvent.ResponseMetadata,
        is StreamEvent.StepStart,
        is StreamEvent.TextStart,
        is StreamEvent.TextDelta,
        is StreamEvent.TextEnd,
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
        is StreamEvent.StepFinish,
        is StreamEvent.Finish,
        StreamEvent.Abort,
        is StreamEvent.Error,
        is StreamEvent.Raw,
        -> null
    }
}
