package ai.torad.aisdk

import ai.torad.aisdk.providers.AlibabaProviderSettings
import ai.torad.aisdk.providers.FalProviderSettings
import ai.torad.aisdk.providers.KlingAIProviderSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class FalKlingAIAlibabaProviderSettingsBuilderTest {
    @Test
    fun `provider settings DSL builders produce value semantic settings`() {
        val fal = FalProviderSettings {
            apiKey("fal-key")
            baseURL("https://fal.test")
            headers(mapOf("X-Provider" to "fal"))
            transcriptionPollIntervalMillis(0)
            transcriptionMaxPollAttempts(2)
            videoPollIntervalMillis(5)
            videoMaxPollAttempts(7)
        }
        val falEqual = FalProviderSettings {
            apiKey("fal-key")
            baseURL("https://fal.test")
            headers(mapOf("X-Provider" to "fal"))
            transcriptionPollIntervalMillis(0)
            transcriptionMaxPollAttempts(2)
            videoPollIntervalMillis(5)
            videoMaxPollAttempts(7)
        }
        val falDifferent = FalProviderSettings {
            apiKey("fal-key")
            baseURL("https://fal.test")
            headers(mapOf("X-Provider" to "fal"))
            transcriptionPollIntervalMillis(1)
            transcriptionMaxPollAttempts(2)
            videoPollIntervalMillis(5)
            videoMaxPollAttempts(7)
        }

        val klingAI = KlingAIProviderSettings {
            accessKey("access")
            secretKey("secret")
            baseURL("https://kling.test")
            headers(mapOf("X-Provider" to "kling"))
        }
        val klingAIEqual = KlingAIProviderSettings {
            accessKey("access")
            secretKey("secret")
            baseURL("https://kling.test")
            headers(mapOf("X-Provider" to "kling"))
        }
        val klingAIDifferent = KlingAIProviderSettings {
            accessKey("access")
            secretKey("other")
            baseURL("https://kling.test")
            headers(mapOf("X-Provider" to "kling"))
        }

        val alibaba = AlibabaProviderSettings {
            baseURL("https://alibaba.test/compatible-mode/v1")
            videoBaseURL("https://dash.test")
            embeddingBaseURL("https://alibaba.test/api/v1")
            apiKey("alibaba-key")
            headers(mapOf("X-Provider" to "alibaba"))
            includeUsage(false)
        }
        val alibabaEqual = AlibabaProviderSettings {
            baseURL("https://alibaba.test/compatible-mode/v1")
            videoBaseURL("https://dash.test")
            embeddingBaseURL("https://alibaba.test/api/v1")
            apiKey("alibaba-key")
            headers(mapOf("X-Provider" to "alibaba"))
            includeUsage(false)
        }
        val alibabaDifferent = AlibabaProviderSettings {
            baseURL("https://alibaba.test/compatible-mode/v1")
            videoBaseURL("https://dash.test")
            embeddingBaseURL("https://alibaba.test/api/v1")
            apiKey("alibaba-key")
            headers(mapOf("X-Provider" to "alibaba"))
            includeUsage(true)
        }

        assertEquals("fal-key", fal.apiKey)
        assertEquals("https://fal.test", fal.baseURL)
        assertEquals(mapOf("X-Provider" to "fal"), fal.headers)
        assertEquals(0L, fal.transcriptionPollIntervalMillis)
        assertEquals(2, fal.transcriptionMaxPollAttempts)
        assertEquals(5L, fal.videoPollIntervalMillis)
        assertEquals(7, fal.videoMaxPollAttempts)
        assertEquals(fal, falEqual)
        assertEquals(fal.hashCode(), falEqual.hashCode())
        assertNotEquals(fal, falDifferent)

        assertEquals("access", klingAI.accessKey)
        assertEquals("secret", klingAI.secretKey)
        assertEquals("https://kling.test", klingAI.baseURL)
        assertEquals(mapOf("X-Provider" to "kling"), klingAI.headers)
        assertEquals(klingAI, klingAIEqual)
        assertEquals(klingAI.hashCode(), klingAIEqual.hashCode())
        assertNotEquals(klingAI, klingAIDifferent)

        assertEquals("https://alibaba.test/compatible-mode/v1", alibaba.baseURL)
        assertEquals("https://dash.test", alibaba.videoBaseURL)
        assertEquals("https://alibaba.test/api/v1", alibaba.embeddingBaseURL)
        assertEquals("alibaba-key", alibaba.apiKey)
        assertEquals(mapOf("X-Provider" to "alibaba"), alibaba.headers)
        assertEquals(false, alibaba.includeUsage)
        assertEquals(alibaba, alibabaEqual)
        assertEquals(alibaba.hashCode(), alibabaEqual.hashCode())
        assertNotEquals(alibaba, alibabaDifferent)
    }
}
