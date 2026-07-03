package ai.torad.aisdk.protocol

import ai.torad.aisdk.CallWarning
import ai.torad.aisdk.JsonAccess
import ai.torad.aisdk.StreamEvent
import ai.torad.aisdk.WireDecoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlin.time.Instant

internal object GatewayLifecycleStreamCodec {
    private const val MILLIS_PER_SECOND = 1000

    fun decode(type: String, obj: JsonObject): StreamEvent? = when (type) {
        "stream-start" -> StreamEvent.StreamStart(callWarnings(obj["warnings"]))
        "response-metadata" -> responseMetadata(obj)
        "step-start", "start-step" -> StreamEvent.StepStart(
            stepNumber = (obj["stepNumber"] as? JsonPrimitive)?.intOrNull ?: 1,
            providerMetadata = ProtocolMetadata.fromJson(obj["providerMetadata"]),
        )
        else -> null
    }

    private fun responseMetadata(obj: JsonObject): StreamEvent.ResponseMetadata =
        StreamEvent.ResponseMetadata(
            id = WireDecoder.optionalString(obj, "id", "gateway", "stream event"),
            timestampMillis = timestampMillis(obj),
            modelId = WireDecoder.optionalString(obj, "modelId", "gateway", "stream event"),
            headers = headers(obj),
            body = obj["body"],
        )

    private fun timestampMillis(obj: JsonObject): Long? =
        (obj["timestampMillis"] as? JsonPrimitive)?.longOrNull
            ?: (obj["timestamp"] as? JsonPrimitive)?.doubleOrNull?.let {
                (it * MILLIS_PER_SECOND).toLong()
            }
            ?: (obj["timestamp"] as? JsonPrimitive)?.contentOrNull
                ?.let { runCatching { Instant.parse(it).toEpochMilliseconds() }.getOrNull() }

    private fun headers(obj: JsonObject): Map<String, String> =
        (JsonAccess.obj(obj, "headers"))
            ?.mapValues { (_, value) -> (value as? JsonPrimitive)?.contentOrNull.orEmpty() }
            .orEmpty()

    private fun callWarnings(value: JsonElement?): List<CallWarning> =
        (value as? JsonArray).orEmpty().mapNotNull { warning ->
            val obj = warning as? JsonObject ?: return@mapNotNull null
            CallWarning(
                type = (obj["type"] as? JsonPrimitive)?.contentOrNull ?: "other",
                message = (obj["message"] as? JsonPrimitive)?.contentOrNull
                    ?: (obj["details"] as? JsonPrimitive)?.contentOrNull,
                details = warning,
            )
        }
}
