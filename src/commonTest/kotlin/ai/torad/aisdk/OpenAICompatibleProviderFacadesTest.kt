package ai.torad.aisdk
import ai.torad.aisdk.providers.CEREBRAS_VERSION
import ai.torad.aisdk.providers.DEEPINFRA_VERSION
import ai.torad.aisdk.providers.DEEPSEEK_VERSION
import ai.torad.aisdk.providers.DeepInfraProviderSettings
import ai.torad.aisdk.providers.FIREWORKS_VERSION
import ai.torad.aisdk.providers.FireworksProviderSettings
import ai.torad.aisdk.providers.GROQ_VERSION
import ai.torad.aisdk.providers.GroqProviderSettings
import ai.torad.aisdk.providers.MOONSHOTAI_VERSION
import ai.torad.aisdk.providers.PERPLEXITY_VERSION
import ai.torad.aisdk.providers.TOGETHERAI_VERSION
import ai.torad.aisdk.providers.VERCEL_VERSION
import ai.torad.aisdk.providers.VercelProviderSettings
import ai.torad.aisdk.providers.browserSearch
import ai.torad.aisdk.providers.cerebras
import ai.torad.aisdk.providers.createDeepInfra
import ai.torad.aisdk.providers.createFireworks
import ai.torad.aisdk.providers.createGroq
import ai.torad.aisdk.providers.createVercel
import ai.torad.aisdk.providers.deepinfra
import ai.torad.aisdk.providers.deepseek
import ai.torad.aisdk.providers.fireworks
import ai.torad.aisdk.providers.groq
import ai.torad.aisdk.providers.moonshotai
import ai.torad.aisdk.providers.perplexity
import ai.torad.aisdk.providers.togetherai
import ai.torad.aisdk.providers.vercel
import ai.torad.aisdk.providers.CerebrasProviderSettings
import ai.torad.aisdk.providers.DeepSeekProviderSettings
import ai.torad.aisdk.providers.MoonshotAIProviderSettings
import ai.torad.aisdk.providers.PerplexityProviderSettings
import ai.torad.aisdk.providers.TogetherAIProviderSettings
import ai.torad.aisdk.providers.createCerebras
import ai.torad.aisdk.providers.createDeepSeek
import ai.torad.aisdk.providers.createMoonshotAI
import ai.torad.aisdk.providers.createPerplexity
import ai.torad.aisdk.providers.createTogetherAI

import io.ktor.http.HttpHeaders
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OpenAICompatibleProviderFacadesTest {
    @Test
    fun `provider facades use upstream base urls headers and provider ids`() = runTest {
        val providers = listOf(
            ProviderCase(
                name = "deepseek",
                expectedChatUrl = "https://deepseek.test/chat/completions",
                expectedUserAgent = "ai-sdk/deepseek/$DEEPSEEK_VERSION",
                create = { client -> createDeepSeek(client, DeepSeekProviderSettings(apiKey = "key", baseURL = "https://deepseek.test")).languageModel("model") },
            ),
            ProviderCase(
                name = "cerebras",
                expectedChatUrl = "https://cerebras.test/v1/chat/completions",
                expectedUserAgent = "ai-sdk/cerebras/$CEREBRAS_VERSION",
                create = { client -> createCerebras(client, CerebrasProviderSettings(apiKey = "key", baseURL = "https://cerebras.test/v1")).languageModel("model") },
            ),
            ProviderCase(
                name = "deepinfra",
                expectedChatUrl = "https://deepinfra.test/v1/openai/chat/completions",
                expectedUserAgent = "ai-sdk/deepinfra/$DEEPINFRA_VERSION",
                create = { client -> createDeepInfra(client, DeepInfraProviderSettings(apiKey = "key", baseURL = "https://deepinfra.test/v1")).languageModel("model") },
            ),
            ProviderCase(
                name = "fireworks",
                expectedChatUrl = "https://fireworks.test/inference/v1/chat/completions",
                expectedUserAgent = "ai-sdk/fireworks/$FIREWORKS_VERSION",
                create = { client -> createFireworks(client, FireworksProviderSettings(apiKey = "key", baseURL = "https://fireworks.test/inference/v1")).chatModel("model") },
            ),
            ProviderCase(
                name = "perplexity",
                expectedChatUrl = "https://perplexity.test/chat/completions",
                expectedUserAgent = "ai-sdk/perplexity/$PERPLEXITY_VERSION",
                create = { client -> createPerplexity(client, PerplexityProviderSettings(apiKey = "key", baseURL = "https://perplexity.test")).languageModel("model") },
            ),
            ProviderCase(
                name = "moonshotai",
                expectedChatUrl = "https://moonshot.test/v1/chat/completions",
                expectedUserAgent = "ai-sdk/moonshotai/$MOONSHOTAI_VERSION",
                create = { client -> createMoonshotAI(client, MoonshotAIProviderSettings(apiKey = "key", baseURL = "https://moonshot.test/v1")).chatModel("model") },
            ),
            ProviderCase(
                name = "groq",
                expectedChatUrl = "https://groq.test/openai/v1/chat/completions",
                expectedUserAgent = "ai-sdk/groq/$GROQ_VERSION",
                create = { client -> createGroq(client, GroqProviderSettings(apiKey = "key", baseURL = "https://groq.test/openai/v1")).chat("model") },
            ),
            ProviderCase(
                name = "togetherai",
                expectedChatUrl = "https://together.test/v1/chat/completions",
                expectedUserAgent = "ai-sdk/togetherai/$TOGETHERAI_VERSION",
                create = { client -> createTogetherAI(client, TogetherAIProviderSettings(apiKey = "key", baseURL = "https://together.test/v1")).chatModel("model") },
            ),
            ProviderCase(
                name = "vercel",
                expectedChatUrl = "https://vercel.test/v1/chat/completions",
                expectedUserAgent = "ai-sdk/vercel/$VERCEL_VERSION",
                create = { client -> createVercel(client, VercelProviderSettings(apiKey = "key", baseURL = "https://vercel.test/v1")).languageModel("model") },
            ),
        )

        for (case in providers) {
            val seenRequests = mutableListOf<TestServerCall>()
            val fixture = createTestServer(
                mutableMapOf(
                    case.expectedChatUrl to UrlHandler(
                        UrlResponse.JsonValue(
                            Json.parseToJsonElement(
                                """{"id":"id_1","model":"model","choices":[{"message":{"role":"assistant","content":"${case.name}"},"finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":2}}""",
                            ),
                        ),
                    ),
                ),
            )
            fixture.server.start()

            val model = case.create(fixture.httpClient())
            val result = model.generate(LanguageModelCallParams(listOf(userMessage("hi"))))
            seenRequests += fixture.calls

            assertEquals(case.name, result.text)
            assertEquals("${case.name}.chat", model.provider)
            val request = seenRequests.single()
            assertEquals("POST", request.requestMethod)
            assertEquals(case.expectedChatUrl, request.requestUrl)
            assertEquals("Bearer key", request.requestHeaders.headerValue(HttpHeaders.Authorization))
            assertTrue(request.requestUserAgent.orEmpty().contains(case.expectedUserAgent))
            assertEquals("model", request.requestBodyJson.jsonObject["model"]?.jsonPrimitive?.contentOrNull)
        }
    }

    @Test
    fun `deepinfra exposes completion embedding image and fixes broken reasoning usage`() = runTest {
        val fixture = createTestServer(
            mutableMapOf(
                "https://deepinfra.test/v1/openai/chat/completions" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """{"id":"chat_1","model":"model","choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],"usage":{"prompt_tokens":3,"completion_tokens":4,"completion_tokens_details":{"reasoning_tokens":9},"total_tokens":7}}""",
                        ),
                    ),
                ),
                "https://deepinfra.test/v1/openai/completions" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement("""{"id":"comp_1","model":"model","choices":[{"text":"done","finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":2}}"""),
                    ),
                ),
                "https://deepinfra.test/v1/openai/embeddings" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement("""{"data":[{"embedding":[0.1,0.2]}],"usage":{"prompt_tokens":2}}"""),
                    ),
                ),
                "https://deepinfra.test/v1/inference/black-forest-labs/FLUX-1-schnell" to UrlHandler(
                    UrlResponse.JsonValue(Json.parseToJsonElement("""{"images":["data:image/png;base64,aW1n"]}""")),
                ),
            ),
        )
        fixture.server.start()
        val provider = createDeepInfra(
            fixture.httpClient(),
            DeepInfraProviderSettings(apiKey = "key", baseURL = "https://deepinfra.test/v1"),
        )

        val chat = provider.chatModel("model").generate(LanguageModelCallParams(listOf(userMessage("hi"))))
        val completion = provider.completionModel("model").generate(LanguageModelCallParams(listOf(userMessage("hi"))))
        val embedding = provider.textEmbeddingModel("embed").embed(EmbeddingModelCallParams(listOf("hello")))
        val image = provider.image("black-forest-labs/FLUX-1-schnell").generate(
            ImageGenerationParams(
                prompt = "mountain",
                n = 2,
                size = "512x768",
                aspectRatio = "1:1",
                seed = 7,
                providerOptions = mapOf("deepinfra" to buildJsonObject { put("scheduler", JsonPrimitive("fast")) }),
            ),
        )

        assertEquals(13, chat.usage.completionTokens)
        assertEquals(9, chat.usage.outputTokens.reasoning)
        assertEquals("done", completion.text)
        assertEquals(listOf(0.1f, 0.2f), embedding.embeddings.single())
        assertEquals("aW1n", image.images.single().base64)
        val imageBody = fixture.calls.last().requestBodyJson.jsonObject
        assertEquals("mountain", imageBody["prompt"]?.jsonPrimitive?.contentOrNull)
        assertEquals(2, imageBody["num_images"]?.jsonPrimitive?.intOrNull)
        assertEquals("512", imageBody["width"]?.jsonPrimitive?.contentOrNull)
        assertEquals("768", imageBody["height"]?.jsonPrimitive?.contentOrNull)
        assertEquals("1:1", imageBody["aspect_ratio"]?.jsonPrimitive?.contentOrNull)
        assertEquals(7, imageBody["seed"]?.jsonPrimitive?.intOrNull)
        assertEquals("fast", imageBody["scheduler"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `fireworks maps language options and image backends`() = runTest {
        val fixture = createTestServer(
            mutableMapOf(
                "https://fireworks.test/inference/v1/chat/completions" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement("""{"id":"chat_1","model":"model","choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":2}}"""),
                    ),
                ),
                "https://fireworks.test/inference/v1/workflows/accounts/fireworks/models/flux-1-dev-fp8/text_to_image" to UrlHandler(
                    UrlResponse.Binary(byteArrayOf(1, 2, 3), headers = mapOf(HttpHeaders.ContentType to "image/jpeg")),
                ),
                "https://fireworks.test/inference/v1/image_generation/accounts/fireworks/models/SSD-1B" to UrlHandler(
                    UrlResponse.Binary(byteArrayOf(4, 5, 6)),
                ),
            ),
        )
        fixture.server.start()
        val provider = createFireworks(
            fixture.httpClient(),
            FireworksProviderSettings(apiKey = "key", baseURL = "https://fireworks.test/inference/v1"),
        )

        provider.chatModel("model").generate(
            LanguageModelCallParams(
                messages = listOf(userMessage("hi")),
                providerOptions = mapOf(
                    "fireworks" to buildJsonObject {
                        put(
                            "thinking",
                            buildJsonObject {
                                put("type", JsonPrimitive("enabled"))
                                put("budgetTokens", JsonPrimitive(2048))
                            },
                        )
                        put("reasoningHistory", JsonPrimitive("preserved"))
                    },
                ),
            ),
        )
        val chatBody = fixture.calls.single { it.requestUrl.endsWith("/chat/completions") }.requestBodyJson.jsonObject
        assertEquals(2048, chatBody["thinking"]?.jsonObject?.get("budget_tokens")?.jsonPrimitive?.intOrNull)
        assertEquals("preserved", chatBody["reasoning_history"]?.jsonPrimitive?.contentOrNull)

        val workflowImage = provider.image("accounts/fireworks/models/flux-1-dev-fp8").generate(
            ImageGenerationParams(prompt = "cat", n = 1, size = "512x512", aspectRatio = "1:1"),
        )
        val imageGenerationImage = provider.image("accounts/fireworks/models/SSD-1B").generate(
            ImageGenerationParams(prompt = "dog", n = 1, size = "768x512", aspectRatio = "16:9"),
        )

        assertEquals(convertByteArrayToBase64(byteArrayOf(1, 2, 3)), workflowImage.images.single().base64)
        assertEquals("image/jpeg", workflowImage.images.single().mediaType)
        assertEquals("unsupported", workflowImage.warnings.single().type)
        assertEquals(convertByteArrayToBase64(byteArrayOf(4, 5, 6)), imageGenerationImage.images.single().base64)
        assertEquals("unsupported", imageGenerationImage.warnings.single().type)
        val workflowBody = fixture.calls.first { it.requestUrl.contains("text_to_image") }.requestBodyJson.jsonObject
        assertEquals("cat", workflowBody["prompt"]?.jsonPrimitive?.contentOrNull)
        assertEquals("1:1", workflowBody["aspect_ratio"]?.jsonPrimitive?.contentOrNull)
        assertEquals("512", workflowBody["width"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `fireworks async image model polls and downloads result`() = runTest {
        val fixture = createTestServer(
            mutableMapOf(
                "https://fireworks.test/inference/v1/workflows/accounts/fireworks/models/flux-kontext-pro" to UrlHandler(
                    UrlResponse.JsonValue(Json.parseToJsonElement("""{"request_id":"req_1"}""")),
                ),
                "https://fireworks.test/inference/v1/workflows/accounts/fireworks/models/flux-kontext-pro/get_result" to UrlHandler(
                    UrlResponse.JsonValue(Json.parseToJsonElement("""{"status":"Ready","result":{"sample":"https://cdn.test/result.png"}}""")),
                ),
                "https://cdn.test/result.png" to UrlHandler(
                    UrlResponse.Binary(byteArrayOf(9, 8, 7), headers = mapOf(HttpHeaders.ContentType to "image/png")),
                ),
            ),
        )
        fixture.server.start()
        val provider = createFireworks(
            fixture.httpClient(),
            FireworksProviderSettings(apiKey = "key", baseURL = "https://fireworks.test/inference/v1"),
        )

        val image = provider.image("accounts/fireworks/models/flux-kontext-pro").generate(ImageGenerationParams(prompt = "edit"))

        assertEquals(convertByteArrayToBase64(byteArrayOf(9, 8, 7)), image.images.single().base64)
        assertEquals(
            listOf(
                "https://fireworks.test/inference/v1/workflows/accounts/fireworks/models/flux-kontext-pro",
                "https://fireworks.test/inference/v1/workflows/accounts/fireworks/models/flux-kontext-pro/get_result",
                "https://cdn.test/result.png",
            ),
            fixture.calls.map { it.requestUrl },
        )
    }

    @Test
    fun `togetherai exposes completion embedding image and reranking`() = runTest {
        val fixture = createTestServer(
            mutableMapOf(
                "https://together.test/v1/completions" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement("""{"id":"comp_1","model":"model","choices":[{"text":"done","finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":2}}"""),
                    ),
                ),
                "https://together.test/v1/embeddings" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement("""{"data":[{"embedding":[0.3,0.4]}],"usage":{"prompt_tokens":2}}"""),
                    ),
                ),
                "https://together.test/v1/images/generations" to UrlHandler(
                    UrlResponse.JsonValue(Json.parseToJsonElement("""{"data":[{"b64_json":"aW1n"}]}""")),
                ),
                "https://together.test/v1/rerank" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement("""{"id":"rerank_1","model":"ranker","results":[{"index":1,"relevance_score":0.9},{"index":0,"relevance_score":0.2}],"usage":{"prompt_tokens":3,"completion_tokens":0,"total_tokens":3}}"""),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val provider = createTogetherAI(
            fixture.httpClient(),
            TogetherAIProviderSettings(apiKey = "key", baseURL = "https://together.test/v1"),
        )

        assertEquals("done", provider.completionModel("model").generate(LanguageModelCallParams(listOf(userMessage("hi")))).text)
        assertEquals(listOf(0.3f, 0.4f), provider.embeddingModel("embed").embed(EmbeddingModelCallParams(listOf("hello"))).embeddings.single())
        val image = provider.image("black-forest-labs/FLUX.1-dev").generate(
            ImageGenerationParams(
                prompt = "house",
                n = 2,
                size = "1024x768",
                seed = 12,
                providerOptions = mapOf(
                    "togetherai" to buildJsonObject {
                        put("steps", JsonPrimitive(8))
                        put("disable_safety_checker", JsonPrimitive(true))
                    },
                ),
            ),
        )
        val ranking = provider.reranking("ranker").rerank(
            RerankingParams(
                query = "best",
                documents = listOf("alpha", "beta"),
                topN = 1,
                providerOptions = mapOf("togetherai" to buildJsonObject { put("rankFields", JsonArray(listOf(JsonPrimitive("text")))) }),
            ),
        )

        assertEquals("aW1n", image.images.single().base64)
        val imageBody = fixture.calls.first { it.requestUrl.endsWith("/images/generations") }.requestBodyJson.jsonObject
        assertEquals(1024, imageBody["width"]?.jsonPrimitive?.intOrNull)
        assertEquals(768, imageBody["height"]?.jsonPrimitive?.intOrNull)
        assertEquals("base64", imageBody["response_format"]?.jsonPrimitive?.contentOrNull)
        assertEquals(8, imageBody["steps"]?.jsonPrimitive?.intOrNull)
        assertEquals(true, imageBody["disable_safety_checker"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("beta", ranking.results.first().value)
        assertEquals(0.9f, ranking.results.first().score)
        val rerankBody = fixture.calls.first { it.requestUrl.endsWith("/rerank") }.requestBodyJson.jsonObject
        assertEquals("ranker", rerankBody["model"]?.jsonPrimitive?.contentOrNull)
        assertEquals("best", rerankBody["query"]?.jsonPrimitive?.contentOrNull)
        assertEquals(1, rerankBody["top_n"]?.jsonPrimitive?.intOrNull)
        assertEquals("text", rerankBody["rank_fields"]?.jsonArray?.single()?.jsonPrimitive?.contentOrNull)
        assertEquals(false, rerankBody["return_documents"]?.jsonPrimitive?.booleanOrNull)
    }

    @Test
    fun `groq exposes transcription and browser search tool`() = runTest {
        val fixture = createTestServer(
            mutableMapOf(
                "https://groq.test/openai/v1/audio/transcriptions" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement("""{"text":"transcribed"}"""),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val provider = createGroq(
            fixture.httpClient(),
            GroqProviderSettings(apiKey = "key", baseURL = "https://groq.test/openai/v1"),
        )

        val transcript = provider.transcription("whisper-large-v3").transcribe(
            TranscriptionParams(
                audio = AudioSource(mediaType = "audio/mpeg", base64 = convertByteArrayToBase64(byteArrayOf(1, 2))),
            ),
        )

        assertEquals("transcribed", transcript.text)
        assertEquals("browserSearch", provider.tools.browserSearch.name)
        assertEquals(JsonPrimitive("groq.browser_search"), provider.tools.browserSearch.metadata["providerToolId"])
    }

    @Test
    fun `unsupported model families throw provider specific NoSuchModelError`() {
        val provider = createDeepSeek(
            TestServer(mutableMapOf()).httpClient(),
            DeepSeekProviderSettings(baseURL = "https://deepseek.test"),
        )

        val error = assertFailsWith<NoSuchModelError> {
            provider.embeddingModel("embedding")
        }

        assertTrue(error.message.orEmpty().contains("deepseek"))

        val vercel = createVercel(
            TestServer(mutableMapOf()).httpClient(),
            VercelProviderSettings(baseURL = "https://vercel.test/v1"),
        )
        val vercelError = assertFailsWith<NoSuchModelError> {
            vercel.imageModel("image")
        }

        assertTrue(vercelError.message.orEmpty().contains("vercel"))
    }

    private data class ProviderCase(
        val name: String,
        val expectedChatUrl: String,
        val expectedUserAgent: String,
        val create: (io.ktor.client.HttpClient) -> LanguageModel,
    )

    private fun Map<String, String>.headerValue(name: String): String? =
        entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}
