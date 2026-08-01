package ai.torad.aisdk

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Direct unit tests for [AgentTelemetryDispatcher]'s guarded error-report site — the
 * `hooks.onError` invocation that both [AgentTelemetryDispatcher.emitError] and
 * [AgentTelemetryDispatcher.runHook] go through.
 *
 * `TelemetryWiringTest` covers the OTHER swallow in this class (a throwing [Telemetry]
 * integration, pinned to a `logger.warn` tell). These cover the hook swallow with the
 * same convention: a consumer-supplied `onError` that throws must never break the loop,
 * must never mask the error it was reporting, and must never disappear silently — while
 * a [CancellationException] from that same hook must still propagate.
 */
class AgentTelemetryDispatcherTest {
    private class DispatcherTestFailure(message: String) : IllegalStateException(message)

    /**
     * A user-defined SUBCLASS of [CancellationException]. Kotlin dispatches catch clauses
     * statically and in order, so this is the shape that discriminates a clause-ORDER break:
     * a broad `catch (t: Throwable)` placed first would eat it and the swallow would look fine.
     */
    private class SubclassCancellation(message: String) : CancellationException(message)

    private class RecordingLogger : Logger {
        val warns = mutableListOf<String>()
        override fun warn(message: String, throwable: Throwable?) {
            warns += "$message | ${throwable?.message}"
        }
        override fun info(message: String): Unit = Unit
        override fun debug(message: String): Unit = Unit
    }

    private class RecordingTelemetry(override val name: String = "probe") : Telemetry {
        val events = mutableListOf<AgentEvent>()
        override suspend fun onEvent(call: TelemetryCall, event: AgentEvent) {
            events += event
        }
    }

    private class ExplodingTelemetry(override val name: String = "probe") : Telemetry {
        override suspend fun onEvent(call: TelemetryCall, event: AgentEvent): Unit =
            throw DispatcherTestFailure("integration exploded")
    }

    private fun feedOf(tele: Telemetry) =
        TelemetryFeed(tele, TelemetryCall(callId = "c1", agentId = "a1"))

    @Test
    fun `a throwing error hook leaves a logger warn tell`() = runTest {
        val logger = RecordingLogger()
        val dispatcher = AgentTelemetryDispatcher<Unit>(logger)
        val hooks = AgentCallHooks(onError = { throw DispatcherTestFailure("error hook exploded") })

        dispatcher.emitError(
            DispatcherTestFailure("the original failure"),
            stepNumber = 3,
            source = AgentEvent.Errored.ErrorSource.Tool,
            hooks = hooks,
        )

        val warn = logger.warns.singleOrNull()
        assertTrue(warn != null, "a broken error hook must be discoverable: ${logger.warns}")
        assertTrue(warn.contains("error hook"), "the warn names the failing hook: $warn")
        assertTrue(warn.contains("Tool"), "the warn names the report that was dropped: $warn")
        assertTrue(warn.contains("error hook exploded"), "the warn carries the hook's own failure: $warn")
    }

    @Test
    fun `a well behaved error hook receives the event and produces no warn`() = runTest {
        val logger = RecordingLogger()
        val dispatcher = AgentTelemetryDispatcher<Unit>(logger)
        val seen = mutableListOf<AgentEvent.Errored>()

        dispatcher.emitError(
            DispatcherTestFailure("the original failure"),
            stepNumber = 7,
            source = AgentEvent.Errored.ErrorSource.PrepareStep,
            hooks = AgentCallHooks(onError = { seen += it }),
        )

        assertEquals(AgentEvent.Errored.ErrorSource.PrepareStep, seen.single().source)
        assertEquals(7, seen.single().stepNumber)
        assertTrue(logger.warns.isEmpty(), "no failure means no tell: ${logger.warns}")
    }

    /**
     * Clause-order guard: `catch (ce: CancellationException) { throw ce }` MUST precede the
     * broad clause, or a cancellation raised by a consumer hook is swallowed and structured
     * concurrency breaks. Merging or reordering those clauses fails here.
     */
    @Test
    fun `a CancellationException from the error hook propagates instead of being swallowed`() = runTest {
        val logger = RecordingLogger()
        val dispatcher = AgentTelemetryDispatcher<Unit>(logger)
        val hooks = AgentCallHooks(onError = { throw CancellationException("stop from the error hook") })

        assertFailsWith<CancellationException> {
            dispatcher.emitError(
                DispatcherTestFailure("the original failure"),
                stepNumber = 3,
                source = AgentEvent.Errored.ErrorSource.Tool,
                hooks = hooks,
            )
        }
        assertTrue(logger.warns.isEmpty(), "cancellation is not a swallowed hook failure: ${logger.warns}")
    }

    /**
     * The SUBCLASS variant of the clause-order guard. The plain-[CancellationException] case
     * above can be passed by an implementation that special-cases the exact type; only a
     * genuinely-first `catch (ce: CancellationException)` clause also lets a subclass through.
     */
    @Test
    fun `a CancellationException subclass from the error hook propagates and logs nothing`() = runTest {
        val logger = RecordingLogger()
        val dispatcher = AgentTelemetryDispatcher<Unit>(logger)
        val hooks = AgentCallHooks(onError = { throw SubclassCancellation("stop") })

        assertFailsWith<SubclassCancellation> {
            dispatcher.emitError(
                DispatcherTestFailure("the original failure"),
                stepNumber = 3,
                source = AgentEvent.Errored.ErrorSource.Tool,
                hooks = hooks,
            )
        }
        assertTrue(logger.warns.isEmpty(), "a cancellation subclass is not a swallowed failure: ${logger.warns}")
    }

    /**
     * Characterisation of [AgentTelemetryDispatcher.runHook]'s failure path, which reports
     * through that same guarded site: exactly one [AgentEvent.Errored] with source `Hook`,
     * carrying the hook body's own throwable and step number.
     */
    @Test
    fun `a throwing lifecycle hook body is reported once as an ErrorSource Hook event`() = runTest {
        val reported = mutableListOf<AgentEvent.Errored>()
        val dispatcher = AgentTelemetryDispatcher<Unit>(RecordingLogger())
        val hooks = AgentCallHooks(onError = { reported += it })

        dispatcher.runHook(stepNumber = 2, feed = null, hooks = hooks) {
            throw DispatcherTestFailure("lifecycle hook blew up")
        }

        val event = reported.single()
        assertEquals(AgentEvent.Errored.ErrorSource.Hook, event.source)
        assertEquals(2, event.stepNumber)
        assertEquals("lifecycle hook blew up", event.error.message)
    }

    /**
     * The INDIRECT route to the same swallow: a lifecycle-hook body throws AND the `onError`
     * that is supposed to report it also throws. The report is dropped, but the drop must
     * still be discoverable on the [AgentTelemetryDispatcher.runHook] path, not only on the
     * direct [AgentTelemetryDispatcher.emitError] one — and it must never propagate.
     */
    @Test
    fun `a throwing error hook on the runHook path leaves a logger warn tell`() = runTest {
        val logger = RecordingLogger()
        val dispatcher = AgentTelemetryDispatcher<Unit>(logger)
        val hooks = AgentCallHooks(onError = { throw DispatcherTestFailure("onError exploded") })

        dispatcher.runHook(stepNumber = 2, feed = null, hooks = hooks) {
            throw DispatcherTestFailure("lifecycle hook blew up")
        }

        val warn = logger.warns.singleOrNull()
        assertTrue(warn != null, "the runHook path's dropped report must be discoverable: ${logger.warns}")
        assertTrue(warn.contains("Hook"), "the warn names the dropped report's source: $warn")
        assertTrue(warn.contains("onError exploded"), "the warn carries the hook's own failure: $warn")
    }

    /**
     * COMPOUND: both guarded sites fire in one call — the consumer `onError` throws AND the
     * telemetry integration throws. Each swallow must leave its OWN tell, in order, so the two
     * failures stay independently observable instead of collapsing into one signal.
     */
    @Test
    fun `both swallows in one call leave independently observable tells`() = runTest {
        val logger = RecordingLogger()
        val dispatcher = AgentTelemetryDispatcher<Unit>(logger)
        val hooks = AgentCallHooks(onError = { throw DispatcherTestFailure("error hook exploded") })

        dispatcher.emitError(
            DispatcherTestFailure("the original failure"),
            stepNumber = 3,
            source = AgentEvent.Errored.ErrorSource.Tool,
            hooks = hooks,
            feed = feedOf(ExplodingTelemetry()),
        )

        assertEquals(2, logger.warns.size, "one tell per swallow: ${logger.warns}")
        assertTrue(logger.warns[0].contains("error hook"), "first tell is the hook swallow: ${logger.warns[0]}")
        assertTrue(
            logger.warns[1].contains("telemetry integration"),
            "second tell is the integration swallow: ${logger.warns[1]}",
        )
    }

    /**
     * Absent-hook guard: `hooks?.onError?.invoke(...)` is a safe-call chain, and a null hook is
     * not a hook failure. Telemetry must still receive the event and nothing may be logged.
     */
    @Test
    fun `null hooks produce no spurious warn and still reach telemetry`() = runTest {
        val logger = RecordingLogger()
        val dispatcher = AgentTelemetryDispatcher<Unit>(logger)
        val tele = RecordingTelemetry()

        dispatcher.emitError(
            DispatcherTestFailure("the original failure"),
            stepNumber = 5,
            source = AgentEvent.Errored.ErrorSource.Tool,
            hooks = null,
            feed = feedOf(tele),
        )

        val event = tele.events.single()
        assertTrue(event is AgentEvent.Errored, "telemetry receives the error event: $event")
        assertEquals(5, event.stepNumber)
        assertTrue(logger.warns.isEmpty(), "an absent hook is not a failure: ${logger.warns}")
    }
}
