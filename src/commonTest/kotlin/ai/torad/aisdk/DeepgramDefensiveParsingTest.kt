package ai.torad.aisdk

import ai.torad.aisdk.providers.DeepgramProviderSettings
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertTrue

class DeepgramDefensiveParsingTest {
    /**
     * Regression (the M4 bug-class): deepgramErrorMessage probed `error.message` via
     * `?.jsonPrimitive?.contentOrNull`, which throws on a present-but-non-primitive field — so
     * building the error message crashed with IllegalArgumentException instead of surfacing it.
     * The safe `(X as? JsonPrimitive)?.…` degrades to null -> the raw-body fallback.
     */
    @Test
    fun `deepgramErrorMessage degrades to raw on a non-primitive error message`() {
        val parsed = buildJsonObject {
            put("error", buildJsonObject { put("message", buildJsonObject { put("oops", JsonPrimitive(1)) }) })
        }

        val message = DeepgramProviderSettings(apiKey = "k").deepgramErrorMessage(400, parsed, "raw-body")

        assertTrue(
            message.contains("Deepgram request failed (400)"),
            "a non-primitive error.message degrades to the raw body, no crash",
        )
    }

    /**
     * Regression (the M4 sibling-accessor bug-class, Wave 7): deepgramErrorMessage navigates
     * `error?.jsonObject?.get("message")` — the `?.jsonObject` throws if `error` is present but a
     * primitive (e.g. `{"error":"plain string"}`), crashing BEFORE the leaf `as? JsonPrimitive`.
     * The safe `(error as? JsonObject)?.…` degrades to null -> the `error as? JsonPrimitive` fallback.
     */
    @Test
    fun `deepgramErrorMessage degrades on a primitive error instead of an object`() {
        val parsed = buildJsonObject { put("error", JsonPrimitive("plain string")) }

        val message = DeepgramProviderSettings(apiKey = "k").deepgramErrorMessage(400, parsed, "raw-body")

        assertTrue(
            message.contains("Deepgram request failed (400)"),
            "a primitive error degrades through the object accessor, no crash",
        )
    }
}
