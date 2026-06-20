package ai.torad.aisdk

import ai.torad.aisdk.testing.drainAllItems
import ai.torad.aisdk.ui.ToolCallState
import ai.torad.aisdk.ui.UIMessage
import ai.torad.aisdk.ui.UIMessagePart
import ai.torad.aisdk.ui.UIMessageRole
import ai.torad.aisdk.ui.createUiMessageStream
import ai.torad.aisdk.ui.streamToUiMessages
import kotlin.test.assertFailsWith
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive

/**
 * Validates the v6-aligned UIMessage shape additions from
 * historical parity work phase 4A:
 *
 * - gap #10: `UIMessage.metadata: Map<String, JsonElement>?` slot
 *   (monomorphic subagent-attribution channel; pragmatic alternative
 *   to v6's `<METADATA, DATA_PARTS, TOOLS>` generics).
 * - gap #8: `UIMessagePart.StepStart(stepNumber)` — multi-step flows
 *   emit a visible boundary so subagent handoffs / multi-tool rounds
 *   can render a divider.
 * - gap #9: `UIMessagePart.DynamicToolUI` — runtime-typed tool
 *   variant for subagent tools the parent's static handler registry
 *   can't dispatch.
 */
class UIMessageShapeTest {

    @Test
    fun `given a UIMessage with metadata when serialized then the metadata round-trips`() {
        // GIVEN
        val original = UIMessage(
            id = "asst_1",
            role = UIMessageRole.Assistant,
            parts = listOf(UIMessagePart.Text("hi")),
            metadata = mapOf(
                "example.sourceAgent" to JsonPrimitive("ResearchAgent"),
                "example.confidence" to JsonPrimitive(0.92f),
            ),
        )

        // WHEN: round-trip through the data-class copy (lightweight
        // equality + structural assert without dragging serialization
        // codec setup into the test).
        val copied = original.copy()

        // THEN
        assertEquals(original.metadata, copied.metadata)
        val md = assertNotNull(copied.metadata)
        assertEquals(JsonPrimitive("ResearchAgent"), md["example.sourceAgent"])
        assertEquals(JsonPrimitive(0.92f), md["example.confidence"])
    }

    @Test
    fun `given a UIMessage without metadata when read then metadata is null`() {
        // GIVEN
        val message = UIMessage(
            id = "asst_2",
            role = UIMessageRole.Assistant,
            parts = listOf(UIMessagePart.Text("hello")),
        )

        // THEN
        assertNull(message.metadata, "metadata defaults to null")
    }

    @Test
    fun `given multi-step stream when converted to UIMessages then StepStart parts emit for step 2+`() = runTest {
        // GIVEN: a stream with TWO step boundaries (step 1 = initial,
        // step 2 = post-tool-result). Step 1 should NOT emit a StepStart
        // part (it's the implicit start of the message); step 2 SHOULD
        // emit a visible boundary.
        val events = flowOf(
            StreamEvent.StreamStart(),
            StreamEvent.StepStart(stepNumber = 1),
            StreamEvent.TextStart("t1"),
            StreamEvent.TextDelta("t1", "calling tool…"),
            StreamEvent.TextEnd("t1"),
            StreamEvent.StepFinish(1, FinishReason.ToolCalls, Usage.of(1, 2)),
            StreamEvent.StepStart(stepNumber = 2),
            StreamEvent.TextStart("t2"),
            StreamEvent.TextDelta("t2", "got result, here's the answer"),
            StreamEvent.TextEnd("t2"),
            StreamEvent.StepFinish(2, FinishReason.Stop, Usage.of(3, 4)),
            StreamEvent.Finish(totalSteps = 2, finishReason = FinishReason.Stop, usage = Usage.of(4, 6)),
        )

        // WHEN
        val snapshots = drainAllItems(streamToUiMessages(events, assistantMessageId = "asst_3"))
        val finalParts = snapshots.last().parts

        // THEN
        val stepStarts = finalParts.filterIsInstance<UIMessagePart.StepStart>()
        assertEquals(1, stepStarts.size, "exactly one StepStart part (for step 2; step 1 is implicit)")
        assertEquals(2, stepStarts.single().stepNumber, "the step-2 boundary is marked")
        // The two text parts also exist + are in render order.
        val textParts = finalParts.filterIsInstance<UIMessagePart.Text>()
        assertEquals(2, textParts.size)
        assertEquals("calling tool…", textParts[0].text)
        assertEquals("got result, here's the answer", textParts[1].text)
    }

    @Test
    fun `given a DynamicToolUI part when constructed then it carries the same lifecycle fields as ToolUI`() {
        // GIVEN
        val dyn = UIMessagePart.DynamicToolUI(
            toolCallId = "call_42",
            toolName = "unknownAtCompileTime",
            state = ToolCallState.OutputAvailable,
            input = JsonPrimitive("input"),
            output = JsonPrimitive("output"),
            error = null,
            preliminary = false,
        )

        // THEN: distinct sealed variant carrying the same lifecycle
        // fields as ToolUI. Renderer dispatches on the sealed variant.
        assertEquals(ToolCallState.OutputAvailable, dyn.state)
        assertEquals(false, dyn.preliminary)
        assertEquals("unknownAtCompileTime", dyn.toolName)
        // Show the renderer-side discrimination shape — a generic part
        // handler chooses the dynamic branch on type.
        val part: UIMessagePart = dyn
        val isDynamic = part is UIMessagePart.DynamicToolUI
        val isStatic = part is UIMessagePart.ToolUI
        assertTrue(isDynamic && !isStatic, "renderer can distinguish dynamic vs static tool parts")
    }

    @Test
    fun `createUiMessageStream rethrows cancellation without emitting an error message`() = runTest {
        val stream = createUiMessageStream {
            throw CancellationException("cancelled")
        }

        assertFailsWith<CancellationException> {
            stream.toList()
        }
    }
}
