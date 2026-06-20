package ai.torad.aisdk
import ai.torad.aisdk.providers.FAL_VERSION
import ai.torad.aisdk.providers.FalProviderSettings

import io.ktor.http.HttpHeaders
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import ai.torad.aisdk.providers.Fal
import kotlinx.serialization.json.JsonObject

class FalProviderTest {
    @Test
    fun `image model maps request options downloads images and exposes metadata`() = runTest {
        val fixture = createTestServer(
            mutableMapOf(
                "https://fal.test/fal-ai/qwen-image" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """
                            {
                              "images":[
                                {
                                  "url":"https://fal.media/files/image.png",
                                  "width":1024,
                                  "height":1024,
                                  "content_type":"image/png",
                                  "file_name":"image.png",
                                  "file_size":12
                                }
                              ],
                              "seed":123,
                              "timings":{"inference":4.5},
                              "has_nsfw_concepts":[false],
                              "prompt":"not revised",
                              "num_inference_steps":30
                            }
                            """.trimIndent(),
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
            FalProviderSettings(
                apiKey = "key",
                baseURL = "https://fal.test",
                headers = mapOf("X-Provider" to "provider"),
            ),
        )

        val result = provider.image("fal-ai/qwen-image").generate(
            ImageGenerationParams(
                prompt = "A clean product render",
                n = 1,
                size = "1024x1024",
                seed = 123,
                files = listOf(
                    ImageGenerationFile(mediaType = "image/png", base64 = "aW1hZ2U="),
                    ImageGenerationFile(url = "https://example.com/second.png"),
                ),
                mask = ImageGenerationFile(mediaType = "image/png", base64 = "bWFzaw=="),
                providerOptions = ProviderOptions.Raw(JsonObject(mapOf(
                    "fal" to buildJsonObject {
                        put("guidanceScale", JsonPrimitive(7.5f))
                        put("num_inference_steps", JsonPrimitive(30))
                        put("enableSafetyChecker", JsonPrimitive(false))
                        put("extra_param", JsonPrimitive("extra"))
                    },
                ))),
                headers = mapOf("X-Request" to "request"),
            ),
        )

        assertEquals("fal.image", provider.imageModel("fal-ai/qwen-image").provider)
        assertEquals(1, provider.imageModel("fal-ai/qwen-image").maxImagesPerCall)
        assertEquals("image/png", result.images.single().mediaType)
        assertEquals("image-bytes", Base64Codec.decode(result.images.single().base64).decodeToString())
        assertEquals("https://fal.media/files/image.png", result.images.single().url)
        assertEquals("image.png", result.images.single().filename)
        assertEquals(false, result.providerMetadata["fal"]?.jsonObject?.get("images")?.jsonArray?.single()?.jsonObject?.get("nsfw")?.jsonPrimitive?.booleanOrNull)
        assertEquals(123, result.providerMetadata["fal"]?.jsonObject?.get("seed")?.jsonPrimitive?.intOrNull)
        assertEquals(30, result.providerMetadata["fal"]?.jsonObject?.get("num_inference_steps")?.jsonPrimitive?.intOrNull)
        assertTrue(result.warnings.any { it.message.orEmpty().contains("Multiple input images") })
        assertTrue(result.warnings.any { it.message.orEmpty().contains("num_inference_steps") })

        val request = fixture.calls.first()
        assertEquals("POST", request.requestMethod)
        assertEquals("https://fal.test/fal-ai/qwen-image", request.requestUrl)
        assertEquals("Key key", request.requestHeaders.headerValue(HttpHeaders.Authorization))
        assertEquals("provider", request.requestHeaders.headerValue("X-Provider"))
        assertEquals("request", request.requestHeaders.headerValue("X-Request"))
        assertTrue(request.requestUserAgent.orEmpty().contains("ai-sdk/fal/$FAL_VERSION"))
        val body = request.requestBodyJson.jsonObject
        assertEquals("A clean product render", body["prompt"]?.jsonPrimitive?.contentOrNull)
        assertEquals(123, body["seed"]?.jsonPrimitive?.intOrNull)
        assertEquals(1024, body["image_size"]?.jsonObject?.get("width")?.jsonPrimitive?.intOrNull)
        assertEquals(1, body["num_images"]?.jsonPrimitive?.intOrNull)
        assertEquals("data:image/png;base64,aW1hZ2U=", body["image_url"]?.jsonPrimitive?.contentOrNull)
        assertEquals("data:image/png;base64,bWFzaw==", body["mask_url"]?.jsonPrimitive?.contentOrNull)
        assertEquals(7.5f, body["guidance_scale"]?.jsonPrimitive?.floatOrNull)
        assertEquals(30, body["num_inference_steps"]?.jsonPrimitive?.intOrNull)
        assertEquals(false, body["enable_safety_checker"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("extra", body["extra_param"]?.jsonPrimitive?.contentOrNull)
        assertEquals("https://fal.media/files/image.png", fixture.calls[1].requestUrl)
    }

    @Test
    fun `speech model posts text options downloads audio and warns on unsupported settings`() = runTest {
        val fixture = createTestServer(
            mutableMapOf(
                "https://fal.run/fal-ai/minimax/speech-02-hd" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """
                            {
                              "audio":{"url":"https://fal.media/files/speech.mp3"},
                              "duration_ms":1234,
                              "request_id":"speech-1"
                            }
                            """.trimIndent(),
                        ),
                    ),
                ),
                "https://fal.media/files/speech.mp3" to UrlHandler(
                    UrlResponse.Binary(
                        "audio-bytes".encodeToByteArray(),
                        headers = mapOf(HttpHeaders.ContentType to "audio/mp3"),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val provider = Fal(fixture.httpClient(), FalProviderSettings(apiKey = "key"))

        val result = provider.speech("fal-ai/minimax/speech-02-hd").generate(
            SpeechGenerationParams(
                text = "Hello from the AI SDK!",
                voice = "voice-1",
                instructions = "Speak softly.",
                speed = 1.2f,
                responseFormat = "wav",
                providerOptions = ProviderOptions.Raw(JsonObject(mapOf(
                    "fal" to buildJsonObject {
                        put(
                            "voice_setting",
                            buildJsonObject { put("emotion", JsonPrimitive("happy")) },
                        )
                    },
                ))),
                headers = mapOf("X-Request" to "request"),
            ),
        )

        assertEquals("fal.speech", provider.speechModel("fal-ai/minimax/speech-02-hd").provider)
        assertEquals("audio/mp3", result.audio?.mediaType)
        assertEquals("audio-bytes", Base64Codec.decode(result.audio?.base64.orEmpty()).decodeToString())
        assertTrue(result.warnings.any { it.message == "instructions" })
        assertTrue(result.warnings.any { it.message == "outputFormat" })

        val request = fixture.calls.first()
        assertEquals("Key key", request.requestHeaders.headerValue(HttpHeaders.Authorization))
        assertEquals("request", request.requestHeaders.headerValue("X-Request"))
        val body = request.requestBodyJson.jsonObject
        assertEquals("Hello from the AI SDK!", body["text"]?.jsonPrimitive?.contentOrNull)
        assertEquals("url", body["output_format"]?.jsonPrimitive?.contentOrNull)
        assertEquals("voice-1", body["voice"]?.jsonPrimitive?.contentOrNull)
        assertEquals(1.2f, body["speed"]?.jsonPrimitive?.floatOrNull)
        assertEquals("happy", body["voice_setting"]?.jsonObject?.get("emotion")?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `transcription model queues audio polls result and maps chunks`() = runTest {
        val fixture = createTestServer(
            mutableMapOf(
                "https://queue.fal.run/fal-ai/wizper" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement("""{"request_id":"transcribe-1"}"""),
                    ),
                ),
                "https://queue.fal.run/fal-ai/wizper/requests/transcribe-1" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """
                            {
                              "text":"Hello from the Vercel AI SDK.",
                              "chunks":[
                                {"text":"Hello","timestamp":[0.0,0.5]},
                                {"text":"SDK","timestamp":[0.5,1.0]}
                              ],
                              "inferred_languages":["en"]
                            }
                            """.trimIndent(),
                        ),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val provider = Fal(
            fixture.httpClient(),
            FalProviderSettings(apiKey = "key", transcriptionPollIntervalMillis = 0),
        )

        val result = provider.transcription("wizper").transcribe(
            TranscriptionParams(
                audio = AudioSource("audio/wav", "YXVkaW8=", "clip.wav"),
                language = "pt",
                providerOptions = ProviderOptions.Raw(JsonObject(mapOf(
                    "fal" to buildJsonObject {
                        put("diarize", JsonPrimitive(false))
                        put("chunkLevel", JsonPrimitive("segment"))
                        put("batchSize", JsonPrimitive(16))
                        put("numSpeakers", JsonPrimitive(2))
                    },
                ))),
                headers = mapOf("X-Request" to "request"),
            ),
        )

        assertEquals("fal.transcription", provider.transcriptionModel("wizper").provider)
        assertEquals("Hello from the Vercel AI SDK.", result.text)
        assertEquals("Hello", result.segments.first().text)
        assertEquals(0.5f, result.segments.first().endSeconds)
        assertEquals("wizper", result.response.modelId)

        val create = fixture.calls.first()
        assertEquals("Key key", create.requestHeaders.headerValue(HttpHeaders.Authorization))
        assertEquals("request", create.requestHeaders.headerValue("X-Request"))
        val body = create.requestBodyJson.jsonObject
        assertEquals("transcribe", body["task"]?.jsonPrimitive?.contentOrNull)
        assertEquals(false, body["diarize"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("segment", body["chunk_level"]?.jsonPrimitive?.contentOrNull)
        assertEquals("pt", body["language"]?.jsonPrimitive?.contentOrNull)
        assertEquals(16, body["batch_size"]?.jsonPrimitive?.intOrNull)
        assertEquals(2, body["num_speakers"]?.jsonPrimitive?.intOrNull)
        assertEquals("data:audio/wav;base64,YXVkaW8=", body["audio_url"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `video model queues normalized model id polls result and returns url video`() = runTest {
        val fixture = createTestServer(
            mutableMapOf(
                "https://queue.fal.run/fal-ai/luma-dream-machine" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """{"request_id":"video-1","response_url":"https://queue.fal.run/fal-ai/luma-dream-machine/requests/video-1"}""",
                        ),
                    ),
                ),
                "https://queue.fal.run/fal-ai/luma-dream-machine/requests/video-1" to UrlHandler { options ->
                    if (options.callNumber == 1) {
                        UrlResponse.Error(
                            status = 400,
                            body = """{"detail":"Request is still in progress"}""",
                            headers = mapOf(HttpHeaders.ContentType to "application/json"),
                        )
                    } else {
                        UrlResponse.JsonValue(
                            Json.parseToJsonElement(
                                """
                                {
                                  "video":{
                                    "url":"https://fal.media/files/video.mp4",
                                    "width":1920,
                                    "height":1080,
                                    "duration":5.0,
                                    "fps":24,
                                    "content_type":"video/mp4"
                                  },
                                  "seed":42,
                                  "timings":{"inference":8.5},
                                  "has_nsfw_concepts":[false],
                                  "prompt":"Enhanced prompt"
                                }
                                """.trimIndent(),
                            ),
                        )
                    }
                },
            ),
        )
        fixture.server.start()
        val provider = Fal(
            fixture.httpClient(),
            FalProviderSettings(apiKey = "key", videoPollIntervalMillis = 10),
        )

        val result = provider.video("fal-ai/luma-dream-machine").generate(
            VideoGenerationParams(
                prompt = "A futuristic city",
                image = GeneratedFile("image/png", "aW1hZ2U="),
                durationSeconds = 5f,
                aspectRatio = "16:9",
                seed = 42,
                providerOptions = ProviderOptions.Raw(JsonObject(mapOf(
                    "fal" to buildJsonObject {
                        put("pollIntervalMs", JsonPrimitive(1))
                        put("pollTimeoutMs", JsonPrimitive(2))
                        put("motionStrength", JsonPrimitive(0.7f))
                        put("negativePrompt", JsonPrimitive("blur"))
                        put("promptOptimizer", JsonPrimitive(true))
                        put("customFlag", JsonPrimitive("custom"))
                    },
                ))),
                headers = mapOf("X-Request" to "request"),
            ),
        )

        assertEquals("fal.video", provider.videoModel("fal-ai/luma-dream-machine").provider)
        assertEquals(1, provider.videoModel("fal-ai/luma-dream-machine").maxVideosPerCall)
        assertEquals("video/mp4", result.videos.single().mediaType)
        assertEquals("", result.videos.single().base64)
        assertEquals("https://fal.media/files/video.mp4", result.videos.single().url)
        assertEquals(42, result.providerMetadata["fal"]?.jsonObject?.get("seed")?.jsonPrimitive?.intOrNull)
        assertEquals("Enhanced prompt", result.providerMetadata["fal"]?.jsonObject?.get("prompt")?.jsonPrimitive?.contentOrNull)
        assertEquals(1920, result.providerMetadata["fal"]?.jsonObject?.get("videos")?.jsonArray?.single()?.jsonObject?.get("width")?.jsonPrimitive?.intOrNull)

        val request = fixture.calls.first()
        assertEquals("https://queue.fal.run/fal-ai/luma-dream-machine", request.requestUrl)
        assertEquals("Key key", request.requestHeaders.headerValue(HttpHeaders.Authorization))
        val body = request.requestBodyJson.jsonObject
        assertEquals("A futuristic city", body["prompt"]?.jsonPrimitive?.contentOrNull)
        assertEquals("data:image/png;base64,aW1hZ2U=", body["image_url"]?.jsonPrimitive?.contentOrNull)
        assertEquals("16:9", body["aspect_ratio"]?.jsonPrimitive?.contentOrNull)
        assertEquals("5s", body["duration"]?.jsonPrimitive?.contentOrNull)
        assertEquals(42, body["seed"]?.jsonPrimitive?.intOrNull)
        assertEquals(0.7f, body["motion_strength"]?.jsonPrimitive?.floatOrNull)
        assertEquals("blur", body["negative_prompt"]?.jsonPrimitive?.contentOrNull)
        assertEquals(true, body["prompt_optimizer"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("custom", body["customFlag"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `unsupported language and embedding models throw no such model errors`() {
        val provider = Fal(createTestServer(mutableMapOf()).httpClient())

        assertFailsWith<NoSuchModelError> { provider.languageModel("model") }
        assertFailsWith<NoSuchModelError> { provider.embeddingModel("model") }
        assertFailsWith<NoSuchModelError> { provider.textEmbeddingModel("model") }
    }

    private fun Map<String, String>.headerValue(name: String): String? =
        entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}
