@file:OptIn(LowLevelLanguageModelApi::class)

package ai.torad.aisdk

import ai.torad.aisdk.providers.Cohere
import ai.torad.aisdk.providers.CohereProviderSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class CohereDefensiveParsingTest {
    /**
     * Regression (the M4 bug-class): cohereUsage read server token counts via
     * `?.jsonPrimitive?.intOrNull`, which throws on a present-but-non-primitive field — failing the
     * whole generate(). The safe `(X as? JsonPrimitive)?.…` degrades to null -> the `?: 0` fallback.
     * The parser is private, so this drives it through the public model.
     */
    @Test
    fun `generate degrades usage to zero on a non-primitive input_tokens`() = runTest {
        val client = HttpClient(
            MockEngine {
                respond(
                    content = """
                        {
                          "message":{"role":"assistant","content":[{"type":"text","text":"hi"}]},
                          "finish_reason":"COMPLETE",
                          "usage":{"tokens":{"input_tokens":{"oops":1},"output_tokens":5}}
                        }
                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        val provider = Cohere(
            client,
            CohereProviderSettings {
                apiKey("key")
                baseURL("https://cohere.test/v2")
            },
        )

        val result = provider(ModelId("command-r-plus")).generate(
            LanguageModelCallParams(messages = listOf(UserMessage("hi"))),
        )

        assertEquals(0, result.usage.inputTokens.total, "a non-primitive input_tokens degrades to 0, no crash")
    }

    /**
     * Regression (Wave 7b, array-element accessors): the chat parser walked the `content` array via
     * the non-null `part.jsonObject` in a joinToString (text) and a forEach (thinking). A non-object
     * element threw ISE, failing generate(). The safe `part as? JsonObject ?: return@…` skips the
     * malformed element; the valid text part is still extracted.
     */
    @Test
    fun `generate drops a malformed content element instead of crashing`() = runTest {
        val client = HttpClient(
            MockEngine {
                respond(
                    content = """
                        {
                          "message":{"role":"assistant","content":[{"type":"text","text":"hi"},"malformed"]},
                          "finish_reason":"COMPLETE"
                        }
                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        val provider = Cohere(
            client,
            CohereProviderSettings {
                apiKey("key")
                baseURL("https://cohere.test/v2")
            },
        )

        val result = provider(ModelId("command-r-plus")).generate(
            LanguageModelCallParams(messages = listOf(UserMessage("hi"))),
        )

        assertEquals("hi", result.text)
    }
}
