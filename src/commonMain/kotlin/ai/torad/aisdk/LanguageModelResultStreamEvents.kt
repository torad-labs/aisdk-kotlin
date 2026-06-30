package ai.torad.aisdk

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow

internal object LanguageModelResultStreamEvents {
    fun from(result: LanguageModelResult): Flow<StreamEvent> = flow {
        emit(StreamEvent.StreamStart(result.warnings))
        responseMetadataEvent(result.response)?.let { emit(it) }

        val emitter = GeneratedContentEmitter(this)
        for ((index, part) in result.content.withIndex()) {
            emitter.emitPart(index, part)
        }
        emitter.emitFallbackText(result.text)
        emitter.emitMissingToolCalls(result.toolCalls)
        emit(
            StreamEvent.Finish(
                totalSteps = 1,
                finishReason = result.finishReason,
                usage = result.usage,
                providerMetadata = result.providerMetadata,
                rawFinishReason = result.rawFinishReason,
            ),
        )
    }

    private class GeneratedContentEmitter(
        private val out: FlowCollector<StreamEvent>,
    ) {
        private val emittedToolCallOccurrences = mutableListOf<ToolCallOccurrenceKey>()
        private val emittedToolCallOrdinalByIdentity = mutableMapOf<ContentPart.ToolCall, Int>()
        private var emittedText = false

        suspend fun emitPart(index: Int, part: ContentPart) {
            when (part) {
                is ContentPart.Text -> emitText(index, part)
                is ContentPart.Reasoning ->
                    out.emitReasoningPart("reasoning-$index", part.text, part.providerMetadata)
                is ContentPart.ToolCall -> emitToolCall(part)
                is ContentPart.Source -> out.emitSourcePart(index, part)
                is ContentPart.File -> out.emitFilePart(part.filename ?: "file-$index", part)
                is ContentPart.Image ->
                    out.emit(
                        StreamEvent.FilePart(
                            id = "image-$index",
                            mediaType = part.mediaType,
                            base64 = part.base64,
                            providerMetadata = part.providerMetadata,
                        ),
                    )
                is ContentPart.ToolResult,
                is ContentPart.ToolApprovalRequest,
                is ContentPart.ToolApprovalResponse,
                -> Unit
            }
        }

        suspend fun emitFallbackText(text: String) {
            if (!emittedText && text.isNotEmpty()) {
                out.emitTextPart("text", text, ProviderMetadata.None)
            }
        }

        suspend fun emitMissingToolCalls(toolCalls: List<ContentPart.ToolCall>) {
            val remainingContentOccurrences = emittedToolCallOccurrences.toMutableList()
            val resultToolCallOrdinalByIdentity = mutableMapOf<ContentPart.ToolCall, Int>()
            for (call in toolCalls) {
                val ordinal = resultToolCallOrdinalByIdentity[call] ?: 0
                resultToolCallOrdinalByIdentity[call] = ordinal + 1
                val occurrence = ToolCallOccurrenceKey(call, ordinal)
                if (!remainingContentOccurrences.remove(occurrence)) out.emitToolCallPart(call)
            }
        }

        suspend fun emitText(index: Int, part: ContentPart.Text) {
            emittedText = true
            out.emitTextPart("text-$index", part.text, part.providerMetadata)
        }

        suspend fun emitToolCall(call: ContentPart.ToolCall) {
            val ordinal = emittedToolCallOrdinalByIdentity[call] ?: 0
            emittedToolCallOrdinalByIdentity[call] = ordinal + 1
            emittedToolCallOccurrences += ToolCallOccurrenceKey(call, ordinal)
            out.emitToolCallPart(call)
        }

        private data class ToolCallOccurrenceKey(
            val call: ContentPart.ToolCall,
            val ordinal: Int,
        )
    }

    private suspend fun FlowCollector<StreamEvent>.emitTextPart(
        id: String,
        text: String,
        providerMetadata: ProviderMetadata,
    ) {
        if (text.isEmpty()) return
        emit(StreamEvent.TextStart(id, providerMetadata))
        emit(StreamEvent.TextDelta(id, text, providerMetadata))
        emit(StreamEvent.TextEnd(id, providerMetadata))
    }

    private suspend fun FlowCollector<StreamEvent>.emitReasoningPart(
        id: String,
        text: String,
        providerMetadata: ProviderMetadata,
    ) {
        if (text.isEmpty()) return
        emit(StreamEvent.ReasoningStart(id, providerMetadata))
        emit(StreamEvent.ReasoningDelta(id, text, providerMetadata))
        emit(StreamEvent.ReasoningEnd(id, providerMetadata))
    }

    private suspend fun FlowCollector<StreamEvent>.emitToolCallPart(call: ContentPart.ToolCall) {
        emit(StreamEvent.ToolInputStart(call.toolCallId, call.toolName, call.providerMetadata))
        emit(StreamEvent.ToolInputDelta(call.toolCallId, call.input.toString(), call.providerMetadata))
        emit(StreamEvent.ToolInputEnd(call.toolCallId, call.providerMetadata))
        emit(StreamEvent.ToolCall(call.toolCallId, call.toolName, call.input, call.providerMetadata))
    }

    private suspend fun FlowCollector<StreamEvent>.emitSourcePart(index: Int, part: ContentPart.Source) {
        emit(
            StreamEvent.SourcePart(
                id = part.sourceId ?: "source-$index",
                sourceType = part.sourceType,
                url = part.url,
                title = part.title,
                mediaType = part.mediaType,
                providerMetadata = part.providerMetadata,
            ),
        )
    }

    private suspend fun FlowCollector<StreamEvent>.emitFilePart(id: String, part: ContentPart.File) {
        emit(
            StreamEvent.FilePart(
                id = id,
                mediaType = part.mediaType,
                base64 = part.base64,
                providerMetadata = part.providerMetadata,
            ),
        )
    }

    private fun responseMetadataEvent(response: LanguageModelResponseMetadata): StreamEvent.ResponseMetadata? =
        run {
            val hasIdentity =
                response.id != null ||
                    response.timestampMillis != null ||
                    response.modelId != null
            val hasPayload = response.headers.isNotEmpty() || response.body != null
            if (!hasIdentity && !hasPayload) {
                null
            } else {
                StreamEvent.ResponseMetadata(
                    id = response.id,
                    timestampMillis = response.timestampMillis,
                    modelId = response.modelId,
                    headers = response.headers,
                    body = response.body,
                )
            }
        }
}
