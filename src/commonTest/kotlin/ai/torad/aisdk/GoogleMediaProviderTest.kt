@file:OptIn(LowLevelLanguageModelApi::class)

package ai.torad.aisdk
import ai.torad.aisdk.providers.GOOGLE_VERSION
import ai.torad.aisdk.providers.GoogleGenerativeAIProviderSettings
import ai.torad.aisdk.providers.GoogleGenerativeAI

import ai.torad.aisdk.testing.FlowDrain.drainAllItems
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

@Suppress("LargeClass")
class GoogleMediaProviderTest {
    @Test
    fun `embedding image and video models map Google payloads`() = runTest {
        var videoPolls = 0
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://google.test/v1beta/models/text-embedding-004:batchEmbedContents" to UrlHandler(
                    UrlResponse.JsonValue(Json.parseToJsonElement("""{"embeddings":[{"values":[1.0,2.0]},{"values":[3.0,4.0]}]}""")),
                ),
                "https://google.test/v1beta/models/imagen-4.0-generate-001:predict" to UrlHandler(
                    UrlResponse.JsonValue(Json.parseToJsonElement("""{"predictions":[{"bytesBase64Encoded":"aW1hZ2U="}]}""")),
                ),
                "https://google.test/v1beta/models/veo-3.1-generate-preview:predictLongRunning" to UrlHandler(
                    UrlResponse.JsonValue(Json.parseToJsonElement("""{"name":"operations/video-1","done":false}""")),
                ),
                "https://google.test/v1beta/operations/video-1" to UrlHandler(
                    {
                        if (videoPolls++ == 0) {
                            UrlResponse.Error(status = 503, body = """{"error":{"message":"temporarily unavailable"}}""")
                        } else {
                            UrlResponse.JsonValue(
                                Json.parseToJsonElement(
                                    """
                                    {
                                      "name":"operations/video-1",
                                      "done":true,
                                      "response":{
                                        "generateVideoResponse":{
                                          "generatedSamples":[{"video":{"uri":"https://videos.example/video.mp4"}}]
                                        }
                                      }
                                    }
                                    """.trimIndent(),
                                ),
                            )
                        }
                    },
                ),
            ),
        )
        fixture.server.start()
        val provider = GoogleGenerativeAI(
            fixture.httpClient(),
            GoogleGenerativeAIProviderSettings {
                apiKey("key")
                baseURL("https://google.test/v1beta")
                videoPollIntervalMillis(0)
            },
        )

        val embeddings = provider.embedding(ModelId("text-embedding-004")).embed(
            EmbeddingModelCallParams {
                values(listOf("one", "two"))
                providerOptions(ProviderOptions.Raw(JsonObject(mapOf(
                    "google" to buildJsonObject {
                        put("taskType", JsonPrimitive("RETRIEVAL_QUERY"))
                        put("outputDimensionality", JsonPrimitive(256))
                    },
                ))))
            },
        )
        val image = provider.image(ModelId("imagen-4.0-generate-001")).generate(
            ImageGenerationParams {
                prompt("A product render")
                n(1)
                aspectRatio("16:9")
                providerOptions(ProviderOptions.Raw(JsonObject(mapOf("google" to buildJsonObject { put("personGeneration", JsonPrimitive("dont_allow")) }))))
            },
        )
        val video = provider.video(ModelId("veo-3.1-generate-preview")).generate(
            VideoGenerationParams {
                prompt("A clean motion shot")
                n(1)
                aspectRatio("16:9")
                durationSeconds(4f)
                resolution("1920x1080")
                providerOptions(ProviderOptions.Raw(JsonObject(mapOf("google" to buildJsonObject { put("negativePrompt", JsonPrimitive("blur")) }))))
            },
        )

        assertEquals(listOf(1.0f, 2.0f), embeddings.embeddings.first())
        assertEquals(2048, provider.embedding(ModelId("text-embedding-004")).maxEmbeddingsPerCall)
        assertEquals(true, provider.embedding(ModelId("text-embedding-004")).supportsParallelCalls)
        assertEquals("image", Base64Codec.decode(image.images.single().base64).decodeToString())
        val generatedVideo = video.videos.single()
        assertEquals("https://videos.example/video.mp4", generatedVideo.url)
        val googleMetadata = generatedVideo.providerMetadata.toMap()["google"]?.jsonObject ?: error("missing google metadata")
        assertEquals("https://videos.example/video.mp4", googleMetadata["uri"]!!.jsonPrimitive.content)
        assertEquals(true, googleMetadata["requiresApiKey"]!!.jsonPrimitive.booleanOrNull)

        val embeddingBody = fixture.calls[0].requestBodyJson.jsonObject
        assertEquals(2, embeddingBody["requests"]?.jsonArray?.size)
        assertEquals(256, embeddingBody["requests"]?.jsonArray?.first()?.jsonObject?.get("outputDimensionality")?.jsonPrimitive?.intOrNull)
        val imageBody = fixture.calls[1].requestBodyJson.jsonObject
        assertEquals("A product render", imageBody["instances"]?.jsonArray?.single()?.jsonObject?.get("prompt")?.jsonPrimitive?.contentOrNull)
        assertEquals("16:9", imageBody["parameters"]?.jsonObject?.get("aspectRatio")?.jsonPrimitive?.contentOrNull)
        val videoBody = fixture.calls[2].requestBodyJson.jsonObject
        assertEquals("1080p", videoBody["parameters"]?.jsonObject?.get("resolution")?.jsonPrimitive?.contentOrNull)
        assertEquals(4f, videoBody["parameters"]?.jsonObject?.get("durationSeconds")?.jsonPrimitive?.floatOrNull)
        assertEquals("GET", fixture.calls[3].requestMethod)
        assertEquals("GET", fixture.calls[4].requestMethod)
    }

    @Test
    fun `image and video models reject malformed Google media wire data`() = runTest {
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://google.test/v1beta/models/imagen-4.0-generate-001:predict" to UrlHandler(
                    UrlResponse.JsonValue(Json.parseToJsonElement("""{"predictions":[{}]}""")),
                ),
                "https://google.test/v1beta/models/veo-3.1-generate-preview:predictLongRunning" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """
                            {
                              "name":"operations/video-1",
                              "done":true,
                              "response":{
                                "generateVideoResponse":{
                                  "generatedSamples":[{"video":{}}]
                                }
                              }
                            }
                            """.trimIndent(),
                        ),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val provider = GoogleGenerativeAI(
            fixture.httpClient(),
            GoogleGenerativeAIProviderSettings {
                apiKey("key")
                baseURL("https://google.test/v1beta")
                videoPollIntervalMillis(0)
            },
        )

        val imageError = assertFailsWith<WireDecodeException> {
            provider.image(ModelId("imagen-4.0-generate-001")).generate(ImageGenerationParams {
                prompt("x")
            })
        }
        val videoError = assertFailsWith<WireDecodeException> {
            provider.video(ModelId("veo-3.1-generate-preview")).generate(VideoGenerationParams {
                prompt("x")
            })
        }

        assertTrue(imageError.message.orEmpty().contains("bytesBase64Encoded"))
        assertTrue(videoError.message.orEmpty().contains("video.uri"))
    }

}
