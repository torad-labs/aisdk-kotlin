package ai.torad.aisdk

import ai.torad.aisdk.providers.Prodia
import ai.torad.aisdk.providers.ProdiaProviderSettings
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

class ProdiaDefensiveParsingTest {
    /**
     * Regression (the M4 bug-class): prodiaErrorMessage probed the error body via
     * `?.jsonPrimitive?.contentOrNull`, which throws on a present-but-non-primitive field — so
     * building the error message for a 4xx crashed with IllegalArgumentException. The safe
     * `(X as? JsonPrimitive)?.…` degrades to null -> the raw-body fallback.
     */
    @Test
    fun `image generate surfaces the raw error on a non-primitive detail`() = runTest {
        val client = HttpClient(
            MockEngine {
                respond(
                    content = """{"detail":{"oops":1}}""",
                    status = HttpStatusCode.BadRequest,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        val model = Prodia(client, ProdiaProviderSettings(apiKey = "key")).image(ModelId("sdxl"))

        val error = assertFails {
            model.generate(ImageGenerationParams(prompt = "x", n = 1))
        }

        assertTrue(
            error.message?.contains("oops") == true,
            "the raw error body is surfaced, not an IllegalArgumentException from jsonPrimitive",
        )
    }
}
