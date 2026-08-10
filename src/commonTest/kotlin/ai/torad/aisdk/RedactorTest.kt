package ai.torad.aisdk

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
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
    fun `an unlisted payload key still gets its blob summarized by the generic string path`() {
        // isPayloadKey() is a short denylist ("base64"/"file"/"payload"/"data"/*bytes), the same
        // shape that made isSensitiveKey() leak two live API keys. It is NOT load-bearing here:
        // an unlisted key falls through to redactJson's JsonPrimitive branch, which applies the
        // same length + base64 tests by CONTENT rather than by name. That fallback is what makes
        // the short list safe, so it is pinned — narrowing the generic path would silently turn
        // this predicate back into the leak shape.
        val json = buildJsonObject {
            put("b64_json", "A".repeat(512))
            put("audioContent", "data:audio/mp3;base64," + "B".repeat(256))
            // Both of the above are over maxStringLength (256), so they only ever exercise the
            // LENGTH branch — the first version of this test would have stayed green with the
            // base64 detection deleted entirely, which is not what its comment claimed to pin.
            // These two sit in the (minBase64Length, maxStringLength] window where only the
            // CONTENT test can catch them: one padded, one unpadded base64url (length 101, not a
            // multiple of 4 — the shape the old `% 4 == 0` requirement let through verbatim).
            put("inlineBlob", "Q".repeat(128))
            put(
                "signedRef",
                "eyJhbGciOiJIUzI1NiJ9-abcdefghijklmnopqrstuvwxyz_" +
                    "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789abcdefghij",
            )
        }
        val redacted = redactor.redactJson(json).toString()
        assertTrue("AAAA" !in redacted, redacted)
        assertTrue("BBBB" !in redacted, redacted)
        assertTrue("QQQQ" !in redacted, redacted)
        assertTrue("eyJhbGciOiJIUzI1NiJ9" !in redacted, redacted)
        assertTrue("[REDACTED]" in redacted, redacted)
    }

    @Test
    fun `non-ascii prose is not mistaken for base64`() {
        // Base64 and base64url are ASCII-only alphabets (RFC 4648), so a Unicode-aware charset
        // test buys no credential coverage and costs diagnostics: this 75-char Japanese sentence
        // sits in the (minBase64Length, maxStringLength] window and has no space or punctuation
        // to fail the charset check, so it was summarized away as a base64 blob.
        val prose = "このモデルは長い文章を生成しますが診断のために本文をそのまま残すことが" +
            "非常に有用ですユーザーの入力内容を確認する必要がありますので削除しないでください"
        assertEquals(prose, redactor.redactText(prose))
    }

    @Test
    fun `non-sensitive primitives pass through`() {
        assertEquals(JsonPrimitive("hello"), redactor.redactJson(JsonPrimitive("hello")))
        assertEquals(JsonPrimitive(42), redactor.redactJson(JsonPrimitive(42)))
    }

    /**
     * Every credential header name this SDK writes must redact. These are the actual literals
     * assigned an apiKey in `src/commonMain`, so the list is derived from the providers rather than
     * invented — which is the point: the redactor's predicate and the providers' header names are
     * two lists that must agree, and nothing but this test links them.
     *
     * Regression: the predicate used to be five EXACT names, so `x-key` (BlackForestLabsProvider.kt
     * :309) and `x-gladia-key` (GladiaProvider.kt:781) — both real API keys — were emitted verbatim
     * into telemetry, as was a caller's `Cookie`. Adding a provider whose credential header is not
     * covered fails here instead of silently disclosing it.
     */
    @Test
    fun `every credential header this SDK sends is redacted`() {
        val credentialHeaders = listOf(
            "Authorization",
            "api-key",
            "x-api-key",
            "x-goog-api-key",
            "xi-api-key",
            "X-Hume-Api-Key",
            "x-gladia-key",
            "x-key",
            "x-amz-security-token",
            // Caller-configured credentials that reach the same header bag.
            "Cookie",
            "Proxy-Authorization",
        )
        for (header in credentialHeaders) {
            val redacted = redactor.redactJson(buildJsonObject { put(header, "super-secret-value") })
            assertEquals(
                JsonPrimitive("[REDACTED]"),
                redacted.jsonObject[header],
                "$header must be redacted before it reaches telemetry",
            )
        }
    }
}
