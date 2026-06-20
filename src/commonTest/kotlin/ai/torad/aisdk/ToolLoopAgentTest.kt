package ai.torad.aisdk

import ai.torad.aisdk.providers.MockLanguageModel
import ai.torad.aisdk.providers.mockLanguageModelTextOnly
import ai.torad.aisdk.providers.mockLanguageModelToolThenText
import ai.torad.aisdk.providers.mockToolInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer

/**
 * Invariants exercised:
 *   I-1  agents are ToolLoopAgent (this test uses it)
 *   I-3  generate / stream only
 *   I-7  loop is managed by SDK (no manual loops in test code)
 */
class ToolLoopAgentTest {

    @Serializable data class Empty(val unused: String = "")

    @Test
    fun `text_only_round_trip`() = runTest {
        val agent = TestToolLoopAgent<Unit, String>(
            model = mockLanguageModelTextOnly("hello, friend"),
            instructions = "be friendly",
            tools = toolSetOf(),
        )
        val result = agent.generate(prompt = "hi").first()
        assertEquals("hello, friend", result.text)
        assertEquals(FinishReason.Stop, result.finishReason)
    }

    @Test
    fun `tool_call_then_final_text`() = runTest {
        val pingTool = Tool<Empty, String, Unit>(
            name = "ping",
            description = "respond with pong",
            inputSerializer = serializer(),
            outputSerializer = serializer(),
        ) { _ -> "pong" }
        val agent = TestToolLoopAgent<Unit, String>(
            model = mockLanguageModelToolThenText(
                toolName = "ping",
                toolInput = mockToolInput("unused" to ""),
                finalText = "got pong, here's the answer",
            ),
            instructions = "use the ping tool then answer",
            tools = toolSetOf(pingTool),
        )
        val events = mutableListOf<StreamEvent>()
        agent.stream(prompt = "trigger").collect { events.add(it) }
        assertTrue(
            events.any { it is StreamEvent.ToolCall && it.toolName == "ping" },
            "tool call",
        )
        assertTrue(
            events.any { it is StreamEvent.ToolResult && it.toolName == "ping" },
            "tool result",
        )
        assertTrue(
            events.any { it is StreamEvent.TextDelta && it.text == "got pong, here's the answer" },
            "final text",
        )
    }
}
