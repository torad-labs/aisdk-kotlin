package ai.torad.aisdk
import ai.torad.aisdk.providers.BLACK_FOREST_LABS_VERSION
import ai.torad.aisdk.providers.BlackForestLabsProviderSettings
import ai.torad.aisdk.providers.blackForestLabs
import ai.torad.aisdk.providers.BlackForestLabs

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
import kotlinx.serialization.json.JsonObject

class BlackForestLabsProviderTest {
    @Test
    fun `image model submits polls downloads image and records metadata`() = runTest {
        val fixture = createTestServer(
            mutableMapOf(
                "https://api.bfl.ai/v1/flux-pro-1.1" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """{"id":"req1","polling_url":"https://api.bfl.ai/v1/get_result","cost":0.02,"input_mp":1.2,"output_mp":0.8}""",
                        ),
                    ),
                ),
                "https://api.bfl.ai/v1/get_result?id=req1" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """{"status":"Ready","result":{"sample":"https://cdn.example/out.png","seed":123,"start_time":10,"end_time":12,"duration":2.5}}""",
                        ),
                    ),
                ),
                "https://cdn.example/out.png" to UrlHandler(
                    UrlResponse.Binary(byteArrayOf(4, 5, 6), headers = mapOf(HttpHeaders.ContentType to "image/jpeg")),
                ),
            ),
        )
        fixture.server.start()
        val model = BlackForestLabs(
            fixture.httpClient(),
            BlackForestLabsProviderSettings(apiKey = "bfl-key"),
        ).image("flux-pro-1.1")

        val result = model.generate(
            ImageGenerationParams(
                prompt = "a detailed city model",
                size = "1024x512",
                seed = 7,
                files = listOf(
                    ImageGenerationFile(url = "https://example.com/ref.png"),
                    ImageGenerationFile(mediaType = "image/png", base64 = "abc123"),
                ),
                mask = ImageGenerationFile(mediaType = "image/png", base64 = "mask123"),
                providerOptions = ProviderOptions.Raw(JsonObject(mapOf(
                    "blackForestLabs" to buildJsonObject {
                        put("steps", JsonPrimitive(4))
                        put("guidance", JsonPrimitive(2.5))
                        put("imagePromptStrength", JsonPrimitive(0.7))
                        put("imagePrompt", JsonPrimitive("prompt-image"))
                        put("outputFormat", JsonPrimitive("jpeg"))
                        put("promptUpsampling", JsonPrimitive(true))
                        put("raw", JsonPrimitive(false))
                        put("safetyTolerance", JsonPrimitive(3))
                        put("webhookSecret", JsonPrimitive("secret"))
                        put("webhookUrl", JsonPrimitive("https://hooks.example/bfl"))
                        put("pollIntervalMillis", JsonPrimitive(1))
                        put("pollTimeoutMillis", JsonPrimitive(1))
                    },
                ))),
            ),
        )

        assertEquals("black-forest-labs.image", model.provider)
        assertEquals(1, model.maxImagesPerCall)
        assertEquals("image/jpeg", result.images.single().mediaType)
        assertEquals(Base64Codec.encode(byteArrayOf(4, 5, 6)), result.images.single().base64)
        assertEquals(1, result.warnings.size)
        assertTrue(result.warnings.single().message.orEmpty().contains("Deriving aspect_ratio"))

        val create = fixture.calls[0]
        assertEquals("POST", create.requestMethod)
        assertEquals("bfl-key", create.requestHeaders.headerValue("x-key"))
        assertTrue(create.requestUserAgent.orEmpty().contains("ai-sdk/black-forest-labs/$BLACK_FOREST_LABS_VERSION"))
        val body = create.requestBodyJson.jsonObject
        assertEquals("a detailed city model", body["prompt"]?.jsonPrimitive?.contentOrNull)
        assertEquals("2:1", body["aspect_ratio"]?.jsonPrimitive?.contentOrNull)
        assertEquals(7, body["seed"]?.jsonPrimitive?.intOrNull)
        assertEquals(1024, body["width"]?.jsonPrimitive?.intOrNull)
        assertEquals(512, body["height"]?.jsonPrimitive?.intOrNull)
        assertEquals("https://example.com/ref.png", body["input_image"]?.jsonPrimitive?.contentOrNull)
        assertEquals("abc123", body["input_image_2"]?.jsonPrimitive?.contentOrNull)
        assertEquals("mask123", body["mask"]?.jsonPrimitive?.contentOrNull)
        assertEquals(4, body["steps"]?.jsonPrimitive?.intOrNull)
        assertEquals(2.5, body["guidance"]?.jsonPrimitive?.doubleOrNull)
        assertEquals(0.7, body["image_prompt_strength"]?.jsonPrimitive?.doubleOrNull)
        assertEquals("prompt-image", body["image_prompt"]?.jsonPrimitive?.contentOrNull)
        assertEquals("jpeg", body["output_format"]?.jsonPrimitive?.contentOrNull)
        assertEquals(3, body["safety_tolerance"]?.jsonPrimitive?.intOrNull)
        assertEquals(null, body["pollIntervalMillis"])

        assertEquals("GET", fixture.calls[1].requestMethod)
        assertEquals("https://api.bfl.ai/v1/get_result?id=req1", fixture.calls[1].requestUrl)
        assertEquals("bfl-key", fixture.calls[2].requestHeaders.headerValue("x-key"))

        val providerMetadata = result.providerMetadata.toMap()["blackForestLabs"]?.jsonObject
        val imageMetadata = providerMetadata?.get("images")?.jsonArray?.single()?.jsonObject
        assertEquals(123, imageMetadata?.get("seed")?.jsonPrimitive?.intOrNull)
        assertEquals(2.5, imageMetadata?.get("duration")?.jsonPrimitive?.doubleOrNull)
        assertEquals(0.02, imageMetadata?.get("cost")?.jsonPrimitive?.doubleOrNull)
        assertEquals(1.2, imageMetadata?.get("inputMegapixels")?.jsonPrimitive?.doubleOrNull)
    }

    @Test
    fun `fill model uses image fields and aspect ratio overrides size`() = runTest {
        val fixture = createTestServer(
            mutableMapOf(
                "https://api.bfl.ai/v1/flux-pro-1.0-fill" to UrlHandler(
                    UrlResponse.JsonValue(Json.parseToJsonElement("""{"id":"req1","polling_url":"https://api.bfl.ai/v1/poll?id=req1"}""")),
                ),
                "https://api.bfl.ai/v1/poll?id=req1" to UrlHandler(
                    UrlResponse.JsonValue(Json.parseToJsonElement("""{"state":"Ready","result":{"sample":"https://cdn.example/fill.png"}}""")),
                ),
                "https://cdn.example/fill.png" to UrlHandler(UrlResponse.Binary(byteArrayOf(1))),
            ),
        )
        fixture.server.start()
        val model = BlackForestLabs(
            fixture.httpClient(),
            BlackForestLabsProviderSettings(apiKey = "key"),
        ).image("flux-pro-1.0-fill")

        val result = model.generate(
            ImageGenerationParams(
                prompt = "replace the sky",
                size = "1024x1024",
                aspectRatio = "16:9",
                files = listOf(
                    ImageGenerationFile(url = "https://example.com/input.png"),
                    ImageGenerationFile(url = "https://example.com/second.png"),
                ),
                providerOptions = ProviderOptions.Raw(JsonObject(mapOf(
                    "blackForestLabs" to buildJsonObject {
                        put("width", JsonPrimitive(1280))
                        put("height", JsonPrimitive(720))
                    },
                ))),
            ),
        )

        val body = fixture.calls.first().requestBodyJson.jsonObject
        assertEquals("16:9", body["aspect_ratio"]?.jsonPrimitive?.contentOrNull)
        assertEquals(1280, body["width"]?.jsonPrimitive?.intOrNull)
        assertEquals(720, body["height"]?.jsonPrimitive?.intOrNull)
        assertEquals("https://example.com/input.png", body["image"]?.jsonPrimitive?.contentOrNull)
        assertEquals("https://example.com/second.png", body["image_2"]?.jsonPrimitive?.contentOrNull)
        assertTrue(result.warnings.single().message.orEmpty().contains("ignores size"))
    }

    @Test
    fun `poll failure and input image limit fail explicitly`() = runTest {
        val fixture = createTestServer(
            mutableMapOf(
                "https://api.bfl.ai/v1/flux-pro-1.1" to UrlHandler(
                    UrlResponse.JsonValue(Json.parseToJsonElement("""{"id":"req1","polling_url":"https://api.bfl.ai/v1/poll"}""")),
                ),
                "https://api.bfl.ai/v1/poll?id=req1" to UrlHandler(
                    UrlResponse.JsonValue(Json.parseToJsonElement("""{"status":"Failed"}""")),
                ),
            ),
        )
        fixture.server.start()
        val model = BlackForestLabs(
            fixture.httpClient(),
            BlackForestLabsProviderSettings(apiKey = "key"),
        ).image("flux-pro-1.1")

        assertFailsWith<AiSdkException> {
            model.generate(
                ImageGenerationParams(
                    prompt = "x",
                    providerOptions = ProviderOptions.Raw(JsonObject(mapOf("blackForestLabs" to buildJsonObject {
                        put("pollIntervalMillis", JsonPrimitive(1))
                        put("pollTimeoutMillis", JsonPrimitive(1))
                    }))),
                ),
            )
        }

        assertFailsWith<AiSdkException> {
            model.generate(
                ImageGenerationParams(
                    prompt = "x",
                    files = List(11) { ImageGenerationFile(url = "https://example.com/$it.png") },
                ),
            )
        }
    }

    @Test
    fun `default provider and unsupported model families fail explicitly`() {
        val fixture = createTestServer(mutableMapOf())
        val provider = BlackForestLabs(fixture.httpClient(), BlackForestLabsProviderSettings(apiKey = "key"))

        assertFailsWith<NoSuchModelError> { provider.languageModel("model") }
        assertFailsWith<NoSuchModelError> { provider.embeddingModel("embed") }
        assertFailsWith<AiSdkException> { blackForestLabs.image("flux-pro-1.1") }
    }

    private fun Map<String, String>.headerValue(name: String): String? =
        entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}
