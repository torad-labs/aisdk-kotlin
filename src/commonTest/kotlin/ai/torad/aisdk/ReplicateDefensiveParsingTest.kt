package ai.torad.aisdk

import ai.torad.aisdk.providers.Replicate
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

class ReplicateDefensiveParsingTest {
    /**
     * Regression (the M4 bug-class): replicateErrorMessage probed `detail` via
     * `?.jsonPrimitive?.contentOrNull`, which throws on a present-but-non-primitive field — so
     * building the error message for a 4xx crashed with IllegalArgumentException. The safe
     * `(X as? JsonPrimitive)?.…` degrades to null -> the raw-body fallback.
     */
    @Test
    fun `image generate surfaces the structured error on a non-primitive detail`() = runTest {
        val client = HttpClient(
            MockEngine {
                respond(
                    content = """{"detail":{"oops":1}}""",
                    status = HttpStatusCode.BadRequest,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        val model = Replicate(client).image(ModelId("owner/model:version-123"))

        val error = assertFails {
            model.generate(
                ImageGenerationParams {
                    prompt("x")
                    n(1)
                }
            )
        }

        assertTrue(
            error.message?.contains("Replicate request failed") == true,
            "the structured Replicate error is built, not an IllegalArgumentException from jsonPrimitive",
        )
    }
}
