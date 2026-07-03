package ai.torad.aisdk

import kotlinx.coroutines.CancellationException

/** One invocation's telemetry handle: resolved integration + call correlation envelope. */
internal class TelemetryFeed(val tele: Telemetry, val call: TelemetryCall)

/**
 * Guarded telemetry + lifecycle-hook dispatch for [ToolLoopAgent]. All
 * telemetry fires and hook invocations go through this collaborator so the
 * agent loop body is pure orchestration — the "fire-and-maybe-swallow"
 * semantics live here.
 *
 * Error events route to the per-call [AgentCallHooks] passed at each call (the
 * `events()` Flow bridge, the engine submit, or an explicit per-call hook) so a
 * `Flow<AgentEvent>` collector sees them. There is no agent-level callback — the
 * 9 constructor `onX` callbacks were replaced by `ToolLoopAgent.events()`.
 */
internal class AgentTelemetryDispatcher<TContext>(
    private val logger: Logger,
) {
    /**
     * Deliver one telemetry event, guarded: telemetry OBSERVES — an
     * integration throw is swallowed so it can never alter loop behaviour
     * (CancellationException still propagates). No-op when [feed] is null.
     */
    suspend fun fireTelemetry(
        feed: TelemetryFeed?,
        block: suspend Telemetry.(TelemetryCall) -> Unit,
    ) {
        if (feed == null) return
        try {
            feed.tele.block(feed.call)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            CancellationExceptions.asCancellationExceptionOrNull(t)?.let { throw it }
            logger.warn("telemetry integration '${feed.tele.name}' threw — event dropped", t)
        }
    }

    /** Run one guarded lifecycle-hook body. On failure: dispatch [AgentEvent.Errored]
     *  to the per-call [hooks] and telemetry, never propagate. */
    suspend fun runHook(
        stepNumber: Int,
        feed: TelemetryFeed?,
        hooks: AgentCallHooks?,
        block: suspend () -> Unit,
    ) {
        try {
            block()
        } catch (t: Throwable) {
            CancellationExceptions.asCancellationExceptionOrNull(t)?.let { throw it }
            val event = AgentEvent.Errored(t, stepNumber, AgentEvent.Errored.ErrorSource.Hook)
            try {
                hooks?.onError?.invoke(event)
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                // reporting hook's own failure is best-effort
            }
            fireTelemetry(feed) { onEvent(it, event) }
        }
    }

    /** Fire [AgentEvent.Errored] to the per-call [hooks] (Flow bridge / engine) and telemetry. */
    suspend fun emitError(
        t: Throwable,
        stepNumber: Int,
        source: AgentEvent.Errored.ErrorSource,
        hooks: AgentCallHooks?,
        feed: TelemetryFeed? = null,
    ) {
        val event = AgentEvent.Errored(t, stepNumber, source)
        try { hooks?.onError?.invoke(event) } catch (ce: CancellationException) { throw ce } catch (_: Throwable) {}
        fireTelemetry(feed) { onEvent(it, event) }
    }
}
