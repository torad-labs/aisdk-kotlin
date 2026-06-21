package ai.torad.aisdk

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.update

/**
 * Telemetry settings for an agent or call (upstream v7 `telemetry`, the
 * stabilized `experimental_telemetry`). With the v7 revamp, telemetry is
 * opt-out: once an integration is registered via [TelemetryOps.registerTelemetry], every
 * agent invocation emits events to it automatically.
 *
 * What the v7 INTEGRATION path honors:
 *  - [integrations] — per-call/per-agent integrations; when non-empty they
 *    REPLACE the globally registered set (upstream per-call semantics).
 *  - [isEnabled] — tri-state: `false` opts this agent/call OUT entirely
 *    (no integration fires, global or local — upstream "opt out of a
 *    specific call"); `null` (the default) means no opinion (registered
 *    integrations fire); `true` is documentation-only (registration is
 *    what turns telemetry on).
 *  - [functionId] — stamped onto every event's [TelemetryCall].
 *
 * NOT yet honored by the integration path (legacy tracer/span machinery
 * only): [recordInputs]/[recordOutputs] (integration events carry the full
 * prepared params — redact in your integration if needed), [metadata]
 * (not threaded onto [TelemetryCall] yet), and [tracer].
 */
public data class TelemetrySettings(
    val isEnabled: Boolean? = null,
    val functionId: String? = null,
    val metadata: Map<String, JsonElement> = emptyMap(),
    val recordInputs: Boolean = true,
    val recordOutputs: Boolean = true,
    val integrations: List<Telemetry> = emptyList(),
    val tracer: TelemetryTracer? = null,
)

/**
 * Correlation envelope for one agent invocation (one `generate`/`stream`
 * call). Every [Telemetry] event of that invocation carries the same
 * [callId], so an integration can reconstruct per-call traces even when a
 * single agent instance serves concurrent calls. [agentId]/[agentVersion]
 * mirror [Agent.id]/[Agent.version] (parity gap #33: "useful for telemetry");
 * [functionId] comes from [TelemetrySettings.functionId].
 */
public data class TelemetryCall(
    val callId: String,
    val agentId: String,
    val agentVersion: String? = null,
    val modelId: String? = null,
    val functionId: String? = null,
)

/** Fired before one step's model call — carries the EXACT prepared params sent to the provider. */
public data class TelemetryModelCallEvent(
    val stepNumber: Int,
    val modelId: String?,
    val params: LanguageModelCallParams,
)

/** Fired after one step's model call streamed to completion. */
public data class TelemetryModelCallResultEvent(
    val stepNumber: Int,
    val modelId: String?,
    val finishReason: FinishReason,
    val usage: Usage,
    val response: LanguageModelResponseMetadata = LanguageModelResponseMetadata(),
    val rawFinishReason: String? = null,
)

/**
 * The v7 telemetry integration seam (upstream's `Telemetry`, the revamped
 * `TelemetryIntegration`): implement it ONCE and the loop feeds it every
 * lifecycle event of every invocation — agent start/finish, step
 * start/finish, model-call start/finish, tool-call start/finish (including
 * tool executions resumed after an approval pause), errors, and aborts.
 * Upstream: "Instead of wiring up individual callbacks on every call, you
 * implement a `Telemetry` once and register it globally or pass it via
 * `telemetry.integrations`."
 *
 * Event payloads reuse the lifecycle hook event types ([OnStartEvent],
 * [OnStepFinishEvent], ...) — upstream: "The event types for each method are
 * the same as the corresponding event callbacks." Each is paired with the
 * invocation's [TelemetryCall] for correlation.
 *
 * Contract:
 *  - Telemetry OBSERVES. It never alters loop behavior: a method throw is
 *    swallowed by the loop (CancellationException still propagates).
 *  - [onToolCallStart]/[onToolCallFinish] may fire CONCURRENTLY (a step's
 *    tool calls execute in parallel) — implementations must be thread-safe.
 *  - All methods default to no-ops; implement only what you record.
 */
public interface Telemetry {
    public val name: String
    public suspend fun onAgentStart(call: TelemetryCall, event: OnStartEvent) {}
    public suspend fun onStepStart(call: TelemetryCall, event: OnStepStartEvent) {}
    public suspend fun onModelCallStart(call: TelemetryCall, event: TelemetryModelCallEvent) {}
    public suspend fun onModelCallFinish(call: TelemetryCall, event: TelemetryModelCallResultEvent) {}
    public suspend fun onToolCallStart(call: TelemetryCall, event: OnToolCallStartEvent) {}

    /** Exactly one of `outputJson`/`errorMessage` is non-null. NOT fired for a CANCELLED tool
     *  call — cancellation unwinds the loop, so integrations may see a start without a finish. */
    public suspend fun onToolCallFinish(call: TelemetryCall, event: OnToolCallFinishEvent) {}
    public suspend fun onStepFinish(call: TelemetryCall, event: OnStepFinishEvent) {}
    public suspend fun onError(call: TelemetryCall, event: OnErrorEvent) {}
    public suspend fun onAbort(call: TelemetryCall, event: OnAbortEvent) {}
    public suspend fun onAgentFinish(call: TelemetryCall, event: OnFinishEvent) {}
}

/**
 * Ordered registry of [Telemetry] integrations, keyed by [Telemetry.name] (re-register
 * replaces, keeping the original position).
 *
 * Copy-on-write via atomic CAS (the [AbortController] callback-list idiom): [list]/[get]
 * serve the agent hot path from an immutable snapshot, so a concurrent [register]/[clear]
 * can never throw ConcurrentModificationException out of a live agent call — telemetry
 * must never alter the loop.
 */
@OptIn(ExperimentalAtomicApi::class)
public class TelemetryRegistry(
    seed: List<Telemetry> = emptyList(),
) {
    private val snapshot = AtomicReference(seed)

    public fun register(integration: Telemetry) {
        snapshot.update { current ->
            val replaced = current.indexOfFirst { it.name == integration.name }
            if (replaced >= 0) {
                current.toMutableList().apply { set(replaced, integration) }.toList()
            } else {
                current + integration
            }
        }
    }

    public fun get(name: String): Telemetry? = snapshot.load().firstOrNull { it.name == name }
    public fun list(): List<Telemetry> = snapshot.load()
    public fun clear() {
        snapshot.store(emptyList())
    }
}

/** Global registry — upstream v7 `registerTelemetry`: register once at startup, all calls emit. */
public val globalTelemetry: TelemetryRegistry = TelemetryRegistry()

/**
 * Telemetry registration + per-call resolution procedures. These are genuine
 * procedures (not loose top-level funs): [registerTelemetry]/[clearGlobalTelemetry]
 * mutate the [globalTelemetry] registry; [resolveTelemetry] computes the effective
 * integration for one call.
 */
public object TelemetryOps {
    public fun registerTelemetry(integration: Telemetry) {
        globalTelemetry.register(integration)
    }

    public fun clearGlobalTelemetry() {
        globalTelemetry.clear()
    }

    /**
     * Effective integration for one call: an explicit `isEnabled = false` opts the call out
     * entirely (upstream "opt out of a specific call"); otherwise per-call
     * [TelemetrySettings.integrations] REPLACE the global registrations when non-empty
     * (upstream per-call semantics); null when nothing is registered (zero-overhead path).
     * [logger] receives one warn per swallowed integration throw — a dead integration is
     * discoverable, never perfectly silent.
     */
    internal fun resolveTelemetry(settings: TelemetrySettings?, logger: Logger = NoopLogger): Telemetry? {
        if (settings?.isEnabled == false) return null
        val perCall = settings?.integrations.orEmpty()
        val effective = perCall.ifEmpty { globalTelemetry.list() }
        return when {
            effective.isEmpty() -> null
            effective.size == 1 -> effective.single()
            else -> CompositeTelemetry(effective, logger)
        }
    }
}

/** One telemetry notification, delivered to each integration of a [CompositeTelemetry]. */
private fun interface TelemetryNotify {
    public suspend fun notify(integration: Telemetry)
}

/** Guarded fan-out: one integration's failure never starves the rest (cancellation still
 *  propagates), and each swallow leaves a [Logger.warn] tell so a broken integration is
 *  discoverable. */
private object TelemetryBroadcast {
    suspend fun run(integrations: List<Telemetry>, logger: Logger, listener: TelemetryNotify) {
        for (integration in integrations) {
            try {
                listener.notify(integration)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                logger.warn("telemetry integration '${integration.name}' threw — event dropped for it", t)
            }
        }
    }
}

/** Broadcasts each event to every integration via [TelemetryBroadcast]. */
private class CompositeTelemetry(
    private val integrations: List<Telemetry>,
    private val logger: Logger,
) : Telemetry {
    override val name: String = "composite"

    override suspend fun onAgentStart(call: TelemetryCall, event: OnStartEvent) {
        TelemetryBroadcast.run(integrations, logger) { it.onAgentStart(call, event) }
    }

    override suspend fun onStepStart(call: TelemetryCall, event: OnStepStartEvent) {
        TelemetryBroadcast.run(integrations, logger) { it.onStepStart(call, event) }
    }

    override suspend fun onModelCallStart(call: TelemetryCall, event: TelemetryModelCallEvent) {
        TelemetryBroadcast.run(integrations, logger) { it.onModelCallStart(call, event) }
    }

    override suspend fun onModelCallFinish(call: TelemetryCall, event: TelemetryModelCallResultEvent) {
        TelemetryBroadcast.run(integrations, logger) { it.onModelCallFinish(call, event) }
    }

    override suspend fun onToolCallStart(call: TelemetryCall, event: OnToolCallStartEvent) {
        TelemetryBroadcast.run(integrations, logger) { it.onToolCallStart(call, event) }
    }

    override suspend fun onToolCallFinish(call: TelemetryCall, event: OnToolCallFinishEvent) {
        TelemetryBroadcast.run(integrations, logger) { it.onToolCallFinish(call, event) }
    }

    override suspend fun onStepFinish(call: TelemetryCall, event: OnStepFinishEvent) {
        TelemetryBroadcast.run(integrations, logger) { it.onStepFinish(call, event) }
    }

    override suspend fun onError(call: TelemetryCall, event: OnErrorEvent) {
        TelemetryBroadcast.run(integrations, logger) { it.onError(call, event) }
    }

    override suspend fun onAbort(call: TelemetryCall, event: OnAbortEvent) {
        TelemetryBroadcast.run(integrations, logger) { it.onAbort(call, event) }
    }

    override suspend fun onAgentFinish(call: TelemetryCall, event: OnFinishEvent) {
        TelemetryBroadcast.run(integrations, logger) { it.onAgentFinish(call, event) }
    }
}
