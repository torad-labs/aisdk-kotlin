package ai.torad.aisdk

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Locks the [ContentPart] wire format to the Vercel AI SDK v6 discriminator
 * tags (H-6). These strings are a committed contract — kotlinx must round-trip
 * v6 message JSON directly, so changing a tag is a breaking change.
 */
class ContentPartWireFormatTest {

    private val codec = Json { encodeDefaults = true }

    private fun tag(part: ContentPart): String =
        codec.encodeToJsonElement(ContentPart.serializer(), part)
            .jsonObject["type"]!!.jsonPrimitive.content

    @Test
    fun `content parts serialize with the v6 type discriminator`() {
        assertEquals("text", tag(ContentPart.Text("hi")))
        assertEquals("reasoning", tag(ContentPart.Reasoning("why")))
        assertEquals("tool-call", tag(ContentPart.ToolCall("c1", "search", JsonObject(emptyMap()))))
        assertEquals("tool-result", tag(ContentPart.ToolResult("c1", "search", JsonPrimitive("ok"))))
        assertEquals(
            "tool-approval-request",
            tag(ContentPart.ToolApprovalRequest("c1", "search", JsonObject(emptyMap()))),
        )
        assertEquals("tool-approval-response", tag(ContentPart.ToolApprovalResponse("c1", approved = true)))
        assertEquals("file", tag(ContentPart.File("text/plain", "aGk=")))
        assertEquals("image", tag(ContentPart.Image("image/png", "aGk=")))
    }

    @Test
    fun `a tool-call with v6 parity fields round-trips`() {
        val original: ContentPart = ContentPart.ToolCall(
            toolCallId = "c1",
            toolName = "search",
            input = JsonObject(emptyMap()),
            providerExecuted = true,
            dynamic = true,
        )
        val json = codec.encodeToString(ContentPart.serializer(), original)
        assertEquals(original, codec.decodeFromString(ContentPart.serializer(), json))
    }
}
