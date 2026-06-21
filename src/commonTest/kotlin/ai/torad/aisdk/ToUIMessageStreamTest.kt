package ai.torad.aisdk

import ai.torad.aisdk.ui.ToUIMessageStream
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class ToUIMessageStreamTest {
    private fun JsonObject.type() = this["type"]?.jsonPrimitive?.content

    @Test
    fun `maps core stream events to their UIMessageChunk wire shapes`() = runTest {
        val chunks = ToUIMessageStream(
            flowOf(
                StreamEvent.StreamStart(),
                StreamEvent.TextStart("t1"),
                StreamEvent.TextDelta("t1", "hi"),
                StreamEvent.TextEnd("t1"),
                StreamEvent.Finish(1, FinishReason.Stop, Usage()),
            ),
        ).toList()

        assertEquals(listOf("start", "text-start", "text-delta", "text-end", "finish"), chunks.map { it.type() })
        val delta = chunks[2]
        assertEquals("t1", delta["id"]?.jsonPrimitive?.content)
        assertEquals("hi", delta["delta"]?.jsonPrimitive?.content)
        // finish chunk carries the wire finishReason (was dropped).
        assertEquals("stop", chunks.last()["finishReason"]?.jsonPrimitive?.content)
    }

    @Test
    fun `finish chunk maps tool-calls finishReason to its wire value`() = runTest {
        val chunk = ToUIMessageStream(flowOf(StreamEvent.Finish(1, FinishReason.ToolCalls, Usage()))).toList().single()
        assertEquals("tool-calls", chunk["finishReason"]?.jsonPrimitive?.content)
    }

    @Test
    fun `tool-output-denied chunk carries only type and toolCallId without errorText`() = runTest {
        val chunk = ToUIMessageStream(
            flowOf(StreamEvent.ToolOutputDenied("c1", "t", approvalId = "a1", reason = "nope")),
        ).toList().single()
        assertEquals("tool-output-denied", chunk["type"]?.jsonPrimitive?.content)
        assertEquals("c1", chunk["toolCallId"]?.jsonPrimitive?.content)
        assertEquals(null, chunk["errorText"], "reason is NOT part of the strict wire schema")
    }

    @Test
    fun `maps a tool round-trip and drops events with no wire counterpart`() = runTest {
        val chunks = ToUIMessageStream(
            flowOf(
                StreamEvent.ResponseMetadata(id = "r1"), // dropped
                StreamEvent.ToolCall("c1", "search", JsonPrimitive("q")),
                StreamEvent.ToolResult("c1", "search", JsonPrimitive("ok")),
                StreamEvent.ToolInputEnd("c1"), // dropped
            ),
        ).toList()

        assertEquals(listOf("tool-input-available", "tool-output-available"), chunks.map { it.type() })
        assertEquals("c1", chunks[0]["toolCallId"]?.jsonPrimitive?.content)
        assertEquals(JsonPrimitive("ok"), chunks[1]["output"])
    }
}
