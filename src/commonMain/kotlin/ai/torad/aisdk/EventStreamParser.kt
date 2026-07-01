package ai.torad.aisdk

import dev.drewhamilton.poko.Poko
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** @since 0.3.0-beta01 */
public sealed class ParseResult<out T> {
    @Poko
    /** @since 0.3.0-beta01 */
    public class Success<T>(public val value: T) : ParseResult<T>()

    @Poko
    /** @since 0.3.0-beta01 */
    public class Failure(public val error: Throwable, public val text: String) : ParseResult<Nothing>()
}

internal object EventStreamParser {

    // SSE line prefixes the parser treats as framing (data: it consumes; the rest it ignores). Used
    // only to distinguish a genuinely non-SSE body from a normal stream when nothing was emitted.
    private val sseFieldPrefixes = listOf("data:", "event:", "id:", "retry:", ":")

    // A streaming SSE line-state machine (framing + the non-SSE-body detection): branchy by nature,
    // and detekt sums its local flush/recordNonSse/processLine helpers into the count.
    @Suppress("CyclomaticComplexMethod")
    public fun <T> parse(
        chunks: Flow<String>,
        schema: Schema<T>,
        json: Json = Json,
    ): Flow<ParseResult<T>> = flow {
        var buffer = ""
        var eventData = mutableListOf<String>()
        var emittedAny = false
        // Non-blank lines that are NOT SSE framing (a plain JSON error envelope / HTML interstitial
        // returned with a 200 status). Retained so such a body can be surfaced, not swallowed.
        val nonSseContent = StringBuilder()
        suspend fun flush() {
            if (eventData.isEmpty()) return
            emittedAny = true // a data: event was framed (even [DONE]) — this body IS an SSE stream
            val data = eventData.joinToString("\n")
            eventData = mutableListOf()
            // Blank joined data (a lone empty `data:`) is valid, payload-less SSE — a no-op, NOT a
            // parse error. Only [DONE] is likewise skipped.
            if (data.isNotBlank() && data.trim() != "[DONE]") emit(safeParseJson(data, schema, json))
        }
        fun recordNonSse(line: String) {
            if (sseFieldPrefixes.any { line.startsWith(it) }) return
            if (nonSseContent.isNotEmpty()) nonSseContent.append('\n')
            nonSseContent.append(line)
        }
        suspend fun processLine(rawLine: String) {
            when {
                rawLine.isEmpty() -> flush()
                rawLine.startsWith("data:") -> eventData += rawLine.removePrefix("data:").trimStart()
                else -> recordNonSse(rawLine)
            }
        }
        chunks.collect { chunk ->
            buffer += chunk
            while (true) {
                val newline = buffer.indexOf('\n')
                if (newline < 0) break
                val rawLine = buffer.substring(0, newline).removeSuffix("\r")
                buffer = buffer.substring(newline + 1)
                processLine(rawLine)
            }
        }
        if (buffer.isNotEmpty()) processLine(buffer.removeSuffix("\r"))
        flush()
        // A 2xx body that produced no SSE event at all yet carried content is an upstream error
        // delivered as a non-SSE payload — surface it instead of completing as a silent empty stream.
        if (!emittedAny && nonSseContent.isNotBlank()) {
            emit(
                ParseResult.Failure(
                    SerializationException("Response body was not an SSE stream"),
                    nonSseContent.toString(),
                ),
            )
        }
    }

    internal fun <T> parse(
        text: String,
        schema: Schema<T>,
        json: Json = Json,
    ): List<ParseResult<T>> =
        serverSentEventData(text).mapNotNull { data ->
            // Blank data (a lone empty `data:`) is payload-less SSE — skip it; parsing "" would
            // wrongly yield a ParseResult.Failure. Mirrors the Flow overload above.
            if (data.isBlank() || data.trim() == "[DONE]") null else safeParseJson(data, schema, json)
        }

    private fun serverSentEventData(text: String): List<String> {
        val events = mutableListOf<String>()
        val current = mutableListOf<String>()
        fun flush() {
            if (current.isNotEmpty()) {
                events += current.joinToString("\n")
                current.clear()
            }
        }
        text.lineSequence().forEach { raw ->
            val line = raw.removeSuffix("\r")
            when {
                line.isEmpty() -> flush()
                line.startsWith("data:") -> current += line.removePrefix("data:").trimStart()
            }
        }
        flush()
        return events
    }

    private fun <T> safeParseJson(text: String, schema: Schema<T>, json: Json): ParseResult<T> =
        try {
            val element = json.parseToJsonElement(text)
            @Suppress("UNCHECKED_CAST")
            ParseResult.Success(schema.validate?.invoke(element) ?: (element as T))
        } catch (error: SerializationException) {
            ParseResult.Failure(error, text)
        } catch (error: IllegalArgumentException) {
            ParseResult.Failure(error, text)
        }
}
