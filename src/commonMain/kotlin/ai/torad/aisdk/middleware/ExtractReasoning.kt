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
fun extractReasoningMiddleware(
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

        context.doStream(context.params).collect { event ->
            if (event !is StreamEvent.TextDelta) {
                emit(event)
                return@collect
            }
            buffer.append(event.text)
            while (true) {
                if (!inReasoning) {
                    val openIdx = buffer.indexOf(openTag)
                    if (openIdx < 0) {
                        if (buffer.isNotEmpty()) {
                            emit(StreamEvent.TextDelta(event.id, buffer.toString()))
                            buffer.clear()
                        }
                        break
                    }
                    if (openIdx > 0) {
                        emit(StreamEvent.TextDelta(event.id, buffer.substring(0, openIdx)))
                    }
                    buffer.deleteRange(0, openIdx + openTag.length)
                    reasoningId = "reasoning_${++nextReasoningIdx}"
                    emit(StreamEvent.ReasoningStart(reasoningId!!))
                    inReasoning = true
                } else {
                    val closeIdx = buffer.indexOf(closeTag)
                    if (closeIdx < 0) {
                        if (buffer.isNotEmpty()) {
                            emit(StreamEvent.ReasoningDelta(reasoningId!!, buffer.toString()))
                            buffer.clear()
                        }
                        break
                    }
                    if (closeIdx > 0) {
                        emit(StreamEvent.ReasoningDelta(reasoningId!!, buffer.substring(0, closeIdx)))
                    }
                    emit(StreamEvent.ReasoningEnd(reasoningId!!))
                    buffer.deleteRange(0, closeIdx + closeTag.length)
                    if (separator.isNotEmpty()) emit(StreamEvent.TextDelta(event.id, separator))
                    inReasoning = false
                    reasoningId = null
                }
            }
        }
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
