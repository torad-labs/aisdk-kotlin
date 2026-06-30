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
public class CallConfigBuilder {
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

    public fun temperature(value: Float?): CallConfigBuilder {
        temperature = value
        return this
    }

    public fun topP(value: Float?): CallConfigBuilder {
        topP = value
        return this
    }

    public fun topK(value: Int?): CallConfigBuilder {
        topK = value
        return this
    }

    public fun maxOutputTokens(value: Int?): CallConfigBuilder {
        maxOutputTokens = value
        return this
    }

    public fun stopSequences(value: List<String>): CallConfigBuilder {
        stopSequences = value
        return this
    }

    public fun seed(value: Int?): CallConfigBuilder {
        seed = value
        return this
    }

    public fun providerOptions(value: ProviderOptions): CallConfigBuilder {
        providerOptions = value
        return this
    }

    public fun abortSignal(value: AbortSignal): CallConfigBuilder {
        abortSignal = value
        return this
    }

    public fun presencePenalty(value: Float?): CallConfigBuilder {
        presencePenalty = value
        return this
    }

    public fun frequencyPenalty(value: Float?): CallConfigBuilder {
        frequencyPenalty = value
        return this
    }

    public fun responseFormat(value: ResponseFormat): CallConfigBuilder {
        responseFormat = value
        return this
    }

    public fun maxRetries(value: Int): CallConfigBuilder {
        maxRetries = value
        return this
    }

    public fun build(): CallConfig =
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
