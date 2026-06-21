package ai.torad.aisdk

import ai.torad.aisdk.testing.FlowDrain.drainAllItems
import ai.torad.aisdk.providers.MockImageModel
import ai.torad.aisdk.providers.MockSpeechModel
import ai.torad.aisdk.providers.MockTranscriptionModel
import ai.torad.aisdk.providers.MockVideoModel
import ai.torad.aisdk.providers.MockAudioSource
import ai.torad.aisdk.ui.SafeValidateUIMessagesResult
import ai.torad.aisdk.ui.TextUIPartState
import ai.torad.aisdk.ui.UIMessage
import ai.torad.aisdk.ui.UIMessagePart
import ai.torad.aisdk.ui.UIMessageRole
import ai.torad.aisdk.ui.UiMessageStreams.safeValidateUIMessages
import ai.torad.aisdk.ui.UiMessageStreams.validateUIMessages
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

class GatewayAndProviderUtilsParityTest {

    @Test
    fun `gateway provider exposes v6 model aliases and routes through transport`() = runTest {
        val transport = CapturingGatewayTransport()
        val provider = Gateway(
            GatewayProviderSettings(
                baseUrl = "https://gateway.test/v3/ai/",
                apiKey = "secret",
                headers = mapOf("x-team" to "torad"),
                transport = transport,
            ),
        )

        val text = TextGenerator(provider(ModelId("chat-model"))).generate(GenerationInput.Prompt("hi")).single()
        val chat = TextGenerator(provider.chat(ModelId("chat-model-2"))).generate(GenerationInput.Prompt("hi")).single()
        val embedding = Embedding.embed(provider.embedding(ModelId("embed-model")), "hello")
        val image = ImageGeneration.generateImage(provider.image(ModelId("image-model")), "logo")
        val video = VideoGeneration.generateVideo(provider.video(ModelId("video-model")), "clip")
        val previewVideo = VideoGeneration.generateVideo(provider.video(ModelId("xai/grok-imagine-video-1.5-preview")), "clip")
        val reranked = Reranking.rerank(provider.reranking(ModelId("rank-model")), "q", listOf("a", "bb"))

        assertEquals("gateway:chat-model", text.text)
        assertEquals("gateway:chat-model-2", chat.text)
        assertEquals(listOf(5f), embedding.embedding)
        assertEquals("image-model", image.image.filename)
        assertEquals("video-model", video.video.filename)
        assertEquals("xai/grok-imagine-video-1.5-preview", previewVideo.video.filename)
        assertEquals("a", reranked.results.first().value)
        assertEquals("gateway", provider.languageModel("x").provider)
        assertEquals("gateway", provider.textEmbeddingModel(ModelId("x")).provider)
        assertEquals("https://gateway.test/v3/ai", transport.contexts.first().baseUrl)
        assertEquals("Bearer secret", transport.contexts.first().headers["authorization"])
        assertEquals("api-key", transport.contexts.first().headers[GATEWAY_AUTH_METHOD_HEADER])
        assertEquals(AI_GATEWAY_PROTOCOL_VERSION, transport.contexts.first().headers["ai-gateway-protocol-version"])
        assertEquals("torad", transport.contexts.first().headers["x-team"])
        assertEquals(true, provider.tools.parallelSearch.providerExecuted)
        assertEquals(true, provider.tools.perplexitySearch.providerExecuted)
    }

    @Test
    fun `gateway metadata methods use transport and cache available models`() = runTest {
        var now = 1_000L
        val transport = CapturingGatewayTransport()
        val provider = GatewayProvider(
            GatewayProviderSettings(
                apiKey = "secret",
                transport = transport,
                metadataCacheRefreshMillis = 500L,
                nowMillis = { now },
            ),
        )

        val first = provider.getAvailableModels()
        val second = provider.getAvailableModels()
        now = 2_000L
        val third = provider.getAvailableModels()
        val credits = provider.getCredits()
        val spend = provider.getSpendReport(
            GatewaySpendReportParams(
                startDate = "2026-06-01",
                endDate = "2026-06-03",
                groupBy = GatewaySpendReportGroupBy.Model,
                credentialType = GatewayCredentialType.Byok,
                tags = listOf("prod", "mobile"),
            ),
        )
        val generation = provider.getGenerationInfo(GatewayGenerationInfoParams("gen_123"))

        assertEquals("model-1", first.models.single().id)
        assertEquals(first, second)
        assertEquals("model-2", third.models.single().id)
        assertEquals(2, transport.metadataCalls)
        assertEquals("100", credits.balance)
        assertEquals(12.5, spend.results.single().totalCost)
        assertEquals(GatewayCredentialType.Byok, transport.spendParams.single().credentialType)
        assertEquals("gen_123", generation.id)
    }

    @Test
    fun `gateway metadata cache uses system clock by default`() = runTest {
        val transport = CapturingGatewayTransport()
        val provider = GatewayProvider(
            GatewayProviderSettings(
                apiKey = "secret",
                transport = transport,
                metadataCacheRefreshMillis = 0L,
            ),
        )

        val first = provider.getAvailableModels()
        val second = provider.getAvailableModels()

        assertEquals("model-1", first.models.single().id)
        assertEquals("model-2", second.models.single().id)
        assertEquals(2, transport.metadataCalls)
    }

    @Test
    fun `gateway headers and errors mirror public gateway package behavior`() = runTest {
        val apiKeyHeaders = GatewayProviderSettings(
            apiKey = "key",
            headers = mapOf("User-Agent" to "app"),
        ).gatewayHeaders()
        assertEquals("Bearer key", apiKeyHeaders["authorization"])
        assertEquals("app ai-sdk/gateway-kotlin", apiKeyHeaders["user-agent"])

        val auth = GatewayAuthenticationError(generationId = "gen_1")
        val timeout = GatewayTimeoutError()
        val modelNotFound = GatewayModelNotFoundError(modelId = "missing")
        val response = GatewayResponseError(response = buildJsonObject { put("bad", JsonPrimitive(true)) })

        assertEquals("authentication_error", auth.type)
        assertTrue(auth.message.orEmpty().contains("gen_1"))
        assertTrue(timeout.isRetryable)
        assertEquals("missing", modelNotFound.modelId)
        assertEquals(JsonPrimitive(true), response.response?.jsonObject?.get("bad"))
    }

    @Test
    fun `parseJsonEventStream parses SSE JSON events and ignores done markers`() = runTest {
        val schema = Schemas.jsonSchema<JsonObject>(
            buildJsonObject { put("type", JsonPrimitive("object")) },
        ) { it.jsonObject }
        val text = """
            event: message
            data: {"type":"chunk","value":1}

            data: [DONE]

            data: {"type":"chunk","value":2}

        """.trimIndent()

        val parsed = EventStreamParser.parse(text, schema)
        val streamed = drainAllItems(
            EventStreamParser.parse(flowOf("data: {\"type\":\"chunk\"", ",\"value\":3}\n\n", "data: [DONE]\n\n"),
            schema,),
        )
        val failed = EventStreamParser.parse("data: {bad json}\n\n", schema)

        assertEquals(listOf(1, 2), parsed.map { (it as ParseResult.Success).value["value"]?.let { value -> value as JsonPrimitive }?.content?.toInt() })
        assertEquals(JsonPrimitive(3), (streamed.single() as ParseResult.Success).value["value"])
        assertIs<ParseResult.Failure>(failed.single())
    }

    @Test
    fun `provider util helpers match v6 defaults`() {
        val generated = IdGenerator.generate()
        val prefixed = IdGenerator(prefix = "msg", size = 4, alphabet = "ab", separator = "_").generate()
        val headers = ProviderHeaders.withUserAgentSuffix(mapOf("User-Agent" to "app/1.0", "X-Empty" to null),
        "ai-sdk/test",)

        assertEquals(16, generated.length)
        assertTrue(prefixed.startsWith("msg_"))
        assertFailsWith<IllegalArgumentException> {
            IdGenerator(prefix = "bad", alphabet = "ab-", separator = "-").generate()
        }
        assertEquals(mapOf("a" to "1", "b" to null), ProviderHeaders.combine(mapOf("a" to "0"), mapOf("a" to "1", "b" to null)))
        assertEquals(mapOf("x-a" to "1"), ProviderHeaders.normalize(mapOf("X-A" to "1", "X-B" to null)))
        assertEquals("app/1.0 ai-sdk/test", headers["user-agent"])
        assertEquals("mp3", MediaTypes.toExtension("audio/mpeg"))
        assertEquals("archive", MediaTypes.stripFileExtension("archive.tar.gz"))
        assertEquals("https://x.test/a", UrlOps.withoutTrailingSlash("https://x.test/a/"))
        assertEquals("boom", ErrorMessages.of(IllegalStateException("boom")))
    }

    @Test
    fun `binary url and media provider-utils are available in common code`() {
        val raw = byteArrayOf(1, 2, 3)
        val encoded = Base64Codec.encode(raw)

        assertEquals(raw.toList(), Base64Codec.decode(encoded).toList())
        assertEquals(encoded, Base64Codec.encode(raw))
        UrlOps.validateDownload("https://example.com/file.png")
        UrlOps.validateDownload("data:text/plain;base64,SGk=")
        assertFailsWith<DownloadError> { UrlOps.validateDownload("http://localhost/file") }
        assertFailsWith<DownloadError> { UrlOps.validateDownload("http://10.0.0.1/file") }
        assertFailsWith<DownloadError> { UrlOps.validateDownload("file:///tmp/secret") }
        // SSRF guard must not be bypassed by userinfo in front of a bracketed IPv6 literal:
        // the parser previously yielded host "[" for these and let the loopback through.
        assertFailsWith<DownloadError> { UrlOps.validateDownload("http://user@[::1]/path") }
        assertFailsWith<DownloadError> { UrlOps.validateDownload("http://[::1]/path") }
        // Non-ASCII is percent-encoded from its UTF-8 bytes, not passed through as Latin-1 chars.
        assertEquals("%C3%A9", UrlOps.encode("é"))
        assertEquals("caf%C3%A9", UrlOps.encode("café"))
    }

    @Test
    fun `lazy schemas validation and provider options match provider-utils`() {
        var created = 0
        val lazy = Schemas.lazySchema<JsonObject> {
            created += 1
            Schemas.jsonSchema(buildJsonObject { put("type", JsonPrimitive("object")) }) { value -> value.jsonObject }
        }
        val value = buildJsonObject { put("name", JsonPrimitive("ok")) }

        assertEquals(0, created)
        assertEquals(JsonPrimitive("ok"), Schemas.validateTypes(value, lazy)["name"])
        assertEquals(JsonPrimitive("ok"), Schemas.safeValidateTypes(value, lazy).let { (it as ValidationResult.Success).value["name"] })
        assertEquals(1, created)
        assertEquals(lazy(), Schemas.asSchema(lazy))
        assertEquals(1, created)
        assertNull(Schemas.parseProviderOptions("missing", ProviderOptions.None, lazy()))
        assertEquals(JsonPrimitive("ok"), Schemas.parseProviderOptions("test", ProviderOptions.Raw(JsonObject(mapOf("test" to value))), lazy())?.get("name"))
        assertFailsWith<InvalidArgumentError> {
            Schemas.parseProviderOptions(
                "test",
                ProviderOptions.Raw(JsonObject(mapOf("test" to value))),
                Schemas.jsonSchema<JsonObject>(JsonObject(emptyMap())) { throw IllegalStateException("bad") },
            )
        }
    }

    @Test
    fun `schema fallback validates basic JSON schema type before returning values`() {
        val stringSchema = Schemas.jsonSchema<String>(
            buildJsonObject { put("type", JsonPrimitive("string")) },
        )
        val objectSchema = Schemas.jsonSchema<JsonObject>(
            buildJsonObject { put("type", JsonPrimitive("object")) },
        )

        val string = assertIs<ValidationResult.Success<String>>(Schemas.safeValidateTypes(JsonPrimitive("ok"), stringSchema))
        val objectFailure = assertIs<ValidationResult.Failure>(Schemas.safeValidateTypes(JsonPrimitive("not-object"), objectSchema))
        val stringFailure = assertIs<ValidationResult.Failure>(Schemas.safeValidateTypes(buildJsonObject { put("x", JsonPrimitive(1)) }, stringSchema))

        assertEquals("ok", string.value)
        assertTrue(objectFailure.error.message.orEmpty().contains("Expected JSON object"))
        assertTrue(stringFailure.error.message.orEmpty().contains("Expected JSON string"))
    }

    @Test
    fun `provider tool factories execute tools and create name mappings`() = runTest {
        val inputSchema = Schemas.jsonSchema<JsonObject>(
            buildJsonObject {
                put("type", JsonPrimitive("object"))
                put("additionalProperties", JsonPrimitive(false))
            },
        ) { value -> value.jsonObject }
        val outputSchema = Schemas.jsonSchema<JsonElement>(
            buildJsonObject { put("type", JsonPrimitive("object")) },
        )
        val factory = ProviderTools.createProviderToolFactory<JsonObject, Unit>(
            id = "web.search",
            inputSerializer = JsonObject.serializer(),
            inputSchema = inputSchema,
            description = "Search the web",
        )
        val hosted = factory(
            ProviderToolFactoryOptions(
                outputSerializer = JsonElement.serializer(),
                outputSchema = outputSchema,
                args = mapOf("limit" to JsonPrimitive(3)),
            ),
        )
        val descriptor = ToolSet<Unit>(hosted).descriptors.single()
        val mapping = ToolNameMapping(
            tools = listOf(descriptor),
            providerToolNames = mapOf("web.search" to "provider_search"),
        )
        val executable = factory(
            ProviderToolFactoryOptions(
                outputSerializer = JsonElement.serializer(),
                execute = { input ->
                    buildJsonObject { put("seen", input["query"] ?: JsonPrimitive("missing")) }
                },
            ),
        )

        val executed = drainAllItems(
            ExecuteTool(
                executable,
                buildJsonObject { put("query", JsonPrimitive("kmp")) },
                ToolExecutionContext(
                    context = Unit,
                    abortSignal = AbortSignalNever,
                    stepNumber = 1,
                    messages = emptyList(),
                    toolCallId = "call_1",
                ),
            ),
        )
        val withOutputSchema = ProviderTools.createProviderToolFactoryWithOutputSchema<JsonObject, JsonElement, Unit>(
            id = "web.lookup",
            inputSerializer = JsonObject.serializer(),
            inputSchema = inputSchema,
            outputSerializer = JsonElement.serializer(),
            outputSchema = outputSchema,
            supportsDeferredResults = true,
        )()

        assertEquals(true, hosted.providerExecuted)
        assertEquals("search", hosted.name)
        assertEquals(inputSchema.jsonSchema.toString(), descriptor.parametersSchemaJson)
        assertEquals("provider_search", mapping.toProviderToolName("search"))
        assertEquals("search", mapping.toCustomToolName("provider_search"))
        assertEquals("unknown", mapping.toProviderToolName("unknown"))
        assertEquals(JsonPrimitive("kmp"), (executed.single() as ExecuteToolResult.Final).output.jsonObject["seen"])
        assertEquals(JsonPrimitive(true), withOutputSchema.metadata["supportsDeferredResults"])
        assertEquals(outputSchema.jsonSchema, withOutputSchema.metadata["outputSchema"])
    }

    @Test
    fun `generated file and experimental media aliases preserve v6 compatibility`() = runTest {
        val fileFromBytes = DefaultGeneratedFile.fromBytes(byteArrayOf(1, 2, 3), "application/octet-stream")
        val fileFromBase64 = DefaultGeneratedFile.fromBase64(fileFromBytes.base64, "application/octet-stream")
        val image: Experimental_GenerateImageResult = ImageGeneration.experimental_generateImage(MockImageModel(), "logo")
        val speech: Experimental_SpeechResult = SpeechGeneration.experimental_generateSpeech(MockSpeechModel(), "hello")
        val transcript: Experimental_TranscriptionResult =
            Transcription.experimental_transcribe(MockTranscriptionModel(), MockAudioSource())
        val video = VideoGeneration.experimental_generateVideo(MockVideoModel(), "clip")

        assertEquals(fileFromBytes.byteArray.toList(), fileFromBase64.byteArray.toList())
        assertEquals("asset.bin", fileFromBytes.toGeneratedFile("asset.bin").filename)
        assertEquals("image/png", image.image.mediaType)
        assertEquals("audio/mpeg", speech.audio.mediaType)
        assertEquals("hello world", transcript.text)
        assertEquals("video/mp4", video.video.mediaType)
    }

    @Test
    fun `pruneMessages removes reasoning and old tool content with v6 options`() {
        val messages = listOf(
            ModelMessage(
                MessageRole.Assistant,
                listOf(
                    ContentPart.Text("keep"),
                    ContentPart.Reasoning("drop"),
                    ContentPart.ToolCall("old-call", "search", JsonObject(emptyMap())),
                ),
            ),
            ModelMessage(
                MessageRole.Tool,
                listOf(ContentPart.ToolResult("old-call", "search", JsonPrimitive("old"))),
            ),
            ModelMessage(
                MessageRole.Assistant,
                listOf(
                    ContentPart.Reasoning("keep-last"),
                    ContentPart.ToolCall("new-call", "search", JsonObject(emptyMap())),
                ),
            ),
        )

        val pruned = MessagePruning.pruneMessages(
            messages,
            reasoning = PruneReasoning.BeforeLastMessage,
            toolCalls = PruneToolCalls.BeforeLastMessage,
        )

        assertEquals(2, pruned.size)
        assertEquals(listOf(ContentPart.Text("keep")), pruned.first().content)
        assertTrue(pruned.last().content.any { it is ContentPart.Reasoning })
        assertTrue(pruned.last().content.any { it is ContentPart.ToolCall })
    }

    @Test
    fun `uppercase UI validation aliases return safe validation results`() {
        val message = UIMessage(
            id = "m1",
            role = UIMessageRole.Assistant,
            parts = listOf(UIMessagePart.Text("hello", TextUIPartState.Done)),
        )

        validateUIMessages(listOf(message))

        val ok = safeValidateUIMessages(listOf(message))
        // An empty list is valid (clear-chat / default state); only a null bag fails the guard.
        val emptyOk = safeValidateUIMessages(emptyList())
        val failed = safeValidateUIMessages(null)

        assertIs<SafeValidateUIMessagesResult.Success>(ok)
        assertIs<SafeValidateUIMessagesResult.Success>(emptyOk)
        assertIs<SafeValidateUIMessagesResult.Failure>(failed)
    }

    @Test
    fun `public v6 error surfaces carry diagnostic fields`() {
        val invalidInputCause = IllegalArgumentException("bad json")
        val invalidToolInput = InvalidToolInputError("{}", "search", invalidInputCause)
        val noSuchTool = NoSuchToolError("search", listOf("weather"))
        val repair = ToolCallRepairError(originalError = invalidToolInput, cause = noSuchTool)
        val missing = MissingToolResultsError(listOf("call_1", "call_2"))
        val retry = RetryError("stopped", RetryErrorReason.MaxRetriesExceeded, listOf(noSuchTool))
        val api = APICallError(
            message = "failed",
            url = "https://api.test",
            statusCode = 429,
            responseHeaders = mapOf("retry-after" to "1"),
            responseBody = "rate limited",
        )
        val prompt = InvalidPromptError(prompt = "bad", message = "unsupported role")
        val responseData = InvalidResponseDataError(data = JsonPrimitive("bad"))
        val parse = JSONParseError(text = "{", cause = IllegalArgumentException("unexpected eof"))
        val tooMany = TooManyEmbeddingValuesForCallError("test", "embed", 1, listOf("a", "b"))
        val validation = TypeValidationError(
            value = JsonPrimitive("bad"),
            cause = IllegalArgumentException("bad"),
            context = TypeValidationContext(field = "message.parts[0]", entityName = "message", entityId = "m1"),
        )
        val wrapped = TypeValidationError.wrap(JsonPrimitive("bad"), validation, validation.context)
        val unsupported = UnsupportedFunctionalityError("tool-result repair")

        assertEquals("search", invalidToolInput.toolName)
        assertEquals("{}", invalidToolInput.toolInput)
        assertTrue(invalidToolInput.message.orEmpty().contains("bad json"))
        assertTrue(noSuchTool.message.orEmpty().contains("Available tools: weather"))
        assertEquals(invalidToolInput, repair.originalError)
        assertTrue(missing.message.orEmpty().contains("call_1, call_2"))
        assertEquals(noSuchTool, retry.lastError)
        assertEquals("approval_1", InvalidToolApprovalError("approval_1").approvalId)
        assertEquals("call_1", ToolCallNotFoundForApprovalError("call_1", "approval_1").toolCallId)
        assertEquals("admin", InvalidMessageRoleError("admin").role)
        assertEquals("raw", InvalidStreamPartError(chunk = "raw", message = "invalid").chunk)
        assertEquals("bytes", InvalidDataContentError("bytes").content)
        assertEquals("ui", MessageConversionError(originalMessage = "m", message = "ui").message)
        assertTrue(api.isRetryable)
        assertEquals("retry-after", api.responseHeaders?.keys?.single())
        assertEquals("bad", prompt.prompt)
        assertEquals(JsonPrimitive("bad"), responseData.data)
        assertEquals("{", parse.text)
        assertEquals(2, tooMany.values.size)
        assertTrue(validation.message.orEmpty().contains("message.parts[0]"))
        assertEquals(validation, wrapped)
        assertEquals("tool-result repair", unsupported.functionality)
        assertEquals("Empty response body", EmptyResponseBodyError().message)
        assertEquals("No content generated.", NoContentGeneratedError().message)
    }

    @Test
    fun `KtorGatewayTransport posts language model requests and parses stream events`() = runTest {
        val seenPaths = mutableListOf<String>()
        val seenHeaders = mutableListOf<Map<String, List<String>>>()
        val client = HttpClient(
            MockEngine { request ->
                seenPaths += request.url.encodedPath
                seenHeaders += request.headers.entries().associate { it.key to it.value }
                when (request.url.encodedPath) {
                    "/v3/ai/language-model" -> {
                        if (request.headers["ai-language-model-streaming"] == "true") {
                            respond(
                                content = """
                                    data: {"type":"text-delta","id":"t1","delta":"hello"}

                                    data: {"type":"finish","totalSteps":1,"finishReason":"stop","usage":{"promptTokens":1,"completionTokens":1}}

                                """.trimIndent(),
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
                            )
                        } else {
                            respond(
                                content = """{"text":"hello","finishReason":"stop","usage":{"promptTokens":1,"completionTokens":1},"providerMetadata":{"gateway":{"id":"gen_1"}}}""",
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        }
                    }
                    else -> respond("""{}""", HttpStatusCode.NotFound, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            },
        )
        val provider = CreateGatewayHttpProvider(
            client,
            GatewayProviderSettings(baseUrl = "https://gateway.test/v3/ai", apiKey = "secret"),
        )

        val generated = TextGenerator(provider.languageModel("gpt-test")).generate(GenerationInput.Prompt("hi")).single()
        val streamed = drainAllItems(provider.languageModel("gpt-test").stream(LanguageModelCallParams(listOf(UserMessage("hi")))))

        assertEquals("hello", generated.text)
        assertEquals(JsonPrimitive("gen_1"), generated.providerMetadata.toMap()["gateway"]?.jsonObject?.get("id"))
        assertTrue(streamed.any { it is StreamEvent.TextDelta && it.text == "hello" })
        assertTrue(streamed.any { it is StreamEvent.Finish })
        assertEquals(listOf("/v3/ai/language-model", "/v3/ai/language-model"), seenPaths)
        assertEquals("Bearer secret", seenHeaders.first()["authorization"]?.single())
        assertEquals("gpt-test", seenHeaders.first()["ai-language-model-id"]?.single())
    }

    @Test
    fun `KtorGatewayTransport rejects malformed required stream event fields`() = runTest {
        val client = HttpClient(
            MockEngine { request ->
                when (request.url.encodedPath) {
                    "/v3/ai/language-model" -> respond(
                        content = """data: {"type":"text-delta","id":"t1"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
                    )
                    else -> respond("""{}""", HttpStatusCode.NotFound, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            },
        )
        val provider = CreateGatewayHttpProvider(
            client,
            GatewayProviderSettings(baseUrl = "https://gateway.test/v3/ai", apiKey = "secret"),
        )

        val error = assertFailsWith<WireDecodeException> {
            drainAllItems(provider.languageModel("gpt-test").stream(LanguageModelCallParams(listOf(UserMessage("hi")))))
        }

        assertEquals("gateway", error.provider)
        assertEquals("stream event", error.operation)
    }

    @Test
    fun `KtorGatewayTransport fetches gateway metadata endpoints`() = runTest {
        val seenUrls = mutableListOf<String>()
        val client = HttpClient(
            MockEngine { request ->
                seenUrls += request.url.toString()
                val content = when (request.url.encodedPath) {
                    "/v3/ai/config" -> """{"models":[{"id":"m1","name":"Model 1","description":"test","pricing":{"input":"0.1","output":"0.2","input_cache_read":"0.01","input_cache_write":"0.02"},"specification":{"specificationVersion":"v3","provider":"openai","modelId":"m1"},"modelType":"language"}]}"""
                    "/v1/credits" -> """{"balance":"100","total_used":"12"}"""
                    "/v1/report" -> """{"results":[{"model":"m1","credential_type":"byok","total_cost":1.5,"request_count":2}]}"""
                    "/v1/generation" -> """{"data":{"id":"gen_1","total_cost":1.0,"upstream_inference_cost":0.5,"usage":1.0,"created_at":"2026-06-03T00:00:00Z","model":"m1","is_byok":true,"provider_name":"openai","streamed":false,"finish_reason":"stop","latency":10,"generation_time":20,"native_tokens_prompt":1,"native_tokens_completion":2,"native_tokens_reasoning":0,"native_tokens_cached":0,"native_tokens_cache_creation":0,"billable_web_search_calls":0}}"""
                    else -> """{}"""
                }
                respond(content, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            },
        )
        val provider = CreateGatewayHttpProvider(
            client,
            GatewayProviderSettings(baseUrl = "https://gateway.test/v3/ai", apiKey = "secret"),
        )

        val models = provider.getAvailableModels()
        val credits = provider.getCredits()
        val spend = provider.getSpendReport(GatewaySpendReportParams("2026-06-01", "2026-06-03"))
        val generation = provider.getGenerationInfo(GatewayGenerationInfoParams("gen_1"))

        assertEquals("m1", models.models.single().id)
        assertEquals(GatewayModelType.Language, models.models.single().modelType)
        assertEquals("0.01", models.models.single().pricing?.cachedInputTokens)
        assertEquals("100", credits.balance)
        assertEquals(GatewayCredentialType.Byok, spend.results.single().credentialType)
        assertEquals("gen_1", generation.id)
        assertTrue(seenUrls.any { it.contains("/v1/report?start_date=2026-06-01") })
        assertTrue(seenUrls.any { it.contains("/v1/generation?id=gen_1") })
    }

    @Test
    fun `KtorGatewayTransport maps embedding image video and reranking model calls`() = runTest {
        val client = HttpClient(
            MockEngine { request ->
                val content = when (request.url.encodedPath) {
                    "/v3/ai/embedding-model" -> """{"embeddings":[[1,2]],"usage":{"tokens":3},"providerMetadata":{"gateway":{"ok":true}}}"""
                    "/v3/ai/image-model" -> """{"images":["iVBORw0="],"warnings":[{"type":"other","message":"note"}]}"""
                    "/v3/ai/video-model" -> """
                        data: {"type":"result","videos":[{"type":"base64","data":"AAAA","mediaType":"video/mp4"}],"warnings":[{"type":"other","message":"note"}]}

                    """.trimIndent()
                    "/v3/ai/reranking-model" -> """{"ranking":[{"index":1,"relevanceScore":0.9},{"index":0,"relevanceScore":0.1}]}"""
                    else -> """{}"""
                }
                respond(
                    content,
                    HttpStatusCode.OK,
                    headersOf(
                        HttpHeaders.ContentType,
                        if (request.url.encodedPath == "/v3/ai/video-model") "text/event-stream" else "application/json",
                    ),
                )
            },
        )
        val provider = CreateGatewayHttpProvider(
            client,
            GatewayProviderSettings(baseUrl = "https://gateway.test/v3/ai", apiKey = "secret"),
        )

        val embedding = Embedding.embed(provider.embeddingModel("embed"), "abc")
        val image = ImageGeneration.generateImage(provider.imageModel("image"), "logo")
        val video = VideoGeneration.generateVideo(provider.videoModel("video"), "clip")
        val ranked = Reranking.rerank(provider.rerankingModel("rank"), "q", listOf("first", "second"))

        assertEquals(listOf(1f, 2f), embedding.embedding)
        assertEquals(3, embedding.usage.tokens)
        assertEquals("iVBORw0=", image.image.base64)
        assertEquals("note", image.warnings.single().message)
        assertEquals("AAAA", video.video.base64)
        assertEquals("second", ranked.results.first().value)
    }

    @Test
    fun `concurrent getAvailableModels within refresh window calls transport at most once`() = runTest {
        val transport = CapturingGatewayTransport()
        val provider = GatewayProvider(
            GatewayProviderSettings(
                apiKey = "secret",
                transport = transport,
                metadataCacheRefreshMillis = 60_000L,
                nowMillis = { 1_000L },
            ),
        )

        val results = (1..10).map { async { provider.getAvailableModels() } }.awaitAll()

        assertEquals(1, transport.metadataCalls)
        assertTrue(results.all { it == results.first() })
    }

    private class CapturingGatewayTransport : GatewayTransport {
        val contexts = mutableListOf<GatewayRequestContext>()
        val spendParams = mutableListOf<GatewaySpendReportParams>()
        var metadataCalls = 0

        override suspend fun generateText(
            context: GatewayRequestContext,
            modelId: ModelId,
            params: LanguageModelCallParams,
        ): LanguageModelResult {
            contexts += context
            return LanguageModelResult(
                text = "gateway:$modelId",
                finishReason = FinishReason.Stop,
                usage = Usage.of(promptTokens = params.messages.size, completionTokens = 1),
            )
        }

        override fun streamText(
            context: GatewayRequestContext,
            modelId: ModelId,
            params: LanguageModelCallParams,
        ): Flow<StreamEvent> {
            contexts += context
            return flowOf(StreamEvent.TextDelta("text", "gateway:$modelId"))
        }

        override suspend fun embed(
            context: GatewayRequestContext,
            modelId: ModelId,
            params: EmbeddingModelCallParams,
        ): EmbeddingModelResult {
            contexts += context
            return EmbeddingModelResult(
                embeddings = params.values.map { listOf(it.length.toFloat()) },
                usage = EmbeddingUsage(tokens = params.values.sumOf { it.length }),
            )
        }

        override suspend fun generateImage(
            context: GatewayRequestContext,
            modelId: ModelId,
            params: ImageGenerationParams,
        ): ImageModelResult {
            contexts += context
            return ImageModelResult(
                images = List(params.n) {
                    GeneratedFile(mediaType = "image/png", base64 = "iVBORw0=", filename = modelId.value)
                },
            )
        }

        override suspend fun generateVideo(
            context: GatewayRequestContext,
            modelId: ModelId,
            params: VideoGenerationParams,
        ): VideoModelResult {
            contexts += context
            return VideoModelResult(
                videos = List(params.n) {
                    GeneratedFile(mediaType = "video/mp4", base64 = "AAAA", filename = modelId.value)
                },
            )
        }

        override suspend fun rerank(
            context: GatewayRequestContext,
            modelId: ModelId,
            params: RerankingParams,
        ): RerankingModelResult {
            contexts += context
            return RerankingModelResult(
                results = params.documents.mapIndexed { index, value ->
                    RerankedItem(value = value, score = value.length.toFloat(), index = index)
                },
            )
        }

        override suspend fun getAvailableModels(context: GatewayRequestContext): GatewayFetchMetadataResponse {
            contexts += context
            metadataCalls += 1
            return GatewayFetchMetadataResponse(
                models = listOf(
                    GatewayLanguageModelEntry(
                        id = "model-$metadataCalls",
                        name = "Model $metadataCalls",
                        specification = GatewayLanguageModelSpecification(provider = "gateway", modelId = "model-$metadataCalls"),
                        modelType = GatewayModelType.Language,
                    ),
                ),
            )
        }

        override suspend fun getCredits(context: GatewayRequestContext): GatewayCreditsResponse {
            contexts += context
            return GatewayCreditsResponse(balance = "100", totalUsed = "12")
        }

        override suspend fun getSpendReport(
            context: GatewayRequestContext,
            params: GatewaySpendReportParams,
        ): GatewaySpendReportResponse {
            contexts += context
            spendParams += params
            return GatewaySpendReportResponse(
                results = listOf(
                    GatewaySpendReportRow(
                        model = params.model ?: "model",
                        credentialType = params.credentialType,
                        totalCost = 12.5,
                        requestCount = 3,
                    ),
                ),
            )
        }

        override suspend fun getGenerationInfo(
            context: GatewayRequestContext,
            params: GatewayGenerationInfoParams,
        ): GatewayGenerationInfo {
            contexts += context
            return GatewayGenerationInfo(
                id = params.id,
                totalCost = 1.0,
                upstreamInferenceCost = 0.5,
                usage = 1.0,
                createdAt = "2026-06-03T00:00:00Z",
                model = "model",
                isByok = true,
                providerName = "gateway",
                streamed = false,
                finishReason = "stop",
                latency = 10,
                generationTime = 20,
                promptTokens = 1,
                completionTokens = 2,
                reasoningTokens = 0,
                cachedTokens = 0,
                cacheCreationTokens = 0,
                billableWebSearchCalls = 0,
            )
        }
    }
}
