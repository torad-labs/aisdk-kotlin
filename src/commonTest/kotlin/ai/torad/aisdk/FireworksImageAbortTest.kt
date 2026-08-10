package ai.torad.aisdk

import ai.torad.aisdk.providers.Fireworks
import ai.torad.aisdk.providers.FireworksProviderSettings
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FireworksImageAbortTest {
    // The synchronous binary image path (`postFacadeBinary`) used to call the client
    // directly, so it never registered the caller's abort signal: an aborted request
    // still went out on the wire and returned an image.
    @Test
    fun `synchronous image generation honours an aborted signal`() = runTest {
        val modelId = "accounts/fireworks/models/flux-1-dev-fp8"
        val url = "https://fireworks.test/workflows/$modelId/text_to_image"
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
            FireworksProviderSettings {
                apiKey("key")
                baseURL("https://fireworks.test")
            },
        )
        val controller = AbortController()
        controller.abort()

        assertFailsWith<AbortError> {
            ImageGeneration.generateImage(
                provider.image(ModelId(modelId)),
                prompt = "a cat",
                abortSignal = controller.signal,
            )
        }
        assertEquals(0, fixture.calls.count { it.requestUrl == url })
    }
}
