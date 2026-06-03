package ai.torad.aisdk

import io.ktor.http.HttpHeaders
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProdiaProviderTest {
    @Test
    fun `image model sends Prodia job request and parses multipart image`() = runTest {
        val fixture = createTestServer(
            mutableMapOf(
                "https://prodia.test/v2/job?price=true" to UrlHandler(
                    prodiaMultipartResponse(
                        jobJson = """{"id":"job-img","config":{"seed":9},"metrics":{"elapsed":1.5,"ips":2.5},"price":{"product":"image","dollars":0.01}}""",
                        outputMediaType = "image/png",
                        outputBytes = byteArrayOf(1, 2, 3),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val model = createProdia(
            fixture.httpClient(),
            ProdiaProviderSettings(
                apiKey = "token",
                baseURL = "https://prodia.test/v2",
                headers = mapOf("X-Provider" to "provider"),
            ),
        ).image("sdxl")

        val result = model.generate(
            ImageGenerationParams(
                prompt = "a glass city",
                n = 2,
                size = "512x768",
                seed = 9,
                providerOptions = mapOf(
                    "prodia" to buildJsonObject {
                        put("width", JsonPrimitive(640))
                        put("steps", JsonPrimitive(4))
                        put("stylePreset", JsonPrimitive("cinematic"))
                        put("loras", buildJsonArray {
                            add(JsonPrimitive("detail-lora"))
                        })
                        put("progressive", JsonPrimitive(true))
                    },
                ),
                headers = mapOf("X-Request" to "request"),
            ),
        )

        assertEquals("prodia.image", model.provider)
        assertEquals("image/png", result.images.single().mediaType)
        assertEquals(convertByteArrayToBase64(byteArrayOf(1, 2, 3)), result.images.single().base64)
        assertTrue(result.warnings.single().message.orEmpty().contains("one image"))
        val metadata = result.providerMetadata["prodia"]?.jsonObject?.get("images")?.jsonArray?.single()?.jsonObject
        assertEquals("job-img", metadata?.get("jobId")?.jsonPrimitive?.contentOrNull)
        assertEquals(9, metadata?.get("seed")?.jsonPrimitive?.intOrNull)
        assertEquals(1.5, metadata?.get("elapsed")?.jsonPrimitive?.doubleOrNull)
        assertEquals(2.5, metadata?.get("iterationsPerSecond")?.jsonPrimitive?.doubleOrNull)

        val request = fixture.calls.single()
        assertEquals("POST", request.requestMethod)
        assertEquals("https://prodia.test/v2/job?price=true", request.requestUrl)
        assertEquals("Bearer token", request.requestHeaders.headerValue(HttpHeaders.Authorization))
        assertEquals("provider", request.requestHeaders.headerValue("X-Provider"))
        assertEquals("request", request.requestHeaders.headerValue("X-Request"))
        assertTrue(request.requestHeaders.headerValue(HttpHeaders.Accept).orEmpty().contains("multipart/form-data; image/png"))
        assertTrue(request.requestUserAgent.orEmpty().contains("ai-sdk/prodia/$PRODIA_VERSION"))
        val body = request.requestBodyJson.jsonObject
        val config = body["config"]?.jsonObject
        assertEquals("sdxl", body["type"]?.jsonPrimitive?.contentOrNull)
        assertEquals("a glass city", config?.get("prompt")?.jsonPrimitive?.contentOrNull)
        assertEquals(640, config?.get("width")?.jsonPrimitive?.intOrNull)
        assertEquals(768, config?.get("height")?.jsonPrimitive?.intOrNull)
        assertEquals(9, config?.get("seed")?.jsonPrimitive?.intOrNull)
        assertEquals(4, config?.get("steps")?.jsonPrimitive?.intOrNull)
        assertEquals("cinematic", config?.get("style_preset")?.jsonPrimitive?.contentOrNull)
        assertEquals("detail-lora", config?.get("loras")?.jsonArray?.single()?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `language model sends multipart job and maps text plus file output`() = runTest {
        val fixture = createTestServer(
            mutableMapOf(
                "https://prodia.test/v2/job?price=true" to UrlHandler(
                    prodiaMultipartResponse(
                        jobJson = """{"id":"job-lang","created_at":"2026-01-01T00:00:00Z","config":{"seed":123}}""",
                        outputs = listOf(
                            ProdiaOutputPart("text/plain", "answer text".encodeToByteArray(), "answer.txt"),
                            ProdiaOutputPart("image/png", byteArrayOf(4, 5), "image.png"),
                        ),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val model = createProdia(
            fixture.httpClient(),
            ProdiaProviderSettings(apiKey = "token", baseURL = "https://prodia.test/v2"),
        ).languageModel("stabilityai/sdxl")

        val result = model.generate(
            LanguageModelCallParams(
                messages = listOf(
                    systemMessage("System line."),
                    ModelMessage(
                        MessageRole.User,
                        listOf(
                            ContentPart.Text("Draw this."),
                            ContentPart.Image("image/png", "iVBORw0="),
                        ),
                    ),
                ),
                temperature = 0.4f,
                tools = listOf(LanguageModelTool("ignored", "ignored", """{"type":"object"}""")),
                responseFormat = ResponseFormat.Json(),
                providerOptions = mapOf("prodia" to buildJsonObject { put("aspectRatio", JsonPrimitive("16:9")) }),
                headers = mapOf("X-Request" to "request"),
            ),
        )

        assertEquals("prodia.language", model.provider)
        assertEquals("answer text", result.text)
        assertEquals(FinishReason.Stop, result.finishReason)
        assertEquals("image/png", result.content.filterIsInstance<ContentPart.File>().single().mediaType)
        assertEquals(convertByteArrayToBase64(byteArrayOf(4, 5)), result.content.filterIsInstance<ContentPart.File>().single().base64)
        assertTrue(result.warnings.any { it.message.orEmpty().contains("temperature") })
        assertTrue(result.warnings.any { it.message.orEmpty().contains("tools") })
        assertTrue(result.warnings.any { it.message.orEmpty().contains("responseFormat") })
        assertEquals("job-lang", result.providerMetadata["prodia"]?.jsonObject?.get("jobId")?.jsonPrimitive?.contentOrNull)

        val request = fixture.calls.single()
        assertEquals("request", request.requestHeaders.headerValue("X-Request"))
        val multipart = request.requestBodyMultipart
        assertNotNull(multipart)
        val job = Json.parseToJsonElement(multipart["job"].orEmpty()).jsonObject
        val config = job["config"]?.jsonObject
        assertEquals("stabilityai/sdxl", job["type"]?.jsonPrimitive?.contentOrNull)
        assertEquals("System line.\nDraw this.", config?.get("prompt")?.jsonPrimitive?.contentOrNull)
        assertEquals("16:9", config?.get("aspect_ratio")?.jsonPrimitive?.contentOrNull)
        assertEquals("true", config?.get("include_messages")?.jsonPrimitive?.contentOrNull)
        assertTrue(multipart.containsKey("input"))
    }

    @Test
    fun `video model supports text and image job request paths`() = runTest {
        val fixture = createTestServer(
            mutableMapOf(
                "https://prodia.test/v2/job?price=true" to UrlHandler(
                    listOf(
                        prodiaMultipartResponse(
                            jobJson = """{"id":"job-video-json","metrics":{"elapsed":2.0}}""",
                            outputMediaType = "video/mp4",
                            outputBytes = byteArrayOf(8, 9),
                        ),
                        prodiaMultipartResponse(
                            jobJson = """{"id":"job-video-multipart","metrics":{"elapsed":3.0}}""",
                            outputMediaType = "video/mp4",
                            outputBytes = byteArrayOf(10, 11),
                        ),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val model = createProdia(
            fixture.httpClient(),
            ProdiaProviderSettings(apiKey = "token", baseURL = "https://prodia.test/v2"),
        ).video("minimax/video")

        val textResult = model.generate(
            VideoGenerationParams(
                prompt = "camera pan",
                seed = 77,
                providerOptions = mapOf("prodia" to buildJsonObject { put("resolution", JsonPrimitive("720p")) }),
            ),
        )
        val imageResult = model.generate(
            VideoGenerationParams(
                prompt = "animate frame",
                image = GeneratedFile(mediaType = "image/png", base64 = convertByteArrayToBase64(byteArrayOf(1, 2))),
                resolution = "480p",
            ),
        )

        assertEquals("prodia.video", model.provider)
        assertEquals(convertByteArrayToBase64(byteArrayOf(8, 9)), textResult.videos.single().base64)
        assertEquals("job-video-json", textResult.providerMetadata["prodia"]?.jsonObject?.get("videos")?.jsonArray?.single()?.jsonObject?.get("jobId")?.jsonPrimitive?.contentOrNull)
        assertEquals(convertByteArrayToBase64(byteArrayOf(10, 11)), imageResult.videos.single().base64)
        assertEquals("job-video-multipart", imageResult.providerMetadata["prodia"]?.jsonObject?.get("videos")?.jsonArray?.single()?.jsonObject?.get("jobId")?.jsonPrimitive?.contentOrNull)

        val jsonBody = fixture.calls[0].requestBodyJson.jsonObject
        val jsonConfig = jsonBody["config"]?.jsonObject
        assertEquals("camera pan", jsonConfig?.get("prompt")?.jsonPrimitive?.contentOrNull)
        assertEquals(77, jsonConfig?.get("seed")?.jsonPrimitive?.intOrNull)
        assertEquals("720p", jsonConfig?.get("resolution")?.jsonPrimitive?.contentOrNull)
        assertEquals("multipart/form-data; video/mp4", fixture.calls[0].requestHeaders.headerValue(HttpHeaders.Accept))

        val multipart = fixture.calls[1].requestBodyMultipart
        assertNotNull(multipart)
        val imageJob = Json.parseToJsonElement(multipart["job"].orEmpty()).jsonObject
        assertEquals("480p", imageJob["config"]?.jsonObject?.get("resolution")?.jsonPrimitive?.contentOrNull)
        assertTrue(multipart.containsKey("input"))
    }

    @Test
    fun `unsupported Prodia surfaces and unconfigured singleton fail explicitly`() {
        val provider = createProdia(createTestServer(mutableMapOf()).httpClient(), ProdiaProviderSettings(apiKey = "token"))

        assertFailsWith<NoSuchModelError> { provider.embeddingModel("embed") }
        assertFailsWith<NoSuchModelError> { provider.textEmbeddingModel("embed") }
        assertTrue(assertFailsWith<AiSdkException> { prodia.languageModel("model") }.message.orEmpty().contains("createProdia"))
        assertTrue(assertFailsWith<AiSdkException> { prodia.image("model") }.message.orEmpty().contains("createProdia"))
        assertTrue(assertFailsWith<AiSdkException> { prodia.video("model") }.message.orEmpty().contains("createProdia"))
    }

    private data class ProdiaOutputPart(
        val mediaType: String,
        val bytes: ByteArray,
        val filename: String,
    )

    private fun prodiaMultipartResponse(
        jobJson: String,
        outputMediaType: String,
        outputBytes: ByteArray,
    ): UrlResponse.Binary = prodiaMultipartResponse(
        jobJson = jobJson,
        outputs = listOf(ProdiaOutputPart(outputMediaType, outputBytes, if (outputMediaType.startsWith("video/")) "output.mp4" else "output.png")),
    )

    private fun prodiaMultipartResponse(
        jobJson: String,
        outputs: List<ProdiaOutputPart>,
    ): UrlResponse.Binary {
        val boundary = "prodia-test-boundary"
        var body = byteArrayOf()
        body += "--$boundary\r\nContent-Disposition: form-data; name=\"job\"; filename=\"job.json\"\r\nContent-Type: application/json\r\n\r\n$jobJson\r\n".encodeToByteArray()
        outputs.forEach { output ->
            body += "--$boundary\r\nContent-Disposition: form-data; name=\"output\"; filename=\"${output.filename}\"\r\nContent-Type: ${output.mediaType}\r\n\r\n".encodeToByteArray()
            body += output.bytes
            body += "\r\n".encodeToByteArray()
        }
        body += "--$boundary--\r\n".encodeToByteArray()
        return UrlResponse.Binary(body, headers = mapOf(HttpHeaders.ContentType to "multipart/form-data; boundary=$boundary"))
    }

    private fun Map<String, String>.headerValue(name: String): String? =
        entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}
