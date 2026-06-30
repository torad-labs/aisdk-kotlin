@file:OptIn(LowLevelLanguageModelApi::class)

package ai.torad.aisdk

import ai.torad.aisdk.middleware.SimulateStreamingMiddleware
import ai.torad.aisdk.testing.FlowDrain.drainAllItems
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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
    ) : LanguageModel {
        override val modelId: String = "test/generate-only"

        override suspend fun generate(params: LanguageModelCallParams): LanguageModelResult =
            LanguageModelResult(
                text = text,
                toolCalls = toolCalls,
                finishReason = FinishReason.Stop,
                usage = Usage.of(promptTokens = PROMPT_TOK_FIXTURE, completionTokens = text.length),
            )

        override fun stream(params: LanguageModelCallParams): Flow<StreamEvent> = flow {
            error("GenerateOnlyModel.stream should never be called when simulateStreamingMiddleware is wired correctly")
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
                wrapped.stream(LanguageModelCallParams(messages = listOf(UserMessage("hi")))),
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
                wrapped.stream(LanguageModelCallParams(messages = listOf(UserMessage("save a note")))),
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
                wrapped.stream(LanguageModelCallParams(messages = listOf(UserMessage("hi")))),
            )

            // THEN
            assertTrue(events.none { it is StreamEvent.TextStart }, "no TextStart for empty text")
            assertTrue(events.none { it is StreamEvent.TextDelta }, "no TextDelta for empty text")
            assertTrue(events.none { it is StreamEvent.TextEnd }, "no TextEnd for empty text")
            assertTrue(events.any { it is StreamEvent.StepFinish }, "StepFinish still emits")
            assertTrue(events.any { it is StreamEvent.Finish }, "Finish still emits")
        }
}

private const val PROMPT_TOK_FIXTURE = 5
