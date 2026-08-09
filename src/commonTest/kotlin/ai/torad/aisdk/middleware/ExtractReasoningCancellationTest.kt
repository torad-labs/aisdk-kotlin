package ai.torad.aisdk.middleware

import ai.torad.aisdk.FinishReason
import ai.torad.aisdk.LanguageModelCallParams
import ai.torad.aisdk.LanguageModelResult
import ai.torad.aisdk.MiddlewareCallContext
import ai.torad.aisdk.StreamEvent
import ai.torad.aisdk.Usage
import ai.torad.aisdk.UserMessage
import ai.torad.aisdk.providers.MockLanguageModelTextOnly
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the middleware guarantees when a collector is CANCELLED with a reasoning section still open.
 *
 * Stream end and `TextEnd` both synthesize the missing `ReasoningEnd`, which is the defect this
 * campaign fixed. Cancellation is deliberately NOT in that set, and this pins why rather than
 * leaving it as an accident:
 *
 * A `Flow` built with `flow { }` must emit from the collector's own coroutine context — that is
 * the context-preservation invariant. Once the collector's job is cancelled, the only way to run
 * an emission at all is to switch into `NonCancellable`, which IS a different context and makes
 * `emit` throw `IllegalStateException: Flow invariant is violated`. And there is nobody to receive
 * it regardless: a cancelled collector never runs its lambda again, so a synthesized terminal
 * would be emitted into a void.
 *
 * So the terminal event is not the right instrument for the cancel path. If a consumer must not
 * retain a half-open reasoning part after `stop()`, that belongs where the cancelled state is
 * owned — `AgentSession.cancel()` / `Chat.stop()` — not in a middleware emitting to a collector
 * that has already gone away.
 */
class ExtractReasoningCancellationTest {

    @Test
    fun `a collector cancelled mid-reasoning stops receiving events and the flow does not throw`() = runTest {
        val secondDeltaReached = CompletableDeferred<Unit>()
        val upstream = flow {
            emit(StreamEvent.StreamStart(emptyList()))
            emit(StreamEvent.TextStart("t1"))
            // Opens a reasoning section and never closes it.
            emit(StreamEvent.TextDelta("t1", "visible <reasoning>still thinking"))
            secondDeltaReached.complete(Unit)
            // Suspends forever: the collector is cancelled while the section is open.
            awaitCancellation()
        }

        val ctx = MiddlewareCallContext(
            params = LanguageModelCallParams { messages(listOf(UserMessage("x"))) },
            model = MockLanguageModelTextOnly("x"),
            doGenerate = { LanguageModelResult("x", emptyList(), FinishReason.Stop, Usage(1, 1)) },
            doStream = { upstream },
        )

        val seen = mutableListOf<StreamEvent>()
        val job = launch {
            ExtractReasoningMiddleware(separator = "").wrapStream(ctx).collect { seen += it }
        }

        secondDeltaReached.await()
        job.cancel()
        job.join()

        // The section opened...
        assertTrue(
            seen.any { it is StreamEvent.ReasoningStart },
            "the open tag must have started a reasoning section: $seen",
        )
        // ...and no terminal arrives, because a cancelled collector cannot receive one.
        assertEquals(
            0,
            seen.count { it is StreamEvent.ReasoningEnd },
            "cancellation cannot deliver a synthesized ReasoningEnd; pinning that it does not " +
                "pretend to. Closing a half-open part after stop() belongs to the state owner " +
                "(AgentSession.cancel / Chat.stop), not to this middleware: $seen",
        )
        assertTrue(job.isCancelled, "the collector job must actually be cancelled")
    }
}
