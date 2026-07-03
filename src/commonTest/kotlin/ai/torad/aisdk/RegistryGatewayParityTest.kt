@file:OptIn(LowLevelLanguageModelApi::class)

package ai.torad.aisdk

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class RegistryGatewayParityTest {
    private class StubProvider(override val providerId: String, private val tag: String) : Provider {
        override fun languageModel(modelId: String): LanguageModel = object : LanguageModel {
            override val modelId = "$tag:$modelId"
            override suspend fun generate(params: LanguageModelCallParams) =
                LanguageModelResult(text = modelId, finishReason = FinishReason.Stop, usage = Usage())
            override fun stream(params: LanguageModelCallParams) = kotlinx.coroutines.flow.emptyFlow<StreamEvent>()
        }
    }

    @Test
    fun `createProviderRegistry honors a custom separator`() {
        val registry = ProviderRegistry.createProviderRegistry(
            mapOf("openai" to StubProvider("openai", "oa")),
            separator = "/",
        )
        // "openai/gpt" splits on "/" → provider "openai", local id "gpt".
        assertEquals("oa:gpt", registry.languageModel("openai/gpt").modelId)
    }

    @Test
    fun `splitProviderModelId supports a multi-char separator`() {
        assertEquals("openai" to "gpt-4o", ProviderRegistry.splitProviderModelId("openai::gpt-4o", separator = "::"))
        assertEquals(null to "bare", ProviderRegistry.splitProviderModelId("bare", separator = "::"))
    }

    @Test
    fun `gateway falls back to AI_GATEWAY_API_KEY from the environment when apiKey is null`() = runTest {
        val token = GatewayAuthToken.fromSettings(
            GatewayProviderSettings {
                environment(mapOf("AI_GATEWAY_API_KEY" to "env-key"))
            }
        )
        assertEquals("env-key", token?.token)
        assertEquals(GatewayAuthMethod.ApiKey, token?.authMethod)
    }

    @Test
    fun `explicit gateway apiKey takes precedence over the environment`() = runTest {
        val token = GatewayAuthToken.fromSettings(
            GatewayProviderSettings {
                apiKey("explicit")
                environment(mapOf("AI_GATEWAY_API_KEY" to "env-key"))
            }
        )
        assertEquals("explicit", token?.token)
    }

    @Test
    fun `gateway falls back to VERCEL_OIDC_TOKEN as the oidc auth method`() = runTest {
        val token = GatewayAuthToken.fromSettings(
            GatewayProviderSettings {
                environment(mapOf("VERCEL_OIDC_TOKEN" to "oidc-tok"))
            }
        )
        assertEquals("oidc-tok", token?.token)
        assertEquals(GatewayAuthMethod.Oidc, token?.authMethod)
    }

    @Test
    fun `blank AI_GATEWAY_API_KEY falls back to OIDC`() = runTest {
        val token = GatewayAuthToken.fromSettings(
            GatewayProviderSettings {
                environment(mapOf("AI_GATEWAY_API_KEY" to "", "VERCEL_OIDC_TOKEN" to "oidc-tok"))
            },
        )
        assertEquals("oidc-tok", token?.token)
        assertEquals(GatewayAuthMethod.Oidc, token?.authMethod)
    }

    @Test
    fun `api key wins over the OIDC token when both are present`() = runTest {
        val token = GatewayAuthToken.fromSettings(
            GatewayProviderSettings {
                environment(mapOf("AI_GATEWAY_API_KEY" to "k", "VERCEL_OIDC_TOKEN" to "oidc-tok"))
            }
        )
        assertEquals("k", token?.token)
        assertEquals(GatewayAuthMethod.ApiKey, token?.authMethod)
    }

    @Test
    fun `gateway emits ai-o11y headers from the VERCEL environment`() = runTest {
        val headers = GatewayProviderSettings {
            environment(mapOf("VERCEL_ENV" to "production", "VERCEL_REGION" to "iad1"))
        }.gatewayHeaders()
        assertEquals("production", headers["ai-o11y-environment"])
        assertEquals("iad1", headers["ai-o11y-region"])
    }
}
