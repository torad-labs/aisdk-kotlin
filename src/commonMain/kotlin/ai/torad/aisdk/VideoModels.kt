@file:kotlin.jvm.JvmName("MediaModelsKt")
@file:kotlin.jvm.JvmMultifileClass

package ai.torad.aisdk

import dev.drewhamilton.poko.Poko
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlin.jvm.JvmOverloads

public interface VideoModel {
    /** @since 0.3.0-beta01 */
    public val modelId: String
    /** @since 0.3.0-beta01 */
    public val provider: String
        get() = "unknown"

    /**
     * How many videos this model produces per call, if limited (see [ImageModel.maxImagesPerCall]).
     * @since 0.3.0-beta01
     */
    public val maxVideosPerCall: Int?
        get() = null

    public suspend fun generate(params: VideoGenerationParams): VideoModelResult
}

/** @since 0.3.0-beta01 */
public class VideoGenerationParams internal constructor(
    /** @since 0.3.0-beta01 */
    public val prompt: String,
    /** @since 0.3.0-beta01 */
    public val n: Int = 1,
    /** @since 0.3.0-beta01 */
    public val image: GeneratedFile? = null,
    /** @since 0.3.0-beta01 */
    public val durationSeconds: Float? = null,
    /** @since 0.3.0-beta01 */
    public val size: String? = null,
    /** @since 0.3.0-beta01 */
    public val aspectRatio: String? = null,
    /** @since 0.3.0-beta01 */
    public val providerOptions: ProviderOptions = ProviderOptions.None,
    /** @since 0.3.0-beta01 */
    public val headers: Map<String, String> = emptyMap(),
    /** @since 0.3.0-beta01 */
    public val abortSignal: AbortSignal = AbortSignalNever,
    /** @since 0.3.0-beta01 */
    public val seed: Int? = null,
    /** @since 0.3.0-beta01 */
    public val fps: Int? = null,
    /** @since 0.3.0-beta01 */
    public val resolution: String? = null,
)

/** @since 0.3.0-beta01 */
public class VideoGenerationParamsBuilder {
    private var prompt: String? = null
    private var n: Int = 1
    private var image: GeneratedFile? = null
    private var durationSeconds: Float? = null
    private var size: String? = null
    private var aspectRatio: String? = null
    private var providerOptions: ProviderOptions = ProviderOptions.None
    private var headers: Map<String, String> = emptyMap()
    private var abortSignal: AbortSignal = AbortSignalNever
    private var seed: Int? = null
    private var fps: Int? = null
    private var resolution: String? = null

    /** @since 0.3.0-beta01 */
    public fun prompt(value: String): VideoGenerationParamsBuilder {
        prompt = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun n(value: Int): VideoGenerationParamsBuilder {
        n = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun image(value: GeneratedFile?): VideoGenerationParamsBuilder {
        image = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun durationSeconds(value: Float?): VideoGenerationParamsBuilder {
        durationSeconds = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun size(value: String?): VideoGenerationParamsBuilder {
        size = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun aspectRatio(value: String?): VideoGenerationParamsBuilder {
        aspectRatio = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun providerOptions(value: ProviderOptions): VideoGenerationParamsBuilder {
        providerOptions = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun headers(value: Map<String, String>): VideoGenerationParamsBuilder {
        headers = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun abortSignal(value: AbortSignal): VideoGenerationParamsBuilder {
        abortSignal = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun seed(value: Int?): VideoGenerationParamsBuilder {
        seed = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun fps(value: Int?): VideoGenerationParamsBuilder {
        fps = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun resolution(value: String?): VideoGenerationParamsBuilder {
        resolution = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun build(): VideoGenerationParams =
        VideoGenerationParams(
            prompt = requireNotNull(prompt) { "VideoGenerationParams.prompt is required" },
            n = n,
            image = image,
            durationSeconds = durationSeconds,
            size = size,
            aspectRatio = aspectRatio,
            providerOptions = providerOptions,
            headers = headers,
            abortSignal = abortSignal,
            seed = seed,
            fps = fps,
            resolution = resolution,
        )
}

/** @since 0.3.0-beta01 */
public fun VideoGenerationParams(
    block: VideoGenerationParamsBuilder.() -> Unit = {},
): VideoGenerationParams =
    VideoGenerationParamsBuilder().apply(block).build()

@Poko
/** @since 0.3.0-beta01 */
public class VideoModelResult(
    /** @since 0.3.0-beta01 */
    public val videos: List<GeneratedFile>,
    /** @since 0.3.0-beta01 */
    public val warnings: List<CallWarning> = emptyList(),
    /** @since 0.3.0-beta01 */
    public val response: LanguageModelResponseMetadata = LanguageModelResponseMetadata(),
    /** @since 0.3.0-beta01 */
    public val providerMetadata: ProviderMetadata = ProviderMetadata.None,
)

@Poko
/** @since 0.3.0-beta01 */
public class GenerateVideoResult(
    /** @since 0.3.0-beta01 */
    public val videos: List<GeneratedFile>,
    /** @since 0.3.0-beta01 */
    public val warnings: List<CallWarning> = emptyList(),
    /** @since 0.3.0-beta01 */
    public val response: LanguageModelResponseMetadata = LanguageModelResponseMetadata(),
    /** @since 0.3.0-beta01 */
    public val providerMetadata: ProviderMetadata = ProviderMetadata.None,
    /**
     * Per-call response metadata, one entry per underlying model call (n-batching).
     * @since 0.3.0-beta01
     */
    public val responses: List<LanguageModelResponseMetadata> = listOf(response),
) {
    /** @since 0.3.0-beta01 */
    public val video: GeneratedFile get() = videos.firstOrNull() ?: throw NoVideoGeneratedError()
}

/** @since 0.3.0-beta01 */
public object VideoGeneration {
    @Suppress("LongParameterList")
    public suspend fun generateVideo(
        model: VideoModel,
        prompt: String,
        n: Int = 1,
        image: GeneratedFile? = null,
        durationSeconds: Float? = null,
        size: String? = null,
        aspectRatio: String? = null,
        providerOptions: ProviderOptions = ProviderOptions.None,
        headers: Map<String, String> = emptyMap(),
        abortSignal: AbortSignal = AbortSignalNever,
        seed: Int? = null,
        fps: Int? = null,
        resolution: String? = null,
        logger: Logger = NoopLogger,
        maxParallelCalls: Int = DEFAULT_MAX_PARALLEL_CALLS,
    ): GenerateVideoResult {
        require(prompt.isNotBlank()) { "generateVideo: prompt must not be blank" }
        require(n > 0) { "generateVideo: n must be > 0" }
        val paramsFor: (Int) -> VideoGenerationParams = { count ->
            VideoGenerationParams {
                prompt(prompt)
                n(count)
                image(image)
                durationSeconds(durationSeconds)
                size(size)
                aspectRatio(aspectRatio)
                providerOptions(providerOptions)
                headers(headers)
                abortSignal(abortSignal)
                seed(seed)
                fps(fps)
                resolution(resolution)
            }
        }
        // Split into ceil(n / maxVideosPerCall) calls when the model is limited.
        val maxPerCall = model.maxVideosPerCall?.coerceAtLeast(1) ?: n
        val counts = if (n <= maxPerCall) {
            listOf(n)
        } else {
            List(n / maxPerCall) { maxPerCall } + listOfNotNull((n % maxPerCall).takeIf { it > 0 })
        }
        val results = if (counts.size == 1) {
            listOf(model.generate(paramsFor(n)))
        } else {
            BoundedParallel.map(counts, maxParallelCalls) { model.generate(paramsFor(it)) }
        }
        val videos = results.flatMap { it.videos }
        if (videos.isEmpty()) throw NoVideoGeneratedError(responses = results.map { it.response })
        results.flatMap { it.warnings }.forEach { logger.warn(it.format()) }
        return GenerateVideoResult(
            videos = videos,
            warnings = results.flatMap { it.warnings },
            response = results.first().response,
            providerMetadata = results.firstNotNullOfOrNull { (it.providerMetadata as? ProviderMetadata.Raw) } ?: ProviderMetadata.None,
            responses = results.map { it.response },
        )
    }

    @ExperimentalAiSdkApi
    @Suppress("FunctionNaming", "LongParameterList")
    public suspend fun experimental_generateVideo(
        model: VideoModel,
        prompt: String,
        n: Int = 1,
        image: GeneratedFile? = null,
        durationSeconds: Float? = null,
        size: String? = null,
        aspectRatio: String? = null,
        providerOptions: ProviderOptions = ProviderOptions.None,
        headers: Map<String, String> = emptyMap(),
        abortSignal: AbortSignal = AbortSignalNever,
        seed: Int? = null,
        fps: Int? = null,
        resolution: String? = null,
        maxParallelCalls: Int = DEFAULT_MAX_PARALLEL_CALLS,
    ): GenerateVideoResult = generateVideo(
        model = model,
        prompt = prompt,
        n = n,
        image = image,
        durationSeconds = durationSeconds,
        size = size,
        aspectRatio = aspectRatio,
        providerOptions = providerOptions,
        headers = headers,
        abortSignal = abortSignal,
        seed = seed,
        fps = fps,
        resolution = resolution,
        maxParallelCalls = maxParallelCalls,
    )
}
