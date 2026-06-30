package ai.torad.aisdk

import dev.drewhamilton.poko.Poko
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

public class StreamObjectResult<TOutput> internal constructor(
    events: Flow<StreamEvent>,
    private val output: Output<TOutput>,
    private val repairText: ((String) -> String?)? = null,
) {
    // Memoise the cold upstream: per the LanguageModel.stream() contract each collection drives a
    // fresh API call, so partialObjectStream / textStream / elementStream / finish() collecting
    // `events` directly would each trigger a SEPARATE (paid, possibly divergent) generation. Route
    // every accessor through one StreamTextResult so the provider is hit at most once and replayed.
    private val stream: Flow<StreamEvent> = StreamTextResult(events).fullStream

    public val partialObjectStream: Flow<TOutput> = flow {
        val textBlocks = OrderedTextBlocks()
        var lastKey: String? = null
        stream.collect { event ->
            when (event) {
                is StreamEvent.TextStart -> textBlocks.start(event.id)
                is StreamEvent.TextDelta -> {
                    val text = textBlocks.append(event.id, event.text)
                    val parsed = PartialJson.parsePartialJson(text).value ?: return@collect
                    val key = parsed.toString()
                    if (key == lastKey) return@collect
                    val decoded = runCatching { output.decode(key) }.getOrNull() ?: return@collect
                    lastKey = key
                    emit(decoded)
                }
                is StreamEvent.Error -> throw UiMessageStreamError(event.message, event.cause)
                is StreamEvent.StreamStart,
                is StreamEvent.ResponseMetadata,
                is StreamEvent.StepStart,
                is StreamEvent.TextEnd,
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
                is StreamEvent.Raw,
                -> Unit
            }
        }
    }

    public val textStream: Flow<String> = flow {
        data class TextBlock(
            val buffer: StringBuilder = StringBuilder(),
            var flushedLength: Int = 0,
            var closed: Boolean = false,
        )

        val blockOrder = mutableListOf<String>()
        val blocks = linkedMapOf<String, TextBlock>()

        fun block(id: String): TextBlock {
            if (id !in blocks) {
                blockOrder += id
            }
            return blocks.getOrPut(id) { TextBlock() }
        }

        suspend fun flushStableSuffixes() {
            for (id in blockOrder) {
                val textBlock = block(id)
                val earlierOpen = blockOrder
                    .takeWhile { it != id }
                    .any { !block(it).closed }
                if (earlierOpen) return
                val pending = textBlock.buffer.substring(textBlock.flushedLength)
                if (pending.isNotEmpty()) {
                    emit(pending)
                    textBlock.flushedLength = textBlock.buffer.length
                }
                if (!textBlock.closed) return
            }
        }

        stream.collect { event ->
            when (event) {
                is StreamEvent.TextStart -> block(event.id)
                is StreamEvent.TextDelta -> {
                    block(event.id).buffer.append(event.text)
                    flushStableSuffixes()
                }
                is StreamEvent.TextEnd -> {
                    block(event.id).closed = true
                    flushStableSuffixes()
                }
                is StreamEvent.Error -> throw UiMessageStreamError(event.message, event.cause)
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
                is StreamEvent.Raw,
                -> Unit
            }
        }
    }

    public fun <E> elementStream(arrayOutput: Output.Arr<E>): Flow<E> = flow {
        val textBlocks = OrderedTextBlocks()
        val decoder = ElementStreamDecoder(arrayOutput)

        stream.collect { event ->
            when (event) {
                is StreamEvent.TextStart -> textBlocks.start(event.id)
                is StreamEvent.TextDelta -> {
                    decoder.ready(textBlocks.append(event.id, event.text), complete = false).forEach { emit(it) }
                }
                is StreamEvent.Error -> throw UiMessageStreamError(event.message, event.cause)
                is StreamEvent.StreamStart,
                is StreamEvent.ResponseMetadata,
                is StreamEvent.StepStart,
                is StreamEvent.TextEnd,
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
                is StreamEvent.Raw,
                -> Unit
            }
        }
        decoder.ready(textBlocks.joinedText(), complete = true).forEach { emit(it) }
    }

    public suspend fun finish(): StreamObjectFinish<TOutput> {
        val textBlocks = OrderedTextBlocks()
        var usage = Usage()
        var finishReason = FinishReason.Stop
        var warnings: List<CallWarning> = emptyList()
        var response = LanguageModelResponseMetadata()
        stream.collect { event ->
            when (event) {
                is StreamEvent.TextStart -> textBlocks.start(event.id)
                is StreamEvent.TextDelta -> textBlocks.append(event.id, event.text)
                // In-band terminal error: surface the real provider failure (preserving its cause)
                // instead of letting it fall through to a misleading NoObjectGeneratedError below.
                is StreamEvent.Error -> throw UiMessageStreamError(event.message, event.cause)
                is StreamEvent.StreamStart -> warnings = event.warnings
                is StreamEvent.ResponseMetadata -> response = LanguageModelResponseMetadata(
                    id = event.id,
                    timestampMillis = event.timestampMillis,
                    modelId = event.modelId,
                    headers = event.headers,
                    body = event.body,
                )
                is StreamEvent.Finish -> {
                    usage = event.usage
                    finishReason = event.finishReason
                }
                is StreamEvent.StepStart,
                is StreamEvent.TextEnd,
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
                StreamEvent.Abort,
                is StreamEvent.Raw,
                -> Unit
            }
        }
        return StreamObjectFinish(
            decodeOrThrow(textBlocks.joinedText(), usage, finishReason, response),
            usage,
            finishReason,
            warnings,
            response,
        )
    }

    public suspend fun objectValue(): TOutput = finish().value

    private fun decodeOrThrow(
        text: String,
        usage: Usage,
        finishReason: FinishReason,
        response: LanguageModelResponseMetadata,
    ): TOutput {
        val primary = runCatching { output.decode(text) }
        primary.getOrNull()?.let { return it }
        repairText?.invoke(text)?.let { repaired ->
            runCatching { output.decode(repaired) }.getOrNull()?.let { return it }
        }
        throw NoObjectGeneratedError(
            "Object stream produced no parseable object",
            text = text,
            response = response,
            usage = usage,
            finishReason = finishReason,
            // Attach the real decode failure (kotlinx SerializationException / validation error)
            // so callers see WHY the JSON failed, not just a generic "no parseable object".
            cause = primary.exceptionOrNull(),
        )
    }

    private class OrderedTextBlocks {
        private val blocks = linkedMapOf<String, StringBuilder>()

        fun start(id: String) {
            blocks.getOrPut(id) { StringBuilder() }
        }

        fun append(id: String, text: String): String {
            blocks.getOrPut(id) { StringBuilder() }.append(text)
            return joinedText()
        }

        fun joinedText(): String = buildString {
            blocks.values.forEach { append(it) }
        }
    }

    private class ElementStreamDecoder<E>(
        private val output: Output.Arr<E>,
    ) {
        private var emitted = 0

        fun ready(text: String, complete: Boolean): List<E> {
            val elements = parsedElements(text) ?: return emptyList()
            val readyCount = if (complete) elements.size else (elements.size - 1).coerceAtLeast(0)
            val out = mutableListOf<E>()
            while (emitted < readyCount) {
                val decoded = decode(elements[emitted]) ?: break
                out += decoded
                emitted++
            }
            return out
        }

        private fun parsedElements(text: String): JsonArray? =
            when (val parsed = PartialJson.parsePartialJson(text).value) {
                is JsonArray -> parsed
                is JsonObject -> JsonAccess.arr(parsed, "elements")
                else -> null
            }

        private fun decode(value: JsonElement): E? =
            runCatching {
                aiSdkOutputJson.decodeFromJsonElement(output.elementSerializer, value)
            }.getOrNull()
    }
}

@Poko
public class StreamObjectFinish<TOutput>(
    public val value: TOutput,
    public val usage: Usage,
    public val finishReason: FinishReason,
    public val warnings: List<CallWarning>,
    public val response: LanguageModelResponseMetadata,
)

public fun <TOutput> StreamObjectResult(
    model: LanguageModel,
    output: Output<TOutput>,
    prompt: String? = null,
    messages: List<ModelMessage> = emptyList(),
    system: String? = null,
    temperature: Float? = null,
    topP: Float? = null,
    topK: Int? = null,
    maxOutputTokens: Int? = null,
    stopSequences: List<String> = emptyList(),
    seed: Int? = null,
    providerOptions: ProviderOptions = ProviderOptions.None,
    abortSignal: AbortSignal = AbortSignalNever,
    presencePenalty: Float? = null,
    frequencyPenalty: Float? = null,
    responseFormat: ResponseFormat = ResponseFormat.Text,
    repairText: ((String) -> String?)? = null,
): StreamObjectResult<TOutput> {
    val effectiveResponseFormat =
        if (responseFormat == ResponseFormat.Text) output.toResponseFormat() else responseFormat
    val inputMessages = buildList {
        if (system != null) add(SystemMessage(system))
        addAll(messages)
    }
    val input = GenerationInput.from(
        prompt = prompt,
        messages = inputMessages,
    )
    return StreamObjectResult(
        events = TextGenerator(
            model,
            CallConfig {
                temperature(temperature)
                topP(topP)
                topK(topK)
                maxOutputTokens(maxOutputTokens)
                stopSequences(stopSequences)
                seed(seed)
                providerOptions(providerOptions)
                abortSignal(abortSignal)
                presencePenalty(presencePenalty)
                frequencyPenalty(frequencyPenalty)
                responseFormat(effectiveResponseFormat)
            },
        ).stream(input),
        output = output,
        repairText = repairText,
    )
}
