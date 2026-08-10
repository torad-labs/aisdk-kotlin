package ai.torad.aisdk.middleware

import ai.torad.aisdk.ContentPart
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
 *   2. One `FilePart` / `SourcePart` per media part in `result.content`
 *      (`ContentPart.File.filename` / `.url` have no slot on `StreamEvent.FilePart`)
 *   3. One `ToolCall` per `result.toolCalls`
 *   4. `StepFinish` + `Finish` with the result's finish reason + usage
 *
 * Per v6 conventions, all synthesized text events share a single `id`
 * (`"sim_text"`) since the simulation produces exactly one text segment.
 */
/** @since 0.3.0-beta01 */
public fun SimulateStreamingMiddleware(): LanguageModelMiddleware = object : LanguageModelMiddleware {
    override fun wrapStream(context: MiddlewareCallContext): Flow<StreamEvent> = flow {
        // The fix: call doGenerate, NOT doStream. The downstream model may
        // not even implement stream — that's the whole reason for this
        // middleware. The old shape exposed only the same-direction `next`,
        // which forced this to consume the (broken) stream — see the
        // pre-refactor file history.
        val result = context.doGenerate(context.params)
        // Mirror v6: lead with stream-start (carrying warnings) and response
        // metadata, then replay reasoning blocks from the result content, then
        // text + tool calls. The prior version dropped warnings, response
        // metadata, and reasoning entirely.
        emit(StreamEvent.StreamStart(result.warnings))
        emit(
            StreamEvent.ResponseMetadata(
                id = result.response.id,
                modelId = result.response.modelId,
                headers = result.response.headers,
                body = result.response.body,
            ),
        )
        result.content.filterIsInstance<ContentPart.Reasoning>().forEachIndexed { i, reasoning ->
            val rid = "sim_reasoning_$i"
            emit(StreamEvent.ReasoningStart(rid, reasoning.providerMetadata))
            emit(StreamEvent.ReasoningDelta(rid, reasoning.text, reasoning.providerMetadata))
            emit(StreamEvent.ReasoningEnd(rid, reasoning.providerMetadata))
        }
        val textId = SIMULATED_TEXT_ID
        if (result.text.isNotEmpty()) {
            emit(StreamEvent.TextStart(textId))
            emit(StreamEvent.TextDelta(textId, result.text))
            emit(StreamEvent.TextEnd(textId))
        }
        result.content.forEachIndexed { i, part -> mediaEventOrNull(part, i)?.let { emit(it) } }
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
                rawFinishReason = result.rawFinishReason,
            ),
        )
    }

    /**
     * The media half of v6's `default:` branch, which forwards every remaining content part
     * verbatim. Our stream has no image event — v3 has no image part either — so an image replays
     * as a file. Parts already replayed above (text, reasoning) and parts with no stream
     * counterpart return null.
     */
    private fun mediaEventOrNull(part: ContentPart, index: Int): StreamEvent? = when (part) {
        is ContentPart.File ->
            StreamEvent.FilePart("sim_file_$index", part.mediaType, part.base64, part.providerMetadata)
        is ContentPart.Image ->
            StreamEvent.FilePart("sim_file_$index", part.mediaType, part.base64, part.providerMetadata)
        is ContentPart.Source -> StreamEvent.SourcePart(
            id = part.sourceId ?: "sim_source_$index",
            sourceType = part.sourceType,
            url = part.url,
            title = part.title,
            mediaType = part.mediaType,
            providerMetadata = part.providerMetadata,
        )
        is ContentPart.Text,
        is ContentPart.Reasoning,
        is ContentPart.ToolCall,
        is ContentPart.ToolResult,
        is ContentPart.ToolApprovalRequest,
        is ContentPart.ToolApprovalResponse,
        is ContentPart.Raw,
        -> null
    }
}

private const val SIMULATED_TEXT_ID = "sim_text"
