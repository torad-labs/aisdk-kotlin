package ai.torad.aisdk

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class TelemetrySettings(
    val isEnabled: Boolean = false,
    val functionId: String? = null,
    val metadata: Map<String, JsonElement> = emptyMap(),
    val recordInputs: Boolean = true,
    val recordOutputs: Boolean = true,
)

data class TelemetrySpan(
    val operationName: String,
    val attributes: Map<String, JsonElement> = emptyMap(),
)

interface TelemetryIntegration {
    val name: String
    suspend fun record(span: TelemetrySpan, block: suspend () -> Unit)
}

object NoopTelemetryIntegration : TelemetryIntegration {
    override val name: String = "noop"
    override suspend fun record(span: TelemetrySpan, block: suspend () -> Unit) {
        block()
    }
}

class TelemetryIntegrationRegistry(
    private val integrations: MutableMap<String, TelemetryIntegration> = linkedMapOf(),
) {
    fun register(integration: TelemetryIntegration) {
        integrations[integration.name] = integration
    }

    fun get(name: String): TelemetryIntegration? = integrations[name]
    fun list(): List<TelemetryIntegration> = integrations.values.toList()
}

val globalTelemetryIntegrations: TelemetryIntegrationRegistry = TelemetryIntegrationRegistry()

fun assembleOperationName(
    operationId: String,
    telemetry: TelemetrySettings = TelemetrySettings(),
): String = telemetry.functionId?.let { "$it.$operationId" } ?: operationId

suspend fun recordSpan(
    integration: TelemetryIntegration = NoopTelemetryIntegration,
    operationName: String,
    attributes: Map<String, JsonElement> = emptyMap(),
    block: suspend () -> Unit,
) {
    integration.record(TelemetrySpan(operationName, attributes), block)
}

fun selectTelemetryAttributes(
    telemetry: TelemetrySettings,
    input: JsonElement? = null,
    output: JsonElement? = null,
    providerMetadata: Map<String, JsonElement> = emptyMap(),
): Map<String, JsonElement> = buildMap {
    putAll(telemetry.metadata)
    if (telemetry.recordInputs && input != null) put("ai.input", input)
    if (telemetry.recordOutputs && output != null) put("ai.output", output)
    if (providerMetadata.isNotEmpty()) {
        put("ai.providerMetadata", JsonObject(providerMetadata))
    }
}

fun stringifyForTelemetry(value: JsonElement?): String? = when (value) {
    null -> null
    is JsonPrimitive -> value.content
    else -> value.toString()
}
