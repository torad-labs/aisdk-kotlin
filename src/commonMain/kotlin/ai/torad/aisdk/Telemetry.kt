@file:Suppress("TooManyFunctions")

package ai.torad.aisdk

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.update

/**
 * Telemetry settings for an agent or call (upstream v7 `telemetry`, the
 * stabilized `experimental_telemetry`). With the v7 revamp, telemetry is
 * opt-out: once an integration is registered via [Telemetry.registerTelemetry], every
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
 * Privacy defaults are metadata-only: [recordInputs] and [recordOutputs] are
 * false unless the host deliberately opts into payload telemetry. The integration
 * path receives a safe projection of [AgentEvent]; raw in-process lifecycle hooks
 * remain unchanged.
 */
public data class TelemetrySettings(
    val isEnabled: Boolean? = null,
    val functionId: String? = null,
    val metadata: Map<String, JsonElement> = emptyMap(),
    val recordInputs: Boolean = false,
    val recordOutputs: Boolean = false,
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

/**
 * The v7 telemetry integration seam: implement it ONCE and the loop feeds it every
 * lifecycle event of every invocation through a single [onEvent] call — agent
 * start/finish, step start/finish, model-call start/finish, tool-call start/finish
 * (including tool executions resumed after an approval pause), errors, and aborts.
 *
 * Symmetric with [ToolLoopAgent.events]: one stream of [AgentEvent], dispatched with
 * an exhaustive `when (event)` (no `else`) so a newly-added event subtype cannot be
 * silently unobserved. Each event is paired with the invocation's [TelemetryCall].
 *
 * Contract:
 *  - Telemetry OBSERVES. It never alters loop behavior: an [onEvent] throw is
 *    swallowed by the loop (CancellationException still propagates).
 *  - Events may arrive CONCURRENTLY (a step's tool calls execute in parallel) —
 *    implementations must be thread-safe.
 */
public interface Telemetry {
    public val name: String

    /** Receives one lifecycle [event] for this [call]. Dispatch with `when (event)`. */
    public suspend fun onEvent(call: TelemetryCall, event: AgentEvent)

    /**
     * Telemetry registration + per-call resolution procedures. These are genuine
     * procedures (not loose top-level funs): `registerTelemetry`/[clearGlobalTelemetry]
     * mutate the [globalTelemetry] registry; [resolveTelemetry] computes the effective
     * integration for one call.
     */
    public companion object {
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
            val privacy = settings ?: TelemetrySettings()
            val perCall = settings?.integrations.orEmpty()
            val effective = perCall.ifEmpty { globalTelemetry.list() }
            val resolved = when {
                effective.isEmpty() -> null
                effective.size == 1 -> effective.single()
                else -> CompositeTelemetry(effective, logger)
            }
            return resolved?.let { RedactingTelemetry(it, privacy) }
        }
    }
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

    override suspend fun onEvent(call: TelemetryCall, event: AgentEvent) {
        TelemetryBroadcast.run(integrations, logger) { it.onEvent(call, event) }
    }
}

private class RedactingTelemetry(
    private val delegate: Telemetry,
    private val settings: TelemetrySettings,
    private val redactor: Redactor = AiSdkDefaultRedactor,
) : Telemetry {
    override val name: String = delegate.name

    override suspend fun onEvent(call: TelemetryCall, event: AgentEvent) {
        delegate.onEvent(call, TelemetryRedaction.safeProjection(event, settings, redactor))
    }
}

private object TelemetryRedaction {
    fun safeProjection(event: AgentEvent, settings: TelemetrySettings, redactor: Redactor): AgentEvent =
        event.safeTelemetryProjection(settings, redactor)

    @Suppress("CyclomaticComplexMethod")
    private fun AgentEvent.safeTelemetryProjection(settings: TelemetrySettings, redactor: Redactor): AgentEvent =
        when (this) {
            is AgentEvent.Started<*> -> AgentEvent.Started<Any?>(
                prompt = prompt?.takeIf { settings.recordInputs }?.let(redactor::redactText),
                priorMessages = priorMessages.sanitizedMessages(settings, redactor),
                options = if (settings.recordInputs) options else null,
            )
            is AgentEvent.StepStarted -> copy(
                messages = messages.sanitizedMessages(settings, redactor),
                request = request.sanitizedParams(settings, redactor),
                priorSteps = priorSteps.map { it.sanitizedStep(settings, redactor) },
            )
            is AgentEvent.Chunk -> copy(event = event.sanitizedStreamEvent(settings, redactor))
            is AgentEvent.StepFinished -> copy(step = step.sanitizedStep(settings, redactor))
            is AgentEvent.ToolCallStarted -> copy(
                input = if (settings.recordInputs) redactor.redactJson(input) else JsonObject(emptyMap()),
                messages = messages.sanitizedMessages(settings, redactor),
            )
            is AgentEvent.ToolCallFinished -> copy(outcome = outcome.sanitizedOutcome(settings, redactor))
            is AgentEvent.Errored -> copy(error = error.redactedThrowable(redactor))
            is AgentEvent.Aborted -> copy(steps = steps.map { it.sanitizedStep(settings, redactor) })
            is AgentEvent.Finished<*, *> -> AgentEvent.Finished<Any?, Any?>(
                output = if (settings.recordOutputs) output else null,
                totalSteps = totalSteps,
                usage = usage.sanitizedUsage(),
                pendingApprovals = pendingApprovals.sanitizedPendingApprovals(settings, redactor),
                messages = messages.sanitizedMessages(settings, redactor),
                experimentalContext = if (settings.recordInputs) experimentalContext else null,
            )
            is AgentEvent.ModelCallStarted -> copy(params = params.sanitizedParams(settings, redactor))
            is AgentEvent.ModelCallFinished -> copy(response = response.sanitizedResponseMetadata(settings, redactor))
            is AgentEvent.SpanEmitted -> copy(attributes = attributes.sanitizedAttributes(settings, redactor))
        }

    private fun AgentEvent.ToolCallFinished.Outcome.sanitizedOutcome(
        settings: TelemetrySettings,
        redactor: Redactor,
    ): AgentEvent.ToolCallFinished.Outcome = when (this) {
        is AgentEvent.ToolCallFinished.Outcome.Success ->
            AgentEvent.ToolCallFinished.Outcome.Success(
                if (settings.recordOutputs) redactor.redactJson(outputJson) else JsonObject(emptyMap()),
            )
        is AgentEvent.ToolCallFinished.Outcome.Failure ->
            AgentEvent.ToolCallFinished.Outcome.Failure(redactor.redactText(errorMessage))
    }

    private fun LanguageModelCallParams.sanitizedParams(
        settings: TelemetrySettings,
        redactor: Redactor,
    ): LanguageModelCallParams = copy(
        messages = messages.sanitizedMessages(settings, redactor),
        providerOptions = ProviderOptions.None,
        headers = redactor.redactHeaders(headers),
    )

    private fun List<ModelMessage>.sanitizedMessages(
        settings: TelemetrySettings,
        redactor: Redactor,
    ): List<ModelMessage> {
        if (!settings.recordInputs && !settings.recordOutputs) return emptyList()
        return mapNotNull { message ->
            val content = message.content.mapNotNull { it.sanitizedContent(message.role, settings, redactor) }
            content.takeIf { it.isNotEmpty() }?.let { message.copy(content = it) }
        }
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun ContentPart.sanitizedContent(
        role: MessageRole,
        settings: TelemetrySettings,
        redactor: Redactor,
    ): ContentPart? = when (this) {
        is ContentPart.Text -> when (role) {
            MessageRole.System, MessageRole.User ->
                if (settings.recordInputs) {
                    copy(text = redactor.redactText(text), providerMetadata = ProviderMetadata.None)
                } else {
                    null
                }
            MessageRole.Assistant, MessageRole.Tool ->
                if (settings.recordOutputs) {
                    copy(text = redactor.redactText(text), providerMetadata = ProviderMetadata.None)
                } else {
                    null
                }
        }
        is ContentPart.Reasoning ->
            if (settings.recordOutputs) {
                copy(text = redactor.redactText(text), providerMetadata = ProviderMetadata.None)
            } else {
                null
            }
        is ContentPart.ToolCall ->
            if (settings.recordInputs) {
                copy(input = redactor.redactJson(input), providerMetadata = ProviderMetadata.None)
            } else {
                null
            }
        is ContentPart.ToolResult ->
            if (settings.recordOutputs) {
                val output = redactor.redactJson(output)
                copy(
                    output = output,
                    modelVisible = redactor.redactJson(modelVisible),
                    providerMetadata = ProviderMetadata.None,
                )
            } else {
                null
            }
        is ContentPart.ToolApprovalRequest ->
            if (settings.recordInputs) {
                copy(
                    input = redactor.redactJson(input),
                    signature = null,
                    providerMetadata = ProviderMetadata.None,
                )
            } else {
                null
            }
        is ContentPart.ToolApprovalResponse ->
            if (settings.recordInputs) copy(reason = reason?.let(redactor::redactText)) else null
        is ContentPart.Source ->
            if (settings.recordOutputs) {
                copy(
                    url = null,
                    title = title?.let(redactor::redactText),
                    providerMetadata = ProviderMetadata.None,
                )
            } else {
                null
            }
        is ContentPart.File ->
            if (settings.recordOutputs) {
                copy(base64 = "", url = null, providerMetadata = ProviderMetadata.None)
            } else {
                null
            }
        is ContentPart.Image ->
            if (settings.recordOutputs) {
                copy(base64 = "", url = null, providerMetadata = ProviderMetadata.None)
            } else {
                null
            }
    }

    private fun StepResult.sanitizedStep(settings: TelemetrySettings, redactor: Redactor): StepResult =
        copy(
            text = if (settings.recordOutputs) redactor.redactText(text) else "",
            reasoning = if (settings.recordOutputs) redactor.redactText(reasoning) else "",
            toolCalls = if (settings.recordInputs) {
                toolCalls.map {
                    it.copy(
                        input = redactor.redactJson(it.input),
                        providerMetadata = ProviderMetadata.None,
                    )
                }
            } else {
                emptyList()
            },
            toolResults = if (settings.recordOutputs) {
                toolResults.map {
                    it.copy(
                        output = redactor.redactJson(it.output),
                        modelVisible = redactor.redactJson(it.modelVisible),
                        providerMetadata = ProviderMetadata.None,
                    )
                }
            } else {
                emptyList()
            },
            toolApprovalRequests = if (settings.recordInputs) {
                toolApprovalRequests.map {
                    it.copy(
                        input = redactor.redactJson(it.input),
                        signature = null,
                        providerMetadata = ProviderMetadata.None,
                    )
                }
            } else {
                emptyList()
            },
            usage = usage.sanitizedUsage(),
            request = request.sanitizedRequestMetadata(settings, redactor),
            response = response.sanitizedResponseMetadata(settings, redactor),
            providerMetadata = ProviderMetadata.None,
            experimentalContext = if (settings.recordInputs) experimentalContext else null,
        )

    private fun List<PendingApproval>.sanitizedPendingApprovals(
        settings: TelemetrySettings,
        redactor: Redactor,
    ): List<PendingApproval> =
        if (settings.recordInputs) {
            map { it.copy(input = redactor.redactJson(it.input), signature = null) }
        } else {
            map { it.copy(input = JsonObject(emptyMap()), signature = null) }
        }

    private fun LanguageModelRequestMetadata.sanitizedRequestMetadata(
        settings: TelemetrySettings,
        redactor: Redactor,
    ): LanguageModelRequestMetadata =
        copy(body = body?.takeIf { settings.recordInputs }?.let(redactor::redactJson))

    private fun LanguageModelResponseMetadata.sanitizedResponseMetadata(
        settings: TelemetrySettings,
        redactor: Redactor,
    ): LanguageModelResponseMetadata =
        copy(
            headers = redactor.redactHeaders(headers),
            body = body?.takeIf { settings.recordOutputs }?.let(redactor::redactJson),
        )

    private fun Usage.sanitizedUsage(): Usage = copy(raw = null)

    private fun StreamEvent.sanitizedStreamEvent(settings: TelemetrySettings, redactor: Redactor): StreamEvent =
        when (this) {
            is StreamEvent.TextDelta -> if (settings.recordOutputs) {
                copy(text = redactor.redactText(text))
            } else {
                copy(text = "")
            }
            is StreamEvent.ReasoningDelta -> if (settings.recordOutputs) {
                copy(text = redactor.redactText(text))
            } else {
                copy(text = "")
            }
            is StreamEvent.ToolCall ->
                copy(
                    inputJson = if (settings.recordInputs) {
                        redactor.redactJson(inputJson)
                    } else {
                        JsonObject(emptyMap())
                    },
                )
            is StreamEvent.ToolResult ->
                StreamEvent.ToolResult(
                    toolCallId = toolCallId,
                    toolName = toolName,
                    outputJson = if (settings.recordOutputs) {
                        redactor.redactJson(outputJson)
                    } else {
                        JsonObject(emptyMap())
                    },
                    preliminary = preliminary,
                )
            is StreamEvent.ToolError -> copy(message = redactor.redactText(message))
            is StreamEvent.Error -> copy(
                message = redactor.redactText(message),
                cause = cause?.redactedThrowable(redactor),
            )
            is StreamEvent.FilePart -> copy(base64 = "", providerMetadata = ProviderMetadata.None)
            else -> this
        }

    private fun Map<String, JsonElement>.sanitizedAttributes(
        settings: TelemetrySettings,
        redactor: Redactor,
    ): Map<String, JsonElement> = buildMap {
        for ((key, value) in this@sanitizedAttributes) {
            val normalized = key.lowercase()
            val isInput = normalized.containsAny("input", "prompt", "request")
            val isOutput = normalized.containsAny("output", "response", "completion")
            if (shouldDropTelemetryAttribute(isInput, isOutput, settings)) continue
            put(key, redactor.redactJson(value))
        }
    }

    private fun shouldDropTelemetryAttribute(
        isInput: Boolean,
        isOutput: Boolean,
        settings: TelemetrySettings,
    ): Boolean =
        (isInput && !settings.recordInputs) || (isOutput && !settings.recordOutputs)

    private fun String.containsAny(vararg needles: String): Boolean =
        needles.any { it in this }

    private fun Throwable.redactedThrowable(redactor: Redactor): Throwable =
        RuntimeException(message?.let(redactor::redactText) ?: (this::class.simpleName ?: "Throwable"))
}
