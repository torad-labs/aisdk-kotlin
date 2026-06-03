package ai.torad.aisdk

import ai.torad.aisdk.providers.MockLanguageModel
import ai.torad.aisdk.providers.mockLanguageModelToolThenText
import ai.torad.aisdk.providers.mockToolInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer

/**
 * Per invariant I-5, per-step settings flow through `prepareStep` — the
 * agent does NOT pass per-step config at the call site.
 */
class PrepareStepTest {

    @Serializable data class Empty(val unused: String = "")

    @Test
    fun `prepareStep_runs_before_every_step_and_can_gate_active_tools`() = runTest {
        val recordedStepNumbers = mutableListOf<Int>()
        val recordedActiveSubsets = mutableListOf<List<String>?>()

        val ping = tool<Empty, String, Unit>(
            name = "ping", description = "",
            inputSerializer = serializer(), outputSerializer = serializer(),
        ) { _ -> "pong" }

        val agent = ToolLoopAgent<Unit, String>(
            model = mockLanguageModelToolThenText(
                toolName = "ping",
                toolInput = mockToolInput("unused" to ""),
                finalText = "ok",
            ),
            instructions = "x",
            tools = toolSetOf(ping),
            prepareStep = {
                recordedStepNumbers.add(stepNumber)
                StepSettings(activeTools = listOf("ping"))
            },
        )
        agent.generate("go")
        assertTrue(recordedStepNumbers.size >= 2, "prepareStep ran at least twice — once per step")
        assertEquals(listOf(1, 2), recordedStepNumbers.take(2))
    }
}
