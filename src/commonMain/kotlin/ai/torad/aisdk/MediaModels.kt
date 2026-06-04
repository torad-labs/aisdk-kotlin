package ai.torad.aisdk

import kotlinx.serialization.json.JsonElement

public data class GeneratedFile(
    val mediaType: String,
    val base64: String,
    val filename: String? = null,
    val providerMetadata: Map<String, JsonElement> = emptyMap(),
    val url: String? = null,
)

public sealed interface FileData {
    public val mediaType: String?
    public val filename: String?

    public data class Base64(
        val value: String,
        override val mediaType: String? = null,
        override val filename: String? = null,
    ) : FileData

    public class Bytes(
        bytes: ByteArray,
        override val mediaType: String? = null,
        override val filename: String? = null,
    ) : FileData {
        public val bytes: ByteArray = bytes.copyOf()

        override fun equals(other: Any?): Boolean =
            other is Bytes &&
                bytes.contentEquals(other.bytes) &&
                mediaType == other.mediaType &&
                filename == other.filename

        override fun hashCode(): Int {
            var result = bytes.contentHashCode()
            result = 31 * result + mediaType.hashCode()
            result = 31 * result + filename.hashCode()
            return result
        }

        override fun toString(): String =
            "Bytes(size=${bytes.size}, mediaType=$mediaType, filename=$filename)"
    }

    public data class Url(
        val value: String,
        override val mediaType: String? = null,
        override val filename: String? = null,
    ) : FileData
}

public fun generatedFile(
    data: FileData,
    mediaType: String = data.mediaType ?: "application/octet-stream",
    filename: String? = data.filename,
    providerMetadata: Map<String, JsonElement> = emptyMap(),
): GeneratedFile = when (data) {
    is FileData.Base64 -> GeneratedFile(
        mediaType = mediaType,
        base64 = data.value,
        filename = filename,
        providerMetadata = providerMetadata,
    )
    is FileData.Bytes -> GeneratedFile(
        mediaType = mediaType,
        base64 = convertByteArrayToBase64(data.bytes),
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

public fun imageGenerationFile(data: FileData): ImageGenerationFile = when (data) {
    is FileData.Base64 -> ImageGenerationFile(
        mediaType = data.mediaType,
        base64 = data.value,
        filename = data.filename,
    )
    is FileData.Bytes -> ImageGenerationFile(
        mediaType = data.mediaType,
        base64 = convertByteArrayToBase64(data.bytes),
        filename = data.filename,
    )
    is FileData.Url -> ImageGenerationFile(
        mediaType = data.mediaType,
        url = data.value,
        filename = data.filename,
    )
}

public fun GeneratedFile.fileData(): FileData =
    url?.let { FileData.Url(it, mediaType = mediaType, filename = filename) }
        ?: FileData.Base64(base64, mediaType = mediaType, filename = filename)

/**
 * Decode the inline base64 payload to bytes.
 *
 * @throws IllegalStateException when this file is URL-backed (no inline
 * bytes) — fetch [GeneratedFile.url] to obtain the data. Without this guard a
 * URL-backed file (whose [base64] is `""`) silently decoded to an empty
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
    return convertBase64ToByteArray(base64)
}

/** Like [bytes] but returns null for a URL-backed file instead of throwing. */
public fun GeneratedFile.bytesOrNull(): ByteArray? =
    if (base64.isEmpty() && url != null) null else bytes()

public typealias GeneratedAudioFile = GeneratedFile
public typealias Experimental_GeneratedImage = GeneratedFile
public typealias Experimental_GenerateImageResult = GenerateImageResult
public typealias Experimental_SpeechResult = GenerateSpeechResult
public typealias Experimental_TranscriptionResult = TranscribeResult

public class DefaultGeneratedFile {
    private var base64Data: String? = null
    private var byteArrayData: ByteArray? = null

    public val mediaType: String

    public constructor(data: String, mediaType: String) {
        this.base64Data = data
        this.mediaType = mediaType
    }

    public constructor(data: ByteArray, mediaType: String) {
        this.byteArrayData = data
        this.mediaType = mediaType
    }

    public val base64: String
        get() {
            if (base64Data == null) {
                base64Data = convertByteArrayToBase64(byteArrayData ?: ByteArray(0))
            }
            return base64Data.orEmpty()
        }

    public val byteArray: ByteArray
        get() {
            if (byteArrayData == null) {
                byteArrayData = convertBase64ToByteArray(base64Data.orEmpty())
            }
            return byteArrayData ?: ByteArray(0)
        }

    public fun toGeneratedFile(filename: String? = null, providerMetadata: Map<String, JsonElement> = emptyMap()): GeneratedFile =
        GeneratedFile(mediaType = mediaType, base64 = base64, filename = filename, providerMetadata = providerMetadata)
}

public interface ImageModel {
    public val modelId: String
    public val provider: String
        get() = "unknown"

    public suspend fun generate(params: ImageGenerationParams): ImageModelResult
}

public data class ImageGenerationParams(
    val prompt: String,
    val n: Int = 1,
    val size: String? = null,
    val aspectRatio: String? = null,
    val seed: Int? = null,
    val providerOptions: Map<String, JsonElement> = emptyMap(),
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

public data class ImageModelResult(
    val images: List<GeneratedFile>,
    val warnings: List<CallWarning> = emptyList(),
    val response: LanguageModelResponseMetadata = LanguageModelResponseMetadata(),
    val providerMetadata: Map<String, JsonElement> = emptyMap(),
)

public data class GenerateImageResult(
    val images: List<GeneratedFile>,
    val warnings: List<CallWarning> = emptyList(),
    val response: LanguageModelResponseMetadata = LanguageModelResponseMetadata(),
    val providerMetadata: Map<String, JsonElement> = emptyMap(),
) {
    val image: GeneratedFile get() = images.firstOrNull() ?: throw NoImageGeneratedError()
}

public suspend fun generateImage(
    model: ImageModel,
    prompt: String,
    n: Int = 1,
    size: String? = null,
    aspectRatio: String? = null,
    seed: Int? = null,
    providerOptions: Map<String, JsonElement> = emptyMap(),
    headers: Map<String, String> = emptyMap(),
    abortSignal: AbortSignal = AbortSignalNever,
    files: List<ImageGenerationFile> = emptyList(),
    mask: ImageGenerationFile? = null,
): GenerateImageResult {
    require(prompt.isNotBlank()) { "generateImage: prompt must not be blank" }
    require(n > 0) { "generateImage: n must be > 0" }
    val result = model.generate(
        ImageGenerationParams(prompt, n, size, aspectRatio, seed, providerOptions, headers, abortSignal, files, mask),
    )
    if (result.images.isEmpty()) throw NoImageGeneratedError()
    return GenerateImageResult(result.images, result.warnings, result.response, result.providerMetadata)
}

public suspend fun experimental_generateImage(
    model: ImageModel,
    prompt: String,
    n: Int = 1,
    size: String? = null,
    aspectRatio: String? = null,
    seed: Int? = null,
    providerOptions: Map<String, JsonElement> = emptyMap(),
    headers: Map<String, String> = emptyMap(),
    abortSignal: AbortSignal = AbortSignalNever,
    files: List<ImageGenerationFile> = emptyList(),
    mask: ImageGenerationFile? = null,
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
)

public interface ImageModelMiddleware {
    public suspend fun wrapGenerate(context: ImageMiddlewareCallContext): ImageModelResult =
        context.doGenerate(context.params)
}

public data class ImageMiddlewareCallContext(
    val params: ImageGenerationParams,
    val model: ImageModel,
    val doGenerate: suspend (ImageGenerationParams) -> ImageModelResult,
)

public fun wrapImageModel(
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
    private val chainGenerate: suspend (ImageGenerationParams) -> ImageModelResult

    init {
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
        chainGenerate = doGenerate
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
    val providerOptions: Map<String, JsonElement> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
    val abortSignal: AbortSignal = AbortSignalNever,
)

public data class SpeechModelResult(
    val audio: GeneratedFile?,
    val warnings: List<CallWarning> = emptyList(),
    val response: LanguageModelResponseMetadata = LanguageModelResponseMetadata(),
    val providerMetadata: Map<String, JsonElement> = emptyMap(),
)

public data class GenerateSpeechResult(
    val audio: GeneratedFile,
    val warnings: List<CallWarning> = emptyList(),
    val response: LanguageModelResponseMetadata = LanguageModelResponseMetadata(),
    val providerMetadata: Map<String, JsonElement> = emptyMap(),
)

public suspend fun generateSpeech(
    model: SpeechModel,
    text: String,
    voice: String? = null,
    instructions: String? = null,
    speed: Float? = null,
    responseFormat: String? = null,
    providerOptions: Map<String, JsonElement> = emptyMap(),
    headers: Map<String, String> = emptyMap(),
    abortSignal: AbortSignal = AbortSignalNever,
): GenerateSpeechResult {
    require(text.isNotBlank()) { "generateSpeech: text must not be blank" }
    val result = model.generate(
        SpeechGenerationParams(text, voice, instructions, speed, responseFormat, providerOptions, headers, abortSignal),
    )
    return GenerateSpeechResult(
        audio = result.audio ?: throw NoSpeechGeneratedError(),
        warnings = result.warnings,
        response = result.response,
        providerMetadata = result.providerMetadata,
    )
}

public suspend fun experimental_generateSpeech(
    model: SpeechModel,
    text: String,
    voice: String? = null,
    instructions: String? = null,
    speed: Float? = null,
    responseFormat: String? = null,
    providerOptions: Map<String, JsonElement> = emptyMap(),
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
    val providerOptions: Map<String, JsonElement> = emptyMap(),
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
    val providerMetadata: Map<String, JsonElement> = emptyMap(),
)

public data class TranscribeResult(
    val text: String,
    val segments: List<TranscriptSegment> = emptyList(),
    val warnings: List<CallWarning> = emptyList(),
    val response: LanguageModelResponseMetadata = LanguageModelResponseMetadata(),
    val providerMetadata: Map<String, JsonElement> = emptyMap(),
)

public suspend fun transcribe(
    model: TranscriptionModel,
    audio: AudioSource,
    language: String? = null,
    prompt: String? = null,
    providerOptions: Map<String, JsonElement> = emptyMap(),
    headers: Map<String, String> = emptyMap(),
    abortSignal: AbortSignal = AbortSignalNever,
): TranscribeResult {
    require(audio.base64.isNotBlank()) { "transcribe: audio base64 must not be blank" }
    val result = model.transcribe(
        TranscriptionParams(audio, language, prompt, providerOptions, headers, abortSignal),
    )
    return TranscribeResult(
        text = result.text ?: throw NoTranscriptGeneratedError(),
        segments = result.segments,
        warnings = result.warnings,
        response = result.response,
        providerMetadata = result.providerMetadata,
    )
}

public suspend fun experimental_transcribe(
    model: TranscriptionModel,
    audio: AudioSource,
    language: String? = null,
    prompt: String? = null,
    providerOptions: Map<String, JsonElement> = emptyMap(),
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

public interface VideoModel {
    public val modelId: String
    public val provider: String
        get() = "unknown"

    public suspend fun generate(params: VideoGenerationParams): VideoModelResult
}

public data class VideoGenerationParams(
    val prompt: String,
    val n: Int = 1,
    val image: GeneratedFile? = null,
    val durationSeconds: Float? = null,
    val size: String? = null,
    val aspectRatio: String? = null,
    val providerOptions: Map<String, JsonElement> = emptyMap(),
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
    val providerMetadata: Map<String, JsonElement> = emptyMap(),
)

public data class GenerateVideoResult(
    val videos: List<GeneratedFile>,
    val warnings: List<CallWarning> = emptyList(),
    val response: LanguageModelResponseMetadata = LanguageModelResponseMetadata(),
    val providerMetadata: Map<String, JsonElement> = emptyMap(),
) {
    val video: GeneratedFile get() = videos.firstOrNull() ?: throw NoVideoGeneratedError()
}

public suspend fun generateVideo(
    model: VideoModel,
    prompt: String,
    n: Int = 1,
    image: GeneratedFile? = null,
    durationSeconds: Float? = null,
    size: String? = null,
    aspectRatio: String? = null,
    providerOptions: Map<String, JsonElement> = emptyMap(),
    headers: Map<String, String> = emptyMap(),
    abortSignal: AbortSignal = AbortSignalNever,
    seed: Int? = null,
    fps: Int? = null,
    resolution: String? = null,
): GenerateVideoResult {
    require(prompt.isNotBlank()) { "generateVideo: prompt must not be blank" }
    require(n > 0) { "generateVideo: n must be > 0" }
    val result = model.generate(
        VideoGenerationParams(
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
        ),
    )
    if (result.videos.isEmpty()) throw NoVideoGeneratedError()
    return GenerateVideoResult(result.videos, result.warnings, result.response, result.providerMetadata)
}

public suspend fun experimental_generateVideo(
    model: VideoModel,
    prompt: String,
    n: Int = 1,
    image: GeneratedFile? = null,
    durationSeconds: Float? = null,
    size: String? = null,
    aspectRatio: String? = null,
    providerOptions: Map<String, JsonElement> = emptyMap(),
    headers: Map<String, String> = emptyMap(),
    abortSignal: AbortSignal = AbortSignalNever,
    seed: Int? = null,
    fps: Int? = null,
    resolution: String? = null,
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
)
