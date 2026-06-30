package ai.torad.aisdk.middleware

import ai.torad.aisdk.LanguageModel
import ai.torad.aisdk.LanguageModelCallParams
import ai.torad.aisdk.LanguageModelMiddleware
import ai.torad.aisdk.LanguageModelTool
import ai.torad.aisdk.MiddlewareOperation
import ai.torad.aisdk.ResponseFormat
import ai.torad.aisdk.ToolChoice
import ai.torad.aisdk.ProviderOptions

/**
 * Applies default per-call settings to every model invocation. Explicitly
 * provided params override defaults; provider option maps are merged.
 *
 * Implemented via [LanguageModelMiddleware.transformParams] (the params-only seam),
 * so the defaults apply uniformly to both generate and stream — matching v6's
 * `defaultSettingsMiddleware`. Use cases:
 *   - app-wide `temperature = 0.7`, `maxOutputTokens = 1000`
 *   - default `tools` / `toolChoice` / `headers`
 *   - default `providerOptions` like `{ openai: { reasoningEffort: "high" } }`
 */
public fun DefaultSettingsMiddleware(
    temperature: Float? = null,
    topP: Float? = null,
    topK: Int? = null,
    maxOutputTokens: Int? = null,
    stopSequences: List<String> = emptyList(),
    seed: Int? = null,
    tools: List<LanguageModelTool> = emptyList(),
    toolChoice: ToolChoice? = null,
    headers: Map<String, String> = emptyMap(),
    providerOptions: ProviderOptions = ProviderOptions.None,
    presencePenalty: Float? = null,
    frequencyPenalty: Float? = null,
    responseFormat: ResponseFormat = ResponseFormat.Text,
): LanguageModelMiddleware = object : LanguageModelMiddleware {
    override suspend fun transformParams(
        operation: MiddlewareOperation,
        params: LanguageModelCallParams,
        model: LanguageModel,
    ): LanguageModelCallParams = params.toBuilder()
        .temperature(params.temperature ?: temperature)
        .topP(params.topP ?: topP)
        .topK(params.topK ?: topK)
        .maxOutputTokens(params.maxOutputTokens ?: maxOutputTokens)
        .stopSequences(params.stopSequences.ifEmpty { stopSequences })
        .seed(params.seed ?: seed)
        .tools(params.tools.ifEmpty { tools })
        .toolChoice(if (params.toolChoice == ToolChoice.Auto && toolChoice != null) toolChoice else params.toolChoice)
        .headers(headers + params.headers)
        .providerOptions(providerOptions.mergedWith(params.providerOptions))
        .presencePenalty(params.presencePenalty ?: presencePenalty)
        .frequencyPenalty(params.frequencyPenalty ?: frequencyPenalty)
        .responseFormat(if (params.responseFormat == ResponseFormat.Text) responseFormat else params.responseFormat)
        .build()
}
