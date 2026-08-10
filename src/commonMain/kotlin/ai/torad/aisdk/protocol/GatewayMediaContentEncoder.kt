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

    // v3's LanguageModelV3FilePart is {type, filename?, data, mediaType}: the URL travels IN
    // `data` (base64 string OR URL string), so there is no sibling `url` key on the wire.
    fun encodeFile(part: ContentPart.File): JsonObject = contentJson("file", part) {
        put("mediaType", part.mediaType)
        put("data", fileData(part.base64, part.url))
        part.filename?.let { put("filename", it) }
    }

    // v3's prompt content unions have no `image` part — upstream collapses an image into a
    // file part carrying the image media type, so the gateway prompt does the same.
    fun encodeImage(part: ContentPart.Image): JsonObject = contentJson("file", part) {
        put("mediaType", part.mediaType)
        put("data", fileData(part.base64, part.url))
    }

    private fun fileData(base64: String, url: String?): String =
        if (base64.isNotEmpty()) base64 else url.orEmpty()
}
