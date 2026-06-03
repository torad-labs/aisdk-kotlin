package ai.torad.aisdk

import kotlinx.serialization.json.JsonElement

data class GeneratedFile(
    val mediaType: String,
    val base64: String,
    val filename: String? = null,
    val providerMetadata: Map<String, JsonElement> = emptyMap(),
)

interface ImageModel {
    val modelId: String
    val provider: String
        get() = "unknown"

    suspend fun generate(params: ImageGenerationParams): ImageModelResult
}

data class ImageGenerationParams(
    val prompt: String,
    val n: Int = 1,
    val size: String? = null,
    val aspectRatio: String? = null,
    val seed: Int? = null,
    val providerOptions: Map<String, JsonElement> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
    val abortSignal: AbortSignal = AbortSignalNever,
)

data class ImageModelResult(
    val images: List<GeneratedFile>,
    val warnings: List<CallWarning> = emptyList(),
    val response: LanguageModelResponseMetadata = LanguageModelResponseMetadata(),
    val providerMetadata: Map<String, JsonElement> = emptyMap(),
)

data class GenerateImageResult(
    val images: List<GeneratedFile>,
    val warnings: List<CallWarning> = emptyList(),
    val response: LanguageModelResponseMetadata = LanguageModelResponseMetadata(),
    val providerMetadata: Map<String, JsonElement> = emptyMap(),
) {
    val image: GeneratedFile get() = images.firstOrNull() ?: throw NoImageGeneratedError()
}

suspend fun generateImage(
    model: ImageModel,
    prompt: String,
    n: Int = 1,
    size: String? = null,
    aspectRatio: String? = null,
    seed: Int? = null,
    providerOptions: Map<String, JsonElement> = emptyMap(),
    headers: Map<String, String> = emptyMap(),
    abortSignal: AbortSignal = AbortSignalNever,
): GenerateImageResult {
    require(prompt.isNotBlank()) { "generateImage: prompt must not be blank" }
    require(n > 0) { "generateImage: n must be > 0" }
    val result = model.generate(
        ImageGenerationParams(prompt, n, size, aspectRatio, seed, providerOptions, headers, abortSignal),
    )
    if (result.images.isEmpty()) throw NoImageGeneratedError()
    return GenerateImageResult(result.images, result.warnings, result.response, result.providerMetadata)
}

interface ImageModelMiddleware {
    suspend fun wrapGenerate(context: ImageMiddlewareCallContext): ImageModelResult =
        context.doGenerate(context.params)
}

data class ImageMiddlewareCallContext(
    val params: ImageGenerationParams,
    val model: ImageModel,
    val doGenerate: suspend (ImageGenerationParams) -> ImageModelResult,
)

fun wrapImageModel(
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

interface SpeechModel {
    val modelId: String
    val provider: String
        get() = "unknown"

    suspend fun generate(params: SpeechGenerationParams): SpeechModelResult
}

data class SpeechGenerationParams(
    val text: String,
    val voice: String? = null,
    val instructions: String? = null,
    val speed: Float? = null,
    val responseFormat: String? = null,
    val providerOptions: Map<String, JsonElement> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
    val abortSignal: AbortSignal = AbortSignalNever,
)

data class SpeechModelResult(
    val audio: GeneratedFile?,
    val warnings: List<CallWarning> = emptyList(),
    val response: LanguageModelResponseMetadata = LanguageModelResponseMetadata(),
    val providerMetadata: Map<String, JsonElement> = emptyMap(),
)

data class GenerateSpeechResult(
    val audio: GeneratedFile,
    val warnings: List<CallWarning> = emptyList(),
    val response: LanguageModelResponseMetadata = LanguageModelResponseMetadata(),
    val providerMetadata: Map<String, JsonElement> = emptyMap(),
)

suspend fun generateSpeech(
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

interface TranscriptionModel {
    val modelId: String
    val provider: String
        get() = "unknown"

    suspend fun transcribe(params: TranscriptionParams): TranscriptionModelResult
}

data class AudioSource(
    val mediaType: String,
    val base64: String,
    val filename: String? = null,
)

data class TranscriptionParams(
    val audio: AudioSource,
    val language: String? = null,
    val prompt: String? = null,
    val providerOptions: Map<String, JsonElement> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
    val abortSignal: AbortSignal = AbortSignalNever,
)

data class TranscriptSegment(
    val text: String,
    val startSeconds: Float? = null,
    val endSeconds: Float? = null,
)

data class TranscriptionModelResult(
    val text: String?,
    val segments: List<TranscriptSegment> = emptyList(),
    val warnings: List<CallWarning> = emptyList(),
    val response: LanguageModelResponseMetadata = LanguageModelResponseMetadata(),
    val providerMetadata: Map<String, JsonElement> = emptyMap(),
)

data class TranscribeResult(
    val text: String,
    val segments: List<TranscriptSegment> = emptyList(),
    val warnings: List<CallWarning> = emptyList(),
    val response: LanguageModelResponseMetadata = LanguageModelResponseMetadata(),
    val providerMetadata: Map<String, JsonElement> = emptyMap(),
)

suspend fun transcribe(
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

interface VideoModel {
    val modelId: String
    val provider: String
        get() = "unknown"

    suspend fun generate(params: VideoGenerationParams): VideoModelResult
}

data class VideoGenerationParams(
    val prompt: String,
    val n: Int = 1,
    val image: GeneratedFile? = null,
    val durationSeconds: Float? = null,
    val size: String? = null,
    val aspectRatio: String? = null,
    val providerOptions: Map<String, JsonElement> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
    val abortSignal: AbortSignal = AbortSignalNever,
)

data class VideoModelResult(
    val videos: List<GeneratedFile>,
    val warnings: List<CallWarning> = emptyList(),
    val response: LanguageModelResponseMetadata = LanguageModelResponseMetadata(),
    val providerMetadata: Map<String, JsonElement> = emptyMap(),
)

data class GenerateVideoResult(
    val videos: List<GeneratedFile>,
    val warnings: List<CallWarning> = emptyList(),
    val response: LanguageModelResponseMetadata = LanguageModelResponseMetadata(),
    val providerMetadata: Map<String, JsonElement> = emptyMap(),
) {
    val video: GeneratedFile get() = videos.firstOrNull() ?: throw NoVideoGeneratedError()
}

suspend fun generateVideo(
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
): GenerateVideoResult {
    require(prompt.isNotBlank()) { "generateVideo: prompt must not be blank" }
    require(n > 0) { "generateVideo: n must be > 0" }
    val result = model.generate(
        VideoGenerationParams(prompt, n, image, durationSeconds, size, aspectRatio, providerOptions, headers, abortSignal),
    )
    if (result.videos.isEmpty()) throw NoVideoGeneratedError()
    return GenerateVideoResult(result.videos, result.warnings, result.response, result.providerMetadata)
}
