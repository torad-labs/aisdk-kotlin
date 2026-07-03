@file:OptIn(LowLevelLanguageModelApi::class)

package ai.torad.aisdk

import ai.torad.aisdk.testing.FlowDrain.drainAllItems
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalAiSdkApi::class)
class KtorGatewayTransportParityTest {
    @Test
    fun `KtorGatewayTransport posts language model requests and parses stream events`() = runTest {
        val seenPaths = mutableListOf<String>()
        val seenHeaders = mutableListOf<Map<String, List<String>>>()
        val client = HttpClient(
            MockEngine { request ->
                seenPaths += request.url.encodedPath
                seenHeaders += request.headers.entries().associate { it.key to it.value }
                when (request.url.encodedPath) {
                    "/v3/ai/language-model" -> {
                        if (request.headers["ai-language-model-streaming"] == "true") {
                            respond(
                                content = """
                                    data: {"type":"text-delta","id":"t1","delta":"hello"}

                                    data: {"type":"finish","totalSteps":1,"finishReason":"stop","usage":{"promptTokens":1,"completionTokens":1}}

                                """.trimIndent(),
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
                            )
                        } else {
                            respond(
                                content = """{"text":"hello","finishReason":"stop","usage":{"promptTokens":1,"completionTokens":1},"providerMetadata":{"gateway":{"id":"gen_1"}}}""",
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        }
                    }
                    else -> respond(
                        """{}""",
                        HttpStatusCode.NotFound,
                        headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
            },
        )
        val provider = CreateGatewayHttpProvider(
            client,
            GatewayProviderSettings {
                baseUrl("https://gateway.test/v3/ai");
                apiKey("secret")
            },
        )

        val generated = TextGenerator(
            provider.languageModel("gpt-test")
        ).generate(GenerationInput.Prompt("hi")).single()
        val streamed = drainAllItems(
            provider.languageModel("gpt-test").stream(
                LanguageModelCallParams {
                    messages(listOf(UserMessage("hi")))
                }
            )
        )

        assertEquals("hello", generated.text)
        assertEquals(JsonPrimitive("gen_1"), generated.providerMetadata.toMap()["gateway"]?.jsonObject?.get("id"))
        assertTrue(streamed.any { it is StreamEvent.TextDelta && it.text == "hello" })
        assertTrue(streamed.any { it is StreamEvent.Finish })
        assertEquals(listOf("/v3/ai/language-model", "/v3/ai/language-model"), seenPaths)
        assertEquals("Bearer secret", seenHeaders.first()["authorization"]?.single())
        assertEquals("gpt-test", seenHeaders.first()["ai-language-model-id"]?.single())
    }

    @Test
    fun `KtorGatewayTransport rejects malformed required stream event fields`() = runTest {
        val client = HttpClient(
            MockEngine { request ->
                when (request.url.encodedPath) {
                    "/v3/ai/language-model" -> respond(
                        content = """data: {"type":"text-delta","id":"t1"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
                    )
                    else -> respond(
                        """{}""",
                        HttpStatusCode.NotFound,
                        headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
            },
        )
        val provider = CreateGatewayHttpProvider(
            client,
            GatewayProviderSettings {
                baseUrl("https://gateway.test/v3/ai");
                apiKey("secret")
            },
        )

        val error = assertFailsWith<WireDecodeException> {
            drainAllItems(
                provider.languageModel("gpt-test").stream(
                    LanguageModelCallParams {
                        messages(listOf(UserMessage("hi")))
                    }
                )
            )
        }

        assertEquals("gateway", error.provider)
        assertEquals("stream event", error.operation)
    }

    @Test
    fun `KtorGatewayTransport fetches gateway metadata endpoints`() = runTest {
        val seenUrls = mutableListOf<String>()
        val client = HttpClient(
            MockEngine { request ->
                seenUrls += request.url.toString()
                val content = when (request.url.encodedPath) {
                    "/v3/ai/config" -> """{"models":[{"id":"m1","name":"Model 1","description":"test","pricing":{"input":"0.1","output":"0.2","input_cache_read":"0.01","input_cache_write":"0.02"},"specification":{"specificationVersion":"v3","provider":"openai","modelId":"m1"},"modelType":"language"}]}"""
                    "/v1/credits" -> """{"balance":"100","total_used":"12"}"""
                    "/v1/report" -> """{"results":[{"model":"m1","credential_type":"byok","total_cost":1.5,"request_count":2}]}"""
                    "/v1/generation" -> """{"data":{"id":"gen_1","total_cost":1.0,"upstream_inference_cost":0.5,"usage":1.0,"created_at":"2026-06-03T00:00:00Z","model":"m1","is_byok":true,"provider_name":"openai","streamed":false,"finish_reason":"stop","latency":10,"generation_time":20,"native_tokens_prompt":1,"native_tokens_completion":2,"native_tokens_reasoning":0,"native_tokens_cached":0,"native_tokens_cache_creation":0,"billable_web_search_calls":0}}"""
                    else -> """{}"""
                }
                respond(content, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            },
        )
        val provider = CreateGatewayHttpProvider(
            client,
            GatewayProviderSettings {
                baseUrl("https://gateway.test/v3/ai");
                apiKey("secret")
            },
        )

        val models = provider.getAvailableModels()
        val credits = provider.getCredits()
        val spend = provider.getSpendReport(
            GatewaySpendReportParams {
                startDate("2026-06-01")
                endDate("2026-06-03")
            }
        )
        val generation = provider.getGenerationInfo(GatewayGenerationInfoParams { id("gen_1") })

        assertEquals("m1", models.models.single().id)
        assertEquals(GatewayModelType.Language, models.models.single().modelType)
        assertEquals("0.01", models.models.single().pricing?.cachedInputTokens)
        assertEquals("100", credits.balance)
        assertEquals(GatewayCredentialType.Byok, spend.results.single().credentialType)
        assertEquals("gen_1", generation.id)
        assertTrue(seenUrls.any { it.contains("/v1/report?start_date=2026-06-01") })
        assertTrue(seenUrls.any { it.contains("/v1/generation?id=gen_1") })
    }

    @Test
    fun `KtorGatewayTransport maps embedding image video and reranking model calls`() = runTest {
        val client = HttpClient(
            MockEngine { request ->
                val content = when (request.url.encodedPath) {
                    "/v3/ai/embedding-model" -> """{"embeddings":[[1,2]],"usage":{"tokens":3},"providerMetadata":{"gateway":{"ok":true}}}"""
                    "/v3/ai/image-model" -> """{"images":["iVBORw0="],"warnings":[{"type":"other","message":"note"}]}"""
                    "/v3/ai/video-model" -> """
                        data: {"type":"result","videos":[{"type":"base64","data":"AAAA","mediaType":"video/mp4"}],"warnings":[{"type":"other","message":"note"}]}

                    """.trimIndent()
                    "/v3/ai/reranking-model" -> """{"ranking":[{"index":1,"relevanceScore":0.9},{"index":0,"relevanceScore":0.1}]}"""
                    else -> """{}"""
                }
                respond(
                    content,
                    HttpStatusCode.OK,
                    headersOf(
                        HttpHeaders.ContentType,
                        if (request.url.encodedPath == "/v3/ai/video-model") "text/event-stream" else "application/json",
                    ),
                )
            },
        )
        val provider = CreateGatewayHttpProvider(
            client,
            GatewayProviderSettings {
                baseUrl("https://gateway.test/v3/ai");
                apiKey("secret")
            },
        )

        val embedding = Embedding.embed(provider.embeddingModel("embed"), "abc")
        val image = ImageGeneration.generateImage(provider.imageModel("image"), "logo")
        val video = VideoGeneration.generateVideo(provider.videoModel("video"), "clip")
        val ranked = Reranking.rerank(provider.rerankingModel("rank"), "q", listOf("first", "second"))

        assertEquals(listOf(1f, 2f), embedding.embedding)
        assertEquals(3, embedding.usage.tokens)
        assertEquals("iVBORw0=", image.image.base64)
        assertEquals("note", image.warnings.single().message)
        assertEquals("AAAA", video.video.base64)
        assertEquals("second", ranked.results.first().value)
    }
}
