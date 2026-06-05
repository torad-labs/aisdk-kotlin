package ai.torad.aisdk.middleware

import ai.torad.aisdk.LanguageModelCallParams
import ai.torad.aisdk.LanguageModelMiddleware
import ai.torad.aisdk.LanguageModelResult
import ai.torad.aisdk.MiddlewareCallContext
import ai.torad.aisdk.ResponseFormat
import ai.torad.aisdk.StreamEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonElement

/**
 * Applies default per-call settings to every model invocation. Explicitly
 * provided params override defaults; provider option maps are merged.
 *
 * Mirrors v6's `defaultSettingsMiddleware`. Use cases:
 *   - app-wide `temperature = 0.7`
 *   - app-wide `maxOutputTokens = 1000`
 *   - default `providerOptions` like `{ openai: { reasoningEffort: "high" } }`
 */
public fun defaultSettingsMiddleware(
    temperature: Float? = null,
    topP: Float? = null,
    topK: Int? = null,
    maxOutputTokens: Int? = null,
    stopSequences: List<String> = emptyList(),
    seed: Int? = null,
    providerOptions: Map<String, JsonElement> = emptyMap(),
    presencePenalty: Float? = null,
    frequencyPenalty: Float? = null,
    responseFormat: ResponseFormat = ResponseFormat.Text,
): LanguageModelMiddleware = object : LanguageModelMiddleware {
    override suspend fun wrapGenerate(context: MiddlewareCallContext): LanguageModelResult =
        context.doGenerate(applyDefaults(context.params))

    override fun wrapStream(context: MiddlewareCallContext): Flow<StreamEvent> =
        context.doStream(applyDefaults(context.params))

    private fun applyDefaults(params: LanguageModelCallParams): LanguageModelCallParams = params.copy(
        temperature = params.temperature ?: temperature,
        topP = params.topP ?: topP,
        topK = params.topK ?: topK,
        maxOutputTokens = params.maxOutputTokens ?: maxOutputTokens,
        stopSequences = params.stopSequences.ifEmpty { stopSequences },
        seed = params.seed ?: seed,
        providerOptions = providerOptions + params.providerOptions,
        presencePenalty = params.presencePenalty ?: presencePenalty,
        frequencyPenalty = params.frequencyPenalty ?: frequencyPenalty,
        responseFormat = if (params.responseFormat == ResponseFormat.Text) responseFormat else params.responseFormat,
    )
}
