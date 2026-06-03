package ai.torad.aisdk

import ai.torad.aisdk.testing.drainAllItems
import ai.torad.aisdk.ui.TextUIPartState
import ai.torad.aisdk.ui.UIMessagePart
import ai.torad.aisdk.ui.streamToUiMessages
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest

/**
 * Validates Phase 4E #30: `TextUIPart.state` + `ReasoningUIPart.state`
 * — streaming-vs-final marker mirrors v6's
 * `TextUIPart.state: 'streaming' | 'done'`. Renderers consume this to
 * drive typing cursors during streaming and commit a final layout pass
 * on `done`.
 *
 * Three behaviors covered:
 *  1. Text parts emitted while `TextDelta` is arriving carry
 *     [TextUIPartState.Streaming].
 *  2. After `TextEnd`, the same part flips to [TextUIPartState.Done].
 *  3. Same flow for `ReasoningStart` / `ReasoningDelta` /
 *     `ReasoningEnd`.
 */
class TextUIPartStateTest {

    @Test
    fun `given an in-flight text part when delta arrives then state is Streaming`() = runTest {
        // GIVEN — TextStart then TextDelta, NO TextEnd yet.
        val events = flowOf<StreamEvent>(
            StreamEvent.TextStart(id = "t1"),
            StreamEvent.TextDelta(id = "t1", text = "Hello "),
            StreamEvent.TextDelta(id = "t1", text = "world"),
        )

        // WHEN
        val snapshots = drainAllItems(streamToUiMessages(events, assistantMessageId = "asst_1"))

        // THEN
        val lastTextPart = snapshots.last().parts.last() as UIMessagePart.Text
        assertEquals("Hello world", lastTextPart.text)
        assertEquals(
            TextUIPartState.Streaming,
            lastTextPart.state,
            "mid-stream text part stays Streaming so renderers keep the cursor visible",
        )
    }

    @Test
    fun `given a completed text stream when TextEnd arrives then state flips to Done`() = runTest {
        // GIVEN — full stream end-to-end.
        val events = flowOf<StreamEvent>(
            StreamEvent.TextStart(id = "t1"),
            StreamEvent.TextDelta(id = "t1", text = "Hello "),
            StreamEvent.TextDelta(id = "t1", text = "world"),
            StreamEvent.TextEnd(id = "t1"),
        )

        // WHEN
        val snapshots = drainAllItems(streamToUiMessages(events, assistantMessageId = "asst_1"))

        // THEN
        val finalPart = snapshots.last().parts.last() as UIMessagePart.Text
        assertEquals("Hello world", finalPart.text)
        assertEquals(
            TextUIPartState.Done,
            finalPart.state,
            "TextEnd flips the part to Done — renderer commits the final layout",
        )
    }

    @Test
    fun `given an in-flight reasoning part when delta arrives then state is Streaming`() = runTest {
        // GIVEN — reasoning stream without close.
        val events = flowOf<StreamEvent>(
            StreamEvent.ReasoningStart(id = "r1"),
            StreamEvent.ReasoningDelta(id = "r1", text = "thinking…"),
        )

        // WHEN
        val snapshots = drainAllItems(streamToUiMessages(events, assistantMessageId = "asst_1"))

        // THEN
        val part = snapshots.last().parts.last() as UIMessagePart.Reasoning
        assertEquals("thinking…", part.text)
        assertEquals(TextUIPartState.Streaming, part.state)
    }

    @Test
    fun `given reasoning end-to-end when ReasoningEnd fires then state flips to Done`() = runTest {
        // GIVEN
        val events = flowOf<StreamEvent>(
            StreamEvent.ReasoningStart(id = "r1"),
            StreamEvent.ReasoningDelta(id = "r1", text = "thought"),
            StreamEvent.ReasoningEnd(id = "r1"),
        )

        // WHEN
        val snapshots = drainAllItems(streamToUiMessages(events, assistantMessageId = "asst_1"))

        // THEN
        val part = snapshots.last().parts.last() as UIMessagePart.Reasoning
        assertEquals(TextUIPartState.Done, part.state)
    }

    @Test
    fun `given a fresh part constructed without state when read then state defaults to Done`() {
        // GIVEN/WHEN — default-arg path for rehydration / test fixtures.
        val text = UIMessagePart.Text(text = "hello")
        val reasoning = UIMessagePart.Reasoning(text = "thought")

        // THEN — backwards-compat default: existing callers that don't
        // pass `state` get the final layout pass with no flicker.
        assertEquals(TextUIPartState.Done, text.state)
        assertEquals(TextUIPartState.Done, reasoning.state)
    }

    @Test
    fun `given two text parts in one message when first ends and second starts then states are independent`() =
        runTest {
            // GIVEN — two distinct text blocks (the v6 stream can interleave
            // multiple text segments + reasoning blocks).
            val events = flowOf<StreamEvent>(
                StreamEvent.TextStart(id = "t1"),
                StreamEvent.TextDelta(id = "t1", text = "first"),
                StreamEvent.TextEnd(id = "t1"),
                StreamEvent.TextStart(id = "t2"),
                StreamEvent.TextDelta(id = "t2", text = "second"),
            )

            // WHEN
            val snapshots = drainAllItems(streamToUiMessages(events, assistantMessageId = "asst_1"))

            // THEN — t1 stays Done; t2 still Streaming.
            val finalParts = snapshots.last().parts
            assertEquals(2, finalParts.size)
            val t1 = finalParts[0] as UIMessagePart.Text
            val t2 = finalParts[1] as UIMessagePart.Text
            assertEquals(TextUIPartState.Done, t1.state)
            assertEquals(TextUIPartState.Streaming, t2.state)
            assertTrue(t1.text == "first" && t2.text == "second")
        }
}
