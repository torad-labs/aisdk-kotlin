package ai.torad.aisdk.protocol

import ai.torad.aisdk.StreamEvent
import ai.torad.aisdk.WireDecoder
import ai.torad.aisdk.protocol.ProtocolJson.stringFromAny
import kotlinx.serialization.json.JsonObject

internal object GatewayMediaStreamCodec {
    fun decode(type: String, obj: JsonObject): StreamEvent? = when (type) {
        "source-url" -> source(obj, StreamEvent.SourcePart.SourceType.Url)
        "source-document" -> source(obj, StreamEvent.SourcePart.SourceType.Document)
        "file" -> file(obj)
        else -> null
    }

    private fun source(
        obj: JsonObject,
        sourceType: StreamEvent.SourcePart.SourceType,
    ): StreamEvent.SourcePart =
        StreamEvent.SourcePart(
            id = WireDecoder.optionalString(obj, "sourceId", "gateway", "stream event")
                ?: WireDecoder.optionalString(obj, "id", "gateway", "stream event")
                ?: sourceType.defaultId,
            sourceType = sourceType,
            url = WireDecoder.optionalString(obj, "url", "gateway", "stream event"),
            title = WireDecoder.optionalString(obj, "title", "gateway", "stream event"),
            mediaType = WireDecoder.optionalString(obj, "mediaType", "gateway", "stream event"),
            providerMetadata = ProtocolMetadata.source(obj),
        )

    private fun file(obj: JsonObject): StreamEvent.FilePart =
        StreamEvent.FilePart(
            id = WireDecoder.optionalString(obj, "id", "gateway", "stream event")
                ?: WireDecoder.optionalString(obj, "sourceId", "gateway", "stream event")
                ?: "file",
            mediaType = WireDecoder.optionalString(obj, "mediaType", "gateway", "stream event")
                ?: "application/octet-stream",
            base64 = obj.stringFromAny("data", "base64").orEmpty(),
            providerMetadata = ProtocolMetadata.file(obj),
        )

    private val StreamEvent.SourcePart.SourceType.defaultId: String
        get() = when (this) {
            StreamEvent.SourcePart.SourceType.Url -> "source-url"
            StreamEvent.SourcePart.SourceType.Document -> "source-document"
        }
}
