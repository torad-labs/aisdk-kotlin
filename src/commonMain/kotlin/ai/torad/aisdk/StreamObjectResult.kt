package ai.torad.aisdk

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
        val accumulated = StringBuilder()
        var lastKey: String? = null
        stream.collect { event ->
            if (event is StreamEvent.Error) throw UiMessageStreamError(event.message, event.cause)
            if (event !is StreamEvent.TextDelta) return@collect
            accumulated.append(event.text)
            val parsed = PartialJson.parsePartialJson(accumulated.toString()).value ?: return@collect
            val key = parsed.toString()
            if (key == lastKey) return@collect
            val decoded = runCatching { output.decode(key) }.getOrNull() ?: return@collect
            lastKey = key
            emit(decoded)
        }
    }

    public val textStream: Flow<String> =
        stream.filterIsInstance<StreamEvent.TextDelta>().map { it.text }

    public fun <E> elementStream(arrayOutput: Output.Arr<E>): Flow<E> = flow {
        val accumulated = StringBuilder()
        var emitted = 0

        fun ready(complete: Boolean): List<E> {
            val elements = (PartialJson.parsePartialJson(accumulated.toString()).value as? JsonObject)
                ?.get("elements") as? JsonArray ?: return emptyList()
            val readyCount = if (complete) elements.size else (elements.size - 1).coerceAtLeast(0)
            val out = mutableListOf<E>()
            while (emitted < readyCount) {
                runCatching {
                    aiSdkOutputJson.decodeFromJsonElement(arrayOutput.elementSerializer, elements[emitted])
                }.getOrNull()?.let { out.add(it) }
                emitted++
            }
            return out
        }

        stream.collect { event ->
            if (event is StreamEvent.Error) throw UiMessageStreamError(event.message, event.cause)
            if (event !is StreamEvent.TextDelta) return@collect
            accumulated.append(event.text)
            ready(complete = false).forEach { emit(it) }
        }
        ready(complete = true).forEach { emit(it) }
    }

    public suspend fun finish(): StreamObjectFinish<TOutput> {
        val accumulated = StringBuilder()
        var usage = Usage()
        var finishReason = FinishReason.Stop
        var warnings: List<CallWarning> = emptyList()
        var response = LanguageModelResponseMetadata()
        stream.collect { event ->
            when (event) {
                is StreamEvent.TextDelta -> accumulated.append(event.text)
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
                is StreamEvent.TextStart,
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
            decodeOrThrow(accumulated.toString(), usage, finishReason, response),
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
}

public data class StreamObjectFinish<TOutput>(
    val value: TOutput,
    val usage: Usage,
    val finishReason: FinishReason,
    val warnings: List<CallWarning>,
    val response: LanguageModelResponseMetadata,
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
            CallConfig(
                temperature = temperature,
                topP = topP,
                topK = topK,
                maxOutputTokens = maxOutputTokens,
                stopSequences = stopSequences,
                seed = seed,
                providerOptions = providerOptions,
                abortSignal = abortSignal,
                presencePenalty = presencePenalty,
                frequencyPenalty = frequencyPenalty,
                responseFormat = effectiveResponseFormat,
            ),
        ).stream(input),
        output = output,
        repairText = repairText,
    )
}
