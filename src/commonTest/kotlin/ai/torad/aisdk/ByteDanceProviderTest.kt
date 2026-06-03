package ai.torad.aisdk

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
import kotlin.test.assertTrue

class ByteDanceProviderTest {
    @Test
    fun `video model creates task polls and returns url metadata`() = runTest {
        val fixture = createTestServer(
            mutableMapOf(
                "https://ark.test/api/v3/contents/generations/tasks" to UrlHandler(
                    UrlResponse.JsonValue(Json.parseToJsonElement("""{"id":"task-1"}""")),
                ),
                "https://ark.test/api/v3/contents/generations/tasks/task-1" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """{"id":"task-1","model":"seedance","status":"succeeded","content":{"video_url":"https://cdn.example/video.mp4"},"usage":{"completion_tokens":100}}""",
                        ),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val model = createByteDance(
            fixture.httpClient(),
            ByteDanceProviderSettings(apiKey = "key", baseURL = "https://ark.test/api/v3", headers = mapOf("X-Test" to "1")),
        ).video("seedance-1-0-pro-250528")

        val result = model.generate(
            VideoGenerationParams(
                prompt = "A futuristic city",
                n = 2,
                image = GeneratedFile(mediaType = "image/png", base64 = "frame"),
                durationSeconds = 5f,
                aspectRatio = "16:9",
                resolution = "1920x1080",
                seed = 42,
                fps = 30,
                providerOptions = mapOf(
                    "bytedance" to buildJsonObject {
                        put("watermark", JsonPrimitive(true))
                        put("generateAudio", JsonPrimitive(false))
                        put("cameraFixed", JsonPrimitive(true))
                        put("returnLastFrame", JsonPrimitive(true))
                        put("serviceTier", JsonPrimitive("flex"))
                        put("draft", JsonPrimitive(false))
                        put("lastFrameImage", JsonPrimitive("https://example.com/last.png"))
                        put("referenceImages", kotlinx.serialization.json.buildJsonArray {
                            add(JsonPrimitive("https://example.com/ref.png"))
                        })
                        put("referenceVideos", kotlinx.serialization.json.buildJsonArray {
                            add(JsonPrimitive("https://example.com/ref.mp4"))
                        })
                        put("referenceAudio", kotlinx.serialization.json.buildJsonArray {
                            add(JsonPrimitive("https://example.com/ref.mp3"))
                        })
                        put("pollIntervalMs", JsonPrimitive(0))
                        put("pollTimeoutMs", JsonPrimitive(1))
                        put("custom_option", JsonPrimitive("kept"))
                    },
                ),
            ),
        )

        assertEquals("bytedance.video", model.provider)
        assertEquals("https://cdn.example/video.mp4", result.videos.single().url)
        assertEquals("video/mp4", result.videos.single().mediaType)
        assertEquals(2, result.warnings.size)
        assertTrue(result.warnings.any { it.message.orEmpty().contains("FPS") })
        assertTrue(result.warnings.any { it.message.orEmpty().contains("multiple videos") })

        val create = fixture.calls.first()
        assertEquals("Bearer key", create.requestHeaders.headerValue(HttpHeaders.Authorization))
        assertEquals("1", create.requestHeaders.headerValue("X-Test"))
        val body = create.requestBodyJson.jsonObject
        assertEquals("seedance-1-0-pro-250528", body["model"]?.jsonPrimitive?.contentOrNull)
        assertEquals("16:9", body["ratio"]?.jsonPrimitive?.contentOrNull)
        assertEquals(5.0, body["duration"]?.jsonPrimitive?.doubleOrNull)
        assertEquals(42, body["seed"]?.jsonPrimitive?.intOrNull)
        assertEquals("1080p", body["resolution"]?.jsonPrimitive?.contentOrNull)
        assertEquals("flex", body["service_tier"]?.jsonPrimitive?.contentOrNull)
        assertEquals("kept", body["custom_option"]?.jsonPrimitive?.contentOrNull)
        val content = body["content"]?.jsonArray.orEmpty()
        assertEquals("text", content[0].jsonObject["type"]?.jsonPrimitive?.contentOrNull)
        assertEquals("data:image/png;base64,frame", content[1].jsonObject["image_url"]?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull)
        assertEquals("last_frame", content[2].jsonObject["role"]?.jsonPrimitive?.contentOrNull)
        assertEquals("reference_image", content[3].jsonObject["role"]?.jsonPrimitive?.contentOrNull)
        assertEquals("reference_video", content[4].jsonObject["role"]?.jsonPrimitive?.contentOrNull)
        assertEquals("reference_audio", content[5].jsonObject["role"]?.jsonPrimitive?.contentOrNull)

        val metadata = result.providerMetadata["bytedance"]?.jsonObject
        assertEquals("task-1", metadata?.get("taskId")?.jsonPrimitive?.contentOrNull)
        assertEquals(100, metadata?.get("usage")?.jsonObject?.get("completion_tokens")?.jsonPrimitive?.intOrNull)
    }

    @Test
    fun `video model reports failed tasks and unsupported families`() = runTest {
        val fixture = createTestServer(
            mutableMapOf(
                "https://ark.test/api/v3/contents/generations/tasks" to UrlHandler(
                    UrlResponse.JsonValue(Json.parseToJsonElement("""{"id":"task-1"}""")),
                ),
                "https://ark.test/api/v3/contents/generations/tasks/task-1" to UrlHandler(
                    UrlResponse.JsonValue(Json.parseToJsonElement("""{"id":"task-1","status":"failed"}""")),
                ),
            ),
        )
        fixture.server.start()
        val provider = createByteDance(fixture.httpClient(), ByteDanceProviderSettings(apiKey = "key", baseURL = "https://ark.test/api/v3"))

        assertFailsWith<AiSdkException> {
            provider.video("seedance").generate(
                VideoGenerationParams(
                    prompt = "x",
                    providerOptions = mapOf("bytedance" to buildJsonObject {
                        put("pollIntervalMs", JsonPrimitive(0))
                        put("pollTimeoutMs", JsonPrimitive(1))
                    }),
                ),
            )
        }
        assertFailsWith<NoSuchModelError> { provider.languageModel("model") }
        assertFailsWith<NoSuchModelError> { provider.embeddingModel("embed") }
        assertFailsWith<NoSuchModelError> { provider.imageModel("image") }
        assertFailsWith<AiSdkException> { byteDance.video("seedance") }
    }

    private fun Map<String, String>.headerValue(name: String): String? =
        entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}
