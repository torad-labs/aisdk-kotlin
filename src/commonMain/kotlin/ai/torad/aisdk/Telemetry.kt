package ai.torad.aisdk

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.update

/**
 * Telemetry settings for an agent or call (upstream v7 `telemetry`, the
 * stabilized `experimental_telemetry`). With the v7 revamp, telemetry is
 * opt-out: once an integration is registered via [registerTelemetry], every
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
 */
internal fun resolveTelemetry(settings: TelemetrySettings?): Telemetry? {
    if (settings?.isEnabled == false) return null
    val perCall = settings?.integrations.orEmpty()
    val effective = perCall.ifEmpty { globalTelemetry.list() }
    return when {
        effective.isEmpty() -> null
        effective.size == 1 -> effective.single()
        else -> CompositeTelemetry(effective)
    }
}

/** One telemetry notification, delivered to each integration of a [CompositeTelemetry]. */
private fun interface TelemetryNotify {
    public suspend fun notify(integration: Telemetry)
}

/** Guarded fan-out: one integration's failure never starves the rest (cancellation still propagates). */
private object TelemetryBroadcast {
    suspend fun run(integrations: List<Telemetry>, listener: TelemetryNotify) {
        for (integration in integrations) {
            try {
                listener.notify(integration)
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                // One integration's failure never starves the rest.
            }
        }
    }
}

/** Broadcasts each event to every integration via [TelemetryBroadcast]. */
private class CompositeTelemetry(
    private val integrations: List<Telemetry>,
) : Telemetry {
    override val name: String = "composite"

    override suspend fun onAgentStart(call: TelemetryCall, event: OnStartEvent) {
        TelemetryBroadcast.run(integrations) { it.onAgentStart(call, event) }
    }

    override suspend fun onStepStart(call: TelemetryCall, event: OnStepStartEvent) {
        TelemetryBroadcast.run(integrations) { it.onStepStart(call, event) }
    }

    override suspend fun onModelCallStart(call: TelemetryCall, event: TelemetryModelCallEvent) {
        TelemetryBroadcast.run(integrations) { it.onModelCallStart(call, event) }
    }

    override suspend fun onModelCallFinish(call: TelemetryCall, event: TelemetryModelCallResultEvent) {
        TelemetryBroadcast.run(integrations) { it.onModelCallFinish(call, event) }
    }

    override suspend fun onToolCallStart(call: TelemetryCall, event: OnToolCallStartEvent) {
        TelemetryBroadcast.run(integrations) { it.onToolCallStart(call, event) }
    }

    override suspend fun onToolCallFinish(call: TelemetryCall, event: OnToolCallFinishEvent) {
        TelemetryBroadcast.run(integrations) { it.onToolCallFinish(call, event) }
    }

    override suspend fun onStepFinish(call: TelemetryCall, event: OnStepFinishEvent) {
        TelemetryBroadcast.run(integrations) { it.onStepFinish(call, event) }
    }

    override suspend fun onError(call: TelemetryCall, event: OnErrorEvent) {
        TelemetryBroadcast.run(integrations) { it.onError(call, event) }
    }

    override suspend fun onAbort(call: TelemetryCall, event: OnAbortEvent) {
        TelemetryBroadcast.run(integrations) { it.onAbort(call, event) }
    }

    override suspend fun onAgentFinish(call: TelemetryCall, event: OnFinishEvent) {
        TelemetryBroadcast.run(integrations) { it.onAgentFinish(call, event) }
    }
}

internal fun assembleOperationName(
    operationId: String,
    telemetry: TelemetrySettings = TelemetrySettings(),
): String = telemetry.functionId?.let { "$it.$operationId" } ?: operationId

internal fun assembleOperationNameAttributes(
    operationId: String,
    telemetry: TelemetrySettings = TelemetrySettings(),
): Map<String, JsonElement> = buildMap {
    put("operation.name", JsonPrimitive(operationId + telemetry.functionId?.let { " $it" }.orEmpty()))
    telemetry.functionId?.let { put("resource.name", JsonPrimitive(it)) }
    put("ai.operationId", JsonPrimitive(operationId))
    telemetry.functionId?.let { put("ai.telemetry.functionId", JsonPrimitive(it)) }
}

internal suspend fun <T> recordSpan(
    name: String,
    tracer: TelemetryTracer,
    attributes: Map<String, JsonElement> = emptyMap(),
    endWhenDone: Boolean = true,
    block: suspend (TelemetryActiveSpan) -> T,
): T = tracer.startActiveSpan(name, attributes) { span ->
    try {
        val result = block(span)
        if (endWhenDone) span.end()
        result
    } catch (error: Throwable) {
        try {
            recordErrorOnSpan(span, error)
        } finally {
            span.end()
        }
        throw error
    }
}

internal fun recordErrorOnSpan(span: TelemetryActiveSpan, error: Throwable) {
    span.recordException(error)
    span.status = TelemetrySpanStatus.Error(error.message)
}

internal fun getTracer(
    isEnabled: Boolean = false,
    tracer: TelemetryTracer? = null,
): TelemetryTracer =
    if (!isEnabled) NoopTelemetryTracer else tracer ?: NoopTelemetryTracer

internal fun selectTelemetryAttributes(
    telemetry: TelemetrySettings,
    input: JsonElement? = null,
    output: JsonElement? = null,
    providerMetadata: Map<String, JsonElement> = emptyMap(),
): Map<String, JsonElement> = buildMap {
    // Legacy span path: stays opt-IN (only an explicit `isEnabled = true` selects attributes).
    if (telemetry.isEnabled != true) return@buildMap
    putAll(telemetry.metadata)
    if (telemetry.recordInputs && input != null) put("ai.input", input)
    if (telemetry.recordOutputs && output != null) put("ai.output", output)
    if (providerMetadata.isNotEmpty()) {
        put("ai.providerMetadata", JsonObject(providerMetadata))
    }
}

internal suspend fun selectTelemetryAttributes(
    telemetry: TelemetrySettings,
    attributes: Map<String, TelemetryAttribute>,
): Map<String, JsonElement> {
    // Legacy span path: stays opt-IN (only an explicit `isEnabled = true` selects attributes).
    if (telemetry.isEnabled != true) return emptyMap()
    val selected = linkedMapOf<String, JsonElement>()
    for ((key, value) in attributes) {
        when (value) {
            is TelemetryAttribute.Value -> selected[key] = value.value
            is TelemetryAttribute.Input -> {
                if (telemetry.recordInputs) value.resolve()?.let { selected[key] = it }
            }
            is TelemetryAttribute.Output -> {
                if (telemetry.recordOutputs) value.resolve()?.let { selected[key] = it }
            }
        }
    }
    return selected
}

internal fun stringifyForTelemetry(value: JsonElement?): String? = when (value) {
    null -> null
    is JsonPrimitive -> value.content
    else -> value.toString()
}

internal sealed interface TelemetryAttribute {
    data class Value(val value: JsonElement) : TelemetryAttribute
    data class Input(val resolve: suspend () -> JsonElement?) : TelemetryAttribute
    data class Output(val resolve: suspend () -> JsonElement?) : TelemetryAttribute
}

internal fun telemetryAttribute(value: JsonElement): TelemetryAttribute = TelemetryAttribute.Value(value)
internal fun telemetryInput(resolve: suspend () -> JsonElement?): TelemetryAttribute = TelemetryAttribute.Input(resolve)
internal fun telemetryOutput(resolve: suspend () -> JsonElement?): TelemetryAttribute = TelemetryAttribute.Output(resolve)

public sealed interface TelemetrySpanStatus {
    public data object Ok : TelemetrySpanStatus
    public data class Error(val message: String? = null) : TelemetrySpanStatus
}

public data class TelemetrySpanEvent(
    val name: String,
    val attributes: Map<String, JsonElement> = emptyMap(),
)

public interface TelemetryActiveSpan {
    public val name: String
    public val attributes: Map<String, JsonElement>
    public var status: TelemetrySpanStatus
    public val events: List<TelemetrySpanEvent>
    public var hasEnded: Boolean

    public fun setAttribute(key: String, value: JsonElement)
    public fun addEvent(name: String, attributes: Map<String, JsonElement> = emptyMap())
    public fun recordException(error: Throwable)
    public fun end()
}

public interface TelemetryTracer {
    public suspend fun <T> startActiveSpan(
        name: String,
        attributes: Map<String, JsonElement> = emptyMap(),
        block: suspend (TelemetryActiveSpan) -> T,
    ): T
}

public data object NoopTelemetryTracer : TelemetryTracer {
    override suspend fun <T> startActiveSpan(
        name: String,
        attributes: Map<String, JsonElement>,
        block: suspend (TelemetryActiveSpan) -> T,
    ): T = block(NoopTelemetryActiveSpan(name, attributes))
}

public class InMemoryTelemetryTracer : TelemetryTracer {
    public val spans: MutableList<MutableTelemetrySpan> = mutableListOf()

    override suspend fun <T> startActiveSpan(
        name: String,
        attributes: Map<String, JsonElement>,
        block: suspend (TelemetryActiveSpan) -> T,
    ): T {
        val span = MutableTelemetrySpan(name, attributes.toMutableMap())
        spans += span
        return block(span)
    }
}

public class MutableTelemetrySpan(
    override val name: String,
    private val mutableAttributes: MutableMap<String, JsonElement> = linkedMapOf(),
) : TelemetryActiveSpan {
    override val attributes: Map<String, JsonElement>
        get() = mutableAttributes.toMap()
    override var status: TelemetrySpanStatus = TelemetrySpanStatus.Ok
    private val mutableEvents: MutableList<TelemetrySpanEvent> = mutableListOf()
    override val events: List<TelemetrySpanEvent>
        get() = mutableEvents.toList()
    override var hasEnded: Boolean = false

    override fun setAttribute(key: String, value: JsonElement) {
        mutableAttributes[key] = value
    }

    override fun addEvent(name: String, attributes: Map<String, JsonElement>) {
        mutableEvents += TelemetrySpanEvent(name, attributes)
    }

    override fun recordException(error: Throwable) {
        addEvent(
            "exception",
            buildMap {
                put("exception.type", JsonPrimitive(error::class.simpleName ?: "Throwable"))
                error.message?.let { put("exception.message", JsonPrimitive(it)) }
                error.stackTraceToString().takeIf { it.isNotBlank() }?.let { put("exception.stacktrace", JsonPrimitive(it)) }
            },
        )
    }

    override fun end() {
        hasEnded = true
    }
}

private class NoopTelemetryActiveSpan(
    override val name: String,
    override val attributes: Map<String, JsonElement>,
) : TelemetryActiveSpan {
    override var status: TelemetrySpanStatus = TelemetrySpanStatus.Ok
    override val events: List<TelemetrySpanEvent> = emptyList()
    override var hasEnded: Boolean = false
    override fun setAttribute(key: String, value: JsonElement) {}
    override fun addEvent(name: String, attributes: Map<String, JsonElement>) {}
    override fun recordException(error: Throwable) {}
    override fun end() {
        hasEnded = true
    }
}
