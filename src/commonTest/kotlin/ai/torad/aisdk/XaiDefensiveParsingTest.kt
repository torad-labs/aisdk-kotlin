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
import kotlin.test.assertFails
import kotlin.test.assertTrue

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
        val provider = Xai(client, XaiProviderSettings { apiKey("key") })

        val result = provider.image(ModelId("grok-2-image")).generate(
            ImageGenerationParams {
                prompt("x")
                n(1)
            },
        )

        assertEquals(1, result.images.size, "a non-primitive revised_prompt degrades to omitted metadata, no crash")
    }

    /**
     * Regression (the M4 sibling-accessor bug-class, Wave 7): xaiErrorMessage navigates
     * `error?.jsonObject?.get("message")` — `?.jsonObject` throws if `error` is present but a
     * primitive (`{"error":"plain string"}`), crashing BEFORE the leaf cast. The safe
     * `(error as? JsonObject)?.…` degrades to null -> the `error as? JsonPrimitive` fallback.
     */
    @Test
    fun `image generate surfaces the structured error on a primitive error`() = runTest {
        val client = HttpClient(
            MockEngine {
                respond(
                    content = """{"error":"plain string"}""",
                    status = HttpStatusCode.BadRequest,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        val provider = Xai(client, XaiProviderSettings { apiKey("key") })

        val error = assertFails {
            provider.image(ModelId("grok-2-image")).generate(
                ImageGenerationParams {
                    prompt("x")
                    n(1)
                }
            )
        }

        assertTrue(
            error.message?.contains("xAI request failed") == true,
            "a primitive error degrades through the object accessor, no crash",
        )
    }

    /**
     * Regression (Wave 7b, array-element accessor): the image parser mapped each `data` element via
     * the non-null `image.jsonObject`, throwing ISE on a non-object element — and `xaiImageMetadata`
     * mapped the same array. The safe `image as? JsonObject ?: return@mapNotNull null` (and the
     * in-place `(image as? JsonObject)?.get(...)` in metadata) drops the malformed entry; valid survive.
     */
    @Test
    fun `image generate drops a malformed data element instead of crashing`() = runTest {
        val client = HttpClient(
            MockEngine {
                respond(
                    content = """{"data":[{"b64_json":"aW1n"},"malformed"]}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        val result = Xai(client, XaiProviderSettings { apiKey("key") })
            .image(ModelId("grok-2-image"))
            .generate(
                ImageGenerationParams {
                    prompt("x")
                    n(1)
                }
            )
        assertEquals(1, result.images.size)
    }
}
