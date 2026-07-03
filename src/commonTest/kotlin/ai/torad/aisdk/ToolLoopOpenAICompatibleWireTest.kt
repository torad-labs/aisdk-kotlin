package ai.torad.aisdk

import ai.torad.aisdk.providers.OpenAICompatible
import ai.torad.aisdk.providers.OpenAICompatibleProviderSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class ToolLoopOpenAICompatibleWireTest {
    @Test
    fun `tool loop sends executed tool result back as OpenAI-compatible tool message`() = runTest {
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://api.test/v1/chat/completions" to UrlHandler { _, options ->
                    when (options.callNumber) {
                        0 -> UrlResponse.JsonValue(firstToolCallResponse)
                        1 -> UrlResponse.JsonValue(finalTextResponse)
                        else -> UrlResponse.Error(status = 500, body = "unexpected call")
                    }
                },
            ),
        )
        fixture.server.start()
        val provider = OpenAICompatible(
            fixture.httpClient(),
            OpenAICompatibleProviderSettings {
                name("openai")
                baseUrl("https://api.test/v1")
                apiKey("test-key")
            },
        )
        val executions = mutableListOf<String>()
        val weatherTool = Tool<WeatherInput, String, Unit>(
            name = "weather",
            description = "Look up the weather.",
            inputSerializer = WeatherInput.serializer(),
            outputSerializer = String.serializer(),
            executor = { input ->
                executions += input.city
                "sunny in ${input.city}"
            },
        )
        val agent = TestToolLoopAgent<Unit, String>(
            model = provider.languageModel("gpt-test"),
            instructions = "Use tools when useful.",
            tools = ToolSet(weatherTool),
        )

        val result = agent.generate(prompt = "Weather in Paris?", options = Unit).first()

        assertEquals("The forecast is sunny in Paris.", result.text)
        assertEquals(listOf("Paris"), executions)
        assertEquals(2, fixture.calls.size)
        val secondBody = fixture.calls[1].requestBodyJson.jsonObject
        val secondMessages = secondBody.getValue("messages").jsonArray.map { it.jsonObject }
        assertEquals(listOf("system", "user", "assistant", "tool"), secondMessages.map { it.stringField("role") })
        val assistantMessage = secondMessages[2]
        val assistantToolCall = assistantMessage.getValue("tool_calls").jsonArray.single().jsonObject
        assertEquals("call_weather_1", assistantToolCall.stringField("id"))
        assertEquals(
            """{"city":"Paris"}""",
            assistantToolCall.getValue("function").jsonObject.stringField("arguments"),
        )
        val toolMessage = secondMessages[3]
        assertEquals("tool", toolMessage.stringField("role"))
        assertEquals("call_weather_1", toolMessage.stringField("tool_call_id"))
        assertEquals("sunny in Paris", toolMessage.stringField("content"))
    }

    @Serializable
    private data class WeatherInput(val city: String)

    private fun JsonObject.stringField(name: String): String? =
        get(name)?.jsonPrimitive?.contentOrNull

    private companion object {
        val firstToolCallResponse: JsonObject = Json.parseToJsonElement(
            """
            {
              "id": "chatcmpl_tool",
              "created": 1780000000,
              "model": "gpt-test",
              "choices": [
                {
                  "message": {
                    "role": "assistant",
                    "content": null,
                    "tool_calls": [
                      {
                        "id": "call_weather_1",
                        "type": "function",
                        "function": {
                          "name": "weather",
                          "arguments": "{\"city\":\"Paris\"}"
                        }
                      }
                    ]
                  },
                  "finish_reason": "tool_calls"
                }
              ],
              "usage": {
                "prompt_tokens": 5,
                "completion_tokens": 2
              }
            }
            """.trimIndent(),
        ).jsonObject

        val finalTextResponse: JsonObject = Json.parseToJsonElement(
            """
            {
              "id": "chatcmpl_final",
              "created": 1780000001,
              "model": "gpt-test",
              "choices": [
                {
                  "message": {
                    "role": "assistant",
                    "content": "The forecast is sunny in Paris."
                  },
                  "finish_reason": "stop"
                }
              ],
              "usage": {
                "prompt_tokens": 7,
                "completion_tokens": 6
              }
            }
            """.trimIndent(),
        ).jsonObject
    }
}
