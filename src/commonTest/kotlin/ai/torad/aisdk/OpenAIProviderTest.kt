package ai.torad.aisdk

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OpenAIProviderTest {
    @Test
    fun `createOpenAI uses default base URL and OpenAI headers`() = runTest {
        val seenRequests = mutableListOf<HttpRequestData>()
        val client = HttpClient(
            MockEngine { request ->
                seenRequests += request
                respond(
                    content = """
                        {
                          "object":"list",
                          "data":[{"object":"embedding","index":0,"embedding":[0.1,0.2]}],
                          "model":"text-embedding-3-small",
                          "usage":{"prompt_tokens":1,"total_tokens":1}
                        }
                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )

        val provider = createOpenAI(
            client,
            OpenAIProviderSettings(
                apiKey = "test-api-key",
                organization = "org_123",
                project = "proj_123",
                headers = mapOf("x-extra" to "yes"),
            ),
        )

        val result = embed(provider.embedding("text-embedding-3-small"), "hello")

        val request = seenRequests.single()
        assertEquals("https://api.openai.com/v1/embeddings", request.url.toString())
        assertEquals("Bearer test-api-key", request.headers[HttpHeaders.Authorization])
        assertEquals("org_123", request.headers["OpenAI-Organization"])
        assertEquals("proj_123", request.headers["OpenAI-Project"])
        assertEquals("yes", request.headers["x-extra"])
        assertTrue(request.headers[HttpHeaders.UserAgent].orEmpty().contains("ai-sdk/openai/$VERSION"))
        assertEquals(listOf(0.1f, 0.2f), result.embedding)
    }

    @Test
    fun `createOpenAI trims custom base URL and aliases model factories`() = runTest {
        val seenPaths = mutableListOf<String>()
        val client = HttpClient(
            MockEngine { request ->
                seenPaths += request.url.encodedPath
                when (request.url.encodedPath) {
                    "/v1/chat/completions" -> respond(
                        """{"id":"chat_1","model":"gpt-5","choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":1}}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                    "/v1/completions" -> respond(
                        """{"id":"cmpl_1","model":"davinci","choices":[{"text":"done","finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":1}}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                    else -> respond("{}", HttpStatusCode.NotFound, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            },
        )
        val provider = createOpenAI(
            client,
            OpenAIProviderSettings(
                baseURL = "https://proxy.openai.example/v1/",
                apiKey = "test-api-key",
            ),
        )

        val chat = generateText<String>(provider("gpt-5"), prompt = "hi")
        val directChat = generateText<String>(provider.chat("gpt-5"), prompt = "hi")
        val completion = generateText<String>(provider.completion("davinci"), prompt = "hi")

        assertEquals("ok", chat.text)
        assertEquals("ok", directChat.text)
        assertEquals("done", completion.text)
        assertEquals(listOf("/v1/chat/completions", "/v1/chat/completions", "/v1/completions"), seenPaths)
    }

    @Test
    fun `OpenAI tools are provider-executed and expose provider tool ids`() {
        val tools = createOpenAI(HttpClient(MockEngine { respond("{}") })).tools

        assertProviderTool(tools.codeInterpreter, "code_interpreter", "openai.code_interpreter")
        assertProviderTool(tools.fileSearch, "file_search", "openai.file_search")
        assertProviderTool(tools.webSearch, "web_search", "openai.web_search")
        assertProviderTool(tools.toolSearch, "tool_search", "openai.tool_search")
    }

    @Test
    fun `default openai singleton fails explicitly without a configured client`() {
        val error = assertFailsWith<OpenAIProviderNotConfiguredError> {
            openai.chat("gpt-5")
        }

        assertNotNull(error.message)
        assertTrue(error.message.orEmpty().contains("createOpenAI"))
    }

    @Test
    fun `createOpenAI sends provider options through OpenAI-compatible request body`() = runTest {
        val seenBody = mutableListOf<JsonObject>()
        val client = HttpClient(
            MockEngine { request ->
                seenBody += Json.parseToJsonElement(requestBodyText(request)).jsonObject
                respond(
                    """{"id":"chat_1","model":"gpt-5","choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":1}}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        val provider = createOpenAI(client, OpenAIProviderSettings(apiKey = "test-api-key"))

        generateText<String>(
            model = provider.chat("gpt-5"),
            prompt = "hi",
            providerOptions = mapOf(
                "openai" to JsonObject(
                    mapOf("parallel_tool_calls" to JsonPrimitive(false)),
                ),
            ),
        )

        assertEquals(JsonPrimitive(false), seenBody.single()["parallel_tool_calls"])
    }

    private fun assertProviderTool(tool: Tool<JsonElement, JsonElement, Any?>, name: String, providerToolId: String) {
        assertEquals(name, tool.name)
        assertEquals(true, tool.providerExecuted)
        assertEquals(JsonPrimitive(providerToolId), tool.metadata["providerToolId"])
    }

    private fun requestBodyText(request: HttpRequestData): String =
        when (val body = request.body) {
            is OutgoingContent.ByteArrayContent -> body.bytes().decodeToString()
            is OutgoingContent.NoContent -> ""
            else -> body.toString()
        }
}
