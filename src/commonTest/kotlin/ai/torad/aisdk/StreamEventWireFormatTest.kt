package ai.torad.aisdk

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class StreamEventWireFormatTest {
    @Test
    fun `stream events use stable serial names not Kotlin class names`() {
        val encoded = aiSdkOutputJson.encodeToString(
            StreamEvent.serializer(),
            StreamEvent.ToolApprovalRequest(
                toolCallId = "call_1",
                toolName = "lookup",
                inputJson = JsonPrimitive("{}"),
            ),
        )

        assertTrue("\"type\":\"tool-approval-request\"" in encoded, encoded)
        assertTrue("ai.torad.aisdk" !in encoded, encoded)
        assertTrue("StreamEvent" !in encoded, encoded)
    }

    @Test
    fun `stream event Poko leaves round trip polymorphically as sealed StreamEvent`() {
        val events = listOf(
            StreamEvent.TextDelta(id = "text_1", text = "hello"),
            StreamEvent.ToolApprovalRequest(
                toolCallId = "call_1",
                toolName = "lookup",
                inputJson = buildJsonObject {
                    put("query", JsonPrimitive("weather"))
                },
                approvalId = "approval_1",
                signature = "signature_1",
            ),
            StreamEvent.Finish(
                totalSteps = 2,
                finishReason = FinishReason.Stop,
                usage = Usage(
                    inputTokens = Usage.InputTokenBreakdown(total = 3),
                    outputTokens = Usage.OutputTokenBreakdown(total = 5),
                ),
                rawFinishReason = "stop",
            ),
        )

        for (event in events) {
            val encoded = aiSdkOutputJson.encodeToString(StreamEvent.serializer(), event)
            val decoded = aiSdkJson.decodeFromString(StreamEvent.serializer(), encoded)

            assertEquals(event, decoded, encoded)
        }
    }

    @Test
    fun `stream event Poko leaf keeps value semantics`() {
        val first = StreamEvent.TextDelta(id = "text_1", text = "hello")
        val equal = StreamEvent.TextDelta(id = "text_1", text = "hello")
        val different = StreamEvent.TextDelta(id = "text_1", text = "goodbye")

        assertEquals(first, equal)
        assertEquals(first.hashCode(), equal.hashCode())
        assertNotEquals(first, different)
    }
}
