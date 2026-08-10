package ai.torad.aisdk

import ai.torad.aisdk.testing.FlowDrain.drainAllItems
import ai.torad.aisdk.ui.Chat
import ai.torad.aisdk.ui.ChatRequest
import ai.torad.aisdk.ui.ChatStatus
import ai.torad.aisdk.ui.ChatTransport
import ai.torad.aisdk.ui.TextStreamChatTransport
import ai.torad.aisdk.ui.UIMessage
import ai.torad.aisdk.ui.UIMessagePart
import ai.torad.aisdk.ui.UIMessageRole
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Multi-turn Chat state: the assistant message id a new turn gets, and the
 * state a resumed stream writes back. Both are turn-boundary behaviours no
 * single-turn test can observe.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatTurnStateTest {

    private fun user(id: String, text: String): UIMessage =
        UIMessage(id = id, role = UIMessageRole.User, parts = listOf(UIMessagePart.Text(text)))

    private fun assistant(id: String, text: String): UIMessage =
        UIMessage(id = id, role = UIMessageRole.Assistant, parts = listOf(UIMessagePart.Text(text)))

    @Test
    fun `a second turn gets a fresh assistant id instead of overwriting the first reply`() = runTest {
        val replies = listOf("reply A", "reply B").iterator()
        val chat = Chat(
            id = "c1",
            transport = TextStreamChatTransport(handler = { flowOf(replies.next()) }),
        )

        drainAllItems(chat.sendMessage(user("u1", "first")))
        drainAllItems(chat.sendMessage(user("u3", "second")))

        assertEquals(
            listOf("u1", "msg_2", "u3", "msg_4"),
            chat.messages.map { it.id },
        )
        assertEquals("reply A", (chat.messages[1].parts.single() as UIMessagePart.Text).text)
        assertEquals("reply B", (chat.messages[3].parts.single() as UIMessagePart.Text).text)
    }

    @Test
    fun `a resumed stream writes its messages into chat state and drives status`() = runTest {
        val resumed = assistant("a1", "recovered")
        val release = CompletableDeferred<Unit>()
        val chat = Chat(
            id = "c1",
            transport = object : ChatTransport {
                override fun sendMessages(request: ChatRequest): Flow<UIMessage> = emptyFlow()

                override fun reconnectToStream(chatId: String, headers: Map<String, String>): Flow<UIMessage> =
                    flow {
                        emit(resumed)
                        release.await()
                    }
            },
        )

        val job = launch { chat.resumeStream().collect {} }
        runCurrent()

        assertEquals(listOf(resumed), chat.messages)
        assertEquals(ChatStatus.Streaming, chat.status)

        release.complete(Unit)
        job.join()

        assertEquals(ChatStatus.Ready, chat.status)
        assertEquals(listOf(resumed), chat.messages)
    }
}
