package ai.torad.aisdk

import ai.torad.aisdk.providers.BasetenEmbeddingModelOptions
import ai.torad.aisdk.providers.CohereEmbeddingModelOptions
import ai.torad.aisdk.providers.CohereLanguageModelOptions
import ai.torad.aisdk.providers.CohereRerankingModelOptions
import ai.torad.aisdk.providers.CohereThinkingOptions
import ai.torad.aisdk.providers.TogetherAIRerankingModelOptions
import ai.torad.aisdk.providers.VoyageEmbeddingModelOptions
import ai.torad.aisdk.providers.VoyageRerankingModelOptions
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ProviderModelOptionsBuilderTest {
    @Test
    fun `Cohere model options DSL keeps value semantics and serialization`() {
        val thinking = CohereThinkingOptions {
            type("enabled")
            tokenBudget(512)
        }
        val options = CohereLanguageModelOptions {
            thinking(thinking)
        }
        val equal = CohereLanguageModelOptions {
            thinking(CohereThinkingOptions {
                type("enabled")
                tokenBudget(512)
            })
        }
        val different = CohereLanguageModelOptions {
            thinking(CohereThinkingOptions {
                type("disabled")
            })
        }

        assertEquals(equal, options)
        assertEquals(equal.hashCode(), options.hashCode())
        assertNotEquals(different, options)
        assertEquals(options, aiSdkJson.decodeFromString<CohereLanguageModelOptions>(aiSdkJson.encodeToString(options)))
    }

    @Test
    fun `embedding option DSLs keep field values and value semantics`() {
        val cohere = CohereEmbeddingModelOptions {
            inputType("search_document")
            truncate("END")
            outputDimension(512)
        }
        val voyage = VoyageEmbeddingModelOptions {
            inputType("document")
            truncation(true)
            outputDimension(1024)
            outputDtype("float")
        }
        val baseten = BasetenEmbeddingModelOptions {
            raw(mapOf("normalize" to JsonPrimitive(true)))
        }

        assertEquals("search_document", cohere.inputType)
        assertEquals(cohere, CohereEmbeddingModelOptions {
            inputType("search_document")
            truncate("END")
            outputDimension(512)
        })
        assertEquals(voyage, aiSdkJson.decodeFromString<VoyageEmbeddingModelOptions>(aiSdkJson.encodeToString(voyage)))
        assertEquals(baseten, BasetenEmbeddingModelOptions {
            raw(mapOf("normalize" to JsonPrimitive(true)))
        })
        assertNotEquals(baseten, BasetenEmbeddingModelOptions {
            raw(mapOf("normalize" to JsonPrimitive(false)))
        })
    }

    @Test
    fun `reranking option DSLs keep value semantics`() {
        val cohere = CohereRerankingModelOptions {
            maxTokensPerDoc(400)
            priority(2)
        }
        val voyage = VoyageRerankingModelOptions {
            returnDocuments(true)
            truncation(false)
        }
        val together = TogetherAIRerankingModelOptions {
            rankFields(listOf("title", "body"))
        }

        assertEquals(cohere, CohereRerankingModelOptions {
            maxTokensPerDoc(400)
            priority(2)
        })
        assertEquals(voyage, aiSdkJson.decodeFromString<VoyageRerankingModelOptions>(aiSdkJson.encodeToString(voyage)))
        assertEquals(together, TogetherAIRerankingModelOptions {
            rankFields(listOf("title", "body"))
        })
        assertNotEquals(together, TogetherAIRerankingModelOptions {
            rankFields(listOf("body"))
        })
    }
}
