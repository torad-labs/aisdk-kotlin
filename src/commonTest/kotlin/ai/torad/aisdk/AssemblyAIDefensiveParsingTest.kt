package ai.torad.aisdk

import ai.torad.aisdk.providers.AssemblyAI
import ai.torad.aisdk.providers.AssemblyAIProviderSettings
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

class AssemblyAIDefensiveParsingTest {
    /**
     * Regression (the M4 bug-class): the error extractor probed `error.message` via
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
        val model = AssemblyAI(
            client,
            AssemblyAIProviderSettings {
                apiKey("key")
            },
        ).transcription(ModelId("best"))

        val error = assertFails {
            model.transcribe(
                TranscriptionParams {
                    audio(AudioSource(mediaType = "audio/mpeg", base64 = Base64Codec.encode(byteArrayOf(1, 2, 3))))
                },
            )
        }

        assertTrue(
            error.message?.contains("AssemblyAI request failed") == true,
            "the structured AssemblyAI error is built, not an IllegalArgumentException from jsonPrimitive",
        )
    }

    /**
     * Regression (the M4 sibling-accessor bug-class, Wave 7): the extractor navigates
     * `error?.jsonObject?.get("message")` — `?.jsonObject` throws if `error` is present but a
     * primitive (`{"error":"plain string"}`), crashing BEFORE the leaf cast. The safe
     * `(error as? JsonObject)?.…` degrades to null -> the `error as? JsonPrimitive` fallback.
     */
    @Test
    fun `transcribe surfaces the structured error on a primitive error`() = runTest {
        val client = HttpClient(
            MockEngine {
                respond(
                    content = """{"error":"plain string"}""",
                    status = HttpStatusCode.BadRequest,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        val model = AssemblyAI(
            client,
            AssemblyAIProviderSettings {
                apiKey("key")
            },
        ).transcription(ModelId("best"))

        val error = assertFails {
            model.transcribe(
                TranscriptionParams {
                    audio(AudioSource(mediaType = "audio/mpeg", base64 = Base64Codec.encode(byteArrayOf(1, 2, 3))))
                },
            )
        }

        assertTrue(
            error.message?.contains("AssemblyAI request failed") == true,
            "a primitive error degrades through the object accessor, no crash",
        )
    }
}
