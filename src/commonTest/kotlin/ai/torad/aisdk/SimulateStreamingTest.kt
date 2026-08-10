@file:OptIn(LowLevelLanguageModelApi::class)

package ai.torad.aisdk

import ai.torad.aisdk.middleware.SimulateStreamingMiddleware
import ai.torad.aisdk.testing.FlowDrain.drainAllItems
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Validates the middleware-shape fix for `simulateStreamingMiddleware`.
 *
 * Before the v6-shape refactor, `wrapStream` only had access to the
 * downstream stream (`next: (params) -> Flow<StreamEvent>`), so this
 * middleware was forced to consume the upstream stream — exactly the
 * call path that doesn't exist on a generate-only model. The file's
 * own comment admitted the bug.
 *
 * After the refactor, the middleware receives a
 * [MiddlewareCallContext] with both `doGenerate` and `doStream`, so
 * it can synthesize a stream from the downstream `generate` result.
 * These tests reproduce the bug scenario (a model whose `stream`
 * throws) and assert the synthesized events flow correctly.
 */
class SimulateStreamingTest {

    /** A model that only supports `generate` — `stream` blows up if
     *  called. Reproduces the situation `simulateStreamingMiddleware`
     *  exists to handle (provider with batch-only API + UI needing
     *  a streaming contract). */
    private class GenerateOnlyModel(
        private val text: String,
        private val toolCalls: List<ContentPart.ToolCall> = emptyList(),
        private val mediaParts: List<ContentPart> = emptyList(),
    ) : LanguageModel {
        override val modelId: String = "test/generate-only"

        override suspend fun generate(params: LanguageModelCallParams): LanguageModelResult =
            LanguageModelResult(
                text = text,
                toolCalls = toolCalls,
                finishReason = FinishReason.Stop,
                usage = Usage(promptTokens = PROMPT_TOK_FIXTURE, completionTokens = text.length),
                content = buildList {
                    if (text.isNotEmpty()) add(ContentPart.Text(text))
                    addAll(mediaParts)
                    addAll(toolCalls)
                },
            )

        override fun stream(params: LanguageModelCallParams): Flow<StreamEvent> = flow {
            fail("GenerateOnlyModel.stream should never be called when simulateStreamingMiddleware is wired correctly")
        }
    }

    @Test
    fun `given a generate-only model when simulateStreamingMiddleware wraps it then stream emits synthesized text events`() =
        runTest {
            // GIVEN
            val model = GenerateOnlyModel(text = "hello world")
            val wrapped = WrapLanguageModel(model, listOf(SimulateStreamingMiddleware()))

            // WHEN
            val events = drainAllItems(
                wrapped.stream(
                    LanguageModelCallParams {
                        messages(listOf(UserMessage("hi")))
                    }
                ),
            )

            // THEN — leads with StreamStart + ResponseMetadata (v6 parity), then the
            // synthesized text segment, then StepFinish / Finish.
            assertEquals(
                7,
                events.size,
                "expected StreamStart / ResponseMetadata / TextStart / TextDelta / TextEnd / StepFinish / Finish",
            )
            events[0] as StreamEvent.StreamStart
            events[1] as StreamEvent.ResponseMetadata
            val textStart = events[2] as StreamEvent.TextStart
            val textDelta = events[3] as StreamEvent.TextDelta
            val textEnd = events[4] as StreamEvent.TextEnd
            val stepFinish = events[5] as StreamEvent.StepFinish
            val finish = events[6] as StreamEvent.Finish
            assertEquals(textStart.id, textDelta.id, "all text events share one id")
            assertEquals(textDelta.id, textEnd.id, "all text events share one id")
            assertEquals("hello world", textDelta.text)
            assertEquals(FinishReason.Stop, stepFinish.finishReason)
            assertEquals(FinishReason.Stop, finish.finishReason)
            assertEquals(1, finish.totalSteps)
        }

    @Test
    fun `given a generate-only model with tool calls when wrapped then ToolCall events emit between text and finish`() =
        runTest {
            // GIVEN
            val toolCall = ContentPart.ToolCall(
                toolCallId = "call_abc",
                toolName = "saveNote",
                input = JsonObject(mapOf("body" to JsonPrimitive("remember the venue map"))),
            )
            val model = GenerateOnlyModel(text = "ok, saving", toolCalls = listOf(toolCall))
            val wrapped = WrapLanguageModel(model, listOf(SimulateStreamingMiddleware()))

            // WHEN
            val events = drainAllItems(
                wrapped.stream(
                    LanguageModelCallParams {
                        messages(listOf(UserMessage("save a note")))
                    }
                ),
            )

            // THEN
            val toolCallEvent = events.filterIsInstance<StreamEvent.ToolCall>().single()
            assertEquals("call_abc", toolCallEvent.toolCallId)
            assertEquals("saveNote", toolCallEvent.toolName)
            // tool-call event lands AFTER TextEnd and BEFORE StepFinish
            val toolCallIdx = events.indexOf(toolCallEvent)
            val textEndIdx = events.indexOfFirst { it is StreamEvent.TextEnd }
            val stepFinishIdx = events.indexOfFirst { it is StreamEvent.StepFinish }
            assertTrue(
                textEndIdx < toolCallIdx,
                "ToolCall must come after TextEnd (textEnd=$textEndIdx toolCall=$toolCallIdx)",
            )
            assertTrue(
                toolCallIdx < stepFinishIdx,
                "ToolCall must come before StepFinish (toolCall=$toolCallIdx stepFinish=$stepFinishIdx)",
            )
        }

    @Test
    fun `given a generate-only model returning empty text when wrapped then no text events emit but step finish still fires`() =
        runTest {
            // GIVEN
            val model = GenerateOnlyModel(text = "")
            val wrapped = WrapLanguageModel(model, listOf(SimulateStreamingMiddleware()))

            // WHEN
            val events = drainAllItems(
                wrapped.stream(
                    LanguageModelCallParams {
                        messages(listOf(UserMessage("hi")))
                    }
                ),
            )

            // THEN
            assertTrue(events.none { it is StreamEvent.TextStart }, "no TextStart for empty text")
            assertTrue(events.none { it is StreamEvent.TextDelta }, "no TextDelta for empty text")
            assertTrue(events.none { it is StreamEvent.TextEnd }, "no TextEnd for empty text")
            assertTrue(events.any { it is StreamEvent.StepFinish }, "StepFinish still emits")
            assertTrue(events.any { it is StreamEvent.Finish }, "Finish still emits")
        }

    @Test
    fun `given a generate-only model producing files sources and images when wrapped then they replay on the stream`() =
        runTest {
            // GIVEN — v6 forwards every non-text, non-reasoning content part verbatim
            // (`simulate-streaming-middleware.ts` default branch); v3's prompt shape has no
            // image part, so an image replays as the stream's file event.
            val model = GenerateOnlyModel(
                text = "here you go",
                mediaParts = listOf(
                    ContentPart.File(mediaType = "application/pdf", base64 = "cGRm"),
                    ContentPart.Source(
                        sourceType = StreamEvent.SourcePart.SourceType.Url,
                        sourceId = "src_1",
                        url = "https://example.test/cite",
                        title = "Citation",
                    ),
                    ContentPart.Image(mediaType = "image/png", base64 = "aW1n"),
                ),
            )
            val wrapped = WrapLanguageModel(model, listOf(SimulateStreamingMiddleware()))

            // WHEN
            val events = drainAllItems(
                wrapped.stream(
                    LanguageModelCallParams {
                        messages(listOf(UserMessage("hi")))
                    }
                ),
            )

            // THEN
            val files = events.filterIsInstance<StreamEvent.FilePart>()
            assertEquals(2, files.size, "both the file part and the image part replay as file events")
            assertEquals("application/pdf", files[0].mediaType)
            assertEquals("cGRm", files[0].base64)
            assertEquals("image/png", files[1].mediaType)
            assertEquals("aW1n", files[1].base64)

            val source = events.filterIsInstance<StreamEvent.SourcePart>().single()
            assertEquals("src_1", source.id)
            assertEquals("https://example.test/cite", source.url)
            assertEquals("Citation", source.title)

            // media replays before the terminal events
            val stepFinishIdx = events.indexOfFirst { it is StreamEvent.StepFinish }
            assertTrue(
                events.indexOf(source) < stepFinishIdx,
                "media events must land before StepFinish",
            )
        }
}

private const val PROMPT_TOK_FIXTURE = 5
