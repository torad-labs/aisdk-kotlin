package ai.torad.aisdk

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

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
    )
}

data class GenerateTextResult<TOutput>(
    val output: TOutput,
    val text: String,
    val toolCalls: List<ContentPart.ToolCall>,
    val finishReason: FinishReason,
    val usage: Usage,
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
    )
    model.stream(params).collect { emit(it) }
}
