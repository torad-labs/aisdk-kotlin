package ai.torad.aisdk

import ai.torad.aisdk.testing.drainAllItems
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AnthropicAwsProviderTest {
    @Test
    fun `messages model uses AWS base url workspace header api key and Anthropic mapping`() = runTest {
        val fixture = createTestServer(
            mutableMapOf(
                "https://aws-anthropic.test/v1/messages" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """
                            {
                              "id":"msg_aws",
                              "model":"claude-sonnet-4-6",
                              "content":[{"type":"text","text":"Hi"}],
                              "stop_reason":"end_turn",
                              "usage":{"input_tokens":1,"output_tokens":2}
                            }
                            """.trimIndent(),
                        ),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val provider = createAnthropicAws(
            fixture.httpClient(),
            AnthropicAwsProviderSettings(
                apiKey = "aws-key",
                workspaceId = "wrkspc_123",
                baseURL = "https://aws-anthropic.test/v1/",
                headers = mapOf("X-Provider" to "provider"),
            ),
        )

        val result = provider("claude-sonnet-4-6").generate(
            LanguageModelCallParams(
                messages = listOf(userMessage("Hello")),
                headers = mapOf("X-Request" to "request"),
            ),
        )

        assertEquals("anthropic-aws.messages", provider.languageModel("claude-sonnet-4-6").provider)
        assertEquals("Hi", result.text)
        assertEquals(FinishReason.Stop, result.finishReason)
        assertEquals(1, result.usage.promptTokens)
        assertEquals(2, result.usage.completionTokens)

        val request = fixture.calls.single()
        assertEquals("aws-key", request.requestHeaders.headerValue("x-api-key"))
        assertEquals("wrkspc_123", request.requestHeaders.headerValue("anthropic-workspace-id"))
        assertEquals("provider", request.requestHeaders.headerValue("X-Provider"))
        assertEquals("request", request.requestHeaders.headerValue("X-Request"))
        assertTrue(request.requestUserAgent.orEmpty().contains("ai-sdk/anthropic-aws/$ANTHROPIC_AWS_VERSION"))
        assertTrue(request.requestUserAgent.orEmpty().contains("ai-sdk/anthropic/$ANTHROPIC_VERSION"))
        assertEquals("claude-sonnet-4-6", request.requestBodyJson.jsonObject["model"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `stream uses Anthropic SSE mapping with AWS headers`() = runTest {
        val fixture = createTestServer(
            mutableMapOf(
                "https://aws-external-anthropic.us-east-1.api.aws/v1/messages" to UrlHandler(
                    UrlResponse.StreamChunks(
                        listOf(
                            """
                            data: {"type":"message_start","message":{"id":"msg_stream","model":"claude-sonnet-4-6","usage":{"input_tokens":1,"output_tokens":0}}}

                            data: {"type":"content_block_start","index":0,"content_block":{"type":"text","id":"text-1"}}

                            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"hello"}}

                            data: {"type":"content_block_stop","index":0}

                            data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"input_tokens":1,"output_tokens":2}}

                            data: {"type":"message_stop"}
                            """.trimIndent(),
                        ),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val provider = createAnthropicAws(
            fixture.httpClient(),
            AnthropicAwsProviderSettings(apiKey = "key", workspaceId = "wrkspc_123", region = "us-east-1"),
        )

        val events = drainAllItems(
            provider.messages("claude-sonnet-4-6").stream(LanguageModelCallParams(messages = listOf(userMessage("hi")))),
        )

        assertIs<StreamEvent.StreamStart>(events.first())
        assertTrue(events.any { it is StreamEvent.TextDelta && it.text == "hello" })
        val finish = events.filterIsInstance<StreamEvent.Finish>().single()
        assertEquals(FinishReason.Stop, finish.finishReason)
        assertEquals(1, finish.usage.promptTokens)
        assertEquals(2, finish.usage.completionTokens)
        assertEquals("text/event-stream", fixture.calls.single().requestHeaders.headerValue(HttpHeaders.Accept))
        assertEquals("wrkspc_123", fixture.calls.single().requestHeaders.headerValue("anthropic-workspace-id"))
    }

    @Test
    fun `unsupported models and SigV4 path are explicit`() = runTest {
        val fixture = createTestServer(mutableMapOf())
        val provider = createAnthropicAws(
            fixture.httpClient(),
            AnthropicAwsProviderSettings(workspaceId = "wrkspc_123", accessKeyId = "id", secretAccessKey = "secret"),
        )

        assertFailsWith<NoSuchModelError> { provider.embeddingModel("embed") }
        assertEquals("advisor", provider.tools.advisor_20260301.name)
        val error = assertFailsWith<AiSdkException> {
            provider.languageModel("claude-sonnet-4-6").generate(LanguageModelCallParams(messages = listOf(userMessage("hi"))))
        }
        assertTrue(error.message.orEmpty().contains("SigV4 request signing"))
    }

    private fun Map<String, String>.headerValue(name: String): String? =
        entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}
