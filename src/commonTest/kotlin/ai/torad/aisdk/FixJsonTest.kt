package ai.torad.aisdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Port-fidelity tests for [fixJson] + [parsePartialJson], using the
 * exact input/output vectors from Vercel AI SDK v6's official
 * `fix-json.test.ts`. If the Kotlin port diverges from upstream on any
 * of these, it's a port bug.
 */
class FixJsonTest {

    private fun assertFix(input: String, expected: String) =
        assertEquals(expected, PartialJson.fixJson(input), "PartialJson.fixJson(${quoted(input)})")

    @Test
    fun `given complete valid json when fixed then it passes through unchanged`() {
        assertFix("""{"a":1}""", """{"a":1}""")
        assertFix("[1,2,3]", "[1,2,3]")
        assertFix("\"done\"", "\"done\"")
        assertFix("", "")
    }

    @Test
    fun `given a partial literal when fixed then the tail is completed`() {
        assertFix("t", "true")
        assertFix("nul", "null")
        assertFix("fals", "false")
    }

    @Test
    fun `given a truncated number when fixed then it truncates back to the last valid digit`() {
        assertFix("12.", "12")
        assertFix("-", "") // a lone '-' is never valid -> empty
        assertFix("2.5e-", "2.5")
        assertFix("42", "42")
    }

    @Test
    fun `given an open string when fixed then it is closed`() {
        assertFix("\"abc", "\"abc\"")
        // bare trailing backslash: the escape never landed -> backslash dropped, string closed.
        assertFix("\"value with \\", "\"value with \"")
    }

    @Test
    fun `given a string ending at a complete escape when fixed then the escape is preserved`() {
        // SER-003 regression: a COMPLETE escape closes cleanly and must NOT be dropped.
        // input  "ab\"  ->  "ab\""   (valid JSON for ab")
        assertFix("\"ab\\\"", "\"ab\\\"\"")
        // input  "ab\\  ->  "ab\\"   (valid JSON for ab\)
        assertFix("\"ab\\\\", "\"ab\\\\\"")
        // input  "ab\n  ->  "ab\n"   (valid JSON for ab<newline>)
        assertFix("\"ab\\n", "\"ab\\n\"")
    }

    @Test
    fun `given a trailing comma when fixed then it is stripped and the container closed`() {
        assertFix("[1, ", "[1]")
        assertFix("""{"k1": 1, "k2""", """{"k1": 1}""")
    }

    @Test
    fun `given an object truncated at the colon when fixed then it closes empty`() {
        assertFix("""{"key":""", "{}")
        assertFix("{", "{}")
    }

    @Test
    fun `given deeply nested partial json when fixed then every frame closes in stack order`() {
        assertFix(
            """{"a": {"b": ["c", {"d": "e",""",
            """{"a": {"b": ["c", {"d": "e"}]}}""",
        )
        assertFix("[[{}], [{", "[[{}], [{}]]")
    }

    @Test
    fun `given null input when parsed partially then state is UndefinedInput`() {
        val r = PartialJson.parsePartialJson(null)
        assertEquals(PartialJsonState.UndefinedInput, r.state)
        assertNull(r.value)
    }

    @Test
    fun `given complete json when parsed partially then state is SuccessfulParse`() {
        val r = PartialJson.parsePartialJson("""{"a":1}""")
        assertEquals(PartialJsonState.SuccessfulParse, r.state)
        assertNotNull(r.value)
    }

    @Test
    fun `given truncated json when parsed partially then state is RepairedParse with the repaired value`() {
        val r = PartialJson.parsePartialJson("""{"a":1""")
        assertEquals(PartialJsonState.RepairedParse, r.state)
        assertNotNull(r.value)
    }

    @Test
    fun `given an empty string when parsed partially then state is FailedParse not UndefinedInput`() {
        // Per v6: "" is NOT undefined-input; it falls through both parses and fails.
        assertEquals(PartialJsonState.FailedParse, PartialJson.parsePartialJson("").state)
        assertEquals(PartialJsonState.FailedParse, PartialJson.parsePartialJson("garbage").state)
    }

    @Test
    fun `given bare NaN or Infinity when parsed partially then state is FailedParse like JS JSON parse`() {
        // toDoubleOrNull accepts these, but JS JSON.parse throws on all of
        // them — isStrictJsonValue requires isFinite() so they fail.
        assertEquals(PartialJsonState.FailedParse, PartialJson.parsePartialJson("NaN").state)
        assertEquals(PartialJsonState.FailedParse, PartialJson.parsePartialJson("Infinity").state)
        assertEquals(PartialJsonState.FailedParse, PartialJson.parsePartialJson("-Infinity").state)
        assertEquals(PartialJsonState.FailedParse, PartialJson.parsePartialJson("1e999").state)
    }

    private fun quoted(s: String): String = "\"" + s.replace("\"", "\\\"") + "\""
}
