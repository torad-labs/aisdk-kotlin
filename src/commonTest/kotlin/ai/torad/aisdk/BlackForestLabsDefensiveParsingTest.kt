package ai.torad.aisdk

import ai.torad.aisdk.providers.BlackForestLabs
import ai.torad.aisdk.providers.BlackForestLabsProviderSettings
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

class BlackForestLabsDefensiveParsingTest {
    /**
     * Regression (the M4 bug-class): bflErrorMessage probed the error `detail` via
     * `detail?.jsonPrimitive?.contentOrNull`, which throws on a present-but-non-primitive field —
     * so building the error message for a 4xx crashed with IllegalArgumentException instead of
     * surfacing the structured error. The safe `(detail as? JsonPrimitive)?.…` degrades to null ->
     * the next when-branch (`detail.toString()`). The parser is private, so this drives it through
     * the public model.
     */
    @Test
    fun `generate surfaces the structured error on a non-primitive detail`() = runTest {
        val client = HttpClient(
            MockEngine {
                respond(
                    content = """{"detail":{"oops":1}}""",
                    status = HttpStatusCode.BadRequest,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        val model = BlackForestLabs(client, BlackForestLabsProviderSettings { apiKey("k") })
            .image(ModelId("flux-pro-1.1"))

        val error = assertFails {
            model.generate(ImageGenerationParams(prompt = "x", size = "1024x1024"))
        }

        assertTrue(
            error.message?.contains("Black Forest Labs request failed") == true,
            "the structured BFL error is built, not an IllegalArgumentException from jsonPrimitive",
        )
    }
}
