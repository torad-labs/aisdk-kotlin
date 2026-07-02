@file:OptIn(LowLevelLanguageModelApi::class)

package ai.torad.aisdk

import ai.torad.aisdk.providers.OpenResponses
import ai.torad.aisdk.providers.OpenResponsesProviderSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class OpenResponsesProviderOptionsTest {

    @Test
    fun `provider options reject unknown keys instead of silently dropping typos`() = runTest {
        val client = HttpClient(MockEngine { throw AssertionError("request should fail before network") })
        val provider = OpenResponses(
            client,
            OpenResponsesProviderSettings {
                url("https://api.test/v1/responses")
                name("openresponses")
            },
        )

        assertFailsWith<InvalidArgumentError> {
            provider.responses("gpt-resp").generate(
                LanguageModelCallParams {
                    messages(listOf(UserMessage("hi")))
                    providerOptions(
                        ProviderOptions.Raw(
                            JsonObject(
                                mapOf(
                                    "openresponses" to buildJsonObject {
                                        put("previousReponseId", JsonPrimitive("resp_1"))
                                    },
                                ),
                            ),
                        )
                    )
                },
            )
        }
    }

    @Test
    fun `open-responses provider options alias still decodes known keys`() = runTest {
        val seenBodies = mutableListOf<JsonObject>()
        val client = HttpClient(
            MockEngine { request ->
                seenBodies += Json.parseToJsonElement(requestBodyText(request)).jsonObject
                respond(
                    content = """
                        {
                          "id":"resp_1",
                          "output":[{"type":"message","content":[{"type":"output_text","text":"ok"}]}]
                        }
                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType to listOf("application/json")),
                )
            },
        )
        val provider = OpenResponses(
            client,
            OpenResponsesProviderSettings {
                url("https://api.test/v1/responses")
                name("openresponses")
            },
        )

        provider.responses("gpt-resp").generate(
            LanguageModelCallParams {
                messages(listOf(UserMessage("hi")))
                providerOptions(
                    ProviderOptions.Raw(
                        JsonObject(
                            mapOf(
                                "open-responses" to buildJsonObject {
                                    put("previousResponseId", JsonPrimitive("resp_prev"))
                                },
                            ),
                        ),
                    )
                )
            },
        )

        val previousResponseId = assertNotNull(seenBodies.single()["previous_response_id"])
        assertEquals("resp_prev", previousResponseId.jsonPrimitive.content)
    }

    private fun requestBodyText(request: HttpRequestData): String =
        when (val body = request.body) {
            is OutgoingContent.ByteArrayContent -> body.bytes().decodeToString()
            is OutgoingContent.NoContent -> ""
            else -> body.toString()
        }
}
