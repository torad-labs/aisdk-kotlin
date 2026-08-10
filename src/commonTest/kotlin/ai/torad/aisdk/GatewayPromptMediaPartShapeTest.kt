package ai.torad.aisdk

import ai.torad.aisdk.protocol.GatewayContentEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The gateway prompt encoder writes the V3 prompt wire shape
 * (`language-model-v3-prompt.ts`): the user/assistant content unions admit no
 * `image` part, and `LanguageModelV3FilePart` carries its URL **in `data`** —
 * there is no sibling `url` key.
 */
class GatewayPromptMediaPartShapeTest {
    @Test
    fun `image prompt parts collapse to v3 file parts`() {
        val inlineImage = GatewayContentEncoder.encode(
            ContentPart.Image(
                mediaType = "image/png",
                base64 = "iVBORw0KGgo=",
            )
        )
        assertEquals("file", inlineImage.stringField("type"))
        assertEquals("image/png", inlineImage.stringField("mediaType"))
        assertEquals("iVBORw0KGgo=", inlineImage.stringField("data"))
        assertNull(inlineImage["url"])

        val remoteImage = GatewayContentEncoder.encode(
            ContentPart.Image(
                mediaType = "image/png",
                url = "https://images.test/a.png",
            )
        )
        assertEquals("file", remoteImage.stringField("type"))
        assertEquals("https://images.test/a.png", remoteImage.stringField("data"))
        assertNull(remoteImage["url"])
    }

    @Test
    fun `url-only file prompt parts carry the url in data`() {
        val remoteFile = GatewayContentEncoder.encode(
            ContentPart.File(
                mediaType = "text/plain",
                url = "https://files.test/hi.txt",
                filename = "hi.txt",
            )
        )
        assertEquals("file", remoteFile.stringField("type"))
        assertEquals("https://files.test/hi.txt", remoteFile.stringField("data"))
        assertEquals("hi.txt", remoteFile.stringField("filename"))
        assertNull(remoteFile["url"])
    }

    private fun JsonObject.stringField(name: String): String? =
        this[name]?.jsonPrimitive?.contentOrNull
}
