package ai.torad.aisdk

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flow

/**
 * Smooths text/reasoning streaming output by buffering until a chunk
 * boundary, then releasing complete chunks with a configurable delay.
 *
 * Mirrors v6's `smoothStream`. Critical UX helper for on-device models
 * (LiteRT-LM on Android) that emit raw tokens fast enough to feel
 * jittery — break by word boundaries, sleep `delayMs` between chunks,
 * the chat surface gets natural reading rhythm.
 *
 * Three chunking strategies:
 *   - [ChunkBy.Word] (default) — split on whitespace
 *   - [ChunkBy.Line] — split on newlines
 *   - [ChunkBy.Pattern] — custom Regex
 *
 * Non-text events (tool calls, step-finish, etc.) pass through immediately
 * without delay so the loop logic isn't slowed down.
 */
public sealed interface ChunkBy {
    public data object Word : ChunkBy
    public data object Line : ChunkBy
    public data class Pattern(val regex: Regex) : ChunkBy
}

// Word-boundary regex with CJK awareness (per AISDK_PORT_GAPS.md gap #32).
// v6 uses Intl.Segmenter to handle scripts without whitespace word separators
// (Chinese, Japanese, Korean ideograms). Kotlin has no Segmenter equivalent
// in commonMain — instead we EXCLUDE CJK from the latin-word path and add
// an alternation that emits each CJK code point as its own chunk.
//
// Without the exclusion, the original `\s*\S+\s+` greedily matched "你好"
// together (and even " 你好 " with surrounding whitespace) because CJK
// chars are `\S`. The first capture group below uses a negative character
// class so latin/digit words don't swallow CJK runs.
//
// Unicode ranges covered for CJK:
//   U+4E00–U+9FFF  CJK Unified Ideographs (Han)
//   U+3040–U+309F  Hiragana
//   U+30A0–U+30FF  Katakana
//   U+AC00–U+D7AF  Hangul Syllables
private val WORD_REGEX = Regex(
    """\s*[^\s一-鿿぀-ゟ゠-ヿ가-힯]+\s+|\s*[一-鿿぀-ゟ゠-ヿ가-힯]""",
    RegexOption.MULTILINE,
)
private val LINE_REGEX = Regex("""[^\n]*\n""", RegexOption.MULTILINE)

// Catches Throwable intentionally: any upstream failure must still flush the
// partial text/reasoning buffers before propagating (CancellationException is
// rethrown first, above), so dropping to a narrower type would lose data.
@Suppress("TooGenericExceptionCaught", "CyclomaticComplexMethod")
public fun smoothStream(
    upstream: Flow<StreamEvent>,
    delayMs: Long = 10L,
    chunkBy: ChunkBy = ChunkBy.Word,
): Flow<StreamEvent> = flow {
    val regex = when (chunkBy) {
        ChunkBy.Word -> WORD_REGEX
        ChunkBy.Line -> LINE_REGEX
        is ChunkBy.Pattern -> chunkBy.regex
    }
    val textBuffers = mutableMapOf<String, StringBuilder>()
    val reasoningBuffers = mutableMapOf<String, StringBuilder>()

    suspend fun flushText(id: String, all: Boolean = false) {
        val buf = textBuffers[id] ?: return
        if (all) {
            if (buf.isNotEmpty()) {
                emit(StreamEvent.TextDelta(id, buf.toString()))
                buf.clear()
            }
            return
        }
        while (true) {
            val match = regex.find(buf) ?: break
            if (match.range.first != 0) break
            val chunk = match.value
            emit(StreamEvent.TextDelta(id, chunk))
            buf.deleteRange(0, chunk.length)
            if (delayMs > 0) delay(delayMs)
        }
    }

    suspend fun flushReasoning(id: String, all: Boolean = false) {
        val buf = reasoningBuffers[id] ?: return
        if (all) {
            if (buf.isNotEmpty()) {
                emit(StreamEvent.ReasoningDelta(id, buf.toString()))
                buf.clear()
            }
            return
        }
        while (true) {
            val match = regex.find(buf) ?: break
            if (match.range.first != 0) break
            val chunk = match.value
            emit(StreamEvent.ReasoningDelta(id, chunk))
            buf.deleteRange(0, chunk.length)
            if (delayMs > 0) delay(delayMs)
        }
    }

    // .buffer() decouples upstream token production from the artificial UI-pacing
    // delay()s in flushText/flushReasoning: without it, the delay back-pressures the
    // provider while we collect. The default (suspending, unbounded-ish) buffer means
    // no token loss — fast on-device models keep producing while we pace the UI.
    var upstreamError: Throwable? = null
    try {
        upstream.buffer().collect { event ->
            when (event) {
                is StreamEvent.TextDelta -> {
                    textBuffers.getOrPut(event.id) { StringBuilder() }.append(event.text)
                    flushText(event.id)
                }
                is StreamEvent.TextEnd -> {
                    flushText(event.id, all = true)
                    textBuffers.remove(event.id)
                    emit(event)
                }
                is StreamEvent.ReasoningDelta -> {
                    reasoningBuffers.getOrPut(event.id) { StringBuilder() }.append(event.text)
                    flushReasoning(event.id)
                }
                is StreamEvent.ReasoningEnd -> {
                    flushReasoning(event.id, all = true)
                    reasoningBuffers.remove(event.id)
                    emit(event)
                }
                else -> emit(event)
            }
        }
    } catch (ce: CancellationException) {
        // Re-throw immediately — the scope is already cancelled, so the
        // flush delays below would throw again anyway. Partial buffers are
        // intentionally not emitted under cancellation.
        throw ce
    } catch (t: Throwable) {
        upstreamError = t
    }
    // Flush any remaining partial buffers on error or normal completion so
    // partial tokens accumulated before the failure are not silently dropped.
    for (id in textBuffers.keys.toList()) flushText(id, all = true)
    for (id in reasoningBuffers.keys.toList()) flushReasoning(id, all = true)
    upstreamError?.let { throw it }
}
