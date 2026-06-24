package ai.torad.aisdk

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Wire-level constraint on what shape the model is allowed to emit.
 * Mirrors v6's `responseFormat` (per `call-settings.ts:103-128` →
 * historical parity gap #20). Distinct from [Output] — Output is the
 * post-decode wrapper that turns text into typed values; ResponseFormat
 * is what the *provider* sees at call time.
 *
 * Providers that support constrained decoding (OpenAI `response_format`,
 * Anthropic `output_format`, on-device grammar-constrained sampling)
 * use this to force JSON shape compliance during decoding rather than
 * letting the model wander into prose. Providers without that
 * capability ignore it — the schema is still useful as a hint in the
 * prompt-injection middleware.
 *
 * Two variants:
 *  - [Text] — default, no constraint, prose allowed.
 *  - [Json] — model must produce JSON; optional schema constrains shape.
 *
 * Sealed interface form is the port-idiomatic shape.
 */
@Serializable
public sealed interface ResponseFormat {

    @Serializable
    public data object Text : ResponseFormat

    /**
     * JSON-shaped response. [schemaJson] is the JSON-Schema constraint
     * (optional — null means "any valid JSON object"); [schemaName] and
     * [schemaDescription] are passed verbatim to providers that expose
     * named-schema modes (OpenAI structured outputs, Gemini's
     * `response_schema`).
     *
     * Idiomatic use via `outputObj` / `outputArray`: when a caller
     * wants typed output, they construct an [Output] AND set
     * `responseFormat = ResponseFormat.Json(...)` on the call params so
     * the provider constrains decoding, then [Output.decode] parses
     * the response text.
     */
    @Serializable
    public data class Json(
        val schemaName: String? = null,
        val schemaDescription: String? = null,
        val schemaJson: JsonElement? = null,
    ) : ResponseFormat
}
