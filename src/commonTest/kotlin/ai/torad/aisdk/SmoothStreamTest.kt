package ai.torad.aisdk

import ai.torad.aisdk.testing.drainAllItems
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest

class SmoothStreamTest {

    @Test
    fun `word_chunking_splits_on_whitespace`() = runTest {
        val events = flow {
            emit(StreamEvent.TextDelta("t_1", "hello world from "))
            emit(StreamEvent.TextDelta("t_1", "app"))
            emit(StreamEvent.TextEnd("t_1"))
        }
        val out = drainAllItems(smoothStream(events, delayMs = 0L))
        val texts = out.filterIsInstance<StreamEvent.TextDelta>().map { it.text }
        assertTrue(texts.size >= 3, "got at least three word chunks")
        val joined = texts.joinToString("")
        assertEquals("hello world from app", joined)
    }

    @Test
    fun `nontext_events_pass_through_immediately`() = runTest {
        val events = flow {
            emit(StreamEvent.ToolCall("c1", "t", kotlinx.serialization.json.JsonObject(emptyMap())))
            emit(StreamEvent.Finish(1, FinishReason.Stop, Usage()))
        }
        val out = drainAllItems(smoothStream(events, delayMs = 0L))
        assertTrue(out.any { it is StreamEvent.ToolCall })
        assertTrue(out.any { it is StreamEvent.Finish })
    }

    @Test
    fun `line_chunking_splits_on_newlines`() = runTest {
        val events = flow {
            emit(StreamEvent.TextDelta("t_1", "line one\nline two\n"))
            emit(StreamEvent.TextEnd("t_1"))
        }
        val out = drainAllItems(smoothStream(events, delayMs = 0L, chunkBy = ChunkBy.Line))
        val texts = out.filterIsInstance<StreamEvent.TextDelta>().map { it.text }
        assertEquals(listOf("line one\n", "line two\n"), texts)
    }

    @Test
    fun `given CJK text without whitespace when smoothed then each ideogram flushes as its own chunk`() =
        runTest {
            // GIVEN — Mandarin / Japanese / Korean don't use whitespace
            // as word separators. The pre-#32 regex `\s*\S+\s+` never
            // matched and held the whole string in the buffer until
            // TextEnd, freezing typing-cursor UX. Per historical parity work
            // gap #32, the alternation in WORD_REGEX now treats each
            // CJK code point as a chunk boundary.
            val events = flow<StreamEvent> {
                emit(StreamEvent.TextDelta("t_1", "你好世界"))
                emit(StreamEvent.TextEnd("t_1"))
            }

            // WHEN
            val out = drainAllItems(smoothStream(events, delayMs = 0L))
            val texts = out.filterIsInstance<StreamEvent.TextDelta>().map { it.text }

            // THEN — four ideograms flush as four separate chunks.
            assertEquals(listOf("你", "好", "世", "界"), texts)
        }

    @Test
    fun `given mixed Latin and CJK text when smoothed then the joined output preserves the input`() =
        runTest {
            // GIVEN — when a CJK char is space-separated, the latin
            // word regex grabs it INCLUDING the surrounding whitespace
            // (one chunk " 你 "). Only contiguous CJK without
            // whitespace separators triggers the per-char alternation.
            // For UX this is fine: spaces around CJK are typing-rhythm
            // friendly either way.
            val events = flow<StreamEvent> {
                emit(StreamEvent.TextDelta("t_1", "Hello 你好 World"))
                emit(StreamEvent.TextEnd("t_1"))
            }

            // WHEN
            val out = drainAllItems(smoothStream(events, delayMs = 0L))
            val texts = out.filterIsInstance<StreamEvent.TextDelta>().map { it.text }

            // THEN — the join preserves input; the contiguous 你好
            // chunk emits as two CJK chunks.
            assertEquals("Hello 你好 World", texts.joinToString(""))
            assertTrue(texts.contains("你"), "first CJK ideogram emitted as standalone chunk")
            assertTrue(texts.contains("好"), "second CJK ideogram emitted as standalone chunk")
        }

    @Test
    fun `given Hiragana and Katakana text when smoothed then each kana char flushes`() =
        runTest {
            // GIVEN — Japanese kana also needs per-char chunking.
            val events = flow<StreamEvent> {
                emit(StreamEvent.TextDelta("t_1", "こんにちは"))
                emit(StreamEvent.TextEnd("t_1"))
            }

            // WHEN
            val out = drainAllItems(smoothStream(events, delayMs = 0L))
            val texts = out.filterIsInstance<StreamEvent.TextDelta>().map { it.text }

            // THEN
            assertEquals(listOf("こ", "ん", "に", "ち", "は"), texts)
        }
}
