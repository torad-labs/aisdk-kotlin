package ai.torad.aisdk

import ai.torad.aisdk.testing.FlowDrain.drainAllItems
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Re-audit regressions for [KtorGatewayTransport]'s gateway language-model paths.
 * Each test pins one CONFIRMED bug fixed in the re-audit pass.
 */
class GatewayReauditTest {
    private val params = LanguageModelCallParams(
        listOf(UserMessage("hi")),
        headers = mapOf("x-call-header" to "call-value"),
    )

    private fun gateway(client: HttpClient): GatewayProvider =
        CreateGatewayHttpProvider(client, GatewayProviderSettings(apiKey = "key"))

    private fun jsonClient(capture: (String?) -> Unit): HttpClient =
        HttpClient(
            MockEngine { request ->
                capture(request.headers["x-call-header"])
                respond(
                    content = """{"text":"hi"}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )

    private fun sseClient(body: String, capture: (String?) -> Unit = {}): HttpClient =
        HttpClient(
            MockEngine { request ->
                capture(request.headers["x-call-header"])
                respond(
                    content = body,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
                )
            },
        )

    /** Bug: generateText dropped per-call params.headers, sending only the spec/model headers. */
    @Test
    fun `generateText forwards per-call params headers`() = runTest {
        var seen: String? = null
        gateway(jsonClient { seen = it }).languageModel("m").generate(params)
        assertEquals("call-value", seen)
    }

    /** Bug: streamText dropped per-call params.headers. */
    @Test
    fun `streamText forwards per-call params headers`() = runTest {
        var seen: String? = null
        val client = sseClient("""data: {"type":"finish"}""") { seen = it }
        drainAllItems(gateway(client).languageModel("m").stream(params))
        assertEquals("call-value", seen)
    }

    /** Bug: the 'error' event decoded the absent `message` field instead of the `error` payload. */
    @Test
    fun `stream error event renders the error field`() = runTest {
        val client = sseClient("""data: {"type":"error","error":"provider exploded"}""")
        val events = drainAllItems(gateway(client).languageModel("m").stream(params))
        val error = events.filterIsInstance<StreamEvent.Error>().single()
        assertEquals("provider exploded", error.message)
    }

    /** Bug: an ISO-8601 timestamp string was read as an epoch-seconds double and silently dropped. */
    @Test
    fun `response-metadata parses an ISO-8601 timestamp string`() = runTest {
        val body = """data: {"type":"response-metadata","id":"r1","timestamp":"2024-01-01T00:00:00Z"}"""
        val events = drainAllItems(gateway(sseClient(body)).languageModel("m").stream(params))
        // forwardSseEvents prepends a synthetic header-only ResponseMetadata; pick the in-band one.
        val meta = events.filterIsInstance<StreamEvent.ResponseMetadata>().single { it.id == "r1" }
        assertEquals(1_704_067_200_000L, meta.timestampMillis)
    }
}
