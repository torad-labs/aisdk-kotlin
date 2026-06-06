package ai.torad.aisdk

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonElement

/**
 * One-shot generation — invariant I-3, the only `text` entry point in v6.
 *
 * For tool-loop agents, use [Agent.generate] instead. This is the
 * primitive for single-turn calls (no tool-loop wrapping).
 */
public suspend fun generateText(
    model: LanguageModel,
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
): GenerateTextResult<String> =
    generateTextImpl(
        model = model,
        prompt = prompt,
        messages = messages,
        system = system,
        output = null,
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
        responseFormat = responseFormat,
        decode = { it },
    )

public suspend fun <TOutput> generateText(
    model: LanguageModel,
    prompt: String? = null,
    messages: List<ModelMessage> = emptyList(),
    system: String? = null,
    output: Output<TOutput>,
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
): GenerateTextResult<TOutput> =
    generateTextImpl(
        model = model,
        prompt = prompt,
        messages = messages,
        system = system,
        output = output,
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
        responseFormat = responseFormat,
        decode = output::decode,
    )

private suspend fun <TOutput> generateTextImpl(
    model: LanguageModel,
    prompt: String?,
    messages: List<ModelMessage>,
    system: String?,
    output: Output<TOutput>?,
    temperature: Float?,
    topP: Float?,
    topK: Int?,
    maxOutputTokens: Int?,
    stopSequences: List<String>,
    seed: Int?,
    providerOptions: Map<String, JsonElement>,
    abortSignal: AbortSignal,
    presencePenalty: Float?,
    frequencyPenalty: Float?,
    responseFormat: ResponseFormat,
    decode: (String) -> TOutput,
): GenerateTextResult<TOutput> {
    require(prompt != null || messages.isNotEmpty()) {
        "generateText: must provide either `prompt` or `messages`"
    }
    val effectiveMessages = buildList {
        if (system != null) add(systemMessage(system))
        addAll(messages)
        if (prompt != null) add(userMessage(prompt))
    }
    val params = LanguageModelCallParams(
        messages = effectiveMessages,
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
        responseFormat = output?.let { structuredOutput ->
            if (responseFormat == ResponseFormat.Text) structuredOutput.toResponseFormat() else responseFormat
        } ?: responseFormat,
    )
    val raw = model.generate(params)
    val typed = decode(raw.text)
    return GenerateTextResult(
        output = typed,
        text = raw.text,
        toolCalls = raw.toolCalls,
        finishReason = raw.finishReason,
        usage = raw.usage,
        content = raw.content,
        reasoning = raw.content.filterIsInstance<ContentPart.Reasoning>(),
        files = raw.content.filterIsInstance<ContentPart.File>(),
        sources = raw.content.filterIsInstance<ContentPart.Source>(),
        totalUsage = raw.usage,
        warnings = raw.warnings,
        request = raw.request,
        response = raw.response,
        providerMetadata = raw.providerMetadata,
        rawFinishReason = raw.rawFinishReason,
    )
}

public data class GenerateTextResult<TOutput>(
    val output: TOutput,
    val text: String,
    val toolCalls: List<ContentPart.ToolCall>,
    val finishReason: FinishReason,
    val usage: Usage,
    val content: List<ContentPart> = buildList {
        if (text.isNotEmpty()) add(ContentPart.Text(text))
        addAll(toolCalls)
    },
    val toolResults: List<ContentPart.ToolResult> = content.filterIsInstance<ContentPart.ToolResult>(),
    val reasoning: List<ContentPart.Reasoning> = content.filterIsInstance<ContentPart.Reasoning>(),
    val reasoningText: String? = reasoning.takeIf { it.isNotEmpty() }?.joinToString("") { it.text },
    val files: List<ContentPart.File> = content.filterIsInstance<ContentPart.File>(),
    val sources: List<ContentPart.Source> = content.filterIsInstance<ContentPart.Source>(),
    val totalUsage: Usage = usage,
    val warnings: List<CallWarning> = emptyList(),
    val request: LanguageModelRequestMetadata = LanguageModelRequestMetadata(),
    val response: LanguageModelResponseMetadata = LanguageModelResponseMetadata(),
    val providerMetadata: Map<String, JsonElement> = emptyMap(),
    val steps: List<StepResult> = emptyList(),
    val rawFinishReason: String? = null,
) {
    /** Tool calls/results against statically-typed tools (`tool(...)`), matching upstream's split. */
    val staticToolCalls: List<ContentPart.ToolCall> get() = toolCalls.filter { !it.dynamic }

    /** Tool calls/results against dynamic (runtime-typed) tools (`dynamicTool(...)`). */
    val dynamicToolCalls: List<ContentPart.ToolCall> get() = toolCalls.filter { it.dynamic }
    val staticToolResults: List<ContentPart.ToolResult> get() = toolResults.filter { !it.dynamic }
    val dynamicToolResults: List<ContentPart.ToolResult> get() = toolResults.filter { it.dynamic }
}

/**
 * Streaming generation — invariant I-3. Returns a cold Flow that drives
 * one upstream call when collected. For tool-loop agents, use
 * [Agent.stream] instead.
 */
public fun streamText(
    model: LanguageModel,
    prompt: String? = null,
    messages: List<ModelMessage> = emptyList(),
    system: String? = null,
    temperature: Float? = null,
    topP: Float? = null,
    topK: Int? = null,
    maxOutputTokens: Int? = null,
    stopSequences: List<String> = emptyList(),
    seed: Int? = null,
    providerOptions: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap(),
    abortSignal: AbortSignal = AbortSignalNever,
    output: Output<*>? = null,
    presencePenalty: Float? = null,
    frequencyPenalty: Float? = null,
    responseFormat: ResponseFormat = ResponseFormat.Text,
): Flow<StreamEvent> = flow {
    streamTextResult(
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
    ).fullStream.collect { emit(it) }
}

@OptIn(ExperimentalAtomicApi::class)
public class StreamTextResult(
    sourceStream: Flow<StreamEvent>,
    public val request: LanguageModelRequestMetadata = LanguageModelRequestMetadata(),
    private val initialResponse: LanguageModelResponseMetadata = LanguageModelResponseMetadata(),
) {
    private val upstream = sourceStream

    // The first collector becomes primary, drives the upstream live, and on
    // successful completion publishes the captured events here; other collectors
    // await + replay it (cancellable) instead of suspending on a lock held
    // across the whole run. Reset to null on failure/cancel so a later
    // collector can retry as primary.
    private val primaryResult = AtomicReference<CompletableDeferred<List<StreamEvent>>?>(null)
    private var capturedWarnings: List<CallWarning> = emptyList()
    private var capturedResponse: LanguageModelResponseMetadata = initialResponse

    /**
     * Memoised replay of the upstream events. The upstream is collected at
     * most once; later collectors replay the captured events. This differs
     * from the cold top-level [streamText], which drives a fresh upstream per
     * collection. Capture commits only on successful completion, so a
     * cancelled collection never memoises a truncated or duplicated replay.
     */
    public val fullStream: Flow<StreamEvent> = flow {
        while (true) {
            val mine = CompletableDeferred<List<StreamEvent>>()
            if (primaryResult.compareAndSet(null, mine)) {
                // Primary: drive the upstream live, memoise only on success.
                val buffer = mutableListOf<StreamEvent>()
                try {
                    upstream.collect { event ->
                        currentCoroutineContext().ensureActive()
                        buffer += event
                        emit(event)
                    }
                } catch (t: Throwable) {
                    primaryResult.compareAndSet(mine, null) // release so a retry can re-collect
                    mine.completeExceptionally(t)
                    throw t
                }
                commit(buffer)
                mine.complete(buffer.toList())
                return@flow
            }
            val existing = primaryResult.load()
            if (existing != null) {
                existing.await().forEach { emit(it) }
                return@flow
            }
            // Primary released the slot between the CAS and the load → retry.
        }
    }

    public val textStream: Flow<String> = fullStream
        .filterIsInstance<StreamEvent.TextDelta>()
        .map { it.text }

    public val warnings: Flow<List<CallWarning>> = flow {
        ensureCollected()
        emit(capturedWarnings)
    }

    public val response: Flow<LanguageModelResponseMetadata> = flow {
        ensureCollected()
        emit(capturedResponse)
    }

    public fun toTextStreamResponse(): ai.torad.aisdk.ui.TextStreamResponse =
        ai.torad.aisdk.ui.createTextStreamResponse(textStream)

    public fun toUiMessageStream(assistantMessageId: String): Flow<ai.torad.aisdk.ui.UIMessage> =
        ai.torad.aisdk.ui.streamToUiMessages(fullStream, assistantMessageId)

    public fun toUiMessageStreamResponse(assistantMessageId: String): ai.torad.aisdk.ui.UIMessageStreamResponse =
        ai.torad.aisdk.ui.createUiMessageStreamResponse(toUiMessageStream(assistantMessageId))

    private suspend fun ensureCollected() {
        // Drives the primary collection (or replays it); commit() has then run.
        fullStream.collect { }
    }

    /**
     * Derive the memoised warnings/response from a fully-collected buffer.
     * Only invoked after the upstream completes normally.
     */
    private fun commit(buffer: List<StreamEvent>) {
        capturedWarnings = buffer.asSequence()
            .filterIsInstance<StreamEvent.StreamStart>()
            .map { it.warnings }
            .firstOrNull { it.isNotEmpty() }
            ?: emptyList()
        var response = initialResponse
        for (event in buffer) {
            if (event is StreamEvent.ResponseMetadata) {
                response = response.merge(event.toLanguageModelResponseMetadata())
            }
        }
        capturedResponse = response
    }
}

public fun streamTextResult(
    model: LanguageModel,
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
    output: Output<*>? = null,
    presencePenalty: Float? = null,
    frequencyPenalty: Float? = null,
    responseFormat: ResponseFormat = ResponseFormat.Text,
): StreamTextResult {
    val params = streamTextCallParams(
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
    )
    val result = model.streamResult(params)
    return StreamTextResult(
        sourceStream = result.stream,
        request = result.request,
        initialResponse = result.response,
    )
}

private fun streamTextCallParams(
    prompt: String?,
    messages: List<ModelMessage>,
    system: String?,
    temperature: Float?,
    topP: Float?,
    topK: Int?,
    maxOutputTokens: Int?,
    stopSequences: List<String>,
    seed: Int?,
    providerOptions: Map<String, JsonElement>,
    abortSignal: AbortSignal,
    output: Output<*>?,
    presencePenalty: Float?,
    frequencyPenalty: Float?,
    responseFormat: ResponseFormat,
): LanguageModelCallParams {
    require(prompt != null || messages.isNotEmpty()) {
        "streamText: must provide either `prompt` or `messages`"
    }
    val effectiveMessages = buildList {
        if (system != null) add(systemMessage(system))
        addAll(messages)
        if (prompt != null) add(userMessage(prompt))
    }
    return LanguageModelCallParams(
        messages = effectiveMessages,
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
        responseFormat = output?.let { structuredOutput ->
            if (responseFormat == ResponseFormat.Text) structuredOutput.toResponseFormat() else responseFormat
        } ?: responseFormat,
    )
}

private fun StreamEvent.ResponseMetadata.toLanguageModelResponseMetadata(): LanguageModelResponseMetadata =
    LanguageModelResponseMetadata(
        id = id,
        timestampMillis = timestampMillis,
        modelId = modelId,
        headers = headers,
        body = body,
    )

private fun LanguageModelResponseMetadata.merge(
    other: LanguageModelResponseMetadata,
): LanguageModelResponseMetadata = LanguageModelResponseMetadata(
    id = other.id ?: id,
    timestampMillis = other.timestampMillis ?: timestampMillis,
    modelId = other.modelId ?: modelId,
    headers = headers + other.headers,
    body = other.body ?: body,
)

/**
 * Deprecated v6 compatibility helper. Prefer [generateText] with
 * `output = ...`; this wrapper exists for call sites that still use
 * the object-generation vocabulary.
 */
@Deprecated("Use generateText(output = ...) instead.")
public suspend fun <TOutput> generateObject(
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
): GenerateObjectResult<TOutput> {
    val result = generateText(
        model = model,
        prompt = prompt,
        messages = messages,
        system = system,
        output = output,
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
        responseFormat = responseFormat,
    )
    return GenerateObjectResult(
        value = result.output,
        text = result.text,
        reasoning = result.reasoningText,
        finishReason = result.finishReason,
        usage = result.usage,
        warnings = result.warnings,
        request = result.request,
        response = result.response,
        providerMetadata = result.providerMetadata,
    )
}

public data class GenerateObjectResult<TOutput>(
    val value: TOutput,
    val text: String,
    val reasoning: String? = null,
    val finishReason: FinishReason,
    val usage: Usage,
    val warnings: List<CallWarning> = emptyList(),
    val request: LanguageModelRequestMetadata = LanguageModelRequestMetadata(),
    val response: LanguageModelResponseMetadata = LanguageModelResponseMetadata(),
    val providerMetadata: Map<String, JsonElement> = emptyMap(),
) {
    val output: TOutput get() = value
    val generatedObject: TOutput get() = value
}

/**
 * Deprecated v6 compatibility helper. Prefer [streamText] with
 * `output = ...`; the KMP core streams typed [StreamEvent] values
 * instead of returning a browser/Node stream facade.
 */
@Deprecated("Use streamText(output = ...) instead.")
public fun <TOutput> streamObject(
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
): Flow<StreamEvent> = streamText(
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
)
