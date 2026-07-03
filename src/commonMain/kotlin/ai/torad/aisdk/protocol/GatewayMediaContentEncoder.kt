package ai.torad.aisdk.protocol

import ai.torad.aisdk.ContentPart
import ai.torad.aisdk.StreamEvent
import ai.torad.aisdk.protocol.GatewayContentEncoder.contentJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put

internal object GatewayMediaContentEncoder {
    fun encodeSource(part: ContentPart.Source): JsonObject =
        contentJson(
            if (part.sourceType == StreamEvent.SourcePart.SourceType.Url) {
                "source-url"
            } else {
                "source-document"
            },
            part,
        ) {
            part.sourceId?.let { put("sourceId", it) }
            part.url?.let { put("url", it) }
            part.title?.let { put("title", it) }
            part.mediaType?.let { put("mediaType", it) }
            part.filename?.let { put("filename", it) }
        }

    fun encodeFile(part: ContentPart.File): JsonObject = contentJson("file", part) {
        put("mediaType", part.mediaType)
        if (part.base64.isNotEmpty()) put("data", part.base64)
        part.url?.let { put("url", it) }
        part.filename?.let { put("filename", it) }
    }

    fun encodeImage(part: ContentPart.Image): JsonObject = contentJson("image", part) {
        put("mediaType", part.mediaType)
        if (part.base64.isNotEmpty()) put("data", part.base64)
        part.url?.let { put("url", it) }
    }
}
