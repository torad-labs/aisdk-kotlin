@file:OptIn(LowLevelLanguageModelApi::class)

package ai.torad.aisdk

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalAiSdkApi::class)
class GatewayAndProviderUtilsParityTest {

    @Test
    fun `gateway provider exposes v6 model aliases and routes through transport`() = runTest {
        val transport = CapturingGatewayTransport()
        val provider = Gateway(
            GatewayProviderSettings {
                baseUrl("https://gateway.test/v3/ai/")
                apiKey("secret")
                headers(mapOf("x-team" to "torad"))
                transport(transport)
            },
        )

        val text = TextGenerator(provider(ModelId("chat-model"))).generate(GenerationInput.Prompt("hi")).single()
        val chat = TextGenerator(provider.chat(ModelId("chat-model-2"))).generate(GenerationInput.Prompt("hi")).single()
        val embedding = Embedding.embed(provider.embedding(ModelId("embed-model")), "hello")
        val image = ImageGeneration.generateImage(provider.image(ModelId("image-model")), "logo")
        val video = VideoGeneration.generateVideo(provider.video(ModelId("video-model")), "clip")
        val previewVideo = VideoGeneration.generateVideo(
            provider.video(ModelId("xai/grok-imagine-video-1.5-preview")),
            "clip"
        )
        val reranked = Reranking.rerank(provider.reranking(ModelId("rank-model")), "q", listOf("a", "bb"))

        assertEquals("gateway:chat-model", text.text)
        assertEquals("gateway:chat-model-2", chat.text)
        assertEquals(listOf(5f), embedding.embedding)
        assertEquals("image-model", image.image.filename)
        assertEquals("video-model", video.video.filename)
        assertEquals("xai/grok-imagine-video-1.5-preview", previewVideo.video.filename)
        assertEquals("a", reranked.results.first().value)
        assertEquals("gateway", provider.languageModel("x").provider)
        assertEquals("gateway", provider.textEmbeddingModel(ModelId("x")).provider)
        assertEquals("https://gateway.test/v3/ai", transport.contexts.first().baseUrl)
        assertEquals("Bearer secret", transport.contexts.first().headers["authorization"])
        assertEquals("api-key", transport.contexts.first().headers[GATEWAY_AUTH_METHOD_HEADER])
        assertEquals(AI_GATEWAY_PROTOCOL_VERSION, transport.contexts.first().headers["ai-gateway-protocol-version"])
        assertEquals("torad", transport.contexts.first().headers["x-team"])
        assertEquals(true, provider.tools.parallelSearch.providerExecuted)
        assertEquals(true, provider.tools.perplexitySearch.providerExecuted)
    }

    @Test
    fun `gateway metadata methods use transport and cache available models`() = runTest {
        var now = 1_000L
        val transport = CapturingGatewayTransport()
        val provider = GatewayProvider(
            GatewayProviderSettings {
                apiKey("secret")
                transport(transport)
                metadataCacheRefreshMillis(500L)
                nowMillis { now }
            },
        )

        val first = provider.getAvailableModels()
        val second = provider.getAvailableModels()
        now = 2_000L
        val third = provider.getAvailableModels()
        val credits = provider.getCredits()
        val spend = provider.getSpendReport(
            GatewaySpendReportParams {
                startDate("2026-06-01")
                endDate("2026-06-03")
                groupBy(GatewaySpendReportGroupBy.Model)
                credentialType(GatewayCredentialType.Byok)
                tags(listOf("prod", "mobile"))
            },
        )
        val generation = provider.getGenerationInfo(GatewayGenerationInfoParams { id("gen_123") })

        assertEquals("model-1", first.models.single().id)
        assertEquals(first, second)
        assertEquals("model-2", third.models.single().id)
        assertEquals(2, transport.metadataCalls)
        assertEquals("100", credits.balance)
        assertEquals(12.5, spend.results.single().totalCost)
        assertEquals(GatewayCredentialType.Byok, transport.spendParams.single().credentialType)
        assertEquals("gen_123", generation.id)
    }

    @Test
    fun `gateway metadata cache uses system clock by default`() = runTest {
        val transport = CapturingGatewayTransport()
        val provider = GatewayProvider(
            GatewayProviderSettings {
                apiKey("secret")
                transport(transport)
                metadataCacheRefreshMillis(0L)
            },
        )

        val first = provider.getAvailableModels()
        val second = provider.getAvailableModels()

        assertEquals("model-1", first.models.single().id)
        assertEquals("model-2", second.models.single().id)
        assertEquals(2, transport.metadataCalls)
    }

    @Test
    fun `gateway Poko response types keep value semantics`() {
        val pricing = GatewayPricing(input = "0.10", output = "0.20")
        val entry = GatewayLanguageModelEntry(
            id = "model-1",
            name = "Model 1",
            pricing = pricing,
            specification = GatewayLanguageModelSpecification(provider = "gateway", modelId = "model-1"),
            modelType = GatewayModelType.Language,
        )
        val equalEntry = GatewayLanguageModelEntry(
            id = "model-1",
            name = "Model 1",
            pricing = GatewayPricing(input = "0.10", output = "0.20"),
            specification = GatewayLanguageModelSpecification(provider = "gateway", modelId = "model-1"),
            modelType = GatewayModelType.Language,
        )
        val differentEntry = GatewayLanguageModelEntry(
            id = "model-2",
            name = "Model 2",
            pricing = pricing,
            specification = GatewayLanguageModelSpecification(provider = "gateway", modelId = "model-2"),
            modelType = GatewayModelType.Language,
        )
        assertEquals(entry, equalEntry)
        assertEquals(entry.hashCode(), equalEntry.hashCode())
        assertNotEquals(entry, differentEntry)

        val report = GatewaySpendReportResponse(
            results = listOf(
                GatewaySpendReportRow(model = "model-1", totalCost = 1.5, requestCount = 3),
            ),
        )
        val equalReport = GatewaySpendReportResponse(
            results = listOf(
                GatewaySpendReportRow(model = "model-1", totalCost = 1.5, requestCount = 3),
            ),
        )
        val differentReport = GatewaySpendReportResponse(
            results = listOf(
                GatewaySpendReportRow(model = "model-1", totalCost = 2.0, requestCount = 3),
            ),
        )
        assertEquals(report, equalReport)
        assertEquals(report.hashCode(), equalReport.hashCode())
        assertNotEquals(report, differentReport)

        val generation = GatewayGenerationInfo(
            id = "gen_1",
            totalCost = 1.0,
            upstreamInferenceCost = 0.5,
            usage = 1.0,
            createdAt = "2026-06-03T00:00:00Z",
            model = "model",
            isByok = true,
            providerName = "gateway",
            streamed = false,
            finishReason = "stop",
            latency = 10,
            generationTime = 20,
            promptTokens = 1,
            completionTokens = 2,
            reasoningTokens = 0,
            cachedTokens = 0,
            cacheCreationTokens = 0,
            billableWebSearchCalls = 0,
        )
        val equalGeneration = GatewayGenerationInfo(
            id = "gen_1",
            totalCost = 1.0,
            upstreamInferenceCost = 0.5,
            usage = 1.0,
            createdAt = "2026-06-03T00:00:00Z",
            model = "model",
            isByok = true,
            providerName = "gateway",
            streamed = false,
            finishReason = "stop",
            latency = 10,
            generationTime = 20,
            promptTokens = 1,
            completionTokens = 2,
            reasoningTokens = 0,
            cachedTokens = 0,
            cacheCreationTokens = 0,
            billableWebSearchCalls = 0,
        )
        val differentGeneration = GatewayGenerationInfo(
            id = "gen_2",
            totalCost = 1.0,
            upstreamInferenceCost = 0.5,
            usage = 1.0,
            createdAt = "2026-06-03T00:00:00Z",
            model = "model",
            isByok = true,
            providerName = "gateway",
            streamed = false,
            finishReason = "stop",
            latency = 10,
            generationTime = 20,
            promptTokens = 1,
            completionTokens = 2,
            reasoningTokens = 0,
            cachedTokens = 0,
            cacheCreationTokens = 0,
            billableWebSearchCalls = 0,
        )
        assertEquals(generation, equalGeneration)
        assertEquals(generation.hashCode(), equalGeneration.hashCode())
        assertNotEquals(generation, differentGeneration)
    }

    @Test
    fun `concurrent getAvailableModels within refresh window calls transport at most once`() = runTest {
        val transport = CapturingGatewayTransport()
        val provider = GatewayProvider(
            GatewayProviderSettings {
                apiKey("secret")
                transport(transport)
                metadataCacheRefreshMillis(60_000L)
                nowMillis { 1_000L }
            },
        )

        val results = (1..10).map { async { provider.getAvailableModels() } }.awaitAll()

        assertEquals(1, transport.metadataCalls)
        assertTrue(results.all { it == results.first() })
    }

    private class CapturingGatewayTransport : GatewayTransport {
        val contexts = mutableListOf<GatewayRequestContext>()
        val spendParams = mutableListOf<GatewaySpendReportParams>()
        var metadataCalls = 0

        override suspend fun generateText(
            context: GatewayRequestContext,
            modelId: ModelId,
            params: LanguageModelCallParams,
        ): LanguageModelResult {
            contexts += context
            return LanguageModelResult(
                text = "gateway:$modelId",
                finishReason = FinishReason.Stop,
                usage = Usage.of(promptTokens = params.messages.size, completionTokens = 1),
            )
        }

        override fun streamText(
            context: GatewayRequestContext,
            modelId: ModelId,
            params: LanguageModelCallParams,
        ): Flow<StreamEvent> {
            contexts += context
            return flowOf(StreamEvent.TextDelta("text", "gateway:$modelId"))
        }

        override suspend fun embed(
            context: GatewayRequestContext,
            modelId: ModelId,
            params: EmbeddingModelCallParams,
        ): EmbeddingModelResult {
            contexts += context
            return EmbeddingModelResult(
                embeddings = params.values.map { listOf(it.length.toFloat()) },
                usage = EmbeddingUsage(tokens = params.values.sumOf { it.length }),
            )
        }

        override suspend fun generateImage(
            context: GatewayRequestContext,
            modelId: ModelId,
            params: ImageGenerationParams,
        ): ImageModelResult {
            contexts += context
            return ImageModelResult(
                images = List(params.n) {
                    GeneratedFile(mediaType = "image/png", base64 = "iVBORw0=", filename = modelId.value)
                },
            )
        }

        override suspend fun generateVideo(
            context: GatewayRequestContext,
            modelId: ModelId,
            params: VideoGenerationParams,
        ): VideoModelResult {
            contexts += context
            return VideoModelResult(
                videos = List(params.n) {
                    GeneratedFile(mediaType = "video/mp4", base64 = "AAAA", filename = modelId.value)
                },
            )
        }

        override suspend fun rerank(
            context: GatewayRequestContext,
            modelId: ModelId,
            params: RerankingParams,
        ): RerankingModelResult {
            contexts += context
            return RerankingModelResult(
                results = params.documents.mapIndexed { index, value ->
                    RerankedItem(value = value, score = value.length.toFloat(), index = index)
                },
            )
        }

        override suspend fun getAvailableModels(context: GatewayRequestContext): GatewayFetchMetadataResponse {
            contexts += context
            metadataCalls += 1
            return GatewayFetchMetadataResponse(
                models = listOf(
                    GatewayLanguageModelEntry(
                        id = "model-$metadataCalls",
                        name = "Model $metadataCalls",
                        specification = GatewayLanguageModelSpecification(
                            provider = "gateway",
                            modelId = "model-$metadataCalls"
                        ),
                        modelType = GatewayModelType.Language,
                    ),
                ),
            )
        }

        override suspend fun getCredits(context: GatewayRequestContext): GatewayCreditsResponse {
            contexts += context
            return GatewayCreditsResponse(balance = "100", totalUsed = "12")
        }

        override suspend fun getSpendReport(
            context: GatewayRequestContext,
            params: GatewaySpendReportParams,
        ): GatewaySpendReportResponse {
            contexts += context
            spendParams += params
            return GatewaySpendReportResponse(
                results = listOf(
                    GatewaySpendReportRow(
                        model = params.model ?: "model",
                        credentialType = params.credentialType,
                        totalCost = 12.5,
                        requestCount = 3,
                    ),
                ),
            )
        }

        override suspend fun getGenerationInfo(
            context: GatewayRequestContext,
            params: GatewayGenerationInfoParams,
        ): GatewayGenerationInfo {
            contexts += context
            return GatewayGenerationInfo(
                id = params.id,
                totalCost = 1.0,
                upstreamInferenceCost = 0.5,
                usage = 1.0,
                createdAt = "2026-06-03T00:00:00Z",
                model = "model",
                isByok = true,
                providerName = "gateway",
                streamed = false,
                finishReason = "stop",
                latency = 10,
                generationTime = 20,
                promptTokens = 1,
                completionTokens = 2,
                reasoningTokens = 0,
                cachedTokens = 0,
                cacheCreationTokens = 0,
                billableWebSearchCalls = 0,
            )
        }
    }
}
