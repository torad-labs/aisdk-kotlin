package ai.torad.aisdk

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class MediaBatchingConcurrencyTest {
    private class ParallelImageModel(
        private val releaseAtStarted: Int,
    ) : ImageModel {
        override val modelId: String = "test/image"
        override val maxImagesPerCall: Int = 1
        private var currentInFlight: Int = 0
        private var observedMaxInFlight: Int = 0
        private var startedCount: Int = 0
        private val gate = CompletableDeferred<Unit>()

        val maxInFlight: Int get() = observedMaxInFlight

        override suspend fun generate(params: ImageGenerationParams): ImageModelResult {
            currentInFlight++
            observedMaxInFlight = maxOf(observedMaxInFlight, currentInFlight)
            startedCount++
            if (startedCount == releaseAtStarted) gate.complete(Unit)
            gate.await()
            currentInFlight--
            return ImageModelResult(
                images = List(params.n) { GeneratedFile(mediaType = "image/png", base64 = "image-$startedCount-$it") },
            )
        }
    }

    private class ParallelVideoModel(
        private val releaseAtStarted: Int,
    ) : VideoModel {
        override val modelId: String = "test/video"
        override val maxVideosPerCall: Int = 1
        private var currentInFlight: Int = 0
        private var observedMaxInFlight: Int = 0
        private var startedCount: Int = 0
        private val gate = CompletableDeferred<Unit>()

        val maxInFlight: Int get() = observedMaxInFlight

        override suspend fun generate(params: VideoGenerationParams): VideoModelResult {
            currentInFlight++
            observedMaxInFlight = maxOf(observedMaxInFlight, currentInFlight)
            startedCount++
            if (startedCount == releaseAtStarted) gate.complete(Unit)
            gate.await()
            currentInFlight--
            return VideoModelResult(
                videos = List(params.n) { GeneratedFile(mediaType = "video/mp4", base64 = "video-$startedCount-$it") },
            )
        }
    }

    @Test
    fun `generateImage bounds default n-batch fan-out`() = runTest {
        val model = ParallelImageModel(releaseAtStarted = DEFAULT_MAX_PARALLEL_CALLS)
        val result = ImageGeneration.generateImage(model, prompt = "logo", n = 12)

        assertEquals(DEFAULT_MAX_PARALLEL_CALLS, model.maxInFlight)
        assertEquals(12, result.images.size)
        assertEquals(12, result.responses.size)
    }

    @Test
    fun `generateVideo honors explicit maxParallelCalls`() = runTest {
        val model = ParallelVideoModel(releaseAtStarted = 3)
        val result = VideoGeneration.generateVideo(model, prompt = "clip", n = 7, maxParallelCalls = 3)

        assertEquals(3, model.maxInFlight)
        assertEquals(7, result.videos.size)
        assertEquals(7, result.responses.size)
    }
}
