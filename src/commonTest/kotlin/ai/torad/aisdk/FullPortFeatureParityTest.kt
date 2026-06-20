package ai.torad.aisdk

import ai.torad.aisdk.providers.MockEmbeddingModel
import ai.torad.aisdk.providers.MockImageModel
import ai.torad.aisdk.providers.MockRerankingModel
import ai.torad.aisdk.providers.MockSpeechModel
import ai.torad.aisdk.providers.MockTranscriptionModel
import ai.torad.aisdk.providers.MockVideoModel
import ai.torad.aisdk.providers.mockAudioSource
import ai.torad.aisdk.providers.mockLanguageModelTextOnly
import ai.torad.aisdk.testing.drainAllItems
import ai.torad.aisdk.ui.Chat
import ai.torad.aisdk.ui.ChatRequest
import ai.torad.aisdk.ui.DirectChatTransport
import ai.torad.aisdk.ui.ServerResponseWriter
import ai.torad.aisdk.ui.TextStreamChatTransport
import ai.torad.aisdk.ui.TextUIPartState
import ai.torad.aisdk.ui.UIMessage
import ai.torad.aisdk.ui.UIMessagePart
import ai.torad.aisdk.ui.UIMessageRole
import ai.torad.aisdk.ui.createTextStreamResponse
import ai.torad.aisdk.ui.createUiMessageStream
import ai.torad.aisdk.ui.getResponseUiMessageId
import ai.torad.aisdk.ui.lastAssistantMessageIsCompleteWithToolCalls
import ai.torad.aisdk.ui.pipeTextStreamToResponse
import ai.torad.aisdk.ui.textStreamFromEvents
import ai.torad.aisdk.ui.transformTextToUiMessageStream
import ai.torad.aisdk.ui.streamToUiMessages
import ai.torad.aisdk.ui.validateUiMessages
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

class FullPortFeatureParityTest {

    @Test
    fun `embed and embedMany call embedding models and preserve batching settings`() = runTest {
        val model = MockEmbeddingModel(dimensions = 2)

        val single = Embedding.embed(model, "hello")
        val many = Embedding.embedMany(model, listOf("a", "bb", "ccc"), maxEmbeddingsPerCall = 2)

        assertEquals(listOf(5f, 6f), single.embedding)
        assertEquals(3, many.embeddings.size)
        assertEquals(6, many.usage.tokens)
        assertEquals(2, model.captured?.maxEmbeddingsPerCall)
    }

    @Test
    fun `embedding middleware applies defaults without overriding explicit params`() = runTest {
        val model = MockEmbeddingModel()
        val wrapped = wrapEmbeddingModel(
            model,
            listOf(
                defaultEmbeddingSettingsMiddleware(
                    maxEmbeddingsPerCall = 4,
                    truncate = true,
                    providerOptions = mapOf("mock" to JsonPrimitive("default")),
                    headers = mapOf("x-default" to "yes"),
                ),
            ),
        )

        wrapped.embed(
            EmbeddingModelCallParams(
                values = listOf("x"),
                truncate = false,
                providerOptions = mapOf("explicit" to JsonPrimitive(1)),
            ),
        )

        val captured = assertNotNull(model.captured)
        assertEquals(4, captured.maxEmbeddingsPerCall)
        assertEquals(false, captured.truncate)
        assertEquals(JsonPrimitive("default"), captured.providerOptions["mock"])
        assertEquals(JsonPrimitive(1), captured.providerOptions["explicit"])
        assertEquals("yes", captured.headers["x-default"])
    }

    @Test
    fun `media generation helpers validate inputs and expose generated files`() = runTest {
        val image = generateImage(MockImageModel(), prompt = "logo", n = 2)
        val speech = generateSpeech(MockSpeechModel(), text = "hello", voice = "alloy")
        val transcript = transcribe(MockTranscriptionModel(), audio = mockAudioSource())
        val video = generateVideo(MockVideoModel(), prompt = "launch reel")

        assertEquals(2, image.images.size)
        assertEquals("image/png", image.image.mediaType)
        assertEquals("audio/mpeg", speech.audio.mediaType)
        assertEquals("hello world", transcript.text)
        assertEquals("video/mp4", video.video.mediaType)
        assertFailsWith<IllegalArgumentException> { generateImage(MockImageModel(), prompt = "") }
    }

    @Test
    fun `rerank preserves provider ranking order and original indexes`() = runTest {
        val model = MockRerankingModel()
        val result = Reranking.rerank(
            model = model,
            query = "kotlin",
            documents = listOf("swift ui", "kotlin multiplatform", "java"),
            topN = 2,
        )

        assertEquals(2, model.captured?.topN)
        assertEquals(listOf("swift ui", "kotlin multiplatform", "java"), result.results.map { it.value })
        assertEquals(listOf(0, 1, 2), result.results.map { it.index })
    }

    @Test
    fun `provider registry routes every model family by provider prefix`() {
        val provider = customProvider(
            providerId = "mock",
            embeddingModels = mapOf("embed" to MockEmbeddingModel()),
            imageModels = mapOf("image" to MockImageModel()),
            speechModels = mapOf("speech" to MockSpeechModel()),
            transcriptionModels = mapOf("transcribe" to MockTranscriptionModel()),
            rerankingModels = mapOf("rerank" to MockRerankingModel()),
            videoModels = mapOf("video" to MockVideoModel()),
        )
        val registry = createProviderRegistry("mock" to provider)

        assertEquals("mock/embedding", registry.embeddingModel("mock:embed").modelId)
        assertEquals("mock/image", registry.imageModel("mock:image").modelId)
        assertEquals("mock/speech", registry.speechModel("mock:speech").modelId)
        assertEquals("mock/transcription", registry.transcriptionModel("mock:transcribe").modelId)
        assertEquals("mock/rerank", registry.rerankingModel("mock:rerank").modelId)
        assertEquals("mock/video", registry.videoModel("mock:video").modelId)
        assertFailsWith<NoSuchProviderError> { registry.provider("missing") }
    }

    @Test
    fun `dynamic tools and schema wrappers expose provider-utils parity`() = runTest {
        val schema = jsonSchema<JsonObject>(buildJsonObject { put("type", JsonPrimitive("object")) })
        val tool = dynamicTool<Unit>(
            name = "runtimeTool",
            description = "runtime registered",
            inputSchemaJson = schema.jsonSchema.toString(),
        ) { input ->
            buildJsonObject { put("seen", input.jsonObject["value"] ?: JsonPrimitive("missing")) }
        }

        val ctx = ToolExecutionContext(
            context = Unit,
            abortSignal = AbortSignalNever,
            stepNumber = 1,
            messages = emptyList(),
            toolCallId = "call_1",
        )
        val value = with(tool) { ctx.execute(buildJsonObject { put("value", JsonPrimitive("ok")) }) }

        assertEquals("runtimeTool", tool.name)
        assertEquals(schema, asSchema(schema))
        assertEquals(JsonPrimitive("ok"), value.jsonObject["seen"])
    }

    @Test
    fun `provider executed tools advertise provider execution in model descriptors`() {
        val tool = providerExecutedTool<JsonElement, JsonElement, Unit>(
            name = "webSearch",
            description = "Hosted web search",
            inputSerializer = JsonElement.serializer(),
            outputSerializer = JsonElement.serializer(),
        )
        val descriptors = toolSetOf(tool).descriptors

        assertEquals(true, descriptors.single().providerExecuted)
    }

    @Test
    fun `image model middleware can wrap provider models`() = runTest {
        val model = MockImageModel()
        val wrapped = wrapImageModel(
            model,
            listOf(
                object : ImageModelMiddleware {
                    override suspend fun wrapGenerate(context: ImageMiddlewareCallContext): ImageModelResult =
                        context.doGenerate(
                            context.params.copy(
                                providerOptions = context.params.providerOptions + ("wrapped" to JsonPrimitive(true)),
                            ),
                        )
                },
            ),
        )

        generateImage(wrapped, "wrapped")

        assertEquals(JsonPrimitive(true), model.captured?.providerOptions?.get("wrapped"))
    }

    @Test
    fun `telemetry helpers assemble names select attributes and record spans`() = runTest {
        val tracer = InMemoryTelemetryTracer()

        val settings = TelemetrySettings(
            isEnabled = true,
            functionId = "chat",
            metadata = mapOf("custom" to JsonPrimitive("value")),
        )
        val attributes = selectTelemetryAttributes(
            telemetry = settings,
            input = JsonPrimitive("in"),
            output = JsonPrimitive("out"),
            providerMetadata = mapOf("mock" to buildJsonObject { put("id", JsonPrimitive("1")) }),
        )
        recordSpan(assembleOperationName("generateText", settings), tracer, attributes) {}

        assertEquals("chat.generateText", tracer.spans.single().name)
        assertEquals(JsonPrimitive("in"), tracer.spans.single().attributes["ai.input"])
        assertTrue(tracer.spans.single().attributes["ai.providerMetadata"] is JsonObject)
        assertEquals("out", stringifyForTelemetry(JsonPrimitive("out")))
    }

    @Test
    fun `utility helpers cover ids media data urls retries and JSON equality`() = runTest {
        var attempts = 0
        val retried = RetryPolicy(maxRetries = 1, baseDelayMs = 0).execute { attempt ->
            attempts += 1
            if (attempt == 0) error("retry")
            "ok"
        }

        val dataUrl = DataUrl.parse("data:text/plain;base64,SGk=")

        assertEquals("ok", retried)
        assertEquals(2, attempts)
        assertEquals("text/plain", dataUrl.mediaType)
        assertEquals("image/png", MediaTypes.detect(filename = "a.png"))
        assertTrue(IdGenerator.generate("test").startsWith("test-"))
        assertTrue(JsonOps.isDeepEqual(JsonPrimitive(1), JsonPrimitive(1.0)))
        assertFalse(JsonOps.isDeepEqual(JsonPrimitive(1), JsonPrimitive(2)))
        assertEquals(listOf(listOf(1, 2), listOf(3)), CollectionOps.splitArray(listOf(1, 2, 3), 2))
    }

    @Test
    fun `text and UI stream helpers convert pipe and validate messages`() = runTest {
        val events = flowOf(
            StreamEvent.TextStart("t1"),
            StreamEvent.TextDelta("t1", "hel"),
            StreamEvent.TextDelta("t1", "lo"),
            StreamEvent.TextEnd("t1"),
        )
        val text = drainAllItems(textStreamFromEvents(events))
        val uiMessages = drainAllItems(transformTextToUiMessageStream(flowOf("he", "llo"), "a1"))
        val response = createTextStreamResponse(flowOf("ok"))
        val writer = CapturingWriter()

        pipeTextStreamToResponse(flowOf("a", "b"), writer)
        validateUiMessages(listOf(uiMessages.last()))

        assertEquals(listOf("hel", "lo"), text)
        assertEquals("hello", (uiMessages.last().parts.single() as UIMessagePart.Text).text)
        assertEquals(TextUIPartState.Done, (uiMessages.last().parts.single() as UIMessagePart.Text).state)
        assertEquals("text/plain; charset=utf-8", response.headers["Content-Type"])
        assertEquals("ab", writer.body)
    }

    @Test
    fun `raw data stream chunks become UI data parts`() = runTest {
        val messages = drainAllItems(
            streamToUiMessages(
                flowOf(
                    StreamEvent.Raw(
                        buildJsonObject {
                            put("type", JsonPrimitive("weather"))
                            put("data", buildJsonObject { put("temp", JsonPrimitive(72)) })
                        },
                    ),
                ),
                assistantMessageId = "a1",
            ),
        )

        val data = messages.single().parts.single() as UIMessagePart.Data
        assertEquals("weather", data.type)
        assertEquals(JsonPrimitive(72), data.data.jsonObject["temp"])
    }

    @Test
    fun `streamTextResult exposes text and UI response facades`() = runTest {
        val result = TextGenerator(mockLanguageModelTextOnly("hello")).streamResult(GenerationInput.Prompt("hi"))
        val text = drainAllItems(result.textStream)
        val ui = drainAllItems(result.toUiMessageStream("a1"))

        assertEquals(listOf("hello"), text)
        assertEquals("text/plain; charset=utf-8", result.toTextStreamResponse().headers["Content-Type"])
        assertEquals("text/event-stream; charset=utf-8", result.toUiMessageStreamResponse("a1").headers["Content-Type"])
        assertEquals("hello", (ui.last().parts.single() as UIMessagePart.Text).text)
    }

    @Test
    fun `create UI message stream emits writer messages and error messages`() = runTest {
        val ok = drainAllItems(
            createUiMessageStream {
                write(assistant("a1", "hello"))
            },
        )
        val failed = drainAllItems(
            createUiMessageStream {
                error("bad")
            },
        )

        assertEquals("hello", (ok.single().parts.single() as UIMessagePart.Text).text)
        assertEquals("bad", (failed.single().parts.single() as UIMessagePart.Error).message)
    }

    @Test
    fun `chat transports send messages and update chat state`() = runTest {
        val transport = DirectChatTransport { _: ChatRequest ->
            flowOf(assistant("assistant-1", "pong"))
        }
        val chat = Chat(id = "c1", transport = transport)
        val emitted = drainAllItems(chat.sendMessage(UIMessage("u1", UIMessageRole.User, listOf(UIMessagePart.Text("ping")))))
        val textTransport = TextStreamChatTransport(handler = { flowOf("he", "llo") })
        val textMessages = drainAllItems(
            textTransport.sendMessages(ChatRequest(listOf(UIMessage("u2", UIMessageRole.User, listOf(UIMessagePart.Text("hi")))))),
        )

        assertEquals("pong", (emitted.single().parts.single() as UIMessagePart.Text).text)
        assertEquals(2, chat.messages.size)
        assertEquals("hello", (textMessages.last().parts.single() as UIMessagePart.Text).text)
        assertEquals("assistant-1", getResponseUiMessageId(chat.messages))
        assertTrue(lastAssistantMessageIsCompleteWithToolCalls(chat.messages))
    }

    private fun assistant(id: String, text: String): UIMessage =
        UIMessage(id, UIMessageRole.Assistant, listOf(UIMessagePart.Text(text)))

    private class CapturingWriter : ServerResponseWriter {
        var capturedStatus: Int = 0
        val headers: MutableMap<String, String> = linkedMapOf()
        private val buffer = StringBuilder()
        val body: String get() = buffer.toString()

        override fun setStatus(status: Int) {
            capturedStatus = status
        }

        override fun setHeader(name: String, value: String) {
            headers[name] = value
        }

        override suspend fun write(chunk: String) {
            buffer.append(chunk)
        }
    }
}
