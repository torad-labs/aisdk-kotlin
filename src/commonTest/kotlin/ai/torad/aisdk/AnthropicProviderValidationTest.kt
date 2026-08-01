@file:OptIn(LowLevelLanguageModelApi::class)

package ai.torad.aisdk
import ai.torad.aisdk.providers.Anthropic
import ai.torad.aisdk.providers.AnthropicProviderSettings
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AnthropicProviderValidationTest {
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
        val provider =
            Anthropic(fixture.httpClient(), AnthropicProviderSettings { baseURL("https://anthropic.test/v1") })

        val error = assertFailsWith<WireDecodeException> {
            provider.messages(ModelId("claude-sonnet-4-5")).generate(
                LanguageModelCallParams {
                    messages(listOf(UserMessage("hi")))
                }
            )
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
        val provider =
            Anthropic(fixture.httpClient(), AnthropicProviderSettings { baseURL("https://anthropic.test/v1") })

        val error = assertFailsWith<WireDecodeException> {
            provider.messages(ModelId("claude-sonnet-4-5")).generate(
                LanguageModelCallParams {
                    messages(listOf(UserMessage("hi")))
                }
            )
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
        val provider =
            Anthropic(fixture.httpClient(), AnthropicProviderSettings { baseURL("https://anthropic.test/v1") })

        val error = assertFailsWith<WireDecodeException> {
            provider.messages(ModelId("claude-sonnet-4-5")).generate(
                LanguageModelCallParams {
                    messages(listOf(UserMessage("hi")))
                }
            )
        }

        val message = error.message.orEmpty()
        assertTrue(message.contains("anthropic"), message)
        assertTrue(message.contains("response content"), message)
        assertTrue(message.contains("tool_use_id"), message)
        assertTrue(message.contains("id"), message)
    }

    @Test
    fun `messages model names a provider tool result from its originating call`() = runTest {
        // The Messages API does not send `name` on server-tool result blocks. The name must come
        // from the paired server_tool_use block, NOT from arithmetic on the block type:
        // "web_search_tool_result".removeSuffix("_result") is "web_search_tool", and the real tool
        // is "web_search" — which made one response emit a ToolCall and a ToolResult with different
        // names for the same tool_use_id.
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
                              "content":[
                                {"type":"server_tool_use","id":"srv_1","name":"web_search","input":{"query":"kotlin"}},
                                {"type":"web_search_tool_result","tool_use_id":"srv_1","content":{"type":"web_search_result"}}
                              ]
                            }
                            """.trimIndent(),
                        ),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val provider =
            Anthropic(fixture.httpClient(), AnthropicProviderSettings { baseURL("https://anthropic.test/v1") })

        val result = provider.messages(ModelId("claude-sonnet-4-5")).generate(
            LanguageModelCallParams {
                messages(listOf(UserMessage("hi")))
            },
        )
        val toolResult = result.content.filterIsInstance<ContentPart.ToolResult>().single()
        assertEquals("srv_1", toolResult.toolCallId)
        assertEquals("web_search", toolResult.toolName)
        // The call and its result must agree — that identity is what a consumer joins on.
        val toolCall = result.content.filterIsInstance<ContentPart.ToolCall>().single()
        assertEquals(toolCall.toolName, toolResult.toolName)
        assertEquals(toolCall.toolCallId, toolResult.toolCallId)
    }

    @Test
    fun `messages model falls back to the block type when no originating call was seen`() = runTest {
        // Last resort only: a result block with neither a `name` nor a preceding server_tool_use.
        // Pinned so the fallback is a deliberate, visible approximation rather than silent drift.
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://anthropic.test/v1/messages" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """
                            {
                              "id":"msg_orphan_result",
                              "type":"message",
                              "role":"assistant",
                              "model":"claude-sonnet-4-5",
                              "stop_reason":"end_turn",
                              "usage":{"input_tokens":1,"output_tokens":1},
                              "content":[{"type":"web_search_tool_result","tool_use_id":"srv_9","content":{"type":"web_search_result"}}]
                            }
                            """.trimIndent(),
                        ),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val provider =
            Anthropic(fixture.httpClient(), AnthropicProviderSettings { baseURL("https://anthropic.test/v1") })

        val result = provider.messages(ModelId("claude-sonnet-4-5")).generate(
            LanguageModelCallParams { messages(listOf(UserMessage("hi"))) },
        )
        val toolResult = result.content.filterIsInstance<ContentPart.ToolResult>().single()
        assertEquals("srv_9", toolResult.toolCallId)
        assertEquals("web_search_tool", toolResult.toolName)
    }
}
