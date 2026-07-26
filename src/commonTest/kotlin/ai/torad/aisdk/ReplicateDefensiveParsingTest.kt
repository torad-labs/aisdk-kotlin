package ai.torad.aisdk

import ai.torad.aisdk.providers.Replicate
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ReplicateDefensiveParsingTest {
    /**
     * Regression (the M4 bug-class): replicateErrorMessage probed `detail` via
     * `?.jsonPrimitive?.contentOrNull`, which throws on a present-but-non-primitive field — so
     * building the error message for a 4xx crashed with IllegalArgumentException. The safe
     * `(X as? JsonPrimitive)?.…` degrades to null -> the raw-body fallback.
     */
    @Test
    fun `image generate surfaces the structured error on a non-primitive detail`() = runTest {
        val client = HttpClient(
            MockEngine {
                respond(
                    content = """{"detail":{"oops":1}}""",
                    status = HttpStatusCode.BadRequest,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        val model = Replicate(client).image(ModelId("owner/model:version-123"))

        val error = assertFails {
            model.generate(
                ImageGenerationParams {
                    prompt("x")
                    n(1)
                }
            )
        }

        assertTrue(
            error.message?.contains("Replicate request failed") == true,
            "the structured Replicate error is built, not an IllegalArgumentException from jsonPrimitive",
        )
    }

    @Test
    fun `video generate rejects missing status without polling`() = runTest {
        assertInvalidVideoStatus(
            response = """{"output":"https://cdn.example/video.mp4",""" +
                """"urls":{"get":"https://api.replicate.com/v1/predictions/pred1"}}""",
            expectedMessage = "Replicate prediction response is missing status",
        )
    }

    @Test
    fun `video generate rejects non-string status without polling`() = runTest {
        assertInvalidVideoStatus(
            response = """{"status":42,"output":"https://cdn.example/video.mp4",""" +
                """"urls":{"get":"https://api.replicate.com/v1/predictions/pred1"}}""",
            expectedMessage = "Replicate prediction response status is not a string",
        )
    }

    @Test
    fun `video generate rejects unknown status without consuming output`() = runTest {
        assertInvalidVideoStatus(
            response = """{"status":"queued","output":"https://cdn.example/video.mp4",""" +
                """"urls":{"get":"https://api.replicate.com/v1/predictions/pred1"}}""",
            expectedMessage = "Replicate prediction response has unknown status: queued",
            expectedStatus = "queued",
        )
        assertInvalidVideoStatus(
            response = """{"status":"future-status","output":"https://cdn.example/video.mp4"}""",
            expectedMessage = "Replicate prediction response has unknown status: future-status",
            expectedStatus = "future-status",
        )
    }

    private suspend fun assertInvalidVideoStatus(
        response: String,
        expectedMessage: String,
        expectedStatus: String? = null,
    ) {
        var requestCount = 0
        val client = HttpClient(
            MockEngine {
                requestCount++
                respond(
                    content = response,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        val model = Replicate(client).video(ModelId("minimax/video-01"))

        val error = assertFailsWith<InvalidResponseDataError> {
            model.generate(
                VideoGenerationParams {
                    prompt("x")
                },
            )
        }

        assertEquals(expectedMessage, error.message)
        expectedStatus?.let { assertEquals(JsonPrimitive(it), error.data) }
        assertEquals(1, requestCount)
    }
}
