package ai.torad.aisdk

import ai.torad.aisdk.providers.AssemblyAIProviderSettings
import ai.torad.aisdk.providers.DeepgramProviderSettings
import ai.torad.aisdk.providers.LumaProviderSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class DeepgramAssemblyAILumaProviderSettingsBuilderTest {
    @Test
    fun `provider settings DSL builders produce value semantic settings`() {
        val deepgram = DeepgramProviderSettings {
            apiKey("deepgram-key")
            headers(mapOf("X-Provider" to "deepgram"))
        }
        val deepgramEqual = DeepgramProviderSettings {
            apiKey("deepgram-key")
            headers(mapOf("X-Provider" to "deepgram"))
        }
        val deepgramDifferent = DeepgramProviderSettings {
            apiKey("other")
            headers(mapOf("X-Provider" to "deepgram"))
        }

        val assemblyAI = AssemblyAIProviderSettings {
            apiKey("assembly-key")
            headers(mapOf("X-Provider" to "assembly"))
            pollingIntervalMillis(0)
            maxPollAttempts(3)
        }
        val assemblyAIEqual = AssemblyAIProviderSettings {
            apiKey("assembly-key")
            headers(mapOf("X-Provider" to "assembly"))
            pollingIntervalMillis(0)
            maxPollAttempts(3)
        }
        val assemblyAIDifferent = AssemblyAIProviderSettings {
            apiKey("assembly-key")
            headers(mapOf("X-Provider" to "assembly"))
            pollingIntervalMillis(1)
            maxPollAttempts(3)
        }

        val luma = LumaProviderSettings {
            apiKey("luma-key")
            baseURL("https://luma.test")
            headers(mapOf("X-Provider" to "luma"))
        }
        val lumaEqual = LumaProviderSettings {
            apiKey("luma-key")
            baseURL("https://luma.test")
            headers(mapOf("X-Provider" to "luma"))
        }
        val lumaDifferent = LumaProviderSettings {
            apiKey("luma-key")
            baseURL("https://other.luma.test")
            headers(mapOf("X-Provider" to "luma"))
        }

        assertEquals("deepgram-key", deepgram.apiKey)
        assertEquals(mapOf("X-Provider" to "deepgram"), deepgram.headers)
        assertEquals(deepgram, deepgramEqual)
        assertEquals(deepgram.hashCode(), deepgramEqual.hashCode())
        assertNotEquals(deepgram, deepgramDifferent)

        assertEquals("assembly-key", assemblyAI.apiKey)
        assertEquals(mapOf("X-Provider" to "assembly"), assemblyAI.headers)
        assertEquals(0L, assemblyAI.pollingIntervalMillis)
        assertEquals(3, assemblyAI.maxPollAttempts)
        assertEquals(assemblyAI, assemblyAIEqual)
        assertEquals(assemblyAI.hashCode(), assemblyAIEqual.hashCode())
        assertNotEquals(assemblyAI, assemblyAIDifferent)

        assertEquals("luma-key", luma.apiKey)
        assertEquals("https://luma.test", luma.baseURL)
        assertEquals(mapOf("X-Provider" to "luma"), luma.headers)
        assertEquals(luma, lumaEqual)
        assertEquals(luma.hashCode(), lumaEqual.hashCode())
        assertNotEquals(luma, lumaDifferent)
    }
}
