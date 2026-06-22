package ai.torad.aisdk

import ai.torad.aisdk.providers.FacadeHttp
import ai.torad.aisdk.providers.Xai
import ai.torad.aisdk.providers.XaiProviderSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertFails
import kotlin.test.assertTrue

class OpenAICompatibleCoreDefensiveParsingTest {
    /**
     * Regression (the M4 bug-class) in the shared OpenAI-compatible core. Both error extractors
     * probed the error `message` via `?.jsonPrimitive?.contentOrNull`, which throws on a
     * present-but-non-primitive field — so building the error message for a 4xx crashed with
     * IllegalArgumentException instead of surfacing the structured error. The safe
     * `(X as? JsonPrimitive)?.…` degrades to null -> the toString()/raw fallbacks.
     */

    @Test
    fun `providerFacadeErrorMessage degrades to toString on a non-primitive error message`() {
        val parsed = buildJsonObject {
            put("error", buildJsonObject { put("message", buildJsonObject { put("oops", JsonPrimitive(1)) }) })
        }

        val message = FacadeHttp.providerFacadeErrorMessage(400, parsed, "raw-body")

        assertTrue(
            message.contains("Provider request failed (400)"),
            "the structured facade error is built, not an IllegalArgumentException from jsonPrimitive",
        )
    }

    /**
     * The path Xai's chat 400 surfaced: the private openAICompatibleErrorMessage in the shared
     * Http base class. Drives it through a concrete OpenAI-compatible chat model (Xai).
     */
    @Test
    fun `openai-compatible chat surfaces the structured error on a non-primitive error message`() = runTest {
        val client = HttpClient(
            MockEngine {
                respond(
                    content = """{"error":{"message":{"oops":1}}}""",
                    status = HttpStatusCode.BadRequest,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        val provider = Xai(client, XaiProviderSettings(apiKey = "key"))

        val error = assertFails {
            provider.chat(ModelId("grok-3")).generate(
                LanguageModelCallParams(messages = listOf(UserMessage("hi"))),
            )
        }

        assertTrue(
            error.message?.contains("OpenAI-compatible request failed") == true,
            "the structured OpenAI-compatible error is built, not an IllegalArgumentException from jsonPrimitive",
        )
    }
}
