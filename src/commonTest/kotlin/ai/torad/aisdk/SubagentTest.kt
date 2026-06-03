package ai.torad.aisdk

import ai.torad.aisdk.providers.MockLanguageModel
import ai.torad.aisdk.providers.mockLanguageModelToolThenText
import ai.torad.aisdk.providers.mockToolInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer

/**
 * Invariant I-10 — a tool that wraps a subagent must propagate the
 * parent's [AbortSignal]. Forgetting this means cancellation doesn't
 * flow into the subagent's generation.
 */
class SubagentTest {

    @Serializable data class SubInput(val text: String)

    @Test
    fun `subagent_tool_receives_parent_abortSignal`() = runTest {
        var observedSignal: AbortSignal? = null

        val subagentTool = tool<SubInput, String, Unit>(
            name = "subagent",
            description = "delegates to a subagent",
            inputSerializer = serializer(),
            outputSerializer = serializer(),
        ) { input ->
            // I-10: capture the signal — caller propagates it via context.
            observedSignal = abortSignal
            "subagent answered for ${input.text}"
        }

        val controller = AbortController()
        val agent = ToolLoopAgent<Unit, String>(
            model = mockLanguageModelToolThenText(
                toolName = "subagent",
                toolInput = mockToolInput("text" to "hello"),
                finalText = "done",
            ),
            instructions = "x",
            tools = toolSetOf(subagentTool),
        )

        agent.generate("go", abortSignal = controller.signal)

        assertTrue(observedSignal != null, "subagent received an abort signal")
        assertEquals(controller.signal, observedSignal, "exact same signal object — propagation, not copy")
    }

    @Test
    fun `aborted_signal_throws_on_check`() = runTest {
        val controller = AbortController()
        controller.abort()
        try {
            controller.signal.throwIfAborted()
            fail("expected AbortError")
        } catch (_: AbortError) {
            // expected
        }
    }
}
