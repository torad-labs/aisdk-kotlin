@file:OptIn(LowLevelLanguageModelApi::class)

package ai.torad.aisdk
import ai.torad.aisdk.providers.GoogleGenerativeAI
import ai.torad.aisdk.providers.GoogleGenerativeAIProviderSettings
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GoogleInteractionsPollingTest {
    @Test
    fun `background generate reports the polled response body and headers not the pre-poll POST`() = runTest {
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://google.test/v1beta/interactions" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """{"id":"interaction-bg","model":"gemini-2.5-flash","status":"in_progress"}""",
                        ),
                        headers = mapOf("x-gemini-service-tier" to "post-tier"),
                    ),
                ),
                "https://google.test/v1beta/interactions/interaction-bg" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """
                            {
                              "id":"interaction-bg",
                              "model":"gemini-2.5-flash",
                              "status":"completed",
                              "steps":[
                                {"type":"model_output","content":[{"type":"text","text":"Polled answer."}]}
                              ]
                            }
                            """.trimIndent(),
                        ),
                        headers = mapOf("x-gemini-service-tier" to "polled-tier"),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val provider = GoogleGenerativeAI(
            fixture.httpClient(),
            GoogleGenerativeAIProviderSettings {
                apiKey("key")
                baseURL("https://google.test/v1beta")
            },
        )

        val result = provider.interactions(ModelId("gemini-2.5-flash")).generate(
            LanguageModelCallParams {
                messages(listOf(UserMessage("Research this.")))
            },
        )

        assertEquals("Polled answer.", result.text)
        // Metadata must describe the response the result was built from, not the half-done
        // POST envelope that preceded polling (upstream reassigns response, rawResponse and
        // responseHeaders from the poll).
        val body = assertIs<JsonObject>(result.response.body)
        assertEquals("completed", body["status"]?.jsonPrimitive?.contentOrNull)
        assertTrue(body.containsKey("steps"), "polled body must carry the terminal steps, got: $body")
        assertEquals("polled-tier", result.response.headers.headerValue("x-gemini-service-tier"))
    }

    private fun Map<String, String>.headerValue(name: String): String? =
        entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}
