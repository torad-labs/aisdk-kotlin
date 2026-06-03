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
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AmazonBedrockProviderTest {
    @Test
    fun `chat model maps Converse request response metadata and auth`() = runTest {
        val fixture = createTestServer(
            mutableMapOf(
                "https://bedrock.test/model/anthropic.claude-3-7-sonnet-20250219-v1%3A0/converse" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """
                            {
                              "output":{
                                "message":{
                                  "role":"assistant",
                                  "content":[
                                    {
                                      "reasoningContent":{
                                        "reasoningText":{"text":"thinking","signature":"sig"}
                                      }
                                    },
                                    {"text":"Answer"},
                                    {
                                      "toolUse":{
                                        "toolUseId":"tool-1",
                                        "name":"lookup",
                                        "input":{"city":"Paris"}
                                      }
                                    }
                                  ]
                                }
                              },
                              "stopReason":"tool_use",
                              "additionalModelResponseFields":{"delta":{"stop_sequence":"</done>"}},
                              "usage":{
                                "inputTokens":12,
                                "outputTokens":5,
                                "cacheReadInputTokens":2,
                                "cacheWriteInputTokens":3,
                                "totalTokens":17
                              },
                              "performanceConfig":{"latency":"optimized"},
                              "serviceTier":{"type":"priority"}
                            }
                            """.trimIndent(),
                        ),
                        headers = mapOf("x-amzn-requestid" to "req-1"),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val provider = createAmazonBedrock(
            fixture.httpClient(),
            AmazonBedrockProviderSettings(
                apiKey = "key",
                baseURL = "https://bedrock.test",
                headers = mapOf("X-Provider" to "provider"),
            ),
        )

        val result = provider("anthropic.claude-3-7-sonnet-20250219-v1:0").generate(
            LanguageModelCallParams(
                messages = listOf(
                    ModelMessage(
                        MessageRole.System,
                        listOf(ContentPart.Text("Follow policy.")),
                    ),
                    ModelMessage(
                        MessageRole.User,
                        listOf(
                            ContentPart.Text("Describe the file."),
                            ContentPart.Image("image/png", "aW1hZ2U="),
                            ContentPart.File(
                                mediaType = "application/pdf",
                                base64 = "cGRm",
                                filename = "brief.pdf",
                                providerMetadata = mapOf(
                                    "bedrock" to buildJsonObject {
                                        put("citations", buildJsonObject { put("enabled", JsonPrimitive(true)) })
                                    },
                                ),
                            ),
                        ),
                    ),
                ),
                tools = listOf(LanguageModelTool("lookup", "Lookup a city.", objectSchema("city").toString())),
                toolChoice = ToolChoice.Specific("lookup"),
                temperature = 2f,
                topP = 0.5f,
                topK = 10,
                maxOutputTokens = 64,
                responseFormat = ResponseFormat.Json(schemaJson = objectSchema("answer")),
                providerOptions = mapOf(
                    "bedrock" to buildJsonObject {
                        put("serviceTier", JsonPrimitive("priority"))
                        put(
                            "reasoningConfig",
                            buildJsonObject {
                                put("type", JsonPrimitive("enabled"))
                                put("budgetTokens", JsonPrimitive(32))
                                put("maxReasoningEffort", JsonPrimitive("high"))
                            },
                        )
                        put("anthropicBeta", Json.parseToJsonElement("""["beta-1"]"""))
                    },
                ),
                headers = mapOf("X-Request" to "request"),
            ),
        )

        assertEquals("Answer", result.text)
        assertEquals(FinishReason.ToolCalls, result.finishReason)
        assertEquals("tool-1", result.toolCalls.single().toolCallId)
        assertEquals("Paris", result.toolCalls.single().input.jsonObject["city"]?.jsonPrimitive?.contentOrNull)
        assertEquals("thinking", result.content.filterIsInstance<ContentPart.Reasoning>().single().text)
        assertEquals(12, result.usage.promptTokens)
        assertEquals(5, result.usage.completionTokens)
        assertEquals(2, result.usage.inputTokens.cacheRead)
        assertEquals(3, result.usage.inputTokens.cacheWrite)
        assertEquals("req-1", result.response.id)
        assertEquals("</done>", result.providerMetadata["bedrock"]?.jsonObject?.get("stopSequence")?.jsonPrimitive?.contentOrNull)

        val request = fixture.calls.single()
        assertEquals("POST", request.requestMethod)
        assertEquals("Bearer key", request.requestHeaders.headerValue(HttpHeaders.Authorization))
        assertEquals("provider", request.requestHeaders.headerValue("X-Provider"))
        assertEquals("request", request.requestHeaders.headerValue("X-Request"))
        assertTrue(request.requestUserAgent.orEmpty().contains("ai-sdk/amazon-bedrock/$AMAZON_BEDROCK_VERSION"))
        val body = request.requestBodyJson.jsonObject
        assertEquals("Follow policy.", body["system"]?.jsonArray?.single()?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull)
        assertEquals("priority", body["serviceTier"]?.jsonObject?.get("type")?.jsonPrimitive?.contentOrNull)
        assertEquals(1.0f, body["inferenceConfig"]?.jsonObject?.get("temperature")?.jsonPrimitive?.floatOrNull)
        assertEquals(64, body["inferenceConfig"]?.jsonObject?.get("maxTokens")?.jsonPrimitive?.intOrNull)
        assertEquals("high", body["additionalModelRequestFields"]?.jsonObject?.get("output_config")?.jsonObject?.get("effort")?.jsonPrimitive?.contentOrNull)
        assertEquals("enabled", body["additionalModelRequestFields"]?.jsonObject?.get("thinking")?.jsonObject?.get("type")?.jsonPrimitive?.contentOrNull)
        assertEquals("beta-1", body["additionalModelRequestFields"]?.jsonObject?.get("anthropic_beta")?.jsonArray?.single()?.jsonPrimitive?.contentOrNull)
        val userContent = body["messages"]?.jsonArray?.first()?.jsonObject?.get("content")?.jsonArray.orEmpty()
        assertEquals("Describe the file.", userContent[0].jsonObject["text"]?.jsonPrimitive?.contentOrNull)
        assertEquals("png", userContent[1].jsonObject["image"]?.jsonObject?.get("format")?.jsonPrimitive?.contentOrNull)
        assertEquals("pdf", userContent[2].jsonObject["document"]?.jsonObject?.get("format")?.jsonPrimitive?.contentOrNull)
        assertEquals(true, userContent[2].jsonObject["document"]?.jsonObject?.get("citations")?.jsonObject?.get("enabled")?.jsonPrimitive?.booleanOrNull)
        assertEquals("lookup", body["toolConfig"]?.jsonObject?.get("toolChoice")?.jsonObject?.get("tool")?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `chat stream maps decoded Bedrock event stream chunks`() = runTest {
        val fixture = createTestServer(
            mutableMapOf(
                "https://bedrock.test/model/anthropic.claude-3-7-sonnet-20250219-v1%3A0/converse-stream" to UrlHandler(
                    UrlResponse.StreamChunks(
                        listOf(
                            """
                            {"messageStart":{"role":"assistant"}}
                            {"contentBlockDelta":{"contentBlockIndex":0,"delta":{"reasoningContent":{"text":"think"}}}}
                            {"contentBlockStop":{"contentBlockIndex":0}}
                            {"contentBlockDelta":{"contentBlockIndex":1,"delta":{"text":"hello"}}}
                            {"contentBlockStop":{"contentBlockIndex":1}}
                            {"contentBlockStart":{"contentBlockIndex":2,"start":{"toolUse":{"toolUseId":"tool-1","name":"lookup"}}}}
                            {"contentBlockDelta":{"contentBlockIndex":2,"delta":{"toolUse":{"input":"{\"city\":\"Paris\"}"}}}}
                            {"contentBlockStop":{"contentBlockIndex":2}}
                            {"messageStop":{"stopReason":"tool_use","additionalModelResponseFields":{"delta":{"stop_sequence":null}}}}
                            {"metadata":{"usage":{"inputTokens":3,"outputTokens":4},"performanceConfig":{"latency":"optimized"}}}
                            """.trimIndent(),
                        ),
                        headers = mapOf("x-amzn-requestid" to "stream-1"),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val provider = createAmazonBedrock(
            fixture.httpClient(),
            AmazonBedrockProviderSettings(apiKey = "key", baseURL = "https://bedrock.test"),
        )

        val events = drainAllItems(
            provider.languageModel("anthropic.claude-3-7-sonnet-20250219-v1:0").stream(
                LanguageModelCallParams(messages = listOf(userMessage("hi"))),
            ),
        )

        assertIs<StreamEvent.StreamStart>(events.first())
        assertTrue(events.any { it is StreamEvent.ResponseMetadata && it.id == "stream-1" })
        assertTrue(events.any { it is StreamEvent.ReasoningDelta && it.text == "think" })
        assertTrue(events.any { it is StreamEvent.TextDelta && it.text == "hello" })
        val toolCall = events.filterIsInstance<StreamEvent.ToolCall>().single()
        assertEquals("lookup", toolCall.toolName)
        assertEquals("Paris", toolCall.inputJson.jsonObject["city"]?.jsonPrimitive?.contentOrNull)
        val finish = events.filterIsInstance<StreamEvent.Finish>().single()
        assertEquals(FinishReason.ToolCalls, finish.finishReason)
        assertEquals(3, finish.usage.promptTokens)
        assertEquals(4, finish.usage.completionTokens)
        assertEquals("application/vnd.amazon.eventstream", fixture.calls.single().requestHeaders.headerValue(HttpHeaders.Accept))
    }

    @Test
    fun `embedding image and reranking models map Bedrock runtime payloads`() = runTest {
        val fixture = createTestServer(
            mutableMapOf(
                "https://bedrock.test/model/amazon.titan-embed-text-v2%3A0/invoke" to UrlHandler(
                    UrlResponse.JsonValue(Json.parseToJsonElement("""{"embedding":[1.0,2.0],"inputTextTokenCount":7}""")),
                ),
                "https://bedrock.test/model/amazon.titan-image-generator-v2%3A0/invoke" to UrlHandler(
                    UrlResponse.JsonValue(Json.parseToJsonElement("""{"images":["aW1hZ2U="]}""")),
                ),
                "https://bedrock-agent.test/rerank" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """
                            {"results":[{"index":1,"relevanceScore":0.9},{"index":0,"relevanceScore":0.4}]}
                            """.trimIndent(),
                        ),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val provider = createAmazonBedrock(
            fixture.httpClient(),
            AmazonBedrockProviderSettings(
                apiKey = "key",
                region = "us-east-1",
                baseURL = "https://bedrock.test",
                agentBaseURL = "https://bedrock-agent.test",
            ),
        )

        val embedding = provider.embedding("amazon.titan-embed-text-v2:0").embed(
            EmbeddingModelCallParams(
                values = listOf("embed me"),
                providerOptions = mapOf(
                    "bedrock" to buildJsonObject {
                        put("dimensions", JsonPrimitive(256))
                        put("normalize", JsonPrimitive(true))
                    },
                ),
            ),
        )
        val image = provider.image("amazon.titan-image-generator-v2:0").generate(
            ImageGenerationParams(
                prompt = "A product render",
                n = 2,
                size = "512x768",
                seed = 42,
                providerOptions = mapOf(
                    "bedrock" to buildJsonObject { put("negativeText", JsonPrimitive("blur")) },
                ),
            ),
        )
        val rerank = provider.reranking("amazon.rerank-v1:0").rerank(
            RerankingParams(query = "best", documents = listOf("first", "second"), topN = 2),
        )

        assertEquals(listOf(1.0f, 2.0f), embedding.embeddings.single())
        assertEquals(7, embedding.usage.tokens)
        assertEquals("image", convertBase64ToByteArray(image.images.single().base64).decodeToString())
        assertEquals("second", rerank.results.first().value)
        assertEquals(0.9f, rerank.results.first().score)

        val embeddingBody = fixture.calls[0].requestBodyJson.jsonObject
        assertEquals("embed me", embeddingBody["inputText"]?.jsonPrimitive?.contentOrNull)
        assertEquals(256, embeddingBody["dimensions"]?.jsonPrimitive?.intOrNull)
        assertEquals(true, embeddingBody["normalize"]?.jsonPrimitive?.booleanOrNull)
        val imageBody = fixture.calls[1].requestBodyJson.jsonObject
        assertEquals("TEXT_IMAGE", imageBody["taskType"]?.jsonPrimitive?.contentOrNull)
        assertEquals(512, imageBody["imageGenerationConfig"]?.jsonObject?.get("width")?.jsonPrimitive?.intOrNull)
        assertEquals("blur", imageBody["textToImageParams"]?.jsonObject?.get("negativeText")?.jsonPrimitive?.contentOrNull)
        val rerankBody = fixture.calls[2].requestBodyJson.jsonObject
        assertEquals("arn:aws:bedrock:us-east-1::foundation-model/amazon.rerank-v1:0", rerankBody["rerankingConfiguration"]?.jsonObject?.get("bedrockRerankingConfiguration")?.jsonObject?.get("modelConfiguration")?.jsonObject?.get("modelArn")?.jsonPrimitive?.contentOrNull)
        assertEquals(2, rerankBody["rerankingConfiguration"]?.jsonObject?.get("bedrockRerankingConfiguration")?.jsonObject?.get("numberOfResults")?.jsonPrimitive?.intOrNull)
    }

    @Test
    fun `mantle facade maps OpenAI compatible chat and SigV4 settings are surfaced`() = runTest {
        val fixture = createTestServer(
            mutableMapOf(
                "https://mantle.test/v1/chat/completions" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """
                            {
                              "id":"chatcmpl-1",
                              "model":"openai.gpt-oss-20b-1:0",
                              "choices":[{"message":{"content":"hello"},"finish_reason":"stop"}],
                              "usage":{"prompt_tokens":2,"completion_tokens":3,"total_tokens":5}
                            }
                            """.trimIndent(),
                        ),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val provider = createBedrockMantle(
            fixture.httpClient(),
            BedrockMantleProviderSettings(apiKey = "key", baseURL = "https://mantle.test/v1"),
        )

        val result = provider.chat("openai.gpt-oss-20b-1:0").generate(
            LanguageModelCallParams(messages = listOf(userMessage("hi"))),
        )

        assertEquals("hello", result.text)
        assertEquals(FinishReason.Stop, result.finishReason)
        assertEquals(2, result.usage.promptTokens)
        assertEquals(3, result.usage.completionTokens)
        assertEquals("Bearer key", fixture.calls.single().requestHeaders.headerValue(HttpHeaders.Authorization))
        assertEquals("openai.gpt-oss-20b-1:0", fixture.calls.single().requestBodyJson.jsonObject["model"]?.jsonPrimitive?.contentOrNull)

        val sigV4Provider = createAmazonBedrock(
            fixture.httpClient(),
            AmazonBedrockProviderSettings(accessKeyId = "id", secretAccessKey = "secret", baseURL = "https://bedrock.test"),
        )
        val error = assertFailsWith<AiSdkException> {
            sigV4Provider.languageModel("amazon.nova-lite-v1:0").generate(
                LanguageModelCallParams(messages = listOf(userMessage("hi"))),
            )
        }
        assertTrue(error.message.orEmpty().contains("SigV4 request signing"))
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
