package ai.torad.aisdk.ui

import ai.torad.aisdk.StreamEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Bridge an agent [StreamEvent] flow to the v6 UI-message-stream wire protocol:
 * each emission is a `UIMessageChunk` JSON object (e.g. `{ "type": "text-delta",
 * "id": "...", "delta": "..." }`) ready to be framed as an SSE `data:` line and
 * consumed by a JS `useChat` client. Events with no wire counterpart
 * (response-metadata, tool-input-end, raw) are dropped.
 *
 * This is the server-side counterpart to [streamToUiMessages] (which builds
 * renderable snapshots in-process); use this when serving the stream over HTTP.
 */
public fun toUIMessageStream(events: Flow<StreamEvent>): Flow<JsonObject> =
    events.mapNotNull { it.toUIMessageChunk() }

private fun jsonChunk(type: String, body: JsonObjectBuilder.() -> Unit = {}): JsonObject =
    buildJsonObject {
        put("type", type)
        body()
    }

// A flat dispatch table over the sealed StreamEvent taxonomy — length/branch count
// is inherent to the 1:1 event→wire-chunk mapping, not accidental complexity.
@Suppress("CyclomaticComplexMethod", "LongMethod")
private fun StreamEvent.toUIMessageChunk(): JsonObject? = when (this) {
    is StreamEvent.StreamStart -> jsonChunk("start")
    is StreamEvent.StepStart -> jsonChunk("start-step")
    is StreamEvent.TextStart -> jsonChunk("text-start") { put("id", id) }
    is StreamEvent.TextDelta -> jsonChunk("text-delta") {
        put("id", id)
        put("delta", text)
    }
    is StreamEvent.TextEnd -> jsonChunk("text-end") { put("id", id) }
    is StreamEvent.ReasoningStart -> jsonChunk("reasoning-start") { put("id", id) }
    is StreamEvent.ReasoningDelta -> jsonChunk("reasoning-delta") {
        put("id", id)
        put("delta", text)
    }
    is StreamEvent.ReasoningEnd -> jsonChunk("reasoning-end") { put("id", id) }
    is StreamEvent.SourcePart -> when (sourceType) {
        StreamEvent.SourcePart.SourceType.Url -> jsonChunk("source-url") {
            put("sourceId", id)
            put("url", url.orEmpty())
            title?.let { put("title", it) }
        }
        StreamEvent.SourcePart.SourceType.Document -> jsonChunk("source-document") {
            put("sourceId", id)
            put("mediaType", mediaType.orEmpty())
            put("title", title.orEmpty())
        }
    }
    is StreamEvent.FilePart -> jsonChunk("file") {
        put("mediaType", mediaType)
        put("url", "data:$mediaType;base64,$base64")
    }
    is StreamEvent.ToolInputStart -> jsonChunk("tool-input-start") {
        put("toolCallId", id)
        put("toolName", toolName)
    }
    is StreamEvent.ToolInputDelta -> jsonChunk("tool-input-delta") {
        put("toolCallId", id)
        put("inputTextDelta", delta)
    }
    is StreamEvent.ToolCall -> jsonChunk("tool-input-available") {
        put("toolCallId", toolCallId)
        put("toolName", toolName)
        put("input", inputJson)
    }
    is StreamEvent.ToolApprovalRequest -> jsonChunk("tool-approval-request") {
        put("toolCallId", toolCallId)
        approvalId?.let { put("approvalId", it) }
    }
    is StreamEvent.ToolResult -> jsonChunk("tool-output-available") {
        put("toolCallId", toolCallId)
        put("output", outputJson)
    }
    is StreamEvent.ToolError -> jsonChunk("tool-output-error") {
        put("toolCallId", toolCallId)
        put("errorText", message)
    }
    is StreamEvent.ToolOutputDenied -> jsonChunk("tool-output-denied") {
        put("toolCallId", toolCallId)
        reason?.let { put("errorText", it) }
    }
    is StreamEvent.StepFinish -> jsonChunk("finish-step")
    is StreamEvent.Finish -> jsonChunk("finish")
    is StreamEvent.Error -> jsonChunk("error") { put("errorText", message) }
    is StreamEvent.Abort -> jsonChunk("abort")
    // No wire counterpart.
    is StreamEvent.ResponseMetadata, is StreamEvent.ToolInputEnd, is StreamEvent.Raw -> null
}
