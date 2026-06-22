package ai.torad.aisdk

import ai.torad.aisdk.providers.BedrockHttp
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Representative degrades-to-default test for the Amazon Bedrock batch (AmazonBedrockProvider,
 * BedrockHttp, BedrockRequest). Covers the shared error extractor directly; the request/response
 * parsers are private and exercised by the existing AmazonBedrockProviderTest.
 */
class BedrockDefensiveParsingTest {
    /**
     * Regression (the M4 bug-class): bedrockErrorMessage probed `message` via
     * `?.jsonPrimitive?.contentOrNull`, which throws on a present-but-non-primitive field — so
     * building the error message crashed with IllegalArgumentException instead of surfacing it.
     * The safe `(X as? JsonPrimitive)?.…` degrades to null -> the raw-body fallback.
     */
    @Test
    fun `bedrockErrorMessage degrades to raw on a non-primitive message`() {
        val parsed = buildJsonObject {
            put("message", buildJsonObject { put("oops", JsonPrimitive(1)) })
        }

        val message = BedrockHttp.bedrockErrorMessage(parsed, "raw-body")

        assertEquals("raw-body", message, "a non-primitive message degrades to the raw body, no crash")
    }
}
