package ai.torad.aisdk

import ai.torad.aisdk.providers.mockLanguageModelTextOnly
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest

/**
 * Invariants I-4 / I-9 / R-11 — provider differences live in middleware.
 * No agent-side branching on provider name. The order of middleware
 * execution is documented in [wrapLanguageModel] (outermost first on the
 * way in, innermost first on the way out).
 */
class MiddlewareTest {

    private class TaggingMiddleware(val tag: String, val onIn: MutableList<String>, val onOut: MutableList<String>) :
        LanguageModelMiddleware {
        override suspend fun wrapGenerate(context: MiddlewareCallContext): LanguageModelResult {
            onIn.add(tag)
            val result = context.doGenerate(context.params)
            onOut.add(tag)
            return result
        }

        override fun wrapStream(context: MiddlewareCallContext): Flow<StreamEvent> {
            onIn.add(tag)
            return context.doStream(context.params)
        }
    }

    @Test
    fun `middlewares_compose_outermost_first_on_the_way_in`() = runTest {
        val onIn = mutableListOf<String>()
        val onOut = mutableListOf<String>()
        val outer = TaggingMiddleware("outer", onIn, onOut)
        val inner = TaggingMiddleware("inner", onIn, onOut)
        val wrapped = wrapLanguageModel(mockLanguageModelTextOnly("ok"), listOf(outer, inner))
        wrapped.generate(LanguageModelCallParams(messages = listOf(userMessage("hi"))))
        assertEquals(listOf("outer", "inner"), onIn)
        assertEquals(listOf("inner", "outer"), onOut)
    }

    @Test
    fun `zero_middlewares_returns_inner_model_unchanged`() {
        val inner = mockLanguageModelTextOnly("untouched")
        val wrapped = wrapLanguageModel(inner, emptyList())
        assertEquals(inner.modelId, wrapped.modelId)
    }
}
