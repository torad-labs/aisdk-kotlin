package ai.torad.aisdk

import ai.torad.aisdk.providers.MockLanguageModelToolThenText
import ai.torad.aisdk.providers.MockLanguageModelTextOnly
import ai.torad.aisdk.providers.MockToolInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.serializer

/**
 * Best practice #10 — lifecycle events OBSERVE but do not modify behavior.
 * Observers collect [ToolLoopAgent.events] (a `Flow<AgentEvent>`) with an
 * exhaustive `when`; the loop never crashes when an observer is present, and
 * internal failures surface as [AgentEvent.Errored] rather than propagating.
 */
class LifecycleHooksTest {
    @Serializable
    private data class Empty(val unused: String = "")

    @Test
    fun `lifecycle Poko types keep value semantics`() {
        val step = StepResult(
            stepNumber = 1,
            text = "done",
            reasoning = "because",
            toolCalls = emptyList(),
            toolResults = emptyList(),
            toolApprovalRequests = emptyList(),
            finishReason = FinishReason.Stop,
            usage = Usage.of(promptTokens = 1, completionTokens = 2),
        )
        val equalStep = StepResult(
            stepNumber = 1,
            text = "done",
            reasoning = "because",
            toolCalls = emptyList(),
            toolResults = emptyList(),
            toolApprovalRequests = emptyList(),
            finishReason = FinishReason.Stop,
            usage = Usage.of(promptTokens = 1, completionTokens = 2),
        )
        val differentStep = StepResult(
            stepNumber = 1,
            text = "different",
            reasoning = "because",
            toolCalls = emptyList(),
            toolResults = emptyList(),
            toolApprovalRequests = emptyList(),
            finishReason = FinishReason.Stop,
            usage = Usage.of(promptTokens = 1, completionTokens = 2),
        )
        assertEquals(step, equalStep)
        assertEquals(step.hashCode(), equalStep.hashCode())
        assertNotEquals(step, differentStep)

        val toolFinished = AgentEvent.ToolCallFinished(
            toolCallId = "call_1",
            toolName = "lookup",
            outcome = AgentEvent.ToolCallFinished.Outcome.Success(JsonPrimitive("ok")),
            stepNumber = 1,
        )
        val equalToolFinished = AgentEvent.ToolCallFinished(
            toolCallId = "call_1",
            toolName = "lookup",
            outcome = AgentEvent.ToolCallFinished.Outcome.Success(JsonPrimitive("ok")),
            stepNumber = 1,
        )
        val differentToolFinished = AgentEvent.ToolCallFinished(
            toolCallId = "call_1",
            toolName = "lookup",
            outcome = AgentEvent.ToolCallFinished.Outcome.Failure("failed"),
            stepNumber = 1,
        )
        assertEquals(toolFinished, equalToolFinished)
        assertEquals(toolFinished.hashCode(), equalToolFinished.hashCode())
        assertNotEquals(toolFinished, differentToolFinished)

        val finished = AgentEvent.Finished<Unit, String>(
            output = "done",
            totalSteps = 1,
            usage = Usage.of(promptTokens = 1, completionTokens = 2),
            messages = listOf(AssistantMessage("done")),
        )
        val equalFinished = AgentEvent.Finished<Unit, String>(
            output = "done",
            totalSteps = 1,
            usage = Usage.of(promptTokens = 1, completionTokens = 2),
            messages = listOf(AssistantMessage("done")),
        )
        val differentFinished = AgentEvent.Finished<Unit, String>(
            output = "different",
            totalSteps = 1,
            usage = Usage.of(promptTokens = 1, completionTokens = 2),
            messages = listOf(AssistantMessage("done")),
        )
        assertEquals(finished, equalFinished)
        assertEquals(finished.hashCode(), equalFinished.hashCode())
        assertNotEquals(finished, differentFinished)
    }

    @Test
    fun `events fire in order Started StepFinished Finished`() = runTest {
        val seq = mutableListOf<String>()
        val agent = TestToolLoopAgent<Unit, String>(
            model = MockLanguageModelTextOnly("answer"),
            instructions = "x",
            tools = ToolSet(),
        )
        agent.collectAgentEvents(prompt = "go") { event ->
            when (event) {
                is AgentEvent.Started<*> -> seq.add("start")
                is AgentEvent.StepFinished -> seq.add("step:${event.stepNumber}")
                is AgentEvent.Finished<*, *> -> seq.add("finish")
                is AgentEvent.StepStarted,
                is AgentEvent.Chunk,
                is AgentEvent.ToolCallStarted,
                is AgentEvent.ToolCallFinished,
                is AgentEvent.Errored,
                is AgentEvent.Aborted,
                is AgentEvent.ModelCallStarted,
                is AgentEvent.ModelCallFinished,
                is AgentEvent.SpanEmitted,
                -> Unit
            }
        }
        assertEquals(listOf("start", "step:1", "finish"), seq)
    }

    @Test
    fun `an internal error surfaces as Errored without crashing the collector`() = runTest {
        // A prepareCall failure ends the run gracefully (StreamEvent.Error) and is
        // delivered to observers as AgentEvent.Errored(PrepareCall) — the loop never
        // throws into the collector.
        val sources = mutableListOf<AgentEvent.Errored.ErrorSource>()
        val agent = TestToolLoopAgent<Unit, String>(
            model = MockLanguageModelTextOnly("ok"),
            instructions = "x",
            tools = ToolSet(),
            prepareCall = { error("boom from prepareCall") },
        )
        agent.collectAgentEvents(prompt = "go") { event ->
            when (event) {
                is AgentEvent.Errored -> sources.add(event.source)
                is AgentEvent.Started<*>,
                is AgentEvent.StepStarted,
                is AgentEvent.Chunk,
                is AgentEvent.StepFinished,
                is AgentEvent.ToolCallStarted,
                is AgentEvent.ToolCallFinished,
                is AgentEvent.Aborted,
                is AgentEvent.Finished<*, *>,
                is AgentEvent.ModelCallStarted,
                is AgentEvent.ModelCallFinished,
                is AgentEvent.SpanEmitted,
                -> Unit
            }
        }
        assertTrue(
            sources.contains(AgentEvent.Errored.ErrorSource.PrepareCall),
            "PrepareCall error surfaced via the event stream",
        )
    }

    @Test
    fun `tool finish typed success outcome is the primary event surface`() {
        val output = JsonPrimitive("ok")
        val event = AgentEvent.ToolCallFinished(
            toolCallId = "call_1",
            toolName = "tool",
            outcome = AgentEvent.ToolCallFinished.Outcome.Success(output),
            stepNumber = 1,
        )

        val outcome = assertIs<AgentEvent.ToolCallFinished.Outcome.Success>(event.outcome)
        assertEquals(output, outcome.outputJson)
    }

    @Test
    fun `tool finish typed failure outcome is the primary event surface`() {
        val event = AgentEvent.ToolCallFinished(
            toolCallId = "call_1",
            toolName = "tool",
            outcome = AgentEvent.ToolCallFinished.Outcome.Failure("failed"),
            stepNumber = 1,
        )

        val outcome = assertIs<AgentEvent.ToolCallFinished.Outcome.Failure>(event.outcome)
        assertEquals("failed", outcome.errorMessage)
    }

    @Test
    fun `tool finish event observes typed outcome`() = runTest {
        var observed: AgentEvent.ToolCallFinished.Outcome? = null
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
        )

        agent.collectAgentEvents(prompt = "go") { event ->
            when (event) {
                is AgentEvent.ToolCallFinished -> observed = event.outcome
                is AgentEvent.Started<*>,
                is AgentEvent.StepStarted,
                is AgentEvent.Chunk,
                is AgentEvent.StepFinished,
                is AgentEvent.ToolCallStarted,
                is AgentEvent.Errored,
                is AgentEvent.Aborted,
                is AgentEvent.Finished<*, *>,
                is AgentEvent.ModelCallStarted,
                is AgentEvent.ModelCallFinished,
                is AgentEvent.SpanEmitted,
                -> Unit
            }
        }

        val outcome = assertIs<AgentEvent.ToolCallFinished.Outcome.Success>(observed)
        assertEquals(JsonPrimitive("pong"), outcome.outputJson)
    }
}
