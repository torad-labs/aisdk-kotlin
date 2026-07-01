package ai.torad.aisdk

import dev.drewhamilton.poko.Poko
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlin.jvm.JvmOverloads

@Poko
/** @since 0.3.0-beta01 */
public class GeneratedFile(
    /** @since 0.3.0-beta01 */
    public val mediaType: String,
    /** @since 0.3.0-beta01 */
    public val base64: String,
    /** @since 0.3.0-beta01 */
    public val filename: String? = null,
    /** @since 0.3.0-beta01 */
    public val providerMetadata: ProviderMetadata = ProviderMetadata.None,
    /** @since 0.3.0-beta01 */
    public val url: String? = null,
)

/** @since 0.3.0-beta01 */
public sealed class FileData {
    /** @since 0.3.0-beta01 */
    public abstract val mediaType: String?
    /** @since 0.3.0-beta01 */
    public abstract val filename: String?

    @Poko
    /** @since 0.3.0-beta01 */
    public class Base64(
        /** @since 0.3.0-beta01 */
        public val value: String,
        override val mediaType: String? = null,
        override val filename: String? = null,
    ) : FileData()

    /** @since 0.3.0-beta01 */
    public class Bytes(
        bytes: ByteArray,
        override val mediaType: String? = null,
        override val filename: String? = null,
    ) : FileData() {
        private val bytesData: ByteArray = bytes.copyOf()
        /** @since 0.3.0-beta01 */
        public fun toByteArray(): ByteArray = bytesData.copyOf()

        override fun equals(other: Any?): Boolean =
            other is Bytes &&
                bytesData.contentEquals(other.bytesData) &&
                mediaType == other.mediaType &&
                filename == other.filename

        override fun hashCode(): Int {
            var result = bytesData.contentHashCode()
            result = 31 * result + mediaType.hashCode()
            result = 31 * result + filename.hashCode()
            return result
        }

        override fun toString(): String =
            "Bytes(size=${bytesData.size}, mediaType=$mediaType, filename=$filename)"
    }

    @Poko
    /** @since 0.3.0-beta01 */
    public class Url(
        /** @since 0.3.0-beta01 */
        public val value: String,
        override val mediaType: String? = null,
        override val filename: String? = null,
    ) : FileData()
}

@JvmOverloads
/** @since 0.3.0-beta01 */
public fun GeneratedFile(
    data: FileData,
    mediaType: String = data.mediaType ?: "application/octet-stream",
    filename: String? = data.filename,
    providerMetadata: ProviderMetadata = ProviderMetadata.None,
): GeneratedFile = when (data) {
    is FileData.Base64 -> GeneratedFile(
        mediaType = mediaType,
        base64 = data.value,
        filename = filename,
        providerMetadata = providerMetadata,
    )
    is FileData.Bytes -> GeneratedFile(
        mediaType = mediaType,
        base64 = Base64Codec.encode(data.toByteArray()),
        filename = filename,
        providerMetadata = providerMetadata,
    )
    is FileData.Url -> GeneratedFile(
        mediaType = mediaType,
        base64 = "",
        filename = filename,
        providerMetadata = providerMetadata,
        url = data.value,
    )
}

/** @since 0.3.0-beta01 */
public fun ImageGenerationFile(data: FileData): ImageGenerationFile = when (data) {
    is FileData.Base64 -> ImageGenerationFile(
        mediaType = data.mediaType,
        base64 = data.value,
        filename = data.filename,
    )
    is FileData.Bytes -> ImageGenerationFile(
        mediaType = data.mediaType,
        base64 = Base64Codec.encode(data.toByteArray()),
        filename = data.filename,
    )
    is FileData.Url -> ImageGenerationFile(
        mediaType = data.mediaType,
        url = data.value,
        filename = data.filename,
    )
}

/**
 * GeneratedFile read accessors as member-extensions. Use via member-import
 * (`import ai.torad.aisdk.GeneratedFiles.bytes`) or `with(GeneratedFiles) { ... }`.
  * @since 0.3.0-beta01
 */
public object GeneratedFiles {
    /** @since 0.3.0-beta01 */
    public fun GeneratedFile.fileData(): FileData =
        url?.let { FileData.Url(it, mediaType = mediaType, filename = filename) }
            ?: FileData.Base64(base64, mediaType = mediaType, filename = filename)

    /**
     * Decode the inline base64 payload to bytes.
     *
     * @throws IllegalStateException when this file is URL-backed (no inline
     * bytes) — fetch [GeneratedFile.url] to obtain the data. Without this guard a
     * URL-backed file (whose `base64` is `""`) silently decoded to an empty
     * `ByteArray`, a wrong answer indistinguishable from a genuinely empty file.
      * @since 0.3.0-beta01
     */
    public fun GeneratedFile.bytes(): ByteArray {
        if (base64.isEmpty()) {
            check(url == null) {
                "GeneratedFile is URL-backed (mediaType=$mediaType); it has no inline bytes. " +
                    "Fetch `url` to obtain the data, or use bytesOrNull()."
            }
            return ByteArray(0)
        }
        return Base64Codec.decode(base64)
    }

    /**
     * Like [bytes] but returns null for a URL-backed file instead of throwing.
     * @since 0.3.0-beta01
     */
    public fun GeneratedFile.bytesOrNull(): ByteArray? =
        if (base64.isEmpty() && url != null) null else bytes()
}

public typealias GeneratedAudioFile = GeneratedFile

@ExperimentalAiSdkApi
public typealias Experimental_GeneratedImage = GeneratedFile

@ExperimentalAiSdkApi
public typealias Experimental_GenerateImageResult = GenerateImageResult

@ExperimentalAiSdkApi
public typealias Experimental_SpeechResult = GenerateSpeechResult

@ExperimentalAiSdkApi
public typealias Experimental_TranscriptionResult = TranscribeResult

/** @since 0.3.0-beta01 */
public class DefaultGeneratedFile private constructor(
    private var base64Data: String?,
    private var byteArrayData: ByteArray?,
    /** @since 0.3.0-beta01 */
    public val mediaType: String,
) {
    public companion object {
        /** @since 0.3.0-beta01 */
        public fun fromBase64(data: String, mediaType: String): DefaultGeneratedFile =
            DefaultGeneratedFile(base64Data = data, byteArrayData = null, mediaType = mediaType)

        /** @since 0.3.0-beta01 */
        public fun fromBytes(data: ByteArray, mediaType: String): DefaultGeneratedFile =
            DefaultGeneratedFile(base64Data = null, byteArrayData = data.copyOf(), mediaType = mediaType)
    }

    /** @since 0.3.0-beta01 */
    public val base64: String
        get() {
            if (base64Data == null) {
                base64Data = Base64Codec.encode(byteArrayData ?: ByteArray(0))
            }
            return base64Data.orEmpty()
        }

    /** @since 0.3.0-beta01 */
    public val byteArray: ByteArray
        get() {
            if (byteArrayData == null) {
                byteArrayData = Base64Codec.decode(base64Data.orEmpty())
            }
            return byteArrayData?.copyOf() ?: ByteArray(0)
        }

    /** @since 0.3.0-beta01 */
    public fun toGeneratedFile(filename: String? = null, providerMetadata: ProviderMetadata = ProviderMetadata.None): GeneratedFile =
        GeneratedFile(mediaType = mediaType, base64 = base64, filename = filename, providerMetadata = providerMetadata)
}

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
            providerMetadata = results.firstNotNullOfOrNull { (it.providerMetadata as? ProviderMetadata.Raw) } ?: ProviderMetadata.None,
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
