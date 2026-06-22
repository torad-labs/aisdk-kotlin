package ai.torad.aisdk

import ai.torad.aisdk.providers.TogetherAI
import ai.torad.aisdk.providers.TogetherAIProviderSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Representative degrades-to-default test for the OpenAI-compatible facade batch (Groq/Fireworks/
 * DeepInfra/Perplexity/DeepSeek/MoonshotAI/TogetherAI). The facades' shared error + usage paths run
 * through the now-fixed openai-compatible-core (covered by OpenAICompatibleCoreDefensiveParsingTest);
 * this covers a facade-LOCAL parse — TogetherAI's reranking response.
 */
class TogetherAIDefensiveParsingTest {
    /**
     * Regression (the M4 bug-class): the rerank parser read `relevance_score` via
     * `?.jsonPrimitive?.floatOrNull`, which throws on a present-but-non-primitive field — failing the
     * whole rerank(). The safe `(X as? JsonPrimitive)?.…` degrades to null -> the `?: 0f` fallback.
     */
    @Test
    fun `rerank degrades a non-primitive relevance_score to zero`() = runTest {
        val client = HttpClient(
            MockEngine {
                respond(
                    content = """{"results":[{"index":0,"relevance_score":{"oops":1}}]}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        val model = TogetherAI(client, TogetherAIProviderSettings(apiKey = "key"))
            .reranking(ModelId("Salesforce/Llama-Rank-V1"))

        val result = model.rerank(RerankingParams(query = "q", documents = listOf("doc")))

        assertEquals(
            0f,
            result.results.single().score,
            "a non-primitive relevance_score degrades to 0f, no crash",
        )
    }
}
