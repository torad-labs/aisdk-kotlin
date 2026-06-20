package ai.torad.aisdk

import kotlinx.serialization.json.JsonElement

public data class CallConfig(
    val temperature: Float? = null,
    val topP: Float? = null,
    val topK: Int? = null,
    val maxOutputTokens: Int? = null,
    val stopSequences: List<String> = emptyList(),
    val seed: Int? = null,
    val providerOptions: ProviderOptions = ProviderOptions.None,
    val abortSignal: AbortSignal = AbortSignalNever,
    val presencePenalty: Float? = null,
    val frequencyPenalty: Float? = null,
    val responseFormat: ResponseFormat = ResponseFormat.Text,
)
