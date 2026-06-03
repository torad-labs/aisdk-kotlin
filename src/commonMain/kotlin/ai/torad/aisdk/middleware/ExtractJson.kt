package ai.torad.aisdk.middleware

import ai.torad.aisdk.LanguageModelMiddleware
import ai.torad.aisdk.LanguageModelResult
import ai.torad.aisdk.MiddlewareCallContext
import ai.torad.aisdk.PartialJsonState
import ai.torad.aisdk.StreamEvent
import ai.torad.aisdk.parsePartialJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Extracts JSON from text-only model output. For models that wrap their
 * structured response in markdown code fences (` ```json {...} ``` `) or
 * mix prose with the JSON, this strips the surrounding text and surfaces
 * just the JSON object.
 *
 * Mirrors v6's `extractJsonMiddleware`. Useful when [Output] is set on
 * `generateText` but the underlying model doesn't natively support
 * structured output and emits free text — the on-device Gemma path.
 *
 * **Truncation robustness.** A truncated generation (context exhausted,
 * max tokens, cancellation) leaves the JSON region open — `{"a":1`
 * instead of `{"a":1}`. [extractAndRepairJson] runs the extracted
 * region through [parsePartialJson] / `fixJson`, so a cut-off object is
 * repaired into a parseable one rather than failing downstream
 * `Output.decode`. This is the on-device "unstable as context grows"
 * failure mode the repair layer (gap #13) exists to absorb.
 */
fun extractJsonMiddleware(): LanguageModelMiddleware = object : LanguageModelMiddleware {

    override suspend fun wrapGenerate(context: MiddlewareCallContext): LanguageModelResult {
        val raw = context.doGenerate(context.params)
        return raw.copy(text = extractAndRepairJson(raw.text))
    }

    override fun wrapStream(context: MiddlewareCallContext): Flow<StreamEvent> = flow {
        val buffer = StringBuilder()
        var emittedAny = false
        var lastTextId = ""
        context.doStream(context.params).collect { event ->
            when (event) {
                is StreamEvent.TextDelta -> {
                    buffer.append(event.text)
                    lastTextId = event.id
                }
                is StreamEvent.StepFinish, is StreamEvent.Finish -> {
                    val extracted = extractAndRepairJson(buffer.toString())
                    if (extracted.isNotEmpty()) {
                        emit(StreamEvent.TextDelta(lastTextId, extracted))
                        emittedAny = true
                    }
                    emit(event)
                }
                else -> emit(event)
            }
        }
        if (!emittedAny && buffer.isNotEmpty()) {
            emit(StreamEvent.TextDelta(lastTextId, extractAndRepairJson(buffer.toString())))
        }
    }

    /**
     * Strip prose/fences to the JSON region, then repair it if the
     * region is a truncated fragment. A clean parse passes through
     * verbatim (preserves the model's formatting); only a repaired
     * (open) fragment is re-serialized to its closed canonical form.
     */
    private fun extractAndRepairJson(text: String): String {
        val region = extractJsonRegion(text)
        val parsed = parsePartialJson(region)
        if (parsed.state == PartialJsonState.RepairedParse && parsed.value != null) {
            return parsed.value.toString()
        }
        return region
    }

    private fun extractJsonRegion(text: String): String {
        // Strip markdown code fences first.
        val fenced = """```(?:json)?\s*([\s\S]*?)```""".toRegex()
            .find(text)?.groupValues?.get(1)?.trim()
        if (fenced != null && (fenced.startsWith("{") || fenced.startsWith("["))) return fenced

        // Otherwise find the first { or [ and scan to the matching close.
        val openIdx = text.indexOfFirst { it == '{' || it == '[' }
        if (openIdx < 0) return text
        return scanBalanced(text, openIdx)
    }

    private fun scanBalanced(text: String, openIdx: Int): String {
        val openChar = text[openIdx]
        val closeChar = if (openChar == '{') '}' else ']'
        var depth = 0
        var inString = false
        var escaped = false
        for (i in openIdx until text.length) {
            val c = text[i]
            if (inString) {
                // Skip everything inside a string literal — a `}`/`]`/`"`
                // here is data, not structure. Mirrors fixJson's
                // INSIDE_STRING handling so a brace inside a string value
                // doesn't collapse depth and truncate trailing fields.
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                openChar -> depth += 1
                closeChar -> {
                    depth -= 1
                    if (depth == 0) return text.substring(openIdx, i + 1)
                }
            }
        }
        // No matching close — truncated. Keep the open region (prose
        // prefix stripped) so the repair layer can close it; the old
        // `return text` leaked the prose prefix back into the result.
        return text.substring(openIdx)
    }
}
