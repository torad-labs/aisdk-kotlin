@file:OptIn(LowLevelLanguageModelApi::class)

package ai.torad.aisdk
import ai.torad.aisdk.providers.BASETEN_VERSION
import ai.torad.aisdk.providers.BasetenProviderSettings
import ai.torad.aisdk.providers.Baseten

import io.ktor.http.HttpHeaders
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BasetenProviderTest {
    @Test
    fun `default chat model uses model api base url and baseten headers`() = runTest {
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://inference.baseten.co/v1/chat/completions" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """{"id":"chat_1","model":"deepseek-ai/DeepSeek-V3-0324","choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":2}}""",
                        ),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val provider = Baseten(fixture.httpClient(), BasetenProviderSettings(apiKey = "key"))

        val result = provider.chatModel(ModelId("deepseek-ai/DeepSeek-V3-0324")).generate(LanguageModelCallParams(listOf(UserMessage("hi"))))

        assertEquals("ok", result.text)
        assertEquals("baseten.chat", provider.chatModel(ModelId("deepseek-ai/DeepSeek-V3-0324")).provider)
        val call = fixture.calls.single()
        assertEquals("Bearer key", call.requestHeaders.headerValue(HttpHeaders.Authorization))
        assertTrue(call.requestUserAgent.orEmpty().contains("ai-sdk/baseten/$BASETEN_VERSION"))
        assertEquals("deepseek-ai/DeepSeek-V3-0324", call.requestBodyJson.jsonObject["model"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `custom sync v1 chat endpoint uses placeholder default model id`() = runTest {
        val modelURL = "https://model-123.api.baseten.co/environments/production/sync/v1"
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "$modelURL/chat/completions" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """{"id":"chat_1","model":"placeholder","choices":[{"message":{"role":"assistant","content":"custom"},"finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":2}}""",
                        ),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val provider = Baseten(fixture.httpClient(), BasetenProviderSettings(apiKey = "key", modelURL = modelURL))

        val result = provider.chatModel().generate(LanguageModelCallParams(listOf(UserMessage("hi"))))

        assertEquals("custom", result.text)
        assertEquals("placeholder", fixture.calls.single().requestBodyJson.jsonObject["model"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `chat rejects custom predict endpoints`() {
        val fixture = TestServer.createTestServer(mutableMapOf())
        val provider = Baseten(
            fixture.httpClient(),
            BasetenProviderSettings(
                apiKey = "key",
                modelURL = "https://model-123.api.baseten.co/environments/production/predict",
            ),
        )

        assertFailsWith<AiSdkException> { provider.chatModel() }
    }

    @Test
    fun `embedding requires model url and normalizes sync endpoint`() = runTest {
        val modelURL = "https://model-123.api.baseten.co/environments/production/sync"
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "$modelURL/v1/embeddings" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """{"data":[{"index":0,"embedding":[0.1,0.2]}],"usage":{"prompt_tokens":3}}""",
                        ),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val provider = Baseten(fixture.httpClient(), BasetenProviderSettings(apiKey = "key", modelURL = modelURL))

        val result = provider.embeddingModel().embed(EmbeddingModelCallParams(listOf("hello")))

        assertEquals(listOf(0.1f, 0.2f), result.embeddings.single())
        assertEquals(3, result.usage.tokens)
        val body = fixture.calls.single().requestBodyJson.jsonObject
        assertEquals("embeddings", body["model"]?.jsonPrimitive?.contentOrNull)
        assertEquals("hello", body["input"]?.jsonArray?.single()?.jsonPrimitive?.contentOrNull)
        assertEquals("float", body["encoding_format"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `embedding rejects missing or unsupported model urls and unsupported image models`() {
        val fixture = TestServer.createTestServer(mutableMapOf())

        assertFailsWith<AiSdkException> {
            Baseten(fixture.httpClient(), BasetenProviderSettings(apiKey = "key")).embeddingModel()
        }
        assertFailsWith<AiSdkException> {
            Baseten(
                fixture.httpClient(),
                BasetenProviderSettings(apiKey = "key", modelURL = "https://model.example/predict"),
            ).embeddingModel()
        }

        val provider = Baseten(fixture.httpClient(), BasetenProviderSettings(apiKey = "key"))
        assertFailsWith<NoSuchModelError> { provider.imageModel("image") }
    }

    private fun Map<String, String>.headerValue(name: String): String? =
        entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}
