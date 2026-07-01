@file:OptIn(LowLevelLanguageModelApi::class)

package ai.torad.aisdk
import ai.torad.aisdk.providers.OpenAICompatibleProviderSettings
import ai.torad.aisdk.providers.OpenAICompatible
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Suppress("LargeClass")
class OpenAICompatibleMediaAndErrorsTest {
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
        val provider = OpenAICompatible(client, OpenAICompatibleProviderSettings { name("openai"); baseUrl("https://api.test/v1") })

        val image = ImageGeneration.generateImage(provider.imageModel("image"), prompt = "logo")

        assertEquals("", image.image.base64)
        assertEquals("https://cdn.test/image.png", image.image.url)
        assertEquals("image/jpeg", image.image.mediaType)
    }

    @Test
    @Suppress("LongMethod")
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
                        """
                        {
                          "data":[{"b64_json":"iVBORw0="}],
                          "usage":{"input_tokens":12,"output_tokens":9,"total_tokens":21}
                        }
                        """.trimIndent(),
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
        val provider = OpenAICompatible(client, OpenAICompatibleProviderSettings { name("openai"); baseUrl("https://api.test/v1") })

        val embedding = Embedding.embedMany(provider.embeddingModel("embed"), listOf("a", "b"))
        val image = ImageGeneration.generateImage(provider.imageModel("image"), prompt = "logo", aspectRatio = "1:1", seed = 1)
        val speech = SpeechGeneration.generateSpeech(provider.speechModel("tts"), text = "hello", voice = "alloy")
        val transcript = Transcription.transcribe(
            provider.transcriptionModel("whisper"),
            AudioSource(mediaType = "audio/mpeg", base64 = Base64Codec.encode("abc".encodeToByteArray())),
        )

        assertEquals(2048, provider.embeddingModel("embed").maxEmbeddingsPerCall)
        assertEquals(true, provider.embeddingModel("embed").supportsParallelCalls)
        assertEquals(listOf(listOf(3f, 4f), listOf(1f, 2f)), embedding.embeddings)
        assertEquals(9, embedding.usage.tokens)
        assertEquals(10, provider.imageModel("image").maxImagesPerCall)
        assertEquals("iVBORw0=", image.image.base64)
        assertEquals(ImageModelUsage(inputTokens = 12, outputTokens = 9, totalTokens = 21), image.usage)
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
    fun `image edits use multipart edits endpoint when files are present`() = runTest {
        var seenPath: String? = null
        var seenContentType: String? = null
        val client = HttpClient(
            MockEngine { request ->
                seenPath = request.url.encodedPath
                seenContentType = request.headers[HttpHeaders.ContentType] ?: request.body.contentType?.toString()
                respond(
                    """{"data":[{"b64_json":"ZWRpdA=="}]}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        val provider = OpenAICompatible(client, OpenAICompatibleProviderSettings { name("openai"); baseUrl("https://api.test/v1") })

        val image = ImageGeneration.generateImage(
            model = provider.imageModel("image"),
            prompt = "edit",
            files = listOf(ImageGenerationFile(mediaType = "image/png", base64 = "aW1n", filename = "input.png")),
            mask = ImageGenerationFile(mediaType = "image/png", base64 = "bWFzaw==", filename = "mask.png"),
        )

        assertEquals("/v1/images/edits", seenPath)
        assertTrue(seenContentType?.startsWith("multipart/form-data") == true)
        assertEquals("ZWRpdA==", image.image.base64)
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
        val provider = OpenAICompatible(client, OpenAICompatibleProviderSettings { name("openai"); baseUrl("https://api.test/v1") })

        val error = assertFailsWith<WireDecodeException> {
            Embedding.embed(provider.embeddingModel("embed"), "a")
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
        val provider = OpenAICompatible(client, OpenAICompatibleProviderSettings { name("openai"); baseUrl("https://api.test/v1") })

        val error = assertFailsWith<WireDecodeException> {
            TextGenerator(provider.languageModel("gpt-test")).generate(GenerationInput.Prompt("hi")).first()
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
        val provider = OpenAICompatible(client, OpenAICompatibleProviderSettings { name("openai"); baseUrl("https://api.test/v1") })

        val completionError = assertFailsWith<WireDecodeException> {
            TextGenerator(provider.completionModel("davinci")).generate(GenerationInput.Prompt("complete")).first()
        }
        val imageError = assertFailsWith<WireDecodeException> {
            ImageGeneration.generateImage(provider.imageModel("image"), prompt = "logo")
        }
        val transcriptError = assertFailsWith<WireDecodeException> {
            Transcription.transcribe(
                provider.transcriptionModel("whisper"),
                AudioSource(mediaType = "audio/mpeg", base64 = Base64Codec.encode("abc".encodeToByteArray())),
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
        val provider = OpenAICompatible(client, OpenAICompatibleProviderSettings { name("openai"); baseUrl("https://api.test/v1") })

        val error = assertFailsWith<APICallError> {
            TextGenerator(provider.languageModel("gpt-test")).generate(GenerationInput.Prompt("hi")).first()
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
        val model = OpenAICompatible(
            client,
            OpenAICompatibleProviderSettings {
                name("test")
                baseUrl("https://api.test/v1")
                apiKey("k")
            },
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

        model.generate(LanguageModelCallParams {
    messages(messages)
})

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
        val model = OpenAICompatible(
            client,
            OpenAICompatibleProviderSettings {
                name("test")
                baseUrl("https://api.test/v1")
                apiKey("k")
            },
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

        model.generate(LanguageModelCallParams {
    messages(messages)
})

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


}
