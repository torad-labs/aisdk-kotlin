@file:OptIn(LowLevelLanguageModelApi::class)

package ai.torad.aisdk
import ai.torad.aisdk.providers.ANTHROPIC_VERSION
import ai.torad.aisdk.providers.AnthropicProviderSettings
import ai.torad.aisdk.providers.AnthropicMessagesLanguageModel.Companion.forwardAnthropicContainerIdFromLastStep

import ai.torad.aisdk.testing.FlowDrain.drainAllItems
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import ai.torad.aisdk.providers.Anthropic

class AnthropicProviderTest {
    @Test
    fun `messages model sends Anthropic request and maps response content`() = runTest {
        val fixture = TestServer.createTestServer(
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
        val provider = Anthropic(
            fixture.httpClient(),
            AnthropicProviderSettings(
                apiKey = "key",
                baseURL = "https://anthropic.test/v1",
                headers = mapOf("X-Provider" to "provider"),
                generateId = { "source-1" },
            ),
        )

        val pdf = Base64Codec.encode("pdf".encodeToByteArray())
        val textDoc = Base64Codec.encode("plain document".encodeToByteArray())
        val result = provider.messages(ModelId("claude-sonnet-4-5")).generate(
            LanguageModelCallParams(
                messages = listOf(
                    SystemMessage("Follow policy."),
                    ModelMessage(
                        MessageRole.User,
                        listOf(
                            ContentPart.Text(
                                "Where is Paris?",
                                providerMetadata = ProviderMetadata.Raw(JsonObject(mapOf(
                                    "anthropic" to buildJsonObject {
                                        put("cacheControl", buildJsonObject { put("type", JsonPrimitive("ephemeral")) })
                                    },
                                ))),
                            ),
                            ContentPart.Image(
                                "image/png",
                                "aW1hZ2U=",
                                providerMetadata = ProviderMetadata.Raw(JsonObject(mapOf(
                                    "anthropic" to buildJsonObject {
                                        put("cache_control", buildJsonObject { put("type", JsonPrimitive("ephemeral")) })
                                    },
                                ))),
                            ),
                            ContentPart.File(
                                mediaType = "application/pdf",
                                base64 = pdf,
                                filename = "brief.pdf",
                                providerMetadata = ProviderMetadata.Raw(JsonObject(mapOf(
                                    "anthropic" to buildJsonObject {
                                        put("citations", buildJsonObject { put("enabled", JsonPrimitive(true)) })
                                        put("context", JsonPrimitive("project brief"))
                                        put("cacheControl", buildJsonObject { put("type", JsonPrimitive("ephemeral")) })
                                    },
                                ))),
                            ),
                            ContentPart.File("text/plain", textDoc, "notes.txt"),
                        ),
                    ),
                    ModelMessage(
                        MessageRole.Assistant,
                        listOf(
                            ContentPart.Text(
                                "Previous",
                                providerMetadata = ProviderMetadata.Raw(JsonObject(mapOf(
                                    "anthropic" to buildJsonObject {
                                        put("cacheControl", buildJsonObject { put("type", JsonPrimitive("ephemeral")) })
                                    },
                                ))),
                            ),
                            ContentPart.Reasoning(
                                "Prior reasoning",
                                providerMetadata = ProviderMetadata.Raw(JsonObject(mapOf(
                                    "anthropic" to buildJsonObject { put("signature", JsonPrimitive("sig-old")) },
                                ))),
                            ),
                            ContentPart.ToolCall(
                                toolCallId = "toolu_old",
                                toolName = "lookup",
                                input = buildJsonObject { put("city", JsonPrimitive("Paris")) },
                                providerMetadata = ProviderMetadata.Raw(JsonObject(mapOf(
                                    "anthropic" to buildJsonObject {
                                        put("cacheControl", buildJsonObject { put("type", JsonPrimitive("ephemeral")) })
                                    },
                                ))),
                            ),
                        ),
                    ),
                    ModelMessage(
                        MessageRole.Tool,
                        listOf(ContentPart.ToolResult(
                            "toolu_old",
                            "lookup",
                            JsonPrimitive("France"),
                            providerMetadata = ProviderMetadata.Raw(JsonObject(mapOf(
                                "anthropic" to buildJsonObject {
                                    put("cacheControl", buildJsonObject { put("type", JsonPrimitive("ephemeral")) })
                                },
                            ))),
                        )),
                    ),
                ),
                tools = listOf(
                    LanguageModelTool(
                        "lookup",
                        "Lookup a city.",
                        objectSchema("city").toString(),
                        strict = false,
                        providerOptions = ProviderOptions.Raw(JsonObject(mapOf(
                            "anthropic" to buildJsonObject {
                                put("cache_control", buildJsonObject { put("type", JsonPrimitive("ephemeral")) })
                            },
                        ))),
                    ),
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
                providerOptions = ProviderOptions.Raw(JsonObject(mapOf(
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
                ))),
                headers = mapOf("X-Request" to "request"),
            ),
        )

        assertEquals("anthropic.messages", provider(ModelId("claude-sonnet-4-5")).provider)
        assertEquals("I will use a tool.", result.text)
        assertEquals(FinishReason.ToolCalls, result.finishReason)
        assertEquals(16, result.usage.promptTokens)
        assertEquals(11, result.usage.inputTokens.noCache)
        assertEquals(3, result.usage.inputTokens.cacheRead)
        assertEquals(2, result.usage.inputTokens.cacheWrite)
        assertEquals(6, result.usage.completionTokens)
        assertEquals("sig-1", result.content.filterIsInstance<ContentPart.Reasoning>().single().providerMetadata.toMap()["anthropic"]?.jsonObject?.get("signature")?.jsonPrimitive?.contentOrNull)
        assertEquals("Example", result.content.filterIsInstance<ContentPart.Source>().single().title)
        assertEquals(listOf("lookup", "web_search"), result.toolCalls.map { it.toolName })
        assertEquals(true, result.toolCalls[1].providerMetadata.toMap()["anthropic"]?.jsonObject?.get("providerExecuted")?.jsonPrimitive?.booleanOrNull)
        assertEquals("container-1", result.providerMetadata.toMap()["anthropic"]?.jsonObject?.get("container")?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull)
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
        assertEquals("ephemeral", userContent[1].jsonObject["cache_control"]?.jsonObject?.get("type")?.jsonPrimitive?.contentOrNull)
        assertEquals("document", userContent[2].jsonObject["type"]?.jsonPrimitive?.contentOrNull)
        assertEquals("brief.pdf", userContent[2].jsonObject["title"]?.jsonPrimitive?.contentOrNull)
        assertEquals("ephemeral", userContent[2].jsonObject["cache_control"]?.jsonObject?.get("type")?.jsonPrimitive?.contentOrNull)
        assertEquals("plain document", userContent[3].jsonObject["source"]?.jsonObject?.get("data")?.jsonPrimitive?.contentOrNull)
        val assistantContent = body["messages"]?.jsonArray?.get(1)?.jsonObject?.get("content")?.jsonArray.orEmpty()
        assertEquals("text", assistantContent[0].jsonObject["type"]?.jsonPrimitive?.contentOrNull)
        assertEquals("ephemeral", assistantContent[0].jsonObject["cache_control"]?.jsonObject?.get("type")?.jsonPrimitive?.contentOrNull)
        assertEquals("tool_use", assistantContent[2].jsonObject["type"]?.jsonPrimitive?.contentOrNull)
        assertEquals("ephemeral", assistantContent[2].jsonObject["cache_control"]?.jsonObject?.get("type")?.jsonPrimitive?.contentOrNull)
        val toolResultContent = body["messages"]?.jsonArray?.get(2)?.jsonObject?.get("content")?.jsonArray.orEmpty()
        assertEquals("tool_result", toolResultContent.single().jsonObject["type"]?.jsonPrimitive?.contentOrNull)
        assertEquals("ephemeral", toolResultContent.single().jsonObject["cache_control"]?.jsonObject?.get("type")?.jsonPrimitive?.contentOrNull)
        assertEquals("ephemeral", body["tools"]?.jsonArray?.first()?.jsonObject?.get("cache_control")?.jsonObject?.get("type")?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `messages model rejects tool use missing id`() = runTest {
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://anthropic.test/v1/messages" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """
                            {
                              "id":"msg_missing_tool_id",
                              "type":"message",
                              "role":"assistant",
                              "model":"claude-sonnet-4-5",
                              "stop_reason":"tool_use",
                              "usage":{"input_tokens":1,"output_tokens":1},
                              "content":[{"type":"tool_use","name":"lookup","input":{}}]
                            }
                            """.trimIndent(),
                        ),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val provider = Anthropic(fixture.httpClient(), AnthropicProviderSettings(baseURL = "https://anthropic.test/v1"))

        val error = assertFailsWith<WireDecodeException> {
            provider.messages(ModelId("claude-sonnet-4-5")).generate(LanguageModelCallParams(messages = listOf(UserMessage("hi"))))
        }

        val message = error.message.orEmpty()
        assertTrue(message.contains("anthropic"), message)
        assertTrue(message.contains("response content"), message)
        assertTrue(message.contains("id"), message)
    }

    @Test
    fun `messages model rejects tool use missing name`() = runTest {
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://anthropic.test/v1/messages" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """
                            {
                              "id":"msg_missing_tool_name",
                              "type":"message",
                              "role":"assistant",
                              "model":"claude-sonnet-4-5",
                              "stop_reason":"tool_use",
                              "usage":{"input_tokens":1,"output_tokens":1},
                              "content":[{"type":"tool_use","id":"toolu_1","input":{}}]
                            }
                            """.trimIndent(),
                        ),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val provider = Anthropic(fixture.httpClient(), AnthropicProviderSettings(baseURL = "https://anthropic.test/v1"))

        val error = assertFailsWith<WireDecodeException> {
            provider.messages(ModelId("claude-sonnet-4-5")).generate(LanguageModelCallParams(messages = listOf(UserMessage("hi"))))
        }

        val message = error.message.orEmpty()
        assertTrue(message.contains("anthropic"), message)
        assertTrue(message.contains("response content"), message)
        assertTrue(message.contains("name"), message)
    }

    @Test
    fun `messages model rejects provider tool result missing tool use id`() = runTest {
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://anthropic.test/v1/messages" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """
                            {
                              "id":"msg_missing_result_id",
                              "type":"message",
                              "role":"assistant",
                              "model":"claude-sonnet-4-5",
                              "stop_reason":"end_turn",
                              "usage":{"input_tokens":1,"output_tokens":1},
                              "content":[{"type":"web_search_tool_result","name":"web_search","content":{"type":"web_search_result"}}]
                            }
                            """.trimIndent(),
                        ),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val provider = Anthropic(fixture.httpClient(), AnthropicProviderSettings(baseURL = "https://anthropic.test/v1"))

        val error = assertFailsWith<WireDecodeException> {
            provider.messages(ModelId("claude-sonnet-4-5")).generate(LanguageModelCallParams(messages = listOf(UserMessage("hi"))))
        }

        val message = error.message.orEmpty()
        assertTrue(message.contains("anthropic"), message)
        assertTrue(message.contains("response content"), message)
        assertTrue(message.contains("tool_use_id"), message)
        assertTrue(message.contains("id"), message)
    }

    @Test
    fun `messages model rejects provider tool result missing name`() = runTest {
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://anthropic.test/v1/messages" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """
                            {
                              "id":"msg_missing_result_name",
                              "type":"message",
                              "role":"assistant",
                              "model":"claude-sonnet-4-5",
                              "stop_reason":"end_turn",
                              "usage":{"input_tokens":1,"output_tokens":1},
                              "content":[{"type":"web_search_tool_result","tool_use_id":"srv_1","content":{"type":"web_search_result"}}]
                            }
                            """.trimIndent(),
                        ),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val provider = Anthropic(fixture.httpClient(), AnthropicProviderSettings(baseURL = "https://anthropic.test/v1"))

        val error = assertFailsWith<WireDecodeException> {
            provider.messages(ModelId("claude-sonnet-4-5")).generate(LanguageModelCallParams(messages = listOf(UserMessage("hi"))))
        }

        val message = error.message.orEmpty()
        assertTrue(message.contains("anthropic"), message)
        assertTrue(message.contains("response content"), message)
        assertTrue(message.contains("name"), message)
    }

    @Test
    fun `stream surfaces malformed content block delta as wire error event`() = runTest {
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://anthropic.test/v1/messages" to UrlHandler(
                    UrlResponse.StreamChunks(
                        listOf(
                            """data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"hi"}}""",
                        ),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val provider = Anthropic(
            fixture.httpClient(),
            AnthropicProviderSettings(apiKey = "key", baseURL = "https://anthropic.test/v1"),
        )

        val events = drainAllItems(
            provider.messages(ModelId("claude-sonnet-4-5")).stream(LanguageModelCallParams(messages = listOf(UserMessage("hi")))),
        )

        val error = events.filterIsInstance<StreamEvent.Error>().single()
        assertTrue(error.message.contains("$.index"))
    }

    @Test
    fun `stream leading Anthropic overloaded error throws retryable API call error before emitting events`() = runTest {
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://anthropic.test/v1/messages" to UrlHandler(
                    UrlResponse.StreamChunks(
                        listOf(
                            """data: {"type":"error","error":{"type":"overloaded_error"}}""",
                        ),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val provider = Anthropic(fixture.httpClient(), AnthropicProviderSettings(baseURL = "https://anthropic.test/v1"))
        val emitted = mutableListOf<StreamEvent>()

        val error = assertFailsWith<APICallError> {
            provider.messages(ModelId("claude-sonnet-4-5"))
                .stream(LanguageModelCallParams(messages = listOf(UserMessage("hi"))))
                .collect { emitted += it }
        }

        assertEquals(529, error.statusCode)
        assertEquals(true, error.isRetryable)
        assertTrue(error.message?.contains("overloaded_error") == true)
        assertTrue(emitted.isEmpty(), "retryable leading error must throw before StreamStart or metadata is emitted")
    }

    @Test
    fun `stream mid Anthropic error stays a terminal stream error after text delta`() = runTest {
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://anthropic.test/v1/messages" to UrlHandler(
                    UrlResponse.StreamChunks(
                        listOf(
                            """
                            data: {"type":"message_start","message":{"id":"msg_stream","model":"claude-sonnet-4-5","usage":{"input_tokens":1,"output_tokens":0}}}

                            data: {"type":"content_block_start","index":0,"content_block":{"type":"text","id":"text-1"}}

                            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"hello"}}

                            data: {"type":"error","error":{"type":"server_error","message":"after text"}}

                            """.trimIndent(),
                        ),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val provider = Anthropic(fixture.httpClient(), AnthropicProviderSettings(baseURL = "https://anthropic.test/v1"))

        val events = drainAllItems(
            provider.messages(ModelId("claude-sonnet-4-5")).stream(
                LanguageModelCallParams(messages = listOf(UserMessage("hi"))),
            ),
        )

        val textIndex = events.indexOfFirst { it is StreamEvent.TextDelta && it.text == "hello" }
        val errorIndex = events.indexOfFirst { it is StreamEvent.Error }
        assertTrue(textIndex >= 0, events.toString())
        assertTrue(errorIndex > textIndex, events.toString())
        val error = events.filterIsInstance<StreamEvent.Error>().single()
        assertEquals("after text", error.message)
    }

    @Test
    fun `stream maps Anthropic SSE text reasoning tool call and finish`() = runTest {
        val fixture = TestServer.createTestServer(
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
        val provider = Anthropic(fixture.httpClient(), AnthropicProviderSettings(baseURL = "https://anthropic.test/v1"))

        val events = drainAllItems(
            provider.messages(ModelId("claude-sonnet-4-5")).stream(
                LanguageModelCallParams(messages = listOf(UserMessage("hi"))),
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
    fun `stream rejects tool block missing id or name`() = runTest {
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://anthropic.test/v1/messages" to UrlHandler(
                    UrlResponse.StreamChunks(
                        listOf(
                            """
                            data: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","name":"lookup","input":{}}}

                            data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_1","input":{}}}

                            data: {"type":"message_stop"}

                            """.trimIndent(),
                        ),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val provider = Anthropic(fixture.httpClient(), AnthropicProviderSettings(baseURL = "https://anthropic.test/v1"))

        val events = drainAllItems(provider.messages(ModelId("claude-sonnet-4-5")).stream(LanguageModelCallParams(messages = listOf(UserMessage("hi")))))

        val errors = events.filterIsInstance<StreamEvent.Error>()
        assertEquals(2, errors.size)
        assertTrue(errors.any { it.message.contains("id") }, errors.toString())
        assertTrue(errors.any { it.message.contains("name") }, errors.toString())
        assertTrue(events.none { it is StreamEvent.ToolCall })
    }

    @Test
    fun `stream usage merges message_delta onto message_start preserving input tokens`() = runTest {
        // The real-world case: message_delta carries ONLY output_tokens. The final usage
        // must keep the input_tokens captured at message_start (was collapsing to 0).
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://anthropic.test/v1/messages" to UrlHandler(
                    UrlResponse.StreamChunks(
                        listOf(
                            """
                            data: {"type":"message_start","message":{"id":"m","model":"claude-sonnet-4-5","usage":{"input_tokens":42,"output_tokens":0}}}

                            data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":7}}

                            data: {"type":"message_stop"}

                            """.trimIndent(),
                        ),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val provider = Anthropic(fixture.httpClient(), AnthropicProviderSettings(baseURL = "https://anthropic.test/v1"))
        val events = drainAllItems(
            provider.messages(ModelId("claude-sonnet-4-5")).stream(
                LanguageModelCallParams(messages = listOf(UserMessage("hi"))),
            ),
        )
        val finish = events.filterIsInstance<StreamEvent.Finish>().single()
        assertEquals(42, finish.usage.promptTokens, "input tokens preserved from message_start")
        assertEquals(7, finish.usage.completionTokens, "output tokens updated from message_delta")
    }

    @Test
    fun `max_tokens defaults to the per-model limit when caller omits maxOutputTokens`() = runTest {
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://anthropic.test/v1/messages" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """{"id":"m","model":"claude-opus-4-8","stop_reason":"end_turn",
                               "content":[{"type":"text","text":"hi"}],
                               "usage":{"input_tokens":1,"output_tokens":1}}""",
                        ),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val provider = Anthropic(fixture.httpClient(), AnthropicProviderSettings(baseURL = "https://anthropic.test/v1"))
        provider.messages(ModelId("claude-opus-4-8")).generate(
            LanguageModelCallParams(messages = listOf(UserMessage("hi"))),
        )
        val body = fixture.calls.single().requestBodyJson.jsonObject
        // claude-opus-4-8 → 128000, not the old hardcoded 4096.
        assertEquals(128_000, body["max_tokens"]?.jsonPrimitive?.intOrNull)
    }

    @Test
    fun `thinking max_tokens clamps to model ceiling and warns only for explicit maxOutputTokens`() = runTest {
        val response = Json.parseToJsonElement(
            """{"id":"m","model":"claude-sonnet-4-5","stop_reason":"end_turn",
               "content":[{"type":"text","text":"ok"}],
               "usage":{"input_tokens":1,"output_tokens":1}}""",
        )
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://anthropic.test/v1/messages" to UrlHandler { UrlResponse.JsonValue(response) },
            ),
        )
        fixture.server.start()
        val provider = Anthropic(fixture.httpClient(), AnthropicProviderSettings(baseURL = "https://anthropic.test/v1"))
        val thinkingOptions = ProviderOptions.Raw(JsonObject(mapOf(
            "anthropic" to buildJsonObject {
                put(
                    "thinking",
                    buildJsonObject {
                        put("type", JsonPrimitive("enabled"))
                        put("budgetTokens", JsonPrimitive(10_000))
                    },
                )
            },
        )))

        val implicit = provider.messages(ModelId("claude-sonnet-4-5")).generate(
            LanguageModelCallParams(
                messages = listOf(UserMessage("hi")),
                providerOptions = thinkingOptions,
            ),
        )
        val explicit = provider.messages(ModelId("claude-sonnet-4-5")).generate(
            LanguageModelCallParams(
                messages = listOf(UserMessage("hi")),
                maxOutputTokens = 60_000,
                providerOptions = thinkingOptions,
            ),
        )
        val unknown = provider.messages(ModelId("claude-fictional-9")).generate(
            LanguageModelCallParams(
                messages = listOf(UserMessage("hi")),
                providerOptions = thinkingOptions,
            ),
        )

        assertEquals(64_000, fixture.calls[0].requestBodyJson.jsonObject["max_tokens"]?.jsonPrimitive?.intOrNull)
        assertTrue(implicit.warnings.none { it.message == "maxOutputTokens" })

        assertEquals(64_000, fixture.calls[1].requestBodyJson.jsonObject["max_tokens"]?.jsonPrimitive?.intOrNull)
        val warning = explicit.warnings.single { it.message == "maxOutputTokens" }
        assertEquals("unsupported", warning.type)
        assertEquals(
            "70000 (maxOutputTokens + thinkingBudget) is greater than claude-sonnet-4-5 64000 max output tokens. " +
                "The max output tokens have been limited to 64000.",
            warning.details?.jsonPrimitive?.contentOrNull,
        )

        assertEquals(14_096, fixture.calls[2].requestBodyJson.jsonObject["max_tokens"]?.jsonPrimitive?.intOrNull)
        assertTrue(unknown.warnings.none { it.message == "maxOutputTokens" })
    }

    @Test
    fun `models that reject sampling parameters omit them and warn while other models forward them`() = runTest {
        val response = Json.parseToJsonElement(
            """{"id":"m","model":"claude","stop_reason":"end_turn",
               "content":[{"type":"text","text":"ok"}],
               "usage":{"input_tokens":1,"output_tokens":1}}""",
        )
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://anthropic.test/v1/messages" to UrlHandler {
                    UrlResponse.JsonValue(response)
                },
            ),
        )
        fixture.server.start()
        val provider = Anthropic(fixture.httpClient(), AnthropicProviderSettings(baseURL = "https://anthropic.test/v1"))
        val rejectedModels = listOf("claude-opus-4-8", "claude-opus-4-7", "claude-fable-5")
        val samplingFeatures = setOf("temperature", "topK", "topP")

        val rejectedResults = rejectedModels.map { model ->
            provider.messages(ModelId(model)).generate(
                LanguageModelCallParams(
                    messages = listOf(UserMessage("hi")),
                    temperature = 0.5f,
                    topP = 0.8f,
                    topK = 42,
                ),
            )
        }
        val sonnetTopPResult = provider.messages(ModelId("claude-sonnet-4-5")).generate(
            LanguageModelCallParams(messages = listOf(UserMessage("hi")), topP = 0.8f, topK = 42),
        )
        val sonnetTemperatureResult = provider.messages(ModelId("claude-sonnet-4-5")).generate(
            LanguageModelCallParams(messages = listOf(UserMessage("hi")), temperature = 0.5f, topK = 42),
        )

        rejectedModels.forEachIndexed { index, model ->
            val body = fixture.calls[index].requestBodyJson.jsonObject
            assertEquals(model, body["model"]?.jsonPrimitive?.contentOrNull)
            assertTrue("temperature" !in body)
            assertTrue("top_p" !in body)
            assertTrue("top_k" !in body)
            val warnings = rejectedResults[index].warnings.filter { it.message in samplingFeatures }
            assertEquals(listOf("temperature", "topK", "topP"), warnings.map { it.message })
            assertEquals(
                listOf(
                    "temperature is not supported by $model and will be ignored",
                    "topK is not supported by $model and will be ignored",
                    "topP is not supported by $model and will be ignored",
                ),
                warnings.map { it.details?.jsonPrimitive?.contentOrNull },
            )
        }

        val sonnetTopPBody = fixture.calls[rejectedModels.size].requestBodyJson.jsonObject
        assertEquals("claude-sonnet-4-5", sonnetTopPBody["model"]?.jsonPrimitive?.contentOrNull)
        assertEquals(0.8f, sonnetTopPBody["top_p"]?.jsonPrimitive?.floatOrNull)
        assertEquals(42, sonnetTopPBody["top_k"]?.jsonPrimitive?.intOrNull)
        assertTrue(sonnetTopPResult.warnings.none { it.message in samplingFeatures })

        val sonnetTemperatureBody = fixture.calls[rejectedModels.size + 1].requestBodyJson.jsonObject
        assertEquals("claude-sonnet-4-5", sonnetTemperatureBody["model"]?.jsonPrimitive?.contentOrNull)
        assertEquals(0.5f, sonnetTemperatureBody["temperature"]?.jsonPrimitive?.floatOrNull)
        assertEquals(42, sonnetTemperatureBody["top_k"]?.jsonPrimitive?.intOrNull)
        assertTrue(sonnetTemperatureResult.warnings.none { it.message in samplingFeatures })
    }

    @Test
    fun `final assistant text is trimmed of trailing whitespace in a pre-fill`() = runTest {
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://anthropic.test/v1/messages" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """{"id":"m","model":"claude-sonnet-4-5","stop_reason":"end_turn",
                               "content":[{"type":"text","text":"ok"}],"usage":{"input_tokens":1,"output_tokens":1}}""",
                        ),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val provider = Anthropic(fixture.httpClient(), AnthropicProviderSettings(baseURL = "https://anthropic.test/v1"))
        // The last message is a pre-filled assistant turn with trailing whitespace.
        provider.messages(ModelId("claude-sonnet-4-5")).generate(
            LanguageModelCallParams(messages = listOf(UserMessage("hi"), AssistantMessage("The answer is  \n  "))),
        )
        val body = fixture.calls.single().requestBodyJson.jsonObject
        val assistantText = body["messages"]?.jsonArray?.last()?.jsonObject
            ?.get("content")?.jsonArray?.last()?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
        assertEquals("The answer is", assistantText, "trailing whitespace trimmed on the pre-fill")
    }

    @Test
    fun `stream surfaces Anthropic deltas for unknown content blocks as errors`() = runTest {
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://anthropic.test/v1/messages" to UrlHandler(
                    UrlResponse.StreamChunks(
                        listOf(
                            """
                            data: {"type":"content_block_delta","index":4,"delta":{"type":"text_delta","text":"lost"}}

                            data: {"type":"message_stop"}

                            """.trimIndent(),
                        ),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val provider = Anthropic(fixture.httpClient(), AnthropicProviderSettings(baseURL = "https://anthropic.test/v1"))

        val events = drainAllItems(provider.messages(ModelId("claude-sonnet-4-5")).stream(LanguageModelCallParams(messages = listOf(UserMessage("hi")))))

        val error = events.filterIsInstance<StreamEvent.Error>().single()
        assertTrue(error.message.contains("unknown block index 4"))
    }

    @Test
    fun `stream malformed Anthropic tool input emits error and no final tool call`() = runTest {
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://anthropic.test/v1/messages" to UrlHandler(
                    UrlResponse.StreamChunks(
                        listOf(
                            """
                            data: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_1","name":"lookup","input":{}}}

                            data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"city\":"}}

                            data: {"type":"content_block_stop","index":0}

                            data: {"type":"message_stop"}

                            """.trimIndent(),
                        ),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val provider = Anthropic(fixture.httpClient(), AnthropicProviderSettings(baseURL = "https://anthropic.test/v1"))

        val events = drainAllItems(provider.messages(ModelId("claude-sonnet-4-5")).stream(LanguageModelCallParams(messages = listOf(UserMessage("hi")))))

        assertTrue(events.filterIsInstance<StreamEvent.ToolCall>().isEmpty())
        val error = events.filterIsInstance<StreamEvent.Error>().single()
        assertTrue(error.message.contains("malformed tool input JSON"))
    }

    @Test
    fun `auth conflict hosted tools and container forwarding are exposed`() {
        val fixture = TestServer.createTestServer(mutableMapOf())
        val provider = Anthropic(
            fixture.httpClient(),
            AnthropicProviderSettings(authToken = "token"),
        )

        val tool = provider.tools.webSearch_20260209
        assertEquals("web_search", tool.name)
        assertEquals(true, tool.providerExecuted)
        assertEquals("anthropic.web_search_20260209", tool.metadata["providerToolId"]?.jsonPrimitive?.contentOrNull)
        assertFailsWith<InvalidArgumentError> {
            Anthropic(
                fixture.httpClient(),
                AnthropicProviderSettings(apiKey = "key", authToken = "token"),
            )
        }
        assertFailsWith<NoSuchModelError> { provider.embeddingModel("embed") }
        assertFailsWith<NoSuchModelError> { provider.textEmbeddingModel("embed") }
        assertFailsWith<NoSuchModelError> { provider.imageModel("image") }

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
