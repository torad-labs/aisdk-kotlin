package ai.torad.aisdk

import ai.torad.aisdk.middleware.LoggingMiddleware
import ai.torad.aisdk.providers.MockLanguageModelTextOnly
import ai.torad.aisdk.providers.OpenAICompatible
import ai.torad.aisdk.providers.OpenAICompatibleProviderSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/** Compiles and executes the provider wiki snippets against the real provider APIs. */
class ProvidersDocSnippetTest {
    private fun noNetworkClient(): HttpClient = HttpClient(
        MockEngine {
            error("Provider documentation snippets should not make network requests.")
        },
    )

    @Test
    fun `providers wiki gateway and openai-compatible snippets compile`() {
        val gatewayApiKey = "gateway-key"
        val client = noNetworkClient()
        val localApiKey = "local-key"

        val gatewayProvider = Gateway(
            GatewayProviderSettings {
                apiKey(gatewayApiKey)
                transport(KtorGatewayTransport(client))
            },
        )

        val gatewayModel = gatewayProvider.languageModel("anthropic/claude-sonnet-4.5")

        val provider = OpenAICompatible(
            client = client,
            settings = OpenAICompatibleProviderSettings {
                name("local")
                baseUrl("http://localhost:11434/v1")
                apiKey(localApiKey)
                includeUsage(true)
            },
        )

        val model = provider.chatModel("llama3.2")

        assertEquals("gateway", gatewayProvider.providerId)
        assertEquals("anthropic/claude-sonnet-4.5", gatewayModel.modelId)
        assertEquals("local", provider.providerId)
        assertEquals("llama3.2", model.modelId)
    }

    @Test
    fun `providers wiki litert params registry and custom provider snippets compile and run`() = runTest {
        val params = LanguageModelCallParams {
            messages(listOf(UserMessage("Plan the next step.")))
            providerOptions(
                ProviderOptions.ofPairs(
                    "litert" to buildJsonObject {
                        put("enableThinking", JsonPrimitive(true))
                        put("extraContext", buildJsonObject {
                            put("screen", JsonPrimitive("home"))
                        })
                    },
                ),
            )
        }

        val gatewayProvider = Provider(
            providerId = "gateway",
            languageModels = mapOf("anthropic/claude-sonnet-4.5" to MockLanguageModelTextOnly("gateway ok")),
        )
        val openaiProvider = Provider(
            providerId = "openai",
            languageModels = mapOf("gpt-4o-mini" to MockLanguageModelTextOnly("openai ok")),
        )
        val registry = ProviderRegistry(
            providers = mapOf(
                "gateway" to gatewayProvider,
                "openai" to openaiProvider,
            ),
            defaultProviderId = "gateway",
            languageModelMiddleware = listOf(LoggingMiddleware(NoopLogger)),
        )

        val model = registry.languageModel("gateway:anthropic/claude-sonnet-4.5")
        val provider = Provider(
            providerId = "test",
            languageModels = mapOf("small" to MockLanguageModelTextOnly("ok")),
        )

        val registryText = TextGenerator(model).generate(GenerationInput.Prompt("hello")).first().text
        val customText = TextGenerator(provider.languageModel("small"))
            .generate(GenerationInput.Prompt("hello"))
            .first()
            .text

        assertEquals("Plan the next step.", params.messages.single().content.single().let {
            (it as ContentPart.Text).text
        })
        assertEquals(
            "home",
            params.providerOptions.toMap().getValue("litert").jsonObject
                .getValue("extraContext").jsonObject
                .getValue("screen").jsonPrimitive.content,
        )
        assertEquals("gateway ok", registryText)
        assertEquals("ok", customText)
    }
}
