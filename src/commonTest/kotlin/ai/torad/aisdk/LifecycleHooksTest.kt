package ai.torad.aisdk

import ai.torad.aisdk.providers.MockLanguageModel
import ai.torad.aisdk.providers.mockLanguageModelTextOnly
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Best practice #10 — lifecycle hooks observe but do NOT modify behavior.
 * Hook failures don't crash the loop; they're surfaced via `onError`.
 */
class LifecycleHooksTest {

    @Test
    fun `hooks_fire_in_order_onStart_onStepFinish_onFinish`() = runTest {
        val seq = mutableListOf<String>()
        val agent = ToolLoopAgent<Unit, String>(
            model = mockLanguageModelTextOnly("answer"),
            instructions = "x",
            tools = toolSetOf(),
            onStart = { seq.add("start") },
            onStepFinish = { seq.add("step:$stepNumber") },
            onFinish = { seq.add("finish") },
        )
        agent.generate("go")
        assertEquals(listOf("start", "step:1", "finish"), seq)
    }

    @Test
    fun `hook_failure_does_not_crash_the_loop`() = runTest {
        val errorsObserved = mutableListOf<OnErrorEvent.ErrorSource>()
        val agent = ToolLoopAgent<Unit, String>(
            model = mockLanguageModelTextOnly("ok"),
            instructions = "x",
            tools = toolSetOf(),
            onStart = { error("boom from hook") },
            onError = { errorsObserved.add(source) },
        )
        val result = agent.generate("go")
        assertEquals("ok", result.text, "loop completed despite hook failure")
        assertTrue(errorsObserved.contains(OnErrorEvent.ErrorSource.Hook), "Hook source surfaced via onError")
    }
}
