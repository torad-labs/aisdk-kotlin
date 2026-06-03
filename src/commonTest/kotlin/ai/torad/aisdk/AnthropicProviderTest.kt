package ai.torad.aisdk

import ai.torad.aisdk.testing.drainAllItems
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AnthropicProviderTest {
    @Test
    fun `messages model sends Anthropic request and maps response content`() = runTest {
        val fixture = createTestServer(
            mutableMapOf(
                "https://anthropic.test/v1/messages" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """
                            {
                              "id":"msg_1",
                              "type":"message",
                              "role":"assistant",
                              "model":"claude-sonnet-4-5",
                              "stop_reason":"tool_use",
                              "stop_sequence":null,
                              "usage":{
                                "input_tokens":10,
                                "output_tokens":5,
                                "cache_creation_input_tokens":2,
                                "cache_read_input_tokens":3,
                                "iterations":[
                                  {"type":"message","input_tokens":11,"output_tokens":6}
                                ]
                              },
                              "container":{"id":"container-1","expires_at":"2026-06-03T00:00:00Z","skills":[]},
                              "content":[
                                {
                                  "type":"thinking",
                                  "thinking":"I should look this up.",
                                  "signature":"sig-1"
                                },
                                {
                                  "type":"text",
                                  "text":"I will use a tool.",
                                  "citations":[
                                    {
                                      "type":"web_search_result_location",
                                      "url":"https://example.com",
                                      "title":"Example",
                                      "cited_text":"Example cited text",
                                      "encrypted_index":"idx"
                                    }
                                  ]
                                },
                                {
                                  "type":"tool_use",
                                  "id":"toolu_1",
                                  "name":"lookup",
                                  "input":{"city":"Paris"}
                                },
                                {
                                  "type":"server_tool_use",
                                  "id":"srv_1",
                                  "name":"web_search",
                                  "input":{"query":"Paris"}
                                }
                              ]
                            }
                            """.trimIndent(),
                        ),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val provider = createAnthropic(
            fixture.httpClient(),
            AnthropicProviderSettings(
                apiKey = "key",
                baseURL = "https://anthropic.test/v1",
                headers = mapOf("X-Provider" to "provider"),
                generateId = { "source-1" },
            ),
        )

        val pdf = convertByteArrayToBase64("pdf".encodeToByteArray())
        val textDoc = convertByteArrayToBase64("plain document".encodeToByteArray())
        val result = provider.messages("claude-sonnet-4-5").generate(
            LanguageModelCallParams(
                messages = listOf(
                    systemMessage("Follow policy."),
                    ModelMessage(
                        MessageRole.User,
                        listOf(
                            ContentPart.Text(
                                "Where is Paris?",
                                providerMetadata = mapOf(
                                    "anthropic" to buildJsonObject {
                                        put("cacheControl", buildJsonObject { put("type", JsonPrimitive("ephemeral")) })
                                    },
                                ),
                            ),
                            ContentPart.Image("image/png", "aW1hZ2U="),
                            ContentPart.File(
                                mediaType = "application/pdf",
                                base64 = pdf,
                                filename = "brief.pdf",
                                providerMetadata = mapOf(
                                    "anthropic" to buildJsonObject {
                                        put("citations", buildJsonObject { put("enabled", JsonPrimitive(true)) })
                                        put("context", JsonPrimitive("project brief"))
                                    },
                                ),
                            ),
                            ContentPart.File("text/plain", textDoc, "notes.txt"),
                        ),
                    ),
                    ModelMessage(
                        MessageRole.Assistant,
                        listOf(
                            ContentPart.Text("Previous"),
                            ContentPart.Reasoning(
                                "Prior reasoning",
                                providerMetadata = mapOf(
                                    "anthropic" to buildJsonObject { put("signature", JsonPrimitive("sig-old")) },
                                ),
                            ),
                            ContentPart.ToolCall(
                                toolCallId = "toolu_old",
                                toolName = "lookup",
                                input = buildJsonObject { put("city", JsonPrimitive("Paris")) },
                            ),
                        ),
                    ),
                    ModelMessage(
                        MessageRole.Tool,
                        listOf(ContentPart.ToolResult("toolu_old", "lookup", JsonPrimitive("France"))),
                    ),
                ),
                tools = listOf(
                    LanguageModelTool("lookup", "Lookup a city.", objectSchema("city").toString(), strict = false),
                    LanguageModelTool("web_search", "Anthropic web search.", """{"type":"object"}""", providerExecuted = true),
                ),
                toolChoice = ToolChoice.Specific("lookup"),
                maxOutputTokens = 1000,
                temperature = 1.4f,
                topP = 0.9f,
                topK = 5,
                frequencyPenalty = 0.1f,
                presencePenalty = 0.2f,
                seed = 7,
                responseFormat = ResponseFormat.Json(schemaJson = objectSchema("answer")),
                providerOptions = mapOf(
                    "anthropic" to buildJsonObject {
                        put("thinking", buildJsonObject {
                            put("type", JsonPrimitive("enabled"))
                            put("budgetTokens", JsonPrimitive(1024))
                        })
                        put("disableParallelToolUse", JsonPrimitive(true))
                        put("metadata", buildJsonObject {
                            put("userId", JsonPrimitive("user-1"))
                        })
                        put(
                            "mcpServers",
                            JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("type", JsonPrimitive("url"))
                                        put("name", JsonPrimitive("tools"))
                                        put("url", JsonPrimitive("https://mcp.test"))
                                        put("authorizationToken", JsonPrimitive("token"))
                                        put(
                                            "toolConfiguration",
                                            buildJsonObject {
                                                put("enabled", JsonPrimitive(true))
                                                put("allowedTools", JsonArray(listOf(JsonPrimitive("lookup"))))
                                            },
                                        )
                                    },
                                ),
                            ),
                        )
                        put("container", buildJsonObject {
                            put("id", JsonPrimitive("container-0"))
                            put(
                                "skills",
                                JsonArray(
                                    listOf(
                                        buildJsonObject {
                                            put("type", JsonPrimitive("anthropic"))
                                            put("skillId", JsonPrimitive("pptx"))
                                            put("version", JsonPrimitive("latest"))
                                        },
                                    ),
                                ),
                            )
                        })
                        put("anthropicBeta", JsonArray(listOf(JsonPrimitive("custom-beta"))))
                    },
                ),
                headers = mapOf("X-Request" to "request"),
            ),
        )

        assertEquals("anthropic.messages", provider("claude-sonnet-4-5").provider)
        assertEquals("I will use a tool.", result.text)
        assertEquals(FinishReason.ToolCalls, result.finishReason)
        assertEquals(16, result.usage.promptTokens)
        assertEquals(11, result.usage.inputTokens.noCache)
        assertEquals(3, result.usage.inputTokens.cacheRead)
        assertEquals(2, result.usage.inputTokens.cacheWrite)
        assertEquals(6, result.usage.completionTokens)
        assertEquals("sig-1", result.content.filterIsInstance<ContentPart.Reasoning>().single().providerMetadata?.get("anthropic")?.jsonObject?.get("signature")?.jsonPrimitive?.contentOrNull)
        assertEquals("Example", result.content.filterIsInstance<ContentPart.Source>().single().title)
        assertEquals(listOf("lookup", "web_search"), result.toolCalls.map { it.toolName })
        assertEquals(true, result.toolCalls[1].providerMetadata?.get("anthropic")?.jsonObject?.get("providerExecuted")?.jsonPrimitive?.booleanOrNull)
        assertEquals("container-1", result.providerMetadata["anthropic"]?.jsonObject?.get("container")?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull)
        assertEquals(setOf("frequencyPenalty", "presencePenalty", "seed", "temperature", "topP", "topK"), result.warnings.mapNotNull { it.message }.toSet())

        val request = fixture.calls.single()
        assertEquals("POST", request.requestMethod)
        assertEquals("https://anthropic.test/v1/messages", request.requestUrl)
        assertEquals("key", request.requestHeaders.headerValue("x-api-key"))
        assertEquals("2023-06-01", request.requestHeaders.headerValue("anthropic-version"))
        assertEquals("provider", request.requestHeaders.headerValue("X-Provider"))
        assertEquals("request", request.requestHeaders.headerValue("X-Request"))
        assertTrue(request.requestUserAgent.orEmpty().contains("ai-sdk/anthropic/$ANTHROPIC_VERSION"))
        val betaHeader = request.requestHeaders.headerValue("anthropic-beta").orEmpty()
        assertTrue(betaHeader.contains("pdfs-2024-09-25"))
        assertTrue(betaHeader.contains("mcp-client-2025-04-04"))
        assertTrue(betaHeader.contains("skills-2025-10-02"))
        assertTrue(betaHeader.contains("custom-beta"))

        val body = request.requestBodyJson.jsonObject
        assertEquals("claude-sonnet-4-5", body["model"]?.jsonPrimitive?.contentOrNull)
        assertEquals(2024, body["max_tokens"]?.jsonPrimitive?.intOrNull)
        assertEquals("enabled", body["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.contentOrNull)
        assertEquals(1024, body["thinking"]?.jsonObject?.get("budget_tokens")?.jsonPrimitive?.intOrNull)
        assertTrue("temperature" !in body)
        assertTrue("top_p" !in body)
        assertTrue("top_k" !in body)
        assertEquals("user-1", body["metadata"]?.jsonObject?.get("user_id")?.jsonPrimitive?.contentOrNull)
        assertEquals("json_schema", body["output_config"]?.jsonObject?.get("format")?.jsonObject?.get("type")?.jsonPrimitive?.contentOrNull)
        assertEquals("token", body["mcp_servers"]?.jsonArray?.single()?.jsonObject?.get("authorization_token")?.jsonPrimitive?.contentOrNull)
        assertEquals("pptx", body["container"]?.jsonObject?.get("skills")?.jsonArray?.single()?.jsonObject?.get("skill_id")?.jsonPrimitive?.contentOrNull)
        assertEquals("lookup", body["tool_choice"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull)
        assertEquals(true, body["tool_choice"]?.jsonObject?.get("disable_parallel_tool_use")?.jsonPrimitive?.booleanOrNull)
        assertEquals(false, body["tools"]?.jsonArray?.first()?.jsonObject?.get("strict")?.jsonPrimitive?.booleanOrNull)
        assertEquals("web_search_20260209", body["tools"]?.jsonArray?.last()?.jsonObject?.get("type")?.jsonPrimitive?.contentOrNull)
        assertEquals("Follow policy.", body["system"]?.jsonArray?.single()?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull)
        val userContent = body["messages"]?.jsonArray?.first()?.jsonObject?.get("content")?.jsonArray.orEmpty()
        assertEquals("text", userContent[0].jsonObject["type"]?.jsonPrimitive?.contentOrNull)
        assertEquals("ephemeral", userContent[0].jsonObject["cache_control"]?.jsonObject?.get("type")?.jsonPrimitive?.contentOrNull)
        assertEquals("image", userContent[1].jsonObject["type"]?.jsonPrimitive?.contentOrNull)
        assertEquals("document", userContent[2].jsonObject["type"]?.jsonPrimitive?.contentOrNull)
        assertEquals("brief.pdf", userContent[2].jsonObject["title"]?.jsonPrimitive?.contentOrNull)
        assertEquals("plain document", userContent[3].jsonObject["source"]?.jsonObject?.get("data")?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `stream maps Anthropic SSE text reasoning tool call and finish`() = runTest {
        val fixture = createTestServer(
            mutableMapOf(
                "https://anthropic.test/v1/messages" to UrlHandler(
                    UrlResponse.StreamChunks(
                        listOf(
                            """
                            data: {"type":"message_start","message":{"id":"msg_stream","model":"claude-sonnet-4-5","usage":{"input_tokens":1,"output_tokens":0}}}

                            data: {"type":"content_block_start","index":0,"content_block":{"type":"thinking","id":"think-1"}}

                            data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"think"}}

                            data: {"type":"content_block_stop","index":0}

                            data: {"type":"content_block_start","index":1,"content_block":{"type":"text","id":"text-1"}}

                            data: {"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"hello"}}

                            data: {"type":"content_block_stop","index":1}

                            data: {"type":"content_block_start","index":2,"content_block":{"type":"tool_use","id":"toolu_1","name":"lookup","input":{}}}

                            data: {"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"{\"city\":\"Paris\"}"}}

                            data: {"type":"content_block_stop","index":2}

                            data: {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"input_tokens":1,"output_tokens":2}}

                            data: {"type":"message_stop"}

                            """.trimIndent(),
                        ),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val provider = createAnthropic(fixture.httpClient(), AnthropicProviderSettings(baseURL = "https://anthropic.test/v1"))

        val events = drainAllItems(
            provider.messages("claude-sonnet-4-5").stream(
                LanguageModelCallParams(messages = listOf(userMessage("hi"))),
            ),
        )

        assertIs<StreamEvent.StreamStart>(events.first())
        assertTrue(events.any { it is StreamEvent.ResponseMetadata && it.id == "msg_stream" })
        assertTrue(events.any { it is StreamEvent.ReasoningDelta && it.text == "think" })
        assertTrue(events.any { it is StreamEvent.TextDelta && it.text == "hello" })
        val toolCall = events.filterIsInstance<StreamEvent.ToolCall>().single()
        assertEquals("lookup", toolCall.toolName)
        assertEquals("Paris", toolCall.inputJson.jsonObject["city"]?.jsonPrimitive?.contentOrNull)
        val finish = events.filterIsInstance<StreamEvent.Finish>().single()
        assertEquals(FinishReason.ToolCalls, finish.finishReason)
        assertEquals(1, finish.usage.promptTokens)
        assertEquals(2, finish.usage.completionTokens)
        assertEquals(true, fixture.calls.single().requestBodyJson.jsonObject["stream"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("text/event-stream", fixture.calls.single().requestHeaders.headerValue(HttpHeaders.Accept))
    }

    @Test
    fun `auth conflict hosted tools and container forwarding are exposed`() {
        val fixture = createTestServer(mutableMapOf())
        val provider = createAnthropic(
            fixture.httpClient(),
            AnthropicProviderSettings(authToken = "token"),
        )

        val tool = provider.tools.webSearch_20260209
        assertEquals("web_search", tool.name)
        assertEquals(true, tool.providerExecuted)
        assertEquals("anthropic.web_search_20260209", tool.metadata["providerToolId"]?.jsonPrimitive?.contentOrNull)
        assertFailsWith<InvalidArgumentError> {
            createAnthropic(
                fixture.httpClient(),
                AnthropicProviderSettings(apiKey = "key", authToken = "token"),
            )
        }
        assertFailsWith<NoSuchModelError> { provider.embeddingModel("embed") }
        assertFailsWith<NoSuchModelError> { provider.textEmbeddingModel("embed") }
        assertFailsWith<NoSuchModelError> { provider.imageModel("image") }
        assertFailsWith<AiSdkException> { anthropic.messages("model") }

        val forwarded = forwardAnthropicContainerIdFromLastStep(
            listOf(
                mapOf("anthropic" to buildJsonObject {}),
                mapOf(
                    "anthropic" to buildJsonObject {
                        put("container", buildJsonObject { put("id", JsonPrimitive("container-next")) })
                    },
                ),
            ),
        )
        assertEquals("container-next", forwarded?.get("anthropic")?.jsonObject?.get("container")?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull)
    }

    private fun objectSchema(vararg required: String): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put(
            "properties",
            buildJsonObject {
                required.forEach { name ->
                    put(name, buildJsonObject { put("type", JsonPrimitive("string")) })
                }
            },
        )
        put("required", JsonArray(required.map(::JsonPrimitive)))
        put("additionalProperties", JsonPrimitive(false))
    }

    private fun Map<String, String>.headerValue(name: String): String? =
        entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}
