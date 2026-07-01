@file:kotlin.jvm.JvmName("MediaModelsKt")
@file:kotlin.jvm.JvmMultifileClass

package ai.torad.aisdk

import dev.drewhamilton.poko.Poko
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlin.jvm.JvmOverloads

/** @since 0.3.0-beta01 */
public interface TranscriptionModel {
    /** @since 0.3.0-beta01 */
    public val modelId: String
    /** @since 0.3.0-beta01 */
    public val provider: String
        get() = "unknown"

    public suspend fun transcribe(params: TranscriptionParams): TranscriptionModelResult
}

/** @since 0.3.0-beta01 */
public data class AudioSource(
    val mediaType: String,
    val base64: String,
    val filename: String? = null,
)

/** @since 0.3.0-beta01 */
public class TranscriptionParams internal constructor(
    /** @since 0.3.0-beta01 */
    public val audio: AudioSource,
    /** @since 0.3.0-beta01 */
    public val language: String? = null,
    /** @since 0.3.0-beta01 */
    public val prompt: String? = null,
    /** @since 0.3.0-beta01 */
    public val providerOptions: ProviderOptions = ProviderOptions.None,
    /** @since 0.3.0-beta01 */
    public val headers: Map<String, String> = emptyMap(),
    /** @since 0.3.0-beta01 */
    public val abortSignal: AbortSignal = AbortSignalNever,
)

/** @since 0.3.0-beta01 */
public class TranscriptionParamsBuilder {
    private var audio: AudioSource? = null
    private var language: String? = null
    private var prompt: String? = null
    private var providerOptions: ProviderOptions = ProviderOptions.None
    private var headers: Map<String, String> = emptyMap()
    private var abortSignal: AbortSignal = AbortSignalNever

    /** @since 0.3.0-beta01 */
    public fun audio(value: AudioSource): TranscriptionParamsBuilder {
        audio = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun language(value: String?): TranscriptionParamsBuilder {
        language = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun prompt(value: String?): TranscriptionParamsBuilder {
        prompt = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun providerOptions(value: ProviderOptions): TranscriptionParamsBuilder {
        providerOptions = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun headers(value: Map<String, String>): TranscriptionParamsBuilder {
        headers = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun abortSignal(value: AbortSignal): TranscriptionParamsBuilder {
        abortSignal = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun build(): TranscriptionParams =
        TranscriptionParams(
            audio = requireNotNull(audio) { "TranscriptionParams.audio is required" },
            language = language,
            prompt = prompt,
            providerOptions = providerOptions,
            headers = headers,
            abortSignal = abortSignal,
        )
}

/** @since 0.3.0-beta01 */
public fun TranscriptionParams(
    block: TranscriptionParamsBuilder.() -> Unit = {},
): TranscriptionParams =
    TranscriptionParamsBuilder().apply(block).build()

@Poko
/** @since 0.3.0-beta01 */
public class TranscriptSegment(
    /** @since 0.3.0-beta01 */
    public val text: String,
    /** @since 0.3.0-beta01 */
    public val startSeconds: Float? = null,
    /** @since 0.3.0-beta01 */
    public val endSeconds: Float? = null,
)

@Poko
/** @since 0.3.0-beta01 */
public class TranscriptionModelResult(
    /** @since 0.3.0-beta01 */
    public val text: String?,
    /** @since 0.3.0-beta01 */
    public val segments: List<TranscriptSegment> = emptyList(),
    /** @since 0.3.0-beta01 */
    public val warnings: List<CallWarning> = emptyList(),
    /** @since 0.3.0-beta01 */
    public val response: LanguageModelResponseMetadata = LanguageModelResponseMetadata(),
    /** @since 0.3.0-beta01 */
    public val providerMetadata: ProviderMetadata = ProviderMetadata.None,
    /**
     * Detected language as an ISO-639-1 code, or null if undetermined.
     * @since 0.3.0-beta01
     */
    public val language: String? = null,
    /**
     * Total audio duration in seconds, or null if undetermined.
     * @since 0.3.0-beta01
     */
    public val durationInSeconds: Float? = null,
)

@Poko
/** @since 0.3.0-beta01 */
public class TranscribeResult(
    /** @since 0.3.0-beta01 */
    public val text: String,
    /** @since 0.3.0-beta01 */
    public val segments: List<TranscriptSegment> = emptyList(),
    /** @since 0.3.0-beta01 */
    public val warnings: List<CallWarning> = emptyList(),
    /** @since 0.3.0-beta01 */
    public val response: LanguageModelResponseMetadata = LanguageModelResponseMetadata(),
    /** @since 0.3.0-beta01 */
    public val providerMetadata: ProviderMetadata = ProviderMetadata.None,
    /** @since 0.3.0-beta01 */
    public val responses: List<LanguageModelResponseMetadata> = listOf(response),
    /**
     * Detected language as an ISO-639-1 code, or null if undetermined.
     * @since 0.3.0-beta01
     */
    public val language: String? = null,
    /**
     * Total audio duration in seconds, or null if undetermined.
     * @since 0.3.0-beta01
     */
    public val durationInSeconds: Float? = null,
)

/** @since 0.3.0-beta01 */
public object Transcription {
    public suspend fun transcribe(
        model: TranscriptionModel,
        audio: AudioSource,
        language: String? = null,
        prompt: String? = null,
        providerOptions: ProviderOptions = ProviderOptions.None,
        headers: Map<String, String> = emptyMap(),
        abortSignal: AbortSignal = AbortSignalNever,
        logger: Logger = NoopLogger,
    ): TranscribeResult {
        require(audio.base64.isNotBlank()) { "transcribe: audio base64 must not be blank" }
        val result = model.transcribe(
            TranscriptionParams {
                audio(audio)
                language(language)
                prompt(prompt)
                providerOptions(providerOptions)
                headers(headers)
                abortSignal(abortSignal)
            },
        )
        result.warnings.forEach { logger.warn(it.format()) }
        return TranscribeResult(
            text = result.text ?: throw NoTranscriptGeneratedError(),
            segments = result.segments,
            warnings = result.warnings,
            response = result.response,
            providerMetadata = result.providerMetadata,
            responses = listOf(result.response),
            language = result.language,
            durationInSeconds = result.durationInSeconds,
        )
    }

    @ExperimentalAiSdkApi
    @Suppress("FunctionNaming")
    public suspend fun experimental_transcribe(
        model: TranscriptionModel,
        audio: AudioSource,
        language: String? = null,
        prompt: String? = null,
        providerOptions: ProviderOptions = ProviderOptions.None,
        headers: Map<String, String> = emptyMap(),
        abortSignal: AbortSignal = AbortSignalNever,
    ): TranscribeResult = transcribe(
        model = model,
        audio = audio,
        language = language,
        prompt = prompt,
        providerOptions = providerOptions,
        headers = headers,
        abortSignal = abortSignal,
    )
}

/** @since 0.3.0-beta01 */
