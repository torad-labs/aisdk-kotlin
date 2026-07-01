@file:OptIn(LowLevelLanguageModelApi::class)

package ai.torad.aisdk

import ai.torad.aisdk.providers.MockAudioSource
import ai.torad.aisdk.providers.MockImageModel
import ai.torad.aisdk.providers.MockSpeechModel
import ai.torad.aisdk.providers.MockTranscriptionModel
import ai.torad.aisdk.providers.MockVideoModel
import ai.torad.aisdk.testing.FlowDrain.drainAllItems
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
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalAiSdkApi::class)
class ProviderUtilsParityTest {
    @Test
    fun `gateway headers and errors mirror public gateway package behavior`() = runTest {
        val apiKeyHeaders = GatewayProviderSettings {
            apiKey("key")
            headers(mapOf("User-Agent" to "app"))
        }.gatewayHeaders()
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
        val prefixed = IdGenerator {
            prefix("msg")
            size(4)
            alphabet("ab")
            separator("_")
        }.generate()
        val headers = ProviderHeaders.withUserAgentSuffix(mapOf("User-Agent" to "app/1.0", "X-Empty" to null),
        "ai-sdk/test",)

        assertEquals(16, generated.length)
        assertTrue(prefixed.startsWith("msg_"))
        assertFailsWith<IllegalArgumentException> {
            IdGenerator {
                prefix("bad")
                alphabet("ab-")
                separator("-")
            }.generate()
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
            ProviderToolFactoryOptions {
                outputSerializer(JsonElement.serializer())
                outputSchema(outputSchema)
                args(mapOf("limit" to JsonPrimitive(3)))
            },
        )
        val descriptor = ToolSet<Unit>(hosted).descriptors.single()
        val mapping = ToolNameMapping(
            tools = listOf(descriptor),
            providerToolNames = mapOf("web.search" to "provider_search"),
        )
        val executable = factory(
            ProviderToolFactoryOptions {
                outputSerializer(JsonElement.serializer())
                execute({ input ->
                                    buildJsonObject { put("seen", input["query"] ?: JsonPrimitive("missing")) }
                                })
            },
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
        val mutableBytes = byteArrayOf(7, 8, 9)
        val copiedFile = DefaultGeneratedFile.fromBytes(mutableBytes, "application/octet-stream")
        mutableBytes[0] = 0
        val exposedBytes = copiedFile.byteArray
        exposedBytes[1] = 0
        val image: Experimental_GenerateImageResult = ImageGeneration.experimental_generateImage(MockImageModel(), "logo")
        val speech: Experimental_SpeechResult = SpeechGeneration.experimental_generateSpeech(MockSpeechModel(), "hello")
        val transcript: Experimental_TranscriptionResult =
            Transcription.experimental_transcribe(MockTranscriptionModel(), MockAudioSource())
        val video = VideoGeneration.experimental_generateVideo(MockVideoModel(), "clip")

        assertEquals(fileFromBytes.byteArray.toList(), fileFromBase64.byteArray.toList())
        assertContentEquals(byteArrayOf(7, 8, 9), copiedFile.byteArray)
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
}
