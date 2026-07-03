@file:OptIn(LowLevelLanguageModelApi::class)

package ai.torad.aisdk

import ai.torad.aisdk.providers.LiteRTContent
import ai.torad.aisdk.providers.LiteRTConversation
import ai.torad.aisdk.providers.LiteRTConversationFactory
import ai.torad.aisdk.providers.LiteRTConversationRequest
import ai.torad.aisdk.providers.LiteRTLanguageModel
import ai.torad.aisdk.providers.LiteRTLanguageModelSettings
import ai.torad.aisdk.providers.LiteRTMessage
import ai.torad.aisdk.providers.LiteRTMessageRole
import ai.torad.aisdk.providers.LiteRTSamplerConfig
import ai.torad.aisdk.providers.LiteRTStreamTextMode
import ai.torad.aisdk.providers.LiteRTToolCall
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LiteRTLanguageModelTest {

    private class FakeLiteRTFactory(
        private val sendResponse: LiteRTMessage = LiteRTMessage {
            role(LiteRTMessageRole.Model)
        },
        private val streamResponses: List<LiteRTMessage> = emptyList(),
        private val streamFactory: (FakeLiteRTConversation.() -> Flow<LiteRTMessage>)? = null,
    ) : LiteRTConversationFactory {
        private val capturedRequests: MutableList<LiteRTConversationRequest> = mutableListOf()
        private val conversations: MutableList<FakeLiteRTConversation> = mutableListOf()

        val requests: List<LiteRTConversationRequest> get() = capturedRequests
        val createdConversations: List<FakeLiteRTConversation> get() = conversations

        override suspend fun create(request: LiteRTConversationRequest): LiteRTConversation {
            capturedRequests += request
            return FakeLiteRTConversation(sendResponse, streamResponses, streamFactory).also { conversations += it }
        }
    }

    private class FakeLiteRTConversation(
        private val sendResponse: LiteRTMessage,
        private val streamResponses: List<LiteRTMessage>,
        private val streamFactory: (FakeLiteRTConversation.() -> Flow<LiteRTMessage>)?,
    ) : LiteRTConversation {
        private var sendCallCount: Int = 0
        private var streamCallCount: Int = 0
        private val cancelEvents: MutableList<Unit> = mutableListOf()
        private var closeCallCount: Int = 0

        val sendCalls: Int get() = sendCallCount
        val streamCalls: Int get() = streamCallCount
        val cancelCalls: Int get() = cancelEvents.size
        val closeCalls: Int get() = closeCallCount

        override suspend fun send(message: LiteRTMessage, extraContext: Map<String, JsonElement>): LiteRTMessage {
            sendCallCount += 1
            return sendResponse
        }

        override fun stream(
            message: LiteRTMessage,
            extraContext: Map<String, JsonElement>,
        ): Flow<LiteRTMessage> =
            flow {
                streamCallCount += 1
                val source = streamFactory?.invoke(this@FakeLiteRTConversation) ?: flow {
                    for (response in streamResponses) emit(response)
                }
                emitAll(source)
            }

        override fun cancel() {
            cancelEvents += Unit
        }

        override fun close() {
            closeCallCount += 1
        }
    }

    @Test
    fun `generate prepares LiteRT conversation and maps text reasoning and tool calls`() = runTest {
        val factory = FakeLiteRTFactory(
            sendResponse = LiteRTMessage {
                role(LiteRTMessageRole.Model)
                content(listOf(LiteRTContent.Text("answer")))
                channels(mapOf("thinking" to "because"))
                toolCalls(
                    listOf(
                        LiteRTToolCall {
                            name("lookup")
                            arguments(JsonObject(mapOf("query" to JsonPrimitive("docs"))))
                            id("call_1")
                        },
                    ),
                )
            },
        )
        val model = LiteRTLanguageModel(
            modelId = "gemma-litert",
            conversationFactory = factory,
            settings = LiteRTLanguageModelSettings(
                block = {
                    defaultSamplerConfig(
                        LiteRTSamplerConfig {
                            topK(8)
                            topP(0.8)
                            temperature(0.6)
                        },
                    )
                },
            ),
        )

        val result = model.generate(
            LanguageModelCallParams {
                messages(listOf(SystemMessage("system"), UserMessage("hello")))
                tools(
                    listOf(
                        LanguageModelTool(
                            name = "lookup",
                            description = "Lookup docs",
                            parametersSchemaJson = """{"type":"object"}""",
                        ),
                    )
                )
                temperature(0.2f)
                providerOptions(
                    ProviderOptions.ofPairs(
                        "litert" to buildJsonObject {
                            put("enableThinking", JsonPrimitive(true))
                            put("extraContext", buildJsonObject { put("screen", JsonPrimitive("home")) })
                        },
                    )
                )
            },
        )

        assertEquals("answer", result.text)
        assertEquals(FinishReason.ToolCalls, result.finishReason)
        assertEquals(Usage(), result.usage)
        assertEquals("because", result.content.filterIsInstance<ContentPart.Reasoning>().single().text)
        assertEquals("lookup", result.toolCalls.single().toolName)
        val request = factory.requests.single()
        assertEquals(false, request.automaticToolCalling)
        assertEquals(listOf(LiteRTContent.Text("system")), request.systemInstruction)
        assertEquals(LiteRTMessageRole.User, request.message.role)
        assertEquals("hello", assertIs<LiteRTContent.Text>(request.message.content.single()).text)
        assertEquals(0.2, request.samplerConfig?.temperature ?: 0.0, absoluteTolerance = 0.000001)
        assertEquals(8, request.samplerConfig?.topK)
        assertEquals(JsonPrimitive(true), request.extraContext["enable_thinking"])
        assertEquals(JsonPrimitive("home"), request.extraContext["screen"])
        assertEquals("lookup", request.tools.single().name)
        assertEquals(1, factory.createdConversations.single().sendCalls)
        assertEquals(1, factory.createdConversations.single().closeCalls)
    }

    @Test
    fun `generate injects JSON instruction for Json response format`() = runTest {
        val schema = JsonObject(mapOf("type" to JsonPrimitive("object")))
        val factory = FakeLiteRTFactory()
        val model = LiteRTLanguageModel(
            modelId = "gemma-litert",
            conversationFactory = factory,
        )

        val result = model.generate(
            LanguageModelCallParams {
                messages(listOf(SystemMessage("Be concise."), UserMessage("hello")))
                responseFormat(ResponseFormat.Json(schemaJson = schema))
            },
        )

        val systemText = assertIs<LiteRTContent.Text>(factory.requests.single().systemInstruction.single()).text
        assertEquals(
            "Be concise.\n\nJSON schema:\n{\"type\":\"object\"}\n" +
                "You MUST answer with a JSON object that matches the JSON schema above.",
            systemText,
        )
        assertTrue(result.warnings.none { it.message.orEmpty().contains("responseFormat") })
    }

    @Test
    fun `generate propagates LiteRT finish reason and usage`() = runTest {
        val usage = Usage.of(promptTokens = 7, completionTokens = 11)
        val factory = FakeLiteRTFactory(
            sendResponse = LiteRTMessage {
                role(LiteRTMessageRole.Model)
                content(listOf(LiteRTContent.Text("partial")))
                finishReason(FinishReason.Length)
                usage(usage)
            },
        )
        val model = LiteRTLanguageModel(modelId = "gemma-litert", conversationFactory = factory)

        val result = model.generate(
            LanguageModelCallParams {
                messages(listOf(UserMessage("hello")))
            },
        )

        assertEquals("partial", result.text)
        assertEquals(FinishReason.Length, result.finishReason)
        assertEquals(usage, result.usage)
    }

    @Test
    fun `generate maps media content to LiteRT bytes files and text`() = runTest {
        val factory = FakeLiteRTFactory()
        val model = LiteRTLanguageModel(modelId = "gemma-litert", conversationFactory = factory)

        model.generate(
            LanguageModelCallParams {
                messages(
                    listOf(
                        ModelMessage(
                            MessageRole.User,
                            listOf(
                                ContentPart.Image(mediaType = "image/png", base64 = "aGk="),
                                ContentPart.File(mediaType = "audio/wav", url = "data:audio/wav;base64,aGk="),
                                ContentPart.Image(mediaType = "image/jpeg", url = "/tmp/image.jpg"),
                                ContentPart.File(mediaType = "audio/mpeg", url = "/tmp/audio.mp3"),
                                ContentPart.File(mediaType = "text/plain", base64 = "aGk="),
                            ),
                        ),
                    ),
                )
            },
        )

        val content = factory.requests.single().message.content
        val imageBytes = assertIs<LiteRTContent.ImageBytes>(content[0])
        assertEquals("image/png", imageBytes.mediaType)
        assertContentEquals(byteArrayOf(104, 105), imageBytes.bytes.toByteArray())
        val audioBytes = assertIs<LiteRTContent.AudioBytes>(content[1])
        assertEquals("audio/wav", audioBytes.mediaType)
        assertContentEquals(byteArrayOf(104, 105), audioBytes.bytes.toByteArray())
        val imageFile = assertIs<LiteRTContent.ImageFile>(content[2])
        assertEquals("/tmp/image.jpg", imageFile.absolutePath)
        assertEquals("image/jpeg", imageFile.mediaType)
        val audioFile = assertIs<LiteRTContent.AudioFile>(content[3])
        assertEquals("/tmp/audio.mp3", audioFile.absolutePath)
        assertEquals("audio/mpeg", audioFile.mediaType)
        assertEquals("hi", assertIs<LiteRTContent.Text>(content[4]).text)
    }

    @Test
    fun `generate rejects unsupported remote media URLs`() = runTest {
        val model = LiteRTLanguageModel(modelId = "gemma-litert", conversationFactory = FakeLiteRTFactory())

        val error = assertFailsWith<UnsupportedFunctionalityError> {
            model.generate(
                LanguageModelCallParams {
                    messages(
                        listOf(
                            ModelMessage(
                                MessageRole.User,
                                listOf(ContentPart.Image(mediaType = "image/png", url = "https://example.com/a.png")),
                            ),
                        ),
                    )
                },
            )
        }

        assertTrue(error.message.orEmpty().contains("absolute local file path"))
    }

    @Test
    fun `generate maps LiteRT tool choices and provider executed warnings`() = runTest {
        val weather = LanguageModelTool("weather", "Weather", """{"type":"object"}""")
        val news = LanguageModelTool("news", "News", """{"type":"object"}""")
        val providerTool = LanguageModelTool(
            name = "server",
            description = "Server tool",
            parametersSchemaJson = """{"type":"object"}""",
            providerExecuted = true,
        )

        val noneFactory = FakeLiteRTFactory()
        LiteRTLanguageModel("gemma-litert", noneFactory).generate(
            LanguageModelCallParams {
                messages(listOf(UserMessage("hello")))
                tools(listOf(weather))
                toolChoice(ToolChoice.None)
            },
        )
        assertEquals(emptyList(), noneFactory.requests.single().tools)

        val requiredFactory = FakeLiteRTFactory()
        val required = LiteRTLanguageModel("gemma-litert", requiredFactory).generate(
            LanguageModelCallParams {
                messages(listOf(UserMessage("hello")))
                tools(listOf(weather))
                toolChoice(ToolChoice.Required)
            },
        )
        assertEquals(listOf("weather"), requiredFactory.requests.single().tools.map { it.name })
        assertTrue(required.warnings.any { it.message.orEmpty().contains("required tool choice") })

        val specificFactory = FakeLiteRTFactory()
        val specific = LiteRTLanguageModel("gemma-litert", specificFactory).generate(
            LanguageModelCallParams {
                messages(listOf(UserMessage("hello")))
                tools(listOf(weather, news))
                toolChoice(ToolChoice.Specific("news"))
            },
        )
        assertEquals(listOf("news"), specificFactory.requests.single().tools.map { it.name })
        assertTrue(specific.warnings.any { it.message.orEmpty().contains("specific tool choice") })

        val providerFactory = FakeLiteRTFactory()
        val providerFiltered = LiteRTLanguageModel("gemma-litert", providerFactory).generate(
            LanguageModelCallParams {
                messages(listOf(UserMessage("hello")))
                tools(listOf(weather, providerTool))
            },
        )
        assertEquals(listOf("weather"), providerFactory.requests.single().tools.map { it.name })
        assertTrue(providerFiltered.warnings.any { it.message.orEmpty().contains("providerExecuted") })
    }

    @Test
    fun `generate rejects empty prompts with typed error`() = runTest {
        val model = LiteRTLanguageModel(modelId = "gemma-litert", conversationFactory = FakeLiteRTFactory())

        val error = assertFailsWith<UnsupportedFunctionalityError> {
            model.generate(
                LanguageModelCallParams {
                    messages(listOf(SystemMessage("system only")))
                },
            )
        }

        assertTrue(error.message.orEmpty().contains("requires at least one non-system message"))
    }

    @Test
    fun `stream maps delta text mode by default`() = runTest {
        val factory = FakeLiteRTFactory(
            streamResponses = listOf(
                LiteRTMessage {
                    role(LiteRTMessageRole.Model)
                    content(listOf(LiteRTContent.Text("Hel")))
                },
                LiteRTMessage {
                    role(LiteRTMessageRole.Model)
                    content(listOf(LiteRTContent.Text("lo")))
                },
            ),
        )
        val model = LiteRTLanguageModel(modelId = "gemma-litert", conversationFactory = factory)

        val events = model.stream(
            LanguageModelCallParams {
                messages(listOf(UserMessage("hello")))
            },
        ).toList()

        assertEquals(listOf("Hel", "lo"), events.filterIsInstance<StreamEvent.TextDelta>().map { it.text })
        val finish = events.filterIsInstance<StreamEvent.Finish>().single()
        assertEquals(FinishReason.Stop, finish.finishReason)
        assertEquals(Usage(), finish.usage)
        assertEquals(1, factory.createdConversations.single().closeCalls)
    }

    @Test
    fun `stream propagates LiteRT finish reason and usage`() = runTest {
        val usage = Usage.of(promptTokens = 13, completionTokens = 17)
        val factory = FakeLiteRTFactory(
            streamResponses = listOf(
                LiteRTMessage {
                    role(LiteRTMessageRole.Model)
                    content(listOf(LiteRTContent.Text("partial")))
                    finishReason(FinishReason.Length)
                    usage(usage)
                },
            ),
        )
        val model = LiteRTLanguageModel(modelId = "gemma-litert", conversationFactory = factory)

        val events = model.stream(
            LanguageModelCallParams {
                messages(listOf(UserMessage("hello")))
            },
        ).toList()

        assertEquals(listOf("partial"), events.filterIsInstance<StreamEvent.TextDelta>().map { it.text })
        val finish = events.filterIsInstance<StreamEvent.Finish>().single()
        assertEquals(FinishReason.Length, finish.finishReason)
        assertEquals(usage, finish.usage)
    }

    @Test
    fun `generated truncated structured output is not decoded as success`() = runTest {
        val factory = FakeLiteRTFactory(
            sendResponse = LiteRTMessage {
                role(LiteRTMessageRole.Model)
                content(listOf(LiteRTContent.Text("""{"value":1}""")))
                finishReason(FinishReason.Length)
            },
        )
        val agent = TestToolLoopAgent<Unit, JsonElement>(
            model = LiteRTLanguageModel(modelId = "gemma-litert", conversationFactory = factory),
            instructions = "Return JSON.",
            tools = ToolSet(),
            output = Output.json(),
        )

        val error = assertFailsWith<NoOutputGeneratedError> {
            agent.generate(prompt = "hello", options = Unit).first()
        }

        assertTrue(error.message.orEmpty().contains("Length"))
    }

    @Test
    fun `stream cancels conversation when abort fires mid-stream`() = runTest {
        val controller = AbortController()
        val factory = FakeLiteRTFactory(
            streamFactory = {
                flow {
                    emit(
                        LiteRTMessage {
                            role(LiteRTMessageRole.Model)
                            content(listOf(LiteRTContent.Text("partial")))
                        },
                    )
                    controller.abort()
                    emit(
                        LiteRTMessage {
                            role(LiteRTMessageRole.Model)
                            content(listOf(LiteRTContent.Text("ignored")))
                        },
                    )
                }
            },
        )
        val model = LiteRTLanguageModel(modelId = "gemma-litert", conversationFactory = factory)

        assertFailsWith<AbortError> {
            model.stream(
                LanguageModelCallParams {
                    messages(listOf(UserMessage("hello")))
                    abortSignal(controller.signal)
                },
            ).toList()
        }

        val conversation = factory.createdConversations.single()
        assertEquals(1, conversation.cancelCalls)
        assertEquals(1, conversation.closeCalls)
    }

    @Test
    fun `stream closes conversation when upstream fails`() = runTest {
        val factory = FakeLiteRTFactory(
            streamFactory = {
                flow {
                    emit(
                        LiteRTMessage {
                            role(LiteRTMessageRole.Model)
                            content(listOf(LiteRTContent.Text("partial")))
                        },
                    )
                    throw APICallError("stream failed", url = "litert://stream")
                }
            },
        )
        val model = LiteRTLanguageModel(modelId = "gemma-litert", conversationFactory = factory)

        val error = assertFailsWith<APICallError> {
            model.stream(
                LanguageModelCallParams {
                    messages(listOf(UserMessage("hello")))
                },
            ).toList()
        }

        assertEquals("stream failed", error.message)
        assertEquals(1, factory.createdConversations.single().closeCalls)
    }

    @Test
    fun `stream maps cumulative text reasoning channels and tool calls`() = runTest {
        val factory = FakeLiteRTFactory(
            streamResponses = listOf(
                LiteRTMessage {
                    role(LiteRTMessageRole.Model)
                    content(listOf(LiteRTContent.Text("Hel")))
                    channels(mapOf("thinking" to "why"))
                },
                LiteRTMessage {
                    role(LiteRTMessageRole.Model)
                    content(listOf(LiteRTContent.Text("Hello")))
                    channels(mapOf("thinking" to "why now"))
                },
                LiteRTMessage {
                    role(LiteRTMessageRole.Model)
                    toolCalls(
                        listOf(
                            LiteRTToolCall {
                                name("lookup")
                                arguments(JsonObject(mapOf("query" to JsonPrimitive("docs"))))
                            },
                        ),
                    )
                },
            ),
        )
        val model = LiteRTLanguageModel(
            modelId = "gemma-litert",
            conversationFactory = factory,
            settings = LiteRTLanguageModelSettings(
                block = {
                    streamTextMode(LiteRTStreamTextMode.Cumulative)
                    toolCallIdGenerator({ "call_fixed" })
                },
            ),
        )

        val events = model.stream(
            LanguageModelCallParams {
                messages(listOf(UserMessage("hello")))
            }
        ).toList()

        assertEquals(listOf("Hel", "lo"), events.filterIsInstance<StreamEvent.TextDelta>().map { it.text })
        assertEquals(listOf("why", " now"), events.filterIsInstance<StreamEvent.ReasoningDelta>().map { it.text })
        val toolCall = events.filterIsInstance<StreamEvent.ToolCall>().single()
        assertEquals("call_fixed", toolCall.toolCallId)
        assertEquals("lookup", toolCall.toolName)
        assertEquals(FinishReason.ToolCalls, events.filterIsInstance<StreamEvent.Finish>().single().finishReason)
        assertEquals(1, factory.createdConversations.single().streamCalls)
        assertEquals(1, factory.createdConversations.single().closeCalls)
    }

    @Test
    fun `stream emits cumulative text suffixes for prefix snapshots`() = runTest {
        val factory = FakeLiteRTFactory(
            streamResponses = listOf(
                LiteRTMessage {
                    role(LiteRTMessageRole.Model)
                    content(listOf(LiteRTContent.Text("Hel")))
                },
                LiteRTMessage {
                    role(LiteRTMessageRole.Model)
                    content(listOf(LiteRTContent.Text("Hello")))
                },
                LiteRTMessage {
                    role(LiteRTMessageRole.Model)
                    content(listOf(LiteRTContent.Text("Hello!")))
                },
            ),
        )
        val model = LiteRTLanguageModel(
            modelId = "gemma-litert",
            conversationFactory = factory,
            settings = LiteRTLanguageModelSettings(
                block = {
                    streamTextMode(LiteRTStreamTextMode.Cumulative)
                },
            ),
        )

        val events = model.stream(
            LanguageModelCallParams {
                messages(listOf(UserMessage("hello")))
            }
        ).toList()

        assertEquals(listOf("Hel", "lo", "!"), events.filterIsInstance<StreamEvent.TextDelta>().map { it.text })
        assertEquals("Hello!", events.filterIsInstance<StreamEvent.TextDelta>().joinToString("") { it.text })
    }

    @Test
    fun `stream does not duplicate non-prefix cumulative text rewrites`() = runTest {
        val factory = FakeLiteRTFactory(
            streamResponses = listOf(
                LiteRTMessage {
                    role(LiteRTMessageRole.Model)
                    content(listOf(LiteRTContent.Text("Hello world")))
                },
                LiteRTMessage {
                    role(LiteRTMessageRole.Model)
                    content(listOf(LiteRTContent.Text("Hello  world!")))
                },
                LiteRTMessage {
                    role(LiteRTMessageRole.Model)
                    content(listOf(LiteRTContent.Text("Hello  world! Done")))
                },
            ),
        )
        val model = LiteRTLanguageModel(
            modelId = "gemma-litert",
            conversationFactory = factory,
            settings = LiteRTLanguageModelSettings(
                block = {
                    streamTextMode(LiteRTStreamTextMode.Cumulative)
                },
            ),
        )

        val events = model.stream(
            LanguageModelCallParams {
                messages(listOf(UserMessage("hello")))
            }
        ).toList()

        val deltas = events.filterIsInstance<StreamEvent.TextDelta>().map { it.text }
        assertEquals(listOf("Hello world", "!", " Done"), deltas)
        assertEquals("Hello world! Done", deltas.joinToString(""))
        val metadata = assertIs<ProviderMetadata.Raw>(
            events.filterIsInstance<StreamEvent.TextDelta>()[1].providerMetadata,
        )
        val litertMetadata = assertIs<JsonObject>(metadata.metadata["litert-lm"])
        val recovery = assertIs<JsonObject>(litertMetadata["cumulativeRecovery"])
        assertEquals(JsonPrimitive("non-prefix-cumulative-snapshot"), recovery["type"])
        assertEquals(JsonPrimitive("text"), recovery["block"])
    }

    @Test
    fun `stream deduplicates cumulative tool calls across snapshots`() = runTest {
        val repeatedToolCall = LiteRTToolCall {
            name("lookup")
            arguments(JsonObject(mapOf("query" to JsonPrimitive("docs"))))
            id("call_1")
        }
        val factory = FakeLiteRTFactory(
            streamResponses = listOf(
                LiteRTMessage {
                    role(LiteRTMessageRole.Model)
                    toolCalls(listOf(repeatedToolCall))
                },
                LiteRTMessage {
                    role(LiteRTMessageRole.Model)
                    toolCalls(listOf(repeatedToolCall))
                },
            ),
        )
        val model = LiteRTLanguageModel(
            modelId = "gemma-litert",
            conversationFactory = factory,
            settings = LiteRTLanguageModelSettings(
                block = {
                    streamTextMode(LiteRTStreamTextMode.Cumulative)
                },
            ),
        )

        val events = model.stream(
            LanguageModelCallParams {
                messages(listOf(UserMessage("hello")))
            }
        ).toList()

        assertEquals(1, events.filterIsInstance<StreamEvent.ToolInputStart>().size)
        assertEquals(1, events.filterIsInstance<StreamEvent.ToolInputDelta>().size)
        assertEquals(1, events.filterIsInstance<StreamEvent.ToolInputEnd>().size)
        val toolCall = events.filterIsInstance<StreamEvent.ToolCall>().single()
        assertEquals("call_1", toolCall.toolCallId)
        assertEquals("lookup", toolCall.toolName)
        assertEquals(FinishReason.ToolCalls, events.filterIsInstance<StreamEvent.Finish>().single().finishReason)
    }
}
