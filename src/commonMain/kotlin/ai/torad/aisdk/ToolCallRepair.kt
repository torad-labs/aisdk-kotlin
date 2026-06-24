package ai.torad.aisdk

import kotlinx.serialization.json.JsonElement

/**
 * Stock self-healing repair: re-prompt the model with the tool's JSON
 * schema + the failed args + the parse error, asking for corrected JSON.
 * If the second response parses cleanly, return a [ContentPart.ToolCall]
 * with the corrected `input`; otherwise return null (the agent loop
 * surfaces [StreamEvent.ToolError]).
 *
 * On-device targets like Gemma 4 E2B hallucinate args ~5% of the time;
 * a single re-prompt typically recovers, and is cheap compared to a
 * full user-visible failure. Wire via [ToolLoopAgent.experimental_repairToolCall].
 *
 * Generic over `TContext` — application code can use this directly
 * regardless of its context type. The function does not look at the
 * model's per-call options, only at the tool's published schema and the
 * conversation history, so no context-specific behavior is needed.
 *
 * Bounded recursion: this builder makes ONE repair attempt per failed
 * call. The loop never re-enters repair on the corrected call.
 *
 * @param model the [LanguageModel] to re-prompt. Typically the raw
 *              (un-middlewared) model so the repair turn doesn't show
 *              up in tool-call logs or other middleware side-channels.
 *              Pass a wrapped model if you want repair attempts
 *              instrumented the same way as main-flow calls.
 */
public fun <TContext> ModelRepromptRepair(
    model: LanguageModel,
): ToolCallRepairFunction<TContext> = { failedCall, error, messages, tools ->
    val tool = tools.find(failedCall.toolName)
    if (tool == null) {
        null
    } else {
        val schema = tools.descriptors
            .firstOrNull { it.name == failedCall.toolName }
            ?.parametersSchemaJson
            ?: "{}"
        val correctedJson = RepairRequest(
            toolName = failedCall.toolName,
            failedArgs = failedCall.input.toString(),
            schema = schema,
            errorMessage = error.message ?: "JSON parse error",
        ).reprompt(model, messages)
        correctedJson?.let { json ->
            ContentPart.ToolCall(
                toolCallId = failedCall.toolCallId,
                toolName = failedCall.toolName,
                input = json,
            )
        }
    }
}

/** Inputs for a single repair re-prompt — bundled so the call doesn't
 *  trip the long-parameter-list rule. The repair fn is the only caller. */
internal data class RepairRequest(
    val toolName: String,
    val failedArgs: String,
    val schema: String,
    val errorMessage: String,
) {
    /** Issue a focused re-prompt and parse the response as JSON. Returns
     *  null if the model's reply doesn't parse — single attempt, no
     *  recursion. */
    suspend fun reprompt(model: LanguageModel, messages: List<ModelMessage>): JsonElement? {
        val prompt = toPrompt()
        val result = model.generate(
            LanguageModelCallParams(
                messages = messages + UserMessage(prompt),
                tools = emptyList(),
                toolChoice = ToolChoice.None,
            ),
        )
        val text = stripCodeFences(result.text.trim())
        return TypedJsonOps.parseJsonElementOrNull(aiSdkJson, text)
    }

    fun toPrompt(): String = """
        Your previous call to tool `$toolName` had invalid JSON arguments.

        Failed arguments:
        $failedArgs

        Error: $errorMessage

        Tool input schema:
        $schema

        Reply with ONLY the corrected JSON arguments — no prose, no
        explanation, no markdown code fences. Just the raw JSON object.
    """.trimIndent()

    /** Strip Markdown code fences off a model response so the JSON inside parses cleanly. */
    private fun stripCodeFences(raw: String): String {
        var t = raw
        if (t.startsWith("```json")) t = t.removePrefix("```json").trim()
        if (t.startsWith("```")) t = t.removePrefix("```").trim()
        if (t.endsWith("```")) t = t.removeSuffix("```").trim()
        return t
    }
}
