package ai.torad.aisdk.protocol

import ai.torad.aisdk.ContentPart
import ai.torad.aisdk.metadata
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object GatewayContentEncoder {
    fun encode(part: ContentPart): JsonObject = when (part) {
        is ContentPart.Text -> encodeText(part)
        is ContentPart.Reasoning -> encodeReasoning(part)
        is ContentPart.ToolCall -> encodeToolCall(part)
        is ContentPart.ToolResult -> encodeToolResult(part)
        is ContentPart.ToolApprovalRequest -> encodeApprovalRequest(part)
        is ContentPart.ToolApprovalResponse -> encodeApprovalResponse(part)
        is ContentPart.Source -> GatewayMediaContentEncoder.encodeSource(part)
        is ContentPart.File -> GatewayMediaContentEncoder.encodeFile(part)
        is ContentPart.Image -> GatewayMediaContentEncoder.encodeImage(part)
    }

    private fun encodeText(part: ContentPart.Text): JsonObject = contentJson("text", part) {
        put("text", part.text)
    }

    private fun encodeReasoning(part: ContentPart.Reasoning): JsonObject =
        contentJson("reasoning", part) {
            put("text", part.text)
        }

    private fun encodeToolCall(part: ContentPart.ToolCall): JsonObject =
        contentJson("tool-call", part) {
            put("toolCallId", part.toolCallId)
            put("toolName", part.toolName)
            put("input", part.input)
            if (part.providerExecuted) put("providerExecuted", true)
            if (part.dynamic) put("dynamic", true)
        }

    private fun encodeToolResult(part: ContentPart.ToolResult): JsonObject =
        contentJson("tool-result", part) {
            put("toolCallId", part.toolCallId)
            put("toolName", part.toolName)
            put("output", part.output)
            put("modelVisible", part.modelVisible)
            put("isError", part.isError)
            if (part.dynamic) put("dynamic", true)
            if (part.providerExecuted) put("providerExecuted", true)
        }

    private fun encodeApprovalRequest(part: ContentPart.ToolApprovalRequest): JsonObject =
        contentJson("tool-approval-request", part) {
            put("toolCallId", part.toolCallId)
            put("toolName", part.toolName)
            put("input", part.input)
            part.approvalId?.let { put("approvalId", it) }
            part.signature?.let { put("signature", it) }
        }

    private fun encodeApprovalResponse(part: ContentPart.ToolApprovalResponse): JsonObject =
        contentJson("tool-approval-response", part) {
            put("toolCallId", part.toolCallId)
            put("approved", part.approved)
            part.reason?.let { put("reason", it) }
            part.approvalId?.let { put("approvalId", it) }
        }

    fun contentJson(
        type: String,
        part: ContentPart,
        body: JsonObjectBuilder.() -> Unit,
    ): JsonObject =
        buildJsonObject {
            put("type", type)
            body()
            ProtocolMetadata.put(this, part.metadata)
        }
}
