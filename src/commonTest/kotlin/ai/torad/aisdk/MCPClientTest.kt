package ai.torad.aisdk

import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MCPClientTest {

    @Test
    fun `createMCPClient performs initialize handshake and initialized notification`() = runTest {
        val transport = FakeMCPTransport { message ->
            if (message is JSONRPCRequest && message.method == "initialize") {
                respond(message.id, initializeResult(instructions = "Use these tools carefully."))
            }
        }

        val client = createMCPClient(
            MCPClientConfig(
                transport = transport,
                clientName = "fixture-client",
                version = "9.9.9",
            ),
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
        val client = createMCPClient(MCPClientConfig(transport = transport))

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
            ToolPredicateOptions(toolCallId = "call_1", messages = emptyList(), experimental_context = Unit),
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
        val client = createMCPClient(MCPClientConfig(transport = transport))
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
        val client = createMCPClient(MCPClientConfig(transport = transport))

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
        val client = createMCPClient(MCPClientConfig(transport = transport))
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
        assertEquals("accept", response.result.jsonObject["action"]!!.jsonPrimitive.content)
        assertEquals(true, response.result.jsonObject["content"]!!.jsonObject["confirmed"]!!.jsonPrimitive.content.toBoolean())
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
        val client = createMCPClient(MCPClientConfig(transport = transport))

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
        val client = createMCPClient(MCPClientConfig(transport = transport))

        val before = transport.sent.size
        val error = assertFailsWith<MCPClientError> { client.listTools() }
        assertEquals("Server does not support tools", error.message)
        assertEquals(before, transport.sent.size)
    }

    @Test
    fun `JSON-RPC parser maps requests notifications responses and errors`() {
        assertIs<JSONRPCRequest>(parseJSONRPCMessage("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}"""))
        assertIs<JSONRPCNotification>(parseJSONRPCMessage("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""))
        assertIs<JSONRPCResponse>(parseJSONRPCMessage("""{"jsonrpc":"2.0","id":1,"result":{"ok":true}}"""))
        val error = assertIs<JSONRPCError>(
            parseJSONRPCMessage("""{"jsonrpc":"2.0","id":1,"error":{"code":-1,"message":"bad"}}"""),
        )
        assertEquals(-1, error.error.code)
    }

    @Test
    fun `JSON-RPC parser rejects malformed envelopes through wire decoder`() {
        assertFailsWith<WireDecodeException> {
            parseJSONRPCMessage("""{"jsonrpc":"2.0","id":1,"method":"tools/list","unexpected":true}""")
        }
        assertFailsWith<WireDecodeException> {
            parseJSONRPCMessage("""{"jsonrpc":"1.0","id":1,"method":"tools/list"}""")
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
        val client = createMCPClient(MCPClientConfig(transport = transport, onUncaughtError = { uncaught += it }))

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
        val client = createMCPClient(MCPClientConfig(transport = transport))
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
    fun `auth returns authorized with existing tokens and redirect when tokens are absent`() = runTest {
        val authorized = MemoryOAuthProvider(tokens = OAuthTokens(accessToken = "token", tokenType = "Bearer"))
        assertEquals(AuthResult.AUTHORIZED, auth(authorized, AuthOptions(serverUrl = "https://mcp.example.com")))

        val redirect = MemoryOAuthProvider(tokens = null)
        assertEquals(AuthResult.REDIRECT, auth(redirect, AuthOptions(serverUrl = "https://mcp.example.com", scope = "tools")))
        assertNotNull(redirect.lastAuthorizationUrl)
        assertTrue(redirect.lastAuthorizationUrl!!.startsWith("https://mcp.example.com/authorize?"))
        assertTrue("scope=tools" in redirect.lastAuthorizationUrl!!)
    }

    @Test
    fun `auth starts authorization with dynamic registration and PKCE`() = runTest {
        val fixture = createTestServer(
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
            auth(
                provider,
                AuthOptions(serverUrl = "https://auth.example.com", scope = "tools offline_access", client = fixture.httpClient()),
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
        val fixture = createTestServer(
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
            onAddClientAuthentication = { _, params, _, _ ->
                delay(5)
                params["client_id"] = "set-by-async-provider"
                params["client_secret"] = "secret-by-async-provider"
                params["example_url"] = "https://auth.example.com"
            },
        )
        provider.saveState("state-1")

        assertEquals(
            AuthResult.AUTHORIZED,
            auth(
                provider,
                AuthOptions(
                    serverUrl = "https://auth.example.com",
                    authorizationCode = "code123",
                    callbackState = "state-1",
                    client = fixture.httpClient(),
                ),
            ),
        )

        assertEquals("async-token", provider.tokens()?.accessToken)
        assertEquals("refresh-token", provider.tokens()?.refreshToken)
    }

    @Test
    fun `auth refreshes tokens preserves refresh token and awaits async client authentication`() = runTest {
        val fixture = createTestServer(
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
            onAddClientAuthentication = { _, params, _, _ ->
                delay(5)
                params["client_id"] = "set-by-async-provider"
                params["client_secret"] = "secret-by-async-provider"
                params["example_url"] = "https://auth.example.com"
            },
        )

        assertEquals(AuthResult.AUTHORIZED, auth(provider, AuthOptions(serverUrl = "https://auth.example.com", client = fixture.httpClient())))

        assertEquals("new-token", provider.tokens()?.accessToken)
        assertEquals("old-refresh", provider.tokens()?.refreshToken)
    }

    @Test
    fun `auth uses protected resource metadata for authorization server and redirect resource`() = runTest {
        val fixture = createTestServer(
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
            auth(provider, AuthOptions(serverUrl = "https://api.example.com/mcp-server", client = fixture.httpClient())),
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
        val fixture = createTestServer(
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
            auth(
                provider,
                AuthOptions(
                    serverUrl = "https://api.example.com/mcp-server",
                    authorizationCode = "code",
                    callbackState = "state-1",
                    client = fixture.httpClient(),
                ),
            ),
        )
        assertEquals("access-token", provider.tokens()?.accessToken)

        assertEquals(
            AuthResult.AUTHORIZED,
            auth(provider, AuthOptions(serverUrl = "https://api.example.com/mcp-server", client = fixture.httpClient())),
        )
        assertEquals("refreshed", provider.tokens()?.accessToken)
        assertEquals("refresh-token", provider.tokens()?.refreshToken)
    }

    @Test
    fun `HTTP MCP transport performs initialize post session headers and tool list`() = runTest {
        val fixture = createTestServer(
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

        val client = createMCPClient(
            MCPClientConfig(
                transport = HttpMCPTransport(
                    client = fixture.httpClient(),
                    url = "https://mcp.test/mcp",
                    headers = mapOf("X-Test" to "transport"),
                ),
            ),
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
        val fixture = createTestServer(
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
        transport.onMessage = { received = it }

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
        val fixture = createTestServer(
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
                "https://mcp.test/messages" to UrlHandler(UrlResponse.Empty(status = 202)),
            ),
        )
        fixture.server.start()
        val authProvider = MemoryOAuthProvider(tokens = OAuthTokens(accessToken = "token", tokenType = "Bearer"))
        val transport = SseMCPTransport(fixture.httpClient(), "https://mcp.test/sse", authProvider = authProvider)

        transport.start()
        transport.close()

        val sseGets = fixture.calls.filter { it.requestUrl == "https://mcp.test/sse" && it.requestMethod == "GET" }
        assertEquals(2, sseGets.size)
        assertEquals("Bearer token", sseGets[1].requestHeaders.headerValue(HttpHeaders.Authorization))
    }

    @Test
    fun `HTTP MCP transport starts at most one inbound SSE reader after concurrent accepted notifications`() = runTest {
        val controller = TestResponseController()
        var getAttempts = 0
        val fixture = createTestServer(
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
    fun `stdio transport exchanges newline-delimited JSON-RPC with process`() = runTest {
        val transport = Experimental_StdioMCPTransport(
            StdioConfig(
                command = "/bin/sh",
                args = listOf("-c", "while IFS= read -r line; do printf '%s\\n' \"\$line\"; done"),
            ),
        )
        var received: JSONRPCMessage? = null
        transport.onMessage = { received = it }

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
        override var onClose: (() -> Unit)? = null
        override var onError: ((Throwable) -> Unit)? = null
        override var onMessage: (suspend (JSONRPCMessage) -> Unit)? = null
        override var protocolVersion: String? = null

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
            headers: MutableMap<String, String>,
            params: MutableMap<String, String>,
            url: String,
            metadata: AuthorizationServerMetadata?,
        ) -> Unit)? = null,
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
            headers: MutableMap<String, String>,
            params: MutableMap<String, String>,
            url: String,
            metadata: AuthorizationServerMetadata?,
        ) {
            onAddClientAuthentication?.invoke(headers, params, url, metadata)
        }
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
