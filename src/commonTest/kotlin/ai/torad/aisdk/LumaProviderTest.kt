package ai.torad.aisdk

import io.ktor.http.HttpHeaders
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LumaProviderTest {
    @Test
    fun `image model creates generation polls and downloads image`() = runTest {
        val fixture = createTestServer(
            mutableMapOf(
                "https://api.lumalabs.ai/dream-machine/v1/generations/image" to UrlHandler(
                    UrlResponse.JsonValue(Json.parseToJsonElement("""{"id":"gen1","state":"queued"}""")),
                ),
                "https://api.lumalabs.ai/dream-machine/v1/generations/gen1" to UrlHandler(
                    UrlResponse.JsonValue(Json.parseToJsonElement("""{"id":"gen1","state":"completed","assets":{"image":"https://cdn.example/image.png"}}""")),
                ),
                "https://cdn.example/image.png" to UrlHandler(
                    UrlResponse.Binary(byteArrayOf(1, 2, 3), headers = mapOf(HttpHeaders.ContentType to "image/png")),
                ),
            ),
        )
        fixture.server.start()
        val model = createLuma(fixture.httpClient(), LumaProviderSettings(apiKey = "key")).image("photon-1")

        val result = model.generate(
            ImageGenerationParams(
                prompt = "a kinetic sculpture",
                size = "1024x1024",
                aspectRatio = "16:9",
                seed = 42,
                files = listOf(
                    ImageGenerationFile(url = "https://example.com/ref-a.png"),
                    ImageGenerationFile(url = "https://example.com/ref-b.png"),
                ),
                providerOptions = mapOf(
                    "luma" to buildJsonObject {
                        put("pollIntervalMillis", JsonPrimitive(0))
                        put("maxPollAttempts", JsonPrimitive(1))
                        put("referenceType", JsonPrimitive("image"))
                        put("images", buildJsonArray {
                            add(buildJsonObject { put("weight", JsonPrimitive(0.9f)) })
                            add(buildJsonObject { put("weight", JsonPrimitive(0.5f)) })
                        })
                        put("style", JsonPrimitive("cinematic"))
                    },
                ),
            ),
        )

        assertEquals("luma.image", model.provider)
        assertEquals("image/png", result.images.single().mediaType)
        assertEquals(convertByteArrayToBase64(byteArrayOf(1, 2, 3)), result.images.single().base64)
        assertEquals(2, result.warnings.size)
        assertTrue(result.warnings.any { it.message.orEmpty().contains("seed") })
        assertTrue(result.warnings.any { it.message.orEmpty().contains("size") })

        val create = fixture.calls[0]
        assertEquals("POST", create.requestMethod)
        assertEquals("Bearer key", create.requestHeaders.headerValue(HttpHeaders.Authorization))
        assertTrue(create.requestUserAgent.orEmpty().contains("ai-sdk/luma/$LUMA_VERSION"))
        val body = create.requestBodyJson.jsonObject
        assertEquals("a kinetic sculpture", body["prompt"]?.jsonPrimitive?.contentOrNull)
        assertEquals("16:9", body["aspect_ratio"]?.jsonPrimitive?.contentOrNull)
        assertEquals("photon-1", body["model"]?.jsonPrimitive?.contentOrNull)
        assertEquals("cinematic", body["style"]?.jsonPrimitive?.contentOrNull)
        assertEquals("https://example.com/ref-a.png", body["image"]?.jsonArray?.first()?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull)
        assertEquals(0.9f, body["image"]?.jsonArray?.first()?.jsonObject?.get("weight")?.jsonPrimitive?.floatOrNull)
    }

    @Test
    fun `image model maps character references`() = runTest {
        val fixture = createTestServer(
            mutableMapOf(
                "https://api.lumalabs.ai/dream-machine/v1/generations/image" to UrlHandler(
                    UrlResponse.JsonValue(Json.parseToJsonElement("""{"id":"gen1","state":"queued"}""")),
                ),
                "https://api.lumalabs.ai/dream-machine/v1/generations/gen1" to UrlHandler(
                    UrlResponse.JsonValue(Json.parseToJsonElement("""{"id":"gen1","state":"completed","assets":{"image":"https://cdn.example/image.png"}}""")),
                ),
                "https://cdn.example/image.png" to UrlHandler(UrlResponse.Binary(byteArrayOf(1))),
            ),
        )
        fixture.server.start()
        val model = createLuma(fixture.httpClient(), LumaProviderSettings(apiKey = "key")).image("photon-flash-1")

        model.generate(
            ImageGenerationParams(
                prompt = "portrait",
                files = listOf(
                    ImageGenerationFile(url = "https://example.com/a.png"),
                    ImageGenerationFile(url = "https://example.com/b.png"),
                ),
                providerOptions = mapOf(
                    "luma" to buildJsonObject {
                        put("pollIntervalMillis", JsonPrimitive(0))
                        put("maxPollAttempts", JsonPrimitive(1))
                        put("referenceType", JsonPrimitive("character"))
                        put("images", buildJsonArray {
                            add(buildJsonObject { put("id", JsonPrimitive("hero")) })
                            add(buildJsonObject { put("id", JsonPrimitive("hero")) })
                        })
                    },
                ),
            ),
        )

        val character = fixture.calls.first().requestBodyJson.jsonObject["character"]?.jsonObject?.get("hero")?.jsonObject
        assertEquals(2, character?.get("images")?.jsonArray?.size)
        assertEquals("https://example.com/a.png", character?.get("images")?.jsonArray?.first()?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `image model rejects base64 reference files and masks`() = runTest {
        val fixture = createTestServer(mutableMapOf())
        val model = createLuma(fixture.httpClient(), LumaProviderSettings(apiKey = "key")).image("photon-1")

        assertFailsWith<AiSdkException> {
            model.generate(ImageGenerationParams(prompt = "x", files = listOf(ImageGenerationFile(mediaType = "image/png", base64 = "abc"))))
        }
        assertFailsWith<AiSdkException> {
            model.generate(ImageGenerationParams(prompt = "x", mask = ImageGenerationFile(url = "https://example.com/mask.png")))
        }
    }

    @Test
    fun `default provider and unsupported model families fail explicitly`() {
        val fixture = createTestServer(mutableMapOf())
        val provider = createLuma(fixture.httpClient(), LumaProviderSettings(apiKey = "key"))

        assertFailsWith<NoSuchModelError> { provider.languageModel("model") }
        assertFailsWith<NoSuchModelError> { provider.embeddingModel("embed") }
        assertFailsWith<AiSdkException> { luma.image("photon-1") }
    }

    private fun Map<String, String>.headerValue(name: String): String? =
        entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}
