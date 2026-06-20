package ai.torad.aisdk

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// The LEGACY tracer/span half of telemetry (pre-v7): manual spans, attribute selection,
// and the in-memory tracer the parity tests exercise. The v7 integration seam — the
// `Telemetry` interface the agent loop feeds automatically — lives in Telemetry.kt.
// This half is opt-IN via an explicit `TelemetrySettings(isEnabled = true)` and is kept
// for span-shaped consumers until a GenAI-conventions integration replaces it.

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

internal fun selectTelemetryAttributes(
    telemetry: TelemetrySettings,
    input: JsonElement? = null,
    output: JsonElement? = null,
    providerMetadata: ProviderMetadata = ProviderMetadata.None,
): Map<String, JsonElement> = buildMap {
    // Legacy span path: stays opt-IN (only an explicit `isEnabled = true` selects attributes).
    if (telemetry.isEnabled != true) return@buildMap
    putAll(telemetry.metadata)
    if (telemetry.recordInputs && input != null) put("ai.input", input)
    if (telemetry.recordOutputs && output != null) put("ai.output", output)
    val pmMap = providerMetadata.toMap()
    if (pmMap.isNotEmpty()) {
        put("ai.providerMetadata", JsonObject(pmMap))
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
