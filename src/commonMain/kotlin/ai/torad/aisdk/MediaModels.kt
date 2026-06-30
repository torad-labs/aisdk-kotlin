package ai.torad.aisdk

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

public data class GeneratedFile(
    val mediaType: String,
    val base64: String,
    val filename: String? = null,
    val providerMetadata: ProviderMetadata = ProviderMetadata.None,
    val url: String? = null,
)

public sealed class FileData {
    public abstract val mediaType: String?
    public abstract val filename: String?

    public data class Base64(
        val value: String,
        override val mediaType: String? = null,
        override val filename: String? = null,
    ) : FileData()

    public class Bytes(
        bytes: ByteArray,
        override val mediaType: String? = null,
        override val filename: String? = null,
    ) : FileData() {
        private val bytesData: ByteArray = bytes.copyOf()
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

    public data class Url(
        val value: String,
        override val mediaType: String? = null,
        override val filename: String? = null,
    ) : FileData()
}

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
 */
public object GeneratedFiles {
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

    /** Like [bytes] but returns null for a URL-backed file instead of throwing. */
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

public class DefaultGeneratedFile private constructor(
    private var base64Data: String?,
    private var byteArrayData: ByteArray?,
    public val mediaType: String,
) {
    public companion object {
        public fun fromBase64(data: String, mediaType: String): DefaultGeneratedFile =
            DefaultGeneratedFile(base64Data = data, byteArrayData = null, mediaType = mediaType)

        public fun fromBytes(data: ByteArray, mediaType: String): DefaultGeneratedFile =
            DefaultGeneratedFile(base64Data = null, byteArrayData = data.copyOf(), mediaType = mediaType)
    }

    public val base64: String
        get() {
            if (base64Data == null) {
                base64Data = Base64Codec.encode(byteArrayData ?: ByteArray(0))
            }
            return base64Data.orEmpty()
        }

    public val byteArray: ByteArray
        get() {
            if (byteArrayData == null) {
                byteArrayData = Base64Codec.decode(base64Data.orEmpty())
            }
            return byteArrayData?.copyOf() ?: ByteArray(0)
        }

    public fun toGeneratedFile(filename: String? = null, providerMetadata: ProviderMetadata = ProviderMetadata.None): GeneratedFile =
        GeneratedFile(mediaType = mediaType, base64 = base64, filename = filename, providerMetadata = providerMetadata)
}

public interface ImageModel {
    public val modelId: String
    public val provider: String
        get() = "unknown"

    /**
     * How many images this model produces per call, if limited. `generateImage`
     * splits a request for more than this into ceil(n / limit) concurrent calls
     * (was: passing n straight through, so a model capped at 1 returned 1 of n).
     * Null = no limit.
     */
    public val maxImagesPerCall: Int?
        get() = null

    public suspend fun generate(params: ImageGenerationParams): ImageModelResult
}

public data class ImageGenerationParams(
    val prompt: String,
    val n: Int = 1,
    val size: String? = null,
    val aspectRatio: String? = null,
    val seed: Int? = null,
    val providerOptions: ProviderOptions = ProviderOptions.None,
    val headers: Map<String, String> = emptyMap(),
    val abortSignal: AbortSignal = AbortSignalNever,
    val files: List<ImageGenerationFile> = emptyList(),
    val mask: ImageGenerationFile? = null,
)

public data class ImageGenerationFile(
    val mediaType: String? = null,
    val base64: String? = null,
    val url: String? = null,
    val filename: String? = null,
)

/** Token usage reported by an image model, when available. Mirrors v6's `ImageModelUsage`. */
public data class ImageModelUsage(
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val totalTokens: Int? = null,
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

public data class ImageModelResult(
    val images: List<GeneratedFile>,
    val warnings: List<CallWarning> = emptyList(),
    val response: LanguageModelResponseMetadata = LanguageModelResponseMetadata(),
    val providerMetadata: ProviderMetadata = ProviderMetadata.None,
    val usage: ImageModelUsage = ImageModelUsage(),
)

public data class GenerateImageResult(
    val images: List<GeneratedFile>,
    val warnings: List<CallWarning> = emptyList(),
    val response: LanguageModelResponseMetadata = LanguageModelResponseMetadata(),
    val providerMetadata: ProviderMetadata = ProviderMetadata.None,
    /** Per-call response metadata, one entry per underlying model call (n-batching). */
    val responses: List<LanguageModelResponseMetadata> = listOf(response),
    val usage: ImageModelUsage = ImageModelUsage(),
) {
    val image: GeneratedFile get() = images.firstOrNull() ?: throw NoImageGeneratedError()
}

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
        fun paramsFor(count: Int) = ImageGenerationParams(
            prompt, count, size, aspectRatio, seed, providerOptions, headers, abortSignal, files, mask,
        )
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

public interface ImageModelMiddleware {
    public suspend fun wrapGenerate(context: ImageMiddlewareCallContext): ImageModelResult =
        context.doGenerate(context.params)
}

public data class ImageMiddlewareCallContext(
    val params: ImageGenerationParams,
    val model: ImageModel,
    val doGenerate: suspend (ImageGenerationParams) -> ImageModelResult,
)

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

public interface SpeechModel {
    public val modelId: String
    public val provider: String
        get() = "unknown"

    public suspend fun generate(params: SpeechGenerationParams): SpeechModelResult
}

public data class SpeechGenerationParams(
    val text: String,
    val voice: String? = null,
    val instructions: String? = null,
    val speed: Float? = null,
    val responseFormat: String? = null,
    /** ISO 639-1 language code (e.g. "en") or "auto". Mirrors upstream's `language`. */
    val language: String? = null,
    val providerOptions: ProviderOptions = ProviderOptions.None,
    val headers: Map<String, String> = emptyMap(),
    val abortSignal: AbortSignal = AbortSignalNever,
)

public data class SpeechModelResult(
    val audio: GeneratedFile?,
    val warnings: List<CallWarning> = emptyList(),
    val response: LanguageModelResponseMetadata = LanguageModelResponseMetadata(),
    val providerMetadata: ProviderMetadata = ProviderMetadata.None,
)

public data class GenerateSpeechResult(
    val audio: GeneratedFile,
    val warnings: List<CallWarning> = emptyList(),
    val response: LanguageModelResponseMetadata = LanguageModelResponseMetadata(),
    val providerMetadata: ProviderMetadata = ProviderMetadata.None,
    val responses: List<LanguageModelResponseMetadata> = listOf(response),
)

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
            SpeechGenerationParams(
                text, voice, instructions, speed, responseFormat, language, providerOptions, headers, abortSignal,
            ),
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

public interface TranscriptionModel {
    public val modelId: String
    public val provider: String
        get() = "unknown"

    public suspend fun transcribe(params: TranscriptionParams): TranscriptionModelResult
}

public data class AudioSource(
    val mediaType: String,
    val base64: String,
    val filename: String? = null,
)

public data class TranscriptionParams(
    val audio: AudioSource,
    val language: String? = null,
    val prompt: String? = null,
    val providerOptions: ProviderOptions = ProviderOptions.None,
    val headers: Map<String, String> = emptyMap(),
    val abortSignal: AbortSignal = AbortSignalNever,
)

public data class TranscriptSegment(
    val text: String,
    val startSeconds: Float? = null,
    val endSeconds: Float? = null,
)

public data class TranscriptionModelResult(
    val text: String?,
    val segments: List<TranscriptSegment> = emptyList(),
    val warnings: List<CallWarning> = emptyList(),
    val response: LanguageModelResponseMetadata = LanguageModelResponseMetadata(),
    val providerMetadata: ProviderMetadata = ProviderMetadata.None,
    /** Detected language as an ISO-639-1 code, or null if undetermined. */
    val language: String? = null,
    /** Total audio duration in seconds, or null if undetermined. */
    val durationInSeconds: Float? = null,
)

public data class TranscribeResult(
    val text: String,
    val segments: List<TranscriptSegment> = emptyList(),
    val warnings: List<CallWarning> = emptyList(),
    val response: LanguageModelResponseMetadata = LanguageModelResponseMetadata(),
    val providerMetadata: ProviderMetadata = ProviderMetadata.None,
    val responses: List<LanguageModelResponseMetadata> = listOf(response),
    /** Detected language as an ISO-639-1 code, or null if undetermined. */
    val language: String? = null,
    /** Total audio duration in seconds, or null if undetermined. */
    val durationInSeconds: Float? = null,
)

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
            TranscriptionParams(audio, language, prompt, providerOptions, headers, abortSignal),
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

public interface VideoModel {
    public val modelId: String
    public val provider: String
        get() = "unknown"

    /** How many videos this model produces per call, if limited (see [ImageModel.maxImagesPerCall]). */
    public val maxVideosPerCall: Int?
        get() = null

    public suspend fun generate(params: VideoGenerationParams): VideoModelResult
}

public data class VideoGenerationParams(
    val prompt: String,
    val n: Int = 1,
    val image: GeneratedFile? = null,
    val durationSeconds: Float? = null,
    val size: String? = null,
    val aspectRatio: String? = null,
    val providerOptions: ProviderOptions = ProviderOptions.None,
    val headers: Map<String, String> = emptyMap(),
    val abortSignal: AbortSignal = AbortSignalNever,
    val seed: Int? = null,
    val fps: Int? = null,
    val resolution: String? = null,
)

public data class VideoModelResult(
    val videos: List<GeneratedFile>,
    val warnings: List<CallWarning> = emptyList(),
    val response: LanguageModelResponseMetadata = LanguageModelResponseMetadata(),
    val providerMetadata: ProviderMetadata = ProviderMetadata.None,
)

public data class GenerateVideoResult(
    val videos: List<GeneratedFile>,
    val warnings: List<CallWarning> = emptyList(),
    val response: LanguageModelResponseMetadata = LanguageModelResponseMetadata(),
    val providerMetadata: ProviderMetadata = ProviderMetadata.None,
    /** Per-call response metadata, one entry per underlying model call (n-batching). */
    val responses: List<LanguageModelResponseMetadata> = listOf(response),
) {
    val video: GeneratedFile get() = videos.firstOrNull() ?: throw NoVideoGeneratedError()
}

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
        fun paramsFor(count: Int) = VideoGenerationParams(
            prompt = prompt,
            n = count,
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
