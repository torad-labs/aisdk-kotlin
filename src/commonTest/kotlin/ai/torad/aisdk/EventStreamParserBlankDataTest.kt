package ai.torad.aisdk

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertTrue

class EventStreamParserBlankDataTest {
    /**
     * Regression: a lone empty `data:` line flushed joined "" through safeParseJson(""), which
     * threw and became a ParseResult.Failure (surfaced as StreamEvent.Error). An empty data field
     * is valid SSE (it carries no payload) and must be ignored, not reported as an error. This must
     * also not collide with the M5 non-SSE-body detection (a framed `data:` still counts as SSE).
     */
    @Test
    fun `a blank SSE data line is a no-op in both parse overloads`() = runTest {
        val schema = Schemas.jsonSchema<JsonElement>(buildJsonObject { })

        val flowResults = EventStreamParser.parse(flowOf("data:\n\n"), schema).toList()
        assertTrue(flowResults.none { it is ParseResult.Failure }, "flow: empty data is not a Failure")
        assertTrue(flowResults.isEmpty(), "flow: no event emitted for empty data")

        val stringResults = EventStreamParser.parse("data:\n\n", schema)
        assertTrue(stringResults.none { it is ParseResult.Failure }, "string: empty data is not a Failure")
    }
}
