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
        val provider = Anthropic(fixture.httpClient(), AnthropicProviderSettings { baseURL("https://anthropic.test/v1") })

        val error = assertFailsWith<WireDecodeException> {
            provider.messages(ModelId("claude-sonnet-4-5")).generate(LanguageModelCallParams {
    messages(listOf(UserMessage("hi")))
})
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
        val provider = Anthropic(fixture.httpClient(), AnthropicProviderSettings { baseURL("https://anthropic.test/v1") })

        val error = assertFailsWith<WireDecodeException> {
            provider.messages(ModelId("claude-sonnet-4-5")).generate(LanguageModelCallParams {
    messages(listOf(UserMessage("hi")))
})
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
        val provider = Anthropic(fixture.httpClient(), AnthropicProviderSettings { baseURL("https://anthropic.test/v1") })

        val error = assertFailsWith<WireDecodeException> {
            provider.messages(ModelId("claude-sonnet-4-5")).generate(LanguageModelCallParams {
    messages(listOf(UserMessage("hi")))
})
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
        val provider = Anthropic(fixture.httpClient(), AnthropicProviderSettings { baseURL("https://anthropic.test/v1") })

        val error = assertFailsWith<WireDecodeException> {
            provider.messages(ModelId("claude-sonnet-4-5")).generate(LanguageModelCallParams {
    messages(listOf(UserMessage("hi")))
})
        }

        val message = error.message.orEmpty()
        assertTrue(message.contains("anthropic"), message)
        assertTrue(message.contains("response content"), message)
        assertTrue(message.contains("name"), message)
    }
}
