package ai.torad.aisdk
import ai.torad.aisdk.providers.REPLICATE_VERSION
import ai.torad.aisdk.providers.ReplicateProviderSettings

import io.ktor.http.HttpHeaders
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import ai.torad.aisdk.providers.Replicate
import kotlinx.serialization.json.JsonObject

class ReplicateProviderTest {
    @Test
    fun `image model sends versioned prediction and downloads outputs`() = runTest {
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://api.replicate.com/v1/predictions" to UrlHandler(
                    UrlResponse.JsonValue(Json.parseToJsonElement("""{"output":["https://cdn.example/a.png","https://cdn.example/b.webp"]}""")),
                ),
                "https://cdn.example/a.png" to UrlHandler(
                    UrlResponse.Binary(byteArrayOf(1, 2), headers = mapOf(HttpHeaders.ContentType to "image/png")),
                ),
                "https://cdn.example/b.webp" to UrlHandler(
                    UrlResponse.Binary(byteArrayOf(3, 4), headers = mapOf(HttpHeaders.ContentType to "image/webp")),
                ),
            ),
        )
        fixture.server.start()
        val model = Replicate(
            fixture.httpClient(),
            ReplicateProviderSettings(apiToken = "token"),
        ).image(ModelId("owner/model:version-123"))

        val result = model.generate(
            ImageGenerationParams(
                prompt = "make an icon",
                n = 2,
                size = "1024x1024",
                aspectRatio = "1:1",
                seed = 9,
                files = listOf(ImageGenerationFile(mediaType = "image/png", base64 = "imgb64")),
                mask = ImageGenerationFile(url = "https://example.com/mask.png"),
                providerOptions = ProviderOptions.Raw(JsonObject(mapOf(
                    "replicate" to buildJsonObject {
                        put("maxWaitTimeInSeconds", JsonPrimitive(5))
                        put("guidance_scale", JsonPrimitive(3.5))
                        put("negative_prompt", JsonPrimitive("blur"))
                        put("custom_option", JsonPrimitive("kept"))
                    },
                ))),
            ),
        )

        assertEquals("replicate", model.provider)
        assertEquals(1, model.maxImagesPerCall)
        assertEquals(8, Replicate(fixture.httpClient()).image(ModelId("black-forest-labs/flux-2-pro")).maxImagesPerCall)
        assertEquals(listOf("image/png", "image/webp"), result.images.map { it.mediaType })
        assertEquals(Base64Codec.encode(byteArrayOf(1, 2)), result.images.first().base64)

        val create = fixture.calls[0]
        assertEquals("POST", create.requestMethod)
        assertEquals("Bearer token", create.requestHeaders.headerValue(HttpHeaders.Authorization))
        assertEquals("wait=5", create.requestHeaders.headerValue("prefer"))
        assertTrue(create.requestUserAgent.orEmpty().contains("ai-sdk/replicate/$REPLICATE_VERSION"))
        val body = create.requestBodyJson.jsonObject
        assertEquals("version-123", body["version"]?.jsonPrimitive?.contentOrNull)
        val input = body["input"]?.jsonObject
        assertEquals("make an icon", input?.get("prompt")?.jsonPrimitive?.contentOrNull)
        assertEquals("1:1", input?.get("aspect_ratio")?.jsonPrimitive?.contentOrNull)
        assertEquals("1024x1024", input?.get("size")?.jsonPrimitive?.contentOrNull)
        assertEquals(9, input?.get("seed")?.jsonPrimitive?.intOrNull)
        assertEquals(2, input?.get("num_outputs")?.jsonPrimitive?.intOrNull)
        assertEquals("data:image/png;base64,imgb64", input?.get("image")?.jsonPrimitive?.contentOrNull)
        assertEquals("https://example.com/mask.png", input?.get("mask")?.jsonPrimitive?.contentOrNull)
        assertEquals(3.5, input?.get("guidance_scale")?.jsonPrimitive?.doubleOrNull)
        assertEquals("blur", input?.get("negative_prompt")?.jsonPrimitive?.contentOrNull)
        assertEquals("kept", input?.get("custom_option")?.jsonPrimitive?.contentOrNull)
        assertNull(input?.get("maxWaitTimeInSeconds"))
    }

    @Test
    fun `flux two image models map up to eight input images and warn on extras`() = runTest {
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://api.replicate.com/v1/models/black-forest-labs/flux-2-pro/predictions" to UrlHandler(
                    UrlResponse.JsonValue(Json.parseToJsonElement("""{"output":"https://cdn.example/out.png"}""")),
                ),
                "https://cdn.example/out.png" to UrlHandler(UrlResponse.Binary(byteArrayOf(8))),
            ),
        )
        fixture.server.start()
        val model = Replicate(
            fixture.httpClient(),
            ReplicateProviderSettings(apiToken = "token"),
        ).image(ModelId("black-forest-labs/flux-2-pro"))

        val result = model.generate(
            ImageGenerationParams(
                prompt = "blend refs",
                files = List(9) { ImageGenerationFile(url = "https://example.com/$it.png") },
                mask = ImageGenerationFile(url = "https://example.com/mask.png"),
            ),
        )

        val input = fixture.calls.first().requestBodyJson.jsonObject["input"]?.jsonObject
        assertEquals("https://example.com/0.png", input?.get("input_image")?.jsonPrimitive?.contentOrNull)
        assertEquals("https://example.com/7.png", input?.get("input_image_8")?.jsonPrimitive?.contentOrNull)
        assertNull(input?.get("input_image_9"))
        assertNull(input?.get("mask"))
        assertEquals(2, result.warnings.size)
        assertTrue(result.warnings.any { it.message.orEmpty().contains("up to 8") })
        assertTrue(result.warnings.any { it.message.orEmpty().contains("mask") })
    }

    @Test
    fun `video model submits polls and returns url video metadata`() = runTest {
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://api.replicate.com/v1/models/minimax/video-01/predictions" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """{"id":"pred1","status":"processing","output":null,"urls":{"get":"https://api.replicate.com/v1/predictions/pred1"},"metrics":null}""",
                        ),
                    ),
                ),
                "https://api.replicate.com/v1/predictions/pred1" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """{"id":"pred1","status":"succeeded","output":"https://cdn.example/video.mp4","urls":{"get":"https://api.replicate.com/v1/predictions/pred1"},"metrics":{"predict_time":1.25}}""",
                        ),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val model = Replicate(
            fixture.httpClient(),
            ReplicateProviderSettings(apiToken = "token"),
        ).video(ModelId("minimax/video-01"))

        val result = model.generate(
            VideoGenerationParams(
                prompt = "camera push in",
                image = GeneratedFile(mediaType = "image/png", base64 = "frameb64"),
                durationSeconds = 3f,
                aspectRatio = "16:9",
                seed = 77,
                fps = 24,
                resolution = "720p",
                providerOptions = ProviderOptions.Raw(JsonObject(mapOf(
                    "replicate" to buildJsonObject {
                        put("maxWaitTimeInSeconds", JsonPrimitive(1))
                        put("pollIntervalMs", JsonPrimitive(0))
                        put("pollTimeoutMs", JsonPrimitive(1))
                        put("guidance_scale", JsonPrimitive(2.0))
                        put("prompt_optimizer", JsonPrimitive(true))
                        put("custom_video_option", JsonPrimitive("kept"))
                    },
                ))),
            ),
        )

        assertEquals("replicate.video", model.provider)
        assertEquals(1, model.maxVideosPerCall)
        assertEquals("video/mp4", result.videos.single().mediaType)
        assertEquals("https://cdn.example/video.mp4", result.videos.single().url)
        val create = fixture.calls[0]
        assertEquals("wait=1", create.requestHeaders.headerValue("prefer"))
        val input = create.requestBodyJson.jsonObject["input"]?.jsonObject
        assertEquals("camera push in", input?.get("prompt")?.jsonPrimitive?.contentOrNull)
        assertEquals("data:image/png;base64,frameb64", input?.get("image")?.jsonPrimitive?.contentOrNull)
        assertEquals("16:9", input?.get("aspect_ratio")?.jsonPrimitive?.contentOrNull)
        assertEquals("720p", input?.get("size")?.jsonPrimitive?.contentOrNull)
        assertEquals(3.0, input?.get("duration")?.jsonPrimitive?.doubleOrNull)
        assertEquals(24, input?.get("fps")?.jsonPrimitive?.intOrNull)
        assertEquals(77, input?.get("seed")?.jsonPrimitive?.intOrNull)
        assertEquals(2.0, input?.get("guidance_scale")?.jsonPrimitive?.doubleOrNull)
        assertEquals("kept", input?.get("custom_video_option")?.jsonPrimitive?.contentOrNull)
        assertNull(input?.get("pollIntervalMs"))

        val metadata = result.providerMetadata.toMap()["replicate"]?.jsonObject
        assertEquals("pred1", metadata?.get("predictionId")?.jsonPrimitive?.contentOrNull)
        assertEquals(
            "https://cdn.example/video.mp4",
            metadata?.get("videos")?.jsonArray?.single()?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull,
        )
        assertEquals(1.25, metadata?.get("metrics")?.jsonObject?.get("predict_time")?.jsonPrimitive?.doubleOrNull)
    }

    @Test
    fun `video model reports failed predictions and unsupported families`() = runTest {
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://api.replicate.com/v1/models/minimax/video-01/predictions" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """{"id":"pred1","status":"failed","output":null,"error":"bad prompt","urls":{"get":"https://api.replicate.com/v1/predictions/pred1"}}""",
                        ),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val provider = Replicate(fixture.httpClient(), ReplicateProviderSettings(apiToken = "token"))

        assertFailsWith<AiSdkException> {
            provider.video(ModelId("minimax/video-01")).generate(VideoGenerationParams(prompt = "x"))
        }
        assertFailsWith<NoSuchModelError> { provider.languageModel("model") }
        assertFailsWith<NoSuchModelError> { provider.embeddingModel("embed") }
    }

    private fun Map<String, String>.headerValue(name: String): String? =
        entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}
