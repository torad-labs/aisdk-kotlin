package ai.torad.aisdk.protocol

import ai.torad.aisdk.FinishReason
import ai.torad.aisdk.StreamEvent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

internal object GatewayTerminalStreamCodec {
    fun decode(type: String, obj: JsonObject): StreamEvent? = when (type) {
        "finish-step" -> StreamEvent.StepFinish(
            stepNumber = (obj["stepNumber"] as? JsonPrimitive)?.intOrNull ?: 1,
            finishReason = finishReason((obj["finishReason"] as? JsonPrimitive)?.contentOrNull),
            usage = GatewayUsageCodec.decode(obj["usage"]),
            providerMetadata = ProtocolMetadata.fromJson(obj["providerMetadata"]),
        )
        "finish" -> StreamEvent.Finish(
            totalSteps = (obj["totalSteps"] as? JsonPrimitive)?.intOrNull ?: 1,
            finishReason = finishReason((obj["finishReason"] as? JsonPrimitive)?.contentOrNull),
            usage = GatewayUsageCodec.decode(obj["usage"]),
            rawFinishReason = (obj["finishReason"] as? JsonPrimitive)?.contentOrNull,
            providerMetadata = ProtocolMetadata.fromJson(obj["providerMetadata"]),
        )
        "abort" -> StreamEvent.Abort
        "error" -> streamError(obj)
        else -> null
    }

    private fun streamError(obj: JsonObject): StreamEvent.Error =
        StreamEvent.Error(
            (obj["error"] as? JsonPrimitive)?.contentOrNull
                ?: obj["error"]?.toString()
                ?: (obj["message"] as? JsonPrimitive)?.contentOrNull
                ?: "Gateway stream error",
        )

    private fun finishReason(value: String?): FinishReason = when (value) {
        "stop" -> FinishReason.Stop
        "length" -> FinishReason.Length
        "tool-calls", "toolCalls" -> FinishReason.ToolCalls
        "content-filter", "contentFilter" -> FinishReason.ContentFilter
        "error" -> FinishReason.Error
        "tool-approval-requested", "toolApprovalRequested" -> FinishReason.ToolApprovalRequested
        else -> FinishReason.Other
    }
}
