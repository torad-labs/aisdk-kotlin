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
    fun `tool-output-denied chunk carries approvalId for duplicate approval correlation without errorText`() = runTest {
        val chunk = ToUIMessageStream(
            flowOf(StreamEvent.ToolOutputDenied("c1", "t", approvalId = "a1", reason = "nope")),
        ).toList().single()
        assertEquals("tool-output-denied", chunk["type"]?.jsonPrimitive?.content)
        assertEquals("c1", chunk["toolCallId"]?.jsonPrimitive?.content)
        assertEquals("a1", chunk["approvalId"]?.jsonPrimitive?.content)
        assertEquals(null, chunk["errorText"], "reason is NOT part of the strict wire schema")
    }

    @Test
    fun `tool-approval-request chunk carries the HMAC signature when set`() = runTest {
        val chunk = ToUIMessageStream(
            flowOf(
                StreamEvent.ToolApprovalRequest(
                    toolCallId = "c1",
                    toolName = "t",
                    inputJson = JsonPrimitive("in"),
                    approvalId = "a1",
                    signature = "sig-abc",
                ),
            ),
        ).toList().single()
        assertEquals("tool-approval-request", chunk["type"]?.jsonPrimitive?.content)
        assertEquals("c1", chunk["toolCallId"]?.jsonPrimitive?.content)
        assertEquals("t", chunk["toolName"]?.jsonPrimitive?.content)
        assertEquals(JsonPrimitive("in"), chunk["input"])
        assertEquals("a1", chunk["approvalId"]?.jsonPrimitive?.content)
        // Security: the signature must reach a JS/TS client or signed-replay is rejected.
        assertEquals("sig-abc", chunk["signature"]?.jsonPrimitive?.content)
    }

    @Test
    fun `tool-approval-request chunk omits signature when null`() = runTest {
        val chunk = ToUIMessageStream(
            flowOf(StreamEvent.ToolApprovalRequest("c1", "t", JsonPrimitive("in"))),
        ).toList().single()
        assertEquals(null, chunk["signature"], "signature absent when the agent holds no approval secret")
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

        assertEquals(listOf("tool-call", "tool-result"), chunks.map { it.type() })
        assertEquals("c1", chunks[0]["toolCallId"]?.jsonPrimitive?.content)
        assertEquals("search", chunks[1]["toolName"]?.jsonPrimitive?.content)
        assertEquals(JsonPrimitive("ok"), chunks[1]["output"])
    }

    @Test
    fun `tool-result chunk carries preliminary and error flags`() = runTest {
        val chunk = ToUIMessageStream(
            flowOf(
                StreamEvent.ToolResult(
                    toolCallId = "c1",
                    toolName = "search",
                    outputJson = JsonPrimitive("progress"),
                    isError = true,
                    preliminary = true,
                ),
            ),
        ).toList().single()

        assertEquals("tool-result", chunk["type"]?.jsonPrimitive?.content)
        assertEquals("search", chunk["toolName"]?.jsonPrimitive?.content)
        assertEquals("true", chunk["isError"]?.jsonPrimitive?.content)
        assertEquals("true", chunk["preliminary"]?.jsonPrimitive?.content)
    }

    @Test
    fun `file chunk carries id data url and media type`() = runTest {
        val chunk = ToUIMessageStream(flowOf(StreamEvent.FilePart("f1", "text/plain", "aGk="))).toList().single()

        assertEquals("file", chunk["type"]?.jsonPrimitive?.content)
        assertEquals("f1", chunk["id"]?.jsonPrimitive?.content)
        assertEquals("text/plain", chunk["mediaType"]?.jsonPrimitive?.content)
        assertEquals("aGk=", chunk["data"]?.jsonPrimitive?.content)
        assertEquals("data:text/plain;base64,aGk=", chunk["url"]?.jsonPrimitive?.content)
    }

    @Test
    fun `tool-input-delta chunk uses the delta field expected by stream decoders`() = runTest {
        val chunk = ToUIMessageStream(
            flowOf(StreamEvent.ToolInputDelta("c1", """{"message":"he""")),
        ).toList().single()

        assertEquals("tool-input-delta", chunk["type"]?.jsonPrimitive?.content)
        assertEquals("""{"message":"he""", chunk["delta"]?.jsonPrimitive?.content)
    }

    @Test
    fun `error chunk uses the provider error field expected by stream decoders`() = runTest {
        val chunk = ToUIMessageStream(
            flowOf(StreamEvent.Error("provider exploded")),
        ).toList().single()

        assertEquals("error", chunk["type"]?.jsonPrimitive?.content)
        assertEquals("provider exploded", chunk["error"]?.jsonPrimitive?.content)
    }
}
