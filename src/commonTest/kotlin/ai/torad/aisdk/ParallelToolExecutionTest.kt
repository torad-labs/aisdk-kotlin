package ai.torad.aisdk

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@OptIn(ExperimentalCoroutinesApi::class)
class ParallelToolExecutionTest {
    @Serializable
    private data class Empty(val unused: String = "")

    // A model whose first step emits two tool calls, then (after the tools run) returns text.
    private class TwoToolThenText : LanguageModel {
        override val modelId = "m"
        var calls = 0
        override suspend fun generate(params: LanguageModelCallParams) =
            LanguageModelResult(text = "done", finishReason = FinishReason.Stop, usage = Usage())
        override fun stream(params: LanguageModelCallParams): Flow<StreamEvent> = flow {
            if (calls++ == 0) {
                emit(StreamEvent.ToolCall("c_a", "toolA", JsonObject(emptyMap())))
                emit(StreamEvent.ToolCall("c_b", "toolB", JsonObject(emptyMap())))
                emit(StreamEvent.StepFinish(1, FinishReason.ToolCalls, Usage()))
                emit(StreamEvent.Finish(1, FinishReason.ToolCalls, Usage()))
            } else {
                emit(StreamEvent.TextStart("t"))
                emit(StreamEvent.TextDelta("t", "done"))
                emit(StreamEvent.TextEnd("t"))
                emit(StreamEvent.Finish(2, FinishReason.Stop, Usage()))
            }
        }
    }

    @Test
    fun `a step's two tools run concurrently and results apply in call order`() = runTest {
        // Gate: each tool reports it has started; the gate opens only when BOTH have started.
        // Serial execution would deadlock (toolA awaits the gate, but toolB never starts), so
        // completing at all proves the two tools ran concurrently.
        var started = 0
        val bothStarted = CompletableDeferred<Unit>()
        fun gatedTool(name: String) = tool<Empty, String, Unit>(
            name = name,
            description = name,
            inputSerializer = serializer(),
            outputSerializer = serializer(),
        ) { _ ->
            if (++started == 2) bothStarted.complete(Unit)
            bothStarted.await()
            "$name-done"
        }

        val agent = TestToolLoopAgent<Unit, String>(
            model = TwoToolThenText(),
            instructions = "x",
            tools = toolSetOf(gatedTool("toolA"), gatedTool("toolB")),
        )
        val result = agent.generate(prompt = "go")

        // Both tools completed (proves concurrency), and the step records both results in
        // the deterministic call order toolA, toolB.
        val step = result.steps.first()
        assertEquals(listOf("c_a", "c_b"), step.toolResults.map { it.toolCallId }, "results applied in call order")
        assertEquals("done", result.text)
    }

    @Test
    fun `maxParallelToolCalls = 1 keeps results in call order`() = runTest {
        fun echoTool(name: String) = tool<Empty, String, Unit>(
            name = name,
            description = name,
            inputSerializer = serializer(),
            outputSerializer = serializer(),
        ) { _ -> "$name-out" }

        val agent = TestToolLoopAgent<Unit, String>(
            model = TwoToolThenText(),
            instructions = "x",
            tools = toolSetOf(echoTool("toolA"), echoTool("toolB")),
            maxParallelToolCalls = 1,
        )
        val result = agent.generate(prompt = "go")
        assertEquals(listOf("c_a", "c_b"), result.steps.first().toolResults.map { it.toolCallId })
    }

    @Test
    fun `streaming tool preliminary result emits before slower sibling finishes`() = runTest {
        val slowStarted = CompletableDeferred<Unit>()
        val slowRelease = CompletableDeferred<Unit>()
        val preliminarySeen = CompletableDeferred<StreamEvent.ToolResult>()
        val fastTool = streamingTool<Empty, String, Unit>(
            name = "toolA",
            description = "fast streaming tool",
            inputSerializer = serializer(),
            outputSerializer = serializer(),
        ) { _ ->
            flow {
                emit("fast-preliminary")
                emit("fast-final")
            }
        }
        val slowTool = tool<Empty, String, Unit>(
            name = "toolB",
            description = "blocked sibling",
            inputSerializer = serializer(),
            outputSerializer = serializer(),
        ) { _ ->
            slowStarted.complete(Unit)
            slowRelease.await()
            "slow-final"
        }

        val agent = TestToolLoopAgent<Unit, String>(
            model = TwoToolThenText(),
            instructions = "x",
            tools = toolSetOf(fastTool, slowTool),
        )
        val job = launch {
            agent.stream(prompt = "go").collect { event ->
                if (
                    event is StreamEvent.ToolResult &&
                    event.toolCallId == "c_a" &&
                    event.preliminary
                ) {
                    preliminarySeen.complete(event)
                }
            }
        }

        slowStarted.await()
        val preliminary = withTimeout(1_000) { preliminarySeen.await() }

        assertEquals("c_a", preliminary.toolCallId)
        assertEquals(true, preliminary.preliminary)
        assertEquals(JsonPrimitive("fast-preliminary"), preliminary.outputJson)
        assertFalse(slowRelease.isCompleted)

        slowRelease.complete(Unit)
        job.join()
    }

    @Test
    fun `maxParallelToolCalls one gates start hooks behind first tool completion`() = runTest {
        val firstStarted = CompletableDeferred<Unit>()
        val firstRelease = CompletableDeferred<Unit>()
        val startOrder = mutableListOf<String>()
        fun gatedTool(name: String) = tool<Empty, String, Unit>(
            name = name,
            description = name,
            inputSerializer = serializer(),
            outputSerializer = serializer(),
        ) { _ ->
            if (name == "toolA") {
                firstStarted.complete(Unit)
                firstRelease.await()
            }
            "$name-done"
        }

        val agent = TestToolLoopAgent<Unit, String>(
            model = TwoToolThenText(),
            instructions = "x",
            tools = toolSetOf(gatedTool("toolA"), gatedTool("toolB")),
            maxParallelToolCalls = 1,
            experimental_onToolCallStart = {
                startOrder += toolName
            },
        )
        val job = launch {
            agent.stream(prompt = "go").collect { }
        }

        firstStarted.await()
        runCurrent()
        assertEquals(listOf("toolA"), startOrder)

        firstRelease.complete(Unit)
        job.join()

        assertEquals(listOf("toolA", "toolB"), startOrder)
    }
}
