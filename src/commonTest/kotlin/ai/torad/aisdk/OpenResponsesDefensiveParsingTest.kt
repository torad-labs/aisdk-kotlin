@file:OptIn(LowLevelLanguageModelApi::class)

package ai.torad.aisdk

import ai.torad.aisdk.providers.OpenResponses
import ai.torad.aisdk.providers.OpenResponsesProviderSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class OpenResponsesDefensiveParsingTest {
    /**
     * Regression (the M4 bug-class): openResponsesUsage read server token counts via
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
                          "id":"resp_1",
                          "model":"m",
                          "output":[],
                          "usage":{"input_tokens":{"oops":1},"output_tokens":5,"total_tokens":5}
                        }
                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        val provider = OpenResponses(
            client,
            OpenResponsesProviderSettings {
                url("https://api.test/v1/responses")
                name("openresponses")
                apiKey("k")
            },
        )

        val result = provider.responses("m").generate(
            LanguageModelCallParams(messages = listOf(UserMessage("hi"))),
        )

        assertEquals(0, result.usage.inputTokens.total, "a non-primitive input_tokens degrades to 0, no crash")
    }

    /**
     * Regression (the M4 sibling-accessor bug-class, Wave 7): openResponsesUsage navigates
     * `input_tokens_details?.jsonObject?.get("cached_tokens")` — `?.jsonObject` throws if the field
     * is present but a non-object (`"input_tokens_details":"oops"`), failing the whole generate().
     * The safe `(X as? JsonObject)?.…` degrades to null -> the `?: 0` cached-tokens fallback.
     */
    @Test
    fun `generate degrades cached tokens on a non-object input_tokens_details`() = runTest {
        val client = HttpClient(
            MockEngine {
                respond(
                    content = """
                        {
                          "id":"resp_1",
                          "model":"m",
                          "output":[],
                          "usage":{"input_tokens":3,"output_tokens":1,"total_tokens":4,"input_tokens_details":"oops"}
                        }
                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        val provider = OpenResponses(
            client,
            OpenResponsesProviderSettings {
                url("https://api.test/v1/responses")
                name("openresponses")
                apiKey("k")
            },
        )

        val result = provider.responses("m").generate(
            LanguageModelCallParams(messages = listOf(UserMessage("hi"))),
        )

        assertEquals(
            3,
            result.usage.inputTokens.total,
            "a non-object input_tokens_details degrades cached tokens to 0, no crash",
        )
    }
}
