package ai.torad.aisdk

import ai.torad.aisdk.providers.MockLanguageModel
import ai.torad.aisdk.providers.mockLanguageModelTextOnly
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.runTest

/** Invariant I-5 — `prepareCall` runs once per invocation, before the loop. */
class PrepareCallTest {

    @Test
    fun `prepareCall_runs_once_and_overrides_instructions`() = runTest {
        var callCount = 0
        val agent = ToolLoopAgent<String, String>(
            model = mockLanguageModelTextOnly("done"),
            instructions = "default instructions",
            tools = toolSetOf(),
            prepareCall = {
                callCount += 1
                AgentSettings(instructions = "overridden for ${options ?: "no-context"}")
            },
        )
        agent.generate("hello", options = "marcos")
        assertEquals(1, callCount, "prepareCall ran exactly once")
    }

    @Test
    fun `prepareCall_can_provide_typed_context`() = runTest {
        var observedContext: String? = null
        val agent = ToolLoopAgent<String, String>(
            model = mockLanguageModelTextOnly("done"),
            instructions = "x",
            tools = toolSetOf(),
            prepareCall = {
                observedContext = options
                AgentSettings()
            },
        )
        agent.generate("hi", options = "example-context")
        assertNotNull(observedContext)
        assertEquals("example-context", observedContext)
    }
}
