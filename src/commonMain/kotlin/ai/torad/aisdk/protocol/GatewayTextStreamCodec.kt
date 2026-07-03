package ai.torad.aisdk.protocol

import ai.torad.aisdk.StreamEvent
import ai.torad.aisdk.WireDecoder
import ai.torad.aisdk.protocol.ProtocolJson.requiredOneOfString
import kotlinx.serialization.json.JsonObject

internal object GatewayTextStreamCodec {
    fun decode(type: String, obj: JsonObject): StreamEvent? = when (type) {
        "text-start" -> StreamEvent.TextStart(
            id = WireDecoder.optionalString(obj, "id", "gateway", "stream event") ?: "text",
            providerMetadata = ProtocolMetadata.fromJson(obj["providerMetadata"]),
        )
        "text-delta" -> StreamEvent.TextDelta(
            id = WireDecoder.optionalString(obj, "id", "gateway", "stream event") ?: "text",
            text = requiredOneOfString(obj, "gateway", "stream event", "delta", "text"),
            providerMetadata = ProtocolMetadata.fromJson(obj["providerMetadata"]),
        )
        "text-end" -> StreamEvent.TextEnd(
            id = WireDecoder.optionalString(obj, "id", "gateway", "stream event") ?: "text",
            providerMetadata = ProtocolMetadata.fromJson(obj["providerMetadata"]),
        )
        "reasoning-start" -> StreamEvent.ReasoningStart(
            id = WireDecoder.optionalString(obj, "id", "gateway", "stream event") ?: "reasoning",
            providerMetadata = ProtocolMetadata.fromJson(obj["providerMetadata"]),
        )
        "reasoning-delta" -> StreamEvent.ReasoningDelta(
            id = WireDecoder.optionalString(obj, "id", "gateway", "stream event") ?: "reasoning",
            text = requiredOneOfString(obj, "gateway", "stream event", "delta", "text"),
            providerMetadata = ProtocolMetadata.fromJson(obj["providerMetadata"]),
        )
        "reasoning-end" -> StreamEvent.ReasoningEnd(
            id = WireDecoder.optionalString(obj, "id", "gateway", "stream event") ?: "reasoning",
            providerMetadata = ProtocolMetadata.fromJson(obj["providerMetadata"]),
        )
        else -> null
    }
}
