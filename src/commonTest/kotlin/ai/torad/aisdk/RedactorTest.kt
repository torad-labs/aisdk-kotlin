package ai.torad.aisdk

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RedactorTest {
    private val redactor = DefaultRedactor()

    @Test
    fun `sensitive headers are redacted case-insensitively`() {
        val redacted = redactor.redactHeaders(
            mapOf(
                "Authorization" to "Bearer sk-live-secret",
                "x-api-key" to "secret",
                "X-Goog-Api-Key" to "g-secret",
                "xi-api-key" to "xi-secret",
                "Content-Type" to "application/json",
            ),
        )
        assertEquals("[REDACTED]", redacted["Authorization"])
        assertEquals("[REDACTED]", redacted["x-api-key"])
        assertEquals("[REDACTED]", redacted["X-Goog-Api-Key"])
        assertEquals("[REDACTED]", redacted["xi-api-key"])
        assertEquals("application/json", redacted["Content-Type"])
    }

    @Test
    fun `bearer and basic tokens in free text are masked`() {
        assertTrue("sk-live-secret" !in redactor.redactText("Authorization: Bearer sk-live-secret"))
        assertTrue("dXNlcjpwYXNz" !in redactor.redactText("Basic dXNlcjpwYXNz"))
        assertTrue("[REDACTED]" in redactor.redactText("api_key=supersecretvalue"))
    }

    @Test
    fun `json keys for auth and payloads are redacted`() {
        val json = buildJsonObject {
            put("Authorization", "Bearer sk-live-secret")
            put("api-key", "secret")
            put("query", "innocent")
            put("base64", "A".repeat(128))
        }
        val redacted = redactor.redactJson(json).toString()
        assertTrue("sk-live-secret" !in redacted, redacted)
        assertTrue("secret" !in redacted, redacted)
        assertTrue("innocent" in redacted, redacted)
        assertTrue("[REDACTED]" in redacted, redacted)
    }

    @Test
    fun `long strings and base64 payloads are summarized`() {
        val big = redactor.redactText("a".repeat(1024))
        assertTrue(big.startsWith("[REDACTED]"), big)
        val dataUrl = redactor.redactText("data:image/png;base64," + "A".repeat(100))
        assertTrue(dataUrl.startsWith("[REDACTED]"), dataUrl)
    }

    @Test
    fun `non-sensitive primitives pass through`() {
        assertEquals(JsonPrimitive("hello"), redactor.redactJson(JsonPrimitive("hello")))
        assertEquals(JsonPrimitive(42), redactor.redactJson(JsonPrimitive(42)))
    }
}
