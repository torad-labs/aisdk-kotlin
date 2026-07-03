@file:kotlin.jvm.JvmName("MediaModelsKt")
@file:kotlin.jvm.JvmMultifileClass

package ai.torad.aisdk

import dev.drewhamilton.poko.Poko
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** @since 0.3.0-beta01 */
public interface ImageModel {
    /** @since 0.3.0-beta01 */
    public val modelId: String

    /** @since 0.3.0-beta01 */
    public val provider: String
        get() = "unknown"

    /**
     * How many images this model produces per call, if limited. `generateImage`
     * splits a request for more than this into ceil(n / limit) concurrent calls
     * (was: passing n straight through, so a model capped at 1 returned 1 of n).
     * Null = no limit.
     * @since 0.3.0-beta01
     */
    public val maxImagesPerCall: Int?
        get() = null

    public suspend fun generate(params: ImageGenerationParams): ImageModelResult
}

/** @since 0.3.0-beta01 */
public class ImageGenerationParams internal constructor(
    /** @since 0.3.0-beta01 */
    public val prompt: String,
    /** @since 0.3.0-beta01 */
    public val n: Int = 1,
    /** @since 0.3.0-beta01 */
    public val size: String? = null,
    /** @since 0.3.0-beta01 */
    public val aspectRatio: String? = null,
    /** @since 0.3.0-beta01 */
    public val seed: Int? = null,
    /** @since 0.3.0-beta01 */
    public val providerOptions: ProviderOptions = ProviderOptions.None,
    /** @since 0.3.0-beta01 */
    public val headers: Map<String, String> = emptyMap(),
    /** @since 0.3.0-beta01 */
    public val abortSignal: AbortSignal = AbortSignalNever,
    /** @since 0.3.0-beta01 */
    public val files: List<ImageGenerationFile> = emptyList(),
    /** @since 0.3.0-beta01 */
    public val mask: ImageGenerationFile? = null,
)

/** @since 0.3.0-beta01 */
public class ImageGenerationParamsBuilder {
    private var prompt: String? = null
    private var n: Int = 1
    private var size: String? = null
    private var aspectRatio: String? = null
    private var seed: Int? = null
    private var providerOptions: ProviderOptions = ProviderOptions.None
    private var headers: Map<String, String> = emptyMap()
    private var abortSignal: AbortSignal = AbortSignalNever
    private var files: List<ImageGenerationFile> = emptyList()
    private var mask: ImageGenerationFile? = null

    /** @since 0.3.0-beta01 */
    public fun prompt(value: String): ImageGenerationParamsBuilder {
        prompt = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun n(value: Int): ImageGenerationParamsBuilder {
        n = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun size(value: String?): ImageGenerationParamsBuilder {
        size = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun aspectRatio(value: String?): ImageGenerationParamsBuilder {
        aspectRatio = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun seed(value: Int?): ImageGenerationParamsBuilder {
        seed = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun providerOptions(value: ProviderOptions): ImageGenerationParamsBuilder {
        providerOptions = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun headers(value: Map<String, String>): ImageGenerationParamsBuilder {
        headers = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun abortSignal(value: AbortSignal): ImageGenerationParamsBuilder {
        abortSignal = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun files(value: List<ImageGenerationFile>): ImageGenerationParamsBuilder {
        files = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun mask(value: ImageGenerationFile?): ImageGenerationParamsBuilder {
        mask = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun build(): ImageGenerationParams =
        ImageGenerationParams(
            prompt = requireNotNull(prompt) { "ImageGenerationParams.prompt is required" },
            n = n,
            size = size,
            aspectRatio = aspectRatio,
            seed = seed,
            providerOptions = providerOptions,
            headers = headers,
            abortSignal = abortSignal,
            files = files,
            mask = mask,
        )
}

/** @since 0.3.0-beta01 */
public fun ImageGenerationParams(
    block: ImageGenerationParamsBuilder.() -> Unit = {},
): ImageGenerationParams =
    ImageGenerationParamsBuilder().apply(block).build()

@Poko
/** @since 0.3.0-beta01 */
public class ImageGenerationFile(
    /** @since 0.3.0-beta01 */
    public val mediaType: String? = null,
    /** @since 0.3.0-beta01 */
    public val base64: String? = null,
    /** @since 0.3.0-beta01 */
    public val url: String? = null,
    /** @since 0.3.0-beta01 */
    public val filename: String? = null,
)

/**
 * Token usage reported by an image model, when available. Mirrors v6's `ImageModelUsage`.
 * @since 0.3.0-beta01
 */
@Poko
public class ImageModelUsage(
    /** @since 0.3.0-beta01 */
    public val inputTokens: Int? = null,
    /** @since 0.3.0-beta01 */
    public val outputTokens: Int? = null,
    /** @since 0.3.0-beta01 */
    public val totalTokens: Int? = null,
) {
    public companion object {
        /** Sum image usage across n-batched calls; a field stays null only if every call left it null. */
        internal fun sum(usages: List<ImageModelUsage>): ImageModelUsage {
            fun sum(selector: (ImageModelUsage) -> Int?): Int? =
                usages.mapNotNull(selector).takeIf { it.isNotEmpty() }?.sum()
            return ImageModelUsage(sum { it.inputTokens }, sum { it.outputTokens }, sum { it.totalTokens })
        }

        /** Image usage from an OpenAI-compatible image response `usage` object. */
        internal fun fromOpenAI(value: JsonElement?): ImageModelUsage {
            val obj = value as? JsonObject ?: return ImageModelUsage()
            return ImageModelUsage(
                inputTokens = (obj["input_tokens"] as? JsonPrimitive)?.intOrNull,
                outputTokens = (obj["output_tokens"] as? JsonPrimitive)?.intOrNull,
                totalTokens = (obj["total_tokens"] as? JsonPrimitive)?.intOrNull,
            )
        }
    }
}

@Poko
/** @since 0.3.0-beta01 */
public class ImageModelResult(
    /** @since 0.3.0-beta01 */
    public val images: List<GeneratedFile>,
    /** @since 0.3.0-beta01 */
    public val warnings: List<CallWarning> = emptyList(),
    /** @since 0.3.0-beta01 */
    public val response: LanguageModelResponseMetadata = LanguageModelResponseMetadata(),
    /** @since 0.3.0-beta01 */
    public val providerMetadata: ProviderMetadata = ProviderMetadata.None,
    /** @since 0.3.0-beta01 */
    public val usage: ImageModelUsage = ImageModelUsage(),
)

@Poko
/** @since 0.3.0-beta01 */
public class GenerateImageResult(
    /** @since 0.3.0-beta01 */
    public val images: List<GeneratedFile>,
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
    /** @since 0.3.0-beta01 */
    public val usage: ImageModelUsage = ImageModelUsage(),
) {
    /** @since 0.3.0-beta01 */
    public val image: GeneratedFile get() = images.firstOrNull() ?: throw NoImageGeneratedError()
}

/** @since 0.3.0-beta01 */
public object ImageGeneration {
    @Suppress("LongParameterList")
    public suspend fun generateImage(
        model: ImageModel,
        prompt: String,
        n: Int = 1,
        size: String? = null,
        aspectRatio: String? = null,
        seed: Int? = null,
        providerOptions: ProviderOptions = ProviderOptions.None,
        headers: Map<String, String> = emptyMap(),
        abortSignal: AbortSignal = AbortSignalNever,
        files: List<ImageGenerationFile> = emptyList(),
        mask: ImageGenerationFile? = null,
        logger: Logger = NoopLogger,
        maxParallelCalls: Int = DEFAULT_MAX_PARALLEL_CALLS,
    ): GenerateImageResult {
        require(prompt.isNotBlank()) { "generateImage: prompt must not be blank" }
        require(n > 0) { "generateImage: n must be > 0" }
        // Split into ceil(n / maxImagesPerCall) calls when the model is limited, so a
        // request for n images from a model capped at < n returns all n (was returning
        // only one call's worth). Calls run concurrently up to maxParallelCalls.
        val paramsFor: (Int) -> ImageGenerationParams = { count ->
            ImageGenerationParams {
                prompt(prompt)
                n(count)
                size(size)
                aspectRatio(aspectRatio)
                seed(seed)
                providerOptions(providerOptions)
                headers(headers)
                abortSignal(abortSignal)
                files(files)
                mask(mask)
            }
        }
        val maxPerCall = model.maxImagesPerCall?.coerceAtLeast(1) ?: n
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
        val images = results.flatMap { it.images }
        if (images.isEmpty()) throw NoImageGeneratedError(responses = results.map { it.response })
        results.flatMap { it.warnings }.forEach { logger.warn(it.format()) }
        return GenerateImageResult(
            images = images,
            warnings = results.flatMap { it.warnings },
            response = results.first().response,
            providerMetadata = results.firstNotNullOfOrNull {
                (it.providerMetadata as? ProviderMetadata.Raw)
            } ?: ProviderMetadata.None,
            responses = results.map { it.response },
            usage = ImageModelUsage.sum(results.map { it.usage }),
        )
    }

    @ExperimentalAiSdkApi
    @Suppress("FunctionNaming", "LongParameterList")
    public suspend fun experimental_generateImage(
        model: ImageModel,
        prompt: String,
        n: Int = 1,
        size: String? = null,
        aspectRatio: String? = null,
        seed: Int? = null,
        providerOptions: ProviderOptions = ProviderOptions.None,
        headers: Map<String, String> = emptyMap(),
        abortSignal: AbortSignal = AbortSignalNever,
        files: List<ImageGenerationFile> = emptyList(),
        mask: ImageGenerationFile? = null,
        maxParallelCalls: Int = DEFAULT_MAX_PARALLEL_CALLS,
    ): GenerateImageResult = generateImage(
        model = model,
        prompt = prompt,
        n = n,
        size = size,
        aspectRatio = aspectRatio,
        seed = seed,
        providerOptions = providerOptions,
        headers = headers,
        abortSignal = abortSignal,
        files = files,
        mask = mask,
        maxParallelCalls = maxParallelCalls,
    )
}

/** @since 0.3.0-beta01 */
public interface ImageModelMiddleware {
    public suspend fun wrapGenerate(context: ImageMiddlewareCallContext): ImageModelResult =
        context.doGenerate(context.params)
}

@Poko
/** @since 0.3.0-beta01 */
public class ImageMiddlewareCallContext(
    /** @since 0.3.0-beta01 */
    public val params: ImageGenerationParams,
    /** @since 0.3.0-beta01 */
    public val model: ImageModel,
    /** @since 0.3.0-beta01 */
    public val doGenerate: suspend (ImageGenerationParams) -> ImageModelResult,
)

/** @since 0.3.0-beta01 */
public fun WrapImageModel(
    model: ImageModel,
    middlewares: List<ImageModelMiddleware>,
): ImageModel {
    if (middlewares.isEmpty()) return model
    return WrappedImageModel(model, middlewares)
}

private class WrappedImageModel(
    private val inner: ImageModel,
    middlewares: List<ImageModelMiddleware>,
) : ImageModel {
    override val modelId: String = inner.modelId
    override val provider: String = inner.provider

    // Forward the inner model's per-call image cap; without this the wrapper inherits the
    // interface default (null = unlimited) and defeats n-batching for capped inner models.
    override val maxImagesPerCall: Int? = inner.maxImagesPerCall
    private val chainGenerate: suspend (ImageGenerationParams) -> ImageModelResult

    init {
        chainGenerate = buildGenerateChain(middlewares)
    }

    private fun buildGenerateChain(
        middlewares: List<ImageModelMiddleware>,
    ): suspend (ImageGenerationParams) -> ImageModelResult {
        var doGenerate: suspend (ImageGenerationParams) -> ImageModelResult = inner::generate
        for (middleware in middlewares.asReversed()) {
            val downstream = doGenerate
            doGenerate = { params ->
                middleware.wrapGenerate(
                    ImageMiddlewareCallContext(
                        params = params,
                        model = this,
                        doGenerate = downstream,
                    ),
                )
            }
        }
        return doGenerate
    }

    override suspend fun generate(params: ImageGenerationParams): ImageModelResult =
        chainGenerate(params)
}

/** @since 0.3.0-beta01 */
