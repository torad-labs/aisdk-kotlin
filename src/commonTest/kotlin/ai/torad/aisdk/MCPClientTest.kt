package ai.torad.aisdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
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
        val output = echoTool.executor(
            ToolExecutionContext(
                context = Unit,
                abortSignal = AbortSignalNever,
                stepNumber = 0,
                messages = emptyList(),
                toolCallId = "call_1",
            ),
            buildJsonObject { put("message", JsonPrimitive("hi")) },
        ).toList().single()

        assertEquals("echo", echoTool.name)
        assertEquals("Echo a message", echoTool.description)
        val descriptorSchema = Json.parseToJsonElement(toolSet.descriptors.single().parametersSchemaJson).jsonObject
        assertEquals(JsonPrimitive(false), descriptorSchema["additionalProperties"])

        val modelOutput = echoTool.toModelOutput!!.invoke(
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
        val output = weather.executor(
            ToolExecutionContext(Unit, AbortSignalNever, 0, emptyList(), "call_1"),
            buildJsonObject { put("city", JsonPrimitive("Austin")) },
        ).toList().single().jsonObject

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
    fun `stdio transport common implementation exposes explicit runtime boundary`() = runTest {
        val transport = Experimental_StdioMCPTransport(StdioConfig(command = "node", args = listOf("server.mjs")))

        val error = assertFailsWith<UnsupportedOperationException> { transport.start() }
        assertTrue(error.message!!.contains("platform process adapter"))
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

        override suspend fun start() {
            startCount += 1
        }

        override suspend fun send(message: JSONRPCMessage) {
            sent += message
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
    ) : OAuthClientProvider {
        var lastAuthorizationUrl: String? = null
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

        override suspend fun saveCodeVerifier(codeVerifier: String) = Unit
        override suspend fun codeVerifier(): String = "verifier"
        override suspend fun clientInformation(): OAuthClientInformation? =
            OAuthClientInformation(clientId = "client-id")
    }

    private companion object {
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
