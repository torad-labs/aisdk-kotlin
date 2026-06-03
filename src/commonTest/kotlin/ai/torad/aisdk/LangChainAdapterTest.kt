package ai.torad.aisdk

import ai.torad.aisdk.ui.ChatRequest
import ai.torad.aisdk.ui.TextUIPartState
import ai.torad.aisdk.ui.ToolCallState
import ai.torad.aisdk.ui.UIMessage
import ai.torad.aisdk.ui.UIMessagePart
import ai.torad.aisdk.ui.UIMessageRole
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class LangChainAdapterTest {
    @Test
    fun `convertModelMessages maps model roles into LangChain base messages`() {
        val messages = convertModelMessages(
            listOf(
                systemMessage("policy"),
                userMessage("hello"),
                ModelMessage(
                    MessageRole.Assistant,
                    listOf(
                        ContentPart.Text("thinking with tool"),
                        ContentPart.ToolCall(
                            toolCallId = "call_1",
                            toolName = "search",
                            input = JsonObject(mapOf("q" to JsonPrimitive("kotlin"))),
                        ),
                    ),
                ),
                toolMessage("call_1", "search", JsonObject(mapOf("ok" to JsonPrimitive(true)))),
            ),
        )

        assertEquals(LangChainMessageRole.System, messages[0].role)
        assertEquals(JsonPrimitive("policy"), messages[0].content)
        assertEquals(LangChainMessageRole.Human, messages[1].role)
        assertEquals(JsonPrimitive("hello"), messages[1].content)
        assertEquals(LangChainMessageRole.AI, messages[2].role)
        assertEquals("search", messages[2].toolCalls.single().name)
        assertEquals(JsonPrimitive("kotlin"), messages[2].toolCalls.single().input.jsonObject["q"])
        assertEquals(LangChainMessageRole.Tool, messages[3].role)
        assertEquals("call_1", messages[3].toolCallId)
    }

    @Test
    fun `toBaseMessages converts UI messages through model message shape`() {
        val messages = toBaseMessages(
            listOf(
                UIMessage(
                    id = "user_1",
                    role = UIMessageRole.User,
                    parts = listOf(UIMessagePart.Text("hello")),
                ),
                UIMessage(
                    id = "assistant_1",
                    role = UIMessageRole.Assistant,
                    parts = listOf(
                        UIMessagePart.DynamicToolUI(
                            toolCallId = "call_1",
                            toolName = "lookup",
                            state = ToolCallState.OutputAvailable,
                            input = JsonObject(mapOf("id" to JsonPrimitive("123"))),
                            output = JsonObject(mapOf("name" to JsonPrimitive("Ada"))),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(LangChainMessageRole.Human, messages[0].role)
        assertEquals(LangChainMessageRole.AI, messages[1].role)
        assertEquals("lookup", messages[1].toolCalls.single().name)
        assertEquals(LangChainMessageRole.Tool, messages[2].role)
        assertEquals(JsonPrimitive("Ada"), messages[2].content.jsonObject["name"])
    }

    @Test
    fun `toUIMessageStream maps model chunks and invokes callbacks`() = runTest {
        val calls = mutableListOf<String>()

        val messages = toUIMessageStream(
            flowOf(
                LangChainStreamItem.ModelChunk(id = "msg_1", reasoning = "plan "),
                LangChainStreamItem.ModelChunk(text = "hello"),
                LangChainStreamItem.ModelChunk(text = " world"),
            ),
            callbacks = StreamCallbacks(
                onStart = { calls += "start" },
                onToken = { calls += "token:$it" },
                onText = { calls += "text:$it" },
                onFinal = { calls += "final:$it" },
                onFinish = { calls += "finish:${it == null}" },
            ),
        ).toList()

        assertEquals("msg_1", messages.first().id)
        val final = messages.last()
        val reasoning = final.parts[0] as UIMessagePart.Reasoning
        val text = final.parts[1] as UIMessagePart.Text
        assertEquals("plan ", reasoning.text)
        assertEquals(TextUIPartState.Done, reasoning.state)
        assertEquals("hello world", text.text)
        assertEquals(TextUIPartState.Done, text.state)
        assertEquals(
            listOf("start", "token:hello", "text:hello", "token: world", "text: world", "final:hello world", "finish:true"),
            calls,
        )
    }

    @Test
    fun `toUIMessageStream maps streamEvents tool lifecycle and errors`() = runTest {
        val output = JsonObject(mapOf("answer" to JsonPrimitive(42)))
        val messages = toUIMessageStream(
            flowOf(
                LangChainStreamItem.StreamEventsEvent(
                    event = "on_tool_start",
                    data = JsonObject(mapOf("input" to JsonObject(mapOf("q" to JsonPrimitive("docs"))))),
                    runId = "tool_1",
                    name = "search",
                ),
                LangChainStreamItem.StreamEventsEvent(
                    event = "on_tool_end",
                    data = JsonObject(mapOf("output" to output)),
                    runId = "tool_1",
                    name = "search",
                ),
            ),
        ).toList()

        val started = messages[0].parts.single() as UIMessagePart.DynamicToolUI
        val finished = messages[1].parts.single() as UIMessagePart.DynamicToolUI
        assertEquals(ToolCallState.InputAvailable, started.state)
        assertEquals(JsonPrimitive("docs"), started.input?.jsonObject?.get("q"))
        assertEquals(ToolCallState.OutputAvailable, finished.state)
        assertEquals(JsonPrimitive(42), finished.output?.jsonObject?.get("answer"))

        val callbackCalls = mutableListOf<String>()
        val errors = langChainToUIMessageStream(
            flow {
                emit(LangChainStreamItem.ModelChunk(text = "partial"))
                throw IllegalStateException("boom")
            },
            callbacks = StreamCallbacks(
                onFinal = { callbackCalls += "final:$it" },
                onError = { callbackCalls += "error:${it.message}" },
            ),
        ).toList()

        assertIs<UIMessagePart.Error>(errors.last().parts.single())
        assertEquals(listOf("final:partial", "error:boom"), callbackCalls)
    }

    @Test
    fun `LangSmithDeploymentTransport converts UI messages and delegates graph stream`() = runTest {
        val seen = mutableListOf<List<LangChainBaseMessage>>()
        val transport = LangSmithDeploymentTransport(
            options = LangSmithDeploymentTransportOptions(url = "https://deployment.test", apiKey = "key"),
            graphStream = { messages, options ->
                seen += messages
                assertEquals("agent", options.graphId)
                flowOf(LangChainStreamItem.ModelChunk(id = "remote_1", text = "remote answer"))
            },
        )

        val responses = transport.sendMessages(
            ChatRequest(
                messages = listOf(
                    UIMessage("user_1", UIMessageRole.User, listOf(UIMessagePart.Text("hi"))),
                ),
            ),
        ).toList()

        assertEquals(LangChainMessageRole.Human, seen.single().single().role)
        val finalText = responses.last().parts.single() as UIMessagePart.Text
        assertEquals("remote answer", finalText.text)

        assertFailsWith<UnsupportedOperationException> {
            transport.reconnectToStream("chat_1")
        }
    }
}
