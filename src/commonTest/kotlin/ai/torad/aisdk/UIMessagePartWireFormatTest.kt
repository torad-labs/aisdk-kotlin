package ai.torad.aisdk

import ai.torad.aisdk.ui.ToolCallState
import ai.torad.aisdk.ui.UIMessagePart
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class UIMessagePartWireFormatTest {
    @Test
    fun `ui message parts use stable serial names not Kotlin class names`() {
        val encoded = aiSdkOutputJson.encodeToString(
            UIMessagePart.serializer(),
            UIMessagePart.ToolUI(
                toolCallId = "call_1",
                toolName = "lookup",
                state = ToolCallState.OutputAvailable,
                output = JsonPrimitive("ok"),
            ),
        )

        assertTrue("\"type\":\"tool\"" in encoded, encoded)
        assertTrue("ai.torad.aisdk" !in encoded, encoded)
        assertTrue("UIMessagePart" !in encoded, encoded)
    }

    @Test
    fun `ui message Poko leaves round trip polymorphically as sealed UIMessagePart`() {
        val parts = listOf(
            UIMessagePart.Text("hello"),
            UIMessagePart.ToolUI(
                toolCallId = "call_1",
                toolName = "lookup",
                state = ToolCallState.OutputAvailable,
                input = buildJsonObject {
                    put("query", JsonPrimitive("weather"))
                },
                output = JsonPrimitive("sunny"),
            ),
            UIMessagePart.File(
                mediaType = "image/png",
                base64 = "aW1n",
                filename = "image.png",
            ),
        )

        for (part in parts) {
            val encoded = aiSdkOutputJson.encodeToString(UIMessagePart.serializer(), part)
            val decoded = aiSdkJson.decodeFromString(UIMessagePart.serializer(), encoded)

            assertEquals(part, decoded, encoded)
        }
    }

    @Test
    fun `ui message Poko leaf keeps value semantics`() {
        val first = UIMessagePart.Text("hello")
        val equal = UIMessagePart.Text("hello")
        val different = UIMessagePart.Text("goodbye")

        assertEquals(first, equal)
        assertEquals(first.hashCode(), equal.hashCode())
        assertNotEquals(first, different)
    }
}
