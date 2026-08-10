@file:OptIn(LowLevelLanguageModelApi::class)

package ai.torad.aisdk

import ai.torad.aisdk.providers.OpenAICompatible
import ai.torad.aisdk.providers.OpenAICompatibleProviderSettings
import ai.torad.aisdk.testing.FlowDrain.drainAllItems
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Prediction-token and thought-signature provider metadata must be keyed by the
 * provider-derived options key on BOTH the buffered and the streaming path —
 * upstream's `doGenerate` and `doStream` both write under `metadataKey`
 * (`openai-compatible-chat-language-model.ts`), never under a literal
 * `"openaiCompatible"`.
 */
class OpenAICompatibleMetadataKeyTest {
    private companion object {
        /** A chat completion carrying both prediction-token details and a tool-call signature. */
        val GENERATE_BODY = """
            {
              "id":"chatcmpl_1",
              "created":1780000000,
              "model":"groq-test",
              "choices":[{
                "message":{
                  "role":"assistant",
                  "content":"hello",
                  "tool_calls":[{
                    "id":"call_1",
                    "type":"function",
                    "function":{"name":"search","arguments":"{}"},
                    "extra_content":{"google":{"thought_signature":"sig_1"}}
                  }]
                },
                "finish_reason":"tool_calls"
              }],
              "usage":{
                "prompt_tokens":5,
                "completion_tokens":7,
                "completion_tokens_details":{
                  "accepted_prediction_tokens":4,
                  "rejected_prediction_tokens":1
                }
              }
            }
        """.trimIndent()
    }

    @Test
    fun `generate keys prediction tokens and thought signatures under the provider options key`() = runTest {
        val client = HttpClient(
            MockEngine {
                respond(
                    content = GENERATE_BODY,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        val provider = OpenAICompatible(
            client,
            OpenAICompatibleProviderSettings {
                name("groq")
                baseUrl("https://api.test/v1")
                apiKey("secret")
            },
        )

        val result = provider.languageModel("groq-test").generate(
            LanguageModelCallParams {
                messages(listOf(UserMessage("hi")))
            }
        )

        val metadata = result.providerMetadata.toMap()["groq"]?.jsonObject
        assertEquals(4, metadata?.get("acceptedPredictionTokens")?.jsonPrimitive?.intOrNull)
        assertEquals(1, metadata?.get("rejectedPredictionTokens")?.jsonPrimitive?.intOrNull)
        assertNull(result.providerMetadata.toMap()["openaiCompatible"])

        val toolCallMetadata = result.toolCalls.single().providerMetadata.toMap()
        assertEquals(
            "sig_1",
            toolCallMetadata["groq"]?.jsonObject?.get("thoughtSignature")?.jsonPrimitive?.content,
        )
        assertNull(toolCallMetadata["thoughtSignature"])
    }

    @Test
    fun `stream keys prediction tokens under the same provider options key as generate`() = runTest {
        val client = HttpClient(
            MockEngine {
                respond(
                    content = """
                        data: {"id":"1","choices":[{"delta":{"content":"hello"}}]}

                        data: {"id":"1","choices":[{"finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":2,"completion_tokens_details":{"accepted_prediction_tokens":4,"rejected_prediction_tokens":1}}}

                        data: [DONE]

                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
                )
            },
        )
        val provider = OpenAICompatible(
            client,
            OpenAICompatibleProviderSettings {
                name("groq")
                baseUrl("https://api.test/v1")
                includeUsage(true)
            },
        )

        val events = drainAllItems(
            provider.languageModel("groq-test").stream(
                LanguageModelCallParams {
                    messages(listOf(UserMessage("hi")))
                }
            )
        )

        val finish = events.filterIsInstance<StreamEvent.Finish>().single()
        val metadata = finish.providerMetadata.toMap()["groq"]?.jsonObject
        assertEquals(4, metadata?.get("acceptedPredictionTokens")?.jsonPrimitive?.intOrNull)
        assertEquals(1, metadata?.get("rejectedPredictionTokens")?.jsonPrimitive?.intOrNull)
        assertNull(finish.providerMetadata.toMap()["openaiCompatible"])
    }
}
