package ai.torad.aisdk

import ai.torad.aisdk.providers.AzureOpenAIProviderSettings
import ai.torad.aisdk.providers.BasetenProviderSettings
import ai.torad.aisdk.providers.OpenAIProviderSettings
import ai.torad.aisdk.providers.XaiProviderSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class OpenAIAzureXaiProviderSettingsBuilderTest {
    @Test
    fun `provider settings DSL builders produce value semantic settings`() {
        val openAI = OpenAIProviderSettings {
            apiKey("openai-key")
            baseURL("https://openai.test/v1")
            organization("org")
            project("project")
            headers(mapOf("X-Provider" to "openai"))
            queryParams(mapOf("api-version" to "2026-06-03"))
            includeUsage(true)
        }
        val openAIEqual = OpenAIProviderSettings {
            apiKey("openai-key")
            baseURL("https://openai.test/v1")
            organization("org")
            project("project")
            headers(mapOf("X-Provider" to "openai"))
            queryParams(mapOf("api-version" to "2026-06-03"))
            includeUsage(true)
        }
        val openAIDifferent = OpenAIProviderSettings {
            apiKey("other")
            baseURL("https://openai.test/v1")
            organization("org")
            project("project")
            headers(mapOf("X-Provider" to "openai"))
            queryParams(mapOf("api-version" to "2026-06-03"))
            includeUsage(true)
        }

        val azure = AzureOpenAIProviderSettings {
            resourceName("resource")
            apiKey("azure-key")
            headers(mapOf("X-Provider" to "azure"))
            apiVersion("2025-04-01-preview")
            useDeploymentBasedUrls(true)
        }
        val azureEqual = AzureOpenAIProviderSettings {
            resourceName("resource")
            apiKey("azure-key")
            headers(mapOf("X-Provider" to "azure"))
            apiVersion("2025-04-01-preview")
            useDeploymentBasedUrls(true)
        }
        val azureDifferent = AzureOpenAIProviderSettings {
            resourceName("other")
            apiKey("azure-key")
            headers(mapOf("X-Provider" to "azure"))
            apiVersion("2025-04-01-preview")
            useDeploymentBasedUrls(true)
        }

        val xai = XaiProviderSettings {
            apiKey("xai-key")
            baseURL("https://xai.test/v1")
            headers(mapOf("X-Provider" to "xai"))
        }
        val xaiEqual = XaiProviderSettings {
            apiKey("xai-key")
            baseURL("https://xai.test/v1")
            headers(mapOf("X-Provider" to "xai"))
        }
        val xaiDifferent = XaiProviderSettings {
            apiKey("xai-key")
            baseURL("https://other.xai.test/v1")
            headers(mapOf("X-Provider" to "xai"))
        }

        val baseten = BasetenProviderSettings {
            apiKey("baseten-key")
            baseURL("https://baseten.test/v1")
            modelURL("https://model.example/sync/v1")
            headers(mapOf("X-Provider" to "baseten"))
        }
        val basetenEqual = BasetenProviderSettings {
            apiKey("baseten-key")
            baseURL("https://baseten.test/v1")
            modelURL("https://model.example/sync/v1")
            headers(mapOf("X-Provider" to "baseten"))
        }
        val basetenDifferent = BasetenProviderSettings {
            apiKey("baseten-key")
            baseURL("https://baseten.test/v1")
            modelURL("https://other.example/sync/v1")
            headers(mapOf("X-Provider" to "baseten"))
        }

        assertEquals("openai-key", openAI.apiKey)
        assertEquals("https://openai.test/v1", openAI.baseURL)
        assertEquals("org", openAI.organization)
        assertEquals("project", openAI.project)
        assertEquals(mapOf("X-Provider" to "openai"), openAI.headers)
        assertEquals(mapOf("api-version" to "2026-06-03"), openAI.queryParams)
        assertEquals(true, openAI.includeUsage)
        assertEquals(openAI, openAIEqual)
        assertEquals(openAI.hashCode(), openAIEqual.hashCode())
        assertNotEquals(openAI, openAIDifferent)

        assertEquals("resource", azure.resourceName)
        assertEquals("azure-key", azure.apiKey)
        assertEquals(mapOf("X-Provider" to "azure"), azure.headers)
        assertEquals("2025-04-01-preview", azure.apiVersion)
        assertEquals(true, azure.useDeploymentBasedUrls)
        assertEquals(azure, azureEqual)
        assertEquals(azure.hashCode(), azureEqual.hashCode())
        assertNotEquals(azure, azureDifferent)

        assertEquals("xai-key", xai.apiKey)
        assertEquals("https://xai.test/v1", xai.baseURL)
        assertEquals(mapOf("X-Provider" to "xai"), xai.headers)
        assertEquals(xai, xaiEqual)
        assertEquals(xai.hashCode(), xaiEqual.hashCode())
        assertNotEquals(xai, xaiDifferent)

        assertEquals("baseten-key", baseten.apiKey)
        assertEquals("https://baseten.test/v1", baseten.baseURL)
        assertEquals("https://model.example/sync/v1", baseten.modelURL)
        assertEquals(mapOf("X-Provider" to "baseten"), baseten.headers)
        assertEquals(baseten, basetenEqual)
        assertEquals(baseten.hashCode(), basetenEqual.hashCode())
        assertNotEquals(baseten, basetenDifferent)
    }
}
