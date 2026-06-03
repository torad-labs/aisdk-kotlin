package ai.torad.aisdk

import io.ktor.http.HttpHeaders
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OpenAICompatibleProviderFacadesTest {
    @Test
    fun `provider facades use upstream base urls headers and provider ids`() = runTest {
        val providers = listOf(
            ProviderCase(
                name = "deepseek",
                baseURL = "https://deepseek.test",
                expectedUserAgent = "ai-sdk/deepseek/$DEEPSEEK_VERSION",
                create = { client -> createDeepSeek(client, DeepSeekProviderSettings(apiKey = "key", baseURL = "https://deepseek.test")).languageModel("model") },
            ),
            ProviderCase(
                name = "cerebras",
                baseURL = "https://cerebras.test/v1",
                expectedUserAgent = "ai-sdk/cerebras/$CEREBRAS_VERSION",
                create = { client -> createCerebras(client, CerebrasProviderSettings(apiKey = "key", baseURL = "https://cerebras.test/v1")).languageModel("model") },
            ),
            ProviderCase(
                name = "perplexity",
                baseURL = "https://perplexity.test",
                expectedUserAgent = "ai-sdk/perplexity/$PERPLEXITY_VERSION",
                create = { client -> createPerplexity(client, PerplexityProviderSettings(apiKey = "key", baseURL = "https://perplexity.test")).languageModel("model") },
            ),
            ProviderCase(
                name = "moonshotai",
                baseURL = "https://moonshot.test/v1",
                expectedUserAgent = "ai-sdk/moonshotai/$MOONSHOTAI_VERSION",
                create = { client -> createMoonshotAI(client, MoonshotAIProviderSettings(apiKey = "key", baseURL = "https://moonshot.test/v1")).chatModel("model") },
            ),
            ProviderCase(
                name = "groq",
                baseURL = "https://groq.test/openai/v1",
                expectedUserAgent = "ai-sdk/groq/$GROQ_VERSION",
                create = { client -> createGroq(client, GroqProviderSettings(apiKey = "key", baseURL = "https://groq.test/openai/v1")).chat("model") },
            ),
        )

        for (case in providers) {
            val seenRequests = mutableListOf<TestServerCall>()
            val fixture = createTestServer(
                mutableMapOf(
                    "${case.baseURL}/chat/completions" to UrlHandler(
                        UrlResponse.JsonValue(
                            Json.parseToJsonElement(
                                """{"id":"id_1","model":"model","choices":[{"message":{"role":"assistant","content":"${case.name}"},"finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":2}}""",
                            ),
                        ),
                    ),
                ),
            )
            fixture.server.start()

            val model = case.create(fixture.httpClient())
            val result = model.generate(LanguageModelCallParams(listOf(userMessage("hi"))))
            seenRequests += fixture.calls

            assertEquals(case.name, result.text)
            assertEquals("${case.name}.chat", model.provider)
            val request = seenRequests.single()
            assertEquals("POST", request.requestMethod)
            assertEquals("${case.baseURL}/chat/completions", request.requestUrl)
            assertEquals("Bearer key", request.requestHeaders.headerValue(HttpHeaders.Authorization))
            assertTrue(request.requestUserAgent.orEmpty().contains(case.expectedUserAgent))
            assertEquals("model", request.requestBodyJson.jsonObject["model"]?.jsonPrimitive?.contentOrNull)
        }
    }

    @Test
    fun `groq exposes transcription and browser search tool`() = runTest {
        val fixture = createTestServer(
            mutableMapOf(
                "https://groq.test/openai/v1/audio/transcriptions" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement("""{"text":"transcribed"}"""),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val provider = createGroq(
            fixture.httpClient(),
            GroqProviderSettings(apiKey = "key", baseURL = "https://groq.test/openai/v1"),
        )

        val transcript = provider.transcription("whisper-large-v3").transcribe(
            TranscriptionParams(
                audio = AudioSource(mediaType = "audio/mpeg", base64 = convertByteArrayToBase64(byteArrayOf(1, 2))),
            ),
        )

        assertEquals("transcribed", transcript.text)
        assertEquals("browserSearch", provider.tools.browserSearch.name)
        assertEquals(JsonPrimitive("groq.browser_search"), provider.tools.browserSearch.metadata["providerToolId"])
    }

    @Test
    fun `unsupported model families throw provider specific NoSuchModelError`() {
        val provider = createDeepSeek(
            TestServer(mutableMapOf()).httpClient(),
            DeepSeekProviderSettings(baseURL = "https://deepseek.test"),
        )

        val error = assertFailsWith<NoSuchModelError> {
            provider.embeddingModel("embedding")
        }

        assertTrue(error.message.orEmpty().contains("deepseek"))
    }

    private data class ProviderCase(
        val name: String,
        val baseURL: String,
        val expectedUserAgent: String,
        val create: (io.ktor.client.HttpClient) -> LanguageModel,
    )

    private fun Map<String, String>.headerValue(name: String): String? =
        entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}
