package ai.torad.aisdk

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class FixJsonUnicodeEscapeTest {
    /**
     * Regression: the port omitted INSIDE_STRING_UNICODE_ESCAPE and treated `\u` as a complete
     * escape, then advanced lastValidIndex over the trailing hex digits. A stream cut mid-\uXXXX
     * left lastValidIndex inside the incomplete escape, so the drain produced invalid JSON like
     * `"caf\u00"` -> kotlinx threw -> FailedParse (instead of the v6 RepairedParse that drops the
     * partial escape and closes the string).
     */
    @Test
    fun `parsePartialJson repairs a string truncated mid unicode escape`() {
        // Raw string: the literal bytes are {"name":"caf\u00 (cut after 2 of 4 hex digits).
        val result = PartialJson.parsePartialJson("""{"name":"caf\u00""")

        assertEquals(PartialJsonState.RepairedParse, result.state, "truncation mid-escape repairs, not fails")
        val obj = assertIs<JsonObject>(result.value)
        assertEquals("caf", obj["name"]?.jsonPrimitive?.content, "the partial \\u00 is dropped, string closed")
    }

    /** A COMPLETE \uXXXX escape still round-trips (the common, non-truncated case is unaffected). */
    @Test
    fun `parsePartialJson keeps a complete unicode escape`() {
        val result = PartialJson.parsePartialJson("""{"name":"café"}""")

        assertEquals(PartialJsonState.SuccessfulParse, result.state)
        val obj = assertIs<JsonObject>(result.value)
        assertEquals("café", obj["name"]?.jsonPrimitive?.content)
    }
}
