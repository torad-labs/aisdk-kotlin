package ai.torad.aisdk

import ai.torad.aisdk.providers.BlackForestLabs
import ai.torad.aisdk.providers.BlackForestLabsProviderSettings
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BlackForestLabsPollingStatusTest {
    @Test
    fun `named terminal statuses fail after one poll`() = runTest {
        val statuses = listOf(
            "Error",
            "Failed",
            "Content Moderated",
            "Request Moderated",
            "Task not found",
        )

        for (status in statuses) {
            val fixture = bflFixture(
                buildJsonObject { put("status", JsonPrimitive(status)) },
            )

            val error = assertFailsWith<NoImageGeneratedError> {
                fixture.model.generateOne()
            }

            assertTrue(error.message.orEmpty().contains(status))
            assertEquals(1, fixture.server.calls.count { it.requestUrl == POLL_URL })
            assertEquals(2, fixture.server.calls.size)
        }
    }

    @Test
    fun `missing status fails as malformed after one poll`() = runTest {
        val fixture = bflFixture(Json.parseToJsonElement("{}"))

        val error = assertFailsWith<InvalidResponseDataError> {
            fixture.model.generateOne()
        }

        assertTrue(error.message.orEmpty().contains("Missing status"))
        assertEquals(1, fixture.server.calls.count { it.requestUrl == POLL_URL })
        assertEquals(2, fixture.server.calls.size)
    }

    @Test
    fun `unknown string remains retryable until timeout`() = runTest {
        val fixture = bflFixture(
            buildJsonObject { put("status", JsonPrimitive("Queued")) },
        )

        val error = assertFailsWith<NoImageGeneratedError> {
            fixture.model.generateOne()
        }

        assertTrue(error.message.orEmpty().contains("timed out"))
        assertEquals(3, fixture.server.calls.count { it.requestUrl == POLL_URL })
        assertEquals(4, fixture.server.calls.size)
    }

    private fun bflFixture(pollResponse: JsonElement): BflFixture {
        val server = TestServer.createTestServer(
            mutableMapOf(
                SUBMIT_URL to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """{"id":"req1","polling_url":"$POLL_BASE_URL"}""",
                        ),
                    ),
                ),
                POLL_URL to UrlHandler(UrlResponse.JsonValue(pollResponse)),
            ),
        )
        server.server.start()
        val model = BlackForestLabs(
            server.httpClient(),
            BlackForestLabsProviderSettings {
                apiKey("key")
                baseURL(BASE_URL)
                pollIntervalMillis(1)
                pollTimeoutMillis(3)
            },
        ).image(ModelId(MODEL_ID))
        return BflFixture(server, model)
    }

    private suspend fun ImageModel.generateOne(): ImageModelResult =
        generate(ImageGenerationParams { prompt("x") })

    private data class BflFixture(
        val server: CreatedTestServer,
        val model: ImageModel,
    )

    private companion object {
        const val BASE_URL = "https://bfl.test/v1"
        const val MODEL_ID = "flux-pro-1.1"
        const val SUBMIT_URL = "$BASE_URL/$MODEL_ID"
        const val POLL_BASE_URL = "$BASE_URL/poll"
        const val POLL_URL = "$POLL_BASE_URL?id=req1"
    }
}
