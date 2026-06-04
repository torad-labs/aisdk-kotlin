package ai.torad.aisdk

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Streaming-safe partial-JSON repair + parse. Faithful port of Vercel
 * AI SDK v6 `packages/ai/src/util/fix-json.ts` + `parse-partial-json.ts`.
 *
 * [fixJson] completes a truncated/partial JSON string so it parses:
 * a single linear forward scan with a state stack tracks the last index
 * that forms a valid prefix (truncating dangling junk like a trailing
 * `-`, `.`, `e`, `,`, or partial key), then walks the open-frame stack
 * top-to-bottom appending the missing closers (`"`, `}`, `]`) and literal
 * tails (`tru` -> `true`). It does NOT validate JSON — a real parse runs
 * afterward.
 *
 * [parsePartialJson] is the consumer: parse the raw text; on failure run
 * [fixJson] then parse; report which path succeeded (or that both failed).
 * This is what lets streaming structured output render incrementally
 * instead of buffering the whole object to end-of-stream.
 */

// Pure scanner state tags (no payload) — an enum is the natural fit.
private enum class FixJsonState {
    ROOT,
    FINISH,
    INSIDE_STRING,
    INSIDE_STRING_ESCAPE,
    INSIDE_LITERAL,
    INSIDE_NUMBER,
    INSIDE_OBJECT_START,
    INSIDE_OBJECT_KEY,
    INSIDE_OBJECT_AFTER_KEY,
    INSIDE_OBJECT_BEFORE_VALUE,
    INSIDE_OBJECT_AFTER_VALUE,
    INSIDE_OBJECT_AFTER_COMMA,
    INSIDE_ARRAY_START,
    INSIDE_ARRAY_AFTER_VALUE,
    INSIDE_ARRAY_AFTER_COMMA,
}

private const val LITERAL_FALSE = "false"
private const val LITERAL_TRUE = "true"
private const val LITERAL_NULL = "null"

private fun isLiteralPrefix(partial: String): Boolean =
    LITERAL_FALSE.startsWith(partial) ||
        LITERAL_TRUE.startsWith(partial) ||
        LITERAL_NULL.startsWith(partial)

/**
 * Complete a partial/truncated JSON string so `Json.parseToJsonElement`
 * can read it. Returns `""` when nothing valid was scanned (e.g. a lone
 * `-`). See [parsePartialJson] for the parse wrapper.
 */
@Suppress("CyclomaticComplexMethod", "LongMethod")
public fun fixJson(input: String): String {
    val stack = ArrayDeque<FixJsonState>().apply { addLast(FixJsonState.ROOT) }
    var lastValidIndex = -1
    var literalStart: Int? = null

    // "A value can begin here": pop the expecting-value state, push the
    // continuation (swapState) we return to once the value completes,
    // then push the value's own state on top.
    fun processValueStart(char: Char, i: Int, swapState: FixJsonState) {
        fun push(value: FixJsonState) {
            stack.removeLast()
            stack.addLast(swapState)
            stack.addLast(value)
        }
        when (char) {
            '"' -> {
                lastValidIndex = i
                push(FixJsonState.INSIDE_STRING)
            }
            'f', 't', 'n' -> {
                lastValidIndex = i
                literalStart = i
                push(FixJsonState.INSIDE_LITERAL)
            }
            // A lone '-' is not yet a valid prefix — do NOT advance lastValidIndex.
            '-' -> push(FixJsonState.INSIDE_NUMBER)
            in '0'..'9' -> {
                lastValidIndex = i
                push(FixJsonState.INSIDE_NUMBER)
            }
            '{' -> {
                lastValidIndex = i
                push(FixJsonState.INSIDE_OBJECT_START)
            }
            '[' -> {
                lastValidIndex = i
                push(FixJsonState.INSIDE_ARRAY_START)
            }
            // else (whitespace before the value) — no-op.
        }
    }

    fun processAfterObjectValue(char: Char, i: Int) {
        when (char) {
            ',' -> {
                stack.removeLast()
                stack.addLast(FixJsonState.INSIDE_OBJECT_AFTER_COMMA)
            }
            '}' -> {
                lastValidIndex = i
                stack.removeLast()
            }
        }
    }

    fun processAfterArrayValue(char: Char, i: Int) {
        when (char) {
            ',' -> {
                stack.removeLast()
                stack.addLast(FixJsonState.INSIDE_ARRAY_AFTER_COMMA)
            }
            ']' -> {
                lastValidIndex = i
                stack.removeLast()
            }
        }
    }

    fun processNumber(char: Char, i: Int) {
        when (char) {
            in '0'..'9' -> lastValidIndex = i
            // valid mid-number chars but not valid number ENDINGS — a
            // number ending on them truncates back to the last digit.
            'e', 'E', '-', '.' -> Unit
            ',' -> {
                stack.removeLast()
                when (stack.last()) {
                    FixJsonState.INSIDE_ARRAY_AFTER_VALUE -> processAfterArrayValue(char, i)
                    FixJsonState.INSIDE_OBJECT_AFTER_VALUE -> processAfterObjectValue(char, i)
                    else -> Unit
                }
            }
            '}' -> {
                stack.removeLast()
                if (stack.last() == FixJsonState.INSIDE_OBJECT_AFTER_VALUE) {
                    processAfterObjectValue(char, i)
                }
            }
            ']' -> {
                stack.removeLast()
                if (stack.last() == FixJsonState.INSIDE_ARRAY_AFTER_VALUE) {
                    processAfterArrayValue(char, i)
                }
            }
            // whitespace etc. — the number ends; its last digit already set lastValidIndex.
            else -> stack.removeLast()
        }
    }

    fun processLiteral(char: Char, i: Int) {
        val start = requireNotNull(literalStart) { "literalStart must be set in INSIDE_LITERAL state" }
        val partialLiteral = input.substring(start, i + 1)
        if (isLiteralPrefix(partialLiteral)) {
            lastValidIndex = i
            return
        }
        // The literal ended at the previous char — re-dispatch this one.
        stack.removeLast()
        when (stack.last()) {
            FixJsonState.INSIDE_OBJECT_AFTER_VALUE -> processAfterObjectValue(char, i)
            FixJsonState.INSIDE_ARRAY_AFTER_VALUE -> processAfterArrayValue(char, i)
            else -> Unit
        }
    }

    fun processObjectStart(char: Char, i: Int) {
        when (char) {
            '"' -> {
                stack.removeLast()
                stack.addLast(FixJsonState.INSIDE_OBJECT_KEY)
            }
            '}' -> {
                lastValidIndex = i
                stack.removeLast()
            }
        }
    }

    fun processString(char: Char, i: Int) {
        when (char) {
            '"' -> {
                stack.removeLast()
                lastValidIndex = i
            }
            '\\' -> stack.addLast(FixJsonState.INSIDE_STRING_ESCAPE)
            else -> lastValidIndex = i
        }
    }

    fun processArrayStart(char: Char, i: Int) {
        if (char == ']') {
            lastValidIndex = i
            stack.removeLast()
            return
        }
        lastValidIndex = i
        processValueStart(char, i, FixJsonState.INSIDE_ARRAY_AFTER_VALUE)
    }

    fun processArrayAfterValue(char: Char, i: Int) {
        when (char) {
            ',' -> {
                stack.removeLast()
                stack.addLast(FixJsonState.INSIDE_ARRAY_AFTER_COMMA)
            }
            ']' -> {
                lastValidIndex = i
                stack.removeLast()
            }
            else -> lastValidIndex = i
        }
    }

    for (i in input.indices) {
        val char = input[i]
        when (stack.last()) {
            FixJsonState.ROOT -> processValueStart(char, i, FixJsonState.FINISH)
            FixJsonState.INSIDE_OBJECT_START -> processObjectStart(char, i)
            FixJsonState.INSIDE_OBJECT_AFTER_COMMA -> if (char == '"') {
                stack.removeLast()
                stack.addLast(FixJsonState.INSIDE_OBJECT_KEY)
            }
            FixJsonState.INSIDE_OBJECT_KEY -> if (char == '"') {
                stack.removeLast()
                stack.addLast(FixJsonState.INSIDE_OBJECT_AFTER_KEY)
            }
            FixJsonState.INSIDE_OBJECT_AFTER_KEY -> if (char == ':') {
                stack.removeLast()
                stack.addLast(FixJsonState.INSIDE_OBJECT_BEFORE_VALUE)
            }
            FixJsonState.INSIDE_OBJECT_BEFORE_VALUE ->
                processValueStart(char, i, FixJsonState.INSIDE_OBJECT_AFTER_VALUE)
            FixJsonState.INSIDE_OBJECT_AFTER_VALUE -> processAfterObjectValue(char, i)
            FixJsonState.INSIDE_STRING -> processString(char, i)
            FixJsonState.INSIDE_STRING_ESCAPE -> {
                stack.removeLast()
                lastValidIndex = i
            }
            FixJsonState.INSIDE_ARRAY_START -> processArrayStart(char, i)
            FixJsonState.INSIDE_ARRAY_AFTER_VALUE -> processArrayAfterValue(char, i)
            FixJsonState.INSIDE_ARRAY_AFTER_COMMA ->
                processValueStart(char, i, FixJsonState.INSIDE_ARRAY_AFTER_VALUE)
            FixJsonState.INSIDE_NUMBER -> processNumber(char, i)
            FixJsonState.INSIDE_LITERAL -> processLiteral(char, i)
            FixJsonState.FINISH -> Unit
        }
    }

    var result = if (lastValidIndex == -1) "" else input.substring(0, lastValidIndex + 1)

    // Stack-drain: close innermost frame first (walk top -> bottom).
    for (idx in stack.indices.reversed()) {
        result += drainClose(stack[idx], input, literalStart)
    }

    return result
}

/** The closer appended for one open stack frame during the drain phase. */
private fun drainClose(state: FixJsonState, input: String, literalStart: Int?): String = when (state) {
    FixJsonState.INSIDE_STRING -> "\""
    FixJsonState.INSIDE_OBJECT_KEY,
    FixJsonState.INSIDE_OBJECT_AFTER_KEY,
    FixJsonState.INSIDE_OBJECT_AFTER_COMMA,
    FixJsonState.INSIDE_OBJECT_START,
    FixJsonState.INSIDE_OBJECT_BEFORE_VALUE,
    FixJsonState.INSIDE_OBJECT_AFTER_VALUE,
    -> "}"
    FixJsonState.INSIDE_ARRAY_START,
    FixJsonState.INSIDE_ARRAY_AFTER_COMMA,
    FixJsonState.INSIDE_ARRAY_AFTER_VALUE,
    -> "]"
    FixJsonState.INSIDE_LITERAL -> literalTail(input.substring(requireNotNull(literalStart), input.length))
    // ROOT, FINISH, INSIDE_STRING_ESCAPE, INSIDE_NUMBER -> nothing.
    else -> ""
}

private fun literalTail(partial: String): String = when {
    LITERAL_TRUE.startsWith(partial) -> LITERAL_TRUE.substring(partial.length)
    LITERAL_FALSE.startsWith(partial) -> LITERAL_FALSE.substring(partial.length)
    LITERAL_NULL.startsWith(partial) -> LITERAL_NULL.substring(partial.length)
    else -> ""
}

/** Outcome of [parsePartialJson], mirroring v6's four state strings. */
public enum class PartialJsonState { UndefinedInput, SuccessfulParse, RepairedParse, FailedParse }

/** Result of [parsePartialJson]: [value] is non-null only on the two
 *  success states. */
public data class PartialJsonResult(val value: JsonElement?, val state: PartialJsonState)

private val partialJsonCodec: Json = Json { ignoreUnknownKeys = true }

// kotlinx parsing is more lenient than JS `JSON.parse`: `parseToJsonElement("")`
// yields JsonNull, and a top-level bareword like `garbage` parses to an
// unquoted JsonPrimitive — both of which JS rejects. Treat blank input
// and unquoted non-literal primitives as unparseable so empty / garbage /
// unrepairable input lands on FailedParse, matching v6.
private fun tryParseJson(text: String): JsonElement? {
    if (text.isBlank()) return null
    val parsed = runCatching { partialJsonCodec.parseToJsonElement(text) }.getOrNull() ?: return null
    return parsed.takeIf(::isStrictJsonValue)
}

private fun isStrictJsonValue(element: JsonElement): Boolean = when (element) {
    is JsonObject, is JsonArray -> true
    is JsonPrimitive ->
        element.isString ||
            element.content == "true" ||
            element.content == "false" ||
            element.content == "null" ||
            // isFinite() rejects NaN / Infinity / -Infinity / 1e999 (which
            // overflows to Infinity) — `toDoubleOrNull` accepts them but JS
            // `JSON.parse` throws on all of them, so they must FailedParse.
            element.content.toDoubleOrNull()?.isFinite() == true
}

/**
 * Parse possibly-partial JSON. Mirrors Vercel AI SDK v6 `parsePartialJson`:
 *  - `null` input -> [PartialJsonState.UndefinedInput] (note: an empty/blank
 *    string is NOT undefined-input — it falls through and fails).
 *  - raw text parses -> [PartialJsonState.SuccessfulParse].
 *  - [fixJson]-repaired text parses -> [PartialJsonState.RepairedParse].
 *  - otherwise -> [PartialJsonState.FailedParse].
 *
 * The repair runs on the ORIGINAL raw text, not on intermediate output.
 */
public fun parsePartialJson(jsonText: String?): PartialJsonResult {
    if (jsonText == null) return PartialJsonResult(null, PartialJsonState.UndefinedInput)
    tryParseJson(jsonText)?.let { return PartialJsonResult(it, PartialJsonState.SuccessfulParse) }
    val repaired = tryParseJson(fixJson(jsonText))
    return if (repaired != null) {
        PartialJsonResult(repaired, PartialJsonState.RepairedParse)
    } else {
        PartialJsonResult(null, PartialJsonState.FailedParse)
    }
}
