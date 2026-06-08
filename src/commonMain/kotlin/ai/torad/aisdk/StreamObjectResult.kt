package ai.torad.aisdk

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

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
    private val repairText: ((String) -> String?)? = null,
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
     * For an array [output] (built via `Output.array`), emit each element as soon as
     * it is complete (all elements but the in-flight last, then the last when the
     * stream ends) — the v6 `elementStream`. Pass the same array output you streamed.
     */
    public fun <E> elementStream(arrayOutput: Output.Arr<E>): Flow<E> = flow {
        val accumulated = StringBuilder()
        var emitted = 0

        // Returns the elements newly ready since the last call. The in-flight last
        // element is held back until the stream completes.
        fun ready(complete: Boolean): List<E> {
            val elements = (parsePartialJson(accumulated.toString()).value as? JsonObject)
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

        events.collect { event ->
            if (event !is StreamEvent.TextDelta) return@collect
            accumulated.append(event.text)
            ready(complete = false).forEach { emit(it) }
        }
        ready(complete = true).forEach { emit(it) }
    }

    /**
     * Collect to completion and return the final typed object plus the call's
     * terminal metadata (usage, finishReason, warnings, response) — the awaitable
     * side of the v6 `StreamObjectResult`. The complete text is decoded once at the
     * end; if that fails and a [repairText] hook is set, the repaired text is retried.
     * Throws [NoObjectGeneratedError] (carrying the raw text) if neither parses.
     */
    public suspend fun finish(): StreamObjectFinish<TOutput> {
        val accumulated = StringBuilder()
        var usage = Usage()
        var finishReason = FinishReason.Stop
        var warnings: List<CallWarning> = emptyList()
        var response = LanguageModelResponseMetadata()
        events.collect { event ->
            when (event) {
                is StreamEvent.TextDelta -> accumulated.append(event.text)
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
                else -> Unit
            }
        }
        return StreamObjectFinish(decodeOrThrow(accumulated.toString()), usage, finishReason, warnings, response)
    }

    /**
     * Collect to completion and return just the final typed object (see [finish] for
     * the call's usage / finishReason / warnings / response).
     */
    public suspend fun objectValue(): TOutput = finish().value

    private fun decodeOrThrow(text: String): TOutput {
        runCatching { output.decode(text) }.getOrNull()?.let { return it }
        repairText?.invoke(text)?.let { repaired ->
            runCatching { output.decode(repaired) }.getOrNull()?.let { return it }
        }
        throw NoObjectGeneratedError("Object stream produced no parseable object", text = text)
    }
}

/** The final object plus terminal metadata of a [StreamObjectResult.finish]. */
public data class StreamObjectFinish<TOutput>(
    val value: TOutput,
    val usage: Usage,
    val finishReason: FinishReason,
    val warnings: List<CallWarning>,
    val response: LanguageModelResponseMetadata,
)

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
    repairText: ((String) -> String?)? = null,
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
    repairText = repairText,
)
