package ai.torad.aisdk

import ai.torad.aisdk.middleware.defaultSettingsMiddleware
import ai.torad.aisdk.middleware.extractReasoningMiddleware
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MiddlewareBugfixTest {
    private fun model(result: LanguageModelResult) = object : LanguageModel {
        override val modelId = "m"
        var seen: LanguageModelCallParams? = null
        override suspend fun generate(params: LanguageModelCallParams): LanguageModelResult {
            seen = params
            return result
        }
        override fun stream(params: LanguageModelCallParams): Flow<StreamEvent> = emptyFlow()
    }

    @Test
    fun `extractReasoning rebuilds content - reasoning part added and text cleaned`() = runTest {
        val inner = model(
            LanguageModelResult(
                text = "before<reasoning>because</reasoning>after",
                finishReason = FinishReason.Stop,
                usage = Usage(),
            ),
        )
        val wrapped = wrapLanguageModel(inner, listOf(extractReasoningMiddleware()))
        val result = wrapped.generate(LanguageModelCallParams(messages = listOf(userMessage("hi"))))

        // text is cleaned of the tag
        assertEquals("beforeafter", result.text)
        // content carries a Reasoning part and the cleaned Text — no stale tagged text
        assertEquals("because", result.content.filterIsInstance<ContentPart.Reasoning>().single().text)
        assertTrue(result.content.filterIsInstance<ContentPart.Text>().none { "<reasoning>" in it.text })
    }

    @Test
    fun `defaultSettingsMiddleware deep-merges providerOptions per provider key`() = runTest {
        // default sets openai.reasoningEffort; per-call sets openai.user — both must survive.
        val defaults = mapOf(
            "openai" to buildJsonObject { put("reasoningEffort", JsonPrimitive("high")) } as JsonObject,
        )
        val inner = model(LanguageModelResult(text = "ok", finishReason = FinishReason.Stop, usage = Usage()))
        val wrapped = wrapLanguageModel(inner, listOf(defaultSettingsMiddleware(providerOptions = defaults)))
        wrapped.generate(
            LanguageModelCallParams(
                messages = listOf(userMessage("hi")),
                providerOptions = mapOf(
                    "openai" to buildJsonObject { put("user", JsonPrimitive("u1")) } as JsonObject,
                ),
            ),
        )
        val merged = inner.seen?.providerOptions?.get("openai")?.jsonObject
        assertEquals("high", merged?.get("reasoningEffort")?.let { (it as JsonPrimitive).content }, "default key kept")
        assertEquals("u1", merged?.get("user")?.let { (it as JsonPrimitive).content }, "per-call key kept")
    }
}
