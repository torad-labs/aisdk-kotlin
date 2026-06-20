package ai.torad.aisdk

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EmbedRerankParityTest {
    // A model that auto-batches at 1/call, supports parallelism, and records how many
    // calls are concurrently in flight so the test can prove batches actually overlap.
    private class ParallelEmbeddingModel : EmbeddingModel {
        override val modelId = "test/embed"
        override val maxEmbeddingsPerCall = 1
        override val supportsParallelCalls = true
        var inFlight = 0
        var maxInFlight = 0
        val gate = CompletableDeferred<Unit>()
        var started = 0

        override suspend fun embed(params: EmbeddingModelCallParams): EmbeddingModelResult {
            inFlight++
            maxInFlight = maxOf(maxInFlight, inFlight)
            if (++started == 3) gate.complete(Unit) // release once all 3 batches are concurrently in flight
            gate.await()
            inFlight--
            return EmbeddingModelResult(
                embeddings = params.values.map { listOf(it.length.toFloat()) },
                usage = EmbeddingUsage(tokens = params.values.size),
            )
        }
    }

    @Test
    fun `embedMany runs batches concurrently when the model supports it and preserves order`() = runTest {
        val model = ParallelEmbeddingModel()
        val result = Embedding.embedMany(model, listOf("a", "bb", "ccc"))
        // 3 values, maxEmbeddingsPerCall=1 → 3 batches; the gate only opens once all 3
        // are in flight, so completion proves they ran concurrently.
        assertEquals(3, model.maxInFlight, "all batches must be in flight at once")
        assertEquals(listOf(listOf(1f), listOf(2f), listOf(3f)), result.embeddings, "order preserved")
        assertEquals(3, result.usage.tokens, "usage summed across batches")
        assertEquals(3, result.responses.size, "one response per batch")
    }

    @Test
    fun `rerank preserves provider ranking order and passes topN through`() = runTest {
        var capturedTopN: Int? = null
        val model = object : RerankingModel {
            override val modelId = "test/rerank"
            override suspend fun rerank(params: RerankingParams): RerankingModelResult {
                capturedTopN = params.topN
                return RerankingModelResult(
                    results = listOf(
                        RerankedItem("mid", score = 0.5f, index = 1),
                        RerankedItem("low", score = 0.1f, index = 0),
                        RerankedItem("high", score = 0.9f, index = 2),
                    ),
                )
            }
        }
        val result = Reranking.rerank(model, "q", listOf("low", "mid", "high"), topN = 2)
        assertEquals(2, capturedTopN)
        assertEquals(listOf("low", "mid", "high"), result.originalDocuments)
        assertEquals(listOf("mid", "low", "high"), result.rerankedDocuments)
    }

    @Test
    fun `rerank with empty documents is a no-op rather than an error`() = runTest {
        val model = object : RerankingModel {
            override val modelId = "test/rerank"
            override suspend fun rerank(params: RerankingParams): RerankingModelResult =
                error("must not be called for empty documents")
        }
        val result = Reranking.rerank(model, "q", emptyList())
        assertTrue(result.results.isEmpty())
        assertTrue(result.rerankedDocuments.isEmpty())
    }
}
