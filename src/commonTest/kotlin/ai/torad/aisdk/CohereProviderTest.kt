package ai.torad.aisdk
import ai.torad.aisdk.providers.COHERE_VERSION
import ai.torad.aisdk.providers.CohereProviderSettings

import io.ktor.http.HttpHeaders
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import ai.torad.aisdk.providers.Cohere
import kotlinx.serialization.json.JsonObject

class CohereProviderTest {
    @Test
    fun `chat model sends Cohere request shape and maps response content`() = runTest {
        val fixture = createTestServer(
            mutableMapOf(
                "https://cohere.test/v2/chat" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """
                            {
                              "generation_id":"gen-1",
                              "message":{
                                "role":"assistant",
                                "content":[
                                  {"type":"text","text":"Use the lookup tool."},
                                  {"type":"thinking","thinking":"Checking the supplied document."}
                                ],
                                "tool_plan":"Need one lookup.",
                                "tool_calls":[
                                  {
                                    "id":"call-1",
                                    "type":"function",
                                    "function":{"name":"lookup","arguments":"{\"city\":\"Paris\"}"}
                                  }
                                ],
                                "citations":[
                                  {
                                    "start":0,
                                    "end":3,
                                    "text":"Use",
                                    "type":"TEXT_CONTENT",
                                    "sources":[{"document":{"title":"Notes","text":"Paris notes"}}]
                                  }
                                ]
                              },
                              "finish_reason":"TOOL_CALL",
                              "usage":{
                                "billed_units":{"input_tokens":2,"output_tokens":3},
                                "tokens":{"input_tokens":11,"output_tokens":5}
                              }
                            }
                            """.trimIndent(),
                        ),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val provider = Cohere(
            fixture.httpClient(),
            CohereProviderSettings(
                apiKey = "key",
                baseURL = "https://cohere.test/v2",
                headers = mapOf("X-Provider" to "provider"),
            ),
        )
        val documentBase64 = Base64Codec.encode("Paris document".encodeToByteArray())

        val result = provider(ModelId("command-r-plus")).generate(
            LanguageModelCallParams(
                messages = listOf(
                    systemMessage("Use documents when available."),
                    ModelMessage(
                        MessageRole.User,
                        listOf(
                            ContentPart.Text("Where is Paris?"),
                            ContentPart.File(mediaType = "text/plain", base64 = documentBase64, filename = "notes.txt"),
                            ContentPart.Image(
                                mediaType = "image/png",
                                base64 = "iVBORw0=",
                                providerMetadata = ProviderMetadata.Raw(JsonObject(mapOf("cohere" to buildJsonObject { put("detail", JsonPrimitive("high")) }))),
                            ),
                        ),
                    ),
                ),
                tools = listOf(
                    LanguageModelTool(
                        name = "lookup",
                        description = "Look up a place.",
                        parametersSchemaJson = """{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}""",
                    ),
                    LanguageModelTool(
                        name = "providerSearch",
                        description = "Provider hosted search.",
                        parametersSchemaJson = """{"type":"object"}""",
                        providerExecuted = true,
                    ),
                ),
                toolChoice = ToolChoice.Specific("lookup"),
                maxOutputTokens = 256,
                temperature = 0.2f,
                topP = 0.9f,
                topK = 25,
                presencePenalty = 0.1f,
                frequencyPenalty = 0.2f,
                seed = 7,
                stopSequences = listOf("STOP"),
                responseFormat = ResponseFormat.Json(
                    schemaJson = buildJsonObject {
                        put("type", JsonPrimitive("object"))
                        put("additionalProperties", JsonPrimitive(false))
                    },
                ),
                providerOptions = ProviderOptions.Raw(JsonObject(mapOf(
                    "cohere" to buildJsonObject {
                        put("thinking", buildJsonObject { put("tokenBudget", JsonPrimitive(128)) })
                    },
                ))),
                headers = mapOf("X-Request" to "request"),
            ),
        )

        assertEquals("cohere.chat", provider(ModelId("command-r-plus")).provider)
        assertEquals("Use the lookup tool.", result.text)
        assertEquals(FinishReason.ToolCalls, result.finishReason)
        assertEquals("TOOL_CALL", result.rawFinishReason)
        assertEquals(11, result.usage.promptTokens)
        assertEquals(5, result.usage.completionTokens)
        assertEquals(11, result.usage.inputTokens.noCache)
        assertEquals("gen-1", result.response.id)
        assertEquals("lookup", result.toolCalls.single().toolName)
        assertEquals("Paris", result.toolCalls.single().input.jsonObject["city"]?.jsonPrimitive?.contentOrNull)
        assertEquals(
            listOf("Checking the supplied document.", "Need one lookup."),
            result.content.filterIsInstance<ContentPart.Reasoning>().map { it.text },
        )
        assertEquals("Notes", result.content.filterIsInstance<ContentPart.Source>().single().title)
        assertTrue(result.warnings.single().message.orEmpty().contains("providerSearch"))

        val request = fixture.calls.single()
        assertEquals("POST", request.requestMethod)
        assertEquals("https://cohere.test/v2/chat", request.requestUrl)
        assertEquals("Bearer key", request.requestHeaders.headerValue(HttpHeaders.Authorization))
        assertEquals("provider", request.requestHeaders.headerValue("X-Provider"))
        assertEquals("request", request.requestHeaders.headerValue("X-Request"))
        assertTrue(request.requestUserAgent.orEmpty().contains("ai-sdk/cohere/$COHERE_VERSION"))

        val body = request.requestBodyJson.jsonObject
        assertEquals("command-r-plus", body["model"]?.jsonPrimitive?.contentOrNull)
        assertEquals(256, body["max_tokens"]?.jsonPrimitive?.intOrNull)
        assertEquals(25, body["k"]?.jsonPrimitive?.intOrNull)
        assertEquals(7, body["seed"]?.jsonPrimitive?.intOrNull)
        assertEquals("STOP", body["stop_sequences"]?.jsonArray?.single()?.jsonPrimitive?.contentOrNull)
        assertEquals("json_object", body["response_format"]?.jsonObject?.get("type")?.jsonPrimitive?.contentOrNull)
        assertEquals(false, body["response_format"]?.jsonObject?.get("json_schema")?.jsonObject?.get("additionalProperties")?.jsonPrimitive?.contentOrNull?.toBoolean())
        assertEquals("REQUIRED", body["tool_choice"]?.jsonPrimitive?.contentOrNull)
        assertEquals("lookup", body["tools"]?.jsonArray?.single()?.jsonObject?.get("function")?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull)
        assertEquals("enabled", body["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.contentOrNull)
        assertEquals(128, body["thinking"]?.jsonObject?.get("token_budget")?.jsonPrimitive?.intOrNull)

        val messages = body["messages"]?.jsonArray.orEmpty()
        assertEquals("system", messages[0].jsonObject["role"]?.jsonPrimitive?.contentOrNull)
        assertEquals("user", messages[1].jsonObject["role"]?.jsonPrimitive?.contentOrNull)
        val userContent = messages[1].jsonObject["content"]?.jsonArray.orEmpty()
        assertEquals("Where is Paris?", userContent[0].jsonObject["text"]?.jsonPrimitive?.contentOrNull)
        assertEquals(
            "data:image/png;base64,iVBORw0=",
            userContent[1].jsonObject["image_url"]?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull,
        )
        assertEquals("high", userContent[1].jsonObject["image_url"]?.jsonObject?.get("detail")?.jsonPrimitive?.contentOrNull)
        val document = body["documents"]?.jsonArray?.single()?.jsonObject?.get("data")?.jsonObject
        assertEquals("Paris document", document?.get("text")?.jsonPrimitive?.contentOrNull)
        assertEquals("notes.txt", document?.get("title")?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `embedding model sends Cohere embed request and parses embeddings`() = runTest {
        val fixture = createTestServer(
            mutableMapOf(
                "https://cohere.test/v2/embed" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """
                            {
                              "id":"embed-1",
                              "texts":["alpha","beta"],
                              "embeddings":{"float":[[0.1,0.2],[0.3,0.4]]},
                              "meta":{"billed_units":{"input_tokens":10}},
                              "response_type":"embeddings_by_type"
                            }
                            """.trimIndent(),
                        ),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val provider = Cohere(
            fixture.httpClient(),
            CohereProviderSettings(apiKey = "key", baseURL = "https://cohere.test/v2"),
        )

        val result = provider.embedding(ModelId("embed-v4.0")).embed(
            EmbeddingModelCallParams(
                values = listOf("alpha", "beta"),
                truncate = false,
                providerOptions = ProviderOptions.Raw(JsonObject(mapOf(
                    "cohere" to buildJsonObject {
                        put("inputType", JsonPrimitive("classification"))
                        put("truncate", JsonPrimitive("START"))
                        put("outputDimension", JsonPrimitive(512))
                    },
                ))),
                headers = mapOf("X-Request" to "request"),
            ),
        )

        assertEquals("cohere.textEmbedding", provider.embeddingModel("embed-v4.0").provider)
        assertEquals(96, provider.embedding(ModelId("embed-v4.0")).maxEmbeddingsPerCall)
        assertEquals(true, provider.embedding(ModelId("embed-v4.0")).supportsParallelCalls)
        assertEquals(provider.embedding(ModelId("embed-v4.0")).modelId, provider.textEmbedding(ModelId("embed-v4.0")).modelId)
        assertEquals(provider.embedding(ModelId("embed-v4.0")).modelId, provider.textEmbeddingModel(ModelId("embed-v4.0")).modelId)
        assertEquals(listOf(listOf(0.1f, 0.2f), listOf(0.3f, 0.4f)), result.embeddings)
        assertEquals(10, result.usage.tokens)
        assertEquals("embed-1", result.response.id)
        val request = fixture.calls.single()
        assertEquals("Bearer key", request.requestHeaders.headerValue(HttpHeaders.Authorization))
        assertEquals("request", request.requestHeaders.headerValue("X-Request"))
        val body = request.requestBodyJson.jsonObject
        assertEquals("embed-v4.0", body["model"]?.jsonPrimitive?.contentOrNull)
        assertEquals("alpha", body["texts"]?.jsonArray?.first()?.jsonPrimitive?.contentOrNull)
        assertEquals("float", body["embedding_types"]?.jsonArray?.single()?.jsonPrimitive?.contentOrNull)
        assertEquals("classification", body["input_type"]?.jsonPrimitive?.contentOrNull)
        assertEquals("START", body["truncate"]?.jsonPrimitive?.contentOrNull)
        assertEquals(512, body["output_dimension"]?.jsonPrimitive?.intOrNull)
    }

    @Test
    fun `reranking model sends Cohere rerank request and maps scores`() = runTest {
        val fixture = createTestServer(
            mutableMapOf(
                "https://cohere.test/v2/rerank" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """
                            {
                              "id":"rerank-1",
                              "results":[
                                {"index":1,"relevance_score":0.91},
                                {"index":0,"relevance_score":0.12}
                              ],
                              "meta":{"billed_units":{"search_units":2}}
                            }
                            """.trimIndent(),
                        ),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val model = Cohere(
            fixture.httpClient(),
            CohereProviderSettings(apiKey = "key", baseURL = "https://cohere.test/v2"),
        ).reranking(ModelId("rerank-v3.5"))

        val result = model.rerank(
            RerankingParams(
                query = "capital",
                documents = listOf("Berlin", "Paris"),
                topN = 1,
                providerOptions = ProviderOptions.Raw(JsonObject(mapOf(
                    "cohere" to buildJsonObject {
                        put("maxTokensPerDoc", JsonPrimitive(64))
                        put("priority", JsonPrimitive(1))
                    },
                ))),
                headers = mapOf("X-Request" to "request"),
            ),
        )

        assertEquals("cohere.reranking", model.provider)
        assertEquals("Paris", result.results.first().value)
        assertEquals(0.91f, result.results.first().score)
        assertEquals(2, result.usage.promptTokens)
        assertEquals("rerank-1", result.response.id)
        val request = fixture.calls.single()
        assertEquals("Bearer key", request.requestHeaders.headerValue(HttpHeaders.Authorization))
        assertEquals("request", request.requestHeaders.headerValue("X-Request"))
        val body = request.requestBodyJson.jsonObject
        assertEquals("rerank-v3.5", body["model"]?.jsonPrimitive?.contentOrNull)
        assertEquals("capital", body["query"]?.jsonPrimitive?.contentOrNull)
        assertEquals("Berlin", body["documents"]?.jsonArray?.first()?.jsonPrimitive?.contentOrNull)
        assertEquals(1, body["top_n"]?.jsonPrimitive?.intOrNull)
        assertEquals(64, body["max_tokens_per_doc"]?.jsonPrimitive?.intOrNull)
        assertEquals(1, body["priority"]?.jsonPrimitive?.intOrNull)
    }

    @Test
    fun `stream emits tool input lifecycle before final tool call`() = runTest {
        val fixture = createTestServer(
            mutableMapOf(
                "https://cohere.test/v2/chat" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """
                            {
                              "generation_id":"gen-1",
                              "message":{
                                "role":"assistant",
                                "tool_calls":[
                                  {
                                    "id":"call-1",
                                    "type":"function",
                                    "function":{"name":"lookup","arguments":"{\"city\":\"Paris\"}"}
                                  }
                                ]
                              },
                              "finish_reason":"TOOL_CALL",
                              "usage":{"tokens":{"input_tokens":1,"output_tokens":1}}
                            }
                            """.trimIndent(),
                        ),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val model = Cohere(
            fixture.httpClient(),
            CohereProviderSettings(apiKey = "key", baseURL = "https://cohere.test/v2"),
        ).languageModel("command-r-plus")

        val events = model.stream(
            LanguageModelCallParams(
                messages = listOf(userMessage("where")),
                tools = listOf(
                    LanguageModelTool(
                        name = "lookup",
                        description = "Look up a place.",
                        parametersSchemaJson = """{"type":"object","properties":{"city":{"type":"string"}}}""",
                    ),
                ),
            ),
        ).toList()

        assertEquals(
            listOf(
                StreamEvent.StreamStart::class,
                StreamEvent.ToolInputStart::class,
                StreamEvent.ToolInputDelta::class,
                StreamEvent.ToolInputEnd::class,
                StreamEvent.ToolCall::class,
                StreamEvent.Finish::class,
            ),
            events.map { it::class },
        )
        val start = events[1] as StreamEvent.ToolInputStart
        val delta = events[2] as StreamEvent.ToolInputDelta
        val toolCall = events[4] as StreamEvent.ToolCall
        assertEquals("call-1", start.id)
        assertEquals("lookup", start.toolName)
        assertEquals("call-1", delta.id)
        assertEquals("Paris", Json.parseToJsonElement(delta.delta).jsonObject["city"]?.jsonPrimitive?.contentOrNull)
        assertEquals("Paris", toolCall.inputJson.jsonObject["city"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `unsupported Cohere surfaces and unconfigured singleton fail explicitly`() = runTest {
        val provider = Cohere(createTestServer(mutableMapOf()).httpClient(), CohereProviderSettings(apiKey = "key"))

        assertFailsWith<NoSuchModelError> { provider.imageModel("image") }

        val unsupportedFile = assertFailsWith<InvalidArgumentError> {
            provider(ModelId("command-r")).generate(
                LanguageModelCallParams(
                    messages = listOf(
                        ModelMessage(
                            MessageRole.User,
                            listOf(ContentPart.File(mediaType = "application/pdf", base64 = "AA==", filename = "paper.pdf")),
                        ),
                    ),
                ),
            )
        }
        assertTrue(unsupportedFile.message.orEmpty().contains("application/pdf"))
    }

    private fun Map<String, String>.headerValue(name: String): String? =
        entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}
