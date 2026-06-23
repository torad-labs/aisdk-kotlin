package ai.torad.aisdk.protocol

import ai.torad.aisdk.StreamEvent
import ai.torad.aisdk.ToolResultOutput
import ai.torad.aisdk.ToolResultOutputs
import ai.torad.aisdk.WireDecoder
import ai.torad.aisdk.protocol.ProtocolJson.requiredOneOfString
import kotlinx.serialization.json.JsonObject

internal object GatewayToolStreamCodec {
    fun decode(type: String, obj: JsonObject): StreamEvent? = when (type) {
        "tool-input-start" -> toolInputStart(obj)
        "tool-input-delta" -> toolInputDelta(obj)
        "tool-input-end" -> toolInputEnd(obj)
        "tool-call" -> toolCall(obj)
        "tool-result" -> toolResult(obj)
        "tool-output-error" -> toolError(obj)
        "tool-approval-request" -> approvalRequest(obj)
        "tool-output-denied" -> outputDenied(obj)
        else -> null
    }

    private fun toolInputStart(obj: JsonObject): StreamEvent.ToolInputStart =
        StreamEvent.ToolInputStart(
            id = requiredOneOfString(obj, "gateway", "stream event", "id", "toolCallId"),
            toolName = WireDecoder.requiredString(obj, "toolName", "gateway", "stream event"),
            providerMetadata = ProtocolMetadata.fromJson(obj["providerMetadata"]),
        )

    private fun toolInputDelta(obj: JsonObject): StreamEvent.ToolInputDelta =
        StreamEvent.ToolInputDelta(
            id = requiredOneOfString(obj, "gateway", "stream event", "id", "toolCallId"),
            delta = WireDecoder.requiredString(obj, "delta", "gateway", "stream event"),
            providerMetadata = ProtocolMetadata.fromJson(obj["providerMetadata"]),
        )

    private fun toolInputEnd(obj: JsonObject): StreamEvent.ToolInputEnd =
        StreamEvent.ToolInputEnd(
            id = requiredOneOfString(obj, "gateway", "stream event", "id", "toolCallId"),
            providerMetadata = ProtocolMetadata.fromJson(obj["providerMetadata"]),
        )

    private fun toolCall(obj: JsonObject): StreamEvent.ToolCall =
        StreamEvent.ToolCall(
            toolCallId = WireDecoder.requiredString(obj, "toolCallId", "gateway", "stream event"),
            toolName = WireDecoder.requiredString(obj, "toolName", "gateway", "stream event"),
            inputJson = WireDecoder.required(obj, "input", "gateway", "stream event"),
            providerMetadata = ProtocolMetadata.fromJson(obj["providerMetadata"]),
        )

    private fun toolResult(obj: JsonObject): StreamEvent.ToolResult = with(ToolResultOutputs) {
        val output = toolResultOutputFromWire(WireDecoder.required(obj, "output", "gateway", "stream event"))
        StreamEvent.ToolResult(
            toolCallId = WireDecoder.requiredString(obj, "toolCallId", "gateway", "stream event"),
            toolName = WireDecoder.requiredString(obj, "toolName", "gateway", "stream event"),
            outputJson = output.toJsonElement(),
            output = output,
            modelOutput = modelOutput(obj, output),
            isError = WireDecoder.optionalBoolean(obj, "isError", "gateway", "stream event")
                ?: output.isToolResultError(),
            preliminary = WireDecoder.optionalBoolean(obj, "preliminary", "gateway", "stream event") ?: false,
            providerMetadata = ProtocolMetadata.fromJson(obj["providerMetadata"]),
        )
    }

    private fun toolError(obj: JsonObject): StreamEvent.ToolError =
        StreamEvent.ToolError(
            toolCallId = WireDecoder.requiredString(obj, "toolCallId", "gateway", "stream event"),
            toolName = WireDecoder.requiredString(obj, "toolName", "gateway", "stream event"),
            message = requiredOneOfString(obj, "gateway", "stream event", "errorText", "message", "error"),
            providerMetadata = ProtocolMetadata.fromJson(obj["providerMetadata"]),
        )

    private fun approvalRequest(obj: JsonObject): StreamEvent.ToolApprovalRequest =
        StreamEvent.ToolApprovalRequest(
            toolCallId = WireDecoder.requiredString(obj, "toolCallId", "gateway", "stream event"),
            toolName = WireDecoder.requiredString(obj, "toolName", "gateway", "stream event"),
            inputJson = WireDecoder.required(obj, "input", "gateway", "stream event"),
            approvalId = WireDecoder.optionalString(obj, "approvalId", "gateway", "stream event"),
            signature = WireDecoder.optionalString(obj, "signature", "gateway", "stream event"),
            providerMetadata = ProtocolMetadata.fromJson(obj["providerMetadata"]),
        )

    private fun outputDenied(obj: JsonObject): StreamEvent.ToolOutputDenied =
        StreamEvent.ToolOutputDenied(
            toolCallId = WireDecoder.requiredString(obj, "toolCallId", "gateway", "stream event"),
            toolName = WireDecoder.requiredString(obj, "toolName", "gateway", "stream event"),
            approvalId = WireDecoder.optionalString(obj, "approvalId", "gateway", "stream event")
                ?: WireDecoder.requiredString(obj, "toolCallId", "gateway", "stream event"),
            reason = WireDecoder.optionalString(obj, "reason", "gateway", "stream event"),
            providerMetadata = ProtocolMetadata.fromJson(obj["providerMetadata"]),
        )

    private fun ToolResultOutputs.modelOutput(
        obj: JsonObject,
        output: ToolResultOutput,
    ): ToolResultOutput =
        obj["modelOutput"]?.let(::toolResultOutputFromWire)
            ?: obj["modelVisible"]?.let(::toolResultOutputFromWire)
            ?: output
}
