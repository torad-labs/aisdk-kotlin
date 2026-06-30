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
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class LiteRTLanguageModelTest {

    private class FakeLiteRTFactory(
        private val sendResponse: LiteRTMessage = LiteRTMessage(LiteRTMessageRole.Model),
        private val streamResponses: List<LiteRTMessage> = emptyList(),
    ) : LiteRTConversationFactory {
        private val capturedRequests: MutableList<LiteRTConversationRequest> = mutableListOf()
        private val conversations: MutableList<FakeLiteRTConversation> = mutableListOf()

        val requests: List<LiteRTConversationRequest> get() = capturedRequests
        val createdConversations: List<FakeLiteRTConversation> get() = conversations

        override suspend fun create(request: LiteRTConversationRequest): LiteRTConversation {
            capturedRequests += request
            return FakeLiteRTConversation(sendResponse, streamResponses).also { conversations += it }
        }
    }

    private class FakeLiteRTConversation(
        private val sendResponse: LiteRTMessage,
        private val streamResponses: List<LiteRTMessage>,
    ) : LiteRTConversation {
        private var sendCallCount: Int = 0
        private var streamCallCount: Int = 0
        private var closeCallCount: Int = 0

        val sendCalls: Int get() = sendCallCount
        val streamCalls: Int get() = streamCallCount
        val closeCalls: Int get() = closeCallCount

        override suspend fun send(message: LiteRTMessage, extraContext: Map<String, Any?>): LiteRTMessage {
            sendCallCount += 1
            return sendResponse
        }

        override fun stream(message: LiteRTMessage, extraContext: Map<String, Any?>): Flow<LiteRTMessage> = flow {
            streamCallCount += 1
            for (response in streamResponses) emit(response)
        }

        override fun close() {
            closeCallCount += 1
        }
    }

    @Test
    fun `generate prepares LiteRT conversation and maps text reasoning and tool calls`() = runTest {
        val factory = FakeLiteRTFactory(
            sendResponse = LiteRTMessage(
                role = LiteRTMessageRole.Model,
                content = listOf(LiteRTContent.Text("answer")),
                channels = mapOf("thinking" to "because"),
                toolCalls = listOf(
                    LiteRTToolCall(
                        name = "lookup",
                        arguments = JsonObject(mapOf("query" to JsonPrimitive("docs"))),
                        id = "call_1",
                    ),
                ),
            ),
        )
        val model = LiteRTLanguageModel(
            modelId = "gemma-litert",
            conversationFactory = factory,
            settings = LiteRTLanguageModelSettings(
                defaultSamplerConfig = LiteRTSamplerConfig(topK = 8, topP = 0.8, temperature = 0.6),
            ),
        )

        val result = model.generate(
            LanguageModelCallParams(
                messages = listOf(SystemMessage("system"), UserMessage("hello")),
                tools = listOf(
                    LanguageModelTool(
                        name = "lookup",
                        description = "Lookup docs",
                        parametersSchemaJson = """{"type":"object"}""",
                    ),
                ),
                temperature = 0.2f,
                providerOptions = ProviderOptions.ofPairs(
                    "litert" to buildJsonObject {
                        put("enableThinking", JsonPrimitive(true))
                        put("extraContext", buildJsonObject { put("screen", JsonPrimitive("home")) })
                    },
                ),
            ),
        )

        assertEquals("answer", result.text)
        assertEquals(FinishReason.ToolCalls, result.finishReason)
        assertEquals("because", result.content.filterIsInstance<ContentPart.Reasoning>().single().text)
        assertEquals("lookup", result.toolCalls.single().toolName)
        val request = factory.requests.single()
        assertEquals(false, request.automaticToolCalling)
        assertEquals(listOf(LiteRTContent.Text("system")), request.systemInstruction)
        assertEquals(LiteRTMessageRole.User, request.message.role)
        assertEquals("hello", assertIs<LiteRTContent.Text>(request.message.content.single()).text)
        assertEquals(0.2, request.samplerConfig?.temperature ?: 0.0, absoluteTolerance = 0.000001)
        assertEquals(8, request.samplerConfig?.topK)
        assertEquals(true, request.extraContext["enable_thinking"] as Boolean)
        assertEquals("home", request.extraContext["screen"])
        assertEquals("lookup", request.tools.single().name)
        assertEquals(1, factory.createdConversations.single().sendCalls)
        assertEquals(1, factory.createdConversations.single().closeCalls)
    }

    @Test
    fun `stream maps cumulative text reasoning channels and tool calls`() = runTest {
        val factory = FakeLiteRTFactory(
            streamResponses = listOf(
                LiteRTMessage(
                    role = LiteRTMessageRole.Model,
                    content = listOf(LiteRTContent.Text("Hel")),
                    channels = mapOf("thinking" to "why"),
                ),
                LiteRTMessage(
                    role = LiteRTMessageRole.Model,
                    content = listOf(LiteRTContent.Text("Hello")),
                    channels = mapOf("thinking" to "why now"),
                ),
                LiteRTMessage(
                    role = LiteRTMessageRole.Model,
                    toolCalls = listOf(
                        LiteRTToolCall(
                            name = "lookup",
                            arguments = JsonObject(mapOf("query" to JsonPrimitive("docs"))),
                        ),
                    ),
                ),
            ),
        )
        val model = LiteRTLanguageModel(
            modelId = "gemma-litert",
            conversationFactory = factory,
            settings = LiteRTLanguageModelSettings(
                streamTextMode = LiteRTStreamTextMode.Cumulative,
                toolCallIdGenerator = { "call_fixed" },
            ),
        )

        val events = model.stream(LanguageModelCallParams(messages = listOf(UserMessage("hello")))).toList()

        assertEquals(listOf("Hel", "lo"), events.filterIsInstance<StreamEvent.TextDelta>().map { it.text })
        assertEquals(listOf("why", " now"), events.filterIsInstance<StreamEvent.ReasoningDelta>().map { it.text })
        val toolCall = events.filterIsInstance<StreamEvent.ToolCall>().single()
        assertEquals("call_fixed", toolCall.toolCallId)
        assertEquals("lookup", toolCall.toolName)
        assertEquals(FinishReason.ToolCalls, events.filterIsInstance<StreamEvent.Finish>().single().finishReason)
        assertEquals(1, factory.createdConversations.single().streamCalls)
        assertEquals(1, factory.createdConversations.single().closeCalls)
    }
}
