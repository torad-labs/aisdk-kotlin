package ai.torad.aisdk

import ai.torad.aisdk.providers.Fal
import ai.torad.aisdk.providers.FalProviderSettings
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class FalDefensiveParsingTest {
    /**
     * Regression (the M4 bug-class): the Fal image parser read `content_type` via
     * `?.jsonPrimitive?.contentOrNull`, which throws on a present-but-non-primitive field — failing
     * the whole generate(). The safe `(X as? JsonPrimitive)?.…` degrades to null -> the existing
     * binary-header fallback. The model is private, so this drives it through the public provider.
     */
    @Test
    fun `image generate degrades a non-primitive content_type to the header fallback`() = runTest {
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://fal.test/fal-ai/qwen-image" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """{"images":[{"url":"https://fal.media/files/image.png","content_type":{"oops":1}}]}""",
                        ),
                    ),
                ),
                "https://fal.media/files/image.png" to UrlHandler(
                    UrlResponse.Binary(
                        "image-bytes".encodeToByteArray(),
                        headers = mapOf(HttpHeaders.ContentType to "image/png"),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val provider = Fal(
            fixture.httpClient(),
            FalProviderSettings {
                apiKey("key")
                baseURL("https://fal.test")
            },
        )

        val result = provider.image(ModelId("fal-ai/qwen-image")).generate(
            ImageGenerationParams {
                prompt("x")
                n(1)
                size("1024x1024")
            },
        )

        assertEquals(
            "image/png",
            result.images.single().mediaType,
            "a non-primitive content_type degrades to the binary header fallback, no crash",
        )
    }
}
