package ai.torad.aisdk

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Typed provider-options boundary (tenet T2).
 *
 * The full options map is wrapped in [Raw] (key = provider name, value = provider JSON).
 * Use [None] as the default when no options are supplied.
  * @since 0.3.0-beta01
 */
public sealed class ProviderOptions {

    /**
     * No provider-specific options — the neutral default.
     * @since 0.3.0-beta01
     */
    public object None : ProviderOptions()

    /**
     * Raw JSON options map.
     * [options] keys are provider names (e.g. `"openai"`, `"anthropic"`);
     * values are provider-specific JSON objects.
      * @since 0.3.0-beta01
     */
    public data class Raw(val options: JsonObject) : ProviderOptions()

    /**
     * Convert to the `Map<String, JsonElement>` format used internally by providers.
     * @since 0.3.0-beta01
     */
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

    /**
     * Recursively deep-merge [other] on top of these options; nested JSON objects
     * are merged key-by-key ([other] wins on scalar conflicts).
     */
    internal fun mergedWith(other: ProviderOptions): ProviderOptions {
        val merged = toMap().toMutableMap()
        for ((key, value) in other.toMap()) {
            val existing = merged[key]
            merged[key] = if (existing is JsonObject && value is JsonObject) deepMerge(existing, value) else value
        }
        return Raw(JsonObject(merged))
    }

    private fun deepMerge(base: JsonObject, override: JsonObject): JsonObject {
        val merged = base.toMutableMap()
        for ((key, value) in override) {
            val existing = merged[key]
            merged[key] = if (existing is JsonObject && value is JsonObject) deepMerge(existing, value) else value
        }
        return JsonObject(merged)
    }

    public companion object {
        /**
         * Build [ProviderOptions] from provider-name / JSON-object pairs.
         * @since 0.3.0-beta01
         */
        public fun ofPairs(vararg pairs: Pair<String, JsonObject>): ProviderOptions =
            if (pairs.isEmpty()) None
            else Raw(JsonObject(pairs.associate { (k, v) -> k to (v as JsonElement) }))
    }
}
