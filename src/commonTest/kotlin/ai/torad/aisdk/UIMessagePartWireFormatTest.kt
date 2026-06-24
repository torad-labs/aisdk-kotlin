package ai.torad.aisdk

import ai.torad.aisdk.ui.ToolCallState
import ai.torad.aisdk.ui.UIMessagePart
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
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
}
