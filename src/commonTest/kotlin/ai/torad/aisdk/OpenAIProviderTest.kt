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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
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
                    "/v1/responses" -> respond(
                        """{"id":"resp_1","model":"gpt-5","output":[{"type":"message","id":"msg_1","role":"assistant","content":[{"type":"output_text","text":"from responses"}]}],"usage":{"input_tokens":1,"output_tokens":1}}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
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

        val responses = generateText<String>(provider("gpt-5"), prompt = "hi")
        val directChat = generateText<String>(provider.chat("gpt-5"), prompt = "hi")
        val completion = generateText<String>(provider.completion("davinci"), prompt = "hi")

        assertEquals("from responses", responses.text)
        assertEquals("ok", directChat.text)
        assertEquals("done", completion.text)
        assertEquals(listOf("/v1/responses", "/v1/chat/completions", "/v1/completions"), seenPaths)
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

    @Test
    fun `OpenAI responses model uses Responses endpoint headers query provider options and supported URLs`() = runTest {
        val seenRequests = mutableListOf<HttpRequestData>()
        val seenBody = mutableListOf<JsonObject>()
        val client = HttpClient(
            MockEngine { request ->
                seenRequests += request
                seenBody += Json.parseToJsonElement(requestBodyText(request)).jsonObject
                respond(
                    """{"id":"resp_1","model":"gpt-5","output":[{"type":"message","id":"msg_1","role":"assistant","content":[{"type":"output_text","text":"ok"}]}],"usage":{"input_tokens":2,"output_tokens":3}}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        val provider = createOpenAI(
            client,
            OpenAIProviderSettings(
                baseURL = "https://proxy.openai.example/v1/",
                apiKey = "test-api-key",
                organization = "org_123",
                project = "proj_123",
                headers = mapOf("x-extra" to "yes"),
                name = "custom",
                queryParams = mapOf("api-version" to "2026-06-03"),
            ),
        )

        val model = provider.responses("gpt-5")
        val result = generateText<String>(
            model = model,
            prompt = "hi",
            providerOptions = mapOf(
                "openai" to buildJsonObject {
                    put("conversation", JsonPrimitive("conv_123"))
                    put("include", JsonArray(listOf(JsonPrimitive("file_search_call.results"))))
                    put("instructions", JsonPrimitive("continue carefully"))
                    put("logprobs", JsonPrimitive(true))
                    put("maxToolCalls", JsonPrimitive(4))
                    put("metadata", buildJsonObject { put("trace", JsonPrimitive("abc")) })
                    put("parallelToolCalls", JsonPrimitive(false))
                    put("previousResponseId", JsonPrimitive("resp_prev"))
                    put("promptCacheKey", JsonPrimitive("cache-key"))
                    put("promptCacheRetention", JsonPrimitive("24h"))
                    put("reasoningEffort", JsonPrimitive("low"))
                    put("reasoningSummary", JsonPrimitive("concise"))
                    put("safetyIdentifier", JsonPrimitive("user-safe"))
                    put("serviceTier", JsonPrimitive("priority"))
                    put("store", JsonPrimitive(false))
                    put("strictJsonSchema", JsonPrimitive(false))
                    put("textVerbosity", JsonPrimitive("low"))
                    put("truncation", JsonPrimitive("disabled"))
                    put("user", JsonPrimitive("user_123"))
                    put("forceReasoning", JsonPrimitive(true))
                },
            ),
            responseFormat = ResponseFormat.Json(schemaName = "Answer", schemaJson = JsonObject(emptyMap())),
        )

        val request = seenRequests.single()
        val body = seenBody.single()
        assertEquals("custom.responses", model.provider)
        assertEquals(listOf("^https?://.*$"), model.supportedUrls["image/*"])
        assertEquals(listOf("^https?://.*$"), model.supportedUrls["application/pdf"])
        assertEquals("https://proxy.openai.example/v1/responses?api-version=2026-06-03", request.url.toString())
        assertEquals("Bearer test-api-key", request.headers[HttpHeaders.Authorization])
        assertEquals("org_123", request.headers["OpenAI-Organization"])
        assertEquals("proj_123", request.headers["OpenAI-Project"])
        assertEquals("yes", request.headers["x-extra"])
        assertTrue(request.headers[HttpHeaders.UserAgent].orEmpty().contains("ai-sdk/openai/$VERSION"))
        assertEquals("gpt-5", body["model"]?.jsonPrimitive?.content)
        assertEquals("low", body["reasoning"]?.jsonObject?.get("effort")?.jsonPrimitive?.content)
        assertEquals("concise", body["reasoning"]?.jsonObject?.get("summary")?.jsonPrimitive?.content)
        assertEquals("conv_123", body["conversation"]?.jsonPrimitive?.content)
        assertEquals("continue carefully", body["instructions"]?.jsonPrimitive?.content)
        assertEquals(20, body["top_logprobs"]?.jsonPrimitive?.content?.toInt())
        assertEquals(4, body["max_tool_calls"]?.jsonPrimitive?.content?.toInt())
        assertEquals(false, body["parallel_tool_calls"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("resp_prev", body["previous_response_id"]?.jsonPrimitive?.content)
        assertEquals("cache-key", body["prompt_cache_key"]?.jsonPrimitive?.content)
        assertEquals("24h", body["prompt_cache_retention"]?.jsonPrimitive?.content)
        assertEquals("user-safe", body["safety_identifier"]?.jsonPrimitive?.content)
        assertEquals("priority", body["service_tier"]?.jsonPrimitive?.content)
        assertEquals(false, body["store"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("disabled", body["truncation"]?.jsonPrimitive?.content)
        assertEquals("user_123", body["user"]?.jsonPrimitive?.content)
        assertEquals("low", body["text"]?.jsonObject?.get("verbosity")?.jsonPrimitive?.content)
        assertEquals(false, body["text"]?.jsonObject?.get("format")?.jsonObject?.get("strict")?.jsonPrimitive?.booleanOrNull)
        val include = body["include"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue("file_search_call.results" in include)
        assertTrue("message.output_text.logprobs" in include)
        assertTrue("reasoning.encrypted_content" in include)
        assertEquals("ok", result.text)
    }

    @Test
    fun `OpenAI responses maps provider executed tools and allowed tool choice`() = runTest {
        val seenBody = mutableListOf<JsonObject>()
        val client = HttpClient(
            MockEngine { request ->
                seenBody += Json.parseToJsonElement(requestBodyText(request)).jsonObject
                respond(
                    """{"id":"resp_1","model":"gpt-5","output":[{"type":"message","id":"msg_1","role":"assistant","content":[{"type":"output_text","text":"ok"}]}]}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        val provider = createOpenAI(client, OpenAIProviderSettings(apiKey = "test-api-key"))

        provider.responses("gpt-5").generate(
            LanguageModelCallParams(
                messages = listOf(userMessage("hi")),
                tools = listOf(
                    LanguageModelTool("lookup", "Lookup.", """{"type":"object"}"""),
                    LanguageModelTool("code_interpreter", "Run code.", """{"type":"object"}""", providerExecuted = true),
                    LanguageModelTool("web_search", "Search web.", """{"type":"object"}""", providerExecuted = true),
                ),
                toolChoice = ToolChoice.Specific("web_search"),
                providerOptions = mapOf(
                    "openai" to buildJsonObject {
                        put(
                            "allowedTools",
                            buildJsonObject {
                                put("toolNames", JsonArray(listOf(JsonPrimitive("lookup"))))
                                put("mode", JsonPrimitive("required"))
                            },
                        )
                    },
                ),
            ),
        )

        val body = seenBody.single()
        val tools = body["tools"]!!.jsonArray.map { it.jsonObject }
        assertEquals("function", tools[0]["type"]?.jsonPrimitive?.content)
        assertEquals("lookup", tools[0]["name"]?.jsonPrimitive?.content)
        assertEquals("code_interpreter", tools[1]["type"]?.jsonPrimitive?.content)
        assertEquals("auto", tools[1]["container"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertEquals("web_search", tools[2]["type"]?.jsonPrimitive?.content)
        val toolChoice = body["tool_choice"]!!.jsonObject
        assertEquals("allowed_tools", toolChoice["type"]?.jsonPrimitive?.content)
        assertEquals("required", toolChoice["mode"]?.jsonPrimitive?.content)
        assertEquals("lookup", toolChoice["tools"]!!.jsonArray.single().jsonObject["name"]?.jsonPrimitive?.content)
        val include = body["include"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue("web_search_call.action.sources" in include)
        assertTrue("code_interpreter_call.outputs" in include)
    }

    @Test
    fun `OpenAI responses sends matching file ids instead of data URLs`() = runTest {
        val seenBody = mutableListOf<JsonObject>()
        val client = HttpClient(
            MockEngine { request ->
                seenBody += Json.parseToJsonElement(requestBodyText(request)).jsonObject
                respond(
                    """{"id":"resp_1","model":"gpt-4o","output":[{"type":"message","id":"msg_1","role":"assistant","content":[{"type":"output_text","text":"ok"}]}]}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        val provider = createOpenAI(client, OpenAIProviderSettings(apiKey = "test-api-key"))

        provider.responses("gpt-4o").generate(
            LanguageModelCallParams(
                messages = listOf(
                    ModelMessage(
                        MessageRole.User,
                        listOf(
                            ContentPart.Text("analyze these"),
                            ContentPart.File("image/jpeg", "file-image123"),
                            ContentPart.File("application/pdf", "file-pdf123", "paper.pdf"),
                            ContentPart.File("image/png", "aW1hZ2U="),
                        ),
                    ),
                ),
            ),
        )

        val content = seenBody.single()["input"]!!.jsonArray.single().jsonObject["content"]!!.jsonArray
        assertEquals("file-image123", content[1].jsonObject["file_id"]?.jsonPrimitive?.content)
        assertEquals("file-pdf123", content[2].jsonObject["file_id"]?.jsonPrimitive?.content)
        assertEquals("data:image/png;base64,aW1hZ2U=", content[3].jsonObject["image_url"]?.jsonPrimitive?.content)
    }

    @Test
    fun `OpenAI responses extracts response id annotations and logprobs metadata`() = runTest {
        val client = HttpClient(
            MockEngine {
                respond(
                    """
                        {
                          "id":"resp_1",
                          "created_at":1780000000,
                          "model":"gpt-4o",
                          "output":[
                            {
                              "type":"message",
                              "id":"msg_1",
                              "role":"assistant",
                              "content":[
                                {
                                  "type":"output_text",
                                  "text":"answer",
                                  "annotations":[{"type":"url_citation","url":"https://example.com","title":"Example"}],
                                  "logprobs":[{"token":"answer","logprob":-0.1,"top_logprobs":[{"token":"answer","logprob":-0.1}]}]
                                }
                              ]
                            }
                          ],
                          "usage":{"input_tokens":1,"output_tokens":1}
                        }
                    """.trimIndent(),
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        val provider = createOpenAI(client, OpenAIProviderSettings(apiKey = "test-api-key"))

        val result = provider.responses("gpt-4o").generate(LanguageModelCallParams(messages = listOf(userMessage("hi"))))

        val topLevel = result.providerMetadata["openai"]!!.jsonObject
        assertEquals("resp_1", topLevel["responseId"]?.jsonPrimitive?.content)
        assertEquals("answer", topLevel["logprobs"]!!.jsonArray.single().jsonArray.single().jsonObject["token"]?.jsonPrimitive?.content)
        val text = result.content.filterIsInstance<ContentPart.Text>().single()
        val textMetadata = text.providerMetadata!!["openai"]!!.jsonObject
        assertEquals("msg_1", textMetadata["itemId"]?.jsonPrimitive?.content)
        assertEquals("https://example.com", textMetadata["annotations"]!!.jsonArray.single().jsonObject["url"]?.jsonPrimitive?.content)
        assertEquals("answer", textMetadata["logprobs"]!!.jsonArray.single().jsonObject["token"]?.jsonPrimitive?.content)
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
