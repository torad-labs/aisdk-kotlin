package ai.torad.aisdk

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Typed provider-options boundary (tenet T2).
 *
 * The full options map is wrapped in [Raw] (key = provider name, value = provider JSON).
 * Use [None] as the default when no options are supplied.
 */
public sealed class ProviderOptions {

    /** No provider-specific options — the neutral default. */
    public object None : ProviderOptions()

    /**
     * Raw JSON options map.
     * [options] keys are provider names (e.g. `"openai"`, `"anthropic"`);
     * values are provider-specific JSON objects.
     */
    public data class Raw(val options: JsonObject) : ProviderOptions()

    /** Convert to the `Map<String, JsonElement>` format used internally by providers. */
    public fun toMap(): Map<String, JsonElement> = when (this) {
        is None -> emptyMap()
        is Raw -> options
    }

    /** Deep-merge [other] on top of these options ([other] wins on key conflicts). */
    public operator fun plus(other: ProviderOptions): ProviderOptions = when {
        other is None -> this
        this is None -> other
        this is Raw && other is Raw -> Raw(JsonObject(options + other.options))
        else -> other
    }

    public companion object {
        /** Build [ProviderOptions] from provider-name / JSON-object pairs. */
        public fun ofPairs(vararg pairs: Pair<String, JsonObject>): ProviderOptions =
            if (pairs.isEmpty()) None
            else Raw(JsonObject(pairs.associate { (k, v) -> k to (v as JsonElement) }))
    }
}
