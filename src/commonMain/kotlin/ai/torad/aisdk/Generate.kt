package ai.torad.aisdk

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonElement

/**
 * One-shot generation — invariant I-3, the only `text` entry point in v6.
 *
 * For tool-loop agents, use [Agent.generate] instead. This is the
 * primitive for single-turn calls (no tool-loop wrapping).
 */
suspend fun <TOutput> generateText(
    model: LanguageModel,
    prompt: String? = null,
    messages: List<ModelMessage> = emptyList(),
    system: String? = null,
    output: Output<TOutput>? = null,
    temperature: Float? = null,
    topP: Float? = null,
    topK: Int? = null,
    maxOutputTokens: Int? = null,
    stopSequences: List<String> = emptyList(),
    seed: Int? = null,
    providerOptions: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap(),
    abortSignal: AbortSignal = AbortSignalNever,
    presencePenalty: Float? = null,
    frequencyPenalty: Float? = null,
    responseFormat: ResponseFormat = ResponseFormat.Text,
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
    @Suppress("UNCHECKED_CAST")
    val typed: TOutput = output?.decode(raw.text) ?: (raw.text as TOutput)
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

data class GenerateTextResult<TOutput>(
    val output: TOutput,
    val text: String,
    val toolCalls: List<ContentPart.ToolCall>,
    val finishReason: FinishReason,
    val usage: Usage,
    val content: List<ContentPart> = buildList {
        if (text.isNotEmpty()) add(ContentPart.Text(text))
        addAll(toolCalls)
    },
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
)

/**
 * Streaming generation — invariant I-3. Returns a cold Flow that drives
 * one upstream call when collected. For tool-loop agents, use
 * [Agent.stream] instead.
 */
fun streamText(
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
    require(prompt != null || messages.isNotEmpty()) {
        "streamText: must provide either `prompt` or `messages`"
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
    model.stream(params).collect { emit(it) }
}

/**
 * Deprecated v6 compatibility helper. Prefer [generateText] with
 * `output = ...`; this wrapper exists for call sites that still use
 * the object-generation vocabulary.
 */
@Deprecated("Use generateText(output = ...) instead.")
suspend fun <TOutput> generateObject(
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

data class GenerateObjectResult<TOutput>(
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
fun <TOutput> streamObject(
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
