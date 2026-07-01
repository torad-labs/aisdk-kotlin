package ai.torad.aisdk.providers

import ai.torad.aisdk.AudioSource
import ai.torad.aisdk.EmbeddingModel
import ai.torad.aisdk.EmbeddingModelCallParams
import ai.torad.aisdk.EmbeddingModelResult
import ai.torad.aisdk.EmbeddingUsage
import ai.torad.aisdk.GeneratedFile
import ai.torad.aisdk.ImageGenerationParams
import ai.torad.aisdk.ImageModel
import ai.torad.aisdk.ImageModelResult
import ai.torad.aisdk.RerankedItem
import ai.torad.aisdk.RerankingModel
import ai.torad.aisdk.RerankingModelResult
import ai.torad.aisdk.RerankingParams
import ai.torad.aisdk.SpeechGenerationParams
import ai.torad.aisdk.SpeechModel
import ai.torad.aisdk.SpeechModelResult
import ai.torad.aisdk.TranscriptSegment
import ai.torad.aisdk.TranscriptionModel
import ai.torad.aisdk.TranscriptionModelResult
import ai.torad.aisdk.TranscriptionParams
import ai.torad.aisdk.Usage
import ai.torad.aisdk.VideoGenerationParams
import ai.torad.aisdk.VideoModel
import ai.torad.aisdk.VideoModelResult

/** @since 0.3.0-beta01 */
public class MockEmbeddingModel(
    override val modelId: String = "mock/embedding",
    override val provider: String = "mock",
    private val dimensions: Int = 3,
) : EmbeddingModel {
    private var _captured: EmbeddingModelCallParams? = null
    /** @since 0.3.0-beta01 */
    public val captured: EmbeddingModelCallParams? get() = _captured

    override suspend fun embed(params: EmbeddingModelCallParams): EmbeddingModelResult {
        _captured = params
        return EmbeddingModelResult(
            embeddings = params.values.map { value ->
                List(dimensions) { index -> (value.length + index).toFloat() }
            },
            usage = EmbeddingUsage(tokens = params.values.sumOf { it.length }),
        )
    }
}

/** @since 0.3.0-beta01 */
public class MockImageModel(
    override val modelId: String = "mock/image",
    override val provider: String = "mock",
    private val image: GeneratedFile = GeneratedFile("image/png", "iVBORw0KGgo=", "image.png"),
) : ImageModel {
    private var _captured: ImageGenerationParams? = null
    /** @since 0.3.0-beta01 */
    public val captured: ImageGenerationParams? get() = _captured

    override suspend fun generate(params: ImageGenerationParams): ImageModelResult {
        _captured = params
        return ImageModelResult(images = List(params.n) { image })
    }
}

/** @since 0.3.0-beta01 */
public class MockSpeechModel(
    override val modelId: String = "mock/speech",
    override val provider: String = "mock",
    private val audio: GeneratedFile = GeneratedFile("audio/mpeg", "SUQz", "speech.mp3"),
) : SpeechModel {
    private var _captured: SpeechGenerationParams? = null
    /** @since 0.3.0-beta01 */
    public val captured: SpeechGenerationParams? get() = _captured

    override suspend fun generate(params: SpeechGenerationParams): SpeechModelResult {
        _captured = params
        return SpeechModelResult(audio = audio)
    }
}

/** @since 0.3.0-beta01 */
public class MockTranscriptionModel(
    override val modelId: String = "mock/transcription",
    override val provider: String = "mock",
    private val transcript: String = "hello world",
) : TranscriptionModel {
    private var _captured: TranscriptionParams? = null
    /** @since 0.3.0-beta01 */
    public val captured: TranscriptionParams? get() = _captured

    override suspend fun transcribe(params: TranscriptionParams): TranscriptionModelResult {
        _captured = params
        return TranscriptionModelResult(
            text = transcript,
            segments = listOf(TranscriptSegment(transcript, startSeconds = 0f, endSeconds = 1f)),
        )
    }
}

/** @since 0.3.0-beta01 */
public class MockVideoModel(
    override val modelId: String = "mock/video",
    override val provider: String = "mock",
    private val video: GeneratedFile = GeneratedFile("video/mp4", "AAAA", "video.mp4"),
) : VideoModel {
    private var _captured: VideoGenerationParams? = null
    /** @since 0.3.0-beta01 */
    public val captured: VideoGenerationParams? get() = _captured

    override suspend fun generate(params: VideoGenerationParams): VideoModelResult {
        _captured = params
        return VideoModelResult(videos = List(params.n) { video })
    }
}

/** @since 0.3.0-beta01 */
public class MockRerankingModel(
    override val modelId: String = "mock/rerank",
    override val provider: String = "mock",
) : RerankingModel {
    private var _captured: RerankingParams? = null
    /** @since 0.3.0-beta01 */
    public val captured: RerankingParams? get() = _captured

    override suspend fun rerank(params: RerankingParams): RerankingModelResult {
        _captured = params
        val results = params.documents.mapIndexed { index, document ->
            val score = if (document.contains(params.query, ignoreCase = true)) 1f else 0.1f / (index + 1)
            RerankedItem(document, score, index)
        }
        return RerankingModelResult(results = results, usage = Usage.of(promptTokens = params.query.length, completionTokens = 0))
    }
}

/** @since 0.3.0-beta01 */
public fun MockAudioSource(): AudioSource = AudioSource(mediaType = "audio/mpeg", base64 = "SUQz", filename = "audio.mp3")
