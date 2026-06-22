package ai.torad.aisdk

import ai.torad.aisdk.providers.GoogleHttp
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Representative degrades-to-default test for the Google media batch (GoogleMediaModels,
 * GoogleVertexProvider, GoogleHttp). Covers the shared Google error extractor directly; the
 * media/vertex parsers are private and exercised by the existing Google provider tests.
 */
class GoogleMediaDefensiveParsingTest {
    /**
     * Regression (the M4 bug-class): googleErrorMessage probed `error.message` via
     * `?.jsonPrimitive?.contentOrNull`, which throws on a present-but-non-primitive field — so
     * building the error message crashed with IllegalArgumentException instead of surfacing it.
     * The safe `(X as? JsonPrimitive)?.…` degrades to null -> the raw-body fallback.
     */
    @Test
    fun `googleErrorMessage degrades to raw on a non-primitive error message`() {
        val parsed = buildJsonObject {
            put("error", buildJsonObject { put("message", buildJsonObject { put("oops", JsonPrimitive(1)) }) })
        }

        val message = GoogleHttp.googleErrorMessage(parsed, "raw-body")

        assertEquals("raw-body", message, "a non-primitive error.message degrades to the raw body, no crash")
    }
}
