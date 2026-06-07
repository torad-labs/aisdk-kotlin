package ai.torad.aisdk

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonElement

/**
 * A typed streaming-object result — the v6 `StreamObjectResult` surface. Wraps the
 * underlying [StreamEvent] flow so callers get the object as it builds and the
 * final typed value, without hand-parsing the stream.
 *
 * The streams are cold: collecting one drives a model call, so pick a single
 * consumer (`partialObjectStream` for incremental UI, or `objectValue()` for just
 * the final result).
 */
public class StreamObjectResult<TOutput> internal constructor(
    private val events: Flow<StreamEvent>,
    private val output: Output<TOutput>,
) {
    /**
     * The object as it builds: each emission is the best-effort decode of the text
     * so far (incomplete JSON is repaired via [parsePartialJson]); the final
     * emission is the complete object. Duplicate parses are suppressed.
     */
    public val partialObjectStream: Flow<TOutput> = flow {
        val accumulated = StringBuilder()
        var lastKey: String? = null
        events.collect { event ->
            if (event !is StreamEvent.TextDelta) return@collect
            accumulated.append(event.text)
            val parsed = parsePartialJson(accumulated.toString()).value ?: return@collect
            val key = parsed.toString()
            if (key == lastKey) return@collect
            val decoded = runCatching { output.decode(key) }.getOrNull() ?: return@collect
            lastKey = key
            emit(decoded)
        }
    }

    /** The raw text deltas as they arrive (cold — collecting drives a model call). */
    public val textStream: Flow<String> =
        events.filterIsInstance<StreamEvent.TextDelta>().map { it.text }

    /**
     * Collect to completion and return the final typed object, or throw
     * [NoObjectGeneratedError] if the model produced nothing parseable.
     */
    public suspend fun objectValue(): TOutput {
        var last: TOutput? = null
        var produced = false
        partialObjectStream.collect {
            last = it
            produced = true
        }
        if (!produced) throw NoObjectGeneratedError("Object stream produced no parseable object")
        @Suppress("UNCHECKED_CAST")
        return last as TOutput
    }
}

/**
 * Stream a structured object, returning a typed [StreamObjectResult] — the v6
 * `streamObject` surface (the older [streamObject] returns a raw event flow and is
 * deprecated). Use [StreamObjectResult.partialObjectStream] for incremental UI or
 * [StreamObjectResult.objectValue] for the final value.
 */
public fun <TOutput> streamObjectResult(
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
    providerOptions: Map<String, JsonElement> = emptyMap(),
    abortSignal: AbortSignal = AbortSignalNever,
    presencePenalty: Float? = null,
    frequencyPenalty: Float? = null,
    responseFormat: ResponseFormat = ResponseFormat.Text,
): StreamObjectResult<TOutput> = StreamObjectResult(
    events = streamText(
        model = model,
        prompt = prompt,
        messages = messages,
        system = system,
        temperature = temperature,
        topP = topP,
        topK = topK,
        maxOutputTokens = maxOutputTokens,
        stopSequences = stopSequences,
        seed = seed,
        providerOptions = providerOptions,
        abortSignal = abortSignal,
        output = output,
        presencePenalty = presencePenalty,
        frequencyPenalty = frequencyPenalty,
        responseFormat = responseFormat,
    ),
    output = output,
)
