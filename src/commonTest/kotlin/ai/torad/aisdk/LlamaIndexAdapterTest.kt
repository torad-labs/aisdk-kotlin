package ai.torad.aisdk

import ai.torad.aisdk.ui.TextUIPartState
import ai.torad.aisdk.ui.UIMessagePart
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

class LlamaIndexAdapterTest {

    @Test
    fun `toUIMessageStream trims leading whitespace and emits streaming then done messages`() = runTest {
        val messages = toUIMessageStream(
            flowOf(
                LlamaIndexEngineResponse("   hello"),
                LlamaIndexEngineResponse(" world"),
            ),
            assistantMessageId = "assistant-1",
        ).toList()

        assertEquals(3, messages.size)
        assertEquals("assistant-1", messages.first().id)
        val first = messages[0].parts.single() as UIMessagePart.Text
        val second = messages[1].parts.single() as UIMessagePart.Text
        val final = messages[2].parts.single() as UIMessagePart.Text
        assertEquals("hello", first.text)
        assertEquals(TextUIPartState.Streaming, first.state)
        assertEquals("hello world", second.text)
        assertEquals(TextUIPartState.Streaming, second.state)
        assertEquals("hello world", final.text)
        assertEquals(TextUIPartState.Done, final.state)
    }

    @Test
    fun `toUIMessageStream invokes stream callbacks`() = runTest {
        val calls = mutableListOf<String>()

        toUIMessageStream(
            flowOf(
                LlamaIndexEngineResponse(" A"),
                LlamaIndexEngineResponse("B"),
            ),
            callbacks = StreamCallbacks(
                onStart = { calls += "start" },
                onToken = { calls += "token:$it" },
                onText = { calls += "text:$it" },
                onFinal = { calls += "final:$it" },
            ),
        ).toList()

        assertEquals(
            listOf("start", "token:A", "text:A", "token:B", "text:B", "final:AB"),
            calls,
        )
    }
}
