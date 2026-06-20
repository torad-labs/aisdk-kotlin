package ai.torad.aisdk
import ai.torad.aisdk.providers.HuggingFaceProviderSettings

import ai.torad.aisdk.testing.drainAllItems
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import ai.torad.aisdk.providers.HuggingFace

class HuggingFaceProviderTest {
    @Test
    fun `responses model sends Hugging Face request and maps output parts`() = runTest {
        val fixture = createTestServer(
            mutableMapOf(
                "https://hf.test/v1/responses" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """
                            {
                              "id":"resp-1",
                              "model":"Qwen/Qwen3-32B",
                              "object":"response",
                              "created_at":1780000000,
                              "status":"completed",
                              "error":null,
                              "instructions":null,
                              "max_output_tokens":256,
                              "metadata":null,
                              "tool_choice":"auto",
                              "tools":[],
                              "temperature":0.2,
                              "top_p":0.9,
                              "incomplete_details":{"reason":"tool_calls"},
                              "usage":{
                                "input_tokens":12,
                                "input_tokens_details":{"cached_tokens":4},
                                "output_tokens":10,
                                "output_tokens_details":{"reasoning_tokens":3},
                                "total_tokens":22
                              },
                              "output":[
                                {
                                  "type":"message",
                                  "id":"msg-1",
                                  "role":"assistant",
                                  "content":[
                                    {
                                      "type":"output_text",
                                      "text":"Paris is in France.",
                                      "annotations":[{"url":"https://example.com/paris","title":"Paris"}]
                                    }
                                  ]
                                },
                                {
                                  "type":"reasoning",
                                  "id":"rsn-1",
                                  "content":[{"type":"reasoning_text","text":"Use the cached city context."}]
                                },
                                {
                                  "type":"function_call",
                                  "id":"fc-1",
                                  "call_id":"call-1",
                                  "name":"lookup",
                                  "arguments":"{\"city\":\"Paris\"}",
                                  "output":"done"
                                },
                                {
                                  "type":"mcp_call",
                                  "id":"mcp-1",
                                  "name":"remoteLookup",
                                  "arguments":"{\"q\":\"Paris\"}",
                                  "output":"remote done"
                                },
                                {
                                  "type":"mcp_list_tools",
                                  "id":"mcp-tools-1",
                                  "server_label":"docs",
                                  "tools":[{"name":"search"}]
                                }
                              ],
                              "output_text":"Paris is in France."
                            }
                            """.trimIndent(),
                        ),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val provider = HuggingFace(
            fixture.httpClient(),
            HuggingFaceProviderSettings(
                apiKey = "key",
                baseURL = "https://hf.test/v1",
                headers = mapOf("X-Provider" to "provider"),
                generateId = { "generated-id" },
            ),
        )

        val result = provider.responses("Qwen/Qwen3-32B").generate(
            LanguageModelCallParams(
                messages = listOf(
                    systemMessage("System rules."),
                    ModelMessage(
                        MessageRole.User,
                        listOf(
                            ContentPart.Text("Where is Paris?"),
                            ContentPart.Image("image/png", "iVBORw0="),
                            ContentPart.File("image/*", "abc="),
                        ),
                    ),
                    assistantMessage("Previous answer."),
                    ModelMessage(
                        MessageRole.Assistant,
                        listOf(ContentPart.Reasoning("Previous reasoning.")),
                    ),
                    toolMessage("call-old", "old", JsonPrimitive("ignored")),
                ),
                tools = listOf(
                    LanguageModelTool(
                        name = "lookup",
                        description = "Lookup city details.",
                        parametersSchemaJson = objectSchema("city").toString(),
                    ),
                    LanguageModelTool(
                        name = "providerHosted",
                        description = "Provider hosted tool.",
                        parametersSchemaJson = """{"type":"object"}""",
                        providerExecuted = true,
                    ),
                ),
                toolChoice = ToolChoice.Specific("lookup"),
                temperature = 0.2f,
                topP = 0.9f,
                topK = 10,
                maxOutputTokens = 256,
                stopSequences = listOf("STOP"),
                seed = 99,
                presencePenalty = 0.1f,
                frequencyPenalty = 0.2f,
                responseFormat = ResponseFormat.Json(
                    schemaName = "Answer",
                    schemaDescription = "Structured answer.",
                    schemaJson = objectSchema("answer"),
                ),
                providerOptions = ProviderOptions.Raw(JsonObject(mapOf(
                    "huggingface" to buildJsonObject {
                        put(
                            "metadata",
                            buildJsonObject { put("trace", JsonPrimitive("abc")) },
                        )
                        put("instructions", JsonPrimitive("Provider instructions."))
                        put("strictJsonSchema", JsonPrimitive(true))
                        put("reasoningEffort", JsonPrimitive("medium"))
                    },
                ))),
                headers = mapOf("X-Request" to "request"),
            ),
        )

        assertEquals("huggingface.responses", provider("Qwen/Qwen3-32B").provider)
        assertEquals("Paris is in France.", result.text)
        assertEquals(FinishReason.ToolCalls, result.finishReason)
        assertEquals("tool_calls", result.rawFinishReason)
        assertEquals(12, result.usage.promptTokens)
        assertEquals(4, result.usage.inputTokens.cacheRead)
        assertEquals(8, result.usage.inputTokens.noCache)
        assertEquals(10, result.usage.completionTokens)
        assertEquals(7, result.usage.outputTokens.text)
        assertEquals(3, result.usage.outputTokens.reasoning)
        assertEquals("resp-1", result.response.id)
        assertEquals(1_780_000_000_000, result.response.timestampMillis)
        assertEquals("Qwen/Qwen3-32B", result.response.modelId)
        assertEquals("resp-1", result.providerMetadata["huggingface"]?.jsonObject?.get("responseId")?.jsonPrimitive?.contentOrNull)
        assertEquals("Use the cached city context.", result.content.filterIsInstance<ContentPart.Reasoning>().single().text)
        assertEquals("Paris", result.content.filterIsInstance<ContentPart.Source>().single().title)
        assertEquals(listOf("lookup", "remoteLookup", "list_tools"), result.toolCalls.map { it.toolName })
        assertEquals("Paris", result.toolCalls[0].input.jsonObject["city"]?.jsonPrimitive?.contentOrNull)
        assertEquals(true, result.toolCalls[1].providerMetadata?.get("huggingface")?.jsonObject?.get("providerExecuted")?.jsonPrimitive?.booleanOrNull)
        assertEquals("docs", result.toolCalls[2].input.jsonObject["server_label"]?.jsonPrimitive?.contentOrNull)
        assertEquals(3, result.content.filterIsInstance<ContentPart.ToolResult>().size)
        assertEquals(
            setOf("topK", "seed", "presencePenalty", "frequencyPenalty", "stopSequences", "tool messages", "provider-defined tool providerHosted"),
            result.warnings.mapNotNull { it.message }.toSet(),
        )

        val request = fixture.calls.single()
        assertEquals("POST", request.requestMethod)
        assertEquals("https://hf.test/v1/responses", request.requestUrl)
        assertEquals("Bearer key", request.requestHeaders.headerValue(HttpHeaders.Authorization))
        assertEquals("provider", request.requestHeaders.headerValue("X-Provider"))
        assertEquals("request", request.requestHeaders.headerValue("X-Request"))
        assertNull(request.requestUserAgent)

        val body = request.requestBodyJson.jsonObject
        assertEquals("Qwen/Qwen3-32B", body["model"]?.jsonPrimitive?.contentOrNull)
        assertEquals(false, body["stream"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(256, body["max_output_tokens"]?.jsonPrimitive?.intOrNull)
        assertEquals("Provider instructions.", body["instructions"]?.jsonPrimitive?.contentOrNull)
        assertEquals("abc", body["metadata"]?.jsonObject?.get("trace")?.jsonPrimitive?.contentOrNull)
        assertEquals("medium", body["reasoning"]?.jsonObject?.get("effort")?.jsonPrimitive?.contentOrNull)
        val format = body["text"]?.jsonObject?.get("format")?.jsonObject
        assertEquals("json_schema", format?.get("type")?.jsonPrimitive?.contentOrNull)
        assertEquals(true, format?.get("strict")?.jsonPrimitive?.booleanOrNull)
        assertEquals("Answer", format?.get("name")?.jsonPrimitive?.contentOrNull)
        assertEquals("Structured answer.", format?.get("description")?.jsonPrimitive?.contentOrNull)
        assertEquals("lookup", body["tools"]?.jsonArray?.single()?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull)
        assertEquals(
            "lookup",
            body["tool_choice"]?.jsonObject?.get("function")?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull,
        )

        val input = body["input"]?.jsonArray.orEmpty()
        assertEquals("system", input[0].jsonObject["role"]?.jsonPrimitive?.contentOrNull)
        assertEquals("System rules.", input[0].jsonObject["content"]?.jsonPrimitive?.contentOrNull)
        val userContent = input[1].jsonObject["content"]?.jsonArray.orEmpty()
        assertEquals("input_text", userContent[0].jsonObject["type"]?.jsonPrimitive?.contentOrNull)
        assertEquals("data:image/png;base64,iVBORw0=", userContent[1].jsonObject["image_url"]?.jsonPrimitive?.contentOrNull)
        assertEquals("data:image/jpeg;base64,abc=", userContent[2].jsonObject["image_url"]?.jsonPrimitive?.contentOrNull)
        assertEquals("assistant", input[2].jsonObject["role"]?.jsonPrimitive?.contentOrNull)
        assertEquals("Previous answer.", input[2].jsonObject["content"]?.jsonArray?.single()?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull)
        assertEquals("Previous reasoning.", input[3].jsonObject["content"]?.jsonArray?.single()?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `stream maps Hugging Face response events`() = runTest {
        val fixture = createTestServer(
            mutableMapOf(
                "https://hf.test/v1/responses" to UrlHandler(
                    UrlResponse.StreamChunks(
                        listOf(
                            """
                            data: {"type":"response.created","response":{"id":"resp-stream","created_at":1780000001,"model":"meta-llama/Llama-3.1-8B-Instruct"}}

                            data: {"type":"response.output_item.added","output_index":0,"item":{"type":"reasoning","id":"rsn-1"},"sequence_number":1}

                            data: {"type":"response.reasoning_summary_text.delta","item_id":"rsn-1","output_index":0,"content_index":0,"delta":"thinking","sequence_number":2}

                            data: {"type":"response.reasoning_summary_text.done","item_id":"rsn-1","output_index":0,"content_index":0,"text":"thinking","sequence_number":3}

                            data: {"type":"response.output_item.added","output_index":1,"item":{"type":"message","id":"msg-1","role":"assistant"},"sequence_number":4}

                            data: {"type":"response.output_text.delta","item_id":"msg-1","output_index":1,"content_index":0,"delta":"hello","sequence_number":5}

                            data: {"type":"response.output_item.done","output_index":1,"item":{"type":"message","id":"msg-1","role":"assistant"},"sequence_number":6}

                            data: {"type":"response.output_item.added","output_index":2,"item":{"type":"function_call","id":"fc-1","call_id":"call-1","name":"lookup","arguments":""},"sequence_number":7}

                            data: {"type":"response.output_item.done","output_index":2,"item":{"type":"function_call","id":"fc-1","call_id":"call-1","name":"lookup","arguments":"{\"city\":\"Paris\"}","output":"ok"},"sequence_number":8}

                            data: {"type":"response.completed","response":{"id":"resp-stream","model":"meta-llama/Llama-3.1-8B-Instruct","object":"response","created_at":1780000001,"status":"completed","error":null,"instructions":null,"max_output_tokens":null,"metadata":null,"tool_choice":"auto","tools":[],"temperature":1,"top_p":1,"incomplete_details":{"reason":"stop"},"usage":{"input_tokens":1,"input_tokens_details":{"cached_tokens":0},"output_tokens":2,"output_tokens_details":{"reasoning_tokens":1},"total_tokens":3},"output":[]}}

                            """.trimIndent(),
                        ),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val provider = HuggingFace(
            fixture.httpClient(),
            HuggingFaceProviderSettings(baseURL = "https://hf.test/v1"),
        )

        val events = drainAllItems(
            provider.responses("meta-llama/Llama-3.1-8B-Instruct").stream(
                LanguageModelCallParams(
                    messages = listOf(userMessage("hi")),
                    tools = listOf(LanguageModelTool("lookup", "Lookup.", objectSchema("city").toString())),
                    toolChoice = ToolChoice.Required,
                ),
            ),
        )

        assertIs<StreamEvent.StreamStart>(events.first())
        assertTrue(events.any { it is StreamEvent.ResponseMetadata && it.id == "resp-stream" })
        assertTrue(events.any { it is StreamEvent.ReasoningDelta && it.text == "thinking" })
        assertTrue(events.any { it is StreamEvent.TextDelta && it.text == "hello" })
        val toolCall = events.filterIsInstance<StreamEvent.ToolCall>().single()
        assertEquals("lookup", toolCall.toolName)
        assertEquals("Paris", toolCall.inputJson.jsonObject["city"]?.jsonPrimitive?.contentOrNull)
        val toolResult = events.filterIsInstance<StreamEvent.ToolResult>().single()
        assertEquals(JsonPrimitive("ok"), toolResult.outputJson)
        val finish = events.filterIsInstance<StreamEvent.Finish>().single()
        assertEquals(FinishReason.Stop, finish.finishReason)
        assertEquals(1, finish.usage.promptTokens)
        assertEquals(1, finish.usage.outputTokens.reasoning)
        assertEquals("resp-stream", finish.providerMetadata?.get("huggingface")?.jsonObject?.get("responseId")?.jsonPrimitive?.contentOrNull)

        val request = fixture.calls.single()
        assertEquals(true, request.requestBodyJson.jsonObject["stream"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("required", request.requestBodyJson.jsonObject["tool_choice"]?.jsonPrimitive?.contentOrNull)
        assertEquals("text/event-stream", request.requestHeaders.headerValue(HttpHeaders.Accept))
    }

    @Test
    fun `stream surfaces Hugging Face missing output item as error`() = runTest {
        val fixture = createTestServer(
            mutableMapOf(
                "https://hf.test/v1/responses" to UrlHandler(
                    UrlResponse.StreamChunks(listOf("""data: {"type":"response.output_item.done"}""")),
                ),
            ),
        )
        fixture.server.start()
        val provider = HuggingFace(fixture.httpClient(), HuggingFaceProviderSettings(baseURL = "https://hf.test/v1"))

        val events = drainAllItems(provider.responses("meta-llama/Llama-3.1-8B-Instruct").stream(LanguageModelCallParams(messages = listOf(userMessage("hi")))))

        val error = events.filterIsInstance<StreamEvent.Error>().single()
        assertTrue(error.message.contains("missing item"))
    }

    @Test
    fun `unsupported embedding and image factories match upstream guidance`() {
        val fixture = createTestServer(mutableMapOf())
        val provider = HuggingFace(fixture.httpClient())

        val embedding = assertFailsWith<NoSuchModelError> {
            provider.embeddingModel("embed")
        }
        assertTrue(embedding.message.orEmpty().contains("does not support text embeddings"))
        val textEmbedding = assertFailsWith<NoSuchModelError> {
            provider.textEmbeddingModel("embed")
        }
        assertTrue(textEmbedding.message.orEmpty().contains("Inference API directly for embeddings"))
        val image = assertFailsWith<NoSuchModelError> {
            provider.imageModel("image")
        }
        assertTrue(image.message.orEmpty().contains("does not support image generation"))
    }

    @Test
    fun `tool choice none is omitted and non-image files are rejected`() = runTest {
        val fixture = createTestServer(
            mutableMapOf(
                "https://hf.test/v1/responses" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """
                            {
                              "id":"resp-2",
                              "model":"model",
                              "object":"response",
                              "created_at":1780000002,
                              "status":"completed",
                              "error":null,
                              "instructions":null,
                              "max_output_tokens":null,
                              "metadata":null,
                              "tool_choice":"auto",
                              "tools":[],
                              "temperature":1,
                              "top_p":1,
                              "usage":null,
                              "output":[{"type":"message","id":"msg-2","role":"assistant","content":[{"type":"output_text","text":"ok"}]}],
                              "output_text":"ok"
                            }
                            """.trimIndent(),
                        ),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val provider = HuggingFace(
            fixture.httpClient(),
            HuggingFaceProviderSettings(baseURL = "https://hf.test/v1"),
        )

        val result = provider("model").generate(
            LanguageModelCallParams(
                messages = listOf(userMessage("hi")),
                tools = listOf(LanguageModelTool("lookup", "Lookup.", objectSchema("q").toString())),
                toolChoice = ToolChoice.None,
            ),
        )

        assertEquals("ok", result.text)
        assertEquals(0, result.usage.totalTokens)
        assertTrue("tool_choice" !in fixture.calls.single().requestBodyJson.jsonObject)

        val error = assertFailsWith<AiSdkException> {
            provider("model").generate(
                LanguageModelCallParams(
                    messages = listOf(
                        ModelMessage(
                            MessageRole.User,
                            listOf(ContentPart.File("text/plain", "dGV4dA==", "notes.txt")),
                        ),
                    ),
                ),
            )
        }
        assertTrue(error.message.orEmpty().contains("text/plain"))
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
