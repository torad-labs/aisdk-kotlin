package ai.torad.aisdk

import ai.torad.aisdk.providers.KlingAI
import ai.torad.aisdk.providers.KlingAIProviderSettings
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

class KlingAIDefensiveParsingTest {
    /**
     * Regression (the M4 bug-class): klingAIErrorMessage probed `message` via
     * `?.jsonPrimitive?.contentOrNull`, which throws on a present-but-non-primitive field — so
     * building the error message for a 4xx crashed with IllegalArgumentException instead of
     * surfacing the structured error. The safe `(X as? JsonPrimitive)?.…` degrades to null -> the
     * raw-body fallback. The parser is private, so this drives it through the public model.
     */
    @Test
    fun `generate surfaces the structured error on a non-primitive message`() = runTest {
        val client = HttpClient(
            MockEngine {
                respond(
                    content = """{"message":{"oops":1}}""",
                    status = HttpStatusCode.BadRequest,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        val model = KlingAI(
            client,
            KlingAIProviderSettings {
                accessKey("a")
                secretKey("s")
            },
        )
            .video(ModelId("kling-v2.6-t2v"))

        val error = assertFails {
            model.generate(
                VideoGenerationParams {
                    prompt("x")
                }
            )
        }

        assertTrue(
            error.message?.contains("KlingAI request failed") == true,
            "the structured KlingAI error is built, not an IllegalArgumentException from jsonPrimitive",
        )
    }
}
