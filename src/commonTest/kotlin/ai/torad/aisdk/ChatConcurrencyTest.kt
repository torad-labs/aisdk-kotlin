package ai.torad.aisdk

import ai.torad.aisdk.ui.Chat
import ai.torad.aisdk.ui.ChatStatus
import ai.torad.aisdk.ui.DirectChatTransport
import ai.torad.aisdk.ui.UIMessage
import ai.torad.aisdk.ui.UIMessagePart
import ai.torad.aisdk.ui.UIMessageRole
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class ChatConcurrencyTest {

    private fun user(id: String, text: String): UIMessage =
        UIMessage(id = id, role = UIMessageRole.User, parts = listOf(UIMessagePart.Text(text)))

    private fun assistant(id: String, text: String): UIMessage =
        UIMessage(id = id, role = UIMessageRole.Assistant, parts = listOf(UIMessagePart.Text(text)))

    @Test
    fun `superseded send does not clobber the newer send's terminal state`() = runTest {
        val firstGate = CompletableDeferred<Unit>()
        var calls = 0
        val chat = Chat(
            transport = DirectChatTransport { request ->
                val n = ++calls
                flow {
                    // The first (soon-superseded) turn parks until released; the
                    // second returns immediately.
                    if (n == 1) firstGate.await()
                    emit(assistant("a$n", "reply$n"))
                }
            },
        )

        // Start the first turn; let its collector reach firstGate.await().
        val firstJob = launch { chat.sendMessage(user("u1", "first")).collect {} }
        runCurrent()
        assertEquals(ChatStatus.Submitted, chat.status)

        // A second send supersedes the first (claims currentOp). It returns
        // immediately and settles to Ready with reply2.
        chat.sendMessage(user("u2", "second")).collect {}
        assertEquals(ChatStatus.Ready, chat.status)

        // Release the first turn. Its trailing Streaming/Ready writes target a
        // superseded op and must be ignored — status stays Ready, and the stale
        // "reply1" assistant message must NOT be appended to the log.
        firstGate.complete(Unit)
        firstJob.join()
        runCurrent()

        assertEquals(ChatStatus.Ready, chat.status)
        val texts = chat.messages.flatMap { it.parts }.filterIsInstance<UIMessagePart.Text>().map { it.text }
        assertEquals(listOf("first", "second", "reply2"), texts)
    }

    @Test
    fun `stop supersedes an in-flight send so its terminal write is ignored`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val chat = Chat(
            transport = DirectChatTransport {
                flow {
                    gate.await()
                    emit(assistant("a1", "late"))
                }
            },
        )

        val job = launch { chat.sendMessage(user("u1", "ping")).collect {} }
        runCurrent()
        assertEquals(ChatStatus.Submitted, chat.status)

        // User hits stop while the turn is parked.
        chat.stop()
        assertEquals(ChatStatus.Ready, chat.status)

        // The transport finally emits and completes. Because stop() cleared the
        // active op, the trailing Ready/Streaming writes are ignored — status
        // stays Ready and the late message is not appended.
        gate.complete(Unit)
        job.join()
        runCurrent()

        assertEquals(ChatStatus.Ready, chat.status)
        val texts = chat.messages.flatMap { it.parts }.filterIsInstance<UIMessagePart.Text>().map { it.text }
        assertEquals(listOf("ping"), texts)
    }
}
