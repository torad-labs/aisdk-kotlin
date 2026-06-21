package ai.torad.aisdk

import ai.torad.aisdk.providers.MockLanguageModel
import ai.torad.aisdk.providers.MockLanguageModelToolThenText
import ai.torad.aisdk.providers.MockLanguageModelTextOnly
import ai.torad.aisdk.providers.MockToolInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.serializer

/**
 * Best practice #10 — lifecycle hooks observe but do NOT modify behavior.
 * Hook failures don't crash the loop; they're surfaced via `onError`.
 */
class LifecycleHooksTest {
    @Serializable
    private data class Empty(val unused: String = "")

    @Test
    fun `hooks_fire_in_order_onStart_onStepFinish_onFinish`() = runTest {
        val seq = mutableListOf<String>()
        val agent = TestToolLoopAgent<Unit, String>(
            model = MockLanguageModelTextOnly("answer"),
            instructions = "x",
            tools = ToolSet(),
            onStart = { seq.add("start") },
            onStepFinish = { seq.add("step:$stepNumber") },
            onFinish = { seq.add("finish") },
        )
        agent.generate("go").first()
        assertEquals(listOf("start", "step:1", "finish"), seq)
    }

    @Test
    fun `hook_failure_does_not_crash_the_loop`() = runTest {
        val errorsObserved = mutableListOf<OnErrorEvent.ErrorSource>()
        val agent = TestToolLoopAgent<Unit, String>(
            model = MockLanguageModelTextOnly("ok"),
            instructions = "x",
            tools = ToolSet(),
            onStart = { error("boom from hook") },
            onError = { errorsObserved.add(source) },
        )
        val result = agent.generate("go").first()
        assertEquals("ok", result.text, "loop completed despite hook failure")
        assertTrue(errorsObserved.contains(OnErrorEvent.ErrorSource.Hook), "Hook source surfaced via onError")
    }

    @Test
    fun `tool finish typed success outcome is the primary event surface`() {
        val output = JsonPrimitive("ok")
        val event = OnToolCallFinishEvent(
            toolCallId = "call_1",
            toolName = "tool",
            outcome = OnToolCallFinishEvent.Outcome.Success(output),
            stepNumber = 1,
        )

        val outcome = assertIs<OnToolCallFinishEvent.Outcome.Success>(event.outcome)
        assertEquals(output, outcome.outputJson)
    }

    @Test
    fun `tool finish typed failure outcome is the primary event surface`() {
        val event = OnToolCallFinishEvent(
            toolCallId = "call_1",
            toolName = "tool",
            outcome = OnToolCallFinishEvent.Outcome.Failure("failed"),
            stepNumber = 1,
        )

        val outcome = assertIs<OnToolCallFinishEvent.Outcome.Failure>(event.outcome)
        assertEquals("failed", outcome.errorMessage)
    }

    @Test
    fun `tool finish hook observes typed outcome`() = runTest {
        var observed: OnToolCallFinishEvent.Outcome? = null
        val pingTool = Tool<Empty, String, Unit>(
            name = "ping",
            description = "respond with pong",
            inputSerializer = serializer(),
            outputSerializer = serializer(),
        ) { _ -> "pong" }
        val agent = TestToolLoopAgent<Unit, String>(
            model = MockLanguageModelToolThenText(
                toolName = "ping",
                toolInput = MockToolInput("unused" to ""),
                finalText = "done",
            ),
            instructions = "x",
            tools = ToolSet(pingTool),
            experimental_onToolCallFinish = {
                observed = outcome
            },
        )

        agent.generate("go").first()

        val outcome = assertIs<OnToolCallFinishEvent.Outcome.Success>(observed)
        assertEquals(JsonPrimitive("pong"), outcome.outputJson)
    }
}
