package ai.torad.aisdk.protocol

import ai.torad.aisdk.StreamEvent
import ai.torad.aisdk.protocol.UiMessageChunkJson.jsonChunk
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put

internal object UiToolMessageChunkCodec {
    fun chunk(event: StreamEvent): JsonObject? = when (event) {
        is StreamEvent.ToolInputStart -> jsonChunk("tool-input-start") {
            put("toolCallId", event.id)
            put("toolName", event.toolName)
        }
        is StreamEvent.ToolInputDelta -> jsonChunk("tool-input-delta") {
            put("toolCallId", event.id)
            put("delta", event.delta)
        }
        is StreamEvent.ToolCall -> toolCall(event)
        is StreamEvent.ToolApprovalRequest -> toolApprovalRequest(event)
        is StreamEvent.ToolResult -> toolResult(event)
        is StreamEvent.ToolError -> toolError(event)
        is StreamEvent.ToolOutputDenied -> jsonChunk("tool-output-denied") {
            put("toolCallId", event.toolCallId)
            put("approvalId", event.approvalId)
        }
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
        is StreamEvent.ToolInputEnd,
        is StreamEvent.StepFinish,
        is StreamEvent.Finish,
        StreamEvent.Abort,
        is StreamEvent.Error,
        is StreamEvent.Raw,
        -> null
    }

    private fun toolCall(event: StreamEvent.ToolCall): JsonObject = jsonChunk("tool-call") {
        put("toolCallId", event.toolCallId)
        put("toolName", event.toolName)
        put("input", event.inputJson)
        ProtocolMetadata.put(this, event.providerMetadata)
    }

    private fun toolApprovalRequest(event: StreamEvent.ToolApprovalRequest): JsonObject =
        jsonChunk("tool-approval-request") {
            put("toolCallId", event.toolCallId)
            put("toolName", event.toolName)
            put("input", event.inputJson)
            event.approvalId?.let { put("approvalId", it) }
            event.signature?.let { put("signature", it) }
            ProtocolMetadata.put(this, event.providerMetadata)
        }

    private fun toolResult(event: StreamEvent.ToolResult): JsonObject = jsonChunk("tool-result") {
        put("toolCallId", event.toolCallId)
        put("toolName", event.toolName)
        put("output", event.outputJson)
        put("isError", event.isError)
        if (event.preliminary) put("preliminary", true)
        ProtocolMetadata.put(this, event.providerMetadata)
    }

    private fun toolError(event: StreamEvent.ToolError): JsonObject = jsonChunk("tool-output-error") {
        put("toolCallId", event.toolCallId)
        put("toolName", event.toolName)
        put("errorText", event.message)
        ProtocolMetadata.put(this, event.providerMetadata)
    }
}
