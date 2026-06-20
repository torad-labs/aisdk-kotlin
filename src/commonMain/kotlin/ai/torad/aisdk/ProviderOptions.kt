package ai.torad.aisdk

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Typed provider-options boundary (tenet T2).
 *
 * Use [Raw] as the bridge until each provider grows its own typed slice in Layer 8.
 * Convert to the legacy map format via [ProviderOptions.toMap].
 *
 * Field-type replacement of `Map<String, JsonElement>` at `LanguageModelCallParams.providerOptions`
 * is deferred to the Layer-8 fan-out: every provider's private options-extractor uses map
 * subscript today, so changing the field type simultaneously across 25+ data classes (including
 * `CallSettings`, `MediaModels`, `RerankingParams`, `EmbeddingModelCallParams`, etc.) and all
 * test fixtures requires touching the full codebase at once — exactly what Layer 8 is for.
 */
public sealed class ProviderOptions {

    /**
     * Untyped escape hatch — pass raw JSON for providers that don't yet have a typed slice.
     * [provider] is the options-map key (e.g. `"openai"`, `"anthropic"`).
     */
    public data class Raw(
        val provider: String,
        val json: JsonObject,
    ) : ProviderOptions()

    public companion object {
        /**
         * Convert a list of [ProviderOptions] to the `Map<String, JsonElement>` format
         * expected by [LanguageModelCallParams.providerOptions].
         */
        public fun toMap(options: List<ProviderOptions>): Map<String, JsonElement> =
            options.filterIsInstance<Raw>()
                .associate { it.provider to (it.json as JsonElement) }
    }
}
