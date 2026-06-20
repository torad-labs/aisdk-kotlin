package ai.torad.aisdk
import ai.torad.aisdk.providers.QUIVERAI_VERSION
import ai.torad.aisdk.providers.QuiverAIProviderSettings

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
import ai.torad.aisdk.providers.QuiverAI

class QuiverAIProviderTest {
    @Test
    @Suppress("LongMethod")
    fun `generate creates svg images with references options auth and metadata`() = runTest {
        val fixture = createTestServer(
            mutableMapOf(
                "https://api.quiver.ai/v1/svgs/generations" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """{"id":"svg_1","created":1710000000,"data":[{"svg":"<svg><rect/></svg>","mime_type":"image/svg+xml"}],"usage":{"input_tokens":12,"output_tokens":9,"total_tokens":21}}""",
                        ),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val model = QuiverAI(
            fixture.httpClient(),
            QuiverAIProviderSettings(apiKey = "key", headers = mapOf("X-Test" to "1")),
        ).image("arrow-1")

        val result = model.generate(
            ImageGenerationParams(
                prompt = "Draw a square icon.",
                files = listOf(
                    ImageGenerationFile(url = "https://example.com/ref.png"),
                    ImageGenerationFile(mediaType = "image/png", base64 = "BAUG"),
                ),
                providerOptions = mapOf(
                    "quiverai" to buildJsonObject {
                        put("instructions", JsonPrimitive("Use clean geometry."))
                        put("temperature", JsonPrimitive(0.4))
                        put("topP", JsonPrimitive(0.95))
                        put("presencePenalty", JsonPrimitive(0.2))
                        put("maxOutputTokens", JsonPrimitive(4096))
                    },
                ),
            ),
        )

        assertEquals("quiverai.image", model.provider)
        assertEquals(16, model.maxImagesPerCall)
        assertEquals("image/svg+xml", result.images.single().mediaType)
        assertEquals("<svg><rect/></svg>", Base64Codec.decode(result.images.single().base64).decodeToString())
        assertEquals("svg_1", result.response.id)
        assertEquals(1710000000000, result.response.timestampMillis)

        val call = fixture.calls.single()
        assertEquals("POST", call.requestMethod)
        assertEquals("Bearer key", call.requestHeaders.headerValue(HttpHeaders.Authorization))
        assertEquals("1", call.requestHeaders.headerValue("X-Test"))
        assertTrue(call.requestUserAgent.orEmpty().contains("ai-sdk/quiverai/$QUIVERAI_VERSION"))
        val body = call.requestBodyJson.jsonObject
        assertEquals("arrow-1", body["model"]?.jsonPrimitive?.contentOrNull)
        assertEquals("Draw a square icon.", body["prompt"]?.jsonPrimitive?.contentOrNull)
        assertEquals("Use clean geometry.", body["instructions"]?.jsonPrimitive?.contentOrNull)
        assertEquals(0.4, body["temperature"]?.jsonPrimitive?.doubleOrNull)
        assertEquals(0.95, body["top_p"]?.jsonPrimitive?.doubleOrNull)
        assertEquals(0.2, body["presence_penalty"]?.jsonPrimitive?.doubleOrNull)
        assertEquals(4096, body["max_output_tokens"]?.jsonPrimitive?.intOrNull)
        assertEquals("https://example.com/ref.png", body["references"]?.jsonArray?.first()?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull)
        assertEquals("BAUG", body["references"]?.jsonArray?.get(1)?.jsonObject?.get("base64")?.jsonPrimitive?.contentOrNull)

        val metadata = result.providerMetadata["quiverai"]?.jsonObject
        assertEquals("image/svg+xml", metadata?.get("images")?.jsonArray?.single()?.jsonObject?.get("mimeType")?.jsonPrimitive?.contentOrNull)
        assertEquals(21, metadata?.get("usage")?.jsonObject?.get("total_tokens")?.jsonPrimitive?.intOrNull)
        assertEquals(ImageModelUsage(inputTokens = 12, outputTokens = 9, totalTokens = 21), result.usage)
    }

    @Test
    fun `vectorize posts one input image with vectorize options`() = runTest {
        val fixture = createTestServer(
            mutableMapOf(
                "https://api.quiver.ai/v1/svgs/vectorizations" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """{"id":"vec_1","created":1710000001,"data":[{"svg":"<svg><path/></svg>","mime_type":"image/svg+xml"}]}""",
                        ),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val model = QuiverAI(fixture.httpClient(), QuiverAIProviderSettings(apiKey = "key")).image("arrow-1")

        model.generate(
            ImageGenerationParams(
                prompt = "",
                n = 2,
                files = listOf(ImageGenerationFile(url = "https://example.com/logo.png")),
                providerOptions = mapOf(
                    "quiverai" to buildJsonObject {
                        put("operation", JsonPrimitive("vectorize"))
                        put("temperature", JsonPrimitive(0.3))
                        put("topP", JsonPrimitive(0.9))
                        put("autoCrop", JsonPrimitive(true))
                        put("targetSize", JsonPrimitive(1024))
                    },
                ),
            ),
        )

        val body = fixture.calls.single().requestBodyJson.jsonObject
        assertEquals("arrow-1", body["model"]?.jsonPrimitive?.contentOrNull)
        assertEquals(2, body["n"]?.jsonPrimitive?.intOrNull)
        assertEquals("https://example.com/logo.png", body["image"]?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull)
        assertEquals(0.3, body["temperature"]?.jsonPrimitive?.doubleOrNull)
        assertEquals(0.9, body["top_p"]?.jsonPrimitive?.doubleOrNull)
        assertEquals(true, body["auto_crop"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull())
        assertEquals(1024, body["target_size"]?.jsonPrimitive?.intOrNull)
    }

    @Test
    fun `validates reference limits vectorize inputs and unsupported options`() = runTest {
        val fixture = createTestServer(
            mutableMapOf(
                "https://api.quiver.ai/v1/svgs/generations" to UrlHandler(
                    UrlResponse.JsonValue(Json.parseToJsonElement("""{"id":"svg","created":1,"data":[{"svg":"<svg/>","mime_type":"image/svg+xml"}]}""")),
                ),
            ),
        )
        fixture.server.start()
        val provider = QuiverAI(fixture.httpClient(), QuiverAIProviderSettings(apiKey = "key"))

        assertFailsWith<InvalidArgumentError> {
            provider.image("arrow-1").generate(
                ImageGenerationParams(
                    prompt = "x",
                    files = List(5) { ImageGenerationFile(url = "https://example.com/$it.png") },
                ),
            )
        }
        assertFailsWith<InvalidArgumentError> {
            provider.image("arrow-1").generate(
                ImageGenerationParams(
                    prompt = "",
                    providerOptions = mapOf("quiverai" to buildJsonObject { put("operation", JsonPrimitive("vectorize")) }),
                ),
            )
        }
        assertFailsWith<InvalidArgumentError> {
            provider.image("arrow-1").generate(
                ImageGenerationParams(
                    prompt = "",
                    files = listOf(ImageGenerationFile(url = "a"), ImageGenerationFile(url = "b")),
                    providerOptions = mapOf("quiverai" to buildJsonObject { put("operation", JsonPrimitive("vectorize")) }),
                ),
            )
        }

        val result = provider.image("arrow-1.1-max").generate(
            ImageGenerationParams(
                prompt = "x",
                files = List(16) { ImageGenerationFile(url = "https://example.com/$it.png") },
                size = "1024x1024",
                aspectRatio = "1:1",
                seed = 42,
                mask = ImageGenerationFile(url = "https://example.com/mask.png"),
            ),
        )

        assertEquals(4, result.warnings.size)
        assertTrue(result.warnings.any { it.message.orEmpty().contains("size") })
        assertTrue(result.warnings.any { it.message.orEmpty().contains("aspectRatio") })
        assertTrue(result.warnings.any { it.message.orEmpty().contains("seed") })
        assertTrue(result.warnings.any { it.message.orEmpty().contains("masks") })
    }

    @Test
    fun `default provider and unsupported model families fail explicitly`() {
        val fixture = createTestServer(mutableMapOf())
        val provider = QuiverAI(fixture.httpClient(), QuiverAIProviderSettings(apiKey = "key"))

        assertFailsWith<NoSuchModelError> { provider.languageModel("model") }
        assertFailsWith<NoSuchModelError> { provider.embeddingModel("embed") }
    }

    private fun Map<String, String>.headerValue(name: String): String? =
        entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}
