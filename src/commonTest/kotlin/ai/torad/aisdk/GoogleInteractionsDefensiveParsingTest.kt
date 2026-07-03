package ai.torad.aisdk

import ai.torad.aisdk.providers.GoogleInteractions
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

class GoogleInteractionsDefensiveParsingTest {
    /**
     * Regression (the M4 bug-class): googleInteractionsUsage read server token counts via
     * `?.jsonPrimitive?.intOrNull`, which throws IllegalArgumentException when the field is present
     * but a non-primitive (object/array). The safe `(X as? JsonPrimitive)?.…` degrades to null,
     * preserving the existing `?: 0` fallback.
     */
    @Test
    fun `googleInteractionsUsage degrades to zero on a non-primitive token count`() {
        val element = buildJsonObject {
            // total_input_tokens is an object, not a number — a malformed/quirky server usage body.
            put("total_input_tokens", buildJsonObject { put("x", JsonPrimitive(1)) })
        }

        val usage = GoogleInteractions.googleInteractionsUsage(element)

        assertEquals(0, usage.inputTokens.total, "a non-primitive token count degrades to 0, no crash")
    }
}
