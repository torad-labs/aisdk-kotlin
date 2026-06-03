package ai.torad.aisdk

import ai.torad.aisdk.testing.drainAllItems
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
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GoogleProviderTest {
    @Test
    fun `language model maps Gemini request response tools sources and metadata`() = runTest {
        val fixture = createTestServer(
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
        val provider = createGoogleGenerativeAI(
            fixture.httpClient(),
            GoogleGenerativeAIProviderSettings(
                apiKey = "key",
                baseURL = "https://google.test/v1beta",
                headers = mapOf("X-Provider" to "provider"),
            ),
        )

        val result = provider("gemini-2.5-flash").generate(
            LanguageModelCallParams(
                messages = listOf(
                    systemMessage("Follow policy."),
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
                providerOptions = mapOf(
                    "google" to buildJsonObject {
                        put("responseModalities", Json.parseToJsonElement("""["TEXT"]"""))
                        put("thinkingConfig", buildJsonObject { put("thinkingBudget", JsonPrimitive(128)) })
                        put("serviceTier", JsonPrimitive("priority"))
                    },
                ),
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
        assertEquals(5, result.usage.completionTokens)
        assertEquals(2, result.usage.outputTokens.reasoning)
        assertEquals("BLOCK_REASON_UNSPECIFIED", result.providerMetadata["google"]?.jsonObject?.get("promptFeedback")?.jsonObject?.get("blockReason")?.jsonPrimitive?.contentOrNull)

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
        assertEquals("lookup", tools[0].jsonObject["functionDeclarations"]?.jsonArray?.single()?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull)
        assertEquals("lookup", body["toolConfig"]?.jsonObject?.get("functionCallingConfig")?.jsonObject?.get("allowedFunctionNames")?.jsonArray?.single()?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `stream maps Gemini SSE text reasoning tool calls files and finish`() = runTest {
        val fixture = createTestServer(
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
        val provider = createGoogleGenerativeAI(
            fixture.httpClient(),
            GoogleGenerativeAIProviderSettings(apiKey = "key", baseURL = "https://google.test/v1beta"),
        )

        val events = drainAllItems(
            provider.chat("gemini-2.5-flash").stream(LanguageModelCallParams(messages = listOf(userMessage("hi")))),
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
    fun `embedding image and video models map Google payloads`() = runTest {
        val fixture = createTestServer(
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
                    ),
                ),
            ),
        )
        fixture.server.start()
        val provider = createGoogleGenerativeAI(
            fixture.httpClient(),
            GoogleGenerativeAIProviderSettings(
                apiKey = "key",
                baseURL = "https://google.test/v1beta",
                videoPollIntervalMillis = 0,
            ),
        )

        val embeddings = provider.embedding("text-embedding-004").embed(
            EmbeddingModelCallParams(
                values = listOf("one", "two"),
                providerOptions = mapOf(
                    "google" to buildJsonObject {
                        put("taskType", JsonPrimitive("RETRIEVAL_QUERY"))
                        put("outputDimensionality", JsonPrimitive(256))
                    },
                ),
            ),
        )
        val image = provider.image("imagen-4.0-generate-001").generate(
            ImageGenerationParams(
                prompt = "A product render",
                n = 1,
                aspectRatio = "16:9",
                providerOptions = mapOf("google" to buildJsonObject { put("personGeneration", JsonPrimitive("dont_allow")) }),
            ),
        )
        val video = provider.video("veo-3.1-generate-preview").generate(
            VideoGenerationParams(
                prompt = "A clean motion shot",
                n = 1,
                aspectRatio = "16:9",
                durationSeconds = 4f,
                resolution = "1920x1080",
                providerOptions = mapOf("google" to buildJsonObject { put("negativePrompt", JsonPrimitive("blur")) }),
            ),
        )

        assertEquals(listOf(1.0f, 2.0f), embeddings.embeddings.first())
        assertEquals("image", convertBase64ToByteArray(image.images.single().base64).decodeToString())
        assertEquals("https://videos.example/video.mp4?key=key", video.videos.single().url)

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
