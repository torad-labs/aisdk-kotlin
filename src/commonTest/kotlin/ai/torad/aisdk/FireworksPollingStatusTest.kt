package ai.torad.aisdk

import ai.torad.aisdk.providers.Fireworks
import ai.torad.aisdk.providers.FireworksProviderSettings
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FireworksPollingStatusTest {
    @Test
    fun `Pending retries and Ready returns its validated sample`() = runTest {
        val fixture = fireworksFixture(
            listOf(
                buildJsonObject { put("status", JsonPrimitive("Pending")) },
                readyResponse(SAME_ORIGIN_IMAGE_URL),
            ),
        )

        val result = fixture.model.generateOne()

        assertEquals(Base64Codec.encode(IMAGE_BYTES), result.images.single().base64)
        val polls = fixture.server.calls.filter { it.requestUrl == POLL_URL }
        assertEquals(2, polls.size)
        assertTrue(polls.all { it.requestMethod == "POST" })
        assertTrue(
            polls.all {
                it.requestBodyJson.jsonObject["id"]?.jsonPrimitive?.contentOrNull == "req1"
            },
        )

        val download = fixture.server.calls.single { it.requestUrl == SAME_ORIGIN_IMAGE_URL }
        assertEquals("Bearer key", download.requestHeaders.headerValue(HttpHeaders.Authorization))
        assertEquals("secondary", download.requestHeaders.headerValue("x-api-key"))
    }

    @Test
    fun `Ready does not send credentials to a cross-origin sample`() = runTest {
        val fixture = fireworksFixture(
            pollResponses = listOf(readyResponse(CROSS_ORIGIN_IMAGE_URL)),
            imageUrl = CROSS_ORIGIN_IMAGE_URL,
        )

        fixture.model.generateOne()

        assertEquals(1, fixture.server.calls.count { it.requestUrl == POLL_URL })
        val download = fixture.server.calls.single { it.requestUrl == CROSS_ORIGIN_IMAGE_URL }
        assertNull(download.requestHeaders.headerValue(HttpHeaders.Authorization))
        assertNull(download.requestHeaders.headerValue("x-api-key"))
    }

    @Test
    fun `Ready requires a string result sample without another poll`() = runTest {
        val responses = listOf(
            buildJsonObject {
                put("status", JsonPrimitive("Ready"))
                put("result", buildJsonObject {})
            },
            buildJsonObject {
                put("status", JsonPrimitive("Ready"))
                put("result", buildJsonObject { put("sample", JsonPrimitive(42)) })
            },
        )

        for (response in responses) {
            val fixture = fireworksFixture(listOf(response))

            val error = assertFailsWith<InvalidResponseDataError> {
                fixture.model.generateOne()
            }

            assertTrue(error.message.orEmpty().contains("missing result.sample"))
            assertEquals(1, fixture.server.calls.count { it.requestUrl == POLL_URL })
            assertEquals(2, fixture.server.calls.size)
            assertEquals(0, fixture.server.calls.count { it.requestUrl == SAME_ORIGIN_IMAGE_URL })
        }
    }

    @Test
    fun `Error and Failed preserve immediate API call errors`() = runTest {
        for (status in listOf("Error", "Failed")) {
            val fixture = fireworksFixture(
                listOf(buildJsonObject { put("status", JsonPrimitive(status)) }),
            )

            val error = assertFailsWith<APICallError> {
                fixture.model.generateOne()
            }

            assertEquals(POLL_URL, error.url)
            assertTrue(error.message.orEmpty().contains(status))
            assertEquals(1, fixture.server.calls.count { it.requestUrl == POLL_URL })
            assertEquals(2, fixture.server.calls.size)
        }
    }

    @Test
    fun `missing null non-string and non-object statuses fail as malformed after one poll`() = runTest {
        val responses = listOf(
            Json.parseToJsonElement("{}"),
            Json.parseToJsonElement("""{"status":null}"""),
            buildJsonObject { put("status", JsonPrimitive(123)) },
            Json.parseToJsonElement("""{"status":true}"""),
            Json.parseToJsonElement("""{"status":[]}"""),
            buildJsonObject { put("status", buildJsonObject { put("phase", JsonPrimitive("Pending")) }) },
            Json.parseToJsonElement("[]"),
            Json.parseToJsonElement("null"),
            Json.parseToJsonElement("\"payload\""),
            Json.parseToJsonElement("123"),
            Json.parseToJsonElement("true"),
        )

        for (response in responses) {
            val fixture = fireworksFixture(listOf(response))

            val error = assertFailsWith<InvalidResponseDataError> {
                fixture.model.generateOne()
            }

            assertEquals("Fireworks poll response is missing a string status", error.message)
            assertEquals(response, error.data)
            assertEquals(1, fixture.server.calls.count { it.requestUrl == POLL_URL })
            assertEquals(2, fixture.server.calls.size)
            assertEquals(0, fixture.server.calls.count { it.requestUrl == SAME_ORIGIN_IMAGE_URL })
        }
    }

    @Test
    fun `unknown and blank string statuses preserve the raw value without retrying`() = runTest {
        for (status in listOf("Queued", "", " ")) {
            val response = buildJsonObject { put("status", JsonPrimitive(status)) }
            val fixture = fireworksFixture(listOf(response))

            val error = assertFailsWith<InvalidResponseDataError> {
                fixture.model.generateOne()
            }

            assertTrue(error.message.orEmpty().contains("unknown status: '$status'"))
            assertEquals(response, error.data)
            assertEquals(1, fixture.server.calls.count { it.requestUrl == POLL_URL })
            assertEquals(2, fixture.server.calls.size)
        }
    }

    private fun fireworksFixture(
        pollResponses: List<JsonElement>,
        imageUrl: String = SAME_ORIGIN_IMAGE_URL,
    ): FireworksFixture {
        var pollResponseIndex = 0
        val server = TestServer.createTestServer(
            mutableMapOf(
                SUBMIT_URL to UrlHandler(
                    UrlResponse.JsonValue(Json.parseToJsonElement("""{"request_id":"req1"}""")),
                ),
                POLL_URL to UrlHandler {
                    val response = pollResponses.getOrElse(pollResponseIndex) { pollResponses.last() }
                    pollResponseIndex++
                    UrlResponse.JsonValue(response)
                },
                imageUrl to UrlHandler(
                    UrlResponse.Binary(IMAGE_BYTES, headers = mapOf(HttpHeaders.ContentType to "image/png")),
                ),
            ),
        )
        server.server.start()
        val model = Fireworks(
            server.httpClient(),
            FireworksProviderSettings {
                apiKey("key")
                baseURL(BASE_URL)
                headers(mapOf("X-Api-Key" to "secondary"))
            },
        ).image(ModelId(MODEL_ID))
        return FireworksFixture(server, model)
    }

    private suspend fun ImageModel.generateOne(): ImageModelResult =
        generate(ImageGenerationParams { prompt("x") })

    private fun readyResponse(imageUrl: String): JsonElement = buildJsonObject {
        put("status", JsonPrimitive("Ready"))
        put("result", buildJsonObject { put("sample", JsonPrimitive(imageUrl)) })
    }

    private fun Map<String, String>.headerValue(name: String): String? =
        entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    private data class FireworksFixture(
        val server: CreatedTestServer,
        val model: ImageModel,
    )

    private companion object {
        const val BASE_URL = "https://fireworks.test"
        const val MODEL_ID = "accounts/fireworks/models/flux-kontext-pro"
        const val SUBMIT_URL = "$BASE_URL/workflows/$MODEL_ID"
        const val POLL_URL = "$SUBMIT_URL/get_result"
        const val SAME_ORIGIN_IMAGE_URL = "$BASE_URL/image.png"
        const val CROSS_ORIGIN_IMAGE_URL = "https://cdn.example/image.png"
        val IMAGE_BYTES = byteArrayOf(1, 2, 3)
    }
}
