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
class MCPClientTest : MCPClientTestBase() {

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
}
