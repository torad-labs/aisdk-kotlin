@file:OptIn(LowLevelLanguageModelApi::class)

package ai.torad.aisdk
import ai.torad.aisdk.providers.GOOGLE_VERSION
import ai.torad.aisdk.providers.GOOGLE_VERTEX_VERSION
import ai.torad.aisdk.providers.GoogleVertexAnthropicProviderSettings
import ai.torad.aisdk.providers.GoogleVertexMaasProviderSettings
import ai.torad.aisdk.providers.GoogleVertexProviderSettings
import ai.torad.aisdk.providers.GoogleVertexXaiProviderSettings

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import ai.torad.aisdk.providers.GoogleVertex
import ai.torad.aisdk.providers.GoogleVertexAnthropic
import ai.torad.aisdk.providers.GoogleVertexMaas
import ai.torad.aisdk.providers.GoogleVertexXai
import kotlinx.serialization.json.JsonObject

class GoogleVertexProviderTest {
    @Test
    fun `vertex core provider maps publisher base url auth and Gemini body`() = runTest {
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://us-central1-aiplatform.googleapis.com/v1beta1/projects/project-1/locations/us-central1/publishers/google/models/gemini-2.5-flash:generateContent" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """
                            {
                              "candidates":[{"content":{"parts":[{"text":"Vertex hello"}]},"finishReason":"STOP"}],
                              "usageMetadata":{"promptTokenCount":1,"candidatesTokenCount":2}
                            }
                            """.trimIndent(),
                        ),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val provider = GoogleVertex(
            fixture.httpClient(),
            GoogleVertexProviderSettings(block = {
                project("project-1")
                location("us-central1")
                accessToken("token")
                headers(mapOf("X-Provider" to "provider"))
            }),
        )

        val result = provider.chat(ModelId("gemini-2.5-flash")).generate(
            LanguageModelCallParams {
                messages(listOf(UserMessage("hi")))
                providerOptions(ProviderOptions.Raw(JsonObject(mapOf(
                    "google" to buildJsonObject {
                        put("sharedRequestType", JsonPrimitive("priority"))
                        put("requestType", JsonPrimitive("shared"))
                    },
                ))))
                headers(mapOf("X-Request" to "request"))
            },
        )

        assertEquals("google.vertex", provider.languageModel("gemini-2.5-flash").provider)
        // Vertex advertises http(s) + gs:// supported URLs, not the generative-AI files/YouTube set.
        assertEquals(
            mapOf("*" to listOf("^https?://.*$", "^gs://.*$")),
            provider.languageModel("gemini-2.5-flash").supportedUrls,
        )
        assertEquals("Vertex hello", result.text)
        assertEquals(FinishReason.Stop, result.finishReason)
        val request = fixture.calls.single()
        assertEquals("Bearer token", request.requestHeaders.headerValue("Authorization"))
        assertEquals("provider", request.requestHeaders.headerValue("X-Provider"))
        assertEquals("request", request.requestHeaders.headerValue("X-Request"))
        assertTrue(request.requestUserAgent.orEmpty().contains("ai-sdk/google-vertex/$GOOGLE_VERTEX_VERSION"))
        assertTrue(request.requestUserAgent.orEmpty().contains("ai-sdk/google/$GOOGLE_VERSION"))
        assertEquals("hi", request.requestBodyJson.jsonObject["contents"]?.jsonArray?.single()?.jsonObject?.get("parts")?.jsonArray?.single()?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `vertex core provider uses multi-region REP host for eu and us`() = runTest {
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://aiplatform.eu.rep.googleapis.com/v1beta1/projects/project-1/locations/eu/publishers/google/models/gemini-2.5-flash:generateContent" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """
                            {
                              "candidates":[{"content":{"parts":[{"text":"eu"}]},"finishReason":"STOP"}],
                              "usageMetadata":{"promptTokenCount":1,"candidatesTokenCount":1}
                            }
                            """.trimIndent(),
                        ),
                    ),
                ),
                "https://aiplatform.us.rep.googleapis.com/v1beta1/projects/project-1/locations/us/publishers/google/models/gemini-2.5-flash:generateContent" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """
                            {
                              "candidates":[{"content":{"parts":[{"text":"us"}]},"finishReason":"STOP"}],
                              "usageMetadata":{"promptTokenCount":1,"candidatesTokenCount":1}
                            }
                            """.trimIndent(),
                        ),
                    ),
                ),
            ),
        )
        fixture.server.start()

        val eu = GoogleVertex(
            fixture.httpClient(),
            GoogleVertexProviderSettings(block = {
                project("project-1")
                location("eu")
                accessToken("token")
            }),
        )
        val us = GoogleVertex(
            fixture.httpClient(),
            GoogleVertexProviderSettings(block = {
                project("project-1")
                location("us")
                accessToken("token")
            }),
        )

        assertEquals("eu", eu.chat(ModelId("gemini-2.5-flash")).generate(LanguageModelCallParams {
    messages(listOf(UserMessage("hi")))
}).text)
        assertEquals("us", us.chat(ModelId("gemini-2.5-flash")).generate(LanguageModelCallParams {
    messages(listOf(UserMessage("hi")))
}).text)
        assertEquals(
            listOf(
                "https://aiplatform.eu.rep.googleapis.com/v1beta1/projects/project-1/locations/eu/publishers/google/models/gemini-2.5-flash:generateContent",
                "https://aiplatform.us.rep.googleapis.com/v1beta1/projects/project-1/locations/us/publishers/google/models/gemini-2.5-flash:generateContent",
            ),
            fixture.calls.map { it.requestUrl },
        )
    }

    @Test
    fun `vertex maas routes through OpenAI compatible chat`() = runTest {
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://vertex-openai.test/v1/chat/completions" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """
                            {
                              "id":"chat-1",
                              "model":"llama-model",
                              "choices":[{"message":{"content":"maas"},"finish_reason":"stop"}],
                              "usage":{"prompt_tokens":3,"completion_tokens":4}
                            }
                            """.trimIndent(),
                        ),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val provider = GoogleVertexMaas(
            fixture.httpClient(),
            GoogleVertexProviderSettings(block = {
                baseURL("https://vertex-openai.test/v1")
                accessToken("token")
            }),
        )

        val result = provider.chat(ModelId("llama-model")).generate(LanguageModelCallParams {
    messages(listOf(UserMessage("hi")))
})

        assertEquals("maas", result.text)
        assertEquals(3, result.usage.promptTokens)
        assertEquals(4, result.usage.completionTokens)
        assertEquals("Bearer token", fixture.calls.single().requestHeaders.headerValue("Authorization"))
        assertEquals("llama-model", fixture.calls.single().requestBodyJson.jsonObject["model"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `vertex maas constructs global regional and multi-region base urls`() = runTest {
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://aiplatform.googleapis.com/v1/projects/project-1/locations/global/endpoints/openapi/chat/completions" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement("""{"id":"global","model":"llama","choices":[{"message":{"content":"global"},"finish_reason":"stop"}]}"""),
                    ),
                ),
                "https://us-central1-aiplatform.googleapis.com/v1/projects/project-1/locations/us-central1/endpoints/openapi/chat/completions" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement("""{"id":"regional","model":"llama","choices":[{"message":{"content":"regional"},"finish_reason":"stop"}]}"""),
                    ),
                ),
                "https://aiplatform.eu.rep.googleapis.com/v1/projects/project-1/locations/eu/endpoints/openapi/chat/completions" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement("""{"id":"eu","model":"llama","choices":[{"message":{"content":"eu"},"finish_reason":"stop"}]}"""),
                    ),
                ),
            ),
        )
        fixture.server.start()

        val global = GoogleVertexMaas(
            fixture.httpClient(),
            GoogleVertexProviderSettings(block = {
                project("project-1")
                location("global")
                accessToken("token")
            }),
        )
        val regional = GoogleVertexMaas(
            fixture.httpClient(),
            GoogleVertexProviderSettings(block = {
                project("project-1")
                location("us-central1")
                accessToken("token")
            }),
        )
        val eu = GoogleVertexMaas(
            fixture.httpClient(),
            GoogleVertexProviderSettings(block = {
                project("project-1")
                location("eu")
                accessToken("token")
            }),
        )

        assertEquals("global", global.chat(ModelId("llama")).generate(LanguageModelCallParams {
    messages(listOf(UserMessage("hi")))
}).text)
        assertEquals("regional", regional.chat(ModelId("llama")).generate(LanguageModelCallParams {
    messages(listOf(UserMessage("hi")))
}).text)
        assertEquals("eu", eu.chat(ModelId("llama")).generate(LanguageModelCallParams {
    messages(listOf(UserMessage("hi")))
}).text)
        assertEquals(
            listOf(
                "https://aiplatform.googleapis.com/v1/projects/project-1/locations/global/endpoints/openapi/chat/completions",
                "https://us-central1-aiplatform.googleapis.com/v1/projects/project-1/locations/us-central1/endpoints/openapi/chat/completions",
                "https://aiplatform.eu.rep.googleapis.com/v1/projects/project-1/locations/eu/endpoints/openapi/chat/completions",
            ),
            fixture.calls.map { it.requestUrl },
        )
    }

    @Test
    fun `vertex anthropic maps rawPredict url headers and body`() = runTest {
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://us-central1-aiplatform.googleapis.com/v1/projects/project-1/locations/us-central1/publishers/anthropic/models/claude-sonnet-4:rawPredict" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """
                            {
                              "id":"msg_1",
                              "type":"message",
                              "role":"assistant",
                              "content":[{"type":"text","text":"vertex anthropic"}],
                              "stop_reason":"end_turn",
                              "usage":{"input_tokens":3,"output_tokens":4}
                            }
                            """.trimIndent(),
                        ),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val missingProject = assertFailsWith<AiSdkException> {
            GoogleVertex(
                fixture.httpClient(),
                GoogleVertexProviderSettings(block = {
                    accessToken("token")
                }),
            )
        }
        assertTrue(missingProject.message.orEmpty().contains("project is required"))

        val anthropic = GoogleVertexAnthropic(
            fixture.httpClient(),
            GoogleVertexProviderSettings(block = {
                project("project-1")
                accessToken("token")
            }),
        )
        assertEquals("vertex.anthropic.messages", anthropic.languageModel("claude-sonnet-4").provider)
        assertEquals("vertex.anthropic.messages", anthropic.messages(ModelId("claude-sonnet-4")).provider)

        val result = anthropic.messages(ModelId("claude-sonnet-4")).generate(
            LanguageModelCallParams {
                messages(listOf(UserMessage("hi")))
                headers(mapOf("X-Request" to "request"))
            },
        )

        assertEquals("vertex anthropic", result.text)
        assertEquals(3, result.usage.promptTokens)
        assertEquals(4, result.usage.completionTokens)
        val request = fixture.calls.single()
        assertEquals("Bearer token", request.requestHeaders.headerValue("Authorization"))
        assertEquals("request", request.requestHeaders.headerValue("X-Request"))
        assertEquals(null, request.requestHeaders.headerValue("anthropic-version"))
        assertTrue(request.requestUserAgent.orEmpty().contains("ai-sdk/google-vertex/$GOOGLE_VERTEX_VERSION"))
        val body = request.requestBodyJson.jsonObject
        assertEquals(null, body["model"])
        assertEquals("vertex-2023-10-16", body["anthropic_version"]?.jsonPrimitive?.contentOrNull)
        assertEquals("hi", body["messages"]?.jsonArray?.single()?.jsonObject?.get("content")?.jsonArray?.single()?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `vertex anthropic uses multi-region REP host for eu and us`() = runTest {
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://aiplatform.eu.rep.googleapis.com/v1/projects/project-1/locations/eu/publishers/anthropic/models/claude-sonnet-4:rawPredict" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """
                            {
                              "id":"msg_1",
                              "type":"message",
                              "role":"assistant",
                              "content":[{"type":"text","text":"eu"}],
                              "stop_reason":"end_turn",
                              "usage":{"input_tokens":1,"output_tokens":1}
                            }
                            """.trimIndent(),
                        ),
                    ),
                ),
                "https://aiplatform.us.rep.googleapis.com/v1/projects/project-1/locations/us/publishers/anthropic/models/claude-sonnet-4:rawPredict" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """
                            {
                              "id":"msg_2",
                              "type":"message",
                              "role":"assistant",
                              "content":[{"type":"text","text":"us"}],
                              "stop_reason":"end_turn",
                              "usage":{"input_tokens":1,"output_tokens":1}
                            }
                            """.trimIndent(),
                        ),
                    ),
                ),
            ),
        )
        fixture.server.start()

        val eu = GoogleVertexAnthropic(
            fixture.httpClient(),
            GoogleVertexProviderSettings(block = {
                project("project-1")
                location("eu")
                accessToken("token")
            }),
        )
        val us = GoogleVertexAnthropic(
            fixture.httpClient(),
            GoogleVertexProviderSettings(block = {
                project("project-1")
                location("us")
                accessToken("token")
            }),
        )

        assertEquals("eu", eu.messages(ModelId("claude-sonnet-4")).generate(LanguageModelCallParams {
    messages(listOf(UserMessage("hi")))
}).text)
        assertEquals("us", us.messages(ModelId("claude-sonnet-4")).generate(LanguageModelCallParams {
    messages(listOf(UserMessage("hi")))
}).text)
        assertEquals(
            listOf(
                "https://aiplatform.eu.rep.googleapis.com/v1/projects/project-1/locations/eu/publishers/anthropic/models/claude-sonnet-4:rawPredict",
                "https://aiplatform.us.rep.googleapis.com/v1/projects/project-1/locations/us/publishers/anthropic/models/claude-sonnet-4:rawPredict",
            ),
            fixture.calls.map { it.requestUrl },
        )
    }

    @Test
    fun `vertex xai drops reasoning effort and uses xai usage totals`() = runTest {
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://aiplatform.googleapis.com/v1/projects/project-1/locations/global/endpoints/openapi/chat/completions" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """
                            {
                              "id":"chat-1",
                              "model":"grok",
                              "choices":[{"message":{"content":"xai"},"finish_reason":"stop"}],
                              "usage":{
                                "prompt_tokens":5,
                                "completion_tokens":7,
                                "prompt_tokens_details":{"cached_tokens":2},
                                "completion_tokens_details":{"reasoning_tokens":3}
                              }
                            }
                            """.trimIndent(),
                        ),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val provider = GoogleVertexXai(
            fixture.httpClient(),
            GoogleVertexProviderSettings(block = {
                project("project-1")
                location("global")
                accessToken("token")
            }),
        )

        val result = provider.chatModel(ModelId("grok")).generate(
            LanguageModelCallParams {
                messages(listOf(UserMessage("hi")))
                providerOptions(ProviderOptions.Raw(JsonObject(mapOf(
                    "xai" to buildJsonObject {
                        put("reasoningEffort", JsonPrimitive("high"))
                        put("topLogprobs", JsonPrimitive(3))
                    },
                ))))
            },
        )

        assertEquals("xai", result.text)
        assertEquals(5, result.usage.promptTokens)
        assertEquals(10, result.usage.completionTokens)
        assertEquals(2, result.usage.inputTokens.cacheRead)
        assertEquals(3, result.usage.outputTokens.reasoning)
        val request = fixture.calls.single()
        assertEquals("Bearer token", request.requestHeaders.headerValue("Authorization"))
        assertEquals("grok", request.requestBodyJson.jsonObject["model"]?.jsonPrimitive?.contentOrNull)
        assertEquals(null, request.requestBodyJson.jsonObject["reasoning_effort"])
        assertEquals(3, request.requestBodyJson.jsonObject["top_logprobs"]?.jsonPrimitive?.intOrNull)
        assertEquals(true, request.requestBodyJson.jsonObject["logprobs"]?.jsonPrimitive?.contentOrNull?.toBoolean())
    }

    private fun Map<String, String>.headerValue(name: String): String? =
        entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}
