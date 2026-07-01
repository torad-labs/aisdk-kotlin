package ai.torad.aisdk

import dev.drewhamilton.poko.Poko
import kotlin.time.Duration

@Poko
/** @since 0.3.0-beta01 */
public class CallConfig internal constructor(
    /** @since 0.3.0-beta01 */
    public val temperature: Float? = null,
    /** @since 0.3.0-beta01 */
    public val topP: Float? = null,
    /** @since 0.3.0-beta01 */
    public val topK: Int? = null,
    /** @since 0.3.0-beta01 */
    public val maxOutputTokens: Int? = null,
    /** @since 0.3.0-beta01 */
    public val stopSequences: List<String> = emptyList(),
    /** @since 0.3.0-beta01 */
    public val seed: Int? = null,
    /** @since 0.3.0-beta01 */
    public val providerOptions: ProviderOptions = ProviderOptions.None,
    /** @since 0.3.0-beta01 */
    public val abortSignal: AbortSignal = AbortSignalNever,
    /** @since 0.3.0-beta01 */
    public val presencePenalty: Float? = null,
    /** @since 0.3.0-beta01 */
    public val frequencyPenalty: Float? = null,
    /** @since 0.3.0-beta01 */
    public val responseFormat: ResponseFormat = ResponseFormat.Text,
    /** @since 0.3.0-beta01 */
    public val headers: Map<String, String> = emptyMap(),
    private val timeoutBox: Any? = null,
    /** @since 0.3.0-beta01 */
    public val maxRetries: Int = 2,
) {
    /**
     * Optional total timeout for one high-level model call. For streaming calls this bounds the
     * full collection lifetime, not the idle gap between individual events.
     * @since 0.3.0-beta01
     */
    public val timeout: Duration?
        get() = timeoutBox as Duration?

    init {
        require(maxRetries >= 0) { "maxRetries must be >= 0" }
        require(timeout == null || timeout?.isPositive() == true) {
            "timeout must be positive when set"
        }
    }
}

@AiSdkDsl
/** @since 0.3.0-beta01 */
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
    private var timeout: Duration? = null
    private var maxRetries: Int = 2
    private val headers = linkedMapOf<String, String>()

    /** @since 0.3.0-beta01 */
    public fun temperature(value: Float?): CallConfigBuilder {
        temperature = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun topP(value: Float?): CallConfigBuilder {
        topP = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun topK(value: Int?): CallConfigBuilder {
        topK = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun maxOutputTokens(value: Int?): CallConfigBuilder {
        maxOutputTokens = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun stopSequences(value: List<String>): CallConfigBuilder {
        stopSequences = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun seed(value: Int?): CallConfigBuilder {
        seed = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun providerOptions(value: ProviderOptions): CallConfigBuilder {
        providerOptions = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun abortSignal(value: AbortSignal): CallConfigBuilder {
        abortSignal = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun presencePenalty(value: Float?): CallConfigBuilder {
        presencePenalty = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun frequencyPenalty(value: Float?): CallConfigBuilder {
        frequencyPenalty = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun responseFormat(value: ResponseFormat): CallConfigBuilder {
        responseFormat = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun headers(value: Map<String, String>): CallConfigBuilder {
        headers.clear()
        headers.putAll(value)
        return this
    }

    /**
     * Set a total timeout for one high-level call. For streaming calls this bounds the whole
     * stream collection, not the time between chunks.
     * @since 0.3.0-beta01
     */
    public fun timeout(value: Duration?): CallConfigBuilder {
        timeout = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun maxRetries(value: Int): CallConfigBuilder {
        maxRetries = value
        return this
    }

    /** @since 0.3.0-beta01 */
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
            headers = headers.toMap(),
            timeoutBox = timeout as Any?,
            maxRetries = maxRetries,
        )
}

/** @since 0.3.0-beta01 */
public fun CallConfig(block: CallConfigBuilder.() -> Unit = {}): CallConfig =
    CallConfigBuilder().apply(block).build()
