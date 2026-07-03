package ai.torad.aisdk

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EventStreamParserEmptyBodyTest {
    /**
     * Regression: a 2xx response whose body is NOT SSE-framed — e.g. a proxy returns HTTP 200 with
     * a plain JSON error envelope instead of text/event-stream — produced ZERO ParseResults: no
     * Success, no Failure. The caller saw an empty, apparently-successful assistant turn and a real
     * upstream error was completely swallowed. Now the non-SSE body is surfaced as a Failure.
     */
    @Test
    fun `a 200 body that is not SSE-framed surfaces a Failure instead of a silent empty stream`() = runTest {
        val schema = Schemas.jsonSchema<JsonElement>(buildJsonObject { })

        val results = EventStreamParser.parse(
            flowOf("""{"error":{"message":"upstream is down"}}"""),
            schema,
        ).toList()

        val failure = assertIs<ParseResult.Failure>(results.single())
        assertTrue(failure.text.contains("upstream is down"), "the raw non-SSE body is carried for the caller")
    }

    @Test
    fun `a normally-framed SSE stream is unaffected`() = runTest {
        val schema = Schemas.jsonSchema<JsonElement>(buildJsonObject { })

        val results = EventStreamParser.parse(
            flowOf("data: {\"ok\":true}\n\n", "data: [DONE]\n\n"),
            schema,
        ).toList()

        assertTrue(
            results.size == 1 && results.single() is ParseResult.Success,
            "only the real event, no synthetic failure",
        )
    }
}
