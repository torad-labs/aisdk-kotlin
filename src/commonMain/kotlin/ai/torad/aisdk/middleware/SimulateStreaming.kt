package ai.torad.aisdk.middleware

import ai.torad.aisdk.LanguageModelMiddleware
import ai.torad.aisdk.MiddlewareCallContext
import ai.torad.aisdk.StreamEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Wraps a non-streaming model into a fake streaming surface. Useful when
 * a provider's batch-only `generate` is the only path available and the
 * UI needs a stream contract.
 *
 * Mirrors v6's `simulateStreamingMiddleware`. Calls the downstream
 * generate (NOT the downstream stream), then synthesizes a stream:
 *   1. `TextStart` → `TextDelta(fullText)` → `TextEnd` (if text is non-empty)
 *   2. One `ToolCall` per `result.toolCalls`
 *   3. `StepFinish` + `Finish` with the result's finish reason + usage
 *
 * Per v6 conventions, all synthesized text events share a single `id`
 * (`"sim_text"`) since the simulation produces exactly one text segment.
 */
public fun simulateStreamingMiddleware(): LanguageModelMiddleware = object : LanguageModelMiddleware {
    override fun wrapStream(context: MiddlewareCallContext): Flow<StreamEvent> = flow {
        // The fix: call doGenerate, NOT doStream. The downstream model may
        // not even implement stream — that's the whole reason for this
        // middleware. The old shape exposed only the same-direction `next`,
        // which forced this to consume the (broken) stream — see the
        // pre-refactor file history.
        val result = context.doGenerate(context.params)
        val textId = SIMULATED_TEXT_ID
        if (result.text.isNotEmpty()) {
            emit(StreamEvent.TextStart(textId))
            emit(StreamEvent.TextDelta(textId, result.text))
            emit(StreamEvent.TextEnd(textId))
        }
        for (call in result.toolCalls) {
            emit(
                StreamEvent.ToolCall(
                    toolCallId = call.toolCallId,
                    toolName = call.toolName,
                    inputJson = call.input,
                ),
            )
        }
        emit(
            StreamEvent.StepFinish(
                stepNumber = 1,
                finishReason = result.finishReason,
                usage = result.usage,
            ),
        )
        emit(
            StreamEvent.Finish(
                totalSteps = 1,
                finishReason = result.finishReason,
                usage = result.usage,
            ),
        )
    }
}

private const val SIMULATED_TEXT_ID = "sim_text"
