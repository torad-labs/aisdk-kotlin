package ai.torad.aisdk
import ai.torad.aisdk.providers.OpenResponsesProviderSettings
import ai.torad.aisdk.providers.createOpenResponses
import ai.torad.aisdk.providers.openai

import ai.torad.aisdk.testing.drainAllItems
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class OpenResponsesProviderTest {

    @Test
    fun `generate posts Open Responses request and maps response output`() = runTest {
        val seenBodies = mutableListOf<JsonObject>()
        val seenHeaders = mutableListOf<Map<String, List<String>>>()
        val client = HttpClient(
            MockEngine { request ->
                seenHeaders += request.headers.entries().associate { it.key to it.value }
                seenBodies += Json.parseToJsonElement(requestBodyText(request)).jsonObject
                respond(
                    content = """
                        {
                          "id":"resp_1",
                          "created_at":1780000000,
                          "model":"gpt-resp",
                          "output":[
                            {"type":"reasoning","id":"rs_1","content":[{"type":"reasoning_text","text":"think"}]},
                            {"type":"message","id":"msg_1","role":"assistant","content":[{"type":"output_text","text":"hello"}]},
                            {"type":"function_call","id":"fc_1","call_id":"call_1","name":"search","arguments":"{\"q\":\"kotlin\"}"}
                          ],
                          "usage":{
                            "input_tokens":10,
                            "output_tokens":8,
                            "total_tokens":18,
                            "input_tokens_details":{"cached_tokens":3},
                            "output_tokens_details":{"reasoning_tokens":2}
                          }
                        }
                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(
                        HttpHeaders.ContentType to listOf("application/json"),
                        "x-request-id" to listOf("req_1"),
                    ),
                )
            },
        )
        val provider = createOpenResponses(
            client,
            OpenResponsesProviderSettings(
                url = "https://api.test/v1/responses",
                name = "openresponses",
                apiKey = "secret",
                headers = mapOf("x-project" to "aisdk"),
            ),
        )

        val result = provider.responses("gpt-resp").generate(
            LanguageModelCallParams(
                messages = listOf(systemMessage("system rules"), userMessage("hi")),
                tools = listOf(
                    LanguageModelTool(
                        name = "search",
                        description = "Search docs",
                        parametersSchemaJson = objectSchema("q").toString(),
                        strict = false,
                    ),
                ),
                toolChoice = ToolChoice.Specific("search"),
                maxOutputTokens = 100,
                temperature = 0.5f,
                topP = 0.9f,
                responseFormat = ResponseFormat.Json(
                    schemaName = "Answer",
                    schemaJson = objectSchema("answer"),
                ),
                providerOptions = mapOf(
                    "openresponses" to buildJsonObject {
                        put("reasoningEffort", JsonPrimitive("low"))
                        put("reasoningSummary", JsonPrimitive("concise"))
                    },
                ),
            ),
        )

        val body = seenBodies.single()
        assertEquals("Bearer secret", seenHeaders.single().headerValue("Authorization"))
        assertEquals("aisdk", seenHeaders.single()["x-project"]?.single())
        assertEquals("gpt-resp", body["model"]?.jsonPrimitive?.content)
        assertEquals("system rules", body["instructions"]?.jsonPrimitive?.content)
        assertEquals("hi", body["input"]!!.jsonArray.single().jsonObject["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("low", body["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertEquals("concise", body["reasoning"]!!.jsonObject["summary"]!!.jsonPrimitive.content)
        assertEquals("function", body["tools"]!!.jsonArray.single().jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(false, body["tools"]!!.jsonArray.single().jsonObject["strict"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("search", body["tool_choice"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("json_schema", body["text"]!!.jsonObject["format"]!!.jsonObject["type"]!!.jsonPrimitive.content)

        assertEquals("hello", result.text)
        assertEquals("think", result.content.filterIsInstance<ContentPart.Reasoning>().single().text)
        assertEquals(FinishReason.ToolCalls, result.finishReason)
        assertEquals("search", result.toolCalls.single().toolName)
        assertEquals(JsonPrimitive("kotlin"), result.toolCalls.single().input.jsonObject["q"])
        assertEquals(10, result.usage.promptTokens)
        assertEquals(8, result.usage.completionTokens)
        assertEquals(3, result.usage.inputTokens.cacheRead)
        assertEquals(2, result.usage.outputTokens.reasoning)
        assertEquals("resp_1", result.response.id)
        assertEquals(1_780_000_000_000, result.response.timestampMillis)
        assertEquals("gpt-resp", result.response.modelId)
    }

    @Test
    fun `stream maps Open Responses SSE text reasoning tool call finish and usage`() = runTest {
        val seenBodies = mutableListOf<JsonObject>()
        val client = HttpClient(
            MockEngine { request ->
                val body = Json.parseToJsonElement(requestBodyText(request)).jsonObject
                seenBodies += body
                assertEquals(true, body["stream"]?.jsonPrimitive?.booleanOrNull)
                respond(
                    content = """
                        data: {"type":"response.output_item.added","item":{"type":"reasoning","id":"rs_1"}}

                        data: {"type":"response.reasoning_text.delta","item_id":"rs_1","delta":"think"}

                        data: {"type":"response.output_item.done","item":{"type":"reasoning","id":"rs_1"}}

                        data: {"type":"response.output_item.added","item":{"type":"message","id":"msg_1"}}

                        data: {"type":"response.output_text.delta","item_id":"msg_1","delta":"hello"}

                        data: {"type":"response.output_item.done","item":{"type":"message","id":"msg_1"}}

                        data: {"type":"response.output_item.added","item":{"type":"function_call","id":"fc_1","call_id":"call_1","name":"search","arguments":""}}

                        data: {"type":"response.function_call_arguments.delta","item_id":"fc_1","delta":"{\"q\""}

                        data: {"type":"response.function_call_arguments.done","item_id":"fc_1","arguments":"{\"q\":\"docs\"}"}

                        data: {"type":"response.output_item.done","item":{"type":"function_call","id":"fc_1","call_id":"call_1","name":"search","arguments":"{\"q\":\"docs\"}"}}

                        data: {"type":"response.completed","response":{"usage":{"input_tokens":1,"output_tokens":2,"total_tokens":3,"input_tokens_details":{"cached_tokens":0},"output_tokens_details":{"reasoning_tokens":1}}}}

                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
                )
            },
        )
        val provider = createOpenResponses(client, OpenResponsesProviderSettings("https://api.test/v1/responses", "openresponses"))

        val events = drainAllItems(provider.languageModel("gpt-resp").stream(LanguageModelCallParams(listOf(userMessage("hi")))))

        assertIs<StreamEvent.StreamStart>(events.first())
        assertTrue(events.any { it is StreamEvent.ReasoningDelta && it.text == "think" })
        assertTrue(events.any { it is StreamEvent.TextDelta && it.text == "hello" })
        val toolCall = events.filterIsInstance<StreamEvent.ToolCall>().single()
        assertEquals("search", toolCall.toolName)
        assertEquals(JsonPrimitive("docs"), toolCall.inputJson.jsonObject["q"])
        val finish = events.filterIsInstance<StreamEvent.Finish>().single()
        assertEquals(FinishReason.ToolCalls, finish.finishReason)
        assertEquals(1, finish.usage.promptTokens)
        assertEquals(2, finish.usage.completionTokens)
        assertEquals("gpt-resp", seenBodies.single()["model"]!!.jsonPrimitive.content)
    }

    @Test
    fun `stream surfaces Open Responses missing output item as error`() = runTest {
        val client = HttpClient(
            MockEngine {
                respond(
                    content = """data: {"type":"response.output_item.added"}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
                )
            },
        )
        val provider = createOpenResponses(client, OpenResponsesProviderSettings("https://api.test/v1/responses", "openresponses"))

        val events = drainAllItems(provider.languageModel("gpt-resp").stream(LanguageModelCallParams(listOf(userMessage("hi")))))

        val error = events.filterIsInstance<StreamEvent.Error>().single()
        assertTrue(error.message.contains("missing item"))
    }

    @Test
    fun `tool call and tool result content convert back to Open Responses input`() = runTest {
        val seenBodies = mutableListOf<JsonObject>()
        val client = HttpClient(
            MockEngine { request ->
                seenBodies += Json.parseToJsonElement(requestBodyText(request)).jsonObject
                respond(
                    content = """{"id":"resp_2","created_at":1780000001,"model":"gpt-resp","output":[{"type":"message","id":"msg_2","role":"assistant","content":[{"type":"output_text","text":"done"}]}]}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        val provider = createOpenResponses(client, OpenResponsesProviderSettings("https://api.test/v1/responses", "openresponses"))

        val modelVisible = ToolResultOutput.Content(
            value = listOf(
                buildJsonObject {
                    put("type", JsonPrimitive("text"))
                    put("text", JsonPrimitive("rendered"))
                },
                buildJsonObject {
                    put("type", JsonPrimitive("image-data"))
                    put("mediaType", JsonPrimitive("image/png"))
                    put("data", JsonPrimitive("iVBORw0="))
                },
            ),
        ).toJsonElement()
        provider.languageModel("gpt-resp").generate(
            LanguageModelCallParams(
                messages = listOf(
                    ModelMessage(
                        MessageRole.Assistant,
                        listOf(
                            ContentPart.ToolCall(
                                toolCallId = "call_1",
                                toolName = "render",
                                input = buildJsonObject { put("topic", JsonPrimitive("logo")) },
                            ),
                        ),
                    ),
                    ModelMessage(
                        MessageRole.Tool,
                        listOf(
                            ContentPart.ToolResult(
                                toolCallId = "call_1",
                                toolName = "render",
                                output = JsonPrimitive("full"),
                                modelVisible = modelVisible,
                            ),
                        ),
                    ),
                ),
            ),
        )

        val input = seenBodies.single()["input"]!!.jsonArray
        assertEquals("function_call", input[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("function_call_output", input[1].jsonObject["type"]!!.jsonPrimitive.content)
        val output = input[1].jsonObject["output"]!!.jsonArray
        assertEquals("input_text", output[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("rendered", output[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("input_image", output[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("data:image/png;base64,iVBORw0=", output[1].jsonObject["image_url"]!!.jsonPrimitive.content)
    }

    @Test
    fun `user file content does not infer file id from decodable base64 prefix`() = runTest {
        val seenBodies = mutableListOf<JsonObject>()
        val client = HttpClient(
            MockEngine { request ->
                seenBodies += Json.parseToJsonElement(requestBodyText(request)).jsonObject
                respond(
                    content = """{"id":"resp_3","created_at":1780000002,"model":"gpt-resp","output":[{"type":"message","id":"msg_3","role":"assistant","content":[{"type":"output_text","text":"ok"}]}]}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        val provider = createOpenResponses(
            client,
            OpenResponsesProviderSettings(
                url = "https://api.test/v1/responses",
                name = "openresponses",
                fileIdPrefixes = listOf("file-"),
            ),
        )

        provider.languageModel("gpt-resp").generate(
            LanguageModelCallParams(
                messages = listOf(
                    ModelMessage(
                        MessageRole.User,
                        listOf(
                            ContentPart.File(
                                mediaType = "application/pdf",
                                base64 = "file-abc",
                                filename = "payload.pdf",
                            ),
                            ContentPart.File(
                                mediaType = "application/pdf",
                                base64 = "ignored",
                                filename = "remote.pdf",
                                providerMetadata = mapOf(
                                    "openai" to buildJsonObject {
                                        put("file_id", JsonPrimitive("file-explicit"))
                                    },
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val content = seenBodies.single()["input"]!!.jsonArray.single().jsonObject["content"]!!.jsonArray
        val payload = content[0].jsonObject
        val explicit = content[1].jsonObject
        assertEquals("data:application/pdf;base64,file-abc", payload["file_data"]!!.jsonPrimitive.content)
        assertEquals(null, payload["file_id"])
        assertEquals("file-explicit", explicit["file_id"]!!.jsonPrimitive.content)
        assertEquals(null, explicit["file_data"])
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
        put("additionalProperties", JsonPrimitive(false))
    }

    private fun requestBodyText(request: HttpRequestData): String =
        when (val body = request.body) {
            is OutgoingContent.ByteArrayContent -> body.bytes().decodeToString()
            is OutgoingContent.NoContent -> ""
            else -> body.toString()
        }

    private fun Map<String, List<String>>.headerValue(name: String): String? =
        entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.singleOrNull()
}
