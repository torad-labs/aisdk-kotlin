package ai.torad.aisdk

import ai.torad.aisdk.ui.StreamToUiMessages
import ai.torad.aisdk.ui.UIMessagePart
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MessageStreamReaderCollisionTest {
    /**
     * Regression: the text/reasoning append helpers shared one id->index map and cast the stored
     * part with the non-null `as` operator. A stream that reused a block id across a reasoning part
     * and a text part (adversarial/quirky wire data) threw ClassCastException, aborting the whole
     * StreamToUiMessages collection and tearing down the in-flight assistant message.
     */
    @Test
    fun `a block id reused across reasoning and text does not crash the stream`() = runTest {
        val events = flowOf(
            StreamEvent.ReasoningStart("b1"),
            StreamEvent.ReasoningDelta("b1", "thinking"),
            StreamEvent.TextDelta("b1", "answer"), // SAME id, different part kind -> unchecked cast pre-fix
        )

        val last = StreamToUiMessages(events, "m1").toList().last()

        assertTrue(last.parts.any { it is UIMessagePart.Reasoning }, "the reasoning part is preserved")
        assertTrue(
            last.parts.any { it is UIMessagePart.Text && it.text.contains("answer") },
            "the text delta lands in a fresh Text part instead of crashing on the cast",
        )
    }

    @Test
    fun `ended tool input placeholders do not collide with later tool calls of the same tool`() = runTest {
        val events = flowOf(
            StreamEvent.ToolInputStart("partial-1", "save"),
            StreamEvent.ToolInputDelta("partial-1", """{"message":"one"}"""),
            StreamEvent.ToolInputEnd("partial-1"),
            StreamEvent.ToolInputStart("partial-2", "save"),
            StreamEvent.ToolInputDelta("partial-2", """{"message":"two"}"""),
            StreamEvent.ToolCall("call-final", "save", buildJsonObject { put("message", "final") }),
        )

        val last = StreamToUiMessages(events, "m1").toList().last()
        val tools = last.parts.filterIsInstance<UIMessagePart.ToolUI>()

        assertEquals(1, tools.size, "stale ToolInputEnd bookkeeping must not leave a duplicate placeholder behind")
        assertEquals("call-final", tools.single().toolCallId)
    }
}
