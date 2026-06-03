package ai.torad.aisdk

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

class GoogleVertexProviderTest {
    @Test
    fun `vertex core provider maps publisher base url auth and Gemini body`() = runTest {
        val fixture = createTestServer(
            mutableMapOf(
                "https://us-central1-aiplatform.googleapis.com/v1/projects/project-1/locations/us-central1/publishers/google/models/gemini-2.5-flash:generateContent" to UrlHandler(
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
        val provider = createVertex(
            fixture.httpClient(),
            GoogleVertexProviderSettings(
                project = "project-1",
                location = "us-central1",
                accessToken = "token",
                headers = mapOf("X-Provider" to "provider"),
            ),
        )

        val result = provider.chat("gemini-2.5-flash").generate(
            LanguageModelCallParams(
                messages = listOf(userMessage("hi")),
                providerOptions = mapOf(
                    "google" to buildJsonObject {
                        put("sharedRequestType", JsonPrimitive("priority"))
                        put("requestType", JsonPrimitive("shared"))
                    },
                ),
                headers = mapOf("X-Request" to "request"),
            ),
        )

        assertEquals("google.vertex", provider.languageModel("gemini-2.5-flash").provider)
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
    fun `vertex maas routes through OpenAI compatible chat`() = runTest {
        val fixture = createTestServer(
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
        val provider = createVertexMaas(
            fixture.httpClient(),
            GoogleVertexMaasProviderSettings(baseURL = "https://vertex-openai.test/v1", accessToken = "token"),
        )

        val result = provider.chat("llama-model").generate(LanguageModelCallParams(messages = listOf(userMessage("hi"))))

        assertEquals("maas", result.text)
        assertEquals(3, result.usage.promptTokens)
        assertEquals(4, result.usage.completionTokens)
        assertEquals("Bearer token", fixture.calls.single().requestHeaders.headerValue("Authorization"))
        assertEquals("llama-model", fixture.calls.single().requestBodyJson.jsonObject["model"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `vertex anthropic subprovider is explicit and project is required`() = runTest {
        val fixture = createTestServer(mutableMapOf())
        val missingProject = assertFailsWith<AiSdkException> {
            createVertex(fixture.httpClient(), GoogleVertexProviderSettings(accessToken = "token"))
        }
        assertTrue(missingProject.message.orEmpty().contains("project is required"))

        val anthropic = createVertexAnthropic(
            fixture.httpClient(),
            GoogleVertexAnthropicProviderSettings(project = "project-1", accessToken = "token"),
        )
        assertEquals("google.vertex.anthropic", anthropic.languageModel("claude-sonnet-4").provider)
        val error = assertFailsWith<AiSdkException> {
            anthropic.languageModel("claude-sonnet-4").generate(LanguageModelCallParams(messages = listOf(userMessage("hi"))))
        }
        assertTrue(error.message.orEmpty().contains("not implemented"))
    }

    private fun Map<String, String>.headerValue(name: String): String? =
        entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}
