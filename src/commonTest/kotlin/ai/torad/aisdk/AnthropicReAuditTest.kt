package ai.torad.aisdk

import ai.torad.aisdk.providers.Anthropic
import ai.torad.aisdk.providers.AnthropicProviderSettings
import ai.torad.aisdk.testing.FlowDrain.drainAllItems
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Re-audit regressions for AnthropicProvider:
 *  - streaming `signature_delta` / `citations_delta` must NOT abort the stream, and unknown
 *    delta subtypes must be ignored forward-compatibly;
 *  - tool-result `modelVisible` must be DECODED, not stringified, so MCP content/images survive
 *    and error/denial wrappers don't leak into the prompt.
 */
class AnthropicReAuditTest {
    @Test
    fun `stream surfaces thinking signature and citation source and ignores unknown delta`() = runTest {
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://anthropic.test/v1/messages" to UrlHandler(
                    UrlResponse.StreamChunks(
                        listOf(
                            """
                            data: {"type":"message_start","message":{"id":"msg_sig","model":"claude-sonnet-4-5","usage":{"input_tokens":1,"output_tokens":0}}}

                            data: {"type":"content_block_start","index":0,"content_block":{"type":"thinking","id":"think-1"}}

                            data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"reasoning"}}

                            data: {"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"sig-stream"}}

                            data: {"type":"content_block_stop","index":0}

                            data: {"type":"content_block_start","index":1,"content_block":{"type":"text","id":"text-1"}}

                            data: {"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"hello"}}

                            data: {"type":"content_block_delta","index":1,"delta":{"type":"citations_delta","citation":{"type":"web_search_result_location","url":"https://example.com","title":"Example"}}}

                            data: {"type":"content_block_delta","index":1,"delta":{"type":"some_future_delta","value":"ignored"}}

                            data: {"type":"content_block_stop","index":1}

                            data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":2}}

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

        // The whole point: no fatal stream error from signature/citations/unknown deltas.
        assertEquals(emptyList(), events.filterIsInstance<StreamEvent.Error>(), events.toString())

        // signature_delta is captured onto the eventual ReasoningEnd providerMetadata.
        val reasoningEnd = events.filterIsInstance<StreamEvent.ReasoningEnd>().single()
        val anthropicMeta = reasoningEnd.providerMetadata.toMap()["anthropic"]?.jsonObject
        assertEquals("sig-stream", anthropicMeta?.get("signature")?.jsonPrimitive?.contentOrNull)

        // citations_delta emits a SourcePart mirroring the non-streaming citation path.
        val source = events.filterIsInstance<StreamEvent.SourcePart>().single()
        assertEquals(StreamEvent.SourcePart.SourceType.Url, source.sourceType)
        assertEquals("https://example.com", source.url)
        assertEquals("Example", source.title)

        // Stream still completes normally.
        assertEquals(FinishReason.Stop, events.filterIsInstance<StreamEvent.Finish>().single().finishReason)
    }

    @Test
    fun `tool result decodes MCP content with text plus image into Anthropic content blocks`() = runTest {
        val fixture = anthropicEchoFixture()
        fixture.server.start()
        val provider = Anthropic(fixture.httpClient(), AnthropicProviderSettings(baseURL = "https://anthropic.test/v1"))

        val mcpContent = with(ToolResultOutputs) {
            ToolResultOutput.Content(
                value = listOf(
                    buildJsonObject {
                        put("type", JsonPrimitive("text"))
                        put("text", JsonPrimitive("snapshot taken"))
                    },
                    buildJsonObject {
                        put("type", JsonPrimitive("image-data"))
                        put("mediaType", JsonPrimitive("image/png"))
                        put("data", JsonPrimitive("aW1n"))
                    },
                ),
            ).toJsonElement()
        }

        provider.messages(ModelId("claude-sonnet-4-5")).generate(
            LanguageModelCallParams(
                messages = listOf(
                    ModelMessage(
                        MessageRole.Tool,
                        listOf(
                            ContentPart.ToolResult(
                                toolCallId = "toolu_mcp",
                                toolName = "screenshot",
                                output = mcpContent,
                                modelVisible = mcpContent,
                            ),
                        ),
                    ),
                ),
            ),
        )

        val toolContent = lastToolResultContent(fixture)
        // Must be an ARRAY of native content blocks, not a stringified blob.
        val blocks = toolContent.jsonArray
        assertEquals("text", blocks[0].jsonObject["type"]?.jsonPrimitive?.contentOrNull)
        assertEquals("snapshot taken", blocks[0].jsonObject["text"]?.jsonPrimitive?.contentOrNull)
        assertEquals("image", blocks[1].jsonObject["type"]?.jsonPrimitive?.contentOrNull)
        val source = blocks[1].jsonObject["source"]?.jsonObject
        assertEquals("base64", source?.get("type")?.jsonPrimitive?.contentOrNull)
        assertEquals("image/png", source?.get("media_type")?.jsonPrimitive?.contentOrNull)
        assertEquals("aW1n", source?.get("data")?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `tool result does not leak the error wrapper into the prompt`() = runTest {
        val fixture = anthropicEchoFixture()
        fixture.server.start()
        val provider = Anthropic(fixture.httpClient(), AnthropicProviderSettings(baseURL = "https://anthropic.test/v1"))

        val errorOutput = with(ToolResultOutputs) {
            ToolResultOutput.Error("upstream timed out").toJsonElement()
        }

        provider.messages(ModelId("claude-sonnet-4-5")).generate(
            LanguageModelCallParams(
                messages = listOf(
                    ModelMessage(
                        MessageRole.Tool,
                        listOf(
                            ContentPart.ToolResult(
                                toolCallId = "toolu_err",
                                toolName = "lookup",
                                output = errorOutput,
                                modelVisible = errorOutput,
                                isError = true,
                            ),
                        ),
                    ),
                ),
            ),
        )

        // Decoded to the bare message string — NOT the {"type":"error-text",...} wrapper.
        val toolContent = lastToolResultContent(fixture)
        assertEquals("upstream timed out", toolContent.jsonPrimitive.contentOrNull)
        assertNull(toolContent as? JsonObject)
    }

    // --- helpers -------------------------------------------------------------

    private fun anthropicEchoFixture() = TestServer.createTestServer(
        mutableMapOf(
            "https://anthropic.test/v1/messages" to UrlHandler(
                UrlResponse.JsonValue(
                    kotlinx.serialization.json.Json.parseToJsonElement(
                        """
                        {
                          "id":"msg_echo",
                          "type":"message",
                          "role":"assistant",
                          "model":"claude-sonnet-4-5",
                          "stop_reason":"end_turn",
                          "usage":{"input_tokens":1,"output_tokens":1},
                          "content":[{"type":"text","text":"ok"}]
                        }
                        """.trimIndent(),
                    ),
                ),
            ),
        ),
    )

    /** Pull the `tool_result.content` value the provider serialized for the single tool turn. */
    private fun lastToolResultContent(fixture: CreatedTestServer): JsonElement {
        val body = fixture.calls.single().requestBodyJson.jsonObject
        val userMessage = body.getValue("messages").jsonArray
            .first { it.jsonObject["role"]?.jsonPrimitive?.contentOrNull == "user" }
        val toolResult = userMessage.jsonObject.getValue("content").jsonArray
            .single { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "tool_result" }
        return toolResult.jsonObject.getValue("content")
    }
}
