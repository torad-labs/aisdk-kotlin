package ai.torad.aisdk

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class GatewayDefensiveParsingTest {
    private fun gateway(body: String): GatewayProvider {
        val client = HttpClient(
            MockEngine {
                respond(
                    content = body,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        return CreateGatewayHttpProvider(client, GatewayProviderSettings(apiKey = "key"))
    }

    /**
     * Regression (the M4 bug-class, Wave 6): getCredits read `balance`/`total_used` via
     * `X?.jsonPrimitive?.contentOrNull`, which throws on a present-but-non-primitive field — `.jsonPrimitive`
     * throws even through `?.`. The safe `(X as? JsonPrimitive)?.…` degrades to the "" default.
     */
    @Test
    fun `getCredits degrades to default on non-primitive fields instead of crashing`() = runTest {
        val credits = gateway("""{"balance":{"oops":1},"total_used":[1,2]}""").getCredits()
        assertEquals("", credits.balance)
        assertEquals("", credits.totalUsed)
    }

    /**
     * Regression (the M4 bug-class, Wave 6): the image result mapped `images[]` via the direct
     * `it.jsonPrimitive.content`, crashing if any array element is a non-primitive. The safe
     * `(it as? JsonPrimitive)?.content.orEmpty()` degrades that element to an empty base64.
     */
    @Test
    fun `generateImage degrades a non-primitive image entry to empty base64 instead of crashing`() = runTest {
        val result = gateway("""{"images":[{"oops":1}]}""")
            .image(ModelId("test-model"))
            .generate(ImageGenerationParams(prompt = "x", n = 1))
        assertEquals(1, result.images.size)
        assertEquals("", result.images.first().base64)
    }
}
