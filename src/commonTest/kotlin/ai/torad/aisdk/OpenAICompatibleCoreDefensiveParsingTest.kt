@file:OptIn(LowLevelLanguageModelApi::class)

package ai.torad.aisdk

import ai.torad.aisdk.providers.DeepInfra
import ai.torad.aisdk.providers.DeepInfraProviderSettings
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
import kotlin.test.assertEquals
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

    /**
     * Wave 7 sibling-accessor variant: openAICompatibleErrorMessage navigates
     * `error?.jsonObject` — `?.jsonObject` throws if `error` is a primitive (`{"error":"plain string"}`),
     * crashing BEFORE the leaf cast. The safe `(error as? JsonObject)` degrades to the raw fallback.
     * This hardens the chat-error path for every OpenAI-compatible provider at once.
     */
    @Test
    fun `openai-compatible chat surfaces the structured error on a primitive error`() = runTest {
        val client = HttpClient(
            MockEngine {
                respond(
                    content = """{"error":"plain string"}""",
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
            "a primitive error degrades through the object accessor, no crash",
        )
    }

    /**
     * Wave 7b (array-element accessor) in the shared chat content parser: openAITextContent read
     * each multimodal content part via the non-null `item.jsonObject`, throwing ISE if any part is
     * a non-object. The safe `(item as? JsonObject)?.takeIf { … }` drops the malformed part and the
     * valid text part is still extracted — hardening multimodal parsing for every compat provider.
     */
    @Test
    fun `openai-compatible chat drops a malformed content part instead of crashing`() = runTest {
        val client = HttpClient(
            MockEngine {
                respond(
                    content = """
                        {"choices":[{"message":{"content":[{"type":"text","text":"hi"},"malformed"]}}]}
                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        val result = Xai(client, XaiProviderSettings(apiKey = "key"))
            .chat(ModelId("grok-3"))
            .generate(LanguageModelCallParams(messages = listOf(UserMessage("hi"))))
        assertEquals("hi", result.text)
    }

    /**
     * Wave 7b: the shared embeddings parser read each `data` row via the non-null `item.jsonObject`
     * to reach `embedding`. The safe `(item as? JsonObject)?.get("embedding")` degrades a malformed
     * row to an empty embedding in place — row count (index alignment) is preserved.
     */
    @Test
    fun `openai-compatible embeddings degrade a malformed data row instead of crashing`() = runTest {
        val client = HttpClient(
            MockEngine {
                respond(
                    content = """{"data":[{"embedding":[0.1,0.2]},"malformed"]}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        val result = DeepInfra(client, DeepInfraProviderSettings { apiKey("key") })
            .embeddingModel("BAAI/bge")
            .embed(EmbeddingModelCallParams(values = listOf("a", "b")))
        assertEquals(2, result.embeddings.size)
        assertTrue(result.embeddings[1].isEmpty(), "the malformed row degrades to an empty embedding")
    }
}
