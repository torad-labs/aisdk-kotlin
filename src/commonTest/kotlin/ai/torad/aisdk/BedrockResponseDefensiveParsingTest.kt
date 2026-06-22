package ai.torad.aisdk

import ai.torad.aisdk.providers.BedrockResponse
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

class BedrockResponseDefensiveParsingTest {
    /**
     * Regression (the M4 bug-class): bedrockUsage read server token counts via
     * `?.jsonPrimitive?.intOrNull`, which throws on a present-but-non-primitive field. The safe
     * `(X as? JsonPrimitive)?.…` degrades to null, preserving the `?: 0` fallback.
     */
    @Test
    fun `bedrockUsage degrades to zero on a non-primitive token count`() {
        val element = buildJsonObject {
            put("inputTokens", buildJsonObject { put("x", JsonPrimitive(1)) })
        }

        val usage = BedrockResponse.bedrockUsage(element)

        assertEquals(0, usage.inputTokens.total, "a non-primitive inputTokens degrades to 0, no crash")
    }
}
