package ai.torad.aisdk

import ai.torad.aisdk.testing.drainAllItems
import ai.torad.aisdk.ui.TextUIPartState
import ai.torad.aisdk.ui.ToolCallState
import ai.torad.aisdk.ui.UIMessagePart
import ai.torad.aisdk.ui.streamToUiMessages
import ai.torad.aisdk.ui.transformTextToUiMessageStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Coverage 14 — message parts stream emits typed parts in correct
 * sequence, and `outputAs<T>()` extracts typed output from JsonElement
 * at the UI seam.
 */
class MessagePartsTest {

    @Test
    fun `empty text stream emits done assistant message`() = runTest {
        val messages = drainAllItems(transformTextToUiMessageStream(emptyFlow(), "msg_empty"))

        val final = messages.single()
        val text = final.parts.single() as UIMessagePart.Text
        assertEquals("", text.text)
        assertEquals(TextUIPartState.Done, text.state)
    }

    @Test
    fun `text_deltas_grow_a_single_text_part`() = runTest {
        val events = flow {
            emit(StreamEvent.TextStart("t1"))
            emit(StreamEvent.TextDelta("t1", "Hello "))
            emit(StreamEvent.TextDelta("t1", "there"))
            emit(StreamEvent.TextEnd("t1"))
            emit(StreamEvent.Finish(1, FinishReason.Stop, Usage()))
        }
        val snapshots = drainAllItems(streamToUiMessages(events, "msg_1"))
        val final = snapshots.last()
        assertEquals(1, final.parts.size, "exactly one text part")
        assertEquals("Hello there", (final.parts[0] as UIMessagePart.Text).text)
    }

    @Test
    fun `tool_call_progresses_through_states`() = runTest {
        val input = JsonObject(mapOf("location" to JsonPrimitive("nyc")))
        val output = JsonObject(mapOf("temp" to JsonPrimitive(72)))
        val events = flow {
            emit(StreamEvent.ToolInputStart("ti1", "weather"))
            emit(StreamEvent.ToolInputDelta("ti1", input.toString()))
            emit(StreamEvent.ToolInputEnd("ti1"))
            emit(StreamEvent.ToolCall("call_1", "weather", input))
            emit(StreamEvent.ToolResult("call_1", "weather", output))
            emit(StreamEvent.Finish(1, FinishReason.Stop, Usage()))
        }
        val snapshots = drainAllItems(streamToUiMessages(events, "msg_2"))

        val states = snapshots.map { snap ->
            (snap.parts.firstOrNull { it is UIMessagePart.ToolUI } as? UIMessagePart.ToolUI)?.state
        }
        assertTrue(states.contains(ToolCallState.InputStreaming))
        assertTrue(states.contains(ToolCallState.InputAvailable))
        assertTrue(states.contains(ToolCallState.OutputAvailable))

        val finalToolPart = snapshots.last().parts.first { it is UIMessagePart.ToolUI } as UIMessagePart.ToolUI
        assertEquals("weather", finalToolPart.toolName)
        assertEquals(ToolCallState.OutputAvailable, finalToolPart.state)
    }

    @Test
    fun `placeholder removal keeps open text part indices in sync`() = runTest {
        val input = JsonObject(mapOf("location" to JsonPrimitive("nyc")))
        val events = flow {
            emit(StreamEvent.ToolInputStart("input_1", "weather"))
            emit(StreamEvent.TextStart("text_1"))
            emit(StreamEvent.TextDelta("text_1", "before "))
            emit(StreamEvent.ToolInputEnd("input_1"))
            emit(StreamEvent.ToolCall("call_1", "weather", input))
            emit(StreamEvent.TextDelta("text_1", "after"))
            emit(StreamEvent.TextEnd("text_1"))
            emit(StreamEvent.Finish(1, FinishReason.Stop, Usage()))
        }

        val final = drainAllItems(streamToUiMessages(events, "msg_placeholder")).last()

        val text = final.parts.filterIsInstance<UIMessagePart.Text>().single()
        val tool = final.parts.filterIsInstance<UIMessagePart.ToolUI>().single()
        assertEquals("before after", text.text)
        assertEquals(ToolCallState.InputAvailable, tool.state)
        assertEquals("call_1", tool.toolCallId)
    }

    @Test
    fun `same-name tool placeholder removal keeps other active parallel calls`() = runTest {
        val input = JsonObject(mapOf("location" to JsonPrimitive("nyc")))
        val events = flow {
            emit(StreamEvent.ToolInputStart("input_1", "weather"))
            emit(StreamEvent.ToolInputStart("input_2", "weather"))
            emit(StreamEvent.ToolInputDelta("input_1", input.toString()))
            emit(StreamEvent.ToolInputDelta("input_2", input.toString()))
            emit(StreamEvent.ToolCall("call_1", "weather", input))
            emit(StreamEvent.Finish(1, FinishReason.Stop, Usage()))
        }

        val final = drainAllItems(streamToUiMessages(events, "msg_same_name")).last()

        val tools = final.parts.filterIsInstance<UIMessagePart.ToolUI>()
        assertEquals(2, tools.size)
        assertEquals(ToolCallState.InputAvailable, tools.single { it.toolCallId == "call_1" }.state)
        assertEquals(ToolCallState.InputStreaming, tools.single { it.toolCallId == "input_2" }.state)
    }

    @Test
    fun `same-name placeholder removal uses current shifted indices`() = runTest {
        val input = JsonObject(mapOf("location" to JsonPrimitive("nyc")))
        val events = flow {
            emit(StreamEvent.ToolInputStart("input_1", "weather"))
            emit(StreamEvent.TextStart("text_1"))
            emit(StreamEvent.TextDelta("text_1", "between"))
            emit(StreamEvent.ToolInputStart("input_2", "weather"))
            emit(StreamEvent.ToolInputDelta("input_1", input.toString()))
            emit(StreamEvent.ToolInputDelta("input_2", input.toString()))
            emit(StreamEvent.ToolCall("call_1", "weather", input))
            emit(StreamEvent.TextEnd("text_1"))
            emit(StreamEvent.Finish(1, FinishReason.Stop, Usage()))
        }

        val final = drainAllItems(streamToUiMessages(events, "msg_shifted_placeholders")).last()

        val tools = final.parts.filterIsInstance<UIMessagePart.ToolUI>()
        val text = final.parts.filterIsInstance<UIMessagePart.Text>().single()
        assertEquals(2, tools.size)
        assertEquals(ToolCallState.InputAvailable, tools.single { it.toolCallId == "call_1" }.state)
        assertEquals(ToolCallState.InputStreaming, tools.single { it.toolCallId == "input_2" }.state)
        assertEquals("between", text.text)
    }

    @Test
    fun `tool_error_state_surfaces_message`() = runTest {
        val events = flow {
            emit(StreamEvent.ToolInputStart("ti1", "weather"))
            emit(StreamEvent.ToolInputEnd("ti1"))
            emit(StreamEvent.ToolCall("call_1", "weather", JsonObject(emptyMap())))
            emit(StreamEvent.ToolError("call_1", "weather", "API down"))
            emit(StreamEvent.Finish(1, FinishReason.Error, Usage()))
        }
        val snapshots = drainAllItems(streamToUiMessages(events, "msg_3"))
        val finalToolPart = snapshots.last().parts.first { it is UIMessagePart.ToolUI } as UIMessagePart.ToolUI
        assertEquals(ToolCallState.OutputError, finalToolPart.state)
        assertEquals("API down", finalToolPart.error)
    }

    @Test
    fun `reasoning_blocks_become_separate_parts`() = runTest {
        val events = flow {
            emit(StreamEvent.ReasoningStart("r1"))
            emit(StreamEvent.ReasoningDelta("r1", "thinking..."))
            emit(StreamEvent.ReasoningEnd("r1"))
            emit(StreamEvent.TextStart("t1"))
            emit(StreamEvent.TextDelta("t1", "answer"))
            emit(StreamEvent.TextEnd("t1"))
            emit(StreamEvent.Finish(1, FinishReason.Stop, Usage()))
        }
        val final = drainAllItems(streamToUiMessages(events, "msg_4")).last()
        assertEquals(2, final.parts.size, "reasoning + text")
        assertTrue(final.parts[0] is UIMessagePart.Reasoning)
        assertEquals("thinking...", (final.parts[0] as UIMessagePart.Reasoning).text)
        assertTrue(final.parts[1] is UIMessagePart.Text)
        assertEquals("answer", (final.parts[1] as UIMessagePart.Text).text)
    }
}
