package ai.torad.aisdk.protocol

import ai.torad.aisdk.ContentPart
import ai.torad.aisdk.StreamEvent
import ai.torad.aisdk.WireDecoder
import ai.torad.aisdk.protocol.ProtocolJson.stringFromAny
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

internal object GatewayContentDecoder {
    fun decode(value: JsonElement): ContentPart? {
        val obj = WireDecoder.objectValue(value, "gateway", "content part")
        return when (WireDecoder.requiredString(obj, "type", "gateway", "content part")) {
            "text" -> decodeText(obj)
            "reasoning" -> decodeReasoning(obj)
            "tool-call" -> decodeToolCall(obj)
            "tool-result" -> decodeToolResult(obj)
            "tool-approval-request" -> decodeApprovalRequest(obj)
            "tool-approval-response" -> decodeApprovalResponse(obj)
            "source-url" -> decodeSource(obj, StreamEvent.SourcePart.SourceType.Url)
            "source-document" -> decodeSource(obj, StreamEvent.SourcePart.SourceType.Document)
            "file" -> decodeFile(obj)
            "image" -> decodeImage(obj)
            else -> null
        }
    }

    private fun decodeText(obj: JsonObject): ContentPart.Text =
        ContentPart.Text(
            text = WireDecoder.requiredString(obj, "text", "gateway", "content part"),
            providerMetadata = ProtocolMetadata.fromJson(obj["providerMetadata"]),
        )

    private fun decodeReasoning(obj: JsonObject): ContentPart.Reasoning =
        ContentPart.Reasoning(
            text = WireDecoder.requiredString(obj, "text", "gateway", "content part"),
            providerMetadata = ProtocolMetadata.fromJson(obj["providerMetadata"]),
        )

    private fun decodeToolCall(obj: JsonObject): ContentPart.ToolCall =
        ContentPart.ToolCall(
            toolCallId = WireDecoder.requiredString(obj, "toolCallId", "gateway", "content part"),
            toolName = WireDecoder.requiredString(obj, "toolName", "gateway", "content part"),
            input = WireDecoder.required(obj, "input", "gateway", "content part"),
            providerExecuted = WireDecoder.optionalBoolean(
                obj,
                "providerExecuted",
                "gateway",
                "content part",
            ) ?: false,
            dynamic = WireDecoder.optionalBoolean(obj, "dynamic", "gateway", "content part") ?: false,
            providerMetadata = ProtocolMetadata.fromJson(obj["providerMetadata"]),
        )

    private fun decodeToolResult(obj: JsonObject): ContentPart.ToolResult =
        ContentPart.ToolResult(
            toolCallId = WireDecoder.requiredString(obj, "toolCallId", "gateway", "content part"),
            toolName = WireDecoder.requiredString(obj, "toolName", "gateway", "content part"),
            output = WireDecoder.required(obj, "output", "gateway", "content part"),
            isError = WireDecoder.optionalBoolean(obj, "isError", "gateway", "content part") ?: false,
            modelVisible = obj["modelVisible"] ?: obj["modelOutput"]
                ?: WireDecoder.required(obj, "output", "gateway", "content part"),
            dynamic = WireDecoder.optionalBoolean(obj, "dynamic", "gateway", "content part") ?: false,
            providerExecuted = WireDecoder.optionalBoolean(
                obj,
                "providerExecuted",
                "gateway",
                "content part",
            ) ?: false,
            providerMetadata = ProtocolMetadata.fromJson(obj["providerMetadata"]),
        )

    private fun decodeApprovalRequest(obj: JsonObject): ContentPart.ToolApprovalRequest =
        ContentPart.ToolApprovalRequest(
            toolCallId = WireDecoder.requiredString(obj, "toolCallId", "gateway", "content part"),
            toolName = WireDecoder.requiredString(obj, "toolName", "gateway", "content part"),
            input = WireDecoder.required(obj, "input", "gateway", "content part"),
            approvalId = WireDecoder.optionalString(obj, "approvalId", "gateway", "content part"),
            signature = WireDecoder.optionalString(obj, "signature", "gateway", "content part"),
            providerMetadata = ProtocolMetadata.fromJson(obj["providerMetadata"]),
        )

    private fun decodeApprovalResponse(obj: JsonObject): ContentPart.ToolApprovalResponse =
        ContentPart.ToolApprovalResponse(
            toolCallId = WireDecoder.requiredString(obj, "toolCallId", "gateway", "content part"),
            approved = WireDecoder.booleanValue(
                WireDecoder.required(obj, "approved", "gateway", "content part"),
                "gateway",
                "content part",
                "$.approved",
            ),
            reason = WireDecoder.optionalString(obj, "reason", "gateway", "content part"),
            approvalId = WireDecoder.optionalString(obj, "approvalId", "gateway", "content part"),
        )

    private fun decodeFile(obj: JsonObject): ContentPart.File =
        ContentPart.File(
            mediaType = WireDecoder.optionalString(obj, "mediaType", "gateway", "content part")
                ?: "application/octet-stream",
            base64 = obj.stringFromAny("data", "base64").orEmpty(),
            filename = WireDecoder.optionalString(obj, "filename", "gateway", "content part"),
            providerMetadata = ProtocolMetadata.fromJson(obj["providerMetadata"]),
            url = WireDecoder.optionalString(obj, "url", "gateway", "content part"),
        )

    private fun decodeImage(obj: JsonObject): ContentPart.Image =
        ContentPart.Image(
            mediaType = WireDecoder.optionalString(obj, "mediaType", "gateway", "content part") ?: "image/*",
            base64 = obj.stringFromAny("data", "base64").orEmpty(),
            providerMetadata = ProtocolMetadata.fromJson(obj["providerMetadata"]),
            url = WireDecoder.optionalString(obj, "url", "gateway", "content part"),
        )

    private fun decodeSource(
        obj: JsonObject,
        sourceType: StreamEvent.SourcePart.SourceType,
    ): ContentPart.Source =
        ContentPart.Source(
            sourceType = sourceType,
            sourceId = WireDecoder.optionalString(obj, "sourceId", "gateway", "content part"),
            url = WireDecoder.optionalString(obj, "url", "gateway", "content part"),
            title = WireDecoder.optionalString(obj, "title", "gateway", "content part"),
            mediaType = WireDecoder.optionalString(obj, "mediaType", "gateway", "content part"),
            filename = WireDecoder.optionalString(obj, "filename", "gateway", "content part"),
            providerMetadata = ProtocolMetadata.fromJson(obj["providerMetadata"]),
        )
}
