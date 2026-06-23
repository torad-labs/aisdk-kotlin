package ai.torad.aisdk.protocol

import ai.torad.aisdk.StreamEvent
import ai.torad.aisdk.protocol.UiMessageChunkJson.jsonChunk
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put

internal object UiMediaMessageChunkCodec {
    fun chunk(event: StreamEvent): JsonObject? = when (event) {
        is StreamEvent.SourcePart -> source(event)
        is StreamEvent.FilePart -> file(event)
        else -> null
    }

    private fun source(event: StreamEvent.SourcePart): JsonObject = when (event.sourceType) {
        StreamEvent.SourcePart.SourceType.Url -> jsonChunk("source-url") {
            put("sourceId", event.id)
            put("url", event.url.orEmpty())
            event.title?.let { put("title", it) }
            ProtocolMetadata.put(this, event.providerMetadata)
        }
        StreamEvent.SourcePart.SourceType.Document -> jsonChunk("source-document") {
            put("sourceId", event.id)
            put("mediaType", event.mediaType.orEmpty())
            put("title", event.title.orEmpty())
            ProtocolMetadata.string(event.providerMetadata, "filename")?.let { put("filename", it) }
            ProtocolMetadata.put(this, event.providerMetadata)
        }
    }

    private fun file(event: StreamEvent.FilePart): JsonObject = jsonChunk("file") {
        put("id", event.id)
        put("mediaType", event.mediaType)
        put("data", event.base64)
        put("url", ProtocolMetadata.string(event.providerMetadata, "url") ?: event.dataUrl())
        ProtocolMetadata.string(event.providerMetadata, "filename")?.let { put("filename", it) }
        ProtocolMetadata.put(this, event.providerMetadata)
    }

    private fun StreamEvent.FilePart.dataUrl(): String =
        "data:$mediaType;base64,$base64"
}
