package ai.torad.aisdk

import ai.torad.aisdk.providers.Xai
import ai.torad.aisdk.providers.XaiProviderSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class XaiDefensiveParsingTest {
    /**
     * Regression (the M4 bug-class): the xAI image parser read `revised_prompt` via
     * `?.jsonPrimitive?.contentOrNull` while building provider metadata, which throws on a
     * present-but-non-primitive field — failing the whole generate(). The safe
     * `(X as? JsonPrimitive)?.…` degrades to null -> the field is simply omitted from metadata.
     * The model is private, so this drives it through the public provider.
     *
     * (The chat error path routes through the shared OpenAI-compatible extractor, not this file,
     * so it is covered by the openai-compatible-core batch — poisoning it here would test the
     * wrong layer.)
     */
    @Test
    fun `image generate degrades a non-primitive revised_prompt to omitted metadata`() = runTest {
        val client = HttpClient(
            MockEngine {
                respond(
                    content = """{"data":[{"b64_json":"aW1n","revised_prompt":{"oops":1}}]}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        val provider = Xai(client, XaiProviderSettings(apiKey = "key"))

        val result = provider.image(ModelId("grok-2-image")).generate(
            ImageGenerationParams(prompt = "x", n = 1),
        )

        assertEquals(1, result.images.size, "a non-primitive revised_prompt degrades to omitted metadata, no crash")
    }
}
