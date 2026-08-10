package ai.torad.aisdk.protocol

import ai.torad.aisdk.ContentPart
import ai.torad.aisdk.StreamEvent
import ai.torad.aisdk.WireDecoder
import ai.torad.aisdk.protocol.ProtocolJson.stringFromAny
import ai.torad.aisdk.protocol.ProtocolJson.toolInput
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

internal object GatewayContentDecoder {
    fun decode(value: JsonElement): ContentPart? {
        val obj = WireDecoder.objectValue(value, "gateway", "content part")
        val type = WireDecoder.requiredString(obj, "type", "gateway", "content part")
        return when (type) {
            "text" -> decodeText(obj)
            "reasoning" -> decodeReasoning(obj)
            "tool-call" -> decodeToolCall(obj)
            "tool-result" -> decodeToolResult(obj)
            "tool-approval-request" -> decodeApprovalRequest(obj)
            "tool-approval-response" -> decodeApprovalResponse(obj)
            // v3 emits BOTH source variants as `type: "source"`, discriminated by `sourceType`;
            // `source-url`/`source-document` are the UI-layer names and stay accepted as aliases.
            "source", "source-url", "source-document" ->
                ProtocolJson.sourceType(type, obj)?.let { decodeSource(obj, it) } ?: ContentPart.Raw(value)
            "file" -> decodeFile(obj)
            "image" -> decodeImage(obj)
            else -> ContentPart.Raw(value)
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
            input = toolInput(WireDecoder.required(obj, "input", "gateway", "content part")),
            providerExecuted = WireDecoder.optionalBoolean(
                obj,
                "providerExecuted",
                "gateway",
                "content part",
            ) ?: false,
            dynamic = WireDecoder.optionalBoolean(obj, "dynamic", "gateway", "content part") ?: false,
            providerMetadata = ProtocolMetadata.fromJson(obj["providerMetadata"]),
        )

    private fun decodeToolResult(obj: JsonObject): ContentPart.ToolResult {
        // v3's response-side tool-result carries the payload under `result`; `output` is the
        // PROMPT-side field name and stays a fallback for gateways that echo the prompt shape.
        val output = obj["result"] ?: WireDecoder.required(obj, "output", "gateway", "content part")
        return ContentPart.ToolResult(
            toolCallId = WireDecoder.requiredString(obj, "toolCallId", "gateway", "content part"),
            toolName = WireDecoder.requiredString(obj, "toolName", "gateway", "content part"),
            output = output,
            isError = WireDecoder.optionalBoolean(obj, "isError", "gateway", "content part") ?: false,
            modelVisible = obj["modelVisible"] ?: obj["modelOutput"] ?: output,
            dynamic = WireDecoder.optionalBoolean(obj, "dynamic", "gateway", "content part") ?: false,
            providerExecuted = WireDecoder.optionalBoolean(
                obj,
                "providerExecuted",
                "gateway",
                "content part",
            ) ?: false,
            providerMetadata = ProtocolMetadata.fromJson(obj["providerMetadata"]),
        )
    }

    // v3's tool-approval-request is {approvalId, toolCallId, providerMetadata?} — it carries
    // NEITHER toolName NOR input (the host correlates them through the matching tool-call).
    private fun decodeApprovalRequest(obj: JsonObject): ContentPart.ToolApprovalRequest =
        ContentPart.ToolApprovalRequest(
            toolCallId = WireDecoder.requiredString(obj, "toolCallId", "gateway", "content part"),
            toolName = WireDecoder.optionalString(obj, "toolName", "gateway", "content part").orEmpty(),
            input = obj["input"]?.let(::toolInput) ?: JsonObject(emptyMap()),
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
            sourceId = WireDecoder.optionalString(obj, "sourceId", "gateway", "content part")
                ?: WireDecoder.optionalString(obj, "id", "gateway", "content part"),
            url = WireDecoder.optionalString(obj, "url", "gateway", "content part"),
            title = WireDecoder.optionalString(obj, "title", "gateway", "content part"),
            mediaType = WireDecoder.optionalString(obj, "mediaType", "gateway", "content part"),
            filename = WireDecoder.optionalString(obj, "filename", "gateway", "content part"),
            providerMetadata = ProtocolMetadata.fromJson(obj["providerMetadata"]),
        )
}
