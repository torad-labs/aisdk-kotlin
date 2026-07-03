@file:OptIn(LowLevelLanguageModelApi::class)

package ai.torad.aisdk
import ai.torad.aisdk.providers.GOOGLE_VERSION
import ai.torad.aisdk.providers.GoogleGenerativeAI
import ai.torad.aisdk.providers.GoogleGenerativeAIProvider
import ai.torad.aisdk.providers.GoogleGenerativeAIProviderSettings
import ai.torad.aisdk.testing.FlowDrain.drainAllItems
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@Suppress("LargeClass")
class GoogleInteractionsProviderTest {
    @Test
    fun `interactions model posts v6 request and maps steps usage metadata and sources`() = runTest {
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://google.test/v1beta/interactions" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """
                            {
                              "id":"interaction-1",
                              "model":"gemini-2.5-flash",
                              "status":"completed",
                              "service_tier":"priority",
                              "created":"2026-06-03T00:00:00Z",
                              "steps":[
                                {
                                  "type":"model_output",
                                  "content":[
                                    {
                                      "type":"text",
                                      "text":"Hello from interactions.",
                                      "annotations":[{"type":"url_citation","url":"https://example.com","title":"Example"}]
                                    },
                                    {"type":"image","data":"aW1n","mime_type":"image/png"}
                                  ]
                                },
                                {
                                  "type":"thought",
                                  "signature":"thought-sig",
                                  "summary":[{"type":"text","text":"reasoning"}]
                                },
                                {
                                  "type":"function_call",
                                  "id":"call-1",
                                  "name":"lookup",
                                  "arguments":{"city":"Paris"},
                                  "signature":"call-sig"
                                }
                              ],
                              "usage":{
                                "total_input_tokens":5,
                                "total_cached_tokens":2,
                                "total_output_tokens":7,
                                "total_thought_tokens":3,
                                "total_tokens":15
                              }
                            }
                            """.trimIndent(),
                        ),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val provider = GoogleGenerativeAI(
            fixture.httpClient(),
            GoogleGenerativeAIProviderSettings {
                apiKey("key")
                baseURL("https://google.test/v1beta")
                headers(mapOf("X-Provider" to "provider"))
            },
        )

        val result = provider.interactions(ModelId("gemini-2.5-flash")).generate(
            LanguageModelCallParams {
                messages(
                    listOf(
                        SystemMessage("Follow policy."),
                        UserMessage("Use interactions."),
                    )
                )
                tools(listOf(LanguageModelTool("lookup", "Lookup a city.", objectSchema("city").toString())))
                toolChoice(ToolChoice.Required)
                temperature(0.2f)
                topP(0.9f)
                maxOutputTokens(128)
                stopSequences(listOf("END"))
                seed(7)
                responseFormat(ResponseFormat.Json(schemaJson = objectSchema("answer")))
                providerOptions(
                    ProviderOptions.Raw(
                        JsonObject(
                            mapOf(
                                "google" to buildJsonObject {
                                    put("store", JsonPrimitive(true))
                                    put("previousInteractionId", JsonPrimitive("prior-1"))
                                    put("thinkingLevel", JsonPrimitive("high"))
                                    put("thinkingSummaries", JsonPrimitive("auto"))
                                    put("responseModalities", Json.parseToJsonElement("""["text","image"]"""))
                                    put(
                                        "responseFormat",
                                        Json.parseToJsonElement("""[{"type":"image","mimeType":"image/png","aspectRatio":"1:1","imageSize":"1K"}]"""),
                                    )
                                    put("serviceTier", JsonPrimitive("priority"))
                                },
                            )
                        )
                    )
                )
                headers(mapOf("X-Request" to "request"))
            },
        )

        assertEquals("Hello from interactions.", result.text)
        assertEquals(FinishReason.ToolCalls, result.finishReason)
        assertEquals("call-1", result.toolCalls.single().toolCallId)
        assertEquals("Paris", result.toolCalls.single().input.jsonObject["city"]?.jsonPrimitive?.contentOrNull)
        assertEquals("reasoning", result.content.filterIsInstance<ContentPart.Reasoning>().single().text)
        assertEquals("image/png", result.content.filterIsInstance<ContentPart.File>().single().mediaType)
        assertEquals("https://example.com", result.content.filterIsInstance<ContentPart.Source>().single().url)
        assertEquals(5, result.usage.promptTokens)
        assertEquals(10, result.usage.completionTokens)
        assertEquals(2, result.usage.inputTokens.cacheRead)
        assertEquals(3, result.usage.outputTokens.reasoning)
        assertEquals("interaction-1", result.response.id)
        assertEquals("gemini-2.5-flash", result.response.modelId)
        assertEquals(
            "interaction-1",
            result.providerMetadata.toMap()["google"]?.jsonObject?.get("interactionId")?.jsonPrimitive?.contentOrNull
        )
        assertEquals(
            "priority",
            result.providerMetadata.toMap()["google"]?.jsonObject?.get("serviceTier")?.jsonPrimitive?.contentOrNull
        )

        val request = fixture.calls.single()
        assertEquals("key", request.requestHeaders.headerValue("x-goog-api-key"))
        assertEquals("2026-05-20", request.requestHeaders.headerValue("Api-Revision"))
        assertEquals("provider", request.requestHeaders.headerValue("X-Provider"))
        assertEquals("request", request.requestHeaders.headerValue("X-Request"))
        assertTrue(request.requestUserAgent.orEmpty().contains("ai-sdk/google/$GOOGLE_VERSION"))
        val body = request.requestBodyJson.jsonObject
        assertEquals("gemini-2.5-flash", body["model"]?.jsonPrimitive?.contentOrNull)
        assertEquals("Follow policy.", body["system_instruction"]?.jsonPrimitive?.contentOrNull)
        assertEquals("prior-1", body["previous_interaction_id"]?.jsonPrimitive?.contentOrNull)
        assertEquals(true, body["store"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(
            "Use interactions.",
            body["input"]?.jsonArray?.single()?.jsonObject?.get(
                "content"
            )?.jsonArray?.single()?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
        )
        val generationConfig = body["generation_config"]!!.jsonObject
        assertEquals(128, generationConfig["max_output_tokens"]?.jsonPrimitive?.intOrNull)
        assertEquals("high", generationConfig["thinking_level"]?.jsonPrimitive?.contentOrNull)
        assertEquals("any", generationConfig["tool_choice"]?.jsonPrimitive?.contentOrNull)
        assertEquals(
            "lookup",
            body["tools"]?.jsonArray?.single()?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
        )
        assertEquals(
            "application/json",
            body["response_format"]?.jsonArray?.first()?.jsonObject?.get("mime_type")?.jsonPrimitive?.contentOrNull
        )
        assertEquals(
            "image",
            body["response_format"]?.jsonArray?.last()?.jsonObject?.get("type")?.jsonPrimitive?.contentOrNull
        )
        assertEquals("priority", body["service_tier"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `interactions live stream maps event_type SSE events into stream events`() = runTest {
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://google.test/v1beta/interactions" to UrlHandler(
                    UrlResponse.StreamChunks(
                        listOf(
                            """
                            data: {"event_type":"interaction.created","interaction":{"id":"interaction-2","model":"gemini-2.5-flash","status":"in_progress"}}

                            data: {"event_type":"step.start","index":0,"step":{"type":"model_output"}}

                            data: {"event_type":"step.delta","index":0,"delta":{"type":"text","text":"Hello "}}

                            data: {"event_type":"step.delta","index":0,"delta":{"type":"text","text":"stream"}}

                            data: {"event_type":"step.stop","index":0}

                            data: {"event_type":"step.start","index":1,"step":{"type":"function_call","id":"call-2","name":"lookup","signature":"call-sig"}}

                            data: {"event_type":"step.delta","index":1,"delta":{"type":"arguments_delta","arguments":"{\"city\":"}}

                            data: {"event_type":"step.delta","index":1,"delta":{"type":"arguments_delta","arguments":"\"Berlin\"}"}}

                            data: {"event_type":"step.stop","index":1}

                            data: {"event_type":"interaction.completed","interaction":{"id":"interaction-2","status":"completed","service_tier":"priority","usage":{"total_input_tokens":1,"total_output_tokens":2}}}

                            """.trimIndent(),
                        ),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val provider = GoogleGenerativeAI(
            fixture.httpClient(),
            GoogleGenerativeAIProviderSettings {
                apiKey("key")
                baseURL("https://google.test/v1beta")
            },
        )

        val events = drainAllItems(
            provider.interactions(ModelId("gemini-2.5-flash")).stream(
                LanguageModelCallParams {
                    messages(listOf(UserMessage("hi")))
                }
            ),
        )

        assertIs<StreamEvent.StreamStart>(events.first())
        assertTrue(events.any { it is StreamEvent.ResponseMetadata && it.id == "interaction-2" })
        assertEquals(listOf("Hello ", "stream"), events.filterIsInstance<StreamEvent.TextDelta>().map { it.text })
        assertEquals(
            listOf("""{"city":""", """"Berlin"}"""),
            events.filterIsInstance<StreamEvent.ToolInputDelta>().map { it.delta },
        )
        val toolCall = events.filterIsInstance<StreamEvent.ToolCall>().single()
        assertEquals("lookup", toolCall.toolName)
        assertEquals("Berlin", toolCall.inputJson.jsonObject["city"]?.jsonPrimitive?.contentOrNull)
        val finish = events.filterIsInstance<StreamEvent.Finish>().single()
        assertEquals(FinishReason.ToolCalls, finish.finishReason)
        assertEquals(1, finish.usage.promptTokens)
        assertEquals(2, finish.usage.completionTokens)
        assertEquals(
            "interaction-2",
            finish.providerMetadata.toMap()["google"]?.jsonObject?.get("interactionId")?.jsonPrimitive?.contentOrNull
        )
        assertEquals(
            "priority",
            finish.providerMetadata.toMap()["google"]?.jsonObject?.get("serviceTier")?.jsonPrimitive?.contentOrNull
        )

        val request = fixture.calls.single()
        assertEquals("2026-05-20", request.requestHeaders.headerValue("Api-Revision"))
        assertEquals(true, request.requestBodyJson.jsonObject["stream"]?.jsonPrimitive?.booleanOrNull)
    }

    @Test
    fun `interactions live stream maps image reasoning and built in tool block arms`() = runTest {
        val fixture = interactionsFixture(UrlResponse.StreamChunks(listOf(liveBlockArmStream)))

        val events = drainAllItems(
            googleInteractionsProvider(fixture).interactions(ModelId("gemini-2.5-flash")).stream(
                LanguageModelCallParams {
                    messages(listOf(UserMessage("hi")))
                }
            ),
        )

        assertLiveBlockMediaAndReasoning(events)
        assertLiveProviderExecutedToolBlocks(events)
        assertLiveBlockFinish(events)
    }

    @Test
    fun `interactions live stream error emits error event and fallback finish`() = runTest {
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://google.test/v1beta/interactions" to UrlHandler(
                    UrlResponse.StreamChunks(
                        listOf(
                            """
                            data: {"event_type":"interaction.created","interaction":{"id":"interaction-error","model":"gemini-2.5-flash","status":"in_progress"}}

                            data: {"event_type":"error","error":{"code":"quota_exceeded","message":"quota exhausted"}}

                            """.trimIndent(),
                        ),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val provider = GoogleGenerativeAI(
            fixture.httpClient(),
            GoogleGenerativeAIProviderSettings {
                apiKey("key")
                baseURL("https://google.test/v1beta")
            },
        )

        val events = drainAllItems(
            provider.interactions(ModelId("gemini-2.5-flash")).stream(
                LanguageModelCallParams {
                    messages(listOf(UserMessage("hi")))
                }
            ),
        )

        assertEquals("quota exhausted", events.filterIsInstance<StreamEvent.Error>().single().message)
        val finish = events.filterIsInstance<StreamEvent.Finish>().single()
        assertEquals(FinishReason.Error, finish.finishReason)
        assertEquals("failed", finish.rawFinishReason)
        assertEquals(
            "interaction-error",
            finish.providerMetadata.toMap()["google"]?.jsonObject?.get("interactionId")?.jsonPrimitive?.contentOrNull,
        )
    }

    @Test
    fun `interactions background terminal stream synthesizes reasoning media and tool steps`() = runTest {
        val fixture =
            interactionsFixture(UrlResponse.JsonValue(Json.parseToJsonElement(backgroundTerminalInteractionJson)))

        val events = drainAllItems(
            googleInteractionsProvider(fixture).interactions(ModelId("gemini-2.5-flash")).stream(
                LanguageModelCallParams {
                    messages(listOf(UserMessage("background")))
                    providerOptions(backgroundProviderOptions())
                },
            ),
        )

        assertBackgroundTerminalEvents(events)
    }

    @Test
    fun `interactions agent background stream omits stream on post and synthesizes terminal response`() = runTest {
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://google.test/v1beta/interactions" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """
                            {
                              "id":"agent-run-1",
                              "model":"deep-research",
                              "status":"completed",
                              "steps":[{"type":"model_output","content":[{"type":"text","text":"Agent result"}]}],
                              "usage":{"total_input_tokens":2,"total_output_tokens":3}
                            }
                            """.trimIndent(),
                        ),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val provider = GoogleGenerativeAI(
            fixture.httpClient(),
            GoogleGenerativeAIProviderSettings {
                apiKey("key")
                baseURL("https://google.test/v1beta")
            },
        )

        val events = drainAllItems(
            provider.agentInteraction("deep-research").stream(
                LanguageModelCallParams {
                    messages(listOf(UserMessage("research this")))
                    tools(listOf(LanguageModelTool("lookup", "Lookup.", objectSchema("q").toString())))
                    temperature(0.1f)
                    providerOptions(
                        ProviderOptions.Raw(
                            JsonObject(
                                mapOf(
                                    "google" to buildJsonObject {
                                        put("background", JsonPrimitive(true))
                                        put(
                                            "agentConfig",
                                            Json.parseToJsonElement("""{"type":"deep-research","thinkingSummaries":"auto","visualization":"auto","collaborativePlanning":true}"""),
                                        )
                                        put(
                                            "environment",
                                            Json.parseToJsonElement("""{"type":"remote","sources":[{"type":"inline","content":"notes","target":"/tmp/notes.txt"}],"network":{"allowlist":[{"domain":"example.com"}]}}"""),
                                        )
                                    },
                                )
                            )
                        )
                    )
                },
            ),
        )

        assertTrue(events.any { it is StreamEvent.TextDelta && it.text == "Agent result" })
        val finish = events.filterIsInstance<StreamEvent.Finish>().single()
        assertEquals(FinishReason.Stop, finish.finishReason)
        assertEquals(2, finish.usage.promptTokens)
        assertEquals(3, finish.usage.completionTokens)

        val body = fixture.calls.single().requestBodyJson.jsonObject
        assertEquals("deep-research", body["agent"]?.jsonPrimitive?.contentOrNull)
        assertEquals(null, body["model"])
        assertEquals(null, body["stream"])
        assertEquals(null, body["tools"])
        assertEquals(null, body["generation_config"])
        assertEquals(true, body["background"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("deep-research", body["agent_config"]?.jsonObject?.get("type")?.jsonPrimitive?.contentOrNull)
        assertEquals("remote", body["environment"]?.jsonObject?.get("type")?.jsonPrimitive?.contentOrNull)
    }

    private val liveBlockArmStream = """
        data: {"event_type":"interaction.created","interaction":{"id":"interaction-blocks","model":"gemini-2.5-flash","status":"in_progress"}}

        data: {"event_type":"step.start","index":0,"step":{"type":"model_output","content":[{"type":"image","data":"old","mime_type":"image/webp","uri":"gs://image-old"}]}}

        data: {"event_type":"step.delta","index":0,"delta":{"type":"image","data":"aW1n","mime_type":"image/png","uri":"gs://image-updated"}}

        data: {"event_type":"step.stop","index":0}

        data: {"event_type":"step.start","index":1,"step":{"type":"thought","signature":"sig-start","summary":[{"type":"text","text":"first thought"}]}}

        data: {"event_type":"step.delta","index":1,"delta":{"type":"thought_summary","content":{"type":"text","text":"second thought"}}}

        data: {"event_type":"step.delta","index":1,"delta":{"type":"thought_signature","signature":"sig-final"}}

        data: {"event_type":"step.stop","index":1}

        data: {"event_type":"step.start","index":2,"step":{"type":"mcp_server_tool_call","id":"builtin-start","name":"server_tool","arguments":{"query":"old"}}}

        data: {"event_type":"step.delta","index":2,"delta":{"type":"mcp_server_tool_call","id":"builtin-final","name":"server_tool_updated","arguments":{"query":"new"}}}

        data: {"event_type":"step.stop","index":2}

        data: {"event_type":"step.start","index":3,"step":{"type":"mcp_server_tool_result","call_id":"builtin-final","name":"server_tool_updated","result":{"ok":false},"is_error":false}}

        data: {"event_type":"step.delta","index":3,"delta":{"type":"mcp_server_tool_result","call_id":"builtin-final-2","name":"server_tool_result_updated","result":{"ok":true},"is_error":true}}

        data: {"event_type":"step.stop","index":3}

        data: {"event_type":"interaction.requires_action","interaction_id":"interaction-blocks"}

        data: {"event_type":"interaction.completed","interaction":{"id":"interaction-blocks","status":"completed","service_tier":"priority","usage":{"total_input_tokens":3,"total_output_tokens":5}}}

    """.trimIndent()

    private val backgroundTerminalInteractionJson = """
        {
          "id":"interaction-synth",
          "model":"gemini-2.5-flash",
          "status":"completed",
          "service_tier":"priority",
          "steps":[
            {
              "type":"model_output",
              "content":[
                {"type":"text","text":"Synthesized text"},
                {"type":"image","data":"aW1hZ2U=","mime_type":"image/png"}
              ]
            },
            {
              "type":"thought",
              "signature":"thought-sig",
              "summary":[{"type":"text","text":"synth reasoning"}]
            },
            {
              "type":"function_call",
              "id":"call-synth",
              "name":"lookup",
              "arguments":{"city":"Rome"},
              "signature":"call-sig"
            },
            {
              "type":"mcp_server_tool_call",
              "id":"builtin-synth",
              "name":"server_tool",
              "arguments":{"query":"docs"}
            }
          ],
          "usage":{"total_input_tokens":4,"total_output_tokens":6}
        }
    """.trimIndent()

    private fun interactionsFixture(response: UrlResponse): CreatedTestServer =
        TestServer.createTestServer(
            mutableMapOf("https://google.test/v1beta/interactions" to UrlHandler(response)),
        ).also { it.server.start() }

    private fun googleInteractionsProvider(fixture: CreatedTestServer): GoogleGenerativeAIProvider = GoogleGenerativeAI(
        fixture.httpClient(),
        GoogleGenerativeAIProviderSettings {
            apiKey("key")
            baseURL("https://google.test/v1beta")
        },
    )

    private fun backgroundProviderOptions(): ProviderOptions.Raw =
        ProviderOptions.Raw(
            JsonObject(
                mapOf(
                    "google" to buildJsonObject {
                        put("background", JsonPrimitive(true))
                    },
                )
            )
        )

    private fun assertLiveBlockMediaAndReasoning(events: List<StreamEvent>) {
        val file = events.filterIsInstance<StreamEvent.FilePart>().single()
        assertEquals("image/png", file.mediaType)
        assertEquals("aW1n", file.base64)
        val fileGoogle = file.providerMetadata.toMap()["google"]?.jsonObject
        assertEquals("interaction-blocks", fileGoogle?.get("interactionId")?.jsonPrimitive?.contentOrNull)
        assertEquals("gs://image-updated", fileGoogle?.get("imageUri")?.jsonPrimitive?.contentOrNull)
        assertEquals(
            listOf("first thought", "second thought"),
            events.filterIsInstance<StreamEvent.ReasoningDelta>().map { it.text },
        )
        val reasoningEnd = events.filterIsInstance<StreamEvent.ReasoningEnd>().single()
        assertEquals(
            "sig-final",
            reasoningEnd.providerMetadata.toMap()["google"]?.jsonObject?.get("signature")?.jsonPrimitive?.contentOrNull,
        )
    }

    private fun assertLiveProviderExecutedToolBlocks(events: List<StreamEvent>) {
        val toolCall = events.filterIsInstance<StreamEvent.ToolCall>().single()
        assertEquals("builtin-final", toolCall.toolCallId)
        assertEquals("server_tool_updated", toolCall.toolName)
        assertEquals("new", toolCall.inputJson.jsonObject["query"]?.jsonPrimitive?.contentOrNull)
        assertProviderExecuted(toolCall.providerMetadata)

        val toolResult = events.filterIsInstance<StreamEvent.ToolResult>().single()
        assertEquals("builtin-final-2", toolResult.toolCallId)
        assertEquals("server_tool_result_updated", toolResult.toolName)
        assertEquals(true, toolResult.outputJson.jsonObject["ok"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(true, toolResult.isError)
        assertProviderExecuted(toolResult.providerMetadata)
    }

    private fun assertLiveBlockFinish(events: List<StreamEvent>) {
        val finish = events.filterIsInstance<StreamEvent.Finish>().single()
        assertEquals(FinishReason.ToolCalls, finish.finishReason)
        assertEquals("completed", finish.rawFinishReason)
        assertEquals(3, finish.usage.promptTokens)
        assertEquals(5, finish.usage.completionTokens)
        assertEquals(
            "priority",
            finish.providerMetadata.toMap()["google"]?.jsonObject?.get("serviceTier")?.jsonPrimitive?.contentOrNull,
        )
    }

    private fun assertBackgroundTerminalEvents(events: List<StreamEvent>) {
        assertEquals("Synthesized text", events.filterIsInstance<StreamEvent.TextDelta>().single().text)
        assertEquals("image/png", events.filterIsInstance<StreamEvent.FilePart>().single().mediaType)
        assertEquals("synth reasoning", events.filterIsInstance<StreamEvent.ReasoningDelta>().single().text)
        assertEquals(
            "thought-sig",
            events.filterIsInstance<StreamEvent.ReasoningEnd>().single()
                .providerMetadata.toMap()["google"]?.jsonObject?.get("signature")?.jsonPrimitive?.contentOrNull,
        )

        val toolCalls = events.filterIsInstance<StreamEvent.ToolCall>().associateBy { it.toolCallId }
        assertEquals(
            "Rome",
            toolCalls.getValue("call-synth").inputJson.jsonObject["city"]?.jsonPrimitive?.contentOrNull
        )
        val builtin = toolCalls.getValue("builtin-synth")
        assertEquals("server_tool", builtin.toolName)
        assertEquals("docs", builtin.inputJson.jsonObject["query"]?.jsonPrimitive?.contentOrNull)
        assertProviderExecuted(builtin.providerMetadata)

        val finish = events.filterIsInstance<StreamEvent.Finish>().single()
        assertEquals(FinishReason.ToolCalls, finish.finishReason)
        assertEquals(4, finish.usage.promptTokens)
        assertEquals(6, finish.usage.completionTokens)
        assertEquals("completed", finish.rawFinishReason)
    }

    private fun assertProviderExecuted(metadata: ProviderMetadata) {
        assertEquals(
            true,
            metadata.toMap()["google"]?.jsonObject?.get("providerExecuted")?.jsonPrimitive?.booleanOrNull,
        )
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
        put("required", kotlinx.serialization.json.JsonArray(required.map(::JsonPrimitive)))
    }

    private fun Map<String, String>.headerValue(name: String): String? =
        entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}
