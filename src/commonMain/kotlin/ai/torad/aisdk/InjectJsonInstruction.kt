package ai.torad.aisdk

import kotlinx.serialization.json.JsonElement

/**
 * JSON-mode instruction injection — faithful port of Vercel AI SDK v6
 * `inject-json-instruction.ts`.
 *
 * On-device targets (Gemma 4 E2B via LiteRT-LM) have no native
 * structured-output / JSON mode the way cloud OpenAI / Anthropic do.
 * The v6 strategy for such providers is to splice a textual schema
 * instruction into the system prompt and then repair the model's
 * partial output with `fixJson` / `parsePartialJson`. This file ports
 * the splice half; `FixJson` ports the repair half.
 *
 * The three default strings are reproduced verbatim from upstream so a
 * prompt built here is byte-identical to one built by the JS SDK —
 * important because prompt phrasing measurably moves a small on-device
 * model's compliance rate, and we want to inherit Vercel's tuning
 * rather than re-discover it.
 */

private const val DEFAULT_SCHEMA_PREFIX = "JSON schema:"
private const val DEFAULT_SCHEMA_SUFFIX =
    "You MUST answer with a JSON object that matches the JSON schema above."
private const val DEFAULT_GENERIC_SUFFIX = "You MUST answer with JSON."

/**
 * JSON-mode instruction injection operations.
 *
 * Groups the splice-half procedures ported from Vercel AI SDK v6
 * `inject-json-instruction.ts`.
  * @since 0.3.0-beta01
 */
public object JsonInstruction {
    /**
 * Build the JSON instruction block.
 *
 * Mirrors the v6 array-filter-join exactly:
 * ```
 * [
 *   prompt (only if non-empty),
 *   ""     (blank separator line — only if prompt non-empty),
 *   schemaPrefix,
 *   JSON.stringify(schema) (only if schema present),
 *   schemaSuffix,
 * ].filter(notNull).join("\n")
 * ```
 *
 * `schema.toString()` on a kotlinx [JsonElement] yields compact JSON
 * (no insignificant whitespace), matching JS `JSON.stringify(schema)`.
 *
 * @param prompt existing instruction text to lead with (the model's
 *   system prompt, typically). Empty / null contributes nothing and
 *   suppresses the blank separator line.
 * @param schema the JSON schema the answer must satisfy. When null the
 *   suffix degrades to the generic "answer with JSON" form and no
 *   schema block is emitted.
 * @param schemaPrefix label printed before the schema. Defaults to
 *   [DEFAULT_SCHEMA_PREFIX] when a schema is present, else null.
 * @param schemaSuffix closing instruction. Defaults to the
     *   schema-aware suffix when a schema is present, else the generic one.
     */
    /** @since 0.3.0-beta01 */
    public fun injectJsonInstruction(
        prompt: String? = null,
        schema: JsonElement? = null,
        schemaPrefix: String? = if (schema != null) DEFAULT_SCHEMA_PREFIX else null,
        schemaSuffix: String? = if (schema != null) DEFAULT_SCHEMA_SUFFIX else DEFAULT_GENERIC_SUFFIX,
    ): String = listOfNotNull(
        prompt?.takeIf { it.isNotEmpty() },
        "".takeIf { !prompt.isNullOrEmpty() },
        schemaPrefix,
        schema?.toString(),
        schemaSuffix,
    ).joinToString("\n")

    /**
     * Splice [injectJsonInstruction] into a message list's system prompt.
     *
     * If the first message is a [MessageRole.System] message, its text is
     * used as the `prompt` seed and the whole leading system message is
     * replaced by the injected one. Otherwise a fresh system message is
     * prepended. The remaining messages pass through untouched.
     *
     * This is the provider-side entry point a LiteRt [LanguageModel] would
     * call before generation when the caller requested structured output
     * but the engine has no native JSON mode.
      * @since 0.3.0-beta01
     */
    public fun injectJsonInstructionIntoMessages(
        messages: List<ModelMessage>,
        schema: JsonElement? = null,
        schemaPrefix: String? = if (schema != null) DEFAULT_SCHEMA_PREFIX else null,
        schemaSuffix: String? = if (schema != null) DEFAULT_SCHEMA_SUFFIX else DEFAULT_GENERIC_SUFFIX,
    ): List<ModelMessage> {
        val leadingSystem = messages.firstOrNull()?.takeIf { it.role == MessageRole.System }
        val existingText = leadingSystem
            ?.content
            ?.filterIsInstance<ContentPart.Text>()
            ?.joinToString("") { it.text }
            .orEmpty()
        val injected = injectJsonInstruction(existingText, schema, schemaPrefix, schemaSuffix)
        val tail = if (leadingSystem != null) messages.drop(1) else messages
        return listOf(SystemMessage(injected)) + tail
    }
}
