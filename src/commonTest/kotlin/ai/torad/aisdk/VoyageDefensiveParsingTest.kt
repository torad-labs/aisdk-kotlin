package ai.torad.aisdk

import ai.torad.aisdk.providers.Voyage
import ai.torad.aisdk.providers.VoyageProviderSettings
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

class VoyageDefensiveParsingTest {
    /**
     * Regression (the M4 bug-class): voyageErrorMessage probed `message` via
     * `?.jsonPrimitive?.contentOrNull`, which throws on a present-but-non-primitive field — so
     * building the error message for a 4xx crashed with IllegalArgumentException instead of
     * surfacing the structured error. The safe `(X as? JsonPrimitive)?.…` degrades to null -> the
     * raw-body fallback. The parser is private, so this drives it through the public model.
     */
    @Test
    fun `embed surfaces the structured error on a non-primitive message`() = runTest {
        val client = HttpClient(
            MockEngine {
                respond(
                    content = """{"message":{"oops":1}}""",
                    status = HttpStatusCode.BadRequest,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        val model = Voyage(client, VoyageProviderSettings(apiKey = "key")).embedding(ModelId("voyage-4"))

        val error = assertFails {
            model.embed(EmbeddingModelCallParams(values = listOf("hi")))
        }

        assertTrue(
            error.message?.contains("Voyage request failed") == true,
            "the structured Voyage error is built, not an IllegalArgumentException from jsonPrimitive",
        )
    }
}
