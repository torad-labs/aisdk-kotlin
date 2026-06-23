package ai.torad.aisdk.protocol

import ai.torad.aisdk.ProviderMetadata
import ai.torad.aisdk.WireDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

internal object ProtocolMetadata {
    fun fromJson(value: JsonElement?): ProviderMetadata =
        if (value is JsonObject) ProviderMetadata.Raw(value) else ProviderMetadata.None

    fun string(metadata: ProviderMetadata, key: String): String? {
        val raw = metadata.toMap()
        val gateway = raw["gateway"] as? JsonObject
        return (raw[key] as? JsonPrimitive)?.contentOrNull
            ?: (gateway?.get(key) as? JsonPrimitive)?.contentOrNull
    }

    fun put(builder: JsonObjectBuilder, metadata: ProviderMetadata) {
        if (metadata is ProviderMetadata.Raw) builder.put("providerMetadata", metadata.metadata)
    }

    fun source(obj: JsonObject): ProviderMetadata =
        fromJson(obj["providerMetadata"]) + gateway(
            "filename" to WireDecoder.optionalString(obj, "filename", "gateway", "stream event"),
        )

    fun file(obj: JsonObject): ProviderMetadata =
        fromJson(obj["providerMetadata"]) + gateway(
            "filename" to WireDecoder.optionalString(obj, "filename", "gateway", "stream event"),
            "url" to WireDecoder.optionalString(obj, "url", "gateway", "stream event"),
        )

    private fun gateway(vararg values: Pair<String, String?>): ProviderMetadata {
        val fields = values.mapNotNull { (key, value) -> value?.let { key to JsonPrimitive(it) } }.toMap()
        return if (fields.isEmpty()) {
            ProviderMetadata.None
        } else {
            ProviderMetadata.Raw(JsonObject(mapOf("gateway" to JsonObject(fields))))
        }
    }
}
