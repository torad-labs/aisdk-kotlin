@file:OptIn(LowLevelLanguageModelApi::class)

package ai.torad.aisdk
import ai.torad.aisdk.providers.OpenAICompatible
import ai.torad.aisdk.providers.OpenAICompatibleProviderSettings
import ai.torad.aisdk.testing.FlowDrain.drainAllItems
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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
                            "completion_tokens_details":{
                              "reasoning_tokens":3,
                              "accepted_prediction_tokens":4,
                              "rejected_prediction_tokens":1
                            }
                          }
                        }
                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        val provider = OpenAICompatible(
            client,
            OpenAICompatibleProviderSettings {
                name("openai")
                baseUrl("https://api.test/v1")
                apiKey("secret")
                headers(mapOf("x-project" to "aisdk"))
                queryParams(mapOf("api-version" to "2026-06-03"))
                supportsStructuredOutputs(true)
            },
        )

        val result = TextGenerator(
            provider.languageModel("gpt-test"),
            CallConfig {
                responseFormat(
                    ResponseFormat.Json(
                        schemaName = "Answer",
                        schemaJson = JsonObject(mapOf("type" to JsonPrimitive("object"))),
                    )
                )
                providerOptions(
                    ProviderOptions.Raw(
                        JsonObject(
                            mapOf(
                                "openai" to JsonObject(
                                    mapOf(
                                        "user" to JsonPrimitive("user_1"),
                                        "reasoningEffort" to JsonPrimitive("high"),
                                        "textVerbosity" to JsonPrimitive("low"),
                                        "parallel_tool_calls" to JsonPrimitive(false),
                                    ),
                                ),
                            )
                        )
                    )
                )
            },
        ).generate(GenerationInput.Prompt("hi")).first()

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
        val providerMetadata = result.providerMetadata.toMap()["openaiCompatible"]?.jsonObject
        assertEquals(4, providerMetadata?.get("acceptedPredictionTokens")?.jsonPrimitive?.intOrNull)
        assertEquals(1, providerMetadata?.get("rejectedPredictionTokens")?.jsonPrimitive?.intOrNull)
        assertEquals("chatcmpl_1", result.response.id)
        assertEquals("gpt-test", result.response.modelId)
    }

    @Test
    fun `chat model omits default tool strict and preserves explicit strict flags`() = runTest {
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
        val provider = OpenAICompatible(
            client,
            OpenAICompatibleProviderSettings {
                name("openai")
                baseUrl("https://api.test/v1")
                apiKey("secret")
            },
        )

        provider.languageModel("gpt-test").generate(
            LanguageModelCallParams {
                messages(listOf(UserMessage("hi")))
                tools(
                    listOf(
                        LanguageModelTool(
                            name = "default",
                            description = "Default schema",
                            parametersSchemaJson = """{"type":"object"}""",
                        ),
                        LanguageModelTool(
                            name = "strict",
                            description = "Strict schema",
                            parametersSchemaJson = """{"type":"object"}""",
                            strict = true,
                        ),
                        LanguageModelTool(
                            name = "loose",
                            description = "Loose schema",
                            parametersSchemaJson = """{"type":"object"}""",
                            strict = false,
                        ),
                    )
                )
            },
        )

        val tools = seenBodies.single()
            .getValue("tools")
            .jsonArray
            .map { toolJson ->
                toolJson.jsonObject.getValue("function").jsonObject
            }
        assertTrue("strict" !in tools[0])
        assertEquals(true, tools[1].getValue("strict").jsonPrimitive.booleanOrNull)
        assertEquals(false, tools[2].getValue("strict").jsonPrimitive.booleanOrNull)
    }

    @Test
    fun `chat model keeps response format strict separate from tool strict`() = runTest {
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
        val provider = OpenAICompatible(
            client,
            OpenAICompatibleProviderSettings {
                name("openai")
                baseUrl("https://api.test/v1")
                apiKey("secret")
                supportsStructuredOutputs(true)
            },
        )

        provider.languageModel("gpt-test").generate(
            LanguageModelCallParams {
                messages(listOf(UserMessage("hi")))
                responseFormat(
                    ResponseFormat.Json(
                        schemaName = "Answer",
                        schemaJson = JsonObject(mapOf("type" to JsonPrimitive("object"))),
                    )
                )
            },
        )

        val strict = seenBodies.single()
            .getValue("response_format")
            .jsonObject
            .getValue("json_schema")
            .jsonObject
            .getValue("strict")
            .jsonPrimitive
            .booleanOrNull
        assertEquals(true, strict)
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
        val provider = OpenAICompatible(
            client,
            OpenAICompatibleProviderSettings {
                name("openai")
                baseUrl("https://api.test/v1")
                apiKey("secret")
            },
        )
        provider.languageModel("gpt-test").generate(
            LanguageModelCallParams {
                messages(listOf(UserMessage("hi")))
            }
        )
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

                        data: {"id":"1","choices":[{"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":1,"completion_tokens":2,"completion_tokens_details":{"accepted_prediction_tokens":4,"rejected_prediction_tokens":1}}}

                        data: [DONE]

                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
                )
            },
        )
        val provider = OpenAICompatible(
            client,
            OpenAICompatibleProviderSettings {
                name("openai")
                baseUrl("https://api.test/v1")
                includeUsage(true)
            },
        )

        val events = drainAllItems(
            provider.languageModel("gpt-test").stream(
                LanguageModelCallParams {
                    messages(listOf(UserMessage("hi")))
                }
            )
        )

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
        val finishMetadata = finish.providerMetadata.toMap()["openai"]?.jsonObject
        assertEquals(4, finishMetadata?.get("acceptedPredictionTokens")?.jsonPrimitive?.intOrNull)
        assertEquals(1, finishMetadata?.get("rejectedPredictionTokens")?.jsonPrimitive?.intOrNull)
    }

    @Test
    fun `chat stream emits mid-stream in-band error and finish after text delta`() = runTest {
        val client = HttpClient(
            MockEngine {
                respond(
                    content = """
                        data: {"id":"1","choices":[{"delta":{"content":"hello"}}]}

                        data: {"error":{"message":"provider exploded","type":"server_error"}}

                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
                )
            },
        )
        val provider = OpenAICompatible(
            client,
            OpenAICompatibleProviderSettings {
                name("openai");
                baseUrl("https://api.test/v1")
            },
        )

        val events = drainAllItems(
            provider.languageModel("gpt-test").stream(
                LanguageModelCallParams {
                    messages(listOf(UserMessage("hi")))
                }
            )
        )

        val textIndex = events.indexOfFirst { it is StreamEvent.TextDelta && it.text == "hello" }
        val errorIndex = events.indexOfFirst { it is StreamEvent.Error }
        val finish = events.filterIsInstance<StreamEvent.Finish>().single()
        assertTrue(textIndex >= 0, events.toString())
        assertTrue(errorIndex > textIndex, events.toString())
        assertEquals("provider exploded", events.filterIsInstance<StreamEvent.Error>().single().message)
        assertEquals(FinishReason.Error, finish.finishReason)
    }

    @Test
    fun `chat stream error-only chunk without choices still surfaces as stream error`() = runTest {
        val client = HttpClient(
            MockEngine {
                respond(
                    content = """
                        data: {"error":{"type":"rate_limit_exceeded"}}

                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
                )
            },
        )
        val provider = OpenAICompatible(
            client,
            OpenAICompatibleProviderSettings {
                name("openai");
                baseUrl("https://api.test/v1")
            },
        )

        val events = drainAllItems(
            provider.languageModel("gpt-test").stream(
                LanguageModelCallParams {
                    messages(listOf(UserMessage("hi")))
                }
            )
        )

        val error = events.filterIsInstance<StreamEvent.Error>().single()
        val finish = events.filterIsInstance<StreamEvent.Finish>().single()
        assertEquals("rate_limit_exceeded", error.message)
        assertEquals(FinishReason.Error, finish.finishReason)
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
        val provider = OpenAICompatible(
            client,
            OpenAICompatibleProviderSettings {
                name("openai")
                baseUrl("https://api.test/v1")
                transformChatResponse(::rewriteWireContentResponse)
            },
        )

        val generated = provider.languageModel("gpt-test").generate(
            LanguageModelCallParams {
                messages(listOf(UserMessage("hi")))
            },
        )
        val events = drainAllItems(
            provider.languageModel("gpt-test").stream(
                LanguageModelCallParams {
                    messages(listOf(UserMessage("hi")))
                }
            ),
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
        val provider = OpenAICompatible(
            client,
            OpenAICompatibleProviderSettings {
                name("openai")
                baseUrl("https://api.test/v1")
                includeUsage(true)
            },
        )

        val deltas = mutableListOf<String>()
        val firstDelta = CompletableDeferred<Unit>()
        val collector = launch {
            provider.languageModel("gpt-test")
                .stream(
                    LanguageModelCallParams {
                        messages(listOf(UserMessage("hi")))
                    }
                )
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
        val provider =
            OpenAICompatible(
                client,
                OpenAICompatibleProviderSettings {
                    name("openai");
                    baseUrl("https://api.test/v1")
                }
            )

        val generated = TextGenerator(
            provider.completionModel("davinci")
        ).generate(GenerationInput.Prompt("complete")).first()
        val streamed = drainAllItems(
            provider.completionModel("davinci").stream(
                LanguageModelCallParams {
                    messages(listOf(UserMessage("hi")))
                }
            )
        )

        assertEquals("done", generated.text)
        assertEquals(3, generated.usage.promptTokens)
        assertTrue(streamed.any { it is StreamEvent.TextDelta && it.text == "a" })
        assertTrue(streamed.any { it is StreamEvent.TextDelta && it.text == "b" })
        assertEquals(listOf("/v1/completions", "/v1/completions"), seenPaths)
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
