package ai.torad.aisdk.ui

import ai.torad.aisdk.TypedJsonOps.decodeValue
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.serializer

/**
 * UI-shape message — what the chat surface renders. Carries an ordered
 * list of [UIMessagePart]s (Vercel AI SDK v6 message-parts model). The
 * conversion from [ai.torad.aisdk.StreamEvent] flow into a growing list
 * of these messages happens in `streamToUiMessages`.
 *
 * The optional [metadata] slot is the Kotlin port's monomorphic
 * substitute for v6's `UIMessage<METADATA, DATA_PARTS, TOOLS>`
 * generics (per historical parity gap #10). Subagents attach their
 * source-agent identity here; UI renderers can read it to chrome
 * "Agent X -> Agent Y" handoff banners. Arbitrary keys are allowed;
 * apps should prefix custom keys with their own namespace.
 */
@Serializable
/** @since 0.3.0-beta01 */
public data class UIMessage(
    val id: String,
    val role: UIMessageRole,
    val parts: List<UIMessagePart>,
    val createdAtMs: Long = 0L,
    val metadata: Map<String, JsonElement>? = null,
) {
    /** @since 0.3.0-beta01 */
    public fun <TMetadata> metadataAs(name: String, serializer: KSerializer<TMetadata>): TMetadata? =
        metadata?.decodeValue(name, serializer)

    /** @since 0.3.0-beta01 */
    public inline fun <reified TMetadata> metadataAs(name: String): TMetadata? =
        metadataAs(name, serializer<TMetadata>())
}

@Serializable
/**
 * This enum may gain variants in future releases. Consumers must not rely on
 * exhaustiveness — include an `else` branch when matching.
 * @since 0.3.0-beta01
 */
public enum class UIMessageRole { System, User, Assistant }
