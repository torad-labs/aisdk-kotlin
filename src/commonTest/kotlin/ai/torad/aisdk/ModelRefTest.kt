package ai.torad.aisdk

import ai.torad.aisdk.providers.mockLanguageModelTextOnly
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class ModelRefTest {

    @Test
    fun `model ref parses provider qualified names`() {
        val ref = modelRef("openai:gpt-5")

        assertEquals(ProviderId("openai"), ref.providerId)
        assertEquals(ModelId("gpt-5"), ref.modelId)
        assertEquals("openai:gpt-5", ref.qualifiedName)
    }

    @Test
    fun `provider registry resolves typed model references`() {
        val model = mockLanguageModelTextOnly("ok")
        val registry = createProviderRegistry(
            "openai" to customProvider(
                providerId = "openai",
                languageModels = mapOf("gpt-5" to model),
            ),
        )

        assertSame(model, registry.languageModel(modelRef("openai:gpt-5")))
    }

    @Test
    fun `direct provider resolves local and matching typed references`() {
        val model = mockLanguageModelTextOnly("ok")
        val provider = customProvider(
            providerId = "openai",
            languageModels = mapOf("gpt-5" to model),
        )

        assertSame(model, provider.languageModel(ModelId("gpt-5")))
        assertSame(model, provider.languageModel(modelRef("openai:gpt-5")))
    }

    @Test
    fun `direct provider rejects mismatched typed provider reference`() {
        val provider = customProvider(providerId = "openai")

        assertFailsWith<NoSuchProviderError> {
            provider.languageModel(modelRef("anthropic:claude"))
        }
    }
}
