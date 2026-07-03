@file:OptIn(LowLevelLanguageModelApi::class)

package ai.torad.aisdk
import ai.torad.aisdk.providers.Anthropic
import ai.torad.aisdk.providers.AnthropicProviderSettings
import ai.torad.aisdk.testing.FlowDrain.drainAllItems
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AnthropicProviderStreamingTest {
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
            AnthropicProviderSettings {
                apiKey("key")
                baseURL("https://anthropic.test/v1")
            },
        )

        val events = drainAllItems(
            provider.messages(ModelId("claude-sonnet-4-5")).stream(
                LanguageModelCallParams {
                    messages(listOf(UserMessage("hi")))
                }
            ),
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
        val provider = Anthropic(
            fixture.httpClient(),
            AnthropicProviderSettings { baseURL("https://anthropic.test/v1") }
        )
        val emitted = mutableListOf<StreamEvent>()

        val error = assertFailsWith<APICallError> {
            provider.messages(ModelId("claude-sonnet-4-5"))
                .stream(
                    LanguageModelCallParams {
                        messages(listOf(UserMessage("hi")))
                    }
                )
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
        val provider = Anthropic(
            fixture.httpClient(),
            AnthropicProviderSettings { baseURL("https://anthropic.test/v1") }
        )

        val events = drainAllItems(
            provider.messages(ModelId("claude-sonnet-4-5")).stream(
                LanguageModelCallParams {
                    messages(listOf(UserMessage("hi")))
                },
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
        val provider = Anthropic(
            fixture.httpClient(),
            AnthropicProviderSettings { baseURL("https://anthropic.test/v1") }
        )

        val events = drainAllItems(
            provider.messages(ModelId("claude-sonnet-4-5")).stream(
                LanguageModelCallParams {
                    messages(listOf(UserMessage("hi")))
                },
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
        val provider = Anthropic(
            fixture.httpClient(),
            AnthropicProviderSettings { baseURL("https://anthropic.test/v1") }
        )

        val events = drainAllItems(
            provider.messages(ModelId("claude-sonnet-4-5")).stream(
                LanguageModelCallParams {
                    messages(listOf(UserMessage("hi")))
                }
            )
        )

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
        val provider = Anthropic(
            fixture.httpClient(),
            AnthropicProviderSettings { baseURL("https://anthropic.test/v1") }
        )
        val events = drainAllItems(
            provider.messages(ModelId("claude-sonnet-4-5")).stream(
                LanguageModelCallParams {
                    messages(listOf(UserMessage("hi")))
                },
            ),
        )
        val finish = events.filterIsInstance<StreamEvent.Finish>().single()
        assertEquals(42, finish.usage.promptTokens, "input tokens preserved from message_start")
        assertEquals(7, finish.usage.completionTokens, "output tokens updated from message_delta")
    }

    @Test
    fun `stream usage merges message_delta iterations into totals`() = runTest {
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://anthropic.test/v1/messages" to UrlHandler(
                    UrlResponse.StreamChunks(
                        listOf(
                            """
                            data: {"type":"message_start","message":{"id":"m","model":"claude-sonnet-4-5","usage":{"input_tokens":5,"output_tokens":1}}}

                            data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":2,"iterations":[{"type":"message","input_tokens":40,"output_tokens":6},{"type":"compaction","input_tokens":8,"output_tokens":0}]}}

                            data: {"type":"message_stop"}

                            """.trimIndent(),
                        ),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val provider = Anthropic(
            fixture.httpClient(),
            AnthropicProviderSettings { baseURL("https://anthropic.test/v1") }
        )

        val events = drainAllItems(
            provider.messages(ModelId("claude-sonnet-4-5")).stream(
                LanguageModelCallParams {
                    messages(listOf(UserMessage("hi")))
                }
            )
        )

        val finish = events.filterIsInstance<StreamEvent.Finish>().single()
        assertEquals(48, finish.usage.promptTokens)
        assertEquals(6, finish.usage.completionTokens)
        assertEquals(2, finish.usage.raw?.jsonObject?.get("iterations")?.jsonArray?.size)
    }

    @Test
    fun `stream redacted thinking keeps redacted data in reasoning metadata`() = runTest {
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://anthropic.test/v1/messages" to UrlHandler(
                    UrlResponse.StreamChunks(
                        listOf(
                            """
                            data: {"type":"message_start","message":{"id":"m","model":"claude-sonnet-4-5","usage":{"input_tokens":1,"output_tokens":0}}}

                            data: {"type":"content_block_start","index":0,"content_block":{"type":"redacted_thinking","data":"redacted-thinking-data"}}

                            data: {"type":"content_block_stop","index":0}

                            data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":1}}

                            data: {"type":"message_stop"}

                            """.trimIndent(),
                        ),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val provider = Anthropic(
            fixture.httpClient(),
            AnthropicProviderSettings { baseURL("https://anthropic.test/v1") }
        )

        val events = drainAllItems(
            provider.messages(ModelId("claude-sonnet-4-5")).stream(
                LanguageModelCallParams {
                    messages(listOf(UserMessage("hi")))
                }
            )
        )

        val reasoningStart = events.filterIsInstance<StreamEvent.ReasoningStart>().single()
        assertEquals(
            "redacted-thinking-data",
            reasoningStart.providerMetadata.toMap()["anthropic"]?.jsonObject?.get(
                "redactedData"
            )?.jsonPrimitive?.contentOrNull,
        )
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
        val provider = Anthropic(
            fixture.httpClient(),
            AnthropicProviderSettings { baseURL("https://anthropic.test/v1") }
        )

        val events = drainAllItems(
            provider.messages(ModelId("claude-sonnet-4-5")).stream(
                LanguageModelCallParams {
                    messages(listOf(UserMessage("hi")))
                }
            )
        )

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
        val provider = Anthropic(
            fixture.httpClient(),
            AnthropicProviderSettings { baseURL("https://anthropic.test/v1") }
        )

        val events = drainAllItems(
            provider.messages(ModelId("claude-sonnet-4-5")).stream(
                LanguageModelCallParams {
                    messages(listOf(UserMessage("hi")))
                }
            )
        )

        assertTrue(events.filterIsInstance<StreamEvent.ToolCall>().isEmpty())
        val error = events.filterIsInstance<StreamEvent.Error>().single()
        assertTrue(error.message.contains("malformed tool input JSON"))
    }

    private fun Map<String, String>.headerValue(name: String): String? =
        entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}
