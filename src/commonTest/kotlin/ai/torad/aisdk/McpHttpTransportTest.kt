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
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalAiSdkApi::class, ExperimentalCoroutinesApi::class, InternalAiSdkApi::class)
class McpHttpTransportTest : MCPClientTestBase() {

    @Test
    fun `HTTP MCP transport performs initialize post session headers and tool list`() = runTest {
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://mcp.test/mcp" to UrlHandler(
                    { request, _ ->
                        when {
                            request.method == "GET" -> UrlResponse.Error(status = 405, body = "GET not supported")
                            "\"method\":\"initialize\"" in request.body -> UrlResponse.JsonValue(
                                JSONRPCResponse(id = JsonPrimitive(0), result = initializeResult()).toJsonElement(),
                                headers = mapOf("mcp-session-id" to "session-1"),
                            )
                            "\"method\":\"notifications/initialized\"" in request.body -> UrlResponse.Empty(status = 202)
                            "\"method\":\"tools/list\"" in request.body ->
                                UrlResponse.JsonValue(JSONRPCResponse(id = JsonPrimitive(1), result = listToolsResult()).toJsonElement())
                            else -> UrlResponse.Error(status = 500, body = "unexpected request: ${request.body}")
                        }
                    },
                ),
            ),
        )
        fixture.server.start()

        val client = CreateMCPClient(
            MCPClientConfig {
                transport(
                    HttpMCPTransport(
                        client = fixture.httpClient(),
                        url = "https://mcp.test/mcp",
                        headers = mapOf("X-Test" to "transport"),
                    )
                )
            },
        )

        assertEquals("fixture-server", client.serverInfo.name)
        assertEquals("echo", client.listTools().tools.single().name)
        waitForRealTime { fixture.calls.count { it.requestMethod == "POST" } >= 3 }
        val initialize = fixture.calls.first { it.requestBodyText.contains("\"method\":\"initialize\"") }
        val initialized = fixture.calls.first { it.requestBodyText.contains(
            "\"method\":\"notifications/initialized\""
        ) }
        val listTools = fixture.calls.first { it.requestBodyText.contains("\"method\":\"tools/list\"") }
        assertEquals("POST", initialize.requestMethod)
        assertEquals("transport", initialize.requestHeaders.headerValue("X-Test"))
        assertEquals(LATEST_PROTOCOL_VERSION, initialize.requestHeaders.headerValue("mcp-protocol-version"))
        assertEquals("session-1", initialized.requestHeaders.headerValue("mcp-session-id"))
        assertEquals("session-1", listTools.requestHeaders.headerValue("mcp-session-id"))
    }

    @Test
    fun `HTTP MCP transport single-flights concurrent reauthorization after 401`() = runTest {
        val parallelism = 5
        val tokenController = TestResponseController()
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://mcp.test/mcp" to UrlHandler(
                    { request, _ ->
                        when {
                            request.method == "GET" -> UrlResponse.Error(status = 405, body = "GET not supported")
                            "\"method\":\"initialize\"" in request.body -> UrlResponse.JsonValue(
                                JSONRPCResponse(
                                    id = Json.parseToJsonElement(request.body).jsonObject["id"] ?: JsonPrimitive(0),
                                    result = initializeResult(),
                                ).toJsonElement(),
                            )
                            "\"method\":\"notifications/initialized\"" in request.body -> UrlResponse.Empty(status = 202)
                            "\"method\":\"tools/list\"" in request.body &&
                                request.headers.headerValue(HttpHeaders.Authorization) == "Bearer stale-token" ->
                                UrlResponse.Error(status = 401, body = "unauthorized")
                            "\"method\":\"tools/list\"" in request.body &&
                                request.headers.headerValue(HttpHeaders.Authorization) == "Bearer refreshed-token" ->
                                UrlResponse.JsonValue(
                                    JSONRPCResponse(
                                        id = Json.parseToJsonElement(request.body).jsonObject["id"] ?: JsonPrimitive(0),
                                        result = listToolsResult(),
                                    ).toJsonElement(),
                                )
                            else -> UrlResponse.Error(status = 500, body = "unexpected request: ${request.body}")
                        }
                    },
                ),
                "https://mcp.test/.well-known/oauth-protected-resource/mcp" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """{"resource":"https://mcp.test/mcp","authorization_servers":["https://auth.mcp.test"]}""",
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
                        UrlResponse.ControlledStream(
                            tokenController,
                            headers = mapOf(HttpHeaders.ContentType to "application/json"),
                        )
                    },
                ),
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
        val client = CreateMCPClient(
            MCPClientConfig {
                transport(HttpMCPTransport(fixture.httpClient(), "https://mcp.test/mcp", authProvider = authProvider))
            },
        )
        val gate = CompletableDeferred<Unit>()
        val requests = List(parallelism) {
            async(Dispatchers.Default) {
                gate.await()
                client.listTools()
            }
        }

        gate.complete(Unit)
        waitForRealTime {
            fixture.calls.count {
                it.requestUrl == "https://mcp.test/mcp" &&
                    it.requestMethod == "POST" &&
                    it.requestBodyText.contains("\"method\":\"tools/list\"") &&
                    it.requestHeaders.headerValue(HttpHeaders.Authorization) == "Bearer stale-token"
            } == parallelism &&
                fixture.calls.count { it.requestUrl == "https://auth.mcp.test/token" } == 1
        }
        tokenController.write(
            """{"access_token":"refreshed-token","token_type":"Bearer","refresh_token":"refresh-token"}"""
        )
        tokenController.close()

        requests.awaitAll().forEach { assertEquals("echo", it.tools.single().name) }

        assertEquals(1, fixture.calls.count { it.requestUrl == "https://auth.mcp.test/token" })
        assertEquals(
            parallelism,
            fixture.calls.count {
                it.requestUrl == "https://mcp.test/mcp" &&
                    it.requestMethod == "POST" &&
                    it.requestBodyText.contains("\"method\":\"tools/list\"") &&
                    it.requestHeaders.headerValue(HttpHeaders.Authorization) == "Bearer refreshed-token"
            },
        )
        client.close()
    }

    @Test
    fun `HTTP inbound SSE retries once after authorized auth on 401`() = runTest {
        val notification = JSONRPCNotification(method = "notifications/server").toJsonElement()
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://mcp.test/mcp" to UrlHandler(
                    { request, _ ->
                        when {
                            request.method == "GET" &&
                                request.headers.headerValue(HttpHeaders.Authorization) == "Bearer stale-token" ->
                                UrlResponse.Error(status = 401, body = "unauthorized")
                            request.method == "GET" &&
                                request.headers.headerValue(HttpHeaders.Authorization) == "Bearer refreshed-token" ->
                                UrlResponse.StreamChunks(listOf("event: message\ndata: $notification\n\n"))
                            request.method == "POST" -> UrlResponse.Empty(status = 202)
                            else -> UrlResponse.Error(status = 500, body = "unexpected request")
                        }
                    },
                ),
                "https://mcp.test/.well-known/oauth-protected-resource/mcp" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """{"resource":"https://mcp.test/mcp","authorization_servers":["https://auth.mcp.test"]}""",
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
                        UrlResponse.JsonValue(
                            Json.parseToJsonElement(
                                """{"access_token":"refreshed-token","token_type":"Bearer","refresh_token":"refresh-token"}""",
                            ),
                        )
                    },
                ),
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
        val transport = HttpMCPTransport(fixture.httpClient(), "https://mcp.test/mcp", authProvider = authProvider)
        var received: JSONRPCMessage? = null
        transport.setOnMessage { received = it }

        transport.start()
        waitForRealTime { received != null }

        assertEquals("notifications/server", assertIs<JSONRPCNotification>(received).method)
        val gets = fixture.calls.filter { it.requestUrl == "https://mcp.test/mcp" && it.requestMethod == "GET" }
        assertEquals(2, gets.size)
        assertEquals("Bearer stale-token", gets[0].requestHeaders.headerValue(HttpHeaders.Authorization))
        assertEquals("Bearer refreshed-token", gets[1].requestHeaders.headerValue(HttpHeaders.Authorization))
        assertEquals(1, fixture.calls.count { it.requestUrl == "https://auth.mcp.test/token" })
        transport.close()
    }

    @Test
    fun `HTTP MCP transport starts at most one inbound SSE reader after concurrent accepted notifications`() = runTest {
        val controller = TestResponseController()
        var getAttempts = 0
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://mcp.test/mcp" to UrlHandler(
                    { request, _ ->
                        when {
                            request.method == "GET" -> {
                                if (getAttempts++ == 0) {
                                    UrlResponse.Error(status = 405, body = "GET not supported")
                                } else {
                                    UrlResponse.ControlledStream(controller)
                                }
                            }
                            request.method == "POST" -> UrlResponse.Empty(status = 202)
                            else -> UrlResponse.Error(status = 500, body = "unexpected request")
                        }
                    },
                ),
            ),
        )
        fixture.server.start()
        val transport = HttpMCPTransport(fixture.httpClient(), "https://mcp.test/mcp")

        transport.start()
        waitForRealTime { fixture.calls.count { it.requestMethod == "GET" } >= 1 }
        delay(50)

        val gate = CompletableDeferred<Unit>()
        val sends = List(25) { index ->
            async(Dispatchers.Default) {
                gate.await()
                transport.send(JSONRPCNotification(method = "notifications/test$index"))
            }
        }
        gate.complete(Unit)
        sends.awaitAll()

        waitForRealTime { fixture.calls.count { it.requestMethod == "GET" } >= 2 }
        delay(50)

        assertEquals(2, fixture.calls.count { it.requestMethod == "GET" })
        controller.close()
        transport.close()
    }

    @Test
    fun `HTTP inbound SSE clean EOF stops without reconnecting`() = runTest {
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://mcp.test/mcp" to UrlHandler(
                    { request, _ ->
                        when (request.method) {
                            "GET" -> UrlResponse.StreamChunks(emptyList())
                            else -> UrlResponse.Empty(status = 202)
                        }
                    },
                ),
            ),
        )
        fixture.server.start()
        val transport = HttpMCPTransport(
            client = fixture.httpClient(),
            url = "https://mcp.test/mcp",
            reconnectionOptions = MCPReconnectionOptions {
                initialReconnectionDelayMillis(30)
                reconnectionDelayGrowFactor(1.5)
                maxReconnectionDelayMillis(1_000)
                maxRetries(2)
            },
        )

        transport.start()
        waitForRealTime { fixture.calls.count { it.requestMethod == "GET" } >= 1 }
        assertEquals(1, fixture.calls.count { it.requestMethod == "GET" })

        withContext(Dispatchers.Default) { delay(100) }

        assertEquals(1, fixture.calls.count { it.requestMethod == "GET" })
        transport.close()
    }

    @Test
    fun `HTTP inbound SSE errors reconnect with capped exponential backoff`() = runTest {
        val oversizedSSEFrame = buildString {
            repeat(1_001) { append("data: x\n") }
            append('\n')
        }
        val errors = mutableListOf<Throwable>()
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://mcp.test/mcp" to UrlHandler(
                    { request, _ ->
                        when (request.method) {
                            "GET" -> UrlResponse.StreamChunks(listOf(oversizedSSEFrame))
                            else -> UrlResponse.Empty(status = 202)
                        }
                    },
                ),
            ),
        )
        fixture.server.start()
        val transport = HttpMCPTransport(
            client = fixture.httpClient(),
            url = "https://mcp.test/mcp",
            reconnectionOptions = MCPReconnectionOptions {
                initialReconnectionDelayMillis(30)
                reconnectionDelayGrowFactor(10.0)
                maxReconnectionDelayMillis(1_000)
                maxRetries(2)
            },
        )
        transport.setOnError { errors += it }

        transport.start()
        waitForRealTime { errors.isNotEmpty() }
        assertEquals(1, fixture.calls.count { it.requestMethod == "GET" })

        withContext(Dispatchers.Default) { delay(15) }
        assertEquals(1, fixture.calls.count { it.requestMethod == "GET" })

        waitForRealTime { errors.size >= 2 }
        assertEquals(2, fixture.calls.count { it.requestMethod == "GET" })

        withContext(Dispatchers.Default) { delay(100) }
        assertEquals(2, fixture.calls.count { it.requestMethod == "GET" })

        waitForRealTime { errors.any { it.message?.contains("Maximum reconnection attempts (2) exceeded") == true } }
        assertEquals(3, fixture.calls.count { it.requestMethod == "GET" })

        withContext(Dispatchers.Default) { delay(100) }

        assertEquals(3, fixture.calls.count { it.requestMethod == "GET" })
        assertTrue(errors.any { it.message?.contains("Maximum reconnection attempts (2) exceeded") == true })
        transport.close()
    }

    @Test
    fun `HTTP inbound SSE reconnect sends last event id`() = runTest {
        val oversizedSSEFrame = buildString {
            repeat(1_001) { append("data: x\n") }
            append('\n')
        }
        val notification = JSONRPCNotification(method = "notifications/progress").toJsonElement()
        val received = intArrayOf(0)
        val getAttempts = intArrayOf(0)
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://mcp.test/mcp" to UrlHandler(
                    { request, _ ->
                        when (request.method) {
                            "GET" -> when (getAttempts[0]++) {
                                0 -> UrlResponse.StreamChunks(
                                    listOf(
                                        "id: event-1\nevent: message\ndata: $notification\n\n",
                                        oversizedSSEFrame,
                                    ),
                                )
                                else -> UrlResponse.StreamChunks(emptyList())
                            }
                            else -> UrlResponse.Empty(status = 202)
                        }
                    },
                ),
            ),
        )
        fixture.server.start()
        val transport = HttpMCPTransport(
            client = fixture.httpClient(),
            url = "https://mcp.test/mcp",
            reconnectionOptions = MCPReconnectionOptions {
                initialReconnectionDelayMillis(30)
                reconnectionDelayGrowFactor(1.5)
                maxReconnectionDelayMillis(1_000)
                maxRetries(1)
            },
        )
        transport.setOnMessage { received[0] += 1 }

        transport.start()
        waitForRealTime { received[0] == 1 && fixture.calls.count { it.requestMethod == "GET" } >= 2 }

        val gets = fixture.calls.filter { it.requestMethod == "GET" }
        assertNull(gets[0].requestHeaders.headerValue("Last-Event-ID"))
        assertEquals("event-1", gets[1].requestHeaders.headerValue("Last-Event-ID"))
        transport.close()
    }

    @Test
    fun `HTTP inbound SSE parsed event resets reconnect attempt counter`() = runTest {
        val oversizedSSEFrame = buildString {
            repeat(1_001) { append("data: x\n") }
            append('\n')
        }
        val notification = JSONRPCNotification(method = "notifications/progress").toJsonElement()
        val errors = mutableListOf<Throwable>()
        val getAttempts = intArrayOf(0)
        val received = intArrayOf(0)
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://mcp.test/mcp" to UrlHandler(
                    { request, _ ->
                        when (request.method) {
                            "GET" -> when (getAttempts[0]++) {
                                0 -> UrlResponse.StreamChunks(listOf(oversizedSSEFrame))
                                1 -> UrlResponse.StreamChunks(
                                    listOf(
                                        "event: message\ndata: $notification\n\n",
                                        oversizedSSEFrame,
                                    ),
                                )
                                else -> UrlResponse.StreamChunks(emptyList())
                            }
                            else -> UrlResponse.Empty(status = 202)
                        }
                    },
                ),
            ),
        )
        fixture.server.start()
        val transport = HttpMCPTransport(
            client = fixture.httpClient(),
            url = "https://mcp.test/mcp",
            reconnectionOptions = MCPReconnectionOptions {
                initialReconnectionDelayMillis(30)
                reconnectionDelayGrowFactor(1.5)
                maxReconnectionDelayMillis(1_000)
                maxRetries(1)
            },
        )
        transport.setOnMessage { received[0] += 1 }
        transport.setOnError { errors += it }

        transport.start()
        waitForRealTime { errors.isNotEmpty() }
        assertEquals(1, fixture.calls.count { it.requestMethod == "GET" })

        waitForRealTime { received[0] == 1 }
        assertEquals(2, fixture.calls.count { it.requestMethod == "GET" })
        assertEquals(1, received[0])

        withContext(Dispatchers.Default) {
            withTimeout(1_000) {
                while (fixture.calls.count { it.requestMethod == "GET" } < 3) delay(10)
            }
        }

        assertEquals(3, fixture.calls.count { it.requestMethod == "GET" })
        transport.close()
    }

    @Test
    fun `HTTP MCP transport reconnects inbound SSE after accepted request explicitly retries`() = runTest {
        val firstInbound = TestResponseController()
        var getAttempts = 0
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://mcp.test/mcp" to UrlHandler(
                    { request, _ ->
                        when {
                            request.method == "GET" && getAttempts++ == 0 -> UrlResponse.ControlledStream(firstInbound)
                            request.method == "GET" -> UrlResponse.StreamChunks(
                                listOf(
                                    "event: message\ndata: ${
                                        JSONRPCResponse(id = JsonPrimitive(1), result = listToolsResult()).toJsonElement()
                                    }\n\n",
                                ),
                            )
                            "\"method\":\"initialize\"" in request.body -> UrlResponse.JsonValue(
                                JSONRPCResponse(id = JsonPrimitive(0), result = initializeResult()).toJsonElement(),
                            )
                            "\"method\":\"notifications/initialized\"" in request.body -> UrlResponse.Empty(status = 202)
                            "\"method\":\"tools/list\"" in request.body -> {
                                firstInbound.close()
                                UrlResponse.Empty(status = 202)
                            }
                            else -> UrlResponse.Error(status = 500, body = "unexpected request: ${request.body}")
                        }
                    },
                ),
            ),
        )
        fixture.server.start()
        val client = CreateMCPClient(
            MCPClientConfig {
                transport(
                    HttpMCPTransport(
                        client = fixture.httpClient(),
                        url = "https://mcp.test/mcp",
                    )
                )
            },
        )

        val tools = withContext(Dispatchers.Default) { withTimeout(20_000) { client.listTools() } }

        assertEquals("echo", tools.tools.single().name)
        assertTrue(
            fixture.calls.count { it.requestMethod == "GET" } >= 2,
            "an accepted 202 request must re-request inbound SSE before the request times out",
        )
        client.close()
    }

    @Test
    fun `HTTP inbound SSE reader survives a malformed message and still processes the next one`() = runTest {
        val good = """{"jsonrpc":"2.0","method":"notifications/server"}"""
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://mcp.test/mcp" to UrlHandler(
                    { request, _ ->
                        if (request.method == "GET") {
                            UrlResponse.StreamChunks(
                                listOf(
                                    "event: message\ndata: {not valid json\n\n",
                                    "event: message\ndata: $good\n\n",
                                ),
                            )
                        } else {
                            UrlResponse.Empty(status = 202)
                        }
                    },
                ),
            ),
        )
        fixture.server.start()
        val transport = HttpMCPTransport(fixture.httpClient(), "https://mcp.test/mcp")
        var received: JSONRPCMessage? = null
        var errored: Throwable? = null
        transport.setOnMessage { received = it }
        transport.setOnError { errored = it }

        transport.start()
        waitForRealTime { received != null }

        assertEquals("notifications/server", assertIs<JSONRPCNotification>(received).method)
        assertNotNull(errored, "the malformed frame is surfaced as a non-fatal error, not a stream abort")
        transport.close()
    }

    @Test
    fun `HTTP transport delivers a response streamed as the POST text event-stream body`() = runTest {
        // Exercises the POST-response SSE path (launchPostResponseSse): when a POST is answered
        // with text/event-stream, the body is parsed off the connection scope and the response is
        // delivered via onMessage to resolve the caller's request. The bug was that this parse ran
        // INLINE in send() and blocked until the stream closed; the true non-blocking property
        // cannot be reproduced under MockEngine (it only completes a request once the body channel
        // closes, so it cannot model a server that holds the stream open), so this guards the
        // refactored delivery path with a finite stream that closes.
        val listToolsFrame =
            JSONRPCResponse(id = JsonPrimitive(1), result = listToolsResult()).toJsonElement()
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://mcp.test/mcp" to UrlHandler(
                    { request, _ ->
                        when {
                            request.method == "GET" -> UrlResponse.Error(status = 405, body = "GET not supported")
                            "\"method\":\"initialize\"" in request.body -> UrlResponse.JsonValue(
                                JSONRPCResponse(id = JsonPrimitive(0), result = initializeResult()).toJsonElement(),
                            )
                            "\"method\":\"notifications/initialized\"" in request.body -> UrlResponse.Empty(status = 202)
                            // tools/list response is streamed back as an SSE frame, not application/json.
                            "\"method\":\"tools/list\"" in request.body ->
                                UrlResponse.StreamChunks(listOf("event: message\ndata: $listToolsFrame\n\n"))
                            else -> UrlResponse.Error(status = 500, body = "unexpected request: ${request.body}")
                        }
                    },
                ),
            ),
        )
        fixture.server.start()
        val transport = HttpMCPTransport(fixture.httpClient(), "https://mcp.test/mcp")
        val client = CreateMCPClient(
            MCPClientConfig {
                transport(transport)
            }
        )

        val tools = withContext(Dispatchers.Default) { withTimeout(20_000) { client.listTools() } }
        assertEquals("echo", tools.tools.single().name)
        client.close()
    }
}
