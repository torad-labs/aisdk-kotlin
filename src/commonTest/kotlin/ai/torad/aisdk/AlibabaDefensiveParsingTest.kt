package ai.torad.aisdk

import ai.torad.aisdk.providers.Alibaba
import ai.torad.aisdk.providers.AlibabaProviderSettings
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

class AlibabaDefensiveParsingTest {
    /**
     * Regression (the M4 bug-class): alibabaErrorMessage probed `message` via
     * `?.jsonPrimitive?.contentOrNull`, which throws on a present-but-non-primitive field — so
     * building the error message for a 4xx crashed with IllegalArgumentException. The safe
     * `(X as? JsonPrimitive)?.…` degrades to null -> the raw-body fallback. Driven through the
     * video path (Alibaba-local; the chat path routes through the openai-compatible-core).
     */
    @Test
    fun `video generate surfaces the structured error on a non-primitive message`() = runTest {
        val client = HttpClient(
            MockEngine {
                respond(
                    content = """{"message":{"oops":1}}""",
                    status = HttpStatusCode.BadRequest,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        val model = Alibaba(client, AlibabaProviderSettings { apiKey("key") })
            .video(ModelId("wan2.6-i2v"))

        val error = assertFails {
            model.generate(
                VideoGenerationParams {
                    prompt("x")
                    image(GeneratedFile(mediaType = "image/png", base64 = "", url = "https://example.com/start.png"))
                },
            )
        }

        assertTrue(
            error.message?.contains("Alibaba request failed") == true,
            "the structured Alibaba error is built, not an IllegalArgumentException from jsonPrimitive",
        )
    }

    /**
     * Regression (the M4 sibling-accessor bug-class, Wave 7): alibabaErrorMessage navigates
     * `error?.jsonObject?.get("message")` — `?.jsonObject` throws if `error` is a primitive
     * (`{"error":"plain string"}`), crashing BEFORE the leaf cast. The safe `(error as? JsonObject)`
     * degrades to null -> the raw-body fallback.
     */
    @Test
    fun `video generate surfaces the structured error on a primitive error`() = runTest {
        val client = HttpClient(
            MockEngine {
                respond(
                    content = """{"error":"plain string"}""",
                    status = HttpStatusCode.BadRequest,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        val model = Alibaba(client, AlibabaProviderSettings { apiKey("key") })
            .video(ModelId("wan2.6-i2v"))

        val error = assertFails {
            model.generate(
                VideoGenerationParams {
                    prompt("x")
                    image(GeneratedFile(mediaType = "image/png", base64 = "", url = "https://example.com/start.png"))
                },
            )
        }

        assertTrue(
            error.message?.contains("Alibaba request failed") == true,
            "a primitive error degrades through the object accessor, no crash",
        )
    }
}
