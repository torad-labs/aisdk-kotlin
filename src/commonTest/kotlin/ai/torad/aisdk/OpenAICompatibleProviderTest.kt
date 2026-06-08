package ai.torad.aisdk
import ai.torad.aisdk.providers.OpenAICompatibleProviderSettings
import ai.torad.aisdk.providers.createOpenAICompatible
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
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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

@Suppress("LargeClass")
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
                        "reasoningEffort" to JsonPrimitive("high"),
                        "textVerbosity" to JsonPrimitive("low"),
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
        // Canonical options must reach the wire under their snake_case keys.
        assertEquals("user_1", body["user"]?.jsonPrimitive?.content)
        assertEquals("high", body["reasoning_effort"]?.jsonPrimitive?.content)
        assertEquals("low", body["verbosity"]?.jsonPrimitive?.content)
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
    fun `chat model omits tool_choice when no tools are sent`() = runTest {
        val seenBodies = mutableListOf<JsonObject>()
        val client = HttpClient(
            MockEngine { request ->
                seenBodies += Json.parseToJsonElement(requestBodyText(request)).jsonObject
                respond(
                    content = "{\"id\":\"c\",\"choices\":[{\"message\":" +
                        "{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        val provider = createOpenAICompatible(
            client,
            OpenAICompatibleProviderSettings(name = "openai", baseUrl = "https://api.test/v1", apiKey = "secret"),
        )
        provider.languageModel("gpt-test").generate(LanguageModelCallParams(messages = listOf(userMessage("hi"))))
        // No tools → neither tools nor tool_choice in the body (strict servers reject lone tool_choice).
        val body = seenBodies.single()
        assertEquals(null, body["tool_choice"], "tool_choice omitted without tools")
        assertEquals(null, body["tools"])
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
    fun `chat response transform applies to generate and stream events`() = runTest {
        val client = HttpClient(
            MockEngine { request ->
                val body = Json.parseToJsonElement(requestBodyText(request)).jsonObject
                if (body["stream"]?.jsonPrimitive?.booleanOrNull == true) {
                    respond(
                        content = """
                            data: {"id":"stream-1","choices":[{"delta":{"wire_content":"streamed"}}]}

                            data: {"id":"stream-1","choices":[{"finish_reason":"stop"}]}

                        """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
                    )
                } else {
                    respond(
                        content = """
                            {"id":"generate-1","choices":[{"message":{"role":"assistant","wire_content":"generated"},"finish_reason":"stop"}]}
                        """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            },
        )
        val provider = createOpenAICompatible(
            client,
            OpenAICompatibleProviderSettings(
                name = "openai",
                baseUrl = "https://api.test/v1",
                transformChatResponse = ::rewriteWireContentResponse,
            ),
        )

        val generated = provider.languageModel("gpt-test").generate(
            LanguageModelCallParams(messages = listOf(userMessage("hi"))),
        )
        val events = drainAllItems(
            provider.languageModel("gpt-test").stream(LanguageModelCallParams(messages = listOf(userMessage("hi")))),
        )

        assertEquals("generated", generated.text)
        assertTrue(events.any { it is StreamEvent.TextDelta && it.text == "streamed" })
        assertEquals(FinishReason.Stop, events.filterIsInstance<StreamEvent.Finish>().single().finishReason)
    }

    @Test
    fun `chat model emits deltas incrementally as SSE chunks arrive rather than in one end burst`() = runTest {
        // The response body is a half-open channel we feed one SSE event at a
        // time. A buffered reader (bodyAsText) would block until the channel
        // CLOSES before emitting anything — so the firstDelta.await() below
        // would hang and fail this test. Incremental reading completes it.
        val body = ByteChannel(autoFlush = true)
        val client = HttpClient(
            MockEngine {
                respond(
                    content = body,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
                )
            },
        )
        val provider = createOpenAICompatible(
            client,
            OpenAICompatibleProviderSettings(name = "openai", baseUrl = "https://api.test/v1", includeUsage = true),
        )

        val deltas = mutableListOf<String>()
        val firstDelta = CompletableDeferred<Unit>()
        val collector = launch {
            provider.languageModel("gpt-test")
                .stream(LanguageModelCallParams(listOf(userMessage("hi"))))
                .collect { event ->
                    if (event is StreamEvent.TextDelta) {
                        deltas += event.text
                        if (deltas.size == 1) firstDelta.complete(Unit)
                    }
                }
        }

        // Write ONLY the first event, then prove its delta surfaces before the
        // rest of the body is ever written — the regression lock against
        // re-buffering the whole stream.
        body.writeStringUtf8("data: {\"choices\":[{\"delta\":{\"content\":\"hello\"}}]}\n\n")
        firstDelta.await()
        assertEquals(listOf("hello"), deltas)

        body.writeStringUtf8("data: {\"choices\":[{\"delta\":{\"content\":\" world\"}}]}\n\n")
        body.writeStringUtf8("data: {\"choices\":[{\"finish_reason\":\"stop\"}]}\n\n")
        body.writeStringUtf8("data: [DONE]\n\n")
        body.flushAndClose()
        collector.join()

        assertEquals(listOf("hello", " world"), deltas)
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

    @Test
    fun `approval bookkeeping messages never reach the OpenAI wire`() = runTest {
        // The approval-resume cycle appends a Tool-role ToolApprovalResponse message. OpenAI-format has no
        // approval concept — serializing it produced {role:"tool", tool_call_id:"", content:""}, which strict
        // shims reject (Gemini 400: function_response.name cannot be empty). The wire must see the assistant's
        // tool_calls entry plus exactly ONE tool message: the real result.
        val seenBodies = mutableListOf<JsonObject>()
        val client = HttpClient(
            MockEngine { request ->
                seenBodies += Json.parseToJsonElement(requestBodyText(request)).jsonObject
                val responseBody = """{"id":"c1","created":1,"model":"m","choices":[""" +
                    """{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}"""
                respond(
                    content = responseBody,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        val model = createOpenAICompatible(
            client,
            OpenAICompatibleProviderSettings(name = "test", baseUrl = "https://api.test/v1", apiKey = "k"),
        ).languageModel("m")
        val gatedInput = JsonObject(mapOf("configPath" to JsonPrimitive("run.yaml")))
        val messages = listOf(
            ModelMessage(MessageRole.User, listOf(ContentPart.Text("launch it"))),
            ModelMessage(
                MessageRole.Assistant,
                listOf(
                    ContentPart.ToolCall("call_gated", "rlLaunch", gatedInput),
                    ContentPart.ToolApprovalRequest("call_gated", "rlLaunch", gatedInput),
                ),
            ),
            ModelMessage(MessageRole.Tool, listOf(ContentPart.ToolApprovalResponse("call_gated", approved = true))),
            ModelMessage(
                MessageRole.Tool,
                listOf(ContentPart.ToolResult("call_gated", "rlLaunch", JsonPrimitive("launched"))),
            ),
        )

        model.generate(LanguageModelCallParams(messages = messages))

        val wireMessages = seenBodies.single()["messages"]!!.jsonArray.map { it.jsonObject }
        val toolMessages = wireMessages.filter { it["role"]?.jsonPrimitive?.content == "tool" }
        assertEquals(1, toolMessages.size, "only the real result is a wire tool message — approvals stay internal")
        assertEquals("call_gated", toolMessages.single()["tool_call_id"]?.jsonPrimitive?.content)
        assertTrue(
            wireMessages.none { it["tool_call_id"]?.jsonPrimitive?.contentOrNull == "" },
            "no message carries an empty tool_call_id",
        )
    }

    @Test
    fun `a Tool message with multiple results serializes one wire tool message per result`() = runTest {
        // Upstream (convert-to-openai-compatible-chat-messages) loops over every tool-result part and
        // emits one {role:"tool", tool_call_id, content} per result; OpenAI requires one tool message per
        // tool_call_id. A single SDK Tool-role ModelMessage can batch multiple results (e.g. AgentSession's
        // streaming state), so the serializer must expand them — not keep only the first.
        val seenBodies = mutableListOf<JsonObject>()
        val client = HttpClient(
            MockEngine { request ->
                seenBodies += Json.parseToJsonElement(requestBodyText(request)).jsonObject
                val responseBody = """{"id":"c1","created":1,"model":"m","choices":[""" +
                    """{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}"""
                respond(
                    content = responseBody,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        val model = createOpenAICompatible(
            client,
            OpenAICompatibleProviderSettings(name = "test", baseUrl = "https://api.test/v1", apiKey = "k"),
        ).languageModel("m")
        val messages = listOf(
            ModelMessage(MessageRole.User, listOf(ContentPart.Text("run both"))),
            ModelMessage(
                MessageRole.Assistant,
                listOf(
                    ContentPart.ToolCall("call_a", "alpha", JsonObject(emptyMap())),
                    ContentPart.ToolCall("call_b", "beta", JsonObject(emptyMap())),
                ),
            ),
            // One Tool-role message batching two results (the AgentSession streaming shape).
            ModelMessage(
                MessageRole.Tool,
                listOf(
                    ContentPart.ToolResult("call_a", "alpha", JsonPrimitive("ra")),
                    ContentPart.ToolResult("call_b", "beta", JsonPrimitive("rb")),
                ),
            ),
        )

        model.generate(LanguageModelCallParams(messages = messages))

        val wireMessages = seenBodies.single()["messages"]!!.jsonArray.map { it.jsonObject }
        val toolMessages = wireMessages.filter { it["role"]?.jsonPrimitive?.content == "tool" }
        assertEquals(2, toolMessages.size, "each result becomes its own wire tool message")
        assertEquals(
            listOf("call_a", "call_b"),
            toolMessages.map { it["tool_call_id"]?.jsonPrimitive?.content },
            "tool_call_ids preserved in order",
        )
        assertEquals(
            listOf("ra", "rb"),
            toolMessages.map { it["content"]?.jsonPrimitive?.content },
            "no result is dropped",
        )
    }

    private fun requestBodyText(request: HttpRequestData): String =
        when (val body = request.body) {
            is OutgoingContent.ByteArrayContent -> body.bytes().decodeToString()
            is OutgoingContent.NoContent -> ""
            else -> body.toString()
        }

    private fun rewriteWireContentResponse(value: JsonObject): JsonObject {
        val choices = value["choices"]?.jsonArray ?: return value
        return JsonObject(
            value + (
                "choices" to JsonArray(
                    choices.map { choice ->
                        val choiceObj = choice.jsonObject
                        val message = (choiceObj["message"] as? JsonObject)?.let(::rewriteWireContentPart)
                        val delta = (choiceObj["delta"] as? JsonObject)?.let(::rewriteWireContentPart)
                        JsonObject(
                            choiceObj +
                                listOfNotNull(
                                    message?.let { "message" to it },
                                    delta?.let { "delta" to it },
                                ).toMap(),
                        )
                    },
                )
                )
        )
    }

    private fun rewriteWireContentPart(value: JsonObject): JsonObject {
        val content = value["wire_content"] ?: return value
        return JsonObject(value - "wire_content" + ("content" to content))
    }

    private fun Map<String, List<String>>.headerValue(name: String): String? =
        entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.singleOrNull()
}
