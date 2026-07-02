package ai.torad.aisdk

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

@Serializable(with = ProviderMetadataSerializer::class)
/** @since 0.3.0-beta01 */
public sealed class ProviderMetadata {

    /** @since 0.3.0-beta01 */
    public object None : ProviderMetadata()

    /** @since 0.3.0-beta01 */
    public data class Raw(val metadata: JsonObject) : ProviderMetadata()

    // claude-directive-exception: 4a8117fb-7a8e-4e-0013 toMap() is the interop bridge to plain maps — not a providerMetadata field declaration
    /** @since 0.3.0-beta01 */
    public fun toMap(): Map<String, JsonElement> = when (this) {
        is None -> emptyMap()
        is Raw -> metadata
    }

    public operator fun plus(other: ProviderMetadata): ProviderMetadata = when {
        other is None -> this
        this is None -> other
        this is Raw && other is Raw -> Raw(JsonObject(metadata + other.metadata))
        else -> other
    }

    public companion object {
        /** @since 0.3.0-beta01 */
        public fun ofPairs(vararg pairs: Pair<String, JsonObject>): ProviderMetadata =
            if (pairs.isEmpty()) {
                None
            } else {
                Raw(JsonObject(pairs.associate { (k, v) -> k to (v as JsonElement) }))
            }
    }
}

internal object ProviderMetadataSerializer : KSerializer<ProviderMetadata> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("ai.torad.aisdk.ProviderMetadata")

    override fun serialize(encoder: Encoder, value: ProviderMetadata) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw UnsupportedFunctionalityError("JSON-only serialization", "ProviderMetadata requires a JSON encoder")
        jsonEncoder.encodeJsonElement(
            when (value) {
                ProviderMetadata.None -> JsonNull
                is ProviderMetadata.Raw -> value.metadata
            },
        )
    }

    override fun deserialize(decoder: Decoder): ProviderMetadata {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw UnsupportedFunctionalityError("JSON-only serialization", "ProviderMetadata requires a JSON decoder")
        return when (val element = jsonDecoder.decodeJsonElement()) {
            is JsonNull -> ProviderMetadata.None
            is JsonObject -> ProviderMetadata.Raw(element)
            else -> ProviderMetadata.None
        }
    }
}
