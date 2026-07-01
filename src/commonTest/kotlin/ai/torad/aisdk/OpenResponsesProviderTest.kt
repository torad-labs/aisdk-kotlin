@file:OptIn(LowLevelLanguageModelApi::class)

package ai.torad.aisdk
import ai.torad.aisdk.ToolResultOutputs.toJsonElement
import ai.torad.aisdk.providers.OpenResponses
import ai.torad.aisdk.providers.OpenResponsesProviderSettings
import ai.torad.aisdk.testing.FlowDrain.drainAllItems
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
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
        val provider = OpenResponses(
            client,
            OpenResponsesProviderSettings {
                url("https://api.test/v1/responses")
                name("openresponses")
                apiKey("secret")
                headers(mapOf("x-project" to "aisdk"))
            },
        )
        val result = provider.responses("gpt-resp").generate(
            LanguageModelCallParams {
                messages(listOf(SystemMessage("system rules"), UserMessage("hi")))
                tools(listOf(
                    LanguageModelTool(
                        name = "search",
                        description = "Search docs",
                        parametersSchemaJson = objectSchema("q").toString(),
                        strict = false,
                    ),
                ))
                toolChoice(ToolChoice.Specific("search"))
                maxOutputTokens(100)
                temperature(0.5f)
                topP(0.9f)
                responseFormat(ResponseFormat.Json(
                    schemaName = "Answer",
                    schemaJson = objectSchema("answer"),
                ))
                providerOptions(ProviderOptions.Raw(JsonObject(mapOf(
                    "openresponses" to buildJsonObject {
                        put("reasoningEffort", JsonPrimitive("low"))
                        put("reasoningSummary", JsonPrimitive("concise"))
                    },
                ))))
            },
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
    fun `generate surfaces web search call as provider-executed tool call and result`() = runTest {
        val client = HttpClient(
            MockEngine {
                respond(
                    content = """
                        {
                          "id":"resp_web_search",
                          "created_at":1780000003,
                          "model":"gpt-resp",
                          "output":[
                            {
                              "type":"web_search_call",
                              "id":"ws_1",
                              "status":"completed",
                              "action":{
                                "type":"search",
                                "query":"kotlin ai sdk",
                                "sources":[{"url":"https://example.com","title":"Example"}]
                              }
                            },
                            {"type":"message","id":"msg_1","role":"assistant","content":[{"type":"output_text","text":"done"}]}
                          ],
                          "usage":{"input_tokens":1,"output_tokens":2,"total_tokens":3}
                        }
                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        val provider = OpenResponses(client, OpenResponsesProviderSettings { url("https://api.test/v1/responses"); name("openresponses") })

        val result = provider.responses("gpt-resp").generate(LanguageModelCallParams {
            messages(listOf(UserMessage("hi")))
        })

        val toolCall = result.content.filterIsInstance<ContentPart.ToolCall>().single()
        assertEquals("ws_1", toolCall.toolCallId)
        assertEquals("web_search", toolCall.toolName)
        assertEquals(JsonObject(emptyMap()), toolCall.input)
        assertEquals(true, toolCall.providerExecuted)
        val toolResult = result.content.filterIsInstance<ContentPart.ToolResult>().single()
        assertEquals("ws_1", toolResult.toolCallId)
        assertEquals("web_search", toolResult.toolName)
        assertEquals(true, toolResult.providerExecuted)
        assertEquals("search", toolResult.output.jsonObject.getValue("action").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("kotlin ai sdk", toolResult.output.jsonObject.getValue("action").jsonObject.getValue("query").jsonPrimitive.content)
        assertEquals("https://example.com", toolResult.output.jsonObject.getValue("sources").jsonArray.single().jsonObject.getValue("url").jsonPrimitive.content)
        assertEquals("done", result.text)
        assertEquals(FinishReason.Stop, result.finishReason)
        assertEquals(1, result.usage.promptTokens)
        assertEquals(2, result.usage.completionTokens)
    }

    @Test
    fun `generate rejects function call missing call id`() = runTest {
        runOpenResponsesMissingCallId()
    }

    private suspend fun runOpenResponsesMissingCallId() {
        val client = HttpClient(
            MockEngine {
                respond(
                    content = """
                        {
                          "id":"resp_missing_call",
                          "created_at":1780000000,
                          "model":"gpt-resp",
                          "output":[{"type":"function_call","id":"fc_1","name":"search","arguments":"{}"}]
                        }
                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        val provider = OpenResponses(client, OpenResponsesProviderSettings { url("https://api.test/v1/responses"); name("openresponses") })

        val error = assertFailsWith<WireDecodeException> {
            provider.responses("gpt-resp").generate(LanguageModelCallParams {
    messages(listOf(UserMessage("hi")))
})
        }

        val message = error.message.orEmpty()
        assertTrue(message.contains("Open Responses"), message)
        assertTrue(message.contains("response output"), message)
        assertTrue(message.contains("call_id"), message)
    }

    @Test
    fun `generate rejects function call missing name`() = runTest {
        val client = HttpClient(
            MockEngine {
                respond(
                    content = """
                        {
                          "id":"resp_missing_name",
                          "created_at":1780000000,
                          "model":"gpt-resp",
                          "output":[{"type":"function_call","id":"fc_1","call_id":"call_1","arguments":"{}"}]
                        }
                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        val provider = OpenResponses(client, OpenResponsesProviderSettings { url("https://api.test/v1/responses"); name("openresponses") })

        val error = assertFailsWith<WireDecodeException> {
            provider.responses("gpt-resp").generate(LanguageModelCallParams {
    messages(listOf(UserMessage("hi")))
})
        }

        val message = error.message.orEmpty()
        assertTrue(message.contains("Open Responses"), message)
        assertTrue(message.contains("response output"), message)
        assertTrue(message.contains("name"), message)
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
        val provider = OpenResponses(client, OpenResponsesProviderSettings { url("https://api.test/v1/responses"); name("openresponses") })

        val events = drainAllItems(provider.languageModel("gpt-resp").stream(LanguageModelCallParams {
    messages(listOf(UserMessage("hi")))
}))

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
    fun `stream surfaces web search call as tool call and result`() = runTest {
        val client = HttpClient(
            MockEngine {
                respond(
                    content = """
                        data: {"type":"response.output_item.added","item":{"type":"web_search_call","id":"ws_1","status":"in_progress","action":{"type":"search","query":"streamed"}}}

                        data: {"type":"response.output_item.done","item":{"type":"web_search_call","id":"ws_1","status":"completed","action":{"type":"search","query":"streamed","sources":[{"url":"https://example.com"}]}}}

                        data: {"type":"response.completed","response":{"usage":{"input_tokens":1,"output_tokens":2,"total_tokens":3}}}

                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
                )
            },
        )
        val provider = OpenResponses(client, OpenResponsesProviderSettings { url("https://api.test/v1/responses"); name("openresponses") })

        val events = drainAllItems(provider.languageModel("gpt-resp").stream(LanguageModelCallParams {
            messages(listOf(UserMessage("hi")))
        }))

        val toolCall = events.filterIsInstance<StreamEvent.ToolCall>().single()
        assertEquals("ws_1", toolCall.toolCallId)
        assertEquals("web_search", toolCall.toolName)
        assertEquals(JsonObject(emptyMap()), toolCall.inputJson)
        val toolResult = events.filterIsInstance<StreamEvent.ToolResult>().single()
        assertEquals("ws_1", toolResult.toolCallId)
        assertEquals("web_search", toolResult.toolName)
        assertEquals("search", toolResult.outputJson.jsonObject.getValue("action").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("streamed", toolResult.outputJson.jsonObject.getValue("action").jsonObject.getValue("query").jsonPrimitive.content)
        val finish = events.filterIsInstance<StreamEvent.Finish>().single()
        assertEquals(FinishReason.Stop, finish.finishReason)
        assertEquals(1, finish.usage.promptTokens)
        assertEquals(2, finish.usage.completionTokens)
    }

    @Test
    fun `stream rejects function call arguments delta missing item id`() = runTest {
        val client = HttpClient(
            MockEngine {
                respond(
                    content = """
                        data: {"type":"response.output_item.added","item":{"type":"function_call","id":"fc_1","call_id":"call_1","name":"search","arguments":""}}

                        data: {"type":"response.function_call_arguments.delta","delta":"{\"q\""}

                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
                )
            },
        )
        val provider = OpenResponses(client, OpenResponsesProviderSettings { url("https://api.test/v1/responses"); name("openresponses") })

        val events = drainAllItems(provider.languageModel("gpt-resp").stream(LanguageModelCallParams {
    messages(listOf(UserMessage("hi")))
}))

        val error = events.filterIsInstance<StreamEvent.Error>().single()
        assertTrue(error.message.contains("item_id"), error.message)
        assertTrue(events.none { it is StreamEvent.ToolCall })
    }

    @Test
    fun `stream rejects final function call missing name or call id`() = runTest {
        val client = HttpClient(
            MockEngine {
                respond(
                    content = """
                        data: {"type":"response.output_item.done","item":{"type":"function_call","id":"fc_1","call_id":"call_1","arguments":"{}"}}

                        data: {"type":"response.output_item.done","item":{"type":"function_call","id":"fc_2","name":"search","arguments":"{}"}}

                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
                )
            },
        )
        val provider = OpenResponses(client, OpenResponsesProviderSettings { url("https://api.test/v1/responses"); name("openresponses") })

        val events = drainAllItems(provider.languageModel("gpt-resp").stream(LanguageModelCallParams {
    messages(listOf(UserMessage("hi")))
}))

        val errors = events.filterIsInstance<StreamEvent.Error>()
        assertEquals(2, errors.size)
        assertTrue(errors.any { it.message.contains("name") }, errors.toString())
        assertTrue(errors.any { it.message.contains("call_id") }, errors.toString())
        assertTrue(events.none { it is StreamEvent.ToolCall })
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
        val provider = OpenResponses(client, OpenResponsesProviderSettings { url("https://api.test/v1/responses"); name("openresponses") })

        val events = drainAllItems(provider.languageModel("gpt-resp").stream(LanguageModelCallParams {
    messages(listOf(UserMessage("hi")))
}))

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
        val provider = OpenResponses(client, OpenResponsesProviderSettings { url("https://api.test/v1/responses"); name("openresponses") })

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
            LanguageModelCallParams {
                messages(listOf(
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
                ))
            },
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
    fun `raw tool result colliding on a tag discriminator is forwarded verbatim`() = runTest {
        val seenBodies = mutableListOf<JsonObject>()
        val client = HttpClient(
            MockEngine { request ->
                seenBodies += Json.parseToJsonElement(requestBodyText(request)).jsonObject
                respond(
                    content = """{"id":"resp_4","created_at":1780000003,"model":"gpt-resp",""" +
                        """"output":[{"type":"message","id":"msg_4","role":"assistant",""" +
                        """"content":[{"type":"output_text","text":"done"}]}]}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        val provider = OpenResponses(client, OpenResponsesProviderSettings { url("https://api.test/v1/responses"); name("openresponses") })

        // modelVisible defaults to the tool's RAW output. This success object collides on the
        // "text" discriminator yet carries no `value` companion: the old path threw a hard
        // model-call failure here. It must now be forwarded to the model verbatim (regression
        // for OpenResponsesProvider:996).
        val rawModelVisible = buildJsonObject {
            put("type", JsonPrimitive("text"))
            put("message", JsonPrimitive("hi"))
        }
        provider.languageModel("gpt-resp").generate(
            LanguageModelCallParams {
                messages(listOf(
                    ModelMessage(
                        MessageRole.Tool,
                        listOf(
                            ContentPart.ToolResult(
                                toolCallId = "call_raw",
                                toolName = "lookup",
                                output = rawModelVisible,
                                modelVisible = rawModelVisible,
                            ),
                        ),
                    ),
                ))
            },
        )

        val output = seenBodies.single().getValue("input").jsonArray.single().jsonObject
        assertEquals("function_call_output", output.getValue("type").jsonPrimitive.content)
        assertEquals(rawModelVisible.toString(), output.getValue("output").jsonPrimitive.content)
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
        val provider = OpenResponses(
            client,
            OpenResponsesProviderSettings {
                url("https://api.test/v1/responses")
                name("openresponses")
                fileIdPrefixes(listOf("file-"))
            },
        )

        provider.languageModel("gpt-resp").generate(
            LanguageModelCallParams {
                messages(listOf(
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
                                providerMetadata = ProviderMetadata.Raw(JsonObject(mapOf(
                                    "openai" to buildJsonObject {
                                        put("file_id", JsonPrimitive("file-explicit"))
                                    },
                                ))),
                            ),
                        ),
                    ),
                ))
            },
        )

        val content = seenBodies.single()["input"]!!.jsonArray.single().jsonObject["content"]!!.jsonArray
        val payload = content[0].jsonObject
        val explicit = content[1].jsonObject
        assertEquals("data:application/pdf;base64,file-abc", payload["file_data"]!!.jsonPrimitive.content)
        assertEquals(null, payload["file_id"])
        assertEquals("file-explicit", explicit["file_id"]!!.jsonPrimitive.content)
        assertEquals(null, explicit["file_data"])
    }

    @Test
    fun `user URL media maps to URL fields and base64 media stays inline`() = runTest {
        val seenBodies = mutableListOf<JsonObject>()
        val client = HttpClient(
            MockEngine { request ->
                seenBodies += Json.parseToJsonElement(requestBodyText(request)).jsonObject
                respond(
                    content = """{"id":"resp_5","created_at":1780000004,"model":"gpt-resp","output":[{"type":"message","id":"msg_5","role":"assistant","content":[{"type":"output_text","text":"ok"}]}]}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        val provider = OpenResponses(client, OpenResponsesProviderSettings { url("https://api.test/v1/responses"); name("openresponses") })
        val imageUrl = "https://cdn.test/image.png"
        val fileUrl = "https://cdn.test/paper.pdf"

        provider.languageModel("gpt-resp").generate(
            LanguageModelCallParams {
                messages(listOf(
                    ModelMessage(
                        MessageRole.User,
                        listOf(
                            ContentPart.Image(mediaType = "image/png", url = imageUrl),
                            ContentPart.File(mediaType = "application/pdf", url = fileUrl, filename = "paper.pdf"),
                            ContentPart.Image(mediaType = "image/jpeg", base64 = "aW1n"),
                            ContentPart.File(mediaType = "text/plain", base64 = "ZG9j", filename = "note.txt"),
                        ),
                    ),
                ))
            },
        )

        val content = seenBodies.single().getValue("input")
            .jsonArray
            .single()
            .jsonObject
            .getValue("content")
            .jsonArray
        val urlImage = content[0].jsonObject
        assertEquals("input_image", urlImage.getValue("type").jsonPrimitive.content)
        assertEquals(imageUrl, urlImage.getValue("image_url").jsonPrimitive.content)
        assertEquals(null, urlImage["file_id"])
        val urlFile = content[1].jsonObject
        assertEquals("input_file", urlFile.getValue("type").jsonPrimitive.content)
        assertEquals(fileUrl, urlFile.getValue("file_url").jsonPrimitive.content)
        assertEquals(null, urlFile["file_data"])
        val base64Image = content[2].jsonObject
        assertEquals("input_image", base64Image.getValue("type").jsonPrimitive.content)
        assertEquals("data:image/jpeg;base64,aW1n", base64Image.getValue("image_url").jsonPrimitive.content)
        assertEquals(null, base64Image["file_url"])
        val base64File = content[3].jsonObject
        assertEquals("input_file", base64File.getValue("type").jsonPrimitive.content)
        assertEquals("data:text/plain;base64,ZG9j", base64File.getValue("file_data").jsonPrimitive.content)
        assertEquals("note.txt", base64File.getValue("filename").jsonPrimitive.content)
        assertEquals(null, base64File["file_url"])
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
