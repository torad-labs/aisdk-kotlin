@file:OptIn(LowLevelLanguageModelApi::class)

package ai.torad.aisdk
import ai.torad.aisdk.providers.GOOGLE_VERSION
import ai.torad.aisdk.providers.GoogleGenerativeAI
import ai.torad.aisdk.providers.GoogleGenerativeAIProviderSettings
import ai.torad.aisdk.testing.FlowDrain.drainAllItems
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@Suppress("LargeClass")
class GoogleProviderTest {
    @Test
    fun `language model maps Gemini request response tools sources and metadata`() = runTest {
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://google.test/v1beta/models/gemini-2.5-flash:generateContent" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """
                            {
                              "candidates":[
                                {
                                  "content":{
                                    "role":"model",
                                    "parts":[
                                      {"text":"thinking","thought":true,"thoughtSignature":"sig"},
                                      {"text":"Hello"},
                                      {"functionCall":{"id":"call-1","name":"lookup","args":{"city":"Paris"}}},
                                      {"inlineData":{"mimeType":"image/png","data":"aW1hZ2U="}}
                                    ]
                                  },
                                  "finishReason":"STOP",
                                  "groundingMetadata":{
                                    "groundingChunks":[{"web":{"uri":"https://example.com","title":"Example"}}]
                                  },
                                  "safetyRatings":[{"category":"HARM_CATEGORY_HATE_SPEECH","probability":"NEGLIGIBLE"}]
                                }
                              ],
                              "usageMetadata":{
                                "promptTokenCount":3,
                                "candidatesTokenCount":5,
                                "thoughtsTokenCount":2,
                                "totalTokenCount":8
                              },
                              "promptFeedback":{"blockReason":"BLOCK_REASON_UNSPECIFIED"}
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
                headers(mapOf("X-Provider" to "provider"))
            },
        )

        val result = provider(ModelId("gemini-2.5-flash")).generate(
            LanguageModelCallParams {
                messages(
                    listOf(
                        SystemMessage("Follow policy."),
                        ModelMessage(
                            MessageRole.User,
                            listOf(
                                ContentPart.Text("Hello"),
                                ContentPart.Image("image/png", "aW1hZ2U="),
                            ),
                        ),
                    )
                )
                tools(
                    listOf(
                        LanguageModelTool("lookup", "Lookup a city.", objectSchema("city").toString()),
                        LanguageModelTool("google_search", "Search.", """{"type":"object"}""", providerExecuted = true),
                    )
                )
                toolChoice(ToolChoice.Specific("lookup"))
                temperature(0.3f)
                topP(0.8f)
                topK(40)
                maxOutputTokens(64)
                responseFormat(ResponseFormat.Json(schemaJson = objectSchema("answer")))
                providerOptions(
                    ProviderOptions.Raw(
                        JsonObject(
                            mapOf(
                                "google" to buildJsonObject {
                                    put("responseModalities", Json.parseToJsonElement("""["TEXT"]"""))
                                    put("thinkingConfig", buildJsonObject { put("thinkingBudget", JsonPrimitive(128)) })
                                    put("serviceTier", JsonPrimitive("priority"))
                                },
                            )
                        )
                    )
                )
                headers(mapOf("X-Request" to "request"))
            },
        )

        assertEquals("Hello", result.text)
        assertEquals(FinishReason.ToolCalls, result.finishReason)
        assertEquals("call-1", result.toolCalls.single().toolCallId)
        assertEquals("Paris", result.toolCalls.single().input.jsonObject["city"]?.jsonPrimitive?.contentOrNull)
        assertEquals("thinking", result.content.filterIsInstance<ContentPart.Reasoning>().single().text)
        assertEquals("image/png", result.content.filterIsInstance<ContentPart.File>().single().mediaType)
        assertEquals("https://example.com", result.content.filterIsInstance<ContentPart.Source>().single().url)
        assertEquals(3, result.usage.promptTokens)
        // outputTokens.total = candidatesTokenCount(5) + thoughtsTokenCount(2) (upstream parity)
        assertEquals(7, result.usage.completionTokens)
        assertEquals(5, result.usage.outputTokens.text)
        assertEquals(2, result.usage.outputTokens.reasoning)
        assertEquals(
            "BLOCK_REASON_UNSPECIFIED",
            result.providerMetadata.toMap()["google"]?.jsonObject?.get(
                "promptFeedback"
            )?.jsonObject?.get("blockReason")?.jsonPrimitive?.contentOrNull
        )

        val request = fixture.calls.single()
        assertEquals("key", request.requestHeaders.headerValue("x-goog-api-key"))
        assertEquals("provider", request.requestHeaders.headerValue("X-Provider"))
        assertEquals("request", request.requestHeaders.headerValue("X-Request"))
        assertTrue(request.requestUserAgent.orEmpty().contains("ai-sdk/google/$GOOGLE_VERSION"))
        val body = request.requestBodyJson.jsonObject
        assertEquals(
            "Follow policy.",
            body["systemInstruction"]?.jsonObject?.get(
                "parts"
            )?.jsonArray?.single()?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
        )
        assertEquals(
            "application/json",
            body["generationConfig"]?.jsonObject?.get("responseMimeType")?.jsonPrimitive?.contentOrNull
        )
        assertEquals(64, body["generationConfig"]?.jsonObject?.get("maxOutputTokens")?.jsonPrimitive?.intOrNull)
        assertEquals(
            128,
            body["generationConfig"]?.jsonObject?.get(
                "thinkingConfig"
            )?.jsonObject?.get("thinkingBudget")?.jsonPrimitive?.intOrNull
        )
        assertEquals("priority", body["serviceTier"]?.jsonPrimitive?.contentOrNull)
        val tools = body["tools"]?.jsonArray.orEmpty()
        val decl = tools[0].jsonObject["functionDeclarations"]?.jsonArray?.single()?.jsonObject
        assertEquals("lookup", decl?.get("name")?.jsonPrimitive?.contentOrNull)
        // The tool parameters schema must be stripped of JSON-Schema keys Google rejects.
        val params = decl?.get("parameters")?.jsonObject
        assertTrue(params != null && "\$schema" !in params, "\$schema stripped from tool schema")
        assertTrue("additionalProperties" !in params, "additionalProperties stripped from tool schema")
        assertTrue("title" !in params, "title stripped from tool schema")
        assertEquals(
            "lookup",
            body["toolConfig"]?.jsonObject?.get(
                "functionCallingConfig"
            )?.jsonObject?.get("allowedFunctionNames")?.jsonArray?.single()?.jsonPrimitive?.contentOrNull
        )
    }

    @Test
    fun `language model sends URL media as fileData and base64 media as inlineData`() = runTest {
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://google.test/v1beta/models/gemini-2.5-flash:generateContent" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """
                            {
                              "candidates":[{"content":{"role":"model","parts":[{"text":"ok"}]},"finishReason":"STOP"}]
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
                apiKey("key");
                baseURL("https://google.test/v1beta")
            },
        )
        val imageUrl = "https://generativelanguage.googleapis.com/v1beta/files/image-1"
        val fileUrl = "gs://bucket/doc.pdf"

        provider(ModelId("gemini-2.5-flash")).generate(
            LanguageModelCallParams {
                messages(
                    listOf(
                        ModelMessage(
                            MessageRole.User,
                            listOf(
                                ContentPart.Text("inspect media"),
                                ContentPart.Image(mediaType = "image/png", url = imageUrl),
                                ContentPart.File(mediaType = "application/pdf", url = fileUrl),
                                ContentPart.Image(mediaType = "image/jpeg", base64 = "aW1n"),
                                ContentPart.File(mediaType = "text/plain", base64 = "ZG9j"),
                            ),
                        ),
                    )
                )
            },
        )

        val parts = fixture.calls.single().requestBodyJson.jsonObject["contents"]
            ?.jsonArray
            ?.single()
            ?.jsonObject
            ?.get("parts")
            ?.jsonArray
            .orEmpty()
        val urlImage = parts[1].jsonObject
        assertEquals(imageUrl, urlImage["fileData"]?.jsonObject?.get("fileUri")?.jsonPrimitive?.contentOrNull)
        assertEquals("image/png", urlImage["fileData"]?.jsonObject?.get("mimeType")?.jsonPrimitive?.contentOrNull)
        assertEquals(null, urlImage["inlineData"])
        val urlFile = parts[2].jsonObject
        assertEquals(fileUrl, urlFile["fileData"]?.jsonObject?.get("fileUri")?.jsonPrimitive?.contentOrNull)
        assertEquals("application/pdf", urlFile["fileData"]?.jsonObject?.get("mimeType")?.jsonPrimitive?.contentOrNull)
        assertEquals(null, urlFile["inlineData"])
        val base64Image = parts[3].jsonObject
        assertEquals("aW1n", base64Image["inlineData"]?.jsonObject?.get("data")?.jsonPrimitive?.contentOrNull)
        assertEquals("image/jpeg", base64Image["inlineData"]?.jsonObject?.get("mimeType")?.jsonPrimitive?.contentOrNull)
        assertEquals(null, base64Image["fileData"])
        val base64File = parts[4].jsonObject
        assertEquals("ZG9j", base64File["inlineData"]?.jsonObject?.get("data")?.jsonPrimitive?.contentOrNull)
        assertEquals("text/plain", base64File["inlineData"]?.jsonObject?.get("mimeType")?.jsonPrimitive?.contentOrNull)
        assertEquals(null, base64File["fileData"])
    }

    @Test
    fun `language model honors disabled Google structured outputs`() = runTest {
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://google.test/v1beta/models/gemini-2.5-flash:generateContent" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """
                            {
                              "candidates":[{"content":{"role":"model","parts":[{"text":"{\"answer\":\"ok\"}"}]},"finishReason":"STOP"}]
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
                apiKey("key");
                baseURL("https://google.test/v1beta")
            },
        )

        provider.chat(ModelId("gemini-2.5-flash")).generate(
            LanguageModelCallParams {
                messages(listOf(UserMessage("json please")))
                responseFormat(ResponseFormat.Json(schemaJson = objectSchema("answer")))
                providerOptions(
                    ProviderOptions.Raw(
                        JsonObject(
                            mapOf(
                                "google" to buildJsonObject { put("structuredOutputs", JsonPrimitive(false)) },
                            )
                        )
                    )
                )
            },
        )

        val generationConfig = fixture.calls.single().requestBodyJson.jsonObject["generationConfig"]?.jsonObject
        assertEquals("application/json", generationConfig?.get("responseMimeType")?.jsonPrimitive?.contentOrNull)
        assertEquals(null, generationConfig?.get("responseSchema"))
    }

    @Test
    fun `language model forwards Google built in tool args`() = runTest {
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://google.test/v1beta/models/gemini-2.5-flash:generateContent" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """
                            {
                              "candidates":[{"content":{"role":"model","parts":[{"text":"ok"}]},"finishReason":"STOP"}]
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
                apiKey("key");
                baseURL("https://google.test/v1beta")
            },
        )

        provider.chat(ModelId("gemini-2.5-flash")).generate(
            LanguageModelCallParams {
                messages(listOf(UserMessage("search")))
                tools(
                    listOf(
                        LanguageModelTool(
                            name = "file_search",
                            description = "File search.",
                            parametersSchemaJson = """{"type":"object"}""",
                            providerExecuted = true,
                            metadata = mapOf(
                                "args" to buildJsonObject {
                                    put("fileSearchStoreNames", buildJsonArray { add(JsonPrimitive("fileSearchStores/store-1")) })
                                    put("topK", JsonPrimitive(7))
                                    put("metadataFilter", JsonPrimitive("author=\"Ada\""))
                                },
                            ),
                        ),
                        LanguageModelTool(
                            name = "vertex_rag_store",
                            description = "RAG store.",
                            parametersSchemaJson = """{"type":"object"}""",
                            providerExecuted = true,
                            metadata = mapOf(
                                "args" to buildJsonObject {
                                    put("ragCorpus", JsonPrimitive("projects/p/locations/us-central1/ragCorpora/corpus-1"))
                                    put("topK", JsonPrimitive(3))
                                },
                            ),
                        ),
                    )
                )
            },
        )

        val tools = fixture.calls.single().requestBodyJson.jsonObject["tools"]?.jsonArray.orEmpty()
        val fileSearch = tools.firstNotNullOf { it.jsonObject["fileSearch"]?.jsonObject }
        assertEquals(
            "fileSearchStores/store-1",
            fileSearch["fileSearchStoreNames"]?.jsonArray?.single()?.jsonPrimitive?.contentOrNull,
        )
        assertEquals(7, fileSearch["topK"]?.jsonPrimitive?.intOrNull)
        assertEquals("author=\"Ada\"", fileSearch["metadataFilter"]?.jsonPrimitive?.contentOrNull)

        val retrieval = tools.firstNotNullOf { it.jsonObject["retrieval"]?.jsonObject }
        val vertexRagStore = retrieval["vertex_rag_store"]?.jsonObject
        assertEquals(
            "projects/p/locations/us-central1/ragCorpora/corpus-1",
            vertexRagStore?.get("rag_resources")?.jsonObject?.get("rag_corpus")?.jsonPrimitive?.contentOrNull,
        )
        assertEquals(3, vertexRagStore?.get("similarity_top_k")?.jsonPrimitive?.intOrNull)
        assertEquals(null, retrieval["vertexRagStore"])
    }

    @Test
    fun `stream maps Gemini SSE text reasoning tool calls files and finish`() = runTest {
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://google.test/v1beta/models/gemini-2.5-flash:streamGenerateContent?alt=sse" to UrlHandler(
                    UrlResponse.StreamChunks(
                        listOf(
                            """
                            data: {"candidates":[{"content":{"parts":[{"text":"think","thought":true},{"text":"hello"},{"functionCall":{"id":"call-1","name":"lookup","args":{"city":"Paris"}}},{"inlineData":{"mimeType":"image/png","data":"aW1hZ2U="}}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":1,"candidatesTokenCount":2}}
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
                apiKey("key");
                baseURL("https://google.test/v1beta")
            },
        )

        val events = drainAllItems(
            provider.chat(ModelId("gemini-2.5-flash")).stream(
                LanguageModelCallParams {
                    messages(listOf(UserMessage("hi")))
                }
            ),
        )

        assertIs<StreamEvent.StreamStart>(events.first())
        assertTrue(events.any { it is StreamEvent.ReasoningDelta && it.text == "think" })
        assertTrue(events.any { it is StreamEvent.TextDelta && it.text == "hello" })
        val toolCall = events.filterIsInstance<StreamEvent.ToolCall>().single()
        assertEquals("lookup", toolCall.toolName)
        assertEquals("Paris", toolCall.inputJson.jsonObject["city"]?.jsonPrimitive?.contentOrNull)
        assertEquals("image/png", events.filterIsInstance<StreamEvent.FilePart>().single().mediaType)
        val finish = events.filterIsInstance<StreamEvent.Finish>().single()
        assertEquals(FinishReason.ToolCalls, finish.finishReason)
        assertEquals(1, finish.usage.promptTokens)
        assertEquals(2, finish.usage.completionTokens)
        assertEquals("text/event-stream", fixture.calls.single().requestHeaders.headerValue(HttpHeaders.Accept))
    }

    @Test
    fun `stream finish carries Google metadata and de duplicates sources`() = runTest {
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://google.test/v1beta/models/gemini-2.5-flash:streamGenerateContent?alt=sse" to UrlHandler(
                    UrlResponse.StreamChunks(
                        listOf(
                            """data: {"candidates":[{"content":{"parts":[{"text":"hello "}]},"groundingMetadata":{"groundingChunks":[{"web":{"uri":"https://source.example.com","title":"Source"}}]},"safetyRatings":[{"category":"HARM_CATEGORY_HATE_SPEECH","probability":"NEGLIGIBLE"}]}],"promptFeedback":{"blockReason":"BLOCK_REASON_UNSPECIFIED"}}""" + "\n\n",
                            """data: {"candidates":[{"content":{"parts":[{"text":"world"}]},"groundingMetadata":{"groundingChunks":[{"web":{"uri":"https://source.example.com","title":"Source"}}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":4,"candidatesTokenCount":2,"thoughtsTokenCount":1}}""" + "\n\n",
                        ),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val provider = GoogleGenerativeAI(
            fixture.httpClient(),
            GoogleGenerativeAIProviderSettings {
                apiKey("key");
                baseURL("https://google.test/v1beta")
            },
        )

        val events = drainAllItems(
            provider.chat(ModelId("gemini-2.5-flash")).stream(
                LanguageModelCallParams {
                    messages(listOf(UserMessage("hi")))
                }
            )
        )

        val sources = events.filterIsInstance<StreamEvent.SourcePart>()
        assertEquals(1, sources.size)
        assertEquals("https://source.example.com", sources.single().url)
        val finish = events.filterIsInstance<StreamEvent.Finish>().single()
        val google = finish.providerMetadata.toMap()["google"]?.jsonObject
        assertEquals(
            "BLOCK_REASON_UNSPECIFIED",
            google?.get("promptFeedback")?.jsonObject?.get("blockReason")?.jsonPrimitive?.contentOrNull
        )
        assertEquals(
            "https://source.example.com",
            google?.get(
                "groundingMetadata"
            )?.jsonObject?.get(
                "groundingChunks"
            )?.jsonArray?.single()?.jsonObject?.get("web")?.jsonObject?.get("uri")?.jsonPrimitive?.contentOrNull
        )
        assertEquals(
            "NEGLIGIBLE",
            google?.get(
                "safetyRatings"
            )?.jsonArray?.single()?.jsonObject?.get("probability")?.jsonPrimitive?.contentOrNull
        )
        assertEquals(4, google?.get("usageMetadata")?.jsonObject?.get("promptTokenCount")?.jsonPrimitive?.intOrNull)
        assertEquals(3, finish.usage.completionTokens)
    }

    @Test
    fun `language model reports length when max tokens response contains tool calls`() = runTest {
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://google.test/v1beta/models/gemini-2.5-flash:generateContent" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """
                            {
                              "candidates":[
                                {
                                  "content":{
                                    "role":"model",
                                    "parts":[
                                      {"functionCall":{"id":"call-1","name":"lookup","args":{"city":"Paris"}}}
                                    ]
                                  },
                                  "finishReason":"MAX_TOKENS"
                                }
                              ],
                              "usageMetadata":{
                                "promptTokenCount":1,
                                "candidatesTokenCount":2,
                                "totalTokenCount":3
                              }
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
                apiKey("key");
                baseURL("https://google.test/v1beta")
            },
        )

        val result = provider(ModelId("gemini-2.5-flash")).generate(
            LanguageModelCallParams {
                messages(listOf(UserMessage("hi")))
                tools(listOf(LanguageModelTool("lookup", "Lookup.", objectSchema("city").toString())))
            },
        )

        assertEquals(FinishReason.Length, result.finishReason)
        assertEquals("MAX_TOKENS", result.rawFinishReason)
        assertEquals("lookup", result.toolCalls.single().toolName)
    }

    @Test
    fun `language model maps MALFORMED_FUNCTION_CALL to error`() = runTest {
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://google.test/v1beta/models/gemini-2.5-flash:generateContent" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"\"}]}," +
                                "\"finishReason\":\"MALFORMED_FUNCTION_CALL\"}]," +
                                "\"usageMetadata\":{\"promptTokenCount\":1,\"candidatesTokenCount\":0," +
                                "\"totalTokenCount\":1}}",
                        ),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val provider = GoogleGenerativeAI(
            fixture.httpClient(),
            GoogleGenerativeAIProviderSettings {
                apiKey("key");
                baseURL("https://google.test/v1beta")
            },
        )
        val result = provider(ModelId("gemini-2.5-flash")).generate(
            LanguageModelCallParams {
                messages(listOf(UserMessage("hi")))
            },
        )
        // Upstream maps MALFORMED_FUNCTION_CALL to error, not content-filter.
        assertEquals(FinishReason.Error, result.finishReason)
        assertEquals("MALFORMED_FUNCTION_CALL", result.rawFinishReason)
    }

    @Test
    fun `language model omits toolConfig when there are no tools`() = runTest {
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://google.test/v1beta/models/gemini-2.5-flash:generateContent" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """
                            {
                              "candidates":[{"content":{"role":"model","parts":[{"text":"ok"}]},"finishReason":"STOP"}]
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
                apiKey("key");
                baseURL("https://google.test/v1beta")
            },
        )

        val result = provider(ModelId("gemini-2.5-flash")).generate(
            LanguageModelCallParams {
                messages(listOf(UserMessage("hi")))
                toolChoice(ToolChoice.Required)
            },
        )

        assertEquals("ok", result.text)
        val body = fixture.calls.single().requestBodyJson.jsonObject
        assertTrue("tools" !in body)
        assertTrue("toolConfig" !in body)
    }

    @Test
    fun `stream surfaces malformed Gemini function call as wire error event`() = runTest {
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://google.test/v1beta/models/gemini-2.5-flash:streamGenerateContent?alt=sse" to UrlHandler(
                    UrlResponse.StreamChunks(
                        listOf(
                            """data: {"candidates":[{"content":{"parts":[{"functionCall":{"args":{"city":"Paris"}}}]}}]}""",
                        ),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val provider = GoogleGenerativeAI(
            fixture.httpClient(),
            GoogleGenerativeAIProviderSettings {
                apiKey("key");
                baseURL("https://google.test/v1beta")
            },
        )

        val events = drainAllItems(
            provider.chat(ModelId("gemini-2.5-flash")).stream(
                LanguageModelCallParams {
                    messages(listOf(UserMessage("hi")))
                }
            ),
        )

        val error = events.filterIsInstance<StreamEvent.Error>().single()
        assertTrue(error.message.contains("functionCall.name"))
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
    }

    private fun Map<String, String>.headerValue(name: String): String? =
        entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}
