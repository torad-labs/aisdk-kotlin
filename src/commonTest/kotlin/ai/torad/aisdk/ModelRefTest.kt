package ai.torad.aisdk

import ai.torad.aisdk.providers.MockLanguageModelTextOnly
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class ModelRefTest {

    @Test
    fun `model ref parses provider qualified names`() {
        val ref = ModelRef("openai:gpt-5")

        assertEquals(ProviderId("openai"), ref.providerId)
        assertEquals(ModelId("gpt-5"), ref.modelId)
        assertEquals("openai:gpt-5", ref.qualifiedName)
    }

    @Test
    fun `provider registry resolves typed model references`() {
        val model = MockLanguageModelTextOnly("ok")
        val registry = ProviderRegistry(
            "openai" to Provider(
                providerId = "openai",
                languageModels = mapOf("gpt-5" to model),
            )
        )

        assertSame(model, registry.languageModel(ModelRef("openai:gpt-5")))
    }

    @Test
    fun `direct provider resolves local and matching typed references`() {
        val model = MockLanguageModelTextOnly("ok")
        val provider = Provider(
            providerId = "openai",
            languageModels = mapOf("gpt-5" to model),
        )

        assertSame(model, provider.languageModel(ModelId("gpt-5")))
        assertSame(model, provider.languageModel(ModelRef("openai:gpt-5")))
    }

    @Test
    fun `direct provider rejects mismatched typed provider reference`() {
        val provider = Provider(providerId = "openai")

        assertFailsWith<NoSuchProviderError> {
            provider.languageModel(ModelRef("anthropic:claude"))
        }
    }

    @Test
    fun `provider registry resolves ModelRef via typed components not colon qualifiedName`() {
        // ModelRef.qualifiedName hardcodes `:`. A registry with separator="/" must still
        // resolve typed refs from providerId + modelId directly — never by re-parsing
        // "openai:gpt-5" as a single local id.
        val model = MockLanguageModelTextOnly("ok")
        val registry = ProviderRegistry(
            mapOf(
                "openai" to Provider(
                    providerId = "openai",
                    languageModels = mapOf("gpt-5" to model),
                ),
            ),
            separator = "/",
        )

        assertSame(model, registry.languageModel(ModelRef("openai", "gpt-5")))
        assertSame(model, registry.languageModel(ModelRef(ProviderId("openai"), ModelId("gpt-5"))))
        // Stringly path still honors the custom separator.
        assertSame(model, registry.languageModel("openai/gpt-5"))
    }
}
