package ai.torad.aisdk.protocol

import ai.torad.aisdk.ContentPart
import ai.torad.aisdk.ProviderMetadata
import ai.torad.aisdk.StreamEvent
import ai.torad.aisdk.Usage
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

internal object ProtocolAdapters {
    fun uiMessageChunk(event: StreamEvent): JsonObject? =
        UiMessageChunkCodec.chunk(event)

    fun gatewayContentPartJson(part: ContentPart): JsonObject =
        GatewayContentCodec.encode(part)

    fun gatewayContentPartFromJson(value: JsonElement): ContentPart? =
        GatewayContentCodec.decode(value)

    fun gatewayStreamEventFromJson(value: JsonElement): StreamEvent =
        GatewayStreamCodec.decode(value)

    fun gatewayUsageFromJson(value: JsonElement?): Usage =
        GatewayUsageCodec.decode(value)

    fun metadataString(metadata: ProviderMetadata, key: String): String? =
        ProtocolMetadata.string(metadata, key)
}
