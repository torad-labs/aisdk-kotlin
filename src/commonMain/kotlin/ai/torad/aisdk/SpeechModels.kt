@file:kotlin.jvm.JvmName("MediaModelsKt")
@file:kotlin.jvm.JvmMultifileClass

package ai.torad.aisdk

import dev.drewhamilton.poko.Poko

/** @since 0.3.0-beta01 */
public interface SpeechModel {
    /** @since 0.3.0-beta01 */
    public val modelId: String

    /** @since 0.3.0-beta01 */
    public val provider: String
        get() = "unknown"

    public suspend fun generate(params: SpeechGenerationParams): SpeechModelResult
}

/** @since 0.3.0-beta01 */
public class SpeechGenerationParams internal constructor(
    /** @since 0.3.0-beta01 */
    public val text: String,
    /** @since 0.3.0-beta01 */
    public val voice: String? = null,
    /** @since 0.3.0-beta01 */
    public val instructions: String? = null,
    /** @since 0.3.0-beta01 */
    public val speed: Float? = null,
    /** @since 0.3.0-beta01 */
    public val responseFormat: String? = null,
    /**
     * ISO 639-1 language code (e.g. "en") or "auto". Mirrors upstream's `language`.
     * @since 0.3.0-beta01
     */
    public val language: String? = null,
    /** @since 0.3.0-beta01 */
    public val providerOptions: ProviderOptions = ProviderOptions.None,
    /** @since 0.3.0-beta01 */
    public val headers: Map<String, String> = emptyMap(),
    /** @since 0.3.0-beta01 */
    public val abortSignal: AbortSignal = AbortSignalNever,
)

/** @since 0.3.0-beta01 */
public class SpeechGenerationParamsBuilder {
    private var text: String? = null
    private var voice: String? = null
    private var instructions: String? = null
    private var speed: Float? = null
    private var responseFormat: String? = null
    private var language: String? = null
    private var providerOptions: ProviderOptions = ProviderOptions.None
    private var headers: Map<String, String> = emptyMap()
    private var abortSignal: AbortSignal = AbortSignalNever

    /** @since 0.3.0-beta01 */
    public fun text(value: String): SpeechGenerationParamsBuilder {
        text = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun voice(value: String?): SpeechGenerationParamsBuilder {
        voice = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun instructions(value: String?): SpeechGenerationParamsBuilder {
        instructions = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun speed(value: Float?): SpeechGenerationParamsBuilder {
        speed = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun responseFormat(value: String?): SpeechGenerationParamsBuilder {
        responseFormat = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun language(value: String?): SpeechGenerationParamsBuilder {
        language = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun providerOptions(value: ProviderOptions): SpeechGenerationParamsBuilder {
        providerOptions = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun headers(value: Map<String, String>): SpeechGenerationParamsBuilder {
        headers = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun abortSignal(value: AbortSignal): SpeechGenerationParamsBuilder {
        abortSignal = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun build(): SpeechGenerationParams =
        SpeechGenerationParams(
            text = requireNotNull(text) { "SpeechGenerationParams.text is required" },
            voice = voice,
            instructions = instructions,
            speed = speed,
            responseFormat = responseFormat,
            language = language,
            providerOptions = providerOptions,
            headers = headers,
            abortSignal = abortSignal,
        )
}

/** @since 0.3.0-beta01 */
public fun SpeechGenerationParams(
    block: SpeechGenerationParamsBuilder.() -> Unit = {},
): SpeechGenerationParams =
    SpeechGenerationParamsBuilder().apply(block).build()

@Poko
/** @since 0.3.0-beta01 */
public class SpeechModelResult(
    /** @since 0.3.0-beta01 */
    public val audio: GeneratedFile?,
    /** @since 0.3.0-beta01 */
    public val warnings: List<CallWarning> = emptyList(),
    /** @since 0.3.0-beta01 */
    public val response: LanguageModelResponseMetadata = LanguageModelResponseMetadata(),
    /** @since 0.3.0-beta01 */
    public val providerMetadata: ProviderMetadata = ProviderMetadata.None,
)

@Poko
/** @since 0.3.0-beta01 */
public class GenerateSpeechResult(
    /** @since 0.3.0-beta01 */
    public val audio: GeneratedFile,
    /** @since 0.3.0-beta01 */
    public val warnings: List<CallWarning> = emptyList(),
    /** @since 0.3.0-beta01 */
    public val response: LanguageModelResponseMetadata = LanguageModelResponseMetadata(),
    /** @since 0.3.0-beta01 */
    public val providerMetadata: ProviderMetadata = ProviderMetadata.None,
    /** @since 0.3.0-beta01 */
    public val responses: List<LanguageModelResponseMetadata> = listOf(response),
)

/** @since 0.3.0-beta01 */
public object SpeechGeneration {
    public suspend fun generateSpeech(
        model: SpeechModel,
        text: String,
        voice: String? = null,
        instructions: String? = null,
        speed: Float? = null,
        responseFormat: String? = null,
        language: String? = null,
        providerOptions: ProviderOptions = ProviderOptions.None,
        headers: Map<String, String> = emptyMap(),
        abortSignal: AbortSignal = AbortSignalNever,
        logger: Logger = NoopLogger,
    ): GenerateSpeechResult {
        require(text.isNotBlank()) { "generateSpeech: text must not be blank" }
        val result = model.generate(
            SpeechGenerationParams {
                text(text)
                voice(voice)
                instructions(instructions)
                speed(speed)
                responseFormat(responseFormat)
                language(language)
                providerOptions(providerOptions)
                headers(headers)
                abortSignal(abortSignal)
            },
        )
        result.warnings.forEach { logger.warn(it.format()) }
        return GenerateSpeechResult(
            audio = result.audio ?: throw NoSpeechGeneratedError(),
            warnings = result.warnings,
            response = result.response,
            providerMetadata = result.providerMetadata,
            responses = listOf(result.response),
        )
    }

    @ExperimentalAiSdkApi
    @Suppress("FunctionNaming")
    public suspend fun experimental_generateSpeech(
        model: SpeechModel,
        text: String,
        voice: String? = null,
        instructions: String? = null,
        speed: Float? = null,
        responseFormat: String? = null,
        providerOptions: ProviderOptions = ProviderOptions.None,
        headers: Map<String, String> = emptyMap(),
        abortSignal: AbortSignal = AbortSignalNever,
    ): GenerateSpeechResult = generateSpeech(
        model = model,
        text = text,
        voice = voice,
        instructions = instructions,
        speed = speed,
        responseFormat = responseFormat,
        providerOptions = providerOptions,
        headers = headers,
        abortSignal = abortSignal,
    )
}

/** @since 0.3.0-beta01 */
