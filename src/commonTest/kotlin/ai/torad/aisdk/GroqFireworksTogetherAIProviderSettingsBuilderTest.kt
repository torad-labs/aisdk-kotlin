package ai.torad.aisdk

import ai.torad.aisdk.providers.FireworksProviderSettings
import ai.torad.aisdk.providers.GroqProviderSettings
import ai.torad.aisdk.providers.TogetherAIProviderSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class GroqFireworksTogetherAIProviderSettingsBuilderTest {
    @Test
    fun `provider settings DSL builders produce value semantic settings`() {
        val groq = GroqProviderSettings {
            apiKey("groq-key")
            baseURL("https://groq.test/openai/v1")
            headers(mapOf("X-Provider" to "groq"))
        }
        val groqEqual = GroqProviderSettings {
            apiKey("groq-key")
            baseURL("https://groq.test/openai/v1")
            headers(mapOf("X-Provider" to "groq"))
        }
        val groqDifferent = GroqProviderSettings {
            apiKey("other")
            baseURL("https://groq.test/openai/v1")
            headers(mapOf("X-Provider" to "groq"))
        }

        val fireworks = FireworksProviderSettings {
            apiKey("fireworks-key")
            baseURL("https://fireworks.test/inference/v1")
            headers(mapOf("X-Provider" to "fireworks"))
        }
        val fireworksEqual = FireworksProviderSettings {
            apiKey("fireworks-key")
            baseURL("https://fireworks.test/inference/v1")
            headers(mapOf("X-Provider" to "fireworks"))
        }
        val fireworksDifferent = FireworksProviderSettings {
            apiKey("fireworks-key")
            baseURL("https://other.fireworks.test/inference/v1")
            headers(mapOf("X-Provider" to "fireworks"))
        }

        val togetherAI = TogetherAIProviderSettings {
            apiKey("together-key")
            baseURL("https://together.test/v1")
            headers(mapOf("X-Provider" to "together"))
        }
        val togetherAIEqual = TogetherAIProviderSettings {
            apiKey("together-key")
            baseURL("https://together.test/v1")
            headers(mapOf("X-Provider" to "together"))
        }
        val togetherAIDifferent = TogetherAIProviderSettings {
            apiKey("together-key")
            baseURL("https://together.test/v1")
            headers(mapOf("X-Provider" to "other"))
        }

        assertEquals("groq-key", groq.apiKey)
        assertEquals("https://groq.test/openai/v1", groq.baseURL)
        assertEquals(mapOf("X-Provider" to "groq"), groq.headers)
        assertEquals(groq, groqEqual)
        assertEquals(groq.hashCode(), groqEqual.hashCode())
        assertNotEquals(groq, groqDifferent)

        assertEquals("fireworks-key", fireworks.apiKey)
        assertEquals("https://fireworks.test/inference/v1", fireworks.baseURL)
        assertEquals(mapOf("X-Provider" to "fireworks"), fireworks.headers)
        assertEquals(fireworks, fireworksEqual)
        assertEquals(fireworks.hashCode(), fireworksEqual.hashCode())
        assertNotEquals(fireworks, fireworksDifferent)

        assertEquals("together-key", togetherAI.apiKey)
        assertEquals("https://together.test/v1", togetherAI.baseURL)
        assertEquals(mapOf("X-Provider" to "together"), togetherAI.headers)
        assertEquals(togetherAI, togetherAIEqual)
        assertEquals(togetherAI.hashCode(), togetherAIEqual.hashCode())
        assertNotEquals(togetherAI, togetherAIDifferent)
    }
}
