package ai.torad.aisdk

import kotlinx.coroutines.CancellationException

/** One invocation's telemetry handle: resolved integration + call correlation envelope. */
internal class TelemetryFeed(val tele: Telemetry, val call: TelemetryCall)

/**
 * Guarded telemetry + lifecycle-hook dispatch for [ToolLoopAgent]. All
 * telemetry fires and hook invocations go through this collaborator so the
 * agent loop body is pure orchestration — the "fire-and-maybe-swallow"
 * semantics live here.
 */
internal class AgentTelemetryDispatcher<TContext>(
    private val logger: Logger,
    private val onError: (suspend OnErrorEvent.() -> Unit)?,
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
            logger.warn("telemetry integration '${feed.tele.name}' threw — event dropped", t)
        }
    }

    /** Run one guarded lifecycle-hook body. On failure: dispatch [OnErrorEvent]
     *  to [onError] and telemetry, never propagate. */
    suspend fun runHook(
        stepNumber: Int,
        feed: TelemetryFeed?,
        block: suspend () -> Unit,
    ) {
        try {
            block()
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            val event = OnErrorEvent(t, stepNumber, OnErrorEvent.ErrorSource.Hook)
            try {
                onError?.invoke(event)
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                // reporting hook's own failure is best-effort
            }
            fireTelemetry(feed) { onError(it, event) }
        }
    }

    /** Fire [OnErrorEvent] to the agent-level hook, the per-call [hooks], and telemetry. */
    suspend fun emitError(
        t: Throwable,
        stepNumber: Int,
        source: OnErrorEvent.ErrorSource,
        hooks: AgentCallHooks?,
        feed: TelemetryFeed? = null,
    ) {
        val event = OnErrorEvent(t, stepNumber, source)
        try { onError?.invoke(event) } catch (ce: CancellationException) { throw ce } catch (_: Throwable) {}
        try { hooks?.onError?.invoke(event) } catch (ce: CancellationException) { throw ce } catch (_: Throwable) {}
        fireTelemetry(feed) { onError(it, event) }
    }
}
