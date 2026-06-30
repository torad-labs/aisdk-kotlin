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

@OptIn(ExperimentalAiSdkApi::class, ExperimentalCoroutinesApi::class)
class MCPClientTest {

    @Test
    fun `createMCPClient performs initialize handshake and initialized notification`() = runTest {
        val transport = FakeMCPTransport { message ->
            if (message is JSONRPCRequest && message.method == "initialize") {
                respond(message.id, initializeResult(instructions = "Use these tools carefully."))
            }
        }

        val client = CreateMCPClient(
            MCPClientConfig {
                transport(transport)
                clientName("fixture-client")
                version("9.9.9")
            },
        )

        assertEquals(1, transport.startCount)
        assertEquals("fixture-server", client.serverInfo.name)
        assertEquals("Use these tools carefully.", client.instructions)
        assertEquals(LATEST_PROTOCOL_VERSION, transport.protocolVersion)

        val initialize = transport.sent.filterIsInstance<JSONRPCRequest>().first()
        assertEquals("initialize", initialize.method)
        val initializeParams = initialize.params!!
        assertEquals("fixture-client", initializeParams["clientInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("9.9.9", initializeParams["clientInfo"]!!.jsonObject["version"]!!.jsonPrimitive.content)

        val initialized = transport.sent.filterIsInstance<JSONRPCNotification>().single()
        assertEquals("notifications/initialized", initialized.method)
    }

    @Test
    fun `tools lists MCP definitions and converted tool calls tools call`() = runTest {
        val transport = FakeMCPTransport { message ->
            when {
                message is JSONRPCRequest && message.method == "initialize" ->
                    respond(message.id, initializeResult())
                message is JSONRPCRequest && message.method == "tools/list" ->
                    respond(message.id, listToolsResult())
                message is JSONRPCRequest && message.method == "tools/call" -> {
                    val params = message.params!!
                    assertEquals("echo", params["name"]!!.jsonPrimitive.content)
                    assertEquals("hi", params["arguments"]!!.jsonObject["message"]!!.jsonPrimitive.content)
                    respond(
                        message.id,
                        callToolResult(
                            content = listOf(
                                buildJsonObject {
                                    put("type", JsonPrimitive("text"))
                                    put("text", JsonPrimitive("echo:hi"))
                                },
                            ),
                        ),
                    )
                }
            }
        }
        val client = CreateMCPClient(MCPClientConfig {
            transport(transport)
        })

        val toolSet = client.tools<Unit>()
        val echoTool = toolSet.byName["echo"].asJsonTool()
        val echoCtx = ToolExecutionContext(
            context = Unit,
            abortSignal = AbortSignalNever,
            stepNumber = 0,
            messages = emptyList(),
            toolCallId = "call_1",
        )
        val output = (echoTool.execute(buildJsonObject { put("message", JsonPrimitive("hi")) }, echoCtx).first() as ToolResult.Success).value

        assertEquals("echo", echoTool.name)
        assertEquals("Echo a message", echoTool.description)
        val descriptorSchema = Json.parseToJsonElement(toolSet.descriptors.single().parametersSchemaJson).jsonObject
        assertEquals(JsonPrimitive(false), descriptorSchema["additionalProperties"])

        val modelOutput = echoTool.toModelOutput(
            output,
            ToolPredicateOptions {
                toolCallId("call_1")
                messages(emptyList())
                experimental_context(Unit)
            },
        )
        val content = assertIs<ToolResultOutput.Content>(modelOutput)
        assertEquals(false, content.isError)
        assertEquals("echo:hi", content.value.single().jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `toolsFromDefinitions filters schema map and extracts structured content`() = runTest {
        val transport = FakeMCPTransport { message ->
            when {
                message is JSONRPCRequest && message.method == "initialize" ->
                    respond(message.id, initializeResult())
                message is JSONRPCRequest && message.method == "tools/call" -> respond(
                    message.id,
                    callToolResult(
                        structuredContent = buildJsonObject {
                            put("temperature", JsonPrimitive(72))
                            put("unit", JsonPrimitive("f"))
                        },
                    ),
                )
            }
        }
        val client = CreateMCPClient(MCPClientConfig {
            transport(transport)
        })
        val definitions = ListToolsResult(
            tools = listOf(
                MCPToolDefinition(
                    name = "weather",
                    description = "Get weather",
                    inputSchema = objectSchema("city"),
                ),
                MCPToolDefinition(
                    name = "hidden",
                    description = "Hidden",
                    inputSchema = objectSchema("value"),
                ),
            ),
        )

        val toolSet = client.toolsFromDefinitions<Unit>(
            definitions = definitions,
            schemas = mapOf(
                "weather" to MCPToolSchema(
                    inputSchema = objectSchema("city"),
                    outputSchema = objectSchema("temperature"),
                ),
            ),
        )

        assertEquals(setOf("weather"), toolSet.byName.keys)
        val weather = toolSet.byName["weather"].asJsonTool()
        val weatherCtx = ToolExecutionContext(Unit, AbortSignalNever, 0, emptyList(), "call_1")
        val output = (weather.execute(buildJsonObject { put("city", JsonPrimitive("Austin")) }, weatherCtx).first() as ToolResult.Success).value.jsonObject

        assertEquals(72, output["temperature"]!!.jsonPrimitive.content.toInt())
        assertTrue(
            transport.sent.filterIsInstance<JSONRPCRequest>().any {
                it.method == "tools/call" && it.params!!["name"]!!.jsonPrimitive.content == "weather"
            },
        )
    }

    @Test
    fun `resources and prompts use MCP methods and parse results`() = runTest {
        val transport = FakeMCPTransport { message ->
            when {
                message is JSONRPCRequest && message.method == "initialize" ->
                    respond(message.id, initializeResult())
                message is JSONRPCRequest && message.method == "resources/list" -> respond(
                    message.id,
                    json.encodeToJsonElement(
                        ListResourcesResult.serializer(),
                        ListResourcesResult(resources = listOf(MCPResource(uri = "file://a", name = "a.txt"))),
                    ),
                )
                message is JSONRPCRequest && message.method == "resources/read" -> respond(
                    message.id,
                    json.encodeToJsonElement(
                        ReadResourceResult.serializer(),
                        ReadResourceResult(
                            contents = listOf(
                                buildJsonObject {
                                    put("uri", JsonPrimitive("file://a"))
                                    put("text", JsonPrimitive("hello"))
                                },
                            ),
                        ),
                    ),
                )
                message is JSONRPCRequest && message.method == "resources/templates/list" -> respond(
                    message.id,
                    json.encodeToJsonElement(
                        ListResourceTemplatesResult.serializer(),
                        ListResourceTemplatesResult(
                            resourceTemplates = listOf(MCPResourceTemplate(uriTemplate = "file://{name}", name = "file")),
                        ),
                    ),
                )
                message is JSONRPCRequest && message.method == "prompts/list" -> respond(
                    message.id,
                    json.encodeToJsonElement(
                        ListPromptsResult.serializer(),
                        ListPromptsResult(prompts = listOf(MCPPrompt(name = "summarize"))),
                    ),
                )
                message is JSONRPCRequest && message.method == "prompts/get" -> respond(
                    message.id,
                    json.encodeToJsonElement(
                        GetPromptResult.serializer(),
                        GetPromptResult(
                            description = "Summarize text",
                            messages = listOf(
                                MCPPromptMessage(
                                    role = "user",
                                    content = buildJsonObject {
                                        put("type", JsonPrimitive("text"))
                                        put("text", JsonPrimitive("Summarize this"))
                                    },
                                ),
                            ),
                        ),
                    ),
                )
            }
        }
        val client = CreateMCPClient(MCPClientConfig {
            transport(transport)
        })

        assertEquals("a.txt", client.listResources().resources.single().name)
        assertEquals("hello", client.readResource("file://a").contents.single()["text"]!!.jsonPrimitive.content)
        assertEquals("file", client.listResourceTemplates().resourceTemplates.single().name)
        assertEquals("summarize", client.experimental_listPrompts().prompts.single().name)
        assertEquals("Summarize text", client.experimental_getPrompt("summarize").description)

        val methods = transport.sent.filterIsInstance<JSONRPCRequest>().map { it.method }
        assertTrue("resources/list" in methods)
        assertTrue("resources/read" in methods)
        assertTrue("resources/templates/list" in methods)
        assertTrue("prompts/list" in methods)
        assertTrue("prompts/get" in methods)
    }

    @Test
    fun `elicitation request from server invokes registered handler and sends result`() = runTest {
        val transport = FakeMCPTransport { message ->
            if (message is JSONRPCRequest && message.method == "initialize") {
                respond(message.id, initializeResult())
            }
        }
        val client = CreateMCPClient(MCPClientConfig {
            transport(transport)
        })
        client.onElicitationRequest(ElicitationRequestSchema) { request ->
            assertEquals("Need confirmation", request.params.message)
            ElicitResult(
                action = "accept",
                content = buildJsonObject { put("confirmed", JsonPrimitive(true)) },
            )
        }

        transport.emitFromServer(
            JSONRPCRequest(
                id = JsonPrimitive(99),
                method = "elicitation/create",
                params = buildJsonObject {
                    put("message", JsonPrimitive("Need confirmation"))
                    put("requestedSchema", objectSchema("confirmed"))
                },
            ),
        )

        val response = transport.sent.filterIsInstance<JSONRPCResponse>().single { it.id == JsonPrimitive(99) }
        val result = assertNotNull(response.result)
        val action = assertNotNull(result.jsonObject["action"])
        val content = assertNotNull(result.jsonObject["content"])
        val confirmed = assertNotNull(content.jsonObject["confirmed"])
        assertEquals("accept", action.jsonPrimitive.content)
        assertEquals(true, confirmed.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `server JSON-RPC error completes request with MCPClientError`() = runTest {
        val transport = FakeMCPTransport { message ->
            when {
                message is JSONRPCRequest && message.method == "initialize" ->
                    respond(message.id, initializeResult())
                message is JSONRPCRequest && message.method == "tools/list" ->
                    fail(message.id, code = -32000, message = "boom")
            }
        }
        val client = CreateMCPClient(MCPClientConfig {
            transport(transport)
        })

        val error = assertFailsWith<MCPClientError> { client.listTools() }
        assertEquals(-32000, error.code)
        assertEquals("boom", error.message)
    }

    @Test
    fun `capability gate rejects unsupported methods before transport send`() = runTest {
        val transport = FakeMCPTransport { message ->
            if (message is JSONRPCRequest && message.method == "initialize") {
                respond(
                    message.id,
                    initializeResult(capabilities = MCPServerCapabilities()),
                )
            }
        }
        val client = CreateMCPClient(MCPClientConfig {
            transport(transport)
        })

        val before = transport.sent.size
        val error = assertFailsWith<MCPClientError> { client.listTools() }
        assertEquals("Server does not support tools", error.message)
        assertEquals(before, transport.sent.size)
    }

    @Test
    fun `JSON-RPC parser maps requests notifications responses and errors`() {
        assertIs<JSONRPCRequest>(JSONRPCMessage.fromJson("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}"""))
        assertIs<JSONRPCNotification>(JSONRPCMessage.fromJson("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""))
        assertIs<JSONRPCResponse>(JSONRPCMessage.fromJson("""{"jsonrpc":"2.0","id":1,"result":{"ok":true}}"""))
        val nullResult = assertIs<JSONRPCResponse>(
            JSONRPCMessage.fromJson("""{"jsonrpc":"2.0","id":7,"result":null}"""),
        )
        assertNull(nullResult.result)
        val error = assertIs<JSONRPCError>(
            JSONRPCMessage.fromJson("""{"jsonrpc":"2.0","id":1,"error":{"code":-1,"message":"bad"}}"""),
        )
        assertEquals(-1, error.error.code)
    }

    @Test
    fun `JSON-RPC null result response settles pending request instead of hanging`() = runTest {
        val uncaught = mutableListOf<Throwable>()
        val transport = FakeMCPTransport { message ->
            when {
                message is JSONRPCRequest && message.method == "initialize" ->
                    respond(message.id, initializeResult())
                message is JSONRPCRequest && message.method == "tools/list" ->
                    emitFromServer(JSONRPCMessage.fromJson("""{"jsonrpc":"2.0","id":${message.id},"result":null}"""))
            }
        }
        val client = CreateMCPClient(
            MCPClientConfig {
                transport(transport)
                onUncaughtError({ uncaught += it })
            },
        )

        val error = assertFailsWith<MCPClientError> {
            client.listTools(options = MCPRequestOptions {
                timeoutMillis(50)
            })
        }

        assertEquals("Failed to parse server response", error.message)
        assertTrue(uncaught.isEmpty(), "null result response should not be routed to uncaught parse errors")
        client.close()
    }

    @Test
    fun `JSON-RPC parser rejects malformed envelopes through wire decoder`() {
        assertFailsWith<WireDecodeException> {
            JSONRPCMessage.fromJson("""{"jsonrpc":"2.0","id":1,"method":"tools/list","unexpected":true}""")
        }
        assertFailsWith<WireDecodeException> {
            JSONRPCMessage.fromJson("""{"jsonrpc":"1.0","id":1,"method":"tools/list"}""")
        }
    }

    @Test
    fun `server notifications without id do not surface as client errors`() = runTest {
        val uncaught = mutableListOf<Throwable>()
        val transport = FakeMCPTransport { message ->
            when {
                message is JSONRPCRequest && message.method == "initialize" -> {
                    emitFromServer(JSONRPCNotification(method = "notifications/progress"))
                    respond(message.id, initializeResult())
                }
                message is JSONRPCRequest && message.method == "tools/list" -> {
                    emitFromServer(JSONRPCNotification(method = "notifications/resources/list_changed"))
                    respond(message.id, listToolsResult())
                }
            }
        }
        val client = CreateMCPClient(MCPClientConfig {
            transport(transport)
            onUncaughtError({ uncaught += it })
        })

        assertEquals("echo", client.listTools().tools.single().name)
        assertTrue(uncaught.isEmpty(), "JSON-RPC notifications are advisory and must not trip uncaught errors")
    }

    @Test
    fun `concurrent MCP requests allocate unique JSON RPC ids`() = runTest {
        val transport = FakeMCPTransport { message ->
            when {
                message is JSONRPCRequest && message.method == "initialize" ->
                    respond(message.id, initializeResult())
                message is JSONRPCRequest && message.method == "tools/list" ->
                    respond(message.id, listToolsResult())
            }
        }
        val client = CreateMCPClient(MCPClientConfig {
            transport(transport)
        })
        val gate = CompletableDeferred<Unit>()

        val jobs = List(100) {
            async(Dispatchers.Default) {
                gate.await()
                client.listTools()
            }
        }
        gate.complete(Unit)
        jobs.awaitAll()

        val ids = transport.sent
            .filterIsInstance<JSONRPCRequest>()
            .filter { it.method == "tools/list" }
            .map { it.id.toString() }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `abort interrupts in-flight MCP request await and cleans pending handler`() = runTest {
        val controller = AbortController()
        val toolsRequestId = CompletableDeferred<JsonElement>()
        val transport = FakeMCPTransport { message ->
            when {
                message is JSONRPCRequest && message.method == "initialize" ->
                    respond(message.id, initializeResult())
                message is JSONRPCRequest && message.method == "tools/list" ->
                    toolsRequestId.complete(message.id)
            }
        }
        val client = CreateMCPClient(MCPClientConfig {
            transport(transport)
        })

        val pending = backgroundScope.async {
            client.listTools(options = MCPRequestOptions {
                signal(controller.signal)
            })
        }
        runCurrent()
        waitForRealTime { toolsRequestId.isCompleted }
        val requestId = toolsRequestId.await()

        controller.abort()

        withContext(Dispatchers.Default) {
            withTimeout(10_000) {
                assertFailsWith<AbortError> { pending.await() }
            }
        }
        val lateResponse = assertFailsWith<MCPClientError> {
            transport.emitFromServer(JSONRPCResponse(id = requestId, result = listToolsResult()))
        }
        assertTrue(assertNotNull(lateResponse.message).contains("unknown message ID"))
        client.close()
    }

    @Test
    fun `scope cancellation during MCP request await propagates and cleans pending handler`() = runTest {
        val toolsRequestId = CompletableDeferred<JsonElement>()
        val transport = FakeMCPTransport { message ->
            when {
                message is JSONRPCRequest && message.method == "initialize" ->
                    respond(message.id, initializeResult())
                message is JSONRPCRequest && message.method == "tools/list" ->
                    toolsRequestId.complete(message.id)
            }
        }
        val client = CreateMCPClient(MCPClientConfig {
            transport(transport)
        })

        val pending = backgroundScope.async { client.listTools() }
        runCurrent()
        waitForRealTime { toolsRequestId.isCompleted }
        val requestId = toolsRequestId.await()

        pending.cancel(CancellationException("caller scope canceled"))

        val error = withContext(Dispatchers.Default) {
            withTimeout(10_000) {
                assertFailsWith<CancellationException> { pending.await() }
            }
        }
        assertTrue(assertNotNull(error.message).contains("caller scope canceled"))
        val lateResponse = assertFailsWith<MCPClientError> {
            transport.emitFromServer(JSONRPCResponse(id = requestId, result = listToolsResult()))
        }
        assertTrue(assertNotNull(lateResponse.message).contains("unknown message ID"))
        client.close()
    }

    @Test
    fun `normal MCP request resolves under default per-request timeout`() = runTest {
        val transport = FakeMCPTransport { message ->
            when {
                message is JSONRPCRequest && message.method == "initialize" ->
                    respond(message.id, initializeResult())
                message is JSONRPCRequest && message.method == "tools/list" ->
                    respond(message.id, listToolsResult())
            }
        }
        val client = CreateMCPClient(MCPClientConfig {
            transport(transport)
        })

        assertEquals("echo", client.listTools().tools.single().name)
        client.close()
    }

    @Test
    fun `auth returns authorized with existing tokens and redirect when tokens are absent`() = runTest {
        val authorized = MemoryOAuthProvider(tokens = OAuthTokens(accessToken = "token", tokenType = "Bearer"))
        assertEquals(AuthResult.AUTHORIZED, McpAuth.auth(authorized, AuthOptions { serverUrl("https://mcp.example.com") }))

        val redirect = MemoryOAuthProvider(tokens = null)
        assertEquals(AuthResult.REDIRECT, McpAuth.auth(redirect, AuthOptions {
            serverUrl("https://mcp.example.com")
            scope("tools")
        }))
        assertNotNull(redirect.lastAuthorizationUrl)
        assertTrue(redirect.lastAuthorizationUrl!!.startsWith("https://mcp.example.com/authorize?"))
        assertTrue("scope=tools" in redirect.lastAuthorizationUrl!!)
    }

    @Test
    fun `reauthorize does not treat an access token without refresh token as already authorized`() = runTest {
        val provider = MemoryOAuthProvider(tokens = OAuthTokens(accessToken = "stale-token", tokenType = "Bearer"))

        assertEquals(
            AuthResult.REDIRECT,
            McpAuth.auth(provider, AuthOptions { serverUrl("https://mcp.example.com") }, reauthorize = true),
        )
        assertNotNull(provider.lastAuthorizationUrl)
    }

    @Test
    fun `auth starts authorization with dynamic registration and PKCE`() = runTest {
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://auth.example.com/.well-known/oauth-authorization-server" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """
                            {
                              "authorization_endpoint":"https://login.example.com/oauth/authorize",
                              "token_endpoint":"https://login.example.com/oauth/token",
                              "registration_endpoint":"https://login.example.com/oauth/register",
                              "response_types_supported":["code"],
                              "code_challenge_methods_supported":["S256"]
                            }
                            """.trimIndent(),
                        ),
                    ),
                ),
                "https://login.example.com/oauth/register" to UrlHandler(
                    { request, _ ->
                        assertTrue(request.body.contains("\"client_name\":\"client-1\""))
                        UrlResponse.JsonValue(Json.parseToJsonElement("""{"client_id":"registered-client","client_secret":"registered-secret"}"""))
                    },
                ),
            ),
        )
        fixture.server.start()
        val provider = MemoryOAuthProvider(tokens = null, clientInformation = null)

        assertEquals(
            AuthResult.REDIRECT,
            McpAuth.auth(
                provider,
                AuthOptions {
                    serverUrl("https://auth.example.com")
                    scope("tools offline_access")
                    client(fixture.httpClient())
                },
            ),
        )

        assertEquals("registered-client", provider.clientInformation()?.clientId)
        assertEquals("registered-secret", provider.clientInformation()?.clientSecret)
        val authorizationUrl = assertNotNull(provider.lastAuthorizationUrl)
        assertTrue(authorizationUrl.startsWith("https://login.example.com/oauth/authorize?"))
        assertTrue("client_id=registered-client" in authorizationUrl)
        assertTrue("scope=tools%20offline_access" in authorizationUrl)
        assertTrue("code_challenge=" in authorizationUrl)
        assertTrue("code_challenge_method=S256" in authorizationUrl)
        assertTrue("prompt=consent" in authorizationUrl)
        assertTrue(provider.savedCodeVerifier.isNotBlank())
    }

    @Test
    fun `auth exchanges authorization code and awaits async client authentication`() = runTest {
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://auth.example.com/.well-known/oauth-authorization-server" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """
                            {
                              "authorization_endpoint":"https://auth.example.com/authorize",
                              "token_endpoint":"https://auth.example.com/token",
                              "response_types_supported":["code"],
                              "grant_types_supported":["authorization_code"],
                              "token_endpoint_auth_methods_supported":["client_secret_post"],
                              "code_challenge_methods_supported":["S256"]
                            }
                            """.trimIndent(),
                        ),
                    ),
                ),
                "https://auth.example.com/token" to UrlHandler(
                    { request, _ ->
                        assertTrue(request.body.contains("grant_type=authorization_code"))
                        assertTrue(request.body.contains("code=code123"))
                        assertTrue(request.body.contains("code_verifier=verifier"))
                        assertTrue(request.body.contains("client_id=set-by-async-provider"))
                        assertTrue(request.body.contains("client_secret=secret-by-async-provider"))
                        assertTrue(request.body.contains("example_url=https%3A%2F%2Fauth.example.com"))
                        UrlResponse.JsonValue(
                            Json.parseToJsonElement(
                                """{"access_token":"async-token","token_type":"Bearer","refresh_token":"refresh-token"}""",
                            ),
                        )
                    },
                ),
            ),
        )
        fixture.server.start()
        val provider = MemoryOAuthProvider(
            tokens = null,
            clientInformation = OAuthClientInformation(clientId = "client-id", clientSecret = "client-secret"),
            onAddClientAuthentication = { _, _, _, _ ->
                delay(5)
                ClientAuthResult(additionalParams = mapOf(
                    "client_id" to "set-by-async-provider",
                    "client_secret" to "secret-by-async-provider",
                    "example_url" to "https://auth.example.com",
                ))
            },
        )
        provider.saveState("state-1")

        assertEquals(
            AuthResult.AUTHORIZED,
            McpAuth.auth(
                provider,
                AuthOptions {
                    serverUrl("https://auth.example.com")
                    authorizationCode("code123")
                    callbackState("state-1")
                    client(fixture.httpClient())
                },
            ),
        )

        assertEquals("async-token", provider.tokens()?.accessToken)
        assertEquals("refresh-token", provider.tokens()?.refreshToken)
    }

    @Test
    fun `auth refreshes tokens preserves refresh token and awaits async client authentication`() = runTest {
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://auth.example.com/.well-known/oauth-authorization-server" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """
                            {
                              "authorization_endpoint":"https://auth.example.com/authorize",
                              "token_endpoint":"https://auth.example.com/token",
                              "response_types_supported":["code"],
                              "grant_types_supported":["refresh_token"],
                              "token_endpoint_auth_methods_supported":["client_secret_post"],
                              "code_challenge_methods_supported":["S256"]
                            }
                            """.trimIndent(),
                        ),
                    ),
                ),
                "https://auth.example.com/token" to UrlHandler(
                    { request, _ ->
                        assertTrue(request.body.contains("grant_type=refresh_token"))
                        assertTrue(request.body.contains("refresh_token=old-refresh"))
                        assertTrue(request.body.contains("client_id=set-by-async-provider"))
                        assertTrue(request.body.contains("client_secret=secret-by-async-provider"))
                        assertTrue(request.body.contains("example_url=https%3A%2F%2Fauth.example.com"))
                        UrlResponse.JsonValue(Json.parseToJsonElement("""{"access_token":"new-token","token_type":"Bearer"}"""))
                    },
                ),
            ),
        )
        fixture.server.start()
        val provider = MemoryOAuthProvider(
            tokens = OAuthTokens(accessToken = "old-token", tokenType = "Bearer", refreshToken = "old-refresh"),
            clientInformation = OAuthClientInformation(clientId = "client-id", clientSecret = "client-secret"),
            onAddClientAuthentication = { _, _, _, _ ->
                delay(5)
                ClientAuthResult(additionalParams = mapOf(
                    "client_id" to "set-by-async-provider",
                    "client_secret" to "secret-by-async-provider",
                    "example_url" to "https://auth.example.com",
                ))
            },
        )

        assertEquals(AuthResult.AUTHORIZED, McpAuth.auth(provider, AuthOptions {
            serverUrl("https://auth.example.com")
            client(fixture.httpClient())
        }))

        assertEquals("new-token", provider.tokens()?.accessToken)
        assertEquals("old-refresh", provider.tokens()?.refreshToken)
    }

    @Test
    fun `auth falls back to redirect when reauthorize refresh token is invalid grant`() = runTest {
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://auth.example.com/.well-known/oauth-authorization-server" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """
                            {
                              "authorization_endpoint":"https://auth.example.com/authorize",
                              "token_endpoint":"https://auth.example.com/token",
                              "response_types_supported":["code"],
                              "grant_types_supported":["authorization_code","refresh_token"],
                              "token_endpoint_auth_methods_supported":["client_secret_post"],
                              "code_challenge_methods_supported":["S256"]
                            }
                            """.trimIndent(),
                        ),
                    ),
                ),
                "https://auth.example.com/token" to UrlHandler(
                    { request, _ ->
                        assertTrue(request.body.contains("grant_type=refresh_token"))
                        assertTrue(request.body.contains("refresh_token=revoked-refresh"))
                        UrlResponse.Error(
                            status = 400,
                            body = """{"error":"invalid_grant"}""",
                            headers = mapOf(HttpHeaders.ContentType to "application/json"),
                        )
                    },
                ),
            ),
        )
        fixture.server.start()
        val provider = MemoryOAuthProvider(
            tokens = OAuthTokens(accessToken = "expired-token", tokenType = "Bearer", refreshToken = "revoked-refresh"),
            clientInformation = OAuthClientInformation(clientId = "client-id", clientSecret = "client-secret"),
        )

        assertEquals(
            AuthResult.REDIRECT,
            McpAuth.auth(
                provider,
                AuthOptions {
                    serverUrl("https://auth.example.com")
                    client(fixture.httpClient())
                },
                reauthorize = true,
            ),
        )

        val authorizationUrl = assertNotNull(provider.lastAuthorizationUrl)
        assertTrue(authorizationUrl.startsWith("https://auth.example.com/authorize?"))
        assertTrue("client_id=client-id" in authorizationUrl)
        assertTrue("code_challenge=" in authorizationUrl)
        assertEquals("expired-token", provider.tokens()?.accessToken)
    }

    @Test
    fun `auth uses protected resource metadata for authorization server and redirect resource`() = runTest {
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://api.example.com/.well-known/oauth-protected-resource/mcp-server" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """{"resource":"https://api.example.com/mcp-server","authorization_servers":["https://auth.example.com"]}""",
                        ),
                    ),
                ),
                "https://auth.example.com/.well-known/oauth-authorization-server" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """
                            {
                              "authorization_endpoint":"https://auth.example.com/authorize",
                              "token_endpoint":"https://auth.example.com/token",
                              "response_types_supported":["code"],
                              "code_challenge_methods_supported":["S256"]
                            }
                            """.trimIndent(),
                        ),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val provider = MemoryOAuthProvider(tokens = null)

        assertEquals(
            AuthResult.REDIRECT,
            McpAuth.auth(provider, AuthOptions {
                serverUrl("https://api.example.com/mcp-server")
                client(fixture.httpClient())
            }),
        )

        val authorizationUrl = assertNotNull(provider.lastAuthorizationUrl)
        assertTrue(authorizationUrl.startsWith("https://auth.example.com/authorize?"))
        assertTrue("resource=https%3A%2F%2Fapi.example.com%2Fmcp-server" in authorizationUrl)
        assertEquals(
            listOf(
                "https://api.example.com/.well-known/oauth-protected-resource/mcp-server",
                "https://auth.example.com/.well-known/oauth-authorization-server",
            ),
            fixture.calls.map { it.requestUrl },
        )
    }

    @Test
    fun `auth carries protected resource through code exchange and refresh`() = runTest {
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://api.example.com/.well-known/oauth-protected-resource/mcp-server" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """{"resource":"https://api.example.com/mcp-server","authorization_servers":["https://auth.example.com"]}""",
                        ),
                    ),
                ),
                "https://auth.example.com/.well-known/oauth-authorization-server" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """
                            {
                              "authorization_endpoint":"https://auth.example.com/authorize",
                              "token_endpoint":"https://auth.example.com/token",
                              "response_types_supported":["code"],
                              "grant_types_supported":["authorization_code","refresh_token"],
                              "token_endpoint_auth_methods_supported":["client_secret_post"],
                              "code_challenge_methods_supported":["S256"]
                            }
                            """.trimIndent(),
                        ),
                    ),
                ),
                "https://auth.example.com/token" to UrlHandler(
                    { request, options ->
                        assertTrue(request.body.contains("resource=https%3A%2F%2Fapi.example.com%2Fmcp-server"))
                        if (options.callNumber == 2) {
                            assertTrue(request.body.contains("grant_type=authorization_code"))
                            UrlResponse.JsonValue(
                                Json.parseToJsonElement(
                                    """{"access_token":"access-token","token_type":"Bearer","refresh_token":"refresh-token"}""",
                                ),
                            )
                        } else {
                            assertTrue(request.body.contains("grant_type=refresh_token"))
                            UrlResponse.JsonValue(Json.parseToJsonElement("""{"access_token":"refreshed","token_type":"Bearer"}"""))
                        }
                    },
                ),
            ),
        )
        fixture.server.start()
        val provider = MemoryOAuthProvider(
            tokens = null,
            clientInformation = OAuthClientInformation(clientId = "client-id", clientSecret = "client-secret"),
        )
        provider.saveState("state-1")

        assertEquals(
            AuthResult.AUTHORIZED,
            McpAuth.auth(
                provider,
                AuthOptions {
                    serverUrl("https://api.example.com/mcp-server")
                    authorizationCode("code")
                    callbackState("state-1")
                    client(fixture.httpClient())
                },
            ),
        )
        assertEquals("access-token", provider.tokens()?.accessToken)

        assertEquals(
            AuthResult.AUTHORIZED,
            McpAuth.auth(provider, AuthOptions {
                serverUrl("https://api.example.com/mcp-server")
                client(fixture.httpClient())
            }),
        )
        assertEquals("refreshed", provider.tokens()?.accessToken)
        assertEquals("refresh-token", provider.tokens()?.refreshToken)
    }

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
                transport(HttpMCPTransport(
                                    client = fixture.httpClient(),
                                    url = "https://mcp.test/mcp",
                                    headers = mapOf("X-Test" to "transport"),
                                ))
            },
        )

        assertEquals("fixture-server", client.serverInfo.name)
        assertEquals("echo", client.listTools().tools.single().name)
        waitForRealTime { fixture.calls.count { it.requestMethod == "POST" } >= 3 }
        val initialize = fixture.calls.first { it.requestBodyText.contains("\"method\":\"initialize\"") }
        val initialized = fixture.calls.first { it.requestBodyText.contains("\"method\":\"notifications/initialized\"") }
        val listTools = fixture.calls.first { it.requestBodyText.contains("\"method\":\"tools/list\"") }
        assertEquals("POST", initialize.requestMethod)
        assertEquals("transport", initialize.requestHeaders.headerValue("X-Test"))
        assertEquals(LATEST_PROTOCOL_VERSION, initialize.requestHeaders.headerValue("mcp-protocol-version"))
        assertEquals("session-1", initialized.requestHeaders.headerValue("mcp-session-id"))
        assertEquals("session-1", listTools.requestHeaders.headerValue("mcp-session-id"))
    }

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
            clientInformation = OAuthClientInformation(clientId = "client-id", clientSecret = "client-secret"),
        )
        val transport = SseMCPTransport(fixture.httpClient(), "https://mcp.test/sse", authProvider = authProvider)

        transport.start()
        transport.close()

        val sseGets = fixture.calls.filter { it.requestUrl == "https://mcp.test/sse" && it.requestMethod == "GET" }
        assertEquals(2, sseGets.size)
        assertEquals("Bearer refreshed", sseGets[1].requestHeaders.headerValue(HttpHeaders.Authorization))
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
                transport(HttpMCPTransport(
                                    client = fixture.httpClient(),
                                    url = "https://mcp.test/mcp",
                                ))
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
    fun `stdio transport exchanges newline-delimited JSON-RPC with process`() = runTest {
        val transport = Experimental_StdioMCPTransport(
            StdioConfig {
                command("/bin/sh")
                args(listOf("-c", "while IFS= read -r line; do printf '%s\\n' \"\$line\"; done"))
            },
        )
        var received: JSONRPCMessage? = null
        transport.setOnMessage { received = it }

        try {
            transport.start()
        } catch (ignoredOnUnsupportedPlatform: UnsupportedOperationException) {
            // Stdio MCP spawns a child process (ProcessBuilder); that actual is
            // unsupported on Native/iOS and throws here. The transport is
            // exercised end-to-end on JVM + Android — skip on platforms without
            // subprocess support rather than fail the shared test.
            return@runTest
        }
        transport.send(JSONRPCNotification(method = "notifications/test"))
        waitForRealTime { received != null }

        val notification = assertIs<JSONRPCNotification>(received)
        assertEquals("notifications/test", notification.method)
        transport.close()
    }

    @Test
    fun `stdio transport survives a child that floods stderr before responding`() = runTest {
        // Regression: the child's stderr pipe was never drained, so a server that logs a large
        // banner/diagnostics to stderr blocks on the write once the ~64KB pipe buffer fills, then
        // stops producing stdout and hangs the JSON-RPC reader forever.
        val transport = Experimental_StdioMCPTransport(
            StdioConfig {
                command("/bin/sh")
                // Write ~256KB to stderr (well past the pipe buffer) BEFORE emitting any stdout.
                args(
                    listOf(
                        "-c",
                        "yes 0123456789abcdef | head -c 262144 1>&2; " +
                            "printf '%s\\n' '{\"jsonrpc\":\"2.0\",\"method\":\"notifications/ready\"}'",
                    ),
                )
            },
        )
        var received: JSONRPCMessage? = null
        transport.setOnMessage { received = it }

        try {
            transport.start()
        } catch (ignoredOnUnsupportedPlatform: UnsupportedOperationException) {
            return@runTest // Native/iOS: no subprocess support (same skip as the stdio round-trip test)
        }
        waitForRealTime { received != null } // pre-fix: child blocks on stderr -> stdout never comes -> timeout

        assertEquals("notifications/ready", assertIs<JSONRPCNotification>(received).method)
        transport.close()
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
    fun `stdio reader EOF tears down the process so a later send reports not-connected`() = runTest {
        // Regression: when the child exits (readLine -> null) the reader fired onClose but never
        // destroyed the process or nulled the field — leaking the handle/FDs (and a reconnect would
        // overwrite the still-open process). Observable proxy: post-fix the field is nulled, so a
        // send after EOF reports the clean "not connected" error instead of a write-to-dead-pipe.
        val transport = Experimental_StdioMCPTransport(
            StdioConfig {
                command("/bin/sh")
                args(listOf("-c", "exit 0"))
            }, // exits immediately -> reader EOF
        )
        var closed = false
        transport.setOnClose { closed = true }
        try {
            transport.start()
        } catch (ignoredOnUnsupportedPlatform: UnsupportedOperationException) {
            return@runTest // Native/iOS: no subprocess support
        }
        waitForRealTime { closed } // reader hit EOF and ran its teardown

        val error = assertFailsWith<MCPClientError> { transport.send(JSONRPCNotification(method = "x")) }
        assertTrue(
            error.message?.contains("not connected") == true,
            "EOF nulls the process; send reports not-connected (pre-fix: a write-to-dead-pipe error)",
        )
        transport.close()
    }

    @Test
    fun `onResponse resolves a numeric request id echoed back as a JSON string`() = runTest {
        // Regression: response handlers were keyed by the id's JSON *type* (s:/n:), so a
        // server that echoes our numeric request id back as a JSON string (1 -> "1") missed
        // the numeric-keyed handler and the response was dropped as an "unknown message ID".
        val transport = FakeMCPTransport { message ->
            when {
                message is JSONRPCRequest && message.method == "initialize" ->
                    respond(message.id, initializeResult())
                message is JSONRPCRequest && message.method == "tools/list" ->
                    // Echo the numeric request id back as a JSON string instead of a number.
                    respond(JsonPrimitive(message.id.jsonPrimitive.content), listToolsResult())
            }
        }
        val client = CreateMCPClient(MCPClientConfig {
            transport(transport)
        })

        // Pre-fix: the string-typed echo misses the handler and throws out of send().
        val tools = client.listTools()
        assertEquals("echo", tools.tools.single().name)
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
        val client = CreateMCPClient(MCPClientConfig {
            transport(transport)
        })

        val tools = withContext(Dispatchers.Default) { withTimeout(20_000) { client.listTools() } }
        assertEquals("echo", tools.tools.single().name)
        client.close()
    }

    private fun objectSchema(vararg required: String): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put(
            "properties",
            buildJsonObject {
                required.forEach { name ->
                    put(name, buildJsonObject { put("type", JsonPrimitive("string")) })
                }
            },
        )
        put("required", kotlinx.serialization.json.JsonArray(required.map(::JsonPrimitive)))
    }

    private suspend fun waitForRealTime(condition: () -> Boolean) {
        withContext(Dispatchers.Default) {
            // Real wall-clock wait on real I/O (subprocess round-trips, SSE
            // reconnects). Generous so a loaded CI/dev host doesn't flake a
            // genuinely-progressing test; a real hang still fails (at the cap).
            withTimeout(20_000) {
                while (!condition()) delay(10)
            }
        }
    }

    private fun initializeResult(
        capabilities: MCPServerCapabilities = MCPServerCapabilities(
            tools = JsonObject(emptyMap()),
            resources = JsonObject(emptyMap()),
            prompts = JsonObject(emptyMap()),
            elicitation = ElicitationCapability(),
        ),
        instructions: String? = null,
    ): JsonElement = json.encodeToJsonElement(
        InitializeResult.serializer(),
        InitializeResult(
            protocolVersion = LATEST_PROTOCOL_VERSION,
            capabilities = capabilities,
            serverInfo = Configuration(name = "fixture-server", version = "1.0.0"),
            instructions = instructions,
        ),
    )

    private fun listToolsResult(): JsonElement = json.encodeToJsonElement(
        ListToolsResult.serializer(),
        ListToolsResult(
            tools = listOf(
                MCPToolDefinition(
                    name = "echo",
                    title = "Echo",
                    description = "Echo a message",
                    inputSchema = objectSchema("message"),
                    meta = buildJsonObject { put("origin", JsonPrimitive("fixture")) },
                ),
            ),
        ),
    )

    private fun callToolResult(
        content: List<JsonObject> = emptyList(),
        structuredContent: JsonElement? = null,
        isError: Boolean = false,
    ): JsonElement = json.encodeToJsonElement(
        CallToolResult.serializer(),
        CallToolResult(content = content, structuredContent = structuredContent, isError = isError),
    )

    @Suppress("UNCHECKED_CAST")
    private fun Tool<*, *, *>?.asJsonTool(): Tool<JsonElement, JsonElement, Unit> =
        assertNotNull(this) as Tool<JsonElement, JsonElement, Unit>

    private class FakeMCPTransport(
        val handler: suspend FakeMCPTransport.(JSONRPCMessage) -> Unit,
    ) : MCPTransport {
        val sent = mutableListOf<JSONRPCMessage>()
        var startCount = 0
        private var onClose: (() -> Unit)? = null
        private var onError: ((Throwable) -> Unit)? = null
        private var onMessage: (suspend (JSONRPCMessage) -> Unit)? = null
        private var _protocolVersion: String? = null
        val protocolVersion: String? get() = _protocolVersion

        override fun setOnClose(handler: (() -> Unit)?) { onClose = handler }
        override fun setOnError(handler: ((Throwable) -> Unit)?) { onError = handler }
        override fun setOnMessage(handler: (suspend (JSONRPCMessage) -> Unit)?) { onMessage = handler }
        override fun setProtocolVersion(version: String?) { _protocolVersion = version }

        // Concurrent client requests hit send() from multiple coroutines; on a
        // multi-threaded Kotlin/Native dispatcher (linuxX64) an unguarded
        // ArrayList.add races, so serialize the recording.
        private val sentMutex = Mutex()

        override suspend fun start() {
            startCount += 1
        }

        override suspend fun send(message: JSONRPCMessage) {
            sentMutex.withLock { sent += message }
            handler(message)
        }

        override suspend fun close() {
            onClose?.invoke()
        }

        suspend fun respond(id: JsonElement, result: JsonElement) {
            onMessage?.invoke(JSONRPCResponse(id = id, result = result))
        }

        suspend fun fail(id: JsonElement, code: Int, message: String) {
            onMessage?.invoke(JSONRPCError(id = id, error = JSONRPCErrorData(code = code, message = message)))
        }

        suspend fun emitFromServer(message: JSONRPCMessage) {
            onMessage?.invoke(message)
        }
    }

    private class MemoryOAuthProvider(
        private var tokens: OAuthTokens?,
        private var clientInformation: OAuthClientInformation? = OAuthClientInformation(clientId = "client-id"),
        private val onAddClientAuthentication: (suspend (
            headers: Map<String, String>,
            params: Map<String, String>,
            url: String,
            metadata: AuthorizationServerMetadata?,
        ) -> ClientAuthResult)? = null,
    ) : OAuthClientProvider {
        var lastAuthorizationUrl: String? = null
        var savedCodeVerifier: String = "verifier"
        private var savedState: String? = null
        override val redirectUrl: String = "https://client.example.com/callback"
        override val clientMetadata: OAuthClientMetadata = OAuthClientMetadata(
            redirectUris = listOf(redirectUrl),
            clientName = "client-1",
        )

        override suspend fun tokens(): OAuthTokens? = tokens
        override suspend fun saveTokens(tokens: OAuthTokens) {
            this.tokens = tokens
        }

        override suspend fun redirectToAuthorization(authorizationUrl: String) {
            lastAuthorizationUrl = authorizationUrl
        }

        override suspend fun saveCodeVerifier(codeVerifier: String) {
            savedCodeVerifier = codeVerifier
        }

        override suspend fun codeVerifier(): String = savedCodeVerifier
        override suspend fun clientInformation(): OAuthClientInformation? = clientInformation

        override suspend fun saveClientInformation(clientInformation: OAuthClientInformation) {
            this.clientInformation = clientInformation
        }

        override suspend fun saveState(state: String) {
            savedState = state
        }

        override suspend fun storedState(): String? = savedState

        override suspend fun addClientAuthentication(
            headers: Map<String, String>,
            params: Map<String, String>,
            url: String,
            metadata: AuthorizationServerMetadata?,
        ): ClientAuthResult = onAddClientAuthentication?.invoke(headers, params, url, metadata) ?: ClientAuthResult()
    }

    private fun Map<String, String>.headerValue(name: String): String? =
        entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    private companion object {
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
