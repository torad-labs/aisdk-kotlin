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
            // No asCancellationExceptionOrNull() call here: the clause above already caught every
            // CancellationException, and that helper is a plain `as?` (see its KDoc), so the call
            // was unreachable. Keeping it suggested the two forms differed and invited two review
            // passes to report a non-defect against the sites that use only one of them.
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
            emitError(t, stepNumber, AgentEvent.Errored.ErrorSource.Hook, hooks, feed)
        }
    }

    /**
     * Fire [AgentEvent.Errored] to the per-call [hooks] (Flow bridge / engine) and telemetry.
     * The single guarded-report site: [runHook] routes its own failures through here too.
     */
    suspend fun emitError(
        error: Throwable,
        stepNumber: Int,
        source: AgentEvent.Errored.ErrorSource,
        hooks: AgentCallHooks?,
        feed: TelemetryFeed? = null,
    ) {
        val event = AgentEvent.Errored(error, stepNumber, source)
        try {
            hooks?.onError?.invoke(event)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            // The error REPORT's own failure is best-effort — it must never replace or mask
            // [error], which is what the caller is actually reporting. Swallowed, but left as a
            // logger tell so a broken consumer hook stays discoverable, exactly as a broken
            // telemetry integration does in fireTelemetry above.
            logger.warn("error hook threw while reporting a $source error — the report was dropped", t)
        }
        fireTelemetry(feed) { onEvent(it, event) }
    }
}
