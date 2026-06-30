@file:OptIn(LowLevelLanguageModelApi::class)

package ai.torad.aisdk
import ai.torad.aisdk.providers.AZURE_VERSION
import ai.torad.aisdk.providers.AzureOpenAIProviderSettings

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Headers
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import ai.torad.aisdk.providers.AzureOpenAI

class AzureProviderTest {
    @Test
    fun `default language model uses Azure responses URL api key auth headers and OpenAI options`() = runTest {
        val seenRequests = mutableListOf<HttpRequestData>()
        val seenBodies = mutableListOf<JsonObject>()
        val client = HttpClient(
            MockEngine { request ->
                seenRequests += request
                seenBodies += Json.parseToJsonElement(requestBodyText(request)).jsonObject
                respond(
                    content = azureResponsesJson("hello from responses"),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        val provider = AzureOpenAI(
            client,
            AzureOpenAIProviderSettings(
                resourceName = "test-resource",
                apiKey = "test-api-key",
                headers = mapOf("Custom-Provider-Header" to "provider-header-value"),
            ),
        )

        val result = provider("test-deployment").generate(
            LanguageModelCallParams(
                messages = listOf(UserMessage("Hello")),
                maxOutputTokens = 64,
                providerOptions = ProviderOptions.Raw(JsonObject(mapOf(
                    "openai" to JsonObject(
                        mapOf(
                            "reasoningEffort" to JsonPrimitive("low"),
                            "reasoningSummary" to JsonPrimitive("concise"),
                        ),
                    ),
                ))),
                headers = mapOf("Custom-Request-Header" to "request-header-value"),
            ),
        )

        val request = seenRequests.single()
        val body = seenBodies.single()
        assertEquals(
            "https://test-resource.openai.azure.com/openai/v1/responses?api-version=v1",
            request.url.toString(),
        )
        assertEquals("test-api-key", request.headers.headerValue("api-key"))
        assertEquals(null, request.headers.headerValue(HttpHeaders.Authorization))
        assertEquals("provider-header-value", request.headers.headerValue("Custom-Provider-Header"))
        assertEquals("request-header-value", request.headers.headerValue("Custom-Request-Header"))
        assertTrue(request.headers.headerValue(HttpHeaders.UserAgent).orEmpty().contains("ai-sdk/azure/$AZURE_VERSION"))
        assertEquals("test-deployment", body["model"]?.jsonPrimitive?.contentOrNull)
        assertEquals("low", body["reasoning"]?.jsonObject?.get("effort")?.jsonPrimitive?.contentOrNull)
        assertEquals("concise", body["reasoning"]?.jsonObject?.get("summary")?.jsonPrimitive?.contentOrNull)
        assertEquals("hello from responses", result.text)
        assertEquals("azure.responses", provider.languageModel("test-deployment").provider)
    }

    @Test
    fun `chat uses token provider for every request and modified api version`() = runTest {
        var tokenCount = 0
        val seenRequests = mutableListOf<HttpRequestData>()
        val client = HttpClient(
            MockEngine { request ->
                seenRequests += request
                respond(
                    content = openAIChatJson("ok"),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        val provider = AzureOpenAI(
            client,
            AzureOpenAIProviderSettings(
                resourceName = "test-resource",
                tokenProvider = { "token-${++tokenCount}" },
                apiVersion = "2025-04-01-preview",
            ),
        )

        provider.chat(ModelId("test-deployment")).generate(LanguageModelCallParams(listOf(UserMessage("Hi"))))
        provider.chat(ModelId("test-deployment")).generate(LanguageModelCallParams(listOf(UserMessage("Hi again"))))

        assertEquals(2, tokenCount)
        assertEquals(
            "https://test-resource.openai.azure.com/openai/v1/chat/completions?api-version=2025-04-01-preview",
            seenRequests.first().url.toString(),
        )
        assertEquals("Bearer token-1", seenRequests[0].headers.headerValue(HttpHeaders.Authorization))
        assertEquals("Bearer token-2", seenRequests[1].headers.headerValue(HttpHeaders.Authorization))
        assertEquals(null, seenRequests[0].headers.headerValue("api-key"))
    }

    @Test
    fun `model factories use base URL deployment URLs aliases and OpenAI image options`() = runTest {
        val seenRequests = mutableListOf<HttpRequestData>()
        val seenBodies = mutableListOf<JsonObject>()
        val client = HttpClient(
            MockEngine { request ->
                seenRequests += request
                if (request.url.encodedPath.endsWith("/images/generations")) {
                    seenBodies += Json.parseToJsonElement(requestBodyText(request)).jsonObject
                    respond(
                        content = """{"data":[{"b64_json":"base64-image-1"}]}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                } else {
                    respond(
                        content = """{"text":"Hello, world!","segments":[]}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            },
        )
        val provider = AzureOpenAI(
            client,
            AzureOpenAIProviderSettings(
                baseURL = "https://proxy.example/openai/",
                apiKey = "test-api-key",
                useDeploymentBasedUrls = true,
            ),
        )

        val image = provider.imageModel("dalle-deployment").generate(
            ImageGenerationParams(
                prompt = "A cute baby sea otter",
                n = 2,
                size = "1024x1024",
                providerOptions = ProviderOptions.Raw(JsonObject(mapOf("openai" to JsonObject(mapOf("style" to JsonPrimitive("natural")))))),
            ),
        )
        val transcript = provider.transcription(ModelId("whisper-1")).transcribe(
            TranscriptionParams(
                audio = AudioSource(
                    mediaType = "audio/wav",
                    base64 = Base64Codec.encode(byteArrayOf(1, 2, 3)),
                ),
            ),
        )

        assertEquals("base64-image-1", image.images.single().base64)
        assertEquals("Hello, world!", transcript.text)
        assertEquals(
            "https://proxy.example/openai/deployments/dalle-deployment/images/generations?api-version=v1",
            seenRequests[0].url.toString(),
        )
        assertEquals(
            "https://proxy.example/openai/deployments/whisper-1/audio/transcriptions?api-version=v1",
            seenRequests[1].url.toString(),
        )
        assertEquals("natural", seenBodies.single()["style"]?.jsonPrimitive?.contentOrNull)
        assertEquals("azure.chat", provider.chat(ModelId("chat-deployment")).provider)
        assertEquals("azure.completion", provider.completion(ModelId("completion-deployment")).provider)
        assertEquals("azure.embeddings", provider.embedding(ModelId("embedding-deployment")).provider)
        assertEquals("azure.image", provider.image(ModelId("image-deployment")).provider)
        assertEquals("azure.transcription", provider.transcriptionModel("whisper-1").provider)
        assertEquals("azure.speech", provider.speech(ModelId("tts-1")).provider)
        assertEquals(provider.embedding(ModelId("embedding-deployment")).modelId, provider.textEmbeddingModel(ModelId("embedding-deployment")).modelId)
    }

    @Test
    fun `tools mirror Azure OpenAI hosted tool subset`() {
        val tools = AzureOpenAI(
            HttpClient(MockEngine { respond("{}") }),
            AzureOpenAIProviderSettings(resourceName = "test-resource", apiKey = "test-api-key"),
        ).tools

        assertProviderTool(tools.codeInterpreter, "code_interpreter", "openai.code_interpreter")
        assertProviderTool(tools.fileSearch, "file_search", "openai.file_search")
        assertProviderTool(tools.imageGeneration, "image_generation", "openai.image_generation")
        assertProviderTool(tools.webSearch, "web_search", "openai.web_search")
        assertProviderTool(tools.webSearchPreview, "web_search_preview", "openai.web_search_preview")
    }

    @Test
    fun `rejects conflicting Azure auth settings and unconfigured singleton fails explicitly`() {
        val error = assertFailsWith<InvalidArgumentError> {
            AzureOpenAI(
                HttpClient(MockEngine { respond("{}") }),
                AzureOpenAIProviderSettings(
                    resourceName = "test-resource",
                    apiKey = "test-api-key",
                    tokenProvider = { "token" },
                ),
            )
        }
        assertTrue(error.message.orEmpty().contains("Both apiKey and tokenProvider were provided"))

    }

    private fun azureResponsesJson(text: String): String =
        """
            {
              "id":"resp_1",
              "created_at":1780000000,
              "model":"test-deployment",
              "output":[
                {"type":"message","id":"msg_1","role":"assistant","content":[{"type":"output_text","text":"$text"}]}
              ],
              "usage":{"input_tokens":4,"output_tokens":5,"total_tokens":9}
            }
        """.trimIndent()

    private fun openAIChatJson(text: String): String =
        """
            {
              "id":"chat_1",
              "model":"test-deployment",
              "choices":[{"message":{"role":"assistant","content":"$text"},"finish_reason":"stop"}],
              "usage":{"prompt_tokens":1,"completion_tokens":1}
            }
        """.trimIndent()

    private fun requestBodyText(request: HttpRequestData): String =
        when (val body = request.body) {
            is OutgoingContent.ByteArrayContent -> body.bytes().decodeToString()
            is OutgoingContent.NoContent -> ""
            else -> body.toString()
        }

    private fun Headers.headerValue(name: String): String? =
        entries().firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()

    private fun assertProviderTool(tool: Tool<JsonElement, JsonElement, Any?>, name: String, providerToolId: String) {
        assertEquals(name, tool.name)
        assertEquals(true, tool.providerExecuted)
        assertEquals(JsonPrimitive(providerToolId), tool.metadata["providerToolId"])
    }
}
