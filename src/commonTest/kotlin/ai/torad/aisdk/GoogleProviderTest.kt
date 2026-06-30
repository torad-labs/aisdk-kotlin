@file:OptIn(LowLevelLanguageModelApi::class)

package ai.torad.aisdk
import ai.torad.aisdk.providers.GOOGLE_VERSION
import ai.torad.aisdk.providers.GoogleGenerativeAIProviderSettings
import ai.torad.aisdk.providers.GoogleGenerativeAI

import ai.torad.aisdk.testing.FlowDrain.drainAllItems
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
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
            GoogleGenerativeAIProviderSettings(
                apiKey = "key",
                baseURL = "https://google.test/v1beta",
                headers = mapOf("X-Provider" to "provider"),
            ),
        )

        val result = provider(ModelId("gemini-2.5-flash")).generate(
            LanguageModelCallParams(
                messages = listOf(
                    SystemMessage("Follow policy."),
                    ModelMessage(
                        MessageRole.User,
                        listOf(
                            ContentPart.Text("Hello"),
                            ContentPart.Image("image/png", "aW1hZ2U="),
                        ),
                    ),
                ),
                tools = listOf(
                    LanguageModelTool("lookup", "Lookup a city.", objectSchema("city").toString()),
                    LanguageModelTool("google_search", "Search.", """{"type":"object"}""", providerExecuted = true),
                ),
                toolChoice = ToolChoice.Specific("lookup"),
                temperature = 0.3f,
                topP = 0.8f,
                topK = 40,
                maxOutputTokens = 64,
                responseFormat = ResponseFormat.Json(schemaJson = objectSchema("answer")),
                providerOptions = ProviderOptions.Raw(JsonObject(mapOf(
                    "google" to buildJsonObject {
                        put("responseModalities", Json.parseToJsonElement("""["TEXT"]"""))
                        put("thinkingConfig", buildJsonObject { put("thinkingBudget", JsonPrimitive(128)) })
                        put("serviceTier", JsonPrimitive("priority"))
                    },
                ))),
                headers = mapOf("X-Request" to "request"),
            ),
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
        assertEquals("BLOCK_REASON_UNSPECIFIED", result.providerMetadata.toMap()["google"]?.jsonObject?.get("promptFeedback")?.jsonObject?.get("blockReason")?.jsonPrimitive?.contentOrNull)

        val request = fixture.calls.single()
        assertEquals("key", request.requestHeaders.headerValue("x-goog-api-key"))
        assertEquals("provider", request.requestHeaders.headerValue("X-Provider"))
        assertEquals("request", request.requestHeaders.headerValue("X-Request"))
        assertTrue(request.requestUserAgent.orEmpty().contains("ai-sdk/google/$GOOGLE_VERSION"))
        val body = request.requestBodyJson.jsonObject
        assertEquals("Follow policy.", body["systemInstruction"]?.jsonObject?.get("parts")?.jsonArray?.single()?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull)
        assertEquals("application/json", body["generationConfig"]?.jsonObject?.get("responseMimeType")?.jsonPrimitive?.contentOrNull)
        assertEquals(64, body["generationConfig"]?.jsonObject?.get("maxOutputTokens")?.jsonPrimitive?.intOrNull)
        assertEquals(128, body["generationConfig"]?.jsonObject?.get("thinkingConfig")?.jsonObject?.get("thinkingBudget")?.jsonPrimitive?.intOrNull)
        assertEquals("priority", body["serviceTier"]?.jsonPrimitive?.contentOrNull)
        val tools = body["tools"]?.jsonArray.orEmpty()
        val decl = tools[0].jsonObject["functionDeclarations"]?.jsonArray?.single()?.jsonObject
        assertEquals("lookup", decl?.get("name")?.jsonPrimitive?.contentOrNull)
        // The tool parameters schema must be stripped of JSON-Schema keys Google rejects.
        val params = decl?.get("parameters")?.jsonObject
        assertTrue(params != null && "\$schema" !in params, "\$schema stripped from tool schema")
        assertTrue("additionalProperties" !in params, "additionalProperties stripped from tool schema")
        assertTrue("title" !in params, "title stripped from tool schema")
        assertEquals("lookup", body["toolConfig"]?.jsonObject?.get("functionCallingConfig")?.jsonObject?.get("allowedFunctionNames")?.jsonArray?.single()?.jsonPrimitive?.contentOrNull)
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
            GoogleGenerativeAIProviderSettings(apiKey = "key", baseURL = "https://google.test/v1beta"),
        )

        val events = drainAllItems(
            provider.chat(ModelId("gemini-2.5-flash")).stream(LanguageModelCallParams(messages = listOf(UserMessage("hi")))),
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
            GoogleGenerativeAIProviderSettings(apiKey = "key", baseURL = "https://google.test/v1beta"),
        )

        val result = provider(ModelId("gemini-2.5-flash")).generate(
            LanguageModelCallParams(
                messages = listOf(UserMessage("hi")),
                tools = listOf(LanguageModelTool("lookup", "Lookup.", objectSchema("city").toString())),
            ),
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
            GoogleGenerativeAIProviderSettings(apiKey = "key", baseURL = "https://google.test/v1beta"),
        )
        val result = provider(ModelId("gemini-2.5-flash")).generate(
            LanguageModelCallParams(messages = listOf(UserMessage("hi"))),
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
            GoogleGenerativeAIProviderSettings(apiKey = "key", baseURL = "https://google.test/v1beta"),
        )

        val result = provider(ModelId("gemini-2.5-flash")).generate(
            LanguageModelCallParams(
                messages = listOf(UserMessage("hi")),
                toolChoice = ToolChoice.Required,
            ),
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
            GoogleGenerativeAIProviderSettings(apiKey = "key", baseURL = "https://google.test/v1beta"),
        )

        val events = drainAllItems(
            provider.chat(ModelId("gemini-2.5-flash")).stream(LanguageModelCallParams(messages = listOf(UserMessage("hi")))),
        )

        val error = events.filterIsInstance<StreamEvent.Error>().single()
        assertTrue(error.message.contains("functionCall.name"))
    }

    @Test
    fun `embedding image and video models map Google payloads`() = runTest {
        var videoPolls = 0
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://google.test/v1beta/models/text-embedding-004:batchEmbedContents" to UrlHandler(
                    UrlResponse.JsonValue(Json.parseToJsonElement("""{"embeddings":[{"values":[1.0,2.0]},{"values":[3.0,4.0]}]}""")),
                ),
                "https://google.test/v1beta/models/imagen-4.0-generate-001:predict" to UrlHandler(
                    UrlResponse.JsonValue(Json.parseToJsonElement("""{"predictions":[{"bytesBase64Encoded":"aW1hZ2U="}]}""")),
                ),
                "https://google.test/v1beta/models/veo-3.1-generate-preview:predictLongRunning" to UrlHandler(
                    UrlResponse.JsonValue(Json.parseToJsonElement("""{"name":"operations/video-1","done":false}""")),
                ),
                "https://google.test/v1beta/operations/video-1" to UrlHandler(
                    {
                        if (videoPolls++ == 0) {
                            UrlResponse.Error(status = 503, body = """{"error":{"message":"temporarily unavailable"}}""")
                        } else {
                            UrlResponse.JsonValue(
                                Json.parseToJsonElement(
                                    """
                                    {
                                      "name":"operations/video-1",
                                      "done":true,
                                      "response":{
                                        "generateVideoResponse":{
                                          "generatedSamples":[{"video":{"uri":"https://videos.example/video.mp4"}}]
                                        }
                                      }
                                    }
                                    """.trimIndent(),
                                ),
                            )
                        }
                    },
                ),
            ),
        )
        fixture.server.start()
        val provider = GoogleGenerativeAI(
            fixture.httpClient(),
            GoogleGenerativeAIProviderSettings(
                apiKey = "key",
                baseURL = "https://google.test/v1beta",
                videoPollIntervalMillis = 0,
            ),
        )

        val embeddings = provider.embedding(ModelId("text-embedding-004")).embed(
            EmbeddingModelCallParams(
                values = listOf("one", "two"),
                providerOptions = ProviderOptions.Raw(JsonObject(mapOf(
                    "google" to buildJsonObject {
                        put("taskType", JsonPrimitive("RETRIEVAL_QUERY"))
                        put("outputDimensionality", JsonPrimitive(256))
                    },
                ))),
            ),
        )
        val image = provider.image(ModelId("imagen-4.0-generate-001")).generate(
            ImageGenerationParams(
                prompt = "A product render",
                n = 1,
                aspectRatio = "16:9",
                providerOptions = ProviderOptions.Raw(JsonObject(mapOf("google" to buildJsonObject { put("personGeneration", JsonPrimitive("dont_allow")) }))),
            ),
        )
        val video = provider.video(ModelId("veo-3.1-generate-preview")).generate(
            VideoGenerationParams(
                prompt = "A clean motion shot",
                n = 1,
                aspectRatio = "16:9",
                durationSeconds = 4f,
                resolution = "1920x1080",
                providerOptions = ProviderOptions.Raw(JsonObject(mapOf("google" to buildJsonObject { put("negativePrompt", JsonPrimitive("blur")) }))),
            ),
        )

        assertEquals(listOf(1.0f, 2.0f), embeddings.embeddings.first())
        assertEquals(2048, provider.embedding(ModelId("text-embedding-004")).maxEmbeddingsPerCall)
        assertEquals(true, provider.embedding(ModelId("text-embedding-004")).supportsParallelCalls)
        assertEquals("image", Base64Codec.decode(image.images.single().base64).decodeToString())
        val generatedVideo = video.videos.single()
        assertEquals("https://videos.example/video.mp4", generatedVideo.url)
        val googleMetadata = generatedVideo.providerMetadata.toMap()["google"]?.jsonObject ?: error("missing google metadata")
        assertEquals("https://videos.example/video.mp4", googleMetadata["uri"]!!.jsonPrimitive.content)
        assertEquals(true, googleMetadata["requiresApiKey"]!!.jsonPrimitive.booleanOrNull)

        val embeddingBody = fixture.calls[0].requestBodyJson.jsonObject
        assertEquals(2, embeddingBody["requests"]?.jsonArray?.size)
        assertEquals(256, embeddingBody["requests"]?.jsonArray?.first()?.jsonObject?.get("outputDimensionality")?.jsonPrimitive?.intOrNull)
        val imageBody = fixture.calls[1].requestBodyJson.jsonObject
        assertEquals("A product render", imageBody["instances"]?.jsonArray?.single()?.jsonObject?.get("prompt")?.jsonPrimitive?.contentOrNull)
        assertEquals("16:9", imageBody["parameters"]?.jsonObject?.get("aspectRatio")?.jsonPrimitive?.contentOrNull)
        val videoBody = fixture.calls[2].requestBodyJson.jsonObject
        assertEquals("1080p", videoBody["parameters"]?.jsonObject?.get("resolution")?.jsonPrimitive?.contentOrNull)
        assertEquals(4f, videoBody["parameters"]?.jsonObject?.get("durationSeconds")?.jsonPrimitive?.floatOrNull)
        assertEquals("GET", fixture.calls[3].requestMethod)
        assertEquals("GET", fixture.calls[4].requestMethod)
    }

    @Test
    fun `image and video models reject malformed Google media wire data`() = runTest {
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://google.test/v1beta/models/imagen-4.0-generate-001:predict" to UrlHandler(
                    UrlResponse.JsonValue(Json.parseToJsonElement("""{"predictions":[{}]}""")),
                ),
                "https://google.test/v1beta/models/veo-3.1-generate-preview:predictLongRunning" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """
                            {
                              "name":"operations/video-1",
                              "done":true,
                              "response":{
                                "generateVideoResponse":{
                                  "generatedSamples":[{"video":{}}]
                                }
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
            GoogleGenerativeAIProviderSettings(apiKey = "key", baseURL = "https://google.test/v1beta", videoPollIntervalMillis = 0),
        )

        val imageError = assertFailsWith<WireDecodeException> {
            provider.image(ModelId("imagen-4.0-generate-001")).generate(ImageGenerationParams(prompt = "x"))
        }
        val videoError = assertFailsWith<WireDecodeException> {
            provider.video(ModelId("veo-3.1-generate-preview")).generate(VideoGenerationParams(prompt = "x"))
        }

        assertTrue(imageError.message.orEmpty().contains("bytesBase64Encoded"))
        assertTrue(videoError.message.orEmpty().contains("video.uri"))
    }

    @Test
    fun `interactions model posts v6 request and maps steps usage metadata and sources`() = runTest {
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
                              "service_tier":"priority",
                              "created":"2026-06-03T00:00:00Z",
                              "steps":[
                                {
                                  "type":"model_output",
                                  "content":[
                                    {
                                      "type":"text",
                                      "text":"Hello from interactions.",
                                      "annotations":[{"type":"url_citation","url":"https://example.com","title":"Example"}]
                                    },
                                    {"type":"image","data":"aW1n","mime_type":"image/png"}
                                  ]
                                },
                                {
                                  "type":"thought",
                                  "signature":"thought-sig",
                                  "summary":[{"type":"text","text":"reasoning"}]
                                },
                                {
                                  "type":"function_call",
                                  "id":"call-1",
                                  "name":"lookup",
                                  "arguments":{"city":"Paris"},
                                  "signature":"call-sig"
                                }
                              ],
                              "usage":{
                                "total_input_tokens":5,
                                "total_cached_tokens":2,
                                "total_output_tokens":7,
                                "total_thought_tokens":3,
                                "total_tokens":15
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
            GoogleGenerativeAIProviderSettings(
                apiKey = "key",
                baseURL = "https://google.test/v1beta",
                headers = mapOf("X-Provider" to "provider"),
            ),
        )

        val result = provider.interactions(ModelId("gemini-2.5-flash")).generate(
            LanguageModelCallParams(
                messages = listOf(
                    SystemMessage("Follow policy."),
                    UserMessage("Use interactions."),
                ),
                tools = listOf(LanguageModelTool("lookup", "Lookup a city.", objectSchema("city").toString())),
                toolChoice = ToolChoice.Required,
                temperature = 0.2f,
                topP = 0.9f,
                maxOutputTokens = 128,
                stopSequences = listOf("END"),
                seed = 7,
                responseFormat = ResponseFormat.Json(schemaJson = objectSchema("answer")),
                providerOptions = ProviderOptions.Raw(JsonObject(mapOf(
                    "google" to buildJsonObject {
                        put("store", JsonPrimitive(true))
                        put("previousInteractionId", JsonPrimitive("prior-1"))
                        put("thinkingLevel", JsonPrimitive("high"))
                        put("thinkingSummaries", JsonPrimitive("auto"))
                        put("responseModalities", Json.parseToJsonElement("""["text","image"]"""))
                        put(
                            "responseFormat",
                            Json.parseToJsonElement("""[{"type":"image","mimeType":"image/png","aspectRatio":"1:1","imageSize":"1K"}]"""),
                        )
                        put("serviceTier", JsonPrimitive("priority"))
                    },
                ))),
                headers = mapOf("X-Request" to "request"),
            ),
        )

        assertEquals("Hello from interactions.", result.text)
        assertEquals(FinishReason.ToolCalls, result.finishReason)
        assertEquals("call-1", result.toolCalls.single().toolCallId)
        assertEquals("Paris", result.toolCalls.single().input.jsonObject["city"]?.jsonPrimitive?.contentOrNull)
        assertEquals("reasoning", result.content.filterIsInstance<ContentPart.Reasoning>().single().text)
        assertEquals("image/png", result.content.filterIsInstance<ContentPart.File>().single().mediaType)
        assertEquals("https://example.com", result.content.filterIsInstance<ContentPart.Source>().single().url)
        assertEquals(5, result.usage.promptTokens)
        assertEquals(10, result.usage.completionTokens)
        assertEquals(2, result.usage.inputTokens.cacheRead)
        assertEquals(3, result.usage.outputTokens.reasoning)
        assertEquals("interaction-1", result.response.id)
        assertEquals("gemini-2.5-flash", result.response.modelId)
        assertEquals("interaction-1", result.providerMetadata.toMap()["google"]?.jsonObject?.get("interactionId")?.jsonPrimitive?.contentOrNull)
        assertEquals("priority", result.providerMetadata.toMap()["google"]?.jsonObject?.get("serviceTier")?.jsonPrimitive?.contentOrNull)

        val request = fixture.calls.single()
        assertEquals("key", request.requestHeaders.headerValue("x-goog-api-key"))
        assertEquals("2026-05-20", request.requestHeaders.headerValue("Api-Revision"))
        assertEquals("provider", request.requestHeaders.headerValue("X-Provider"))
        assertEquals("request", request.requestHeaders.headerValue("X-Request"))
        assertTrue(request.requestUserAgent.orEmpty().contains("ai-sdk/google/$GOOGLE_VERSION"))
        val body = request.requestBodyJson.jsonObject
        assertEquals("gemini-2.5-flash", body["model"]?.jsonPrimitive?.contentOrNull)
        assertEquals("Follow policy.", body["system_instruction"]?.jsonPrimitive?.contentOrNull)
        assertEquals("prior-1", body["previous_interaction_id"]?.jsonPrimitive?.contentOrNull)
        assertEquals(true, body["store"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("Use interactions.", body["input"]?.jsonArray?.single()?.jsonObject?.get("content")?.jsonArray?.single()?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull)
        val generationConfig = body["generation_config"]!!.jsonObject
        assertEquals(128, generationConfig["max_output_tokens"]?.jsonPrimitive?.intOrNull)
        assertEquals("high", generationConfig["thinking_level"]?.jsonPrimitive?.contentOrNull)
        assertEquals("any", generationConfig["tool_choice"]?.jsonPrimitive?.contentOrNull)
        assertEquals("lookup", body["tools"]?.jsonArray?.single()?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull)
        assertEquals("application/json", body["response_format"]?.jsonArray?.first()?.jsonObject?.get("mime_type")?.jsonPrimitive?.contentOrNull)
        assertEquals("image", body["response_format"]?.jsonArray?.last()?.jsonObject?.get("type")?.jsonPrimitive?.contentOrNull)
        assertEquals("priority", body["service_tier"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `interactions stream maps SSE steps into stream events`() = runTest {
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://google.test/v1beta/interactions" to UrlHandler(
                    UrlResponse.StreamChunks(
                        listOf(
                            """
                            data: {"type":"interaction.created","interaction":{"id":"interaction-2","model":"gemini-2.5-flash","status":"in_progress"}}

                            data: {"type":"step.start","step":{"type":"model_output","content":[{"type":"text","text":"Hello "}]},"interaction":{"id":"interaction-2"}}

                            data: {"type":"step.start","step":{"type":"model_output","content":[{"type":"text","text":"stream"}]},"interaction":{"id":"interaction-2"}}

                            data: {"type":"step.start","step":{"type":"function_call","id":"call-2","name":"lookup","arguments":{"city":"Berlin"}},"interaction":{"id":"interaction-2"}}

                            data: {"type":"interaction.complete","interaction":{"id":"interaction-2","model":"gemini-2.5-flash","status":"completed","usage":{"total_input_tokens":1,"total_output_tokens":2}}}

                            """.trimIndent(),
                        ),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val provider = GoogleGenerativeAI(
            fixture.httpClient(),
            GoogleGenerativeAIProviderSettings(apiKey = "key", baseURL = "https://google.test/v1beta"),
        )

        val events = drainAllItems(
            provider.interactions(ModelId("gemini-2.5-flash")).stream(LanguageModelCallParams(messages = listOf(UserMessage("hi")))),
        )

        assertIs<StreamEvent.StreamStart>(events.first())
        assertTrue(events.any { it is StreamEvent.ResponseMetadata && it.id == "interaction-2" })
        assertTrue(events.any { it is StreamEvent.TextDelta && it.text == "Hello " })
        assertTrue(events.any { it is StreamEvent.TextDelta && it.text == "stream" })
        val toolCall = events.filterIsInstance<StreamEvent.ToolCall>().single()
        assertEquals("lookup", toolCall.toolName)
        assertEquals("Berlin", toolCall.inputJson.jsonObject["city"]?.jsonPrimitive?.contentOrNull)
        val finish = events.filterIsInstance<StreamEvent.Finish>().single()
        assertEquals(FinishReason.ToolCalls, finish.finishReason)
        assertEquals(1, finish.usage.promptTokens)
        assertEquals(2, finish.usage.completionTokens)

        val request = fixture.calls.single()
        assertEquals("2026-05-20", request.requestHeaders.headerValue("Api-Revision"))
        assertEquals(true, request.requestBodyJson.jsonObject["stream"]?.jsonPrimitive?.booleanOrNull)
    }

    @Test
    fun `interactions agent background stream omits stream on post and synthesizes terminal response`() = runTest {
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://google.test/v1beta/interactions" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """
                            {
                              "id":"agent-run-1",
                              "model":"deep-research",
                              "status":"completed",
                              "steps":[{"type":"model_output","content":[{"type":"text","text":"Agent result"}]}],
                              "usage":{"total_input_tokens":2,"total_output_tokens":3}
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
            GoogleGenerativeAIProviderSettings(apiKey = "key", baseURL = "https://google.test/v1beta"),
        )

        val events = drainAllItems(
            provider.agentInteraction("deep-research").stream(
                LanguageModelCallParams(
                    messages = listOf(UserMessage("research this")),
                    tools = listOf(LanguageModelTool("lookup", "Lookup.", objectSchema("q").toString())),
                    temperature = 0.1f,
                    providerOptions = ProviderOptions.Raw(JsonObject(mapOf(
                        "google" to buildJsonObject {
                            put("background", JsonPrimitive(true))
                            put(
                                "agentConfig",
                                Json.parseToJsonElement("""{"type":"deep-research","thinkingSummaries":"auto","visualization":"auto","collaborativePlanning":true}"""),
                            )
                            put(
                                "environment",
                                Json.parseToJsonElement("""{"type":"remote","sources":[{"type":"inline","content":"notes","target":"/tmp/notes.txt"}],"network":{"allowlist":[{"domain":"example.com"}]}}"""),
                            )
                        },
                    ))),
                ),
            ),
        )

        assertTrue(events.any { it is StreamEvent.TextDelta && it.text == "Agent result" })
        val finish = events.filterIsInstance<StreamEvent.Finish>().single()
        assertEquals(FinishReason.Stop, finish.finishReason)
        assertEquals(2, finish.usage.promptTokens)
        assertEquals(3, finish.usage.completionTokens)

        val body = fixture.calls.single().requestBodyJson.jsonObject
        assertEquals("deep-research", body["agent"]?.jsonPrimitive?.contentOrNull)
        assertEquals(null, body["model"])
        assertEquals(null, body["stream"])
        assertEquals(null, body["tools"])
        assertEquals(null, body["generation_config"])
        assertEquals(true, body["background"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("deep-research", body["agent_config"]?.jsonObject?.get("type")?.jsonPrimitive?.contentOrNull)
        assertEquals("remote", body["environment"]?.jsonObject?.get("type")?.jsonPrimitive?.contentOrNull)
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
