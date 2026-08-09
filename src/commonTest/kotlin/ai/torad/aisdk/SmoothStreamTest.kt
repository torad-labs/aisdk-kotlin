package ai.torad.aisdk

import ai.torad.aisdk.testing.FlowDrain.drainAllItems
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SmoothStreamTest {

    @Test
    fun `word_chunking_splits_on_whitespace`() = runTest {
        val events = flow {
            emit(StreamEvent.TextDelta("t_1", "hello world from "))
            emit(StreamEvent.TextDelta("t_1", "app"))
            emit(StreamEvent.TextEnd("t_1"))
        }
        val out = drainAllItems(SmoothStream(events, delayMs = 0L))
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
        val out = drainAllItems(SmoothStream(events, delayMs = 0L))
        assertTrue(out.any { it is StreamEvent.ToolCall })
        assertTrue(out.any { it is StreamEvent.Finish })
    }

    @Test
    fun `terminal event flushes buffered text before finish`() = runTest {
        val events = flow {
            emit(StreamEvent.TextDelta("t_1", "done"))
            emit(StreamEvent.Finish(1, FinishReason.Stop, Usage()))
        }
        val out = drainAllItems(SmoothStream(events, delayMs = 0L))

        assertEquals(
            listOf(StreamEvent.TextDelta::class, StreamEvent.Finish::class),
            out.map { it::class },
        )
        assertEquals("done", out.filterIsInstance<StreamEvent.TextDelta>().single().text)
    }

    @Test
    fun `non-text event flushes buffered text before passthrough`() = runTest {
        val events = flow {
            emit(StreamEvent.TextDelta("t_1", "partial"))
            emit(StreamEvent.SourcePart("s_1", StreamEvent.SourcePart.SourceType.Url, url = "https://example.test"))
        }
        val out = drainAllItems(SmoothStream(events, delayMs = 0L))

        assertEquals(
            listOf(StreamEvent.TextDelta::class, StreamEvent.SourcePart::class),
            out.map { it::class },
        )
        assertEquals("partial", out.filterIsInstance<StreamEvent.TextDelta>().single().text)
    }

    @Test
    fun `line_chunking_splits_on_newlines`() = runTest {
        val events = flow {
            emit(StreamEvent.TextDelta("t_1", "line one\nline two\n"))
            emit(StreamEvent.TextEnd("t_1"))
        }
        val out = drainAllItems(SmoothStream(events, delayMs = 0L, chunkBy = ChunkBy.Line))
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
            val out = drainAllItems(SmoothStream(events, delayMs = 0L))
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
            val out = drainAllItems(SmoothStream(events, delayMs = 0L))
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
            val out = drainAllItems(SmoothStream(events, delayMs = 0L))
            val texts = out.filterIsInstance<StreamEvent.TextDelta>().map { it.text }

            // THEN
            assertEquals(listOf("こ", "ん", "に", "ち", "は"), texts)
        }

    @Test
    fun `given latin text directly followed by CJK when smoothed then chunks flush before block end`() =
        runTest {
            // GIVEN — "iPhone搭載" shaped mixed-script output is ubiquitous in Japanese responses.
            // The latin branch required TRAILING whitespace, so with a CJK char directly after a
            // latin run neither alternation could match at offset 0; flushText's offset guard then
            // broke on every invocation and the whole block sat in the buffer until TextEnd —
            // byte-for-byte the pre-#32 jitter the CJK rewrite exists to remove.
            val events = flow<StreamEvent> {
                emit(StreamEvent.TextDelta("t_1", "iPhone搭載の"))
                emit(StreamEvent.TextDelta("t_1", "AIモデル"))
                emit(StreamEvent.TextEnd("t_1"))
            }

            // WHEN
            val out = drainAllItems(SmoothStream(events, delayMs = 0L))
            val texts = out.filterIsInstance<StreamEvent.TextDelta>().map { it.text }

            // THEN — paced chunks, not one giant delta at block end.
            assertEquals("iPhone搭載のAIモデル", texts.joinToString(""))
            assertTrue(texts.size > 1, "chunking stalled; whole block arrived as $texts")
            assertEquals("iPhone", texts.first(), "the latin run before the CJK boundary is its own chunk")
        }

    @Test
    fun `given a reasoning delta carrying provider metadata when re-chunked then the metadata survives`() =
        runTest {
            // GIVEN — Bedrock and Google attach the thinking thought-signature to ReasoningDelta
            // metadata ONLY (ReasoningEnd is bare), and replay reads it back off the assembled
            // ContentPart.Reasoning. Re-chunking without it silently unsigns the block.
            val signature = ProviderMetadata(
                "bedrock" to buildJsonObject { put("signature", JsonPrimitive("sig-1")) },
            )
            val events = flow<StreamEvent> {
                emit(StreamEvent.ReasoningStart("r_1"))
                emit(StreamEvent.ReasoningDelta("r_1", "thinking out loud ", signature))
                emit(StreamEvent.ReasoningEnd("r_1"))
            }

            // WHEN
            val out = drainAllItems(SmoothStream(events, delayMs = 0L))
            val deltas = out.filterIsInstance<StreamEvent.ReasoningDelta>()

            // THEN
            assertEquals("thinking out loud ", deltas.joinToString("") { it.text })
            var merged: ProviderMetadata = ProviderMetadata.None
            deltas.forEach { merged += it.providerMetadata }
            assertEquals(signature, merged)
        }

    @Test
    fun `given a metadata-only reasoning delta when smoothed then it is not swallowed`() =
        runTest {
            // GIVEN — a signature-only delta with empty text: its text appended nothing, so nothing
            // was ever re-emitted for it and the metadata disappeared with it.
            val signature = ProviderMetadata(
                "bedrock" to buildJsonObject { put("signature", JsonPrimitive("sig-2")) },
            )
            val events = flow<StreamEvent> {
                emit(StreamEvent.ReasoningStart("r_1"))
                emit(StreamEvent.ReasoningDelta("r_1", "done"))
                emit(StreamEvent.ReasoningDelta("r_1", "", signature))
                emit(StreamEvent.ReasoningEnd("r_1"))
            }

            // WHEN
            val out = drainAllItems(SmoothStream(events, delayMs = 0L))
            val deltas = out.filterIsInstance<StreamEvent.ReasoningDelta>()

            // THEN
            assertEquals("done", deltas.joinToString("") { it.text })
            var merged: ProviderMetadata = ProviderMetadata.None
            deltas.forEach { merged += it.providerMetadata }
            assertEquals(signature, merged)
        }
}
