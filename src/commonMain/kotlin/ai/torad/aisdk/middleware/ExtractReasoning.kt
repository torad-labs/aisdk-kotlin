package ai.torad.aisdk.middleware

import ai.torad.aisdk.ContentPart
import ai.torad.aisdk.LanguageModelMiddleware
import ai.torad.aisdk.LanguageModelResult
import ai.torad.aisdk.MiddlewareCallContext
import ai.torad.aisdk.ProviderMetadata
import ai.torad.aisdk.StreamEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.jvm.JvmOverloads

/**
 * Extracts XML-tagged reasoning sections from generated text and
 * surfaces them as separate `ReasoningStart/Delta/End` events.
 *
 * For models that emit reasoning inline (`<reasoning>...</reasoning>`,
 * `<think>...</think>`) — strips the tags from the visible text and
 * produces typed reasoning events the UI can render in a collapsible.
 *
 * Mirrors v6's `extractReasoningMiddleware`. Useful for Gemma-style
 * models that emit thinking inline rather than via dedicated reasoning
 * channels.
 */
@JvmOverloads
/** @since 0.3.0-beta01 */
public fun ExtractReasoningMiddleware(
    tagName: String = "reasoning",
    separator: String = "\n",
    startWithReasoning: Boolean = false,
): LanguageModelMiddleware = object : LanguageModelMiddleware {
    private val openTag = "<$tagName>"
    private val closeTag = "</$tagName>"

    override suspend fun wrapGenerate(context: MiddlewareCallContext): LanguageModelResult {
        val raw = context.doGenerate(context.params)
        // Rebuild content like upstream: each text part with a reasoning tag becomes
        // a Reasoning part + a cleaned Text part; other parts pass through. The prior
        // version only set text=cleanText, leaving the tagged text in content and
        // dropping the reasoning entirely.
        val rebuilt = mutableListOf<ContentPart>()
        val cleanedText = StringBuilder()
        for (part in raw.content) {
            if (part !is ContentPart.Text) {
                rebuilt += part
                continue
            }
            // startWithReasoning: the model emits raw reasoning before any open tag.
            val text = if (startWithReasoning) "$openTag${part.text}" else part.text
            val (clean, reasoning) = extractReasoning(text)
            // Key off whether tags were actually stripped (clean != text), NOT whether
            // the reasoning text is non-empty — an empty <reasoning></reasoning> must
            // still be stripped (matching upstream's match-found check), else the literal
            // tags leak into visible text.
            if (clean == text) {
                rebuilt += part
                cleanedText.append(part.text)
            } else {
                rebuilt += ContentPart.Reasoning(reasoning)
                rebuilt += ContentPart.Text(clean)
                cleanedText.append(clean)
            }
        }
        return LanguageModelResult(
            text = cleanedText.toString(),
            toolCalls = raw.toolCalls,
            finishReason = raw.finishReason,
            usage = raw.usage,
            providerMetadata = raw.providerMetadata,
            content = rebuilt,
            rawFinishReason = raw.rawFinishReason,
            warnings = raw.warnings,
            request = raw.request,
            response = raw.response,
        )
    }

    override fun wrapStream(context: MiddlewareCallContext): Flow<StreamEvent> = flow {
        val buffer = StringBuilder()
        var inReasoning = false
        var reasoningId: String? = null
        var nextReasoningIdx = 0
        var lastTextId: String? = null
        // The separator is a conditional PREFIX on the next published chunk, not a
        // standalone delta after every close tag — matching v6's `publish()`. It only
        // appears when a switch just happened AND text of that kind was already
        // published, so a reasoning block that opens or closes the stream leaves no
        // stray leading/trailing separator in the visible text.
        var isFirstText = true
        var isFirstReasoning = true
        var afterSwitch = false

        // startWithReasoning: begin already inside a reasoning section (the model
        // streams reasoning tokens before any open tag), looking for the close tag.
        if (startWithReasoning) {
            val rid = "reasoning_${++nextReasoningIdx}"
            reasoningId = rid
            inReasoning = true
            emit(StreamEvent.ReasoningStart(rid))
        }

        suspend fun publish(
            id: String?,
            text: String,
            providerMetadata: ProviderMetadata = ProviderMetadata.None,
        ) {
            if (text.isEmpty()) return
            val rid = reasoningId
            if (inReasoning && rid != null) {
                val prefix = if (afterSwitch && !isFirstReasoning) separator else ""
                emit(StreamEvent.ReasoningDelta(rid, prefix + text, providerMetadata))
                isFirstReasoning = false
                afterSwitch = false
            } else if (id != null) {
                val prefix = if (afterSwitch && !isFirstText) separator else ""
                emit(StreamEvent.TextDelta(id, prefix + text, providerMetadata))
                isFirstText = false
                afterSwitch = false
            }
        }

        suspend fun emitBufferedText(id: String?) {
            if (buffer.isEmpty()) return
            val text = buffer.toString()
            buffer.clear()
            publish(id, text)
        }

        // A section left open at the end of the text block (or of the whole stream) must
        // still be terminated: every other reasoning producer in this SDK closes an open
        // section at stream end, and downstream consumers materialise the reasoning part
        // only on ReasoningEnd. A truncated or malformed `<reasoning>` would otherwise
        // leave the part open forever.
        suspend fun closeOpenReasoning() {
            val rid = reasoningId
            if (inReasoning && rid != null) {
                emit(StreamEvent.ReasoningEnd(rid))
                inReasoning = false
                reasoningId = null
                afterSwitch = true
            }
        }

        context.doStream(context.params).collect { event ->
            when (event) {
                is StreamEvent.TextDelta -> {
                    lastTextId = event.id
                    buffer.append(event.text)
                    while (true) {
                        if (!inReasoning) {
                            val openIdx = buffer.indexOf(openTag)
                            if (openIdx < 0) {
                                val emitLength = buffer.length - ReasoningScan.longestSuffixPrefixOf(buffer, openTag)
                                if (emitLength > 0) {
                                    publish(event.id, buffer.substring(0, emitLength), event.providerMetadata)
                                    buffer.deleteRange(0, emitLength)
                                }
                                break
                            }
                            if (openIdx > 0) {
                                publish(event.id, buffer.substring(0, openIdx), event.providerMetadata)
                            }
                            buffer.deleteRange(0, openIdx + openTag.length)
                            val newReasoningId = "reasoning_${++nextReasoningIdx}"
                            reasoningId = newReasoningId
                            emit(StreamEvent.ReasoningStart(newReasoningId, event.providerMetadata))
                            inReasoning = true
                            afterSwitch = true
                        } else {
                            // Invariant: reasoningId is non-null whenever inReasoning.
                            val rid = requireNotNull(reasoningId) {
                                "reasoningId must be set while parsing a reasoning section"
                            }
                            val closeIdx = buffer.indexOf(closeTag)
                            if (closeIdx < 0) {
                                val emitLength = buffer.length - ReasoningScan.longestSuffixPrefixOf(buffer, closeTag)
                                if (emitLength > 0) {
                                    publish(event.id, buffer.substring(0, emitLength), event.providerMetadata)
                                    buffer.deleteRange(0, emitLength)
                                }
                                break
                            }
                            if (closeIdx > 0) {
                                publish(event.id, buffer.substring(0, closeIdx), event.providerMetadata)
                            }
                            emit(StreamEvent.ReasoningEnd(rid, event.providerMetadata))
                            buffer.deleteRange(0, closeIdx + closeTag.length)
                            inReasoning = false
                            reasoningId = null
                            afterSwitch = true
                        }
                    }
                }
                is StreamEvent.TextEnd -> {
                    emitBufferedText(event.id)
                    closeOpenReasoning()
                    emit(event)
                }
                is StreamEvent.StreamStart,
                is StreamEvent.ResponseMetadata,
                is StreamEvent.StepStart,
                is StreamEvent.TextStart,
                is StreamEvent.ReasoningStart,
                is StreamEvent.ReasoningDelta,
                is StreamEvent.ReasoningEnd,
                is StreamEvent.SourcePart,
                is StreamEvent.FilePart,
                is StreamEvent.Data,
                is StreamEvent.ToolInputStart,
                is StreamEvent.ToolInputDelta,
                is StreamEvent.ToolInputEnd,
                is StreamEvent.ToolCall,
                is StreamEvent.ToolResult,
                is StreamEvent.ToolError,
                is StreamEvent.ToolApprovalRequest,
                is StreamEvent.ToolOutputDenied,
                is StreamEvent.StepFinish,
                is StreamEvent.Finish,
                StreamEvent.Abort,
                is StreamEvent.Error,
                is StreamEvent.Raw,
                -> {
                    emitBufferedText(lastTextId)
                    emit(event)
                }
            }
        }
        emitBufferedText(lastTextId)
        closeOpenReasoning()
    }

    private fun extractReasoning(text: String): Pair<String, String> {
        if (!text.contains(openTag)) return text to ""
        val sb = StringBuilder(text)
        val reasoningSb = StringBuilder()
        while (true) {
            val openIdx = sb.indexOf(openTag)
            if (openIdx < 0) break
            val closeIdx = sb.indexOf(closeTag, startIndex = openIdx + openTag.length)
            if (closeIdx < 0) break
            val reasoning = sb.substring(openIdx + openTag.length, closeIdx)
            reasoningSb.append(reasoning)
            sb.deleteRange(openIdx, closeIdx + closeTag.length)
        }
        return sb.toString() to reasoningSb.toString()
    }
}

internal object ReasoningScan {
    /**
     * Length of the longest suffix of [builder] that is also a (strict) prefix of [value].
     * Used to hold back a partial tag match that may complete on the next stream delta.
     */
    fun longestSuffixPrefixOf(builder: StringBuilder, value: String): Int {
        val maxLength = minOf(builder.length, value.length - 1)
        for (candidateLength in maxLength downTo 1) {
            val suffixStart = builder.length - candidateLength
            var matches = true
            for (i in 0 until candidateLength) {
                if (builder[suffixStart + i] != value[i]) {
                    matches = false
                    break
                }
            }
            if (matches) return candidateLength
        }
        return 0
    }
}
