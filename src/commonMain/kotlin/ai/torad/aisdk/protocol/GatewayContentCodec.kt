package ai.torad.aisdk.protocol

import ai.torad.aisdk.ContentPart
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

internal object GatewayContentCodec {
    fun encode(part: ContentPart): JsonObject =
        GatewayContentEncoder.encode(part)

    fun decode(value: JsonElement): ContentPart? =
        GatewayContentDecoder.decode(value)
}
