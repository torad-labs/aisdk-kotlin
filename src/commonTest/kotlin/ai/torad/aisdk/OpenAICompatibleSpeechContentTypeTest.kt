package ai.torad.aisdk

import ai.torad.aisdk.providers.OpenAICompatible
import ai.torad.aisdk.providers.OpenAICompatibleProviderSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class OpenAICompatibleSpeechContentTypeTest {
    /**
     * Regression: the speech model read the response Content-Type via a case-sensitive
     * map lookup (`response.headers["Content-Type"]`). `flattenedHeaders()` preserves the
     * server's key casing, and HTTP/2 lowercases all header names, so the lookup always
     * missed on HTTP/2 and silently fell back to the format-derived media type. The fix
     * uses the case-insensitive `headerValue()` helper. Here the server returns a lowercase
     * `content-type` whose value differs from the `responseFormat` fallback ("audio/wav"),
     * so a case-sensitive miss would surface as the wrong media type.
     */
    @Test
    fun `speech model reads lowercase content-type header case-insensitively`() = runTest {
        val client = HttpClient(
            MockEngine {
                respond(
                    content = "fake-audio-bytes",
                    status = HttpStatusCode.OK,
                    headers = headersOf("content-type", "audio/aac"),
                )
            },
        )
        val provider = OpenAICompatible(
            client,
            OpenAICompatibleProviderSettings(
                name = "openai",
                baseUrl = "https://api.test/v1",
                apiKey = "secret",
            ),
        )

        val result = provider.speechModel("tts-1")
            .generate(SpeechGenerationParams(text = "hello", responseFormat = "wav"))

        assertEquals("audio/aac", result.audio?.mediaType)
    }
}
