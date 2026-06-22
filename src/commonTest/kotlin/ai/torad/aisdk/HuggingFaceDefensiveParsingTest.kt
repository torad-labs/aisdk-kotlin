package ai.torad.aisdk

import ai.torad.aisdk.providers.HuggingFaceProviderSettings
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

class HuggingFaceDefensiveParsingTest {
    /**
     * Regression (the M4 bug-class): huggingFaceUsage read server token counts via
     * `?.jsonPrimitive?.intOrNull`, which throws on a present-but-non-primitive field. The safe
     * `(X as? JsonPrimitive)?.…` degrades to null, preserving the `?: 0` fallback.
     */
    @Test
    fun `huggingFaceUsage degrades to zero on a non-primitive token count`() {
        val element = buildJsonObject {
            put("input_tokens", buildJsonObject { put("x", JsonPrimitive(1)) })
        }

        val usage = HuggingFaceProviderSettings().huggingFaceUsage(element)

        assertEquals(0, usage.inputTokens.total, "a non-primitive token count degrades to 0, no crash")
    }
}
