@file:OptIn(LowLevelLanguageModelApi::class)

package ai.torad.aisdk
import ai.torad.aisdk.providers.GoogleGenerativeAI
import ai.torad.aisdk.providers.GoogleGenerativeAIProviderSettings
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `ContentPart.ToolResult.modelVisible` carries the ENVELOPE that
 * [ToolResultOutput.toJsonElement] produces (`ToolLoopAgent` assigns it), so every provider that
 * writes it to the wire must decode it first via [ToolResultOutputs.toolResultPayloadJson].
 *
 * Anthropic/Bedrock/Cohere/OpenAI-compatible/OpenResponses always did. Google, Google Interactions
 * and LiteRT did not: once the `Json` arm of `toJsonElement()` started emitting
 * `{"type":"json","value":…}` (so a payload that merely LOOKS like an error tag could not be
 * re-decoded as one), those three began sending the wrapper to the model as the tool's result.
 * A tool returning `{"temperature":72}` reached Gemini as `{"type":"json","value":{...}}`.
 *
 * Nothing pinned the tool-result payload on any of the three, which is why the whole suite stayed
 * green through that change. These tests are that pin. Reverting
 * `toolResultPayloadJson` back to a bare `part.modelVisible` at any of the three sites turns them
 * red. The LiteRT half lives in `LiteRTLanguageModelTest` where its conversation fakes already are.
 */
class ToolResultEnvelopeWireTest {
    private val toolPayload = buildJsonObject { put("temperature", JsonPrimitive(72)) }

    /** A tool result exactly as the agent loop builds it: modelVisible is the ENVELOPE. */
    private fun envelopedToolResult(): ContentPart.ToolResult = ContentPart.ToolResult(
        toolCallId = "call-1",
        toolName = "get_weather",
        output = toolPayload,
        modelVisible = ToolResultOutput.Json(toolPayload).toJsonElement(),
    )

    @Test
    fun `google functionResponse carries the tool payload not the SDK envelope`() = runTest {
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://google.test/v1beta/models/gemini-2.5-flash:generateContent" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """
                            {"candidates":[{"content":{"role":"model","parts":[{"text":"ok"}]},"finishReason":"STOP"}]}
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

        provider(ModelId("gemini-2.5-flash")).generate(
            LanguageModelCallParams {
                messages(listOf(ModelMessage(MessageRole.Tool, listOf(envelopedToolResult()))))
            },
        )

        val content = fixture.calls.single().requestBodyJson.jsonObject["contents"]
            ?.jsonArray?.single()?.jsonObject
            ?.get("parts")?.jsonArray?.single()?.jsonObject
            ?.get("functionResponse")?.jsonObject
            ?.get("response")?.jsonObject
            ?.get("content")

        assertEquals(toolPayload, content, "Gemini must receive the tool's payload, not the SDK envelope")
    }

    @Test
    fun `google interactions function_result carries the tool payload not the SDK envelope`() = runTest {
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
                              "steps":[{"type":"model_output","content":[{"type":"text","text":"ok"}]}]
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

        provider.interactions(ModelId("gemini-2.5-flash")).generate(
            LanguageModelCallParams {
                messages(listOf(ModelMessage(MessageRole.Tool, listOf(envelopedToolResult()))))
            },
        )

        val functionResult = fixture.calls.single().requestBodyJson.jsonObject["input"]
            ?.jsonArray
            ?.map { it.jsonObject }
            ?.single { it["type"]?.jsonPrimitive?.contentOrNull == "function_result" }

        assertEquals(
            toolPayload,
            functionResult?.get("result"),
            "Interactions must receive the tool's payload, not the SDK envelope",
        )
    }

    @Test
    fun `error variants reach the wire as their message not as a tagged object`() {
        // The three JSON-shaped providers signal failure with a sibling flag (is_error) or have no
        // error slot at all, so an {"type":"error-text",...} object would land as DATA in the model's
        // context. Flattening is what the string-shaped providers already do.
        assertEquals(
            JsonPrimitive("boom"),
            ToolResultOutputs.toolResultPayloadJson(ToolResultOutput.Error("boom").toJsonElement()),
        )
        assertEquals(
            JsonPrimitive("Tool execution denied."),
            ToolResultOutputs.toolResultPayloadJson(ToolResultOutput.ExecutionDenied(null).toJsonElement()),
        )
        assertEquals(
            toolPayload,
            ToolResultOutputs.toolResultPayloadJson(ToolResultOutput.ErrorJson(toolPayload).toJsonElement()),
        )
    }

    @Test
    fun `a raw success payload that collides with the envelope shape survives unchanged`() {
        // toolResultOutputFromWire only decodes an EXACT envelope shape; a tool whose own output
        // happens to carry a "type" key must not be unwrapped or mistaken for an error.
        val collides = buildJsonObject {
            put("type", JsonPrimitive("json"))
            put("unrelated", JsonPrimitive("kept"))
        }
        assertEquals(collides, ToolResultOutputs.toolResultPayloadJson(collides))
    }
}
