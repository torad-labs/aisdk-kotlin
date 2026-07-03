@file:OptIn(LowLevelLanguageModelApi::class)

package ai.torad.aisdk

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class RegistryCustomProviderR5Test {
    private fun stubModel(id: String) = object : LanguageModel {
        override val modelId = id
        override suspend fun generate(params: LanguageModelCallParams) =
            LanguageModelResult(text = id, finishReason = FinishReason.Stop, usage = Usage())
        override fun stream(params: LanguageModelCallParams): Flow<StreamEvent> = emptyFlow()
    }

    private fun providerWith(id: String, model: LanguageModel) = object : Provider {
        override val providerId = id
        override fun languageModel(modelId: String) = model
    }

    @Test
    fun `customProvider falls back to the fallback provider on a miss`() {
        val fallback = providerWith("fb", stubModel("fb/model"))
        val provider = Provider(providerId = "custom", fallbackProvider = fallback)
        // "anything" is not in custom's maps → resolves via fallback rather than throwing.
        assertEquals("fb/model", provider.languageModel("anything").modelId)
    }

    @Test
    fun `registry applies registry-level middleware to resolved language models`() {
        val mw = object : LanguageModelMiddleware {
            override fun overrideModelId(model: LanguageModel) = "wrapped"
        }
        val registry = ProviderRegistry.createProviderRegistry(
            mapOf("p" to providerWith("p", stubModel("p/inner"))),
            languageModelMiddleware = listOf(mw),
        )
        assertEquals("wrapped", registry.languageModel("p:m").modelId, "middleware override applied")
    }

    @Test
    fun `convertToLanguageModelPrompt merges consecutive tool messages`() = runTest {
        val messages = listOf(
            ModelMessage(
                MessageRole.Assistant,
                listOf(
                    ContentPart.ToolCall("c1", "t", JsonObject(emptyMap())),
                    ContentPart.ToolCall("c2", "t", JsonObject(emptyMap())),
                ),
            ),
            ModelMessage(MessageRole.Tool, listOf(ContentPart.ToolResult("c1", "t", JsonPrimitive("a")))),
            ModelMessage(MessageRole.Tool, listOf(ContentPart.ToolResult("c2", "t", JsonPrimitive("b")))),
        )
        val converted = PromptConversion.convertToLanguageModelPrompt(messages)
        // The two tool messages collapse into one with both results.
        val toolMessages = converted.filter { it.role == MessageRole.Tool }
        assertEquals(1, toolMessages.size)
        assertEquals(2, toolMessages.single().content.size)
    }
}
