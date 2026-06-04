package ai.torad.aisdk.middleware

import ai.torad.aisdk.LanguageModelMiddleware
import ai.torad.aisdk.LanguageModelResult
import ai.torad.aisdk.MiddlewareCallContext
import ai.torad.aisdk.StreamEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

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
public fun extractReasoningMiddleware(
    tagName: String = "reasoning",
    separator: String = "\n",
): LanguageModelMiddleware = object : LanguageModelMiddleware {
    private val openTag = "<$tagName>"
    private val closeTag = "</$tagName>"

    override suspend fun wrapGenerate(context: MiddlewareCallContext): LanguageModelResult {
        val raw = context.doGenerate(context.params)
        val (cleanText, _) = extractReasoning(raw.text)
        return raw.copy(text = cleanText)
    }

    override fun wrapStream(context: MiddlewareCallContext): Flow<StreamEvent> = flow {
        val buffer = StringBuilder()
        var inReasoning = false
        var reasoningId: String? = null
        var nextReasoningIdx = 0
        var lastTextId: String? = null

        suspend fun emitBufferedText(id: String?) {
            if (buffer.isEmpty()) return
            val text = buffer.toString()
            buffer.clear()
            val rid = reasoningId
            if (inReasoning && rid != null) {
                emit(StreamEvent.ReasoningDelta(rid, text))
            } else if (id != null) {
                emit(StreamEvent.TextDelta(id, text))
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
                                val emitLength = buffer.length - buffer.longestSuffixPrefixOf(openTag)
                                if (emitLength > 0) {
                                    emit(StreamEvent.TextDelta(event.id, buffer.substring(0, emitLength), event.providerMetadata))
                                    buffer.deleteRange(0, emitLength)
                                }
                                break
                            }
                            if (openIdx > 0) {
                                emit(StreamEvent.TextDelta(event.id, buffer.substring(0, openIdx), event.providerMetadata))
                            }
                            buffer.deleteRange(0, openIdx + openTag.length)
                            val newReasoningId = "reasoning_${++nextReasoningIdx}"
                            reasoningId = newReasoningId
                            emit(StreamEvent.ReasoningStart(newReasoningId, event.providerMetadata))
                            inReasoning = true
                        } else {
                            // Invariant: reasoningId is non-null whenever inReasoning.
                            val rid = requireNotNull(reasoningId) {
                                "reasoningId must be set while parsing a reasoning section"
                            }
                            val closeIdx = buffer.indexOf(closeTag)
                            if (closeIdx < 0) {
                                val emitLength = buffer.length - buffer.longestSuffixPrefixOf(closeTag)
                                if (emitLength > 0) {
                                    emit(StreamEvent.ReasoningDelta(rid, buffer.substring(0, emitLength), event.providerMetadata))
                                    buffer.deleteRange(0, emitLength)
                                }
                                break
                            }
                            if (closeIdx > 0) {
                                emit(StreamEvent.ReasoningDelta(rid, buffer.substring(0, closeIdx), event.providerMetadata))
                            }
                            emit(StreamEvent.ReasoningEnd(rid, event.providerMetadata))
                            buffer.deleteRange(0, closeIdx + closeTag.length)
                            if (separator.isNotEmpty()) emit(StreamEvent.TextDelta(event.id, separator, event.providerMetadata))
                            inReasoning = false
                            reasoningId = null
                        }
                    }
                }
                is StreamEvent.TextEnd -> {
                    emitBufferedText(event.id)
                    emit(event)
                }
                else -> {
                    emitBufferedText(lastTextId)
                    emit(event)
                }
            }
        }
        emitBufferedText(lastTextId)
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

private fun StringBuilder.longestSuffixPrefixOf(value: String): Int {
    val maxLength = minOf(length, value.length - 1)
    for (candidateLength in maxLength downTo 1) {
        val suffixStart = length - candidateLength
        var matches = true
        for (i in 0 until candidateLength) {
            if (this[suffixStart + i] != value[i]) {
                matches = false
                break
            }
        }
        if (matches) return candidateLength
    }
    return 0
}
