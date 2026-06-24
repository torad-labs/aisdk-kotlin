package ai.torad.aisdk

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EventStreamParserProtocolChaosTest {

    @Test
    fun `SSE comments event ids and retry fields do not synthesize failures`() = runTest {
        val results = EventStreamParser.parse(
            flowOf(": keepalive\n", "event: message\n", "id: 42\n", "retry: 1000\n\n"),
            jsonElementSchema(),
        ).toList()

        assertTrue(results.isEmpty(), "framing-only SSE input should be ignored, not reported as malformed JSON")
    }

    @Test
    fun `multi-line SSE data is joined into a single JSON payload`() = runTest {
        val results = EventStreamParser.parse(
            flowOf("data: {\"message\":\n", "data: \"hello\"}\n\n"),
            jsonElementSchema(),
        ).toList()

        val success = assertIs<ParseResult.Success<JsonElement>>(results.single())
        assertEquals("hello", success.value.jsonObject.getValue("message").jsonPrimitive.content)
    }

    @Test
    fun `invalid JSON frame is surfaced with the original frame text`() = runTest {
        val results = EventStreamParser.parse(
            flowOf("data: {\"message\":\n\n"),
            jsonElementSchema(),
        ).toList()

        val failure = assertIs<ParseResult.Failure>(results.single())
        assertTrue(failure.text.contains("message"), "caller needs the bad frame text for provider diagnostics")
    }

    @Test
    fun `abrupt EOF after a complete data line still flushes the event`() = runTest {
        val results = EventStreamParser.parse(
            flowOf("data: {\"done\":true}\n"),
            jsonElementSchema(),
        ).toList()

        assertIs<ParseResult.Success<JsonElement>>(results.single())
    }

    private fun jsonElementSchema(): Schema<JsonElement> = Schemas.jsonSchema(buildJsonObject { })
}
