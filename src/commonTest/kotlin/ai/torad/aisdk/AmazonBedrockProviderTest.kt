@file:OptIn(LowLevelLanguageModelApi::class)

package ai.torad.aisdk
import ai.torad.aisdk.providers.AMAZON_BEDROCK_VERSION
import ai.torad.aisdk.providers.AmazonBedrock
import ai.torad.aisdk.providers.AmazonBedrockProviderSettings
import ai.torad.aisdk.providers.BedrockMantle
import ai.torad.aisdk.providers.BedrockMantleProviderSettings
import ai.torad.aisdk.testing.FlowDrain.drainAllItems
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AmazonBedrockProviderTest {
    @Test
    fun `chat model maps Converse request response metadata and auth`() = runTest {
        val fixture = TestServer.createTestServer(
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
        val provider = AmazonBedrock(
            fixture.httpClient(),
            AmazonBedrockProviderSettings(
                apiKey = "key",
                baseURL = "https://bedrock.test",
                headers = mapOf("X-Provider" to "provider"),
            ),
        )

        val result = provider(ModelId("anthropic.claude-3-7-sonnet-20250219-v1:0")).generate(
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
                                providerMetadata = ProviderMetadata.Raw(JsonObject(mapOf(
                                    "bedrock" to buildJsonObject {
                                        put("citations", buildJsonObject { put("enabled", JsonPrimitive(true)) })
                                    },
                                ))),
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
                providerOptions = ProviderOptions.Raw(JsonObject(mapOf(
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
                ))),
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
        assertEquals("</done>", result.providerMetadata.toMap()["bedrock"]?.jsonObject?.get("stopSequence")?.jsonPrimitive?.contentOrNull)

        val request = fixture.calls.single()
        assertEquals("POST", request.requestMethod)
        assertEquals("Bearer key", request.requestHeaders.headerValue(HttpHeaders.Authorization))
        assertEquals("provider", request.requestHeaders.headerValue("X-Provider"))
        assertEquals("request", request.requestHeaders.headerValue("X-Request"))
        assertTrue(request.requestUserAgent.orEmpty().contains("ai-sdk/amazon-bedrock/$AMAZON_BEDROCK_VERSION"))
        val body = request.requestBodyJson.jsonObject
        assertEquals("Follow policy.", body["system"]?.jsonArray?.single()?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull)
        assertEquals("priority", body["serviceTier"]?.jsonObject?.get("type")?.jsonPrimitive?.contentOrNull)
        // Anthropic thinking is enabled, so Bedrock rejects sampling params — they're stripped,
        // and the thinking budget (32) is added to maxTokens (64 + 32 = 96).
        val inference = body["inferenceConfig"]?.jsonObject
        assertEquals(null, inference?.get("temperature"), "temperature stripped with thinking")
        assertEquals(null, inference?.get("topP"), "topP stripped with thinking")
        assertEquals(null, inference?.get("topK"), "topK stripped with thinking")
        assertEquals(96, inference?.get("maxTokens")?.jsonPrimitive?.intOrNull)
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
    fun `tool result serializes as a text block with no status field`() = runTest {
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://bedrock.test/model/anthropic.claude-3-7-sonnet-20250219-v1%3A0/converse" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """{"output":{"message":{"role":"assistant","content":[{"text":"ok"}]}},
                               "stopReason":"end_turn","usage":{"inputTokens":1,"outputTokens":1,"totalTokens":2}}""",
                        ),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val provider = AmazonBedrock(
            fixture.httpClient(),
            AmazonBedrockProviderSettings(apiKey = "key", baseURL = "https://bedrock.test"),
        )
        provider(ModelId("anthropic.claude-3-7-sonnet-20250219-v1:0")).generate(
            LanguageModelCallParams(
                messages = listOf(
                    ModelMessage(MessageRole.User, listOf(ContentPart.Text("go"))),
                    ModelMessage(
                        MessageRole.Assistant,
                        listOf(ContentPart.ToolCall("t1", "lookup", JsonObject(emptyMap()))),
                    ),
                    ModelMessage(
                        MessageRole.Tool,
                        // A JSON (non-string) tool result + isError — must become a text block, no status.
                        listOf(
                            ContentPart.ToolResult(
                                "t1",
                                "lookup",
                                Json.parseToJsonElement("""{"temp":20}"""),
                                isError = true,
                            ),
                        ),
                    ),
                ),
            ),
        )
        val messages = fixture.calls.single().requestBodyJson.jsonObject["messages"]!!.jsonArray
        val lastContent = messages.last().jsonObject["content"]!!.jsonArray.single().jsonObject
        val toolResult = lastContent["toolResult"]!!.jsonObject
        val block = toolResult["content"]!!.jsonArray.single().jsonObject
        assertEquals("""{"temp":20}""", block["text"]?.jsonPrimitive?.contentOrNull, "non-string output → text block")
        assertEquals(null, block["json"], "no json block (Bedrock rejects it)")
        assertEquals(null, toolResult["status"], "Bedrock toolResult has no status field")
    }

    @Test
    fun `chat stream maps decoded Bedrock event stream chunks`() = runTest {
        val fixture = TestServer.createTestServer(
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
        val provider = AmazonBedrock(
            fixture.httpClient(),
            AmazonBedrockProviderSettings(apiKey = "key", baseURL = "https://bedrock.test"),
        )

        val events = drainAllItems(
            provider.languageModel("anthropic.claude-3-7-sonnet-20250219-v1:0").stream(
                LanguageModelCallParams(messages = listOf(UserMessage("hi"))),
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
    fun `chat stream decodes Smithy binary event stream frames`() = runTest {
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://bedrock.test/model/amazon.nova-lite-v1%3A0/converse-stream" to UrlHandler(
                    UrlResponse.Binary(
                        bedrockSmithyEventStream(
                            "messageStart" to """{"role":"assistant"}""",
                            "contentBlockDelta" to """{"contentBlockIndex":0,"delta":{"text":"binary"}}""",
                            "contentBlockStop" to """{"contentBlockIndex":0}""",
                            "messageStop" to """{"stopReason":"end_turn"}""",
                            "metadata" to """{"usage":{"inputTokens":2,"outputTokens":3}}""",
                        ),
                        headers = mapOf(
                            HttpHeaders.ContentType to "application/vnd.amazon.eventstream",
                            "x-amzn-requestid" to "binary-stream",
                        ),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val provider = AmazonBedrock(
            fixture.httpClient(),
            AmazonBedrockProviderSettings(apiKey = "key", baseURL = "https://bedrock.test"),
        )

        val events = drainAllItems(
            provider.languageModel("amazon.nova-lite-v1:0").stream(
                LanguageModelCallParams(messages = listOf(UserMessage("hi"))),
            ),
        )

        assertTrue(events.any { it is StreamEvent.ResponseMetadata && it.id == "binary-stream" })
        assertTrue(events.any { it is StreamEvent.TextDelta && it.text == "binary" })
        val finish = events.filterIsInstance<StreamEvent.Finish>().single()
        assertEquals(FinishReason.Stop, finish.finishReason)
        assertEquals(2, finish.usage.promptTokens)
        assertEquals(3, finish.usage.completionTokens)
    }

    @Test
    fun `chat stream surfaces a mid-stream modeled exception sent via colon-exception-type`() = runTest {
        // AWS sends a modeled error event with :message-type=exception and the
        // union member name in :exception-type (camelCase) -- NOT :event-type and
        // NOT :error-code. The decoder must read :exception-type so the payload
        // is wrapped under e.g. "internalServerException" and accept() raises it.
        val errorPayload = """{"message":"boom"}"""
        val frames = bedrockSmithyFrame(
            headers = smithyStringHeader(":message-type", "exception") +
                smithyStringHeader(":exception-type", "internalServerException"),
            payload = errorPayload.encodeToByteArray(),
        )
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://bedrock.test/model/amazon.nova-lite-v1%3A0/converse-stream" to UrlHandler(
                    UrlResponse.Binary(
                        frames,
                        headers = mapOf(HttpHeaders.ContentType to "application/vnd.amazon.eventstream"),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val provider = AmazonBedrock(
            fixture.httpClient(),
            AmazonBedrockProviderSettings(apiKey = "key", baseURL = "https://bedrock.test"),
        )

        val events = drainAllItems(
            provider.languageModel("amazon.nova-lite-v1:0").stream(
                LanguageModelCallParams(messages = listOf(UserMessage("hi"))),
            ),
        )

        val error = events.filterIsInstance<StreamEvent.Error>().single()
        assertTrue(error.message.contains("boom"))
    }

    @Test
    fun `chat stream decodes binary frames delivered across split reads`() = runTest {
        // The binary frame reader must reassemble a frame across multiple channel
        // reads. Deliver the bytes in two halves with the collector running in
        // between: a busy-spin reader or a buffer-the-whole-body reader would
        // hang here (the second half is written only after runCurrent()).
        val frames = bedrockSmithyEventStream(
            "messageStart" to """{"role":"assistant"}""",
            "contentBlockDelta" to """{"contentBlockIndex":0,"delta":{"text":"split"}}""",
            "contentBlockStop" to """{"contentBlockIndex":0}""",
            "messageStop" to """{"stopReason":"end_turn"}""",
        )
        val body = ByteChannel(autoFlush = true)
        val client = HttpClient(
            MockEngine {
                respond(
                    content = body,
                    headers = headersOf(HttpHeaders.ContentType, "application/vnd.amazon.eventstream"),
                )
            },
        )
        val provider = AmazonBedrock(
            client,
            AmazonBedrockProviderSettings(apiKey = "key", baseURL = "https://bedrock.test"),
        )

        val deltas = mutableListOf<String>()
        val collector = launch {
            provider.languageModel("amazon.nova-lite-v1:0")
                .stream(LanguageModelCallParams(messages = listOf(UserMessage("hi"))))
                .collect { if (it is StreamEvent.TextDelta) deltas += it.text }
        }

        // writeFully takes (startIndex, endIndex) — kotlinx-io convention.
        val mid = frames.size / 2
        body.writeFully(frames, 0, mid)
        runCurrent()
        body.writeFully(frames, mid, frames.size)
        body.flushAndClose()
        collector.join()

        assertEquals(listOf("split"), deltas)
    }

    @Test
    fun `embedding image and reranking models map Bedrock runtime payloads`() = runTest {
        val fixture = TestServer.createTestServer(
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
        val provider = AmazonBedrock(
            fixture.httpClient(),
            AmazonBedrockProviderSettings(
                apiKey = "key",
                region = "us-east-1",
                baseURL = "https://bedrock.test",
                agentBaseURL = "https://bedrock-agent.test",
            ),
        )

        val embedding = provider.embedding(ModelId("amazon.titan-embed-text-v2:0")).embed(
            EmbeddingModelCallParams(
                values = listOf("embed me"),
                providerOptions = ProviderOptions.Raw(JsonObject(mapOf(
                    "bedrock" to buildJsonObject {
                        put("dimensions", JsonPrimitive(256))
                        put("normalize", JsonPrimitive(true))
                    },
                ))),
            ),
        )
        val image = provider.image(ModelId("amazon.titan-image-generator-v2:0")).generate(
            ImageGenerationParams(
                prompt = "A product render",
                n = 2,
                size = "512x768",
                seed = 42,
                providerOptions = ProviderOptions.Raw(JsonObject(mapOf(
                    "bedrock" to buildJsonObject { put("negativeText", JsonPrimitive("blur")) },
                ))),
            ),
        )
        val rerank = provider.reranking(ModelId("amazon.rerank-v1:0")).rerank(
            RerankingParams(query = "best", documents = listOf("first", "second"), topN = 2),
        )

        assertEquals(listOf(1.0f, 2.0f), embedding.embeddings.single())
        assertEquals(1, provider.embedding(ModelId("amazon.titan-embed-text-v2:0")).maxEmbeddingsPerCall)
        assertEquals(true, provider.embedding(ModelId("amazon.titan-embed-text-v2:0")).supportsParallelCalls)
        assertEquals(7, embedding.usage.tokens)
        assertEquals("image", Base64Codec.decode(image.images.single().base64).decodeToString())
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
        val fixture = TestServer.createTestServer(
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
                "https://bedrock.test/model/amazon.nova-lite-v1%3A0/converse" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """
                            {
                              "output":{"message":{"role":"assistant","content":[{"text":"signed"}]}},
                              "stopReason":"end_turn",
                              "usage":{"inputTokens":1,"outputTokens":1}
                            }
                            """.trimIndent(),
                        ),
                        headers = mapOf("x-amzn-requestid" to "req-signed"),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val provider = BedrockMantle(
            fixture.httpClient(),
            BedrockMantleProviderSettings(apiKey = "key", baseURL = "https://mantle.test/v1"),
        )

        val result = provider.chat(ModelId("openai.gpt-oss-20b-1:0")).generate(
            LanguageModelCallParams(messages = listOf(UserMessage("hi"))),
        )

        assertEquals("hello", result.text)
        assertEquals(FinishReason.Stop, result.finishReason)
        assertEquals(2, result.usage.promptTokens)
        assertEquals(3, result.usage.completionTokens)
        assertEquals("Bearer key", fixture.calls.single().requestHeaders.headerValue(HttpHeaders.Authorization))
        assertEquals("openai.gpt-oss-20b-1:0", fixture.calls.single().requestBodyJson.jsonObject["model"]?.jsonPrimitive?.contentOrNull)

        val sigV4Provider = AmazonBedrock(
            fixture.httpClient(),
            AmazonBedrockProviderSettings(
                accessKeyId = "id",
                secretAccessKey = "secret",
                sessionToken = "token",
                baseURL = "https://bedrock.test",
            ),
        )
        val signed = sigV4Provider.languageModel("amazon.nova-lite-v1:0").generate(
            LanguageModelCallParams(messages = listOf(UserMessage("hi"))),
        )
        val signedRequest = fixture.calls.last()
        assertEquals("signed", signed.text)
        assertEquals("token", signedRequest.requestHeaders.headerValue("x-amz-security-token"))
        assertEquals("bedrock.test", signedRequest.requestHeaders.headerValue("host"))
        assertTrue(signedRequest.requestHeaders.headerValue(HttpHeaders.Authorization).orEmpty().contains("AWS4-HMAC-SHA256"))
        assertTrue(signedRequest.requestHeaders.headerValue(HttpHeaders.Authorization).orEmpty().contains("Credential=id/"))
        assertTrue(signedRequest.requestHeaders.headerValue(HttpHeaders.Authorization).orEmpty().contains("/bedrock/aws4_request"))
    }

    @Test
    fun `mantle chat decodes OpenAI shaped tool_calls into ContentPart ToolCall`() = runTest {
        val toolCall =
            """{"id":"call_abc","function":{"name":"get_weather","arguments":"{\"city\":\"Paris\"}"}}"""
        val message = """{"content":null,"tool_calls":[$toolCall]}"""
        val body = """{"choices":[{"message":$message,"finish_reason":"tool_calls"}]}"""
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://mantle.test/v1/chat/completions" to
                    UrlHandler(UrlResponse.JsonValue(Json.parseToJsonElement(body))),
            ),
        )
        fixture.server.start()
        val provider = BedrockMantle(
            fixture.httpClient(),
            BedrockMantleProviderSettings(apiKey = "key", baseURL = "https://mantle.test/v1"),
        )
        val result = provider.chat(ModelId("m")).generate(
            LanguageModelCallParams(messages = listOf(UserMessage("hi"))),
        )
        assertEquals(FinishReason.ToolCalls, result.finishReason)
        val call = result.toolCalls.single()
        assertEquals("call_abc", call.toolCallId)
        assertEquals("get_weather", call.toolName)
        assertEquals("Paris", call.input.jsonObject["city"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `bedrock SigV4 clock skew errors include actionable clock guidance`() = runTest {
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://bedrock.test/model/amazon.nova-lite-v1%3A0/converse" to UrlHandler(
                    UrlResponse.Error(
                        status = 403,
                        body = """
                            {
                              "__type":"com.amazonaws.bedrock#RequestTimeTooSkewed",
                              "message":"Signature expired: 20260603T010000Z is now earlier than 20260603T011000Z"
                            }
                        """.trimIndent(),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val provider = AmazonBedrock(
            fixture.httpClient(),
            AmazonBedrockProviderSettings(
                accessKeyId = "id",
                secretAccessKey = "secret",
                baseURL = "https://bedrock.test",
            ),
        )

        val error = assertFailsWith<APICallError> {
            provider.languageModel("amazon.nova-lite-v1:0").generate(
                LanguageModelCallParams(messages = listOf(UserMessage("hi"))),
            )
        }

        assertTrue(error.message.orEmpty().contains("local clock appears to be skewed"))
        assertTrue(error.message.orEmpty().contains("Signature expired"))
        // Rich APICallError fields populated by the shared transport helper.
        assertEquals(403, error.statusCode)
        assertNotNull(error.responseBody)
        assertTrue(error.responseBody.orEmpty().contains("RequestTimeTooSkewed"))
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

    private fun bedrockSmithyEventStream(vararg events: Pair<String, String>): ByteArray =
        events.fold(ByteArray(0)) { acc, (eventType, payload) ->
            acc + bedrockSmithyFrame(
                headers = smithyStringHeader(":message-type", "event") + smithyStringHeader(":event-type", eventType),
                payload = payload.encodeToByteArray(),
            )
        }

    private fun bedrockSmithyFrame(headers: ByteArray, payload: ByteArray): ByteArray {
        val totalLength = 12 + headers.size + payload.size + 4
        return ByteArray(totalLength).also { frame ->
            frame.writeInt32BE(0, totalLength)
            frame.writeInt32BE(4, headers.size)
            headers.copyInto(frame, destinationOffset = 12)
            payload.copyInto(frame, destinationOffset = 12 + headers.size)
        }
    }

    private fun smithyStringHeader(name: String, value: String): ByteArray {
        val nameBytes = name.encodeToByteArray()
        val valueBytes = value.encodeToByteArray()
        return byteArrayOf(nameBytes.size.toByte()) +
            nameBytes +
            byteArrayOf(7) +
            byteArrayOf(((valueBytes.size ushr 8) and 0xff).toByte(), (valueBytes.size and 0xff).toByte()) +
            valueBytes
    }

    private fun ByteArray.writeInt32BE(index: Int, value: Int) {
        this[index] = (value ushr 24).toByte()
        this[index + 1] = (value ushr 16).toByte()
        this[index + 2] = (value ushr 8).toByte()
        this[index + 3] = value.toByte()
    }

    private fun Map<String, String>.headerValue(name: String): String? =
        entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}
