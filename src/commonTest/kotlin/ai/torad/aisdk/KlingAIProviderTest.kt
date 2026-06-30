package ai.torad.aisdk
import ai.torad.aisdk.providers.KLINGAI_VERSION
import ai.torad.aisdk.providers.KlingAIProviderSettings

import io.ktor.http.HttpHeaders
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
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
import ai.torad.aisdk.providers.KlingAI
import kotlinx.serialization.json.JsonObject

class KlingAIProviderTest {
    @Test
    fun `text to video sends KlingAI body polls and maps url video`() = runTest {
        val fixture = createKlingAIFixture("/v1/videos/text2video")
        val model = KlingAI(
            fixture.httpClient(),
            KlingAIProviderSettings {
                accessKey("access-key")
                secretKey("secret-key")
                baseURL(KLING_TEST_BASE_URL)
                headers(mapOf("X-Provider" to "provider"))
            },
        ).video(ModelId("kling-v2.6-t2v"))

        val result = model.generate(
            VideoGenerationParams {
                prompt("A character dances")
                n(2)
                image(GeneratedFile(mediaType = "image/png", base64 = "ignored"))
                durationSeconds(5f)
                aspectRatio("16:9")
                seed(9)
                fps(24)
                resolution("720p")
                providerOptions(ProviderOptions.Raw(JsonObject(mapOf(
                                    "klingai" to buildJsonObject {
                                        put("mode", JsonPrimitive("pro"))
                                        put("pollIntervalMs", JsonPrimitive(0))
                                        put("pollTimeoutMs", JsonPrimitive(1_000))
                                        put("negativePrompt", JsonPrimitive("blur"))
                                        put("sound", JsonPrimitive("on"))
                                        put("cfgScale", JsonPrimitive(0.7f))
                                        put("cameraControl", buildJsonObject {
                                            put("type", JsonPrimitive("simple"))
                                            put("config", buildJsonObject { put("zoom", JsonPrimitive(0.2f)) })
                                        })
                                        put("multiShot", JsonPrimitive(true))
                                        put("shotType", JsonPrimitive("intelligence"))
                                        put("multiPrompt", buildJsonArray {
                                            add(buildJsonObject {
                                                put("index", JsonPrimitive(1))
                                                put("prompt", JsonPrimitive("wide shot"))
                                                put("duration", JsonPrimitive("5"))
                                            })
                                        })
                                        put("voiceList", buildJsonArray {
                                            add(buildJsonObject { put("voice_id", JsonPrimitive("voice_1")) })
                                        })
                                        put("watermarkEnabled", JsonPrimitive(false))
                                        put("custom_passthrough", JsonPrimitive("kept"))
                                    },
                                ))))
                headers(mapOf("X-Request" to "request"))
            },
        )

        assertEquals("klingai.video", model.provider)
        assertEquals(1, model.maxVideosPerCall)
        assertEquals("video/mp4", result.videos.single().mediaType)
        assertEquals("https://cdn.kling.test/video.mp4", result.videos.single().url)
        assertEquals("task-1", result.providerMetadata.toMap()["klingai"]?.jsonObject?.get("taskId")?.jsonPrimitive?.contentOrNull)
        val videoMetadata = result.providerMetadata.toMap()["klingai"]?.jsonObject?.get("videos")?.jsonArray?.single()?.jsonObject
        assertEquals("video-1", videoMetadata?.get("id")?.jsonPrimitive?.contentOrNull)
        assertEquals("https://cdn.kling.test/video-watermark.mp4", videoMetadata?.get("watermarkUrl")?.jsonPrimitive?.contentOrNull)
        assertEquals("5.0", videoMetadata?.get("duration")?.jsonPrimitive?.contentOrNull)
        assertTrue(result.warnings.any { it.message.orEmpty().contains("image input") })
        assertTrue(result.warnings.any { it.message.orEmpty().contains("resolution") })
        assertTrue(result.warnings.any { it.message.orEmpty().contains("seed") })
        assertTrue(result.warnings.any { it.message.orEmpty().contains("FPS") })
        assertTrue(result.warnings.any { it.message.orEmpty().contains("multiple videos") })

        val create = fixture.calls[0]
        assertEquals("POST", create.requestMethod)
        assertEquals("$KLING_TEST_BASE_URL/v1/videos/text2video", create.requestUrl)
        assertEquals("provider", create.requestHeaders.headerValue("X-Provider"))
        assertEquals("request", create.requestHeaders.headerValue("X-Request"))
        assertTrue(create.requestUserAgent.orEmpty().contains("ai-sdk/klingai/$KLINGAI_VERSION"))
        assertKlingAIAuthHeader(create.requestHeaders.headerValue(HttpHeaders.Authorization))
        val body = create.requestBodyJson.jsonObject
        assertEquals("kling-v2-6", body["model_name"]?.jsonPrimitive?.contentOrNull)
        assertEquals("A character dances", body["prompt"]?.jsonPrimitive?.contentOrNull)
        assertEquals("pro", body["mode"]?.jsonPrimitive?.contentOrNull)
        assertEquals("blur", body["negative_prompt"]?.jsonPrimitive?.contentOrNull)
        assertEquals("on", body["sound"]?.jsonPrimitive?.contentOrNull)
        assertEquals(0.7f, body["cfg_scale"]?.jsonPrimitive?.floatOrNull)
        assertEquals("16:9", body["aspect_ratio"]?.jsonPrimitive?.contentOrNull)
        assertEquals("5", body["duration"]?.jsonPrimitive?.contentOrNull)
        assertEquals(true, body["multi_shot"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("intelligence", body["shot_type"]?.jsonPrimitive?.contentOrNull)
        assertEquals("voice_1", body["voice_list"]?.jsonArray?.single()?.jsonObject?.get("voice_id")?.jsonPrimitive?.contentOrNull)
        assertEquals(false, body["watermark_info"]?.jsonObject?.get("enabled")?.jsonPrimitive?.booleanOrNull)
        assertEquals("kept", body["custom_passthrough"]?.jsonPrimitive?.contentOrNull)
        assertEquals("GET", fixture.calls[1].requestMethod)
        assertEquals("$KLING_TEST_BASE_URL/v1/videos/text2video/task-1", fixture.calls[1].requestUrl)
    }

    @Test
    fun `image to video maps image options and warnings`() = runTest {
        val fixture = createKlingAIFixture("/v1/videos/image2video")
        val model = KlingAI(
            fixture.httpClient(),
            KlingAIProviderSettings {
                accessKey("access-key")
                secretKey("secret-key")
                baseURL(KLING_TEST_BASE_URL)
            },
        ).video(ModelId("kling-v3.0-i2v"))

        model.generate(
            VideoGenerationParams {
                prompt("Animate this frame")
                image(GeneratedFile(mediaType = "image/png", base64 = "frameb64"))
                durationSeconds(10f)
                aspectRatio("1:1")
                providerOptions(ProviderOptions.Raw(JsonObject(mapOf(
                                    "klingai" to buildJsonObject {
                                        put("mode", JsonPrimitive("std"))
                                        put("pollIntervalMs", JsonPrimitive(0))
                                        put("imageTail", JsonPrimitive("tailb64"))
                                        put("staticMask", JsonPrimitive("maskb64"))
                                        put("dynamicMasks", buildJsonArray {
                                            add(buildJsonObject {
                                                put("mask", JsonPrimitive("dynmask"))
                                                put("trajectories", buildJsonArray {
                                                    add(buildJsonObject {
                                                        put("x", JsonPrimitive(1))
                                                        put("y", JsonPrimitive(2))
                                                    })
                                                })
                                            })
                                        })
                                        put("elementList", buildJsonArray {
                                            add(buildJsonObject { put("element_id", JsonPrimitive(3)) })
                                        })
                                        put("watermarkEnabled", JsonPrimitive(true))
                                    },
                                ))))
            },
        )

        val body = fixture.calls[0].requestBodyJson.jsonObject
        assertEquals("kling-v3", body["model_name"]?.jsonPrimitive?.contentOrNull)
        assertEquals("frameb64", body["image"]?.jsonPrimitive?.contentOrNull)
        assertEquals("tailb64", body["image_tail"]?.jsonPrimitive?.contentOrNull)
        assertEquals("maskb64", body["static_mask"]?.jsonPrimitive?.contentOrNull)
        assertEquals("dynmask", body["dynamic_masks"]?.jsonArray?.single()?.jsonObject?.get("mask")?.jsonPrimitive?.contentOrNull)
        assertEquals(3, body["element_list"]?.jsonArray?.single()?.jsonObject?.get("element_id")?.jsonPrimitive?.contentOrNull?.toInt())
        assertEquals("10", body["duration"]?.jsonPrimitive?.contentOrNull)
        assertEquals(true, body["watermark_info"]?.jsonObject?.get("enabled")?.jsonPrimitive?.booleanOrNull)
    }

    @Test
    fun `motion control requires provider options and maps reference fields`() = runTest {
        val fixture = createKlingAIFixture("/v1/videos/motion-control")
        val model = KlingAI(
            fixture.httpClient(),
            KlingAIProviderSettings {
                accessKey("access-key")
                secretKey("secret-key")
                baseURL(KLING_TEST_BASE_URL)
            },
        ).video(ModelId("kling-v2.6-motion-control"))

        val missing = assertFailsWith<AiSdkException> {
            model.generate(VideoGenerationParams {
                prompt("missing options")
            })
        }
        assertTrue(missing.message.orEmpty().contains("Motion Control"))

        val result = model.generate(
            VideoGenerationParams {
                prompt("Follow the reference motion")
                image(GeneratedFile(mediaType = "image/png", base64 = "", url = "https://example.com/ref.png"))
                durationSeconds(5f)
                aspectRatio("16:9")
                providerOptions(ProviderOptions.Raw(JsonObject(mapOf(
                                    "klingai" to buildJsonObject {
                                        put("pollIntervalMs", JsonPrimitive(0))
                                        put("videoUrl", JsonPrimitive("https://example.com/ref.mp4"))
                                        put("characterOrientation", JsonPrimitive("image"))
                                        put("mode", JsonPrimitive("std"))
                                        put("keepOriginalSound", JsonPrimitive("yes"))
                                        put("watermarkEnabled", JsonPrimitive(false))
                                        put("custom_motion", JsonPrimitive("kept"))
                                    },
                                ))))
            },
        )

        assertTrue(result.warnings.any { it.message.orEmpty().contains("aspectRatio") })
        assertTrue(result.warnings.any { it.message.orEmpty().contains("duration") })
        val body = fixture.calls[0].requestBodyJson.jsonObject
        assertEquals("kling-v2-6", body["model_name"]?.jsonPrimitive?.contentOrNull)
        assertEquals("https://example.com/ref.mp4", body["video_url"]?.jsonPrimitive?.contentOrNull)
        assertEquals("image", body["character_orientation"]?.jsonPrimitive?.contentOrNull)
        assertEquals("std", body["mode"]?.jsonPrimitive?.contentOrNull)
        assertEquals("https://example.com/ref.png", body["image_url"]?.jsonPrimitive?.contentOrNull)
        assertEquals("yes", body["keep_original_sound"]?.jsonPrimitive?.contentOrNull)
        assertEquals(false, body["watermark_info"]?.jsonObject?.get("enabled")?.jsonPrimitive?.booleanOrNull)
        assertEquals("kept", body["custom_motion"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `unsupported KlingAI surfaces and unconfigured singleton fail explicitly`() = runTest {
        val provider = KlingAI(
            TestServer.createTestServer(mutableMapOf()).httpClient(),
            KlingAIProviderSettings {
                accessKey("access-key")
                secretKey("secret-key")
            },
        )

        assertFailsWith<NoSuchModelError> { provider.languageModel("model") }
        assertFailsWith<NoSuchModelError> { provider.embeddingModel("embed") }
        assertFailsWith<NoSuchModelError> { provider.imageModel("image") }
        assertFailsWith<NoSuchModelError> { provider.video(ModelId("unknown-model")).generate(VideoGenerationParams {
            prompt("x")
        }) }
    }

    private fun createKlingAIFixture(endpoint: String): CreatedTestServer {
        val createResponse = """{"code":0,"message":"success","request_id":"req-1","data":{"task_id":"task-1","task_status":"submitted"}}"""
        val statusResponse = """
            {
              "code":0,
              "message":"success",
              "request_id":"req-2",
              "data":{
                "task_id":"task-1",
                "task_status":"succeed",
                "task_result":{
                  "videos":[
                    {
                      "id":"video-1",
                      "url":"https://cdn.kling.test/video.mp4",
                      "watermark_url":"https://cdn.kling.test/video-watermark.mp4",
                      "duration":"5.0"
                    }
                  ]
                }
              }
            }
        """.trimIndent()
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "$KLING_TEST_BASE_URL$endpoint" to UrlHandler(UrlResponse.JsonValue(Json.parseToJsonElement(createResponse))),
                "$KLING_TEST_BASE_URL$endpoint/task-1" to UrlHandler(UrlResponse.JsonValue(Json.parseToJsonElement(statusResponse))),
            ),
        )
        fixture.server.start()
        return fixture
    }

    private fun assertKlingAIAuthHeader(value: String?) {
        assertTrue(value.orEmpty().startsWith("Bearer "))
        val token = value.orEmpty().removePrefix("Bearer ")
        val parts = token.split(".")
        assertEquals(3, parts.size)
        val header = Json.parseToJsonElement(decodeBase64UrlPart(parts[0])).jsonObject
        val payload = Json.parseToJsonElement(decodeBase64UrlPart(parts[1])).jsonObject
        assertEquals("HS256", header["alg"]?.jsonPrimitive?.contentOrNull)
        assertEquals("JWT", header["typ"]?.jsonPrimitive?.contentOrNull)
        assertEquals("access-key", payload["iss"]?.jsonPrimitive?.contentOrNull)
        assertTrue(parts[2].isNotBlank())
    }

    private fun decodeBase64UrlPart(value: String): String {
        val padding = "=".repeat((4 - value.length % 4) % 4)
        return Base64Codec.decode(value + padding).decodeToString()
    }

    private fun Map<String, String>.headerValue(name: String): String? =
        entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}

private const val KLING_TEST_BASE_URL: String = "https://kling.test"
