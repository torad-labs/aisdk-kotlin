package ai.torad.aisdk.protocol

import ai.torad.aisdk.StreamEvent
import ai.torad.aisdk.WireDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object GatewayStreamCodec {
    fun decode(value: JsonElement): StreamEvent {
        val obj = WireDecoder.objectValue(value, "gateway", "stream event")
        val type = WireDecoder.requiredString(obj, "type", "gateway", "stream event")
        return GatewayLifecycleStreamCodec.decode(type, obj)
            ?: GatewayTextStreamCodec.decode(type, obj)
            ?: GatewayToolStreamCodec.decode(type, obj)
            ?: GatewayMediaStreamCodec.decode(type, obj)
            ?: GatewayTerminalStreamCodec.decode(type, obj)
            ?: raw(type, value)
    }

    private fun raw(type: String, value: JsonElement): StreamEvent =
        if (type == "raw") {
            val obj = WireDecoder.objectValue(value, "gateway", "stream event")
            StreamEvent.Raw(obj["rawValue"] ?: value)
        } else {
            StreamEvent.Raw(
                buildJsonObject {
                    put("type", type)
                    put("data", value)
                },
            )
        }
}
