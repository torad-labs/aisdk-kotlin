package ai.torad.aisdk

import ai.torad.aisdk.providers.Fireworks
import ai.torad.aisdk.providers.FireworksProviderSettings
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class FireworksImageBatchingTest {
    // Regression: FireworksImageModel returns exactly one image per call, so it must
    // report maxImagesPerCall = 1. When it was omitted (null = no limit), generateImage
    // passed n straight through as a single call and silently returned only 1 of n.
    @Test
    fun `generateImage splits n into per-call requests and returns all images`() = runTest {
        val modelId = "accounts/fireworks/models/SSD-1B"
        val url = "https://fireworks.test/image_generation/$modelId"
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                url to UrlHandler(
                    UrlResponse.Binary(
                        "image-bytes".encodeToByteArray(),
                        headers = mapOf(HttpHeaders.ContentType to "image/png"),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val provider = Fireworks(
            fixture.httpClient(),
            FireworksProviderSettings(apiKey = "key", baseURL = "https://fireworks.test"),
        )

        assertEquals(1, provider.image(ModelId(modelId)).maxImagesPerCall)

        val result = ImageGeneration.generateImage(provider.image(ModelId(modelId)), prompt = "a cat", n = 2)

        assertEquals(2, result.images.size)
        assertEquals(2, fixture.calls.count { it.requestUrl == url })
    }
}
