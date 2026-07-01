package ai.torad.aisdk

import ai.torad.aisdk.providers.OpenAICompatible
import ai.torad.aisdk.providers.OpenAICompatibleProviderSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class CallConfigHeadersTest {
    @Test
    fun `TextGenerator CallConfig headers reach outgoing OpenAI-compatible request`() = runTest {
        val seenHeaders = mutableListOf<Map<String, List<String>>>()
        val client = HttpClient(
            MockEngine { request ->
                seenHeaders += request.headers.entries().associate { it.key to it.value }
                respond(
                    content = """{"id":"chat_1","model":"gpt-test","choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":1}}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        val provider = OpenAICompatible(
            client,
            OpenAICompatibleProviderSettings {
                name("openai")
                baseUrl("https://api.test/v1")
                apiKey("secret")
            },
        )

        val result = TextGenerator(
            provider.languageModel("gpt-test"),
            CallConfig {
                headers(mapOf("X-Trace-Id" to "trace-123"))
            },
        ).generate(GenerationInput.Prompt("hi")).first()

        assertEquals("ok", result.text)
        assertEquals("trace-123", seenHeaders.single().headerValue("X-Trace-Id"))
    }

    private fun Map<String, List<String>>.headerValue(name: String): String? =
        entries.firstOrNull { (key, _) -> key.equals(name, ignoreCase = true) }?.value?.singleOrNull()
}
