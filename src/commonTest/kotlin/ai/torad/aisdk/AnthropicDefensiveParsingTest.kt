package ai.torad.aisdk

import ai.torad.aisdk.providers.AnthropicMessagesLanguageModel
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

class AnthropicDefensiveParsingTest {
    /**
     * Regression (the M4 bug-class in providers): reading a server-controlled field via
     * `?.jsonPrimitive` throws IllegalArgumentException when the field is PRESENT but a
     * non-primitive (object/array) — `.jsonPrimitive` throws even through `?.`. The safe form
     * `(X as? JsonPrimitive)?.…` degrades to null, preserving the existing `?: fallback`.
     */
    @Test
    fun `anthropicErrorMessage degrades to the raw body on a non-primitive error message`() {
        val parsed = buildJsonObject {
            put(
                "error",
                buildJsonObject {
                    // `message` is an array, not the expected string — a malformed/quirky server body.
                    put("message", JsonArray(listOf(JsonPrimitive("nested"))))
                },
            )
        }

        val message = AnthropicMessagesLanguageModel.anthropicErrorMessage(parsed, raw = "raw error body")

        assertEquals("raw error body", message, "a non-primitive message degrades to the raw fallback, no crash")
    }
}
