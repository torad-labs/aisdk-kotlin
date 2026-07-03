package ai.torad.aisdk

import ai.torad.aisdk.providers.HuggingFaceProviderSettings
import ai.torad.aisdk.providers.OpenAICompatibleProviderSettings
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DeferredProviderSettingsBuilderTest {
    @Test
    fun `OpenAICompatibleProviderSettings DSL preserves function fields`() {
        val authHeadersProvider: suspend () -> Map<String, String> = { mapOf("authorization" to "Bearer token") }
        val urlBuilder: (path: String, modelId: String) -> String = { path, modelId -> "$modelId::$path" }
        val transformChatRequestBody: (JsonObject) -> JsonObject = { it }
        val convertUsage: (JsonElement?) -> Usage = { Usage(raw = it) }
        val transformChatResponse: (JsonObject) -> JsonObject = { it }

        val settings = OpenAICompatibleProviderSettings {
            name("compatible")
            baseUrl("https://compatible.test/v1")
            apiKey("key")
            authHeadersProvider(authHeadersProvider)
            urlBuilder(urlBuilder)
            transformChatRequestBody(transformChatRequestBody)
            convertUsage(convertUsage)
            transformChatResponse(transformChatResponse)
        }

        assertEquals("compatible", settings.name)
        assertEquals("https://compatible.test/v1", settings.baseUrl)
        assertEquals("key", settings.apiKey)
        assertTrue(settings.authHeadersProvider === authHeadersProvider)
        assertTrue(settings.urlBuilder === urlBuilder)
        assertTrue(settings.transformChatRequestBody === transformChatRequestBody)
        assertTrue(settings.convertUsage === convertUsage)
        assertTrue(settings.transformChatResponse === transformChatResponse)
    }

    @Test
    fun `GatewayProviderSettings DSL preserves transport and provider functions`() {
        val transport = object : GatewayTransport {}
        val nowMillis: () -> Long = { 42L }
        val authTokenProvider: suspend () -> GatewayAuthToken? = {
            GatewayAuthToken("oidc-token", GatewayAuthMethod.Oidc)
        }

        val settings = GatewayProviderSettings {
            baseUrl("https://gateway.test/v3/ai")
            apiKey("key")
            transport(transport)
            metadataCacheRefreshMillis(123L)
            nowMillis(nowMillis)
            authTokenProvider(authTokenProvider)
            environment(mapOf("VERCEL_ENV" to "preview"))
        }

        assertEquals("https://gateway.test/v3/ai", settings.baseUrl)
        assertEquals("key", settings.apiKey)
        assertTrue(settings.transport === transport)
        assertEquals(123L, settings.metadataCacheRefreshMillis)
        assertTrue(settings.nowMillis === nowMillis)
        assertTrue(settings.authTokenProvider === authTokenProvider)
        assertEquals(mapOf("VERCEL_ENV" to "preview"), settings.environment)
    }

    @Test
    fun `function-bearing settings keep identity equality`() {
        val generateId: () -> String = { "generated-id" }
        val first = HuggingFaceProviderSettings(block = {
            apiKey("key")
            baseURL("https://hf.test/v1")
            generateId(generateId)
        })
        val second = HuggingFaceProviderSettings(block = {
            apiKey("key")
            baseURL("https://hf.test/v1")
            generateId(generateId)
        })

        assertEquals(first, first)
        assertNotEquals(first, second)
        assertEquals("generated-id", first.generateId())
        assertTrue(first.generateId === generateId)
    }
}
