package ai.torad.aisdk

import ai.torad.aisdk.protocol.ProtocolAdapters
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * One wire object, two decode paths, one answer.
 *
 * A gateway mid-transition can send a tool-result carrying BOTH `modelVisible` and the legacy
 * `modelOutput`. The streamed decode and the content-part replay read the same bytes, so if they
 * disagree about which name wins, the model sees a different payload depending on which path
 * delivered the result — and the stream-vs-replay equivalence the rest of the V3 work rests on
 * quietly stops holding.
 *
 * `modelVisible` is canonical because that is the name GatewayContentEncoder writes, so it is also
 * what a round-trip through this SDK produces.
 */
class GatewayModelVisiblePrecedenceTest {

    private companion object {
        const val VISIBLE = "the summary the model should see"
        const val LEGACY = "the full payload under the legacy name"
    }

    /** A tool-result carrying both names with distinguishable values. */
    private fun bothNames() = buildJsonObject {
        put("type", "tool-result")
        put("toolCallId", "call-1")
        put("toolName", "searchDocs")
        put("result", JsonPrimitive("the raw result"))
        put("modelVisible", JsonPrimitive(VISIBLE))
        put("modelOutput", JsonPrimitive(LEGACY))
    }

    @Test
    fun `both decode paths agree that modelVisible wins over the legacy modelOutput`() {
        val wire = bothNames()

        val streamed = ProtocolAdapters.gatewayStreamEventFromJson(wire)
        val replayed = ProtocolAdapters.gatewayContentPartFromJson(wire)

        val streamedEvent = assertIs<StreamEvent.ToolResult>(streamed)
        val replayedPart = assertIs<ContentPart.ToolResult>(replayed)

        val streamedVisible = assertIs<ToolResultOutput.Text>(streamedEvent.modelOutput)
        assertEquals(
            VISIBLE,
            streamedVisible.text,
            "the streamed decode must prefer modelVisible, the name the encoder writes",
        )
        assertEquals(
            JsonPrimitive(VISIBLE),
            replayedPart.modelVisible,
            "the content-part decode must prefer modelVisible too",
        )
    }

    @Test
    fun `the legacy modelOutput is still honoured when it arrives alone`() {
        val wire = buildJsonObject {
            put("type", "tool-result")
            put("toolCallId", "call-1")
            put("toolName", "searchDocs")
            put("result", JsonPrimitive("the raw result"))
            put("modelOutput", JsonPrimitive(LEGACY))
        }

        val streamedEvent = assertIs<StreamEvent.ToolResult>(ProtocolAdapters.gatewayStreamEventFromJson(wire))
        val replayedPart = assertIs<ContentPart.ToolResult>(ProtocolAdapters.gatewayContentPartFromJson(wire))

        assertEquals(LEGACY, assertIs<ToolResultOutput.Text>(streamedEvent.modelOutput).text)
        assertEquals(JsonPrimitive(LEGACY), replayedPart.modelVisible)
    }
}
