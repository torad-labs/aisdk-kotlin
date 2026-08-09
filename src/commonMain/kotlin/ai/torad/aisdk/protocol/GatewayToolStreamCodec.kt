package ai.torad.aisdk.protocol

import ai.torad.aisdk.StreamEvent
import ai.torad.aisdk.ToolResultOutput
import ai.torad.aisdk.ToolResultOutputs
import ai.torad.aisdk.WireDecoder
import ai.torad.aisdk.protocol.ProtocolJson.requiredOneOfString
import ai.torad.aisdk.protocol.ProtocolJson.toolInput
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
            inputJson = toolInput(WireDecoder.required(obj, "input", "gateway", "stream event")),
            providerMetadata = ProtocolMetadata.fromJson(obj["providerMetadata"]),
        )

    private fun toolResult(obj: JsonObject): StreamEvent.ToolResult = with(ToolResultOutputs) {
        // v3's response-side tool-result carries the payload under `result`; `output` is the
        // PROMPT-side field name and stays a fallback for gateways that echo the prompt shape.
        val payload = obj["result"] ?: WireDecoder.required(obj, "output", "gateway", "stream event")
        val output = toolResultOutputFromWire(payload)
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

    // v3's tool-approval-request is {approvalId, toolCallId, providerMetadata?} — it carries
    // NEITHER toolName NOR input (the host correlates them through the matching tool-call).
    // Both stay optional so a spec-conformant part decodes instead of killing the stream.
    private fun approvalRequest(obj: JsonObject): StreamEvent.ToolApprovalRequest =
        StreamEvent.ToolApprovalRequest(
            toolCallId = WireDecoder.requiredString(obj, "toolCallId", "gateway", "stream event"),
            toolName = WireDecoder.optionalString(obj, "toolName", "gateway", "stream event").orEmpty(),
            inputJson = obj["input"]?.let(::toolInput) ?: JsonObject(emptyMap()),
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

    /**
     * `modelVisible` wins over the legacy `modelOutput`, matching GatewayContentEncoder (which
     * writes `modelVisible`) and GatewayContentDecoder. Both names can arrive together from a
     * transition-era gateway, and the two decode paths MUST agree on which one is canonical:
     * disagreeing meant the same wire object produced one payload when it arrived as a stream
     * event and a different one when replayed as a content part.
     */
    private fun ToolResultOutputs.modelOutput(
        obj: JsonObject,
        output: ToolResultOutput,
    ): ToolResultOutput =
        obj["modelVisible"]?.let(::toolResultOutputFromWire)
            ?: obj["modelOutput"]?.let(::toolResultOutputFromWire)
            ?: output
}
