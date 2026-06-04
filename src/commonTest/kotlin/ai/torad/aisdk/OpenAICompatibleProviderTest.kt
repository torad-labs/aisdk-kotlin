package ai.torad.aisdk

import ai.torad.aisdk.testing.drainAllItems
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

class OpenAICompatibleProviderTest {
    @Test
    fun `chat model posts OpenAI-compatible request and maps response content`() = runTest {
        val seenBodies = mutableListOf<JsonObject>()
        val seenHeaders = mutableListOf<Map<String, List<String>>>()
        val client = HttpClient(
            MockEngine { request ->
                seenHeaders += request.headers.entries().associate { it.key to it.value }
                seenBodies += Json.parseToJsonElement(requestBodyText(request)).jsonObject
                respond(
                    content = """
                        {
                          "id":"chatcmpl_1",
                          "created":1780000000,
                          "model":"gpt-test",
                          "choices":[{
                            "message":{
                              "role":"assistant",
                              "content":"hello",
                              "reasoning_content":"because",
                              "tool_calls":[{
                                "id":"call_1",
                                "type":"function",
                                "function":{"name":"search","arguments":"{\"query\":\"kotlin\"}"}
                              }]
                            },
                            "finish_reason":"tool_calls"
                          }],
                          "usage":{
                            "prompt_tokens":5,
                            "completion_tokens":7,
                            "prompt_tokens_details":{"cached_tokens":2},
                            "completion_tokens_details":{"reasoning_tokens":3}
                          }
                        }
                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        val provider = createOpenAICompatible(
            client,
            OpenAICompatibleProviderSettings(
                name = "openai",
                baseUrl = "https://api.test/v1",
                apiKey = "secret",
                headers = mapOf("x-project" to "aisdk"),
                queryParams = mapOf("api-version" to "2026-06-03"),
                supportsStructuredOutputs = true,
            ),
        )

        val result = generateText(
            model = provider.languageModel("gpt-test"),
            prompt = "hi",
            responseFormat = ResponseFormat.Json(
                schemaName = "Answer",
                schemaJson = JsonObject(mapOf("type" to JsonPrimitive("object"))),
            ),
            providerOptions = mapOf(
                "openai" to JsonObject(
                    mapOf(
                        "user" to JsonPrimitive("user_1"),
                        "parallel_tool_calls" to JsonPrimitive(false),
                    ),
                ),
            ),
        )

        val body = seenBodies.single()
        assertEquals("Bearer secret", seenHeaders.single().headerValue("Authorization"))
        assertEquals("aisdk", seenHeaders.single()["x-project"]?.single())
        assertEquals("gpt-test", body["model"]?.jsonPrimitive?.content)
        assertEquals("hi", body["messages"]?.jsonArray?.single()?.jsonObject?.get("content")?.jsonPrimitive?.content)
        assertEquals("json_schema", body["response_format"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertEquals(false, body["parallel_tool_calls"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("hello", result.text)
        assertEquals("because", result.reasoningText)
        assertEquals(FinishReason.ToolCalls, result.finishReason)
        assertEquals("search", result.toolCalls.single().toolName)
        assertEquals(JsonPrimitive("kotlin"), result.toolCalls.single().input.jsonObject["query"])
        assertEquals(5, result.usage.promptTokens)
        assertEquals(7, result.usage.completionTokens)
        assertEquals(2, result.usage.inputTokens.cacheRead)
        assertEquals(3, result.usage.outputTokens.reasoning)
        assertEquals("chatcmpl_1", result.response.id)
        assertEquals("gpt-test", result.response.modelId)
    }

    @Test
    fun `chat model preserves per-tool strict flag in OpenAI-compatible request`() = runTest {
        val seenBodies = mutableListOf<JsonObject>()
        val client = HttpClient(
            MockEngine { request ->
                seenBodies += Json.parseToJsonElement(requestBodyText(request)).jsonObject
                respond(
                    content = """{"id":"chatcmpl_1","choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        val provider = createOpenAICompatible(
            client,
            OpenAICompatibleProviderSettings(name = "openai", baseUrl = "https://api.test/v1", apiKey = "secret"),
        )

        provider.languageModel("gpt-test").generate(
            LanguageModelCallParams(
                messages = listOf(userMessage("hi")),
                tools = listOf(
                    LanguageModelTool(
                        name = "loose",
                        description = "Loose schema",
                        parametersSchemaJson = """{"type":"object"}""",
                        strict = false,
                    ),
                ),
            ),
        )

        val strict = seenBodies.single()
            .getValue("tools")
            .jsonArray
            .single()
            .jsonObject
            .getValue("function")
            .jsonObject
            .getValue("strict")
            .jsonPrimitive
            .booleanOrNull
        assertEquals(false, strict)
    }

    @Test
    fun `chat model streams text reasoning tool calls and finish usage`() = runTest {
        val client = HttpClient(
            MockEngine { request ->
                val body = Json.parseToJsonElement(requestBodyText(request)).jsonObject
                assertEquals(true, body["stream"]?.jsonPrimitive?.booleanOrNull)
                respond(
                    content = """
                        data: {"id":"1","choices":[{"delta":{"reasoning_content":"think"}}]}

                        data: {"id":"1","choices":[{"delta":{"content":"hello"}}]}

                        data: {"id":"1","choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"search","arguments":"{\"q\""}}]}}]}

                        data: {"id":"1","choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":":\"docs\"}"}}]}}]}

                        data: {"id":"1","choices":[{"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":1,"completion_tokens":2}}

                        data: [DONE]

                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
                )
            },
        )
        val provider = createOpenAICompatible(
            client,
            OpenAICompatibleProviderSettings(name = "openai", baseUrl = "https://api.test/v1", includeUsage = true),
        )

        val events = drainAllItems(provider.languageModel("gpt-test").stream(LanguageModelCallParams(listOf(userMessage("hi")))))

        assertIs<StreamEvent.StreamStart>(events[0])
        val metadata = events.filterIsInstance<StreamEvent.ResponseMetadata>()
        assertEquals("1", metadata.last().id)
        assertTrue(events.any { it is StreamEvent.ReasoningDelta && it.text == "think" })
        assertTrue(events.any { it is StreamEvent.TextDelta && it.text == "hello" })
        val toolCall = events.filterIsInstance<StreamEvent.ToolCall>().single()
        assertEquals("search", toolCall.toolName)
        assertEquals(JsonPrimitive("docs"), toolCall.inputJson.jsonObject["q"])
        val finish = events.filterIsInstance<StreamEvent.Finish>().single()
        assertEquals(FinishReason.ToolCalls, finish.finishReason)
        assertEquals(1, finish.usage.promptTokens)
        assertEquals(2, finish.usage.completionTokens)
    }

    @Test
    fun `completion model maps prompt response and streaming deltas`() = runTest {
        val seenPaths = mutableListOf<String>()
        val client = HttpClient(
            MockEngine { request ->
                seenPaths += request.url.encodedPath
                val body = Json.parseToJsonElement(requestBodyText(request)).jsonObject
                if (body["stream"]?.jsonPrimitive?.booleanOrNull == true) {
                    respond(
                        content = """
                            data: {"choices":[{"text":"a"}]}

                            data: {"choices":[{"text":"b","finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":2}}

                        """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
                    )
                } else {
                    respond(
                        content = """{"id":"cmpl_1","model":"davinci","choices":[{"text":"done","finish_reason":"stop"}],"usage":{"prompt_tokens":3,"completion_tokens":4}}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            },
        )
        val provider = createOpenAICompatible(client, OpenAICompatibleProviderSettings("openai", "https://api.test/v1"))

        val generated = generateText(provider.completionModel("davinci"), prompt = "complete")
        val streamed = drainAllItems(provider.completionModel("davinci").stream(LanguageModelCallParams(listOf(userMessage("hi")))))

        assertEquals("done", generated.text)
        assertEquals(3, generated.usage.promptTokens)
        assertTrue(streamed.any { it is StreamEvent.TextDelta && it.text == "a" })
        assertTrue(streamed.any { it is StreamEvent.TextDelta && it.text == "b" })
        assertEquals(listOf("/v1/completions", "/v1/completions"), seenPaths)
    }

    @Test
    fun `image model preserves url-only OpenAI-compatible image output`() = runTest {
        val client = HttpClient(
            MockEngine {
                respond(
                    """{"data":[{"url":"https://cdn.test/image.png","media_type":"image/jpeg"}]}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        val provider = createOpenAICompatible(client, OpenAICompatibleProviderSettings("openai", "https://api.test/v1"))

        val image = generateImage(provider.imageModel("image"), prompt = "logo")

        assertEquals("", image.image.base64)
        assertEquals("https://cdn.test/image.png", image.image.url)
        assertEquals("image/jpeg", image.image.mediaType)
    }

    @Test
    fun `embedding image speech and transcription models map native endpoints`() = runTest {
        val seenPaths = mutableListOf<String>()
        val seenContentTypes = mutableListOf<String?>()
        val client = HttpClient(
            MockEngine { request ->
                seenPaths += request.url.encodedPath
                seenContentTypes += request.headers[HttpHeaders.ContentType] ?: request.body.contentType?.toString()
                when (request.url.encodedPath) {
                    "/v1/embeddings" -> respond(
                        """{"data":[{"index":1,"embedding":[3,4]},{"index":0,"embedding":[1,2]}],"usage":{"prompt_tokens":9},"providerMetadata":{"openai":{"batch":"ok"}}}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                    "/v1/images/generations" -> respond(
                        """{"data":[{"b64_json":"iVBORw0="}]}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                    "/v1/audio/speech" -> respond(
                        "abc",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "audio/mpeg"),
                    )
                    "/v1/audio/transcriptions" -> respond(
                        """{"text":"hello audio","segments":[{"text":"hello","start":0.0,"end":1.5}]}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                    else -> respond("{}", HttpStatusCode.NotFound, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            },
        )
        val provider = createOpenAICompatible(client, OpenAICompatibleProviderSettings("openai", "https://api.test/v1"))

        val embedding = embedMany(provider.embeddingModel("embed"), listOf("a", "b"))
        val image = generateImage(provider.imageModel("image"), prompt = "logo", aspectRatio = "1:1", seed = 1)
        val speech = generateSpeech(provider.speechModel("tts"), text = "hello", voice = "alloy")
        val transcript = transcribe(
            provider.transcriptionModel("whisper"),
            AudioSource(mediaType = "audio/mpeg", base64 = convertByteArrayToBase64("abc".encodeToByteArray())),
        )

        assertEquals(listOf(listOf(1f, 2f), listOf(3f, 4f)), embedding.embeddings)
        assertEquals(9, embedding.usage.tokens)
        assertEquals("iVBORw0=", image.image.base64)
        assertEquals(2, image.warnings.size)
        assertEquals("YWJj", speech.audio.base64)
        assertEquals("audio/mpeg", speech.audio.mediaType)
        assertEquals("hello audio", transcript.text)
        assertEquals(1.5f, transcript.segments.single().endSeconds)
        assertEquals(
            listOf("/v1/embeddings", "/v1/images/generations", "/v1/audio/speech", "/v1/audio/transcriptions"),
            seenPaths,
        )
        assertTrue(seenContentTypes.last()?.startsWith("multipart/form-data") == true)
    }

    @Test
    fun `embedding model rejects non numeric vector values`() = runTest {
        val client = HttpClient(
            MockEngine {
                respond(
                    """{"data":[{"index":0,"embedding":[1,"bad"]}],"usage":{"prompt_tokens":1}}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        val provider = createOpenAICompatible(client, OpenAICompatibleProviderSettings("openai", "https://api.test/v1"))

        val error = assertFailsWith<WireDecodeException> {
            embed(provider.embeddingModel("embed"), "a")
        }

        assertEquals("openai.embedding", error.provider)
        assertTrue(error.message.orEmpty().contains("expected number"))
    }

    @Test
    fun `chat model rejects malformed tool call wire data`() = runTest {
        val client = HttpClient(
            MockEngine {
                respond(
                    """{"choices":[{"message":{"role":"assistant","tool_calls":[{"id":"call_1","function":{"arguments":"{}"}}]},"finish_reason":"tool_calls"}]}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        val provider = createOpenAICompatible(client, OpenAICompatibleProviderSettings("openai", "https://api.test/v1"))

        val error = assertFailsWith<WireDecodeException> {
            generateText(provider.languageModel("gpt-test"), prompt = "hi")
        }

        assertEquals("openai.chat", error.provider)
        assertTrue(error.message.orEmpty().contains("tool_calls[0].function.name"))
    }

    @Test
    fun `completion and media models reject malformed required wire fields`() = runTest {
        val client = HttpClient(
            MockEngine { request ->
                when (request.url.encodedPath) {
                    "/v1/completions" -> respond(
                        """{"choices":[{}]}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                    "/v1/images/generations" -> respond(
                        """{"data":[{"media_type":"image/png"}]}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                    "/v1/audio/transcriptions" -> respond(
                        """{"text":"hello","segments":[{"start":0.0}]}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                    else -> respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            },
        )
        val provider = createOpenAICompatible(client, OpenAICompatibleProviderSettings("openai", "https://api.test/v1"))

        val completionError = assertFailsWith<WireDecodeException> {
            generateText(provider.completionModel("davinci"), prompt = "complete")
        }
        val imageError = assertFailsWith<WireDecodeException> {
            generateImage(provider.imageModel("image"), prompt = "logo")
        }
        val transcriptError = assertFailsWith<WireDecodeException> {
            transcribe(
                provider.transcriptionModel("whisper"),
                AudioSource(mediaType = "audio/mpeg", base64 = convertByteArrayToBase64("abc".encodeToByteArray())),
            )
        }

        assertTrue(completionError.message.orEmpty().contains("choices[0].text"))
        assertTrue(imageError.message.orEmpty().contains("b64_json or url"))
        assertTrue(transcriptError.message.orEmpty().contains("segments[0].text"))
    }

    @Test
    fun `OpenAI-compatible errors include status and provider message`() = runTest {
        val client = HttpClient(
            MockEngine {
                respond(
                    """{"error":{"message":"bad key","type":"invalid_request_error"}}""",
                    HttpStatusCode.Unauthorized,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        val provider = createOpenAICompatible(client, OpenAICompatibleProviderSettings("openai", "https://api.test/v1"))

        val error = assertFailsWith<APICallError> {
            generateText(provider.languageModel("gpt-test"), prompt = "hi")
        }

        // APICallError is an AiSdkException, so existing catch(AiSdkException) still catches it.
        assertIs<AiSdkException>(error)
        assertNotNull(error.message)
        assertTrue(error.message.orEmpty().contains("401"))
        assertTrue(error.message.orEmpty().contains("bad key"))
        // Rich fields: callers/retry layers can now branch on status/retryability/body.
        assertEquals(401, error.statusCode)
        assertEquals(false, error.isRetryable)
        assertNotNull(error.responseBody)
        assertTrue(error.responseBody.orEmpty().contains("bad key"))
        assertNotNull(error.responseHeaders)
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
