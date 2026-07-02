package ai.torad.aisdk

import ai.torad.aisdk.providers.OpenAICompatible
import ai.torad.aisdk.providers.OpenAICompatibleProviderSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlin.test.Test
import kotlin.test.assertEquals

/** Compiles the JVM provider on-ramp snippet with the documented CIO engine. */
class ProvidersJvmDocSnippetTest {
    @Test
    fun `providers wiki openai-compatible cio snippet compiles`() {
        val client = HttpClient(CIO)
        try {
            val apiKey = "test-key"

            val provider = OpenAICompatible(
                client = client,
                settings = OpenAICompatibleProviderSettings {
                    name("openai-compatible")
                    baseUrl("https://api.openai.com/v1")
                    apiKey(apiKey)
                },
            )

            val model = provider.chatModel("gpt-4o-mini")

            assertEquals("openai-compatible", provider.providerId)
            assertEquals("gpt-4o-mini", model.modelId)
        } finally {
            client.close()
        }
    }
}
