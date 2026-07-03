@file:Suppress("TooManyFunctions")

package ai.torad.aisdk

import dev.drewhamilton.poko.Poko
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
/** @since 0.3.0-beta01 */
public class TelemetrySettings internal constructor(
    /** @since 0.3.0-beta01 */
    public val isEnabled: Boolean? = null,
    /** @since 0.3.0-beta01 */
    public val functionId: String? = null,
    /** @since 0.3.0-beta01 */
    public val metadata: Map<String, JsonElement> = emptyMap(),
    /** @since 0.3.0-beta01 */
    public val recordInputs: Boolean = false,
    /** @since 0.3.0-beta01 */
    public val recordOutputs: Boolean = false,
    /** @since 0.3.0-beta01 */
    public val integrations: List<Telemetry> = emptyList(),
    /** @since 0.3.0-beta01 */
    public val tracer: TelemetryTracer? = null,
)

/** @since 0.3.0-beta01 */
public class TelemetrySettingsBuilder {
    private var isEnabled: Boolean? = null
    private var functionId: String? = null
    private var metadata: Map<String, JsonElement> = emptyMap()
    private var recordInputs: Boolean = false
    private var recordOutputs: Boolean = false
    private var integrations: List<Telemetry> = emptyList()
    private var tracer: TelemetryTracer? = null

    /** @since 0.3.0-beta01 */
    public fun isEnabled(value: Boolean?): TelemetrySettingsBuilder {
        isEnabled = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun functionId(value: String?): TelemetrySettingsBuilder {
        functionId = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun metadata(value: Map<String, JsonElement>): TelemetrySettingsBuilder {
        metadata = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun recordInputs(value: Boolean): TelemetrySettingsBuilder {
        recordInputs = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun recordOutputs(value: Boolean): TelemetrySettingsBuilder {
        recordOutputs = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun integrations(value: List<Telemetry>): TelemetrySettingsBuilder {
        integrations = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun tracer(value: TelemetryTracer?): TelemetrySettingsBuilder {
        tracer = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun build(): TelemetrySettings =
        TelemetrySettings(
            isEnabled = isEnabled,
            functionId = functionId,
            metadata = metadata,
            recordInputs = recordInputs,
            recordOutputs = recordOutputs,
            integrations = integrations,
            tracer = tracer,
        )
}

/** @since 0.3.0-beta01 */
public fun TelemetrySettings(
    block: TelemetrySettingsBuilder.() -> Unit = {},
): TelemetrySettings =
    TelemetrySettingsBuilder().apply(block).build()

/**
 * Correlation envelope for one agent invocation (one `generate`/`stream`
 * call). Every [Telemetry] event of that invocation carries the same
 * [callId], so an integration can reconstruct per-call traces even when a
 * single agent instance serves concurrent calls. [agentId]/[agentVersion]
 * mirror [Agent.id]/[Agent.version] (parity gap #33: "useful for telemetry");
 * [functionId] comes from [TelemetrySettings.functionId].
 * @since 0.3.0-beta01
 */
@Poko
public class TelemetryCall(
    /** @since 0.3.0-beta01 */
    public val callId: String,
    /** @since 0.3.0-beta01 */
    public val agentId: String,
    /** @since 0.3.0-beta01 */
    public val agentVersion: String? = null,
    /** @since 0.3.0-beta01 */
    public val modelId: String? = null,
    /** @since 0.3.0-beta01 */
    public val functionId: String? = null,
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
/** @since 0.3.0-beta01 */
public interface Telemetry {
    /** @since 0.3.0-beta01 */
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
        /** @since 0.3.0-beta01 */
        public fun registerTelemetry(integration: Telemetry) {
            globalTelemetry.register(integration)
        }

        /** @since 0.3.0-beta01 */
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
 * @since 0.3.0-beta01
 */
@OptIn(ExperimentalAtomicApi::class)
public class TelemetryRegistry(
    seed: List<Telemetry> = emptyList(),
) {
    private val snapshot = AtomicReference(seed)

    /** @since 0.3.0-beta01 */
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

    /** @since 0.3.0-beta01 */
    public fun get(name: String): Telemetry? = snapshot.load().firstOrNull { it.name == name }

    /** @since 0.3.0-beta01 */
    public fun list(): List<Telemetry> = snapshot.load()

    /** @since 0.3.0-beta01 */
    public fun clear() {
        snapshot.store(emptyList())
    }
}

/**
 * Global registry — upstream v7 `registerTelemetry`: register once at startup, all calls emit.
 * @since 0.3.0-beta01
 */
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
            is AgentEvent.StepStarted -> AgentEvent.StepStarted(
                stepNumber = stepNumber,
                messages = messages.sanitizedMessages(settings, redactor),
                request = request.sanitizedParams(settings, redactor),
                priorSteps = priorSteps.map { it.sanitizedStep(settings, redactor) },
            )
            is AgentEvent.Chunk -> AgentEvent.Chunk(
                event = StreamEventTelemetryRedaction.sanitize(event, settings, redactor),
                stepNumber = stepNumber,
            )
            is AgentEvent.StepFinished -> AgentEvent.StepFinished(
                stepNumber = stepNumber,
                step = step.sanitizedStep(settings, redactor),
            )
            is AgentEvent.ToolCallStarted -> AgentEvent.ToolCallStarted(
                toolCallId = toolCallId,
                toolName = toolName,
                input = if (settings.recordInputs) redactor.redactJson(input) else JsonObject(emptyMap()),
                stepNumber = stepNumber,
                messages = messages.sanitizedMessages(settings, redactor),
            )
            is AgentEvent.ToolCallFinished -> AgentEvent.ToolCallFinished(
                toolCallId = toolCallId,
                toolName = toolName,
                outcome = outcome.sanitizedOutcome(settings, redactor),
                stepNumber = stepNumber,
            )
            is AgentEvent.Errored -> AgentEvent.Errored(
                error = error.redactedThrowable(redactor),
                stepNumber = stepNumber,
                source = source,
            )
            is AgentEvent.Aborted -> AgentEvent.Aborted(
                steps = steps.map { it.sanitizedStep(settings, redactor) },
            )
            is AgentEvent.Finished<*, *> -> AgentEvent.Finished<Any?, Any?>(
                output = if (settings.recordOutputs) output else null,
                totalSteps = totalSteps,
                usage = Usage(
                    inputTokens = usage.inputTokens,
                    outputTokens = usage.outputTokens,
                    raw = null,
                ),
                pendingApprovals = pendingApprovals.sanitizedPendingApprovals(settings, redactor),
                messages = messages.sanitizedMessages(settings, redactor),
                experimentalContext = if (settings.recordInputs) experimentalContext else null,
            )
            is AgentEvent.ModelCallStarted -> AgentEvent.ModelCallStarted(
                stepNumber = stepNumber,
                modelId = modelId,
                params = params.sanitizedParams(settings, redactor),
            )
            is AgentEvent.ModelCallFinished -> AgentEvent.ModelCallFinished(
                stepNumber = stepNumber,
                modelId = modelId,
                finishReason = finishReason,
                usage = usage,
                response = response.sanitizedResponseMetadata(settings, redactor),
                rawFinishReason = rawFinishReason,
            )
            is AgentEvent.SpanEmitted -> AgentEvent.SpanEmitted(
                name = name,
                attributes = attributes.sanitizedAttributes(settings, redactor),
            )
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
    ): LanguageModelCallParams = toBuilder()
        .messages(messages.sanitizedMessages(settings, redactor))
        .providerOptions(ProviderOptions.None)
        .headers(redactor.redactHeaders(headers))
        .build()

    private fun List<ModelMessage>.sanitizedMessages(
        settings: TelemetrySettings,
        redactor: Redactor,
    ): List<ModelMessage> {
        if (!settings.recordInputs && !settings.recordOutputs) return emptyList()
        return mapNotNull { message ->
            val content = message.content.mapNotNull { it.sanitizedContent(message.role, settings, redactor) }
            content.takeIf { it.isNotEmpty() }?.let {
                ModelMessage(
                    role = message.role,
                    content = it,
                )
            }
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
                    ContentPart.Text(
                        text = redactor.redactText(text),
                        providerMetadata = ProviderMetadata.None,
                    )
                } else {
                    null
                }
            MessageRole.Assistant, MessageRole.Tool ->
                if (settings.recordOutputs) {
                    ContentPart.Text(
                        text = redactor.redactText(text),
                        providerMetadata = ProviderMetadata.None,
                    )
                } else {
                    null
                }
        }
        is ContentPart.Reasoning ->
            if (settings.recordOutputs) {
                ContentPart.Reasoning(
                    text = redactor.redactText(text),
                    providerMetadata = ProviderMetadata.None,
                )
            } else {
                null
            }
        is ContentPart.ToolCall ->
            if (settings.recordInputs) {
                ContentPart.ToolCall(
                    toolCallId = toolCallId,
                    toolName = toolName,
                    input = redactor.redactJson(input),
                    providerExecuted = providerExecuted,
                    dynamic = dynamic,
                    providerMetadata = ProviderMetadata.None,
                )
            } else {
                null
            }
        is ContentPart.ToolResult ->
            if (settings.recordOutputs) {
                val output = redactor.redactJson(output)
                ContentPart.ToolResult(
                    toolCallId = toolCallId,
                    toolName = toolName,
                    output = output,
                    isError = isError,
                    modelVisible = redactor.redactJson(modelVisible),
                    dynamic = dynamic,
                    providerExecuted = providerExecuted,
                    providerMetadata = ProviderMetadata.None,
                )
            } else {
                null
            }
        is ContentPart.ToolApprovalRequest ->
            if (settings.recordInputs) {
                ContentPart.ToolApprovalRequest(
                    toolCallId = toolCallId,
                    toolName = toolName,
                    input = redactor.redactJson(input),
                    approvalId = approvalId,
                    signature = null,
                    providerMetadata = ProviderMetadata.None,
                )
            } else {
                null
            }
        is ContentPart.ToolApprovalResponse ->
            if (settings.recordInputs) {
                ContentPart.ToolApprovalResponse(
                    toolCallId = toolCallId,
                    approved = approved,
                    reason = reason?.let(redactor::redactText),
                    approvalId = approvalId,
                )
            } else {
                null
            }
        is ContentPart.Source ->
            if (settings.recordOutputs) {
                ContentPart.Source(
                    sourceType = sourceType,
                    sourceId = sourceId,
                    url = null,
                    title = title?.let(redactor::redactText),
                    providerMetadata = ProviderMetadata.None,
                    mediaType = mediaType,
                    filename = filename,
                )
            } else {
                null
            }
        is ContentPart.File ->
            if (settings.recordOutputs) {
                ContentPart.File(
                    mediaType = mediaType,
                    base64 = "",
                    filename = filename,
                    providerMetadata = ProviderMetadata.None,
                    url = null,
                )
            } else {
                null
            }
        is ContentPart.Image ->
            if (settings.recordOutputs) {
                ContentPart.Image(
                    mediaType = mediaType,
                    base64 = "",
                    providerMetadata = ProviderMetadata.None,
                    url = null,
                )
            } else {
                null
            }
        is ContentPart.Raw ->
            if (settings.recordOutputs) {
                ContentPart.Raw(rawValue = redactor.redactJson(rawValue))
            } else {
                null
            }
    }

    private fun StepResult.sanitizedStep(settings: TelemetrySettings, redactor: Redactor): StepResult =
        ResultConstruction.stepResult(
            stepNumber = stepNumber,
            text = if (settings.recordOutputs) redactor.redactText(text) else "",
            reasoning = if (settings.recordOutputs) redactor.redactText(reasoning) else "",
            toolCalls = if (settings.recordInputs) {
                toolCalls.map {
                    ContentPart.ToolCall(
                        toolCallId = it.toolCallId,
                        toolName = it.toolName,
                        input = redactor.redactJson(it.input),
                        providerExecuted = it.providerExecuted,
                        dynamic = it.dynamic,
                        providerMetadata = ProviderMetadata.None,
                    )
                }
            } else {
                emptyList()
            },
            toolResults = if (settings.recordOutputs) {
                toolResults.map {
                    ContentPart.ToolResult(
                        toolCallId = it.toolCallId,
                        toolName = it.toolName,
                        output = redactor.redactJson(it.output),
                        isError = it.isError,
                        modelVisible = redactor.redactJson(it.modelVisible),
                        dynamic = it.dynamic,
                        providerExecuted = it.providerExecuted,
                        providerMetadata = ProviderMetadata.None,
                    )
                }
            } else {
                emptyList()
            },
            toolApprovalRequests = if (settings.recordInputs) {
                toolApprovalRequests.map {
                    ContentPart.ToolApprovalRequest(
                        toolCallId = it.toolCallId,
                        toolName = it.toolName,
                        input = redactor.redactJson(it.input),
                        approvalId = it.approvalId,
                        signature = null,
                        providerMetadata = ProviderMetadata.None,
                    )
                }
            } else {
                emptyList()
            },
            finishReason = finishReason,
            usage = Usage(
                inputTokens = usage.inputTokens,
                outputTokens = usage.outputTokens,
                raw = null,
            ),
            warnings = warnings,
            request = request.sanitizedRequestMetadata(settings, redactor),
            response = response.sanitizedResponseMetadata(settings, redactor),
            providerMetadata = ProviderMetadata.None,
            rawFinishReason = rawFinishReason,
            model = model,
            experimentalContext = if (settings.recordInputs) experimentalContext else null,
        )

    private fun List<PendingApproval>.sanitizedPendingApprovals(
        settings: TelemetrySettings,
        redactor: Redactor,
    ): List<PendingApproval> =
        if (settings.recordInputs) {
            map {
                PendingApproval(
                    toolCallId = it.toolCallId,
                    toolName = it.toolName,
                    input = redactor.redactJson(it.input),
                    approvalId = it.approvalId,
                    signature = null,
                )
            }
        } else {
            map {
                PendingApproval(
                    toolCallId = it.toolCallId,
                    toolName = it.toolName,
                    input = JsonObject(emptyMap()),
                    approvalId = it.approvalId,
                    signature = null,
                )
            }
        }

    private fun LanguageModelRequestMetadata.sanitizedRequestMetadata(
        settings: TelemetrySettings,
        redactor: Redactor,
    ): LanguageModelRequestMetadata =
        LanguageModelRequestMetadata(
            body = body?.takeIf { settings.recordInputs }?.let(redactor::redactJson),
        )

    private fun LanguageModelResponseMetadata.sanitizedResponseMetadata(
        settings: TelemetrySettings,
        redactor: Redactor,
    ): LanguageModelResponseMetadata =
        LanguageModelResponseMetadata(
            id = id,
            timestampMillis = timestampMillis,
            modelId = modelId,
            headers = redactor.redactHeaders(headers),
            body = body?.takeIf { settings.recordOutputs }?.let(redactor::redactJson),
        )
    private fun Map<String, JsonElement>.sanitizedAttributes(
        settings: TelemetrySettings,
        redactor: Redactor,
    ): Map<String, JsonElement> = buildMap {
        for ((key, value) in this@sanitizedAttributes) {
            val normalized = key.lowercase()
            if (shouldDropTelemetryAttribute(normalized, settings)) continue
            put(key, redactor.redactJson(value))
        }
    }

    private fun shouldDropTelemetryAttribute(
        normalizedKey: String,
        settings: TelemetrySettings,
    ): Boolean {
        val isInput = normalizedKey.containsAny("input", "prompt", "request")
        val isOutput = normalizedKey.containsAny("output", "response", "completion")
        return (isInput && !settings.recordInputs) || (isOutput && !settings.recordOutputs)
    }

    private fun String.containsAny(vararg needles: String): Boolean =
        needles.any { it in this }

    private fun Throwable.redactedThrowable(redactor: Redactor): Throwable =
        RuntimeException(message?.let(redactor::redactText) ?: (this::class.simpleName ?: "Throwable"))
}
