@file:OptIn(LowLevelLanguageModelApi::class)

package ai.torad.aisdk

import ai.torad.aisdk.testing.FlowDrain.drainAllItems
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

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

    @Test
    @Suppress("Indentation", "MaxLineLength", "MaximumLineLength")
    fun `generate preserves approval content parts and usage breakdowns`() = runTest {
        val client = HttpClient(
            MockEngine {
                respond(
                    content =
                        "{" +
                            "\"content\":[" +
                            "{\"type\":\"tool-approval-request\",\"toolCallId\":\"call_1\",\"toolName\":\"send\",\"input\":{\"message\":\"hi\"},\"approvalId\":\"approval_1\",\"signature\":\"sig-1\"}," +
                            "{\"type\":\"tool-approval-response\",\"toolCallId\":\"call_1\",\"approved\":false,\"reason\":\"denied\",\"approvalId\":\"approval_1\"}" +
                            "]," +
                            "\"finishReason\":\"tool-approval-requested\"," +
                            "\"usage\":{\"inputTokens\":10,\"outputTokens\":7,\"cachedInputTokens\":3,\"cacheCreationInputTokens\":2,\"reasoningTokens\":4}" +
                            "}",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )

        val result = gateway(client).languageModel("m").generate(params)

        val request = assertIs<ContentPart.ToolApprovalRequest>(result.content[0])
        val response = assertIs<ContentPart.ToolApprovalResponse>(result.content[1])
        assertEquals("approval_1", request.approvalId)
        assertEquals("sig-1", request.signature)
        assertEquals("approval_1", response.approvalId)
        assertEquals(false, response.approved)
        assertEquals(10, result.usage.inputTokens.total)
        assertEquals(3, result.usage.inputTokens.cacheRead)
        assertEquals(2, result.usage.inputTokens.cacheWrite)
        assertEquals(7, result.usage.outputTokens.total)
        assertEquals(4, result.usage.outputTokens.reasoning)
    }

    @Test
    fun `stream maps approval denied source document and file parts to typed events`() = runTest {
        val body =
            """
            data: {"type":"tool-approval-request","toolCallId":"call_1","toolName":"send","input":{"message":"hi"},"approvalId":"approval_1","signature":"sig-1"}

            data: {"type":"tool-output-denied","toolCallId":"call_1","toolName":"send","approvalId":"approval_1","reason":"denied"}

            data: {"type":"source-document","sourceId":"doc_1","title":"Spec","mediaType":"application/pdf"}

            data: {"type":"file","id":"file_1","mediaType":"text/plain","data":"aGk="}

            data: {"type":"finish","finishReason":"stop"}
            """.trimIndent()
        val events = drainAllItems(gateway(sseClient(body)).languageModel("m").stream(params))

        val approval = assertIs<StreamEvent.ToolApprovalRequest>(events.first { it is StreamEvent.ToolApprovalRequest })
        val denied = assertIs<StreamEvent.ToolOutputDenied>(events.first { it is StreamEvent.ToolOutputDenied })
        val source = assertIs<StreamEvent.SourcePart>(events.first { it is StreamEvent.SourcePart })
        val file = assertIs<StreamEvent.FilePart>(events.first { it is StreamEvent.FilePart })

        assertEquals("approval_1", approval.approvalId)
        assertEquals("approval_1", denied.approvalId)
        assertEquals(StreamEvent.SourcePart.SourceType.Document, source.sourceType)
        assertEquals("application/pdf", source.mediaType)
        assertEquals("text/plain", file.mediaType)
        assertEquals("aGk=", file.base64)
    }

    @Test
    fun `stream maps canonical gateway variants and metadata fields`() = runTest {
        val body =
            """
            data: {"type":"step-start","stepNumber":2,"providerMetadata":{"gateway":{"step":"two"}}}

            data: {"type":"source-url","sourceId":"src_1","url":"https://example.test","title":"Example"}

            data: {"type":"tool-result","toolCallId":"call_1","toolName":"search","output":"partial","preliminary":true,"isError":false,"providerMetadata":{"gateway":{"trace":"t1"}}}

            data: {"type":"tool-output-error","toolCallId":"call_2","toolName":"search","errorText":"boom"}

            data: {"type":"file","id":"file_1","mediaType":"text/plain","url":"https://files.test/a.txt","filename":"a.txt"}

            data: {"type":"abort"}
            """.trimIndent()
        val events = drainAllItems(gateway(sseClient(body)).languageModel("m").stream(params))

        val step = assertIs<StreamEvent.StepStart>(events.first { it is StreamEvent.StepStart })
        val source = assertIs<StreamEvent.SourcePart>(events.first { it is StreamEvent.SourcePart })
        val result = assertIs<StreamEvent.ToolResult>(events.first { it is StreamEvent.ToolResult })
        val error = assertIs<StreamEvent.ToolError>(events.first { it is StreamEvent.ToolError })
        val file = assertIs<StreamEvent.FilePart>(events.first { it is StreamEvent.FilePart })

        assertEquals(2, step.stepNumber)
        assertEquals(StreamEvent.SourcePart.SourceType.Url, source.sourceType)
        assertEquals("https://example.test", source.url)
        assertEquals(true, result.preliminary)
        assertEquals(
            "t1",
            result.providerMetadata.toMap()["gateway"]?.jsonObject?.get("trace")?.jsonPrimitive?.content,
        )
        assertEquals("boom", error.message)
        assertEquals("", file.base64)
        assertEquals(
            "a.txt",
            file.providerMetadata.toMap()["gateway"]?.jsonObject?.get("filename")?.jsonPrimitive?.content,
        )
        assertTrue(events.any { it == StreamEvent.Abort })
    }

    @Test
    fun `metadata endpoints preserve the configured gateway base path`() = runTest {
        val seenPaths = mutableListOf<String>()
        val client = HttpClient(
            MockEngine { request ->
                val path = request.url.encodedPath
                seenPaths += path
                val body = when (path) {
                    "/tenant/v1/credits" -> """{"balance":"100","total_used":"12"}"""
                    "/tenant/v1/report" -> """{"results":[]}"""
                    "/tenant/v1/generation" ->
                        """
                        {"data":{"id":"gen_1","total_cost":1.0,"upstream_inference_cost":0.5,"usage":1.0,"created_at":"2026-06-03T00:00:00Z","model":"m1","is_byok":true,"provider_name":"openai","streamed":false,"finish_reason":"stop","latency":10,"generation_time":20,"native_tokens_prompt":1,"native_tokens_completion":2,"native_tokens_reasoning":0,"native_tokens_cached":0,"native_tokens_cache_creation":0,"billable_web_search_calls":0}}
                        """.trimIndent()
                    else -> throw AssertionError("unexpected path $path")
                }
                respond(
                    content = body,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        val provider = CreateGatewayHttpProvider(
            client,
            GatewayProviderSettings(baseUrl = "https://gateway.test/tenant/v3/ai", apiKey = "key"),
        )

        provider.getCredits()
        provider.getSpendReport(GatewaySpendReportParams(startDate = "2026-06-01", endDate = "2026-06-02"))
        provider.getGenerationInfo(GatewayGenerationInfoParams("gen_1"))

        assertTrue("/tenant/v1/credits" in seenPaths)
        assertTrue("/tenant/v1/report" in seenPaths)
        assertTrue("/tenant/v1/generation" in seenPaths)
    }
}
