package ai.torad.aisdk

import ai.torad.aisdk.testing.drainAllItems
import ai.torad.aisdk.ui.ChatStatus
import ai.torad.aisdk.ui.DirectChatTransport
import ai.torad.aisdk.ui.UIMessage
import ai.torad.aisdk.ui.UIMessagePart
import ai.torad.aisdk.ui.UIMessageRole
import ai.torad.aisdk.ui.chatSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

class ChatSessionTest {

    @Test
    fun `chat session exposes state flow over existing transport`() = runTest {
        val assistant = assistantMessage("a1", "pong")
        val session = chatSession(
            id = "chat-1",
            transport = DirectChatTransport {
                flow { emit(assistant) }
            },
        )

        val emitted = drainAllItems(session.sendMessage(userMessage("u1", "ping")))

        assertEquals(listOf(assistant), emitted)
        val state = session.state.value
        assertEquals("chat-1", state.id)
        assertEquals(ChatStatus.Ready, state.status)
        assertNull(state.error)
        assertEquals(listOf(userMessage("u1", "ping"), assistant), state.messages)
    }

    @Test
    fun `chat session enters submitted state before first response`() = runTest {
        val transportStarted = CompletableDeferred<Unit>()
        val releaseResponse = CompletableDeferred<Unit>()
        val session = chatSession(
            transport = DirectChatTransport {
                flow {
                    transportStarted.complete(Unit)
                    releaseResponse.await()
                    emit(assistantMessage("a1", "pong"))
                }
            },
        )

        val job = launch {
            session.sendMessage(userMessage("u1", "ping")).collect {}
        }
        transportStarted.await()

        assertEquals(ChatStatus.Submitted, session.state.value.status)
        assertEquals(listOf(userMessage("u1", "ping")), session.state.value.messages)

        releaseResponse.complete(Unit)
        job.join()
        assertEquals(ChatStatus.Ready, session.state.value.status)
        assertEquals(2, session.state.value.messages.size)
    }

    private fun userMessage(id: String, text: String): UIMessage =
        UIMessage(id = id, role = UIMessageRole.User, parts = listOf(UIMessagePart.Text(text)))

    private fun assistantMessage(id: String, text: String): UIMessage =
        UIMessage(id = id, role = UIMessageRole.Assistant, parts = listOf(UIMessagePart.Text(text)))
}
