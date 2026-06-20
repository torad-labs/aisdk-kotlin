package ai.torad.aisdk

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guards the engine-surface supersession invariant: when a new submit cancels and
 * replaces an in-flight engine job, the superseded job must not write engineState
 * after the new job has taken over. See [ToolLoopAgent.updateEngineStateIfCurrent].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ToolLoopAgentSupersessionTest {
    @Test
    fun `rapid resubmission converges to the latest engine submission`() = runTest {
        val firstCallGate = CompletableDeferred<Unit>()
        // The engine drives the model through stream(). The first (soon-superseded)
        // call parks at the gate until released; the second returns immediately.
        val model = object : LanguageModel {
            override val modelId: String = "test/gated"
            var calls = 0

            override suspend fun generate(params: LanguageModelCallParams): LanguageModelResult =
                LanguageModelResult(
                    text = "",
                    finishReason = FinishReason.Stop,
                    usage = Usage.of(promptTokens = 1, completionTokens = 1),
                )

            override fun stream(params: LanguageModelCallParams): Flow<StreamEvent> = flow {
                val n = ++calls
                if (n == 1) firstCallGate.await()
                emit(StreamEvent.TextStart("t1"))
                emit(StreamEvent.TextDelta("t1", "call$n"))
                emit(StreamEvent.TextEnd("t1"))
                emit(StreamEvent.Finish(1, FinishReason.Stop, Usage.of(promptTokens = 1, completionTokens = 1)))
            }
        }
        // Run engine jobs on the test scheduler so submit/cancel ordering is deterministic.
        val agent = TestToolLoopAgent<Unit, String>(
            model = model,
            instructions = "Be brief.",
            tools = toolSetOf<Unit>(),
            engineContext = StandardTestDispatcher(testScheduler),
        )

        agent.dispatchEngineAction(ToolLoopAgentAction.UserSubmitPrompt("first", context = Unit))
        runCurrent() // let the first job reach firstCallGate.await()
        agent.dispatchEngineAction(ToolLoopAgentAction.UserSubmitPrompt("second", context = Unit))
        firstCallGate.complete(Unit) // first (cancelled) job resumes into a CancellationException
        advanceUntilIdle()

        // The newer submit cancels the first and settles on its own result — never stuck
        // streaming, never the stale "call1". The updateEngineStateIfCurrent guard
        // additionally protects this on real multi-threaded dispatchers, where a cancelled
        // job's terminal write can land after the new job settles; that interleaving is NOT
        // reproducible on the single-threaded test scheduler, so this asserts the
        // deterministic convergence behavior — the guard's race coverage is the linuxX64 CI
        // runtime leg plus review.
        val texts = agent.engineState.value.messages
            .flatMap { it.content }
            .filterIsInstance<ContentPart.Text>()
            .map { it.text }
        assertEquals(ToolLoopAgentState.Phase.Idle, agent.engineState.value.phase, "must not be stuck streaming")
        assertTrue(texts.none { it.contains("call1") }, "stale superseded result must not appear: $texts")
        assertTrue(texts.any { it == "call2" }, "must converge to the latest submission: $texts")
        assertFalse(agent.engineState.value.phase is ToolLoopAgentState.Phase.Error, "engine must finish without error")
    }
}
