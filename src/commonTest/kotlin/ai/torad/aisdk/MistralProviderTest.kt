package ai.torad.aisdk
import ai.torad.aisdk.providers.MISTRAL_VERSION
import ai.torad.aisdk.providers.MistralProviderSettings
import ai.torad.aisdk.providers.createMistral
import ai.torad.aisdk.providers.mistral
import ai.torad.aisdk.testing.drainAllItems

import io.ktor.http.HttpHeaders
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MistralProviderTest {
    @Test
    fun `chat model uses Mistral endpoint headers and option mapping`() = runTest {
        val fixture = createTestServer(
            mutableMapOf(
                "https://api.mistral.ai/v1/chat/completions" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """
                            {
                              "id":"chat-1",
                              "created":1780000000,
                              "model":"mistral-small-latest",
                              "choices":[{"message":{"role":"assistant","content":"bonjour","reasoning_content":"thinking"},"finish_reason":"stop"}],
                              "usage":{"prompt_tokens":13,"completion_tokens":34,"total_tokens":47}
                            }
                            """.trimIndent(),
                        ),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val provider = createMistral(
            fixture.httpClient(),
            MistralProviderSettings(apiKey = "key", headers = mapOf("X-Provider" to "provider")),
        )

        val result = provider.chat("mistral-small-latest").generate(
            LanguageModelCallParams(
                messages = listOf(userMessage("Hello")),
                maxOutputTokens = 256,
                seed = 77,
                providerOptions = mapOf(
                    "mistral" to buildJsonObject {
                        put("safePrompt", JsonPrimitive(true))
                        put("documentImageLimit", JsonPrimitive(2))
                        put("documentPageLimit", JsonPrimitive(8))
                        put("parallelToolCalls", JsonPrimitive(false))
                        put("reasoningEffort", JsonPrimitive("high"))
                        put("strictJsonSchema", JsonPrimitive(false))
                    },
                ),
                headers = mapOf("X-Request" to "request"),
            ),
        )

        assertEquals("mistral.chat", provider("mistral-small-latest").provider)
        assertEquals("bonjour", result.text)
        assertEquals("thinking", result.content.filterIsInstance<ContentPart.Reasoning>().single().text)
        assertEquals(13, result.usage.promptTokens)
        assertEquals(34, result.usage.completionTokens)
        val call = fixture.calls.single()
        assertEquals("Bearer key", call.requestHeaders.headerValue(HttpHeaders.Authorization))
        assertEquals("provider", call.requestHeaders.headerValue("X-Provider"))
        assertEquals("request", call.requestHeaders.headerValue("X-Request"))
        assertTrue(call.requestUserAgent.orEmpty().contains("ai-sdk/mistral/$MISTRAL_VERSION"))
        val body = call.requestBodyJson.jsonObject
        assertEquals("mistral-small-latest", body["model"]?.jsonPrimitive?.contentOrNull)
        assertEquals(256, body["max_tokens"]?.jsonPrimitive?.intOrNull)
        assertEquals(77, body["random_seed"]?.jsonPrimitive?.intOrNull)
        assertEquals(true, body["safe_prompt"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(2, body["document_image_limit"]?.jsonPrimitive?.intOrNull)
        assertEquals(8, body["document_page_limit"]?.jsonPrimitive?.intOrNull)
        assertEquals(false, body["parallel_tool_calls"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("high", body["reasoning_effort"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `embedding model aliases map usage and request body`() = runTest {
        val fixture = createTestServer(
            mutableMapOf(
                "https://mistral.test/v1/embeddings" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """{"data":[{"object":"embedding","index":1,"embedding":[3,4]},{"object":"embedding","index":0,"embedding":[1,2]}],"model":"mistral-embed","usage":{"prompt_tokens":8,"total_tokens":8}}""",
                        ),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val provider = createMistral(
            fixture.httpClient(),
            MistralProviderSettings(baseURL = "https://mistral.test/v1", apiKey = "key"),
        )

        val result = provider.embedding("mistral-embed").embed(
            EmbeddingModelCallParams(
                values = listOf("sunny day", "rainy city"),
                headers = mapOf("X-Request" to "request"),
            ),
        )

        assertEquals("mistral.embedding", provider.embeddingModel("mistral-embed").provider)
        assertEquals(provider.embedding("mistral-embed").modelId, provider.textEmbedding("mistral-embed").modelId)
        assertEquals(provider.embedding("mistral-embed").modelId, provider.textEmbeddingModel("mistral-embed").modelId)
        assertEquals(listOf(listOf(1f, 2f), listOf(3f, 4f)), result.embeddings)
        assertEquals(8, result.usage.tokens)
        val call = fixture.calls.single()
        assertEquals("Bearer key", call.requestHeaders.headerValue(HttpHeaders.Authorization))
        assertEquals("request", call.requestHeaders.headerValue("X-Request"))
        val body = call.requestBodyJson.jsonObject
        assertEquals("mistral-embed", body["model"]?.jsonPrimitive?.contentOrNull)
        assertEquals("sunny day", body["input"]?.jsonArray?.first()?.jsonPrimitive?.contentOrNull)
        assertEquals("float", body["encoding_format"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `chat body uses Mistral wire shape - any toolChoice + tool name + prefix`() = runTest {
        val fixture = createTestServer(
            mutableMapOf(
                "https://api.mistral.ai/v1/chat/completions" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            "{\"id\":\"c\",\"model\":\"m\",\"choices\":[{\"message\":" +
                                "{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}]," +
                                "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}",
                        ),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val provider = createMistral(fixture.httpClient(), MistralProviderSettings(apiKey = "key"))
        provider.chat("mistral-small-latest").generate(
            LanguageModelCallParams(
                messages = listOf(
                    userMessage("go"),
                    ModelMessage(
                        MessageRole.Assistant,
                        listOf(ContentPart.ToolCall("t1", "lookup", buildJsonObject {})),
                    ),
                    ModelMessage(
                        MessageRole.Tool,
                        listOf(ContentPart.ToolResult("t1", "lookup", JsonPrimitive("done"))),
                    ),
                ),
                tools = listOf(
                    LanguageModelTool("lookup", "d", "{\"type\":\"object\"}"),
                    LanguageModelTool("other", "d", "{\"type\":\"object\"}"),
                ),
                toolChoice = ToolChoice.Specific("lookup"),
            ),
        )
        val body = fixture.calls.single().requestBodyJson.jsonObject
        // tool_choice "any" + tools filtered to the named one.
        assertEquals("any", body["tool_choice"]?.jsonPrimitive?.contentOrNull)
        assertEquals(1, body["tools"]?.jsonArray?.size, "tools filtered to the named tool")
        val msgs = body["messages"]!!.jsonArray
        fun role(m: kotlinx.serialization.json.JsonElement) = m.jsonObject["role"]?.jsonPrimitive?.contentOrNull
        // The tool-result message carries the tool name.
        val toolMsg = msgs.first { role(it) == "tool" }.jsonObject
        assertEquals("lookup", toolMsg["name"]?.jsonPrimitive?.contentOrNull)
        // The final assistant message gets prefix:true.
        val asstMsg = msgs.last { role(it) == "assistant" }.jsonObject
        assertEquals(true, asstMsg["prefix"]?.jsonPrimitive?.booleanOrNull)
    }

    @Test
    fun `chat response maps Mistral thinking content for generate and stream`() = runTest {
        val fixture = createTestServer(
            mutableMapOf(
                "https://api.mistral.ai/v1/chat/completions" to UrlHandler(
                    listOf(
                        UrlResponse.JsonValue(
                            Json.parseToJsonElement(
                                """
                                {
                                  "id":"chat-2",
                                  "model":"magistral-small-2507",
                                  "choices":[
                                    {
                                      "message":{
                                        "role":"assistant",
                                        "content":[
                                          {"type":"thinking","thinking":[{"type":"text","text":"First thought."}]},
                                          {"type":"text","text":"Final answer."}
                                        ]
                                      },
                                      "finish_reason":"stop"
                                    }
                                  ],
                                  "usage":{"prompt_tokens":1,"completion_tokens":2,"total_tokens":3}
                                }
                                """.trimIndent(),
                            ),
                        ),
                        UrlResponse.StreamChunks(
                            listOf(
                                """
                                data: {"id":"chat-stream-2","model":"magistral-small-2507","choices":[{"delta":{"role":"assistant","content":[{"type":"thinking","thinking":[{"type":"text","text":"Stream thought."}]}]},"finish_reason":null}]}

                                data: {"id":"chat-stream-2","model":"magistral-small-2507","choices":[{"delta":{"role":"assistant","content":[{"type":"text","text":"Stream answer."}]},"finish_reason":null}]}

                                data: {"id":"chat-stream-2","model":"magistral-small-2507","choices":[{"delta":{"content":""},"finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":2,"total_tokens":3}}

                                data: [DONE]

                                """.trimIndent(),
                            ),
                        ),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val provider = createMistral(fixture.httpClient(), MistralProviderSettings(apiKey = "key"))

        val generated = provider.chat("magistral-small-2507").generate(LanguageModelCallParams(messages = listOf(userMessage("hi"))))
        val events = drainAllItems(provider.chat("magistral-small-2507").stream(LanguageModelCallParams(messages = listOf(userMessage("hi")))))

        assertEquals("Final answer.", generated.text)
        assertEquals("First thought.", generated.content.filterIsInstance<ContentPart.Reasoning>().single().text)
        assertTrue(events.any { it is StreamEvent.ReasoningDelta && it.text == "Stream thought." })
        assertTrue(events.any { it is StreamEvent.TextDelta && it.text == "Stream answer." })
        assertEquals(FinishReason.Stop, events.filterIsInstance<StreamEvent.Finish>().single().finishReason)
    }

    @Test
    fun `unsupported model families and unconfigured singleton fail explicitly`() {
        val provider = createMistral(createTestServer(mutableMapOf()).httpClient(), MistralProviderSettings(apiKey = "key"))

        assertFailsWith<NoSuchModelError> { provider.imageModel("pixtral") }
        val chatError = assertFailsWith<AiSdkException> { mistral.chat("mistral-small-latest") }
        val embeddingError = assertFailsWith<AiSdkException> { mistral.embedding("mistral-embed") }
        assertNotNull(chatError.message)
        assertTrue(chatError.message.orEmpty().contains("createMistral"))
        assertTrue(embeddingError.message.orEmpty().contains("createMistral"))
    }

    private fun Map<String, String>.headerValue(name: String): String? =
        entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}
