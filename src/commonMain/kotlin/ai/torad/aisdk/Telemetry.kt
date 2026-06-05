package ai.torad.aisdk

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

public data class TelemetrySettings(
    val isEnabled: Boolean = false,
    val functionId: String? = null,
    val metadata: Map<String, JsonElement> = emptyMap(),
    val recordInputs: Boolean = true,
    val recordOutputs: Boolean = true,
    val integrations: List<TelemetryIntegration> = emptyList(),
    val tracer: TelemetryTracer? = null,
)

public data class TelemetrySpan(
    val operationName: String,
    val attributes: Map<String, JsonElement> = emptyMap(),
)

public data class TelemetryEvent(
    val name: String,
    val attributes: Map<String, JsonElement> = emptyMap(),
    val payload: JsonElement? = null,
)

public interface TelemetryIntegration {
    public val name: String
    public suspend fun record(span: TelemetrySpan, block: suspend () -> Unit)
    public suspend fun onStart(event: TelemetryEvent) {}
    public suspend fun onStepStart(event: TelemetryEvent) {}
    public suspend fun onToolCallStart(event: TelemetryEvent) {}
    public suspend fun onToolCallFinish(event: TelemetryEvent) {}
    public suspend fun onStepFinish(event: TelemetryEvent) {}
    public suspend fun onFinish(event: TelemetryEvent) {}
}

public data object NoopTelemetryIntegration : TelemetryIntegration {
    override val name: String = "noop"
    override suspend fun record(span: TelemetrySpan, block: suspend () -> Unit) {
        block()
    }
}

public class TelemetryIntegrationRegistry(
    private val integrations: MutableMap<String, TelemetryIntegration> = linkedMapOf(),
) {
    public fun register(integration: TelemetryIntegration) {
        integrations[integration.name] = integration
    }

    public fun get(name: String): TelemetryIntegration? = integrations[name]
    public fun list(): List<TelemetryIntegration> = integrations.values.toList()
    public fun clear() {
        integrations.clear()
    }
}

public val globalTelemetryIntegrations: TelemetryIntegrationRegistry = TelemetryIntegrationRegistry()

public fun registerTelemetryIntegration(integration: TelemetryIntegration) {
    globalTelemetryIntegrations.register(integration)
}

public fun getGlobalTelemetryIntegrations(): List<TelemetryIntegration> =
    globalTelemetryIntegrations.list()

public fun clearGlobalTelemetryIntegrations() {
    globalTelemetryIntegrations.clear()
}

internal fun bindTelemetryIntegration(integration: TelemetryIntegration): TelemetryIntegration = integration

internal fun getGlobalTelemetryIntegration(
    integrations: List<TelemetryIntegration> = emptyList(),
): TelemetryIntegration {
    val allIntegrations = getGlobalTelemetryIntegrations() + integrations
    return CompositeTelemetryIntegration(allIntegrations)
}

internal fun getGlobalTelemetryIntegration(
    integration: TelemetryIntegration?,
): TelemetryIntegration = getGlobalTelemetryIntegration(listOfNotNull(integration))

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

internal suspend fun recordSpan(
    integration: TelemetryIntegration = NoopTelemetryIntegration,
    operationName: String,
    attributes: Map<String, JsonElement> = emptyMap(),
    block: suspend () -> Unit,
) {
    integration.record(TelemetrySpan(operationName, attributes), block)
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
    if (!telemetry.isEnabled) return@buildMap
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
    if (!telemetry.isEnabled) return emptyMap()
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

private class CompositeTelemetryIntegration(
    private val integrations: List<TelemetryIntegration>,
) : TelemetryIntegration {
    override val name: String = "composite"

    override suspend fun record(span: TelemetrySpan, block: suspend () -> Unit) {
        fun nested(index: Int): suspend () -> Unit = {
            if (index >= integrations.size) {
                block()
            } else {
                integrations[index].record(span, nested(index + 1))
            }
        }
        nested(0).invoke()
    }

    override suspend fun onStart(event: TelemetryEvent) {
        broadcast { it.onStart(event) }
    }

    override suspend fun onStepStart(event: TelemetryEvent) {
        broadcast { it.onStepStart(event) }
    }

    override suspend fun onToolCallStart(event: TelemetryEvent) {
        broadcast { it.onToolCallStart(event) }
    }

    override suspend fun onToolCallFinish(event: TelemetryEvent) {
        broadcast { it.onToolCallFinish(event) }
    }

    override suspend fun onStepFinish(event: TelemetryEvent) {
        broadcast { it.onStepFinish(event) }
    }

    override suspend fun onFinish(event: TelemetryEvent) {
        broadcast { it.onFinish(event) }
    }

    private suspend fun broadcast(listener: suspend (TelemetryIntegration) -> Unit) {
        for (integration in integrations) {
            runCatching { listener(integration) }
        }
    }
}
