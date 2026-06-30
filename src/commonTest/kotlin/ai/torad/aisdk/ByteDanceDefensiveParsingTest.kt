package ai.torad.aisdk

import ai.torad.aisdk.providers.ByteDance
import ai.torad.aisdk.providers.ByteDanceProviderSettings
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

class ByteDanceDefensiveParsingTest {
    /**
     * Regression (the M4 bug-class): byteDanceErrorMessage probed `error.message` via
     * `?.jsonPrimitive?.contentOrNull`, which throws on a present-but-non-primitive field — so
     * building the error message for a 4xx crashed with IllegalArgumentException. The safe
     * `(X as? JsonPrimitive)?.…` degrades to null -> the raw-body fallback.
     */
    @Test
    fun `video generate surfaces the structured error on a non-primitive error message`() = runTest {
        val client = HttpClient(
            MockEngine {
                respond(
                    content = """{"error":{"message":{"oops":1}}}""",
                    status = HttpStatusCode.BadRequest,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        val model = ByteDance(client, ByteDanceProviderSettings { apiKey("key") })
            .video(ModelId("seedance-1-0-pro-250528"))

        val error = assertFails {
            model.generate(
                VideoGenerationParams(prompt = "x", image = GeneratedFile(mediaType = "image/png", base64 = "AQID")),
            )
        }

        assertTrue(
            error.message?.contains("ByteDance request failed") == true,
            "the structured ByteDance error is built, not an IllegalArgumentException from jsonPrimitive",
        )
    }
}
