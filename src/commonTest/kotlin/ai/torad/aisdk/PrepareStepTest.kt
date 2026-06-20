package ai.torad.aisdk

import ai.torad.aisdk.providers.MockLanguageModel
import ai.torad.aisdk.providers.mockLanguageModelToolThenText
import ai.torad.aisdk.providers.mockToolInput
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
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

    private class CapturingModel : LanguageModel {
        val observedToolNames = mutableListOf<List<String>>()
        override val modelId: String = "test/capturing"

        override suspend fun generate(params: LanguageModelCallParams): LanguageModelResult {
            observedToolNames += params.tools.map { it.name }
            return LanguageModelResult(
                text = "ok",
                finishReason = FinishReason.Stop,
                usage = Usage(),
            )
        }

        override fun stream(params: LanguageModelCallParams): Flow<StreamEvent> = flow {
            observedToolNames += params.tools.map { it.name }
            emit(StreamEvent.TextStart("t1"))
            emit(StreamEvent.TextDelta("t1", "ok"))
            emit(StreamEvent.TextEnd("t1"))
            emit(StreamEvent.StepFinish(1, FinishReason.Stop, Usage()))
        }
    }

    @Test
    fun `prepareStep_runs_before_every_step_and_can_gate_active_tools`() = runTest {
        val recordedStepNumbers = mutableListOf<Int>()
        val recordedActiveSubsets = mutableListOf<List<String>?>()

        val ping = Tool<Empty, String, Unit>(
            name = "ping", description = "",
            inputSerializer = serializer(), outputSerializer = serializer(),
        ) { _ -> "pong" }

        val agent = TestToolLoopAgent<Unit, String>(
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
        agent.generate("go").first()
        assertTrue(recordedStepNumbers.size >= 2, "prepareStep ran at least twice — once per step")
        assertEquals(listOf(1, 2), recordedStepNumbers.take(2))
    }

    @Test
    fun `agent activeTools limits tools advertised to the model`() = runTest {
        val capture = CapturingModel()

        val agent = TestToolLoopAgent<Unit, String>(
            model = capture,
            instructions = "x",
            tools = toolSetOf(testTool("ping"), testTool("pong")),
            activeTools = listOf("ping"),
        )

        agent.generate("go").first()

        assertEquals(listOf(listOf("ping")), capture.observedToolNames)
    }

    @Test
    fun `prepareCall activeTools overrides agent activeTools`() = runTest {
        val capture = CapturingModel()

        val agent = TestToolLoopAgent<Unit, String>(
            model = capture,
            instructions = "x",
            tools = toolSetOf(testTool("ping"), testTool("pong")),
            activeTools = listOf("ping"),
            prepareCall = { AgentSettings(activeTools = listOf("pong")) },
        )

        agent.generate("go").first()

        assertEquals(listOf(listOf("pong")), capture.observedToolNames)
    }

    @Test
    fun `prepareStep activeTools overrides prepareCall activeTools`() = runTest {
        val capture = CapturingModel()

        val agent = TestToolLoopAgent<Unit, String>(
            model = capture,
            instructions = "x",
            tools = toolSetOf(testTool("ping"), testTool("pong")),
            activeTools = listOf("ping"),
            prepareCall = { AgentSettings(activeTools = listOf("pong")) },
            prepareStep = { StepSettings(activeTools = listOf("ping")) },
        )

        agent.generate("go").first()

        assertEquals(listOf(listOf("ping")), capture.observedToolNames)
    }

    private fun testTool(name: String): Tool<Empty, String, Unit> = Tool<Empty, String, Unit>(
        name = name,
        description = "",
        inputSerializer = serializer(),
        outputSerializer = serializer(),
    ) { _ -> "ok" }
}
