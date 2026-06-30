package ai.torad.aisdk

import dev.drewhamilton.poko.Poko

@Poko
public class CallConfig internal constructor(
    public val temperature: Float? = null,
    public val topP: Float? = null,
    public val topK: Int? = null,
    public val maxOutputTokens: Int? = null,
    public val stopSequences: List<String> = emptyList(),
    public val seed: Int? = null,
    public val providerOptions: ProviderOptions = ProviderOptions.None,
    public val abortSignal: AbortSignal = AbortSignalNever,
    public val presencePenalty: Float? = null,
    public val frequencyPenalty: Float? = null,
    public val responseFormat: ResponseFormat = ResponseFormat.Text,
    public val maxRetries: Int = 2,
) {
    init {
        require(maxRetries >= 0) { "maxRetries must be >= 0" }
    }
}

@AiSdkDsl
public class CallConfigBuilder internal constructor() {
    private var temperature: Float? = null
    private var topP: Float? = null
    private var topK: Int? = null
    private var maxOutputTokens: Int? = null
    private var stopSequences: List<String> = emptyList()
    private var seed: Int? = null
    private var providerOptions: ProviderOptions = ProviderOptions.None
    private var abortSignal: AbortSignal = AbortSignalNever
    private var presencePenalty: Float? = null
    private var frequencyPenalty: Float? = null
    private var responseFormat: ResponseFormat = ResponseFormat.Text
    private var maxRetries: Int = 2

    public fun temperature(value: Float?) {
        temperature = value
    }

    public fun topP(value: Float?) {
        topP = value
    }

    public fun topK(value: Int?) {
        topK = value
    }

    public fun maxOutputTokens(value: Int?) {
        maxOutputTokens = value
    }

    public fun stopSequences(value: List<String>) {
        stopSequences = value
    }

    public fun seed(value: Int?) {
        seed = value
    }

    public fun providerOptions(value: ProviderOptions) {
        providerOptions = value
    }

    public fun abortSignal(value: AbortSignal) {
        abortSignal = value
    }

    public fun presencePenalty(value: Float?) {
        presencePenalty = value
    }

    public fun frequencyPenalty(value: Float?) {
        frequencyPenalty = value
    }

    public fun responseFormat(value: ResponseFormat) {
        responseFormat = value
    }

    public fun maxRetries(value: Int) {
        maxRetries = value
    }

    internal fun build(): CallConfig =
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
            responseFormat = responseFormat,
            maxRetries = maxRetries,
        )
}

public fun CallConfig(block: CallConfigBuilder.() -> Unit = {}): CallConfig =
    CallConfigBuilder().apply(block).build()
