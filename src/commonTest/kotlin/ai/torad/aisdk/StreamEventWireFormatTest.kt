package ai.torad.aisdk

import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertTrue

class StreamEventWireFormatTest {
    @Test
    fun `stream events use stable serial names not Kotlin class names`() {
        val encoded = aiSdkOutputJson.encodeToString(
            StreamEvent.serializer(),
            StreamEvent.ToolApprovalRequest(
                toolCallId = "call_1",
                toolName = "lookup",
                inputJson = kotlinx.serialization.json.JsonPrimitive("{}"),
            ),
        )

        assertTrue("\"type\":\"tool-approval-request\"" in encoded, encoded)
        assertTrue("ai.torad.aisdk" !in encoded, encoded)
        assertTrue("StreamEvent" !in encoded, encoded)
    }
}
