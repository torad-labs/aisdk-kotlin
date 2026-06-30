@file:OptIn(LowLevelLanguageModelApi::class)

package ai.torad.aisdk

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
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
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class ParallelToolExecutionTest {
    @Serializable
    private data class Empty(val unused: String = "")

    // A model whose first step emits two tool calls, then (after the tools run) returns text.
    private class TwoToolThenText : LanguageModel {
        override val modelId = "m"
        var calls = 0
        override suspend fun generate(params: LanguageModelCallParams) =
            if (calls++ == 0) {
                val first = ContentPart.ToolCall("c_a", "toolA", JsonObject(emptyMap()))
                val second = ContentPart.ToolCall("c_b", "toolB", JsonObject(emptyMap()))
                LanguageModelResult(
                    text = "",
                    toolCalls = listOf(first, second),
                    finishReason = FinishReason.ToolCalls,
                    usage = Usage(),
                )
            } else {
                LanguageModelResult(text = "done", finishReason = FinishReason.Stop, usage = Usage())
            }
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

    // A model whose first step emits a SINGLE tool call, then (after the tool runs) returns text.
    private class OneToolThenText : LanguageModel {
        override val modelId = "m"
        private var calls = 0
        override suspend fun generate(params: LanguageModelCallParams) =
            if (calls++ == 0) {
                val call = ContentPart.ToolCall("c_a", "toolA", JsonObject(emptyMap()))
                LanguageModelResult(
                    text = "",
                    toolCalls = listOf(call),
                    finishReason = FinishReason.ToolCalls,
                    usage = Usage(),
                )
            } else {
                LanguageModelResult(text = "done", finishReason = FinishReason.Stop, usage = Usage())
            }
        override fun stream(params: LanguageModelCallParams): Flow<StreamEvent> = flow {
            if (calls++ == 0) {
                emit(StreamEvent.ToolCall("c_a", "toolA", JsonObject(emptyMap())))
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

    private class ManyToolsThenText(private val toolCallCount: Int) : LanguageModel {
        override val modelId = "m"
        private var calls = 0
        override suspend fun generate(params: LanguageModelCallParams) =
            if (calls++ == 0) {
                val calls = List(toolCallCount) { index ->
                    ContentPart.ToolCall("c_$index", "tool", JsonObject(emptyMap()))
                }
                LanguageModelResult(
                    text = "",
                    toolCalls = calls,
                    finishReason = FinishReason.ToolCalls,
                    usage = Usage(),
                )
            } else {
                LanguageModelResult(text = "done", finishReason = FinishReason.Stop, usage = Usage())
            }
        override fun stream(params: LanguageModelCallParams): Flow<StreamEvent> = flow {
            if (calls++ == 0) {
                repeat(toolCallCount) { index ->
                    emit(StreamEvent.ToolCall("c_$index", "tool", JsonObject(emptyMap())))
                }
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
    fun `a tool that aborts mid parallel step emits Abort and does not hang`() = runTest {
        // Regression: a tool throwing AbortError (the cooperative stop signal — what
        // ctx.abortSignal.throwIfAborted() raises) used to black-hole the parallel-tool consumer:
        // the child died via CancellationException without sending its Completed signal, so the
        // `while (completed < n) { receive() }` consumer suspended forever and the whole agent hung.
        val controller = AbortController()
        val abortingTool = Tool<Empty, String, Unit>(
            name = "toolA",
            description = "aborts mid-flight",
            inputSerializer = serializer(),
            outputSerializer = serializer(),
        ) { _ ->
            // Mirror a stop pressed while the tool runs: the host's signal aborts, and an
            // abort-aware executor surfaces it via throwIfAborted() (AbortSignal.kt:95).
            controller.abort()
            abortSignal.throwIfAborted()
            "unreachable"
        }
        val agent = TestToolLoopAgent<Unit, String>(
            model = OneToolThenText(),
            instructions = "x",
            tools = ToolSet(abortingTool),
        )

        // withTimeout bounds the deadlock: pre-fix this hangs and the (virtual-time) timeout fires
        // the test; post-fix the abort surfaces as a terminal StreamEvent.Abort and the flow ends.
        val events = withTimeout(10_000) {
            agent.stream(prompt = "go", abortSignal = controller.signal).toList()
        }

        assertEquals(1, events.count { it == StreamEvent.Abort }, "aborted parallel step emits one terminal Abort")
    }

    @Test
    fun `a step's two tools run concurrently and results apply in call order`() = runTest {
        // Gate: each tool reports it has started; the gate opens only when BOTH have started.
        // Serial execution would deadlock (toolA awaits the gate, but toolB never starts), so
        // completing at all proves the two tools ran concurrently.
        var started = 0
        val bothStarted = CompletableDeferred<Unit>()
        fun gatedTool(name: String) = Tool<Empty, String, Unit>(
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
            tools = ToolSet(gatedTool("toolA"), gatedTool("toolB")),
        )
        val result = agent.generate(prompt = "go").first()

        // Both tools completed (proves concurrency), and the step records both results in
        // the deterministic call order toolA, toolB.
        val step = result.steps.first()
        assertEquals(listOf("c_a", "c_b"), step.toolResults.map { it.toolCallId }, "results applied in call order")
        assertEquals("done", result.text)
    }

    @Test
    fun `maxParallelToolCalls = 1 keeps results in call order`() = runTest {
        fun echoTool(name: String) = Tool<Empty, String, Unit>(
            name = name,
            description = name,
            inputSerializer = serializer(),
            outputSerializer = serializer(),
        ) { _ -> "$name-out" }

        val agent = TestToolLoopAgent<Unit, String>(
            model = TwoToolThenText(),
            instructions = "x",
            tools = ToolSet(echoTool("toolA"), echoTool("toolB")),
            maxParallelToolCalls = 1,
        )
        val result = agent.generate(prompt = "go").first()
        assertEquals(listOf("c_a", "c_b"), result.steps.first().toolResults.map { it.toolCallId })
    }

    @Test
    fun `streaming tool preliminary result emits before slower sibling finishes`() = runTest {
        val slowStarted = CompletableDeferred<Unit>()
        val slowRelease = CompletableDeferred<Unit>()
        val preliminarySeen = CompletableDeferred<StreamEvent.ToolResult>()
        val fastTool = StreamingTool<Empty, String, Unit>(
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
        val slowTool = Tool<Empty, String, Unit>(
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
            tools = ToolSet(fastTool, slowTool),
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
        fun gatedTool(name: String) = Tool<Empty, String, Unit>(
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
            tools = ToolSet(gatedTool("toolA"), gatedTool("toolB")),
            maxParallelToolCalls = 1,
        )
        val job = launch {
            agent.collectAgentEvents(prompt = "go") { event ->
                when (event) {
                    is AgentEvent.ToolCallStarted -> startOrder += event.toolName
                    is AgentEvent.Started<*>,
                    is AgentEvent.StepStarted,
                    is AgentEvent.Chunk,
                    is AgentEvent.StepFinished,
                    is AgentEvent.ToolCallFinished,
                    is AgentEvent.Errored,
                    is AgentEvent.Aborted,
                    is AgentEvent.Finished<*, *>,
                    is AgentEvent.ModelCallStarted,
                    is AgentEvent.ModelCallFinished,
                    is AgentEvent.SpanEmitted,
                    -> Unit
                }
            }
        }

        firstStarted.await()
        runCurrent()
        assertEquals(listOf("toolA"), startOrder)

        firstRelease.complete(Unit)
        job.join()

        assertEquals(listOf("toolA", "toolB"), startOrder)
    }

    @Test
    fun `many tool calls only start bounded workers before release`() = runTest {
        val maxParallel = 4
        val toolCallCount = 200
        val startedIds = mutableListOf<String>()
        val firstWaveStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val blockingTool = Tool<Empty, String, Unit>(
            name = "tool",
            description = "blocked tool",
            inputSerializer = serializer(),
            outputSerializer = serializer(),
        ) { _ ->
            startedIds += toolCallId
            if (startedIds.size == maxParallel) firstWaveStarted.complete(Unit)
            release.await()
            "ok-$toolCallId"
        }
        val agent = TestToolLoopAgent<Unit, String>(
            model = ManyToolsThenText(toolCallCount),
            instructions = "x",
            tools = ToolSet(blockingTool),
            toolExecutionPolicy = ToolExecutionPolicy(
                maxParallelToolCalls = maxParallel,
                maxToolCallsPerStep = toolCallCount,
            ),
        )

        val job = launch { agent.generate(prompt = "go").first() }
        firstWaveStarted.await()
        runCurrent()

        assertEquals(
            maxParallel,
            startedIds.size,
            "scheduler must not start more tool executions than maxParallelToolCalls while first wave is blocked",
        )

        release.complete(Unit)
        job.join()
        assertEquals(toolCallCount, startedIds.size)
    }

    @Test
    fun `maxToolCallsPerStep emits typed error before tool execution`() = runTest {
        var executed = 0
        val tool = Tool<Empty, String, Unit>(
            name = "tool",
            description = "should not run",
            inputSerializer = serializer(),
            outputSerializer = serializer(),
        ) { _ ->
            executed += 1
            "unexpected"
        }
        val agent = TestToolLoopAgent<Unit, String>(
            model = ManyToolsThenText(toolCallCount = 3),
            instructions = "x",
            tools = ToolSet(tool),
            toolExecutionPolicy = ToolExecutionPolicy(
                maxParallelToolCalls = 2,
                maxToolCallsPerStep = 2,
            ),
        )

        val events = agent.stream(prompt = "go").toList()
        val error = events.filterIsInstance<StreamEvent.Error>().single()

        assertTrue(error.cause is AgentError.MaxToolCallsPerStepExceeded)
        assertEquals(0, executed, "over-limit step must fail before any tool executor starts")
    }

    @Test
    fun `toolExecutionTimeout emits typed tool failure and loop continues`() = runTest {
        val slowTool = Tool<Empty, String, Unit>(
            name = "tool",
            description = "slow tool",
            inputSerializer = serializer(),
            outputSerializer = serializer(),
        ) { _ ->
            delay(1_000)
            "late"
        }
        val agent = TestToolLoopAgent<Unit, String>(
            model = ManyToolsThenText(toolCallCount = 1),
            instructions = "x",
            tools = ToolSet(slowTool),
            toolExecutionPolicy = ToolExecutionPolicy(
                maxParallelToolCalls = 1,
                maxToolCallsPerStep = 1,
                toolExecutionTimeout = 10.milliseconds,
            ),
        )

        val events = agent.stream(prompt = "go").toList()
        val toolError = events.filterIsInstance<StreamEvent.ToolError>().single()

        assertTrue(toolError.error is AgentError.ToolExecutionTimedOut)
        assertTrue(
            events.any { it is StreamEvent.TextDelta && it.text == "done" },
            "a timed-out tool is reported as a tool failure and the model gets a retry step",
        )
    }
}
