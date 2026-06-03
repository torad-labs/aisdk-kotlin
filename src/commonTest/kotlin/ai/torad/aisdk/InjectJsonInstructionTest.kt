package ai.torad.aisdk

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Port-fidelity tests for [injectJsonInstruction] +
 * [injectJsonInstructionIntoMessages]. The expected strings are built
 * from the verbatim v6 default templates ("JSON schema:", the
 * schema-aware suffix, and the generic suffix) so any drift from
 * upstream phrasing is a port bug.
 */
class InjectJsonInstructionTest {

    private fun obj(raw: String): JsonElement =
        Json.parseToJsonElement(raw)

    @Test
    fun `given a prompt and no schema when injected then the generic suffix follows a blank line`() {
        // GIVEN
        val prompt = "Do the thing"

        // WHEN
        val result = injectJsonInstruction(prompt = prompt)

        // THEN
        assertEquals("Do the thing\n\nYou MUST answer with JSON.", result)
    }

    @Test
    fun `given a schema and no prompt when injected then prefix schema and schema-aware suffix are emitted`() {
        // GIVEN
        val schema = obj("""{"type":"object"}""")

        // WHEN
        val result = injectJsonInstruction(schema = schema)

        // THEN
        assertEquals(
            "JSON schema:\n{\"type\":\"object\"}\n" +
                "You MUST answer with a JSON object that matches the JSON schema above.",
            result,
        )
    }

    @Test
    fun `given both a prompt and a schema when injected then all five lines appear in order`() {
        // GIVEN
        val schema = obj("""{"type":"string"}""")

        // WHEN
        val result = injectJsonInstruction(prompt = "Be precise", schema = schema)

        // THEN
        assertEquals(
            "Be precise\n\nJSON schema:\n{\"type\":\"string\"}\n" +
                "You MUST answer with a JSON object that matches the JSON schema above.",
            result,
        )
    }

    @Test
    fun `given an empty prompt and no schema when injected then only the generic suffix remains`() {
        // GIVEN
        val prompt = ""

        // WHEN
        val result = injectJsonInstruction(prompt = prompt)

        // THEN — empty prompt contributes no line and no blank separator.
        assertEquals("You MUST answer with JSON.", result)
    }

    @Test
    fun `given a leading system message when injected into messages then its text seeds the prompt`() {
        // GIVEN
        val schema = obj("""{"type":"object"}""")
        val messages = listOf(systemMessage("Sys"), userMessage("Hi"))

        // WHEN
        val result = injectJsonInstructionIntoMessages(messages, schema = schema)

        // THEN
        val expectedSystem = "Sys\n\nJSON schema:\n{\"type\":\"object\"}\n" +
            "You MUST answer with a JSON object that matches the JSON schema above."
        assertEquals(2, result.size)
        assertEquals(systemMessage(expectedSystem), result[0])
        assertEquals(userMessage("Hi"), result[1])
    }

    @Test
    fun `given no leading system message when injected into messages then a system message is prepended`() {
        // GIVEN
        val messages = listOf(userMessage("Hi"))

        // WHEN
        val result = injectJsonInstructionIntoMessages(messages)

        // THEN
        assertEquals(2, result.size)
        assertEquals(systemMessage("You MUST answer with JSON."), result[0])
        assertEquals(userMessage("Hi"), result[1])
    }
}
