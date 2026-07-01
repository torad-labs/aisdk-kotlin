package ai.torad.aisdk

import ai.torad.aisdk.JSONRPCMessage.Companion.toJsonElement
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource

@OptIn(ExperimentalAiSdkApi::class, ExperimentalCoroutinesApi::class, InternalAiSdkApi::class)
class McpSseTransportTest : MCPClientTestBase() {

    @Test
    fun `SSE MCP transport discovers endpoint posts messages and receives server messages`() = runTest {
        val serverNotification = """{"jsonrpc":"2.0","method":"notifications/server"}"""
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://mcp.test/sse" to UrlHandler(
                    UrlResponse.StreamChunks(
                        listOf(
                            "event: endpoint\ndata: /messages\n\n",
                            "event: message\ndata: $serverNotification\n\n",
                        ),
                    ),
                ),
                "https://mcp.test/messages" to UrlHandler(UrlResponse.Empty(status = 202)),
            ),
        )
        fixture.server.start()
        val transport = SseMCPTransport(fixture.httpClient(), "https://mcp.test/sse")
        var received: JSONRPCMessage? = null
        transport.setOnMessage { received = it }

        transport.start()
        waitForRealTime { received != null }
        transport.send(JSONRPCNotification(method = "notifications/test"))

        assertEquals("POST", fixture.calls.single { it.requestUrl == "https://mcp.test/messages" }.requestMethod)
        val notification = assertIs<JSONRPCNotification>(received)
        assertEquals("notifications/server", notification.method)
        transport.close()
    }

    @Test
    fun `SSE MCP transport retries start GET after authorized auth`() = runTest {
        var sseAttempts = 0
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://mcp.test/sse" to UrlHandler(
                    { _, _ ->
                        if (sseAttempts++ == 0) {
                            UrlResponse.Error(status = 401, body = "unauthorized")
                        } else {
                            UrlResponse.StreamChunks(listOf("event: endpoint\ndata: /messages\n\n"))
                        }
                    },
                ),
                "https://mcp.test/.well-known/oauth-protected-resource/sse" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """{"resource":"https://mcp.test/sse","authorization_servers":["https://auth.mcp.test"]}""",
                        ),
                    ),
                ),
                "https://auth.mcp.test/.well-known/oauth-authorization-server" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """
                            {
                              "authorization_endpoint":"https://auth.mcp.test/authorize",
                              "token_endpoint":"https://auth.mcp.test/token",
                              "grant_types_supported":["refresh_token"],
                              "token_endpoint_auth_methods_supported":["client_secret_post"]
                            }
                            """.trimIndent(),
                        ),
                    ),
                ),
                "https://auth.mcp.test/token" to UrlHandler(
                    { request, _ ->
                        assertTrue(request.body.contains("grant_type=refresh_token"))
                        assertTrue(request.body.contains("refresh_token=refresh-token"))
                        UrlResponse.JsonValue(Json.parseToJsonElement("""{"access_token":"refreshed","token_type":"Bearer"}"""))
                    },
                ),
                "https://mcp.test/messages" to UrlHandler(UrlResponse.Empty(status = 202)),
            ),
        )
        fixture.server.start()
        val authProvider = MemoryOAuthProvider(
            tokens = OAuthTokens(accessToken = "stale-token", tokenType = "Bearer", refreshToken = "refresh-token"),
            clientInformation = OAuthClientInformation {
                clientId("client-id")
                clientSecret("client-secret")
            },
        )
        val transport = SseMCPTransport(fixture.httpClient(), "https://mcp.test/sse", authProvider = authProvider)

        transport.start()
        transport.close()

        val sseGets = fixture.calls.filter { it.requestUrl == "https://mcp.test/sse" && it.requestMethod == "GET" }
        assertEquals(2, sseGets.size)
        assertEquals("Bearer refreshed", sseGets[1].requestHeaders.headerValue(HttpHeaders.Authorization))
    }

    @Test
    fun `SSE reader survives a malformed message and still processes the next one`() = runTest {
        // Regression: a per-message JSONRPCMessage.fromJson throw used to propagate out of
        // parseStream into the reader's OUTER catch -> onError + the reader EXITS (and for SSE,
        // onClose tears down the whole connection). One bad inbound frame killed the reader.
        val good = """{"jsonrpc":"2.0","method":"notifications/server"}"""
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://mcp.test/sse" to UrlHandler(
                    UrlResponse.StreamChunks(
                        listOf(
                            "event: endpoint\ndata: /messages\n\n",
                            "event: message\ndata: {not valid json\n\n", // fromJson throws here
                            "event: message\ndata: $good\n\n", // must STILL be delivered
                        ),
                    ),
                ),
                "https://mcp.test/messages" to UrlHandler(UrlResponse.Empty(status = 202)),
            ),
        )
        fixture.server.start()
        val transport = SseMCPTransport(fixture.httpClient(), "https://mcp.test/sse")
        var received: JSONRPCMessage? = null
        var errored: Throwable? = null
        transport.setOnMessage { received = it }
        transport.setOnError { errored = it }

        transport.start()
        waitForRealTime { received != null } // pre-fix: never arrives (reader died) -> times out

        assertEquals("notifications/server", assertIs<JSONRPCNotification>(received).method)
        assertNotNull(errored, "the malformed frame is surfaced as a non-fatal error, not a teardown")
        transport.close()
    }
}
