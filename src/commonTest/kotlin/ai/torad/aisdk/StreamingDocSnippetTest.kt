package ai.torad.aisdk

import ai.torad.aisdk.providers.MockLanguageModelTextOnly
import ai.torad.aisdk.ui.ChatSession
import ai.torad.aisdk.ui.CreateUiMessageStream
import ai.torad.aisdk.ui.CreateUiMessageStreamResponse
import ai.torad.aisdk.ui.ReadUiMessageStream
import ai.torad.aisdk.ui.ServerResponseWriter
import ai.torad.aisdk.ui.StreamToUiMessages
import ai.torad.aisdk.ui.TextStreamChatTransport
import ai.torad.aisdk.ui.UIMessage
import ai.torad.aisdk.ui.UIMessagePart
import ai.torad.aisdk.ui.UIMessageRole
import ai.torad.aisdk.ui.UiMessageStreams
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Compiles and executes representative snippets from docs/wiki/streaming.md and UI stream docs. */
class StreamingDocSnippetTest {
    private class RecordingResponseWriter : ServerResponseWriter {
        val statuses = mutableListOf<Int>()
        val headers = mutableMapOf<String, String>()
        val chunks = mutableListOf<String>()

        override fun setStatus(status: Int) {
            statuses += status
        }

        override fun setHeader(name: String, value: String) {
            headers[name] = value
        }

        override suspend fun write(chunk: String) {
            chunks += chunk
        }
    }

    @Test
    fun `basic stream and result adapter snippets compile and run`() = runTest {
        val model = MockLanguageModelTextOnly("short checklist")
        val events = TextGenerator(model).stream(
            GenerationInput.Prompt("Write a short migration checklist."),
        ).toList()

        val result = TextGenerator(MockLanguageModelTextOnly("ui stream"))
            .streamResult(GenerationInput.Prompt("Explain UI message streams."))
        val text = result.textStream.toList().joinToString("")
        val response = result.toUiMessageStreamResponse(assistantMessageId = "assistant-42")
        val responseMessages = response.stream.toList()

        assertTrue(events.any { it is StreamEvent.TextDelta })
        assertEquals("ui stream", text)
        assertTrue(responseMessages.any { it.parts.any { part -> part is UIMessagePart.Text } })
    }

    @Test
    fun `UI message stream snippets compile and run`() = runTest {
        val result = TextGenerator(MockLanguageModelTextOnly("hello")).streamResult(GenerationInput.Prompt("hello"))
        val messages = StreamToUiMessages(
            events = result.fullStream,
            assistantMessageId = "assistant-1",
        ).toList()

        val custom = CreateUiMessageStream {
            write(
                UIMessage(
                    id = "status-1",
                    role = UIMessageRole.Assistant,
                    parts = listOf(
                        UIMessagePart.Data(
                            type = "status",
                            data = JsonPrimitive("starting"),
                            transient = true,
                        ),
                    ),
                ),
            )
            merge(messages.asFlow())
        }
        val streamResponse = CreateUiMessageStreamResponse(custom)
        val readBack = ReadUiMessageStream(streamResponse.stream).toList()

        assertEquals("status-1", readBack.first().id)
        assertTrue(readBack.any { it.id == "assistant-1" })
    }

    @Test
    fun `host writer and chat session snippets compile and run`() = runTest {
        val result = TextGenerator(MockLanguageModelTextOnly("writer")).streamResult(GenerationInput.Prompt("writer"))
        val writer = RecordingResponseWriter()
        UiMessageStreams.pipeUiMessageStreamToResponse(
            stream = result.toUiMessageStream("assistant-1"),
            response = writer,
        )

        val session = ChatSession(
            id = "support",
            initialMessages = emptyList(),
            transport = TextStreamChatTransport(handler = { request ->
                TextGenerator(MockLanguageModelTextOnly("reply"))
                    .stream(GenerationInput.Prompt(request.messages.last().id))
                    .filterIsInstance<StreamEvent.TextDelta>()
                    .map { it.text }
            }),
        )
        val userMessage = UIMessage(
            id = "user-1",
            role = UIMessageRole.User,
            parts = listOf(UIMessagePart.Text("Hello")),
        )
        val replies = session.sendMessage(userMessage).toList()

        assertEquals(listOf(200), writer.statuses)
        assertTrue(writer.headers["Content-Type"].orEmpty().contains("text/event-stream"))
        assertTrue(writer.chunks.isNotEmpty())
        assertTrue(replies.isNotEmpty())
    }
}
