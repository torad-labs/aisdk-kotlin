package ai.torad.aisdk
import ai.torad.aisdk.providers.VOYAGE_VERSION
import ai.torad.aisdk.providers.VoyageProviderSettings
import ai.torad.aisdk.providers.createVoyage
import ai.torad.aisdk.providers.voyage

import io.ktor.http.HttpHeaders
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class VoyageProviderTest {
    @Test
    fun `embedding model sends voyage request shape and parses usage`() = runTest {
        val fixture = createTestServer(
            mutableMapOf(
                "https://voyage.test/v1/embeddings" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """{"data":[{"embedding":[0.1,0.2],"index":1},{"embedding":[0.3,0.4],"index":0}],"usage":{"total_tokens":7}}""",
                        ),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val model = createVoyage(
            fixture.httpClient(),
            VoyageProviderSettings(apiKey = "key", baseURL = "https://voyage.test/v1"),
        ).embedding("voyage-4")

        val result = model.embed(
            EmbeddingModelCallParams(
                values = listOf("first", "second"),
                providerOptions = mapOf(
                    "voyage" to buildJsonObject {
                        put("inputType", JsonPrimitive("document"))
                        put("truncation", JsonPrimitive(true))
                        put("outputDimension", JsonPrimitive(256))
                        put("outputDtype", JsonPrimitive("int8"))
                    },
                ),
            ),
        )

        assertEquals("voyage.embedding", model.provider)
        assertEquals(128, model.maxEmbeddingsPerCall)
        assertEquals(true, model.supportsParallelCalls)
        assertEquals(listOf(listOf(0.1f, 0.2f), listOf(0.3f, 0.4f)), result.embeddings)
        assertEquals(7, result.usage.tokens)
        val request = fixture.calls.single()
        assertEquals("POST", request.requestMethod)
        assertEquals("https://voyage.test/v1/embeddings", request.requestUrl)
        assertEquals("Bearer key", request.requestHeaders.headerValue(HttpHeaders.Authorization))
        assertTrue(request.requestUserAgent.orEmpty().contains("ai-sdk/voyage/$VOYAGE_VERSION"))
        val body = request.requestBodyJson.jsonObject
        assertEquals("voyage-4", body["model"]?.jsonPrimitive?.contentOrNull)
        assertEquals("first", body["input"]?.jsonArray?.first()?.jsonPrimitive?.contentOrNull)
        assertEquals("document", body["input_type"]?.jsonPrimitive?.contentOrNull)
        assertEquals(true, body["truncation"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(256, body["output_dimension"]?.jsonPrimitive?.intOrNull)
        assertEquals("int8", body["output_dtype"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `reranking model sends voyage request shape and maps ranking`() = runTest {
        val fixture = createTestServer(
            mutableMapOf(
                "https://voyage.test/v1/rerank" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """{"data":[{"index":1,"relevance_score":0.9},{"index":0,"relevance_score":0.2}],"usage":{"total_tokens":11}}""",
                        ),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val model = createVoyage(
            fixture.httpClient(),
            VoyageProviderSettings(apiKey = "key", baseURL = "https://voyage.test/v1"),
        ).reranking("rerank-2.5")

        val result = model.rerank(
            RerankingParams(
                query = "best",
                documents = listOf("alpha", "beta"),
                topN = 1,
                providerOptions = mapOf(
                    "voyage" to buildJsonObject {
                        put("returnDocuments", JsonPrimitive(false))
                        put("truncation", JsonPrimitive(true))
                    },
                ),
            ),
        )

        assertEquals("voyage.reranking", model.provider)
        assertEquals("beta", result.results.first().value)
        assertEquals(0.9f, result.results.first().score)
        assertEquals(11, result.usage.promptTokens)
        val body = fixture.calls.single().requestBodyJson.jsonObject
        assertEquals("rerank-2.5", body["model"]?.jsonPrimitive?.contentOrNull)
        assertEquals("best", body["query"]?.jsonPrimitive?.contentOrNull)
        assertEquals("alpha", body["documents"]?.jsonArray?.first()?.jsonPrimitive?.contentOrNull)
        assertEquals(1, body["top_k"]?.jsonPrimitive?.intOrNull)
        assertEquals(false, body["return_documents"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(true, body["truncation"]?.jsonPrimitive?.booleanOrNull)
    }

    @Test
    fun `embedding model enforces voyage max values per call`() = runTest {
        val model = createVoyage(
            TestServer(mutableMapOf()).httpClient(),
            VoyageProviderSettings(baseURL = "https://voyage.test/v1"),
        ).embedding("voyage-4")

        val error = assertFailsWith<InvalidArgumentError> {
            model.embed(EmbeddingModelCallParams(values = List(129) { "value-$it" }))
        }

        assertTrue(error.message.orEmpty().contains("128 values"))
    }

    private fun Map<String, String>.headerValue(name: String): String? =
        entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}
