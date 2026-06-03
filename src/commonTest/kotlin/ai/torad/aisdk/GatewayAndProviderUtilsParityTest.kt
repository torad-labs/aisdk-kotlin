package ai.torad.aisdk

import ai.torad.aisdk.testing.drainAllItems
import ai.torad.aisdk.providers.MockImageModel
import ai.torad.aisdk.providers.MockSpeechModel
import ai.torad.aisdk.providers.MockTranscriptionModel
import ai.torad.aisdk.providers.MockVideoModel
import ai.torad.aisdk.providers.mockAudioSource
import ai.torad.aisdk.ui.SafeValidateUIMessagesResult
import ai.torad.aisdk.ui.TextUIPartState
import ai.torad.aisdk.ui.UIMessage
import ai.torad.aisdk.ui.UIMessagePart
import ai.torad.aisdk.ui.UIMessageRole
import ai.torad.aisdk.ui.safeValidateUIMessages
import ai.torad.aisdk.ui.validateUIMessages
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
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
        val provider = createGateway(
            GatewayProviderSettings(
                baseUrl = "https://gateway.test/v3/ai/",
                apiKey = "secret",
                headers = mapOf("x-team" to "torad"),
                transport = transport,
            ),
        )

        val text = generateText<String>(provider("chat-model"), prompt = "hi")
        val chat = generateText<String>(provider.chat("chat-model-2"), prompt = "hi")
        val embedding = embed(provider.embedding("embed-model"), "hello")
        val image = generateImage(provider.image("image-model"), "logo")
        val video = generateVideo(provider.video("video-model"), "clip")
        val reranked = rerank(provider.reranking("rank-model"), "q", listOf("a", "bb"))

        assertEquals("gateway:chat-model", text.text)
        assertEquals("gateway:chat-model-2", chat.text)
        assertEquals(listOf(5f), embedding.embedding)
        assertEquals("image-model", image.image.filename)
        assertEquals("video-model", video.video.filename)
        assertEquals("bb", reranked.results.first().value)
        assertEquals("gateway", provider.languageModel("x").provider)
        assertEquals("gateway", provider.textEmbeddingModel("x").provider)
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
        val provider = createGatewayProvider(
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
    fun `gateway headers and errors mirror public gateway package behavior`() = runTest {
        val apiKeyHeaders = gatewayHeaders(
            GatewayProviderSettings(
                apiKey = "key",
                headers = mapOf("User-Agent" to "app"),
            ),
        )
        val oidcHeaders = gatewayHeaders(
            GatewayProviderSettings(
                authTokenProvider = { GatewayAuthToken("oidc", GatewayAuthMethod.Oidc) },
            ),
        )

        assertEquals("Bearer key", apiKeyHeaders["authorization"])
        assertEquals("app ai-sdk/gateway-kotlin", apiKeyHeaders["user-agent"])
        assertEquals(GatewayAuthMethod.ApiKey, parseGatewayAuthMethod(apiKeyHeaders))
        assertEquals(GatewayAuthMethod.Oidc, parseGatewayAuthMethod(oidcHeaders))
        assertNull(parseGatewayAuthMethod(mapOf(GATEWAY_AUTH_METHOD_HEADER to "API-KEY")))

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
        val schema = jsonSchema<JsonObject>(
            buildJsonObject { put("type", JsonPrimitive("object")) },
        ) { it.jsonObject }
        val text = """
            event: message
            data: {"type":"chunk","value":1}

            data: [DONE]

            data: {"type":"chunk","value":2}

        """.trimIndent()

        val parsed = parseJsonEventStream(text, schema)
        val streamed = drainAllItems(
            parseJsonEventStream(
                flowOf("data: {\"type\":\"chunk\"", ",\"value\":3}\n\n", "data: [DONE]\n\n"),
                schema,
            ),
        )
        val failed = parseJsonEventStream("data: {bad json}\n\n", schema)

        assertEquals(listOf(1, 2), parsed.map { (it as ParseResult.Success).value["value"]?.let { value -> value as JsonPrimitive }?.content?.toInt() })
        assertEquals(JsonPrimitive(3), (streamed.single() as ParseResult.Success).value["value"])
        assertIs<ParseResult.Failure>(failed.single())
    }

    @Test
    fun `provider util helpers match v6 defaults`() {
        val generated = generateId()
        val prefixed = createIdGenerator(prefix = "msg", size = 4, alphabet = "ab", separator = "_").generate()
        val headers = withUserAgentSuffix(
            mapOf("User-Agent" to "app/1.0", "X-Empty" to null),
            "ai-sdk/test",
        )

        assertEquals(16, generated.length)
        assertTrue(prefixed.startsWith("msg_"))
        assertFailsWith<IllegalArgumentException> {
            createIdGenerator(prefix = "bad", alphabet = "ab-", separator = "-").generate()
        }
        assertEquals(mapOf("a" to "1", "b" to null), combineHeaders(mapOf("a" to "0"), mapOf("a" to "1", "b" to null)))
        assertEquals(mapOf("x-a" to "1"), normalizeHeaders(mapOf("X-A" to "1", "X-B" to null)))
        assertEquals("app/1.0 ai-sdk/test", headers["user-agent"])
        assertEquals("mp3", mediaTypeToExtension("audio/mpeg"))
        assertEquals("archive", stripFileExtension("archive.tar.gz"))
        assertEquals("https://x.test/a", withoutTrailingSlash("https://x.test/a/"))
        assertEquals(mapOf("a" to JsonPrimitive(1)), removeUndefinedEntries(mapOf("a" to JsonPrimitive(1), "b" to null)))
        assertEquals("boom", getErrorMessage(IllegalStateException("boom")))
    }

    @Test
    fun `binary url and media provider-utils are available in common code`() {
        val raw = byteArrayOf(1, 2, 3)
        val encoded = convertByteArrayToBase64(raw)

        assertEquals(raw.toList(), convertBase64ToByteArray(encoded).toList())
        assertEquals(raw.toList(), convertBase64ToUint8Array(encoded).toList())
        assertEquals(encoded, convertUint8ArrayToBase64(raw))
        assertEquals(byteArrayOf(0xfb.toByte(), 0xff.toByte()).toList(), convertBase64ToUint8Array("-_8=").toList())
        assertEquals("already-base64", convertToBase64("already-base64"))
        assertEquals(encoded, convertToBase64(raw))
        assertTrue(
            isUrlSupported(
                mediaType = "image/png",
                url = "https://cdn.example.com/image.png",
                supportedUrls = mapOf("image/*" to listOf(Regex("cdn\\.example\\.com"))),
            ),
        )
        validateDownloadUrl("https://example.com/file.png")
        validateDownloadUrl("data:text/plain;base64,SGk=")
        assertFailsWith<DownloadError> { validateDownloadUrl("http://localhost/file") }
        assertFailsWith<DownloadError> { validateDownloadUrl("http://10.0.0.1/file") }
        assertFailsWith<DownloadError> { validateDownloadUrl("file:///tmp/secret") }
    }

    @Test
    fun `lazy schemas validation provider options and setting loaders match provider-utils`() {
        var created = 0
        val lazy = lazySchema<JsonObject> {
            created += 1
            jsonSchema(
                buildJsonObject { put("type", JsonPrimitive("object")) },
            ) { value -> value.jsonObject }
        }
        val value = buildJsonObject { put("name", JsonPrimitive("ok")) }

        assertEquals(0, created)
        assertEquals(JsonPrimitive("ok"), validateTypes(value, lazy)["name"])
        assertEquals(JsonPrimitive("ok"), safeValidateTypes(value, lazy).let { (it as ValidationResult.Success).value["name"] })
        assertEquals(1, created)
        assertEquals(lazy(), asSchema(lazy))
        assertEquals(1, created)
        assertNull(parseProviderOptions("missing", emptyMap(), lazy()))
        assertEquals(JsonPrimitive("ok"), parseProviderOptions("test", mapOf("test" to value), lazy())?.get("name"))
        assertFailsWith<InvalidArgumentError> {
            parseProviderOptions(
                "test",
                mapOf("test" to value),
                jsonSchema<JsonObject>(JsonObject(emptyMap())) { throw IllegalStateException("bad") },
            )
        }

        assertEquals("explicit", loadApiKey("explicit", "TEST_API_KEY", description = "Test"))
        assertEquals("from-env", loadApiKey(null, "TEST_API_KEY", description = "Test", environment = mapOf("TEST_API_KEY" to "from-env")))
        assertEquals("setting", loadSetting(null, "TEST_SETTING", "setting", "Test", mapOf("TEST_SETTING" to "setting")))
        assertEquals("optional", loadOptionalSetting(null, "TEST_OPTIONAL", mapOf("TEST_OPTIONAL" to "optional")))
        assertFailsWith<LoadAPIKeyError> { loadApiKey(null, "MISSING_API_KEY", description = "Missing") }
        assertFailsWith<LoadSettingError> { loadSetting(null, "MISSING_SETTING", "setting", "Missing") }
    }

    @Test
    fun `provider tool factories execute tools and create name mappings`() = runTest {
        val inputSchema = jsonSchema<JsonObject>(
            buildJsonObject {
                put("type", JsonPrimitive("object"))
                put("additionalProperties", JsonPrimitive(false))
            },
        ) { value -> value.jsonObject }
        val outputSchema = jsonSchema<JsonElement>(
            buildJsonObject { put("type", JsonPrimitive("object")) },
        )
        val factory = createProviderToolFactory<JsonObject, Unit>(
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
        val descriptor = toolSetOf<Unit>(hosted).descriptors.single()
        val mapping = createToolNameMapping(
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
            executeTool(
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
        val withOutputSchema = createProviderToolFactoryWithOutputSchema<JsonObject, JsonElement, Unit>(
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
        val fileFromBytes = DefaultGeneratedFile(byteArrayOf(1, 2, 3), "application/octet-stream")
        val fileFromBase64 = DefaultGeneratedFile(fileFromBytes.base64, "application/octet-stream")
        val image: Experimental_GenerateImageResult = experimental_generateImage(MockImageModel(), "logo")
        val speech: Experimental_SpeechResult = experimental_generateSpeech(MockSpeechModel(), "hello")
        val transcript: Experimental_TranscriptionResult =
            experimental_transcribe(MockTranscriptionModel(), mockAudioSource())
        val video = experimental_generateVideo(MockVideoModel(), "clip")

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

        val pruned = pruneMessages(
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
        val failed = safeValidateUIMessages(emptyList())

        assertIs<SafeValidateUIMessagesResult.Success>(ok)
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
        val provider = createGatewayHttpProvider(
            client,
            GatewayProviderSettings(baseUrl = "https://gateway.test/v3/ai", apiKey = "secret"),
        )

        val generated = generateText<String>(provider.languageModel("gpt-test"), prompt = "hi")
        val streamed = drainAllItems(provider.languageModel("gpt-test").stream(LanguageModelCallParams(listOf(userMessage("hi")))))

        assertEquals("hello", generated.text)
        assertEquals(JsonPrimitive("gen_1"), generated.providerMetadata["gateway"]?.jsonObject?.get("id"))
        assertTrue(streamed.any { it is StreamEvent.TextDelta && it.text == "hello" })
        assertTrue(streamed.any { it is StreamEvent.Finish })
        assertEquals(listOf("/v3/ai/language-model", "/v3/ai/language-model"), seenPaths)
        assertEquals("Bearer secret", seenHeaders.first()["authorization"]?.single())
        assertEquals("gpt-test", seenHeaders.first()["ai-language-model-id"]?.single())
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
        val provider = createGatewayHttpProvider(
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
        val provider = createGatewayHttpProvider(
            client,
            GatewayProviderSettings(baseUrl = "https://gateway.test/v3/ai", apiKey = "secret"),
        )

        val embedding = embed(provider.embeddingModel("embed"), "abc")
        val image = generateImage(provider.imageModel("image"), "logo")
        val video = generateVideo(provider.videoModel("video"), "clip")
        val ranked = rerank(provider.rerankingModel("rank"), "q", listOf("first", "second"))

        assertEquals(listOf(1f, 2f), embedding.embedding)
        assertEquals(3, embedding.usage.tokens)
        assertEquals("iVBORw0=", image.image.base64)
        assertEquals("note", image.warnings.single().message)
        assertEquals("AAAA", video.video.base64)
        assertEquals("second", ranked.results.first().value)
    }

    private class CapturingGatewayTransport : GatewayTransport {
        val contexts = mutableListOf<GatewayRequestContext>()
        val spendParams = mutableListOf<GatewaySpendReportParams>()
        var metadataCalls = 0

        override suspend fun generateText(
            context: GatewayRequestContext,
            modelId: GatewayModelId,
            params: LanguageModelCallParams,
        ): LanguageModelResult {
            contexts += context
            return LanguageModelResult(
                text = "gateway:$modelId",
                finishReason = FinishReason.Stop,
                usage = Usage(promptTokens = params.messages.size, completionTokens = 1),
            )
        }

        override fun streamText(
            context: GatewayRequestContext,
            modelId: GatewayModelId,
            params: LanguageModelCallParams,
        ): Flow<StreamEvent> {
            contexts += context
            return flowOf(StreamEvent.TextDelta("text", "gateway:$modelId"))
        }

        override suspend fun embed(
            context: GatewayRequestContext,
            modelId: GatewayEmbeddingModelId,
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
            modelId: GatewayImageModelId,
            params: ImageGenerationParams,
        ): ImageModelResult {
            contexts += context
            return ImageModelResult(
                images = List(params.n) {
                    GeneratedFile(mediaType = "image/png", base64 = "iVBORw0=", filename = modelId)
                },
            )
        }

        override suspend fun generateVideo(
            context: GatewayRequestContext,
            modelId: GatewayVideoModelId,
            params: VideoGenerationParams,
        ): VideoModelResult {
            contexts += context
            return VideoModelResult(
                videos = List(params.n) {
                    GeneratedFile(mediaType = "video/mp4", base64 = "AAAA", filename = modelId)
                },
            )
        }

        override suspend fun rerank(
            context: GatewayRequestContext,
            modelId: GatewayRerankingModelId,
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
