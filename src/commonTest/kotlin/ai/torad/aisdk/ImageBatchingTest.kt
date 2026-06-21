package ai.torad.aisdk

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ImageBatchingTest {
    // A model capped at maxImagesPerCall=1 that returns `count` images per call and
    // records how many calls it received.
    private class CappedImageModel(override val maxImagesPerCall: Int? = 1) : ImageModel {
        override val modelId = "test/image"
        var calls = 0
        override suspend fun generate(params: ImageGenerationParams): ImageModelResult {
            calls++
            return ImageModelResult(
                images = List(params.n) { GeneratedFile("image/png", "img$it") },
            )
        }
    }

    @Test
    fun `generateImage splits n by maxImagesPerCall into multiple calls and returns all images`() = runTest {
        val model = CappedImageModel(maxImagesPerCall = 2)
        val result = ImageGeneration.generateImage(model, prompt = "cat", n = 5)
        // 5 images, cap 2 → 3 calls (2,2,1), all 5 images returned.
        assertEquals(3, model.calls, "ceil(5/2) = 3 calls")
        assertEquals(5, result.images.size, "all n images aggregated")
        assertEquals(3, result.responses.size, "one response per call")
    }

    @Test
    fun `generateImage makes a single call when the model has no per-call limit`() = runTest {
        val model = CappedImageModel(maxImagesPerCall = null)
        val result = ImageGeneration.generateImage(model, prompt = "cat", n = 4)
        assertEquals(1, model.calls, "no limit → one call")
        assertEquals(4, result.images.size)
    }

    @Test
    fun `splitCount chunks correctly`() {
        assertEquals(listOf(2, 2, 1), MediaSupport.splitCount(5, 2))
        assertEquals(listOf(3), MediaSupport.splitCount(3, 5))
        assertEquals(listOf(2, 2), MediaSupport.splitCount(4, 2))
    }
}
