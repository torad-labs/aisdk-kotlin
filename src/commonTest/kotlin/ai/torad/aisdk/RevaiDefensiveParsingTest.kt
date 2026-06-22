package ai.torad.aisdk

import ai.torad.aisdk.providers.Revai
import ai.torad.aisdk.providers.RevaiProviderSettings
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

class RevaiDefensiveParsingTest {
    /**
     * Regression (the M4 bug-class): revaiErrorMessage probed `error.message` via
     * `?.jsonPrimitive?.contentOrNull`, which throws on a present-but-non-primitive field — so
     * building the error message for a 4xx crashed with IllegalArgumentException. The safe
     * `(X as? JsonPrimitive)?.…` degrades to null -> the raw-body fallback.
     */
    @Test
    fun `transcribe surfaces the structured error on a non-primitive error message`() = runTest {
        val client = HttpClient(
            MockEngine {
                respond(
                    content = """{"error":{"message":{"oops":1}}}""",
                    status = HttpStatusCode.BadRequest,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        val model = Revai(client, RevaiProviderSettings(apiKey = "key")).transcription(ModelId("machine"))

        val error = assertFails {
            model.transcribe(
                TranscriptionParams(
                    audio = AudioSource(mediaType = "audio/mpeg", base64 = Base64Codec.encode(byteArrayOf(1, 2, 3))),
                ),
            )
        }

        assertTrue(
            error.message?.contains("Rev.ai request failed") == true,
            "the structured Rev.ai error is built, not an IllegalArgumentException from jsonPrimitive",
        )
    }
}
