package ai.torad.aisdk

import ai.torad.aisdk.providers.ElevenLabs
import ai.torad.aisdk.providers.ElevenLabsProviderSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFails
import kotlin.test.assertTrue

class ElevenLabsDefensiveParsingTest {
    /**
     * Regression (the M4 bug-class): elevenLabsErrorMessage probed `detail.message` via
     * `?.jsonPrimitive?.contentOrNull`, which throws on a present-but-non-primitive field — so
     * building the error message for a 4xx crashed with IllegalArgumentException instead of
     * surfacing the structured error. The safe `(X as? JsonPrimitive)?.…` degrades to null -> the
     * raw-body fallback. The parser is private, so this drives it through the public transcription
     * model (the only path wired to elevenLabsErrorMessage).
     */
    @Test
    fun `transcription surfaces the structured error on a non-primitive detail message`() = runTest {
        val client = HttpClient(
            MockEngine {
                respond(
                    content = """{"detail":{"message":{"oops":1}}}""",
                    status = HttpStatusCode.BadRequest,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        val model = ElevenLabs(client, ElevenLabsProviderSettings(apiKey = "key"))
            .transcription(ModelId("scribe_v1"))

        val error = assertFails {
            model.transcribe(
                TranscriptionParams(
                    audio = AudioSource(mediaType = "audio/mpeg", base64 = Base64Codec.encode(byteArrayOf(1, 2, 3))),
                ),
            )
        }

        assertTrue(
            error.message?.contains("ElevenLabs request failed") == true,
            "the structured ElevenLabs error is built, not an IllegalArgumentException from jsonPrimitive",
        )
    }

    /**
     * Regression (the M4 sibling-accessor bug-class, Wave 7): elevenLabsErrorMessage navigates
     * `detail?.jsonObject?.get("message")` — `?.jsonObject` throws if `detail` is present but a
     * primitive (`{"detail":"plain string"}`), crashing BEFORE the leaf cast. The safe
     * `(detail as? JsonObject)?.…` degrades to null -> the `detail as? JsonPrimitive` fallback.
     */
    @Test
    fun `transcription surfaces the structured error on a primitive detail`() = runTest {
        val client = HttpClient(
            MockEngine {
                respond(
                    content = """{"detail":"plain string"}""",
                    status = HttpStatusCode.BadRequest,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        val model = ElevenLabs(client, ElevenLabsProviderSettings(apiKey = "key"))
            .transcription(ModelId("scribe_v1"))

        val error = assertFails {
            model.transcribe(
                TranscriptionParams(
                    audio = AudioSource(mediaType = "audio/mpeg", base64 = Base64Codec.encode(byteArrayOf(1, 2, 3))),
                ),
            )
        }

        assertTrue(
            error.message?.contains("ElevenLabs request failed") == true,
            "a primitive detail degrades through the object accessor, no crash",
        )
    }
}
