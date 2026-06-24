package ai.torad.aisdk.middleware

import ai.torad.aisdk.LanguageModelMiddleware
import ai.torad.aisdk.LanguageModelResult
import ai.torad.aisdk.MiddlewareCallContext
import ai.torad.aisdk.PartialJsonState
import ai.torad.aisdk.ContentPart
import ai.torad.aisdk.StreamEvent
import ai.torad.aisdk.PartialJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow

/**
 * Extracts JSON from text-only model output. For models that wrap their
 * structured response in markdown code fences (` ```json {...} ``` `) or
 * mix prose with the JSON, this strips the surrounding text and surfaces
 * just the JSON object.
 *
 * Mirrors v6's `extractJsonMiddleware`. Useful when [Output] is set on
 * `generateText` but the underlying model doesn't natively support
 * structured output and emits free text — the on-device Gemma path.
 *
 * Streaming follows v6's state machine: hold `text-start` until a
 * possible markdown fence prefix is resolved, then stream through text
 * while retaining a 12-character suffix buffer so a closing fence can be
 * stripped without buffering the whole response.
 *
 * **Truncation robustness.** A truncated generation (context exhausted,
 * max tokens, cancellation) leaves the JSON region open — `{"a":1`
 * instead of `{"a":1}`. [extractAndRepairJson] runs the extracted
 * region through `parsePartialJson` / `fixJson`, so a cut-off object is
 * repaired into a parseable one rather than failing downstream
 * `Output.decode`. This is the on-device "unstable as context grows"
 * failure mode the repair layer (gap #13) exists to absorb.
 * @param transform optional custom text transform. When supplied,
 * streaming text is buffered until `TextEnd`, matching v6 because an
 * arbitrary transform cannot be applied incrementally.
 */
public fun ExtractJsonMiddleware(
    transform: ((String) -> String)? = null,
): LanguageModelMiddleware = object : LanguageModelMiddleware {

    private val hasCustomTransform: Boolean = transform != null

    override suspend fun wrapGenerate(context: MiddlewareCallContext): LanguageModelResult {
        val raw = context.doGenerate(context.params)
        val text = transformText(raw.text)
        return raw.copy(
            text = text,
            content = rebuildContent(raw.content, text),
        )
    }

    override fun wrapStream(context: MiddlewareCallContext): Flow<StreamEvent> = flow {
        val textBlocks = mutableMapOf<String, TextBlock>()
        context.doStream(context.params).collect { event ->
            when (event) {
                is StreamEvent.TextStart -> {
                    textBlocks[event.id] = TextBlock(
                        startEvent = event,
                        phase = if (hasCustomTransform) TextPhase.Buffering else TextPhase.Prefix,
                    )
                }
                is StreamEvent.TextDelta -> {
                    val block = textBlocks[event.id]
                    if (block == null) {
                        emit(event)
                    } else {
                        block.buffer.append(event.text)
                        emitBufferedText(event, block)
                    }
                }
                is StreamEvent.TextEnd -> {
                    val block = textBlocks.remove(event.id)
                    if (block == null) {
                        emit(event)
                    } else {
                        if (block.phase == TextPhase.Prefix || block.phase == TextPhase.Buffering) {
                            emit(block.startEvent)
                        }
                        val remaining = remainingText(block)
                        if (remaining.isNotEmpty()) {
                            emit(StreamEvent.TextDelta(event.id, remaining, event.providerMetadata))
                        }
                        emit(event)
                    }
                }
                is StreamEvent.StreamStart,
                is StreamEvent.ResponseMetadata,
                is StreamEvent.StepStart,
                is StreamEvent.ReasoningStart,
                is StreamEvent.ReasoningDelta,
                is StreamEvent.ReasoningEnd,
                is StreamEvent.SourcePart,
                is StreamEvent.FilePart,
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
                -> emit(event)
            }
        }
    }

    private suspend fun FlowCollector<StreamEvent>.emitBufferedText(
        event: StreamEvent.TextDelta,
        block: TextBlock,
    ) {
        if (block.phase == TextPhase.Buffering) return

        if (block.phase == TextPhase.Prefix) {
            resolvePrefix(block)
            if (block.phase == TextPhase.Streaming) {
                emit(block.startEvent)
            }
        }

        if (block.phase == TextPhase.Streaming && block.buffer.length > SUFFIX_BUFFER_SIZE) {
            val streamLength = block.buffer.length - SUFFIX_BUFFER_SIZE
            val toStream = block.buffer.substring(0, streamLength)
            block.buffer.deleteRange(0, streamLength)
            emit(StreamEvent.TextDelta(event.id, toStream, event.providerMetadata))
            block.emittedContent = true
        }
    }

    private fun resolvePrefix(block: TextBlock) {
        val current = block.buffer.toString()
        when {
            current.isNotEmpty() && !current.startsWith("`") -> {
                val jsonStart = current.indexOfFirst { it == '{' || it == '[' }
                if (jsonStart >= 0) {
                    if (jsonStart > 0) {
                        block.buffer.deleteRange(0, jsonStart)
                    }
                    block.phase = TextPhase.Streaming
                }
            }
            current.startsWith("```") && current.contains('\n') -> {
                val prefixMatch = FENCE_PREFIX.find(current)
                if (prefixMatch != null) {
                    block.buffer.deleteRange(0, prefixMatch.value.length)
                    block.prefixStripped = true
                    // Only stream once the JSON fence header is stripped. A non-JSON fence
                    // (```python, ```sh …) yields no prefix match — transitioning to Streaming
                    // there would flush the raw fence header and force the wrong TextEnd path;
                    // staying Buffering lets remainingText() extract the JSON via scanBalanced.
                    block.phase = TextPhase.Streaming
                }
            }
            current.length >= 3 && !current.startsWith("```") -> {
                block.phase = TextPhase.Streaming
            }
        }
    }

    private fun remainingText(block: TextBlock): String {
        val remaining = block.buffer.toString()
        return when {
            block.phase == TextPhase.Buffering -> transformText(remaining)
            block.prefixStripped && block.emittedContent -> stripClosingFence(remaining)
            block.prefixStripped -> extractAndRepairJson(stripClosingFence(remaining))
            block.emittedContent -> stripClosingFence(remaining)
            else -> transformText(remaining)
        }
    }

    private fun transformText(text: String): String =
        transform?.invoke(text) ?: extractAndRepairJson(text)

    private fun rebuildContent(content: List<ContentPart>, text: String): List<ContentPart> = buildList {
        if (text.isNotEmpty()) add(ContentPart.Text(text))
        addAll(content.filterNot { it is ContentPart.Text })
    }

    /**
     * Strip prose/fences to the JSON region, then repair it if the
     * region is a truncated fragment. A clean parse passes through
     * verbatim (preserves the model's formatting); only a repaired
     * (open) fragment is re-serialized to its closed canonical form.
     */
    private fun extractAndRepairJson(text: String): String {
        val region = extractJsonRegion(text)
        val parsed = PartialJson.parsePartialJson(region)
        if (parsed.state == PartialJsonState.RepairedParse && parsed.value != null) {
            return parsed.value.toString()
        }
        return region
    }

    private fun extractJsonRegion(text: String): String {
        // Strip markdown code fences first.
        val fenced = """```(?:json)?\s*([\s\S]*?)```""".toRegex()
            .find(text)?.groupValues?.get(1)?.trim()
        if (fenced != null && (fenced.startsWith("{") || fenced.startsWith("["))) return fenced

        // Otherwise find the first { or [ and scan to the matching close.
        val openIdx = text.indexOfFirst { it == '{' || it == '[' }
        if (openIdx < 0) return text
        return scanBalanced(text, openIdx)
    }

    private fun scanBalanced(text: String, openIdx: Int): String {
        val openChar = text[openIdx]
        val closeChar = if (openChar == '{') '}' else ']'
        var depth = 0
        var inString = false
        var escaped = false
        for (i in openIdx until text.length) {
            val c = text[i]
            if (inString) {
                // Skip everything inside a string literal — a `}`/`]`/`"`
                // here is data, not structure. Mirrors fixJson's
                // INSIDE_STRING handling so a brace inside a string value
                // doesn't collapse depth and truncate trailing fields.
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                openChar -> depth += 1
                closeChar -> {
                    depth -= 1
                    if (depth == 0) return text.substring(openIdx, i + 1)
                }
            }
        }
        // No matching close — truncated. Keep the open region (prose
        // prefix stripped) so the repair layer can close it; the old
        // `return text` leaked the prose prefix back into the result.
        return text.substring(openIdx)
    }

    private fun stripClosingFence(text: String): String =
        text.replace(FENCE_SUFFIX, "").trimEnd()
}

private const val SUFFIX_BUFFER_SIZE = 12

private val FENCE_PREFIX = """^```(?:json)?\s*\n""".toRegex()
private val FENCE_SUFFIX = """\n?```\s*$""".toRegex()

private enum class TextPhase {
    Prefix,
    Streaming,
    Buffering,
}

private data class TextBlock(
    val startEvent: StreamEvent.TextStart,
    var phase: TextPhase,
    val buffer: StringBuilder = StringBuilder(),
    var prefixStripped: Boolean = false,
    var emittedContent: Boolean = false,
)
