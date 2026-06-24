package ai.torad.aisdk

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.fail

class MCPProtocolChaosTest {

    @Test
    fun `JSON-RPC parser preserves numeric and string id shapes`() {
        val request = assertIs<JSONRPCRequest>(
            JSONRPCMessage.fromJson("""{"jsonrpc":"2.0","id":"abc","method":"tools/list"}"""),
        )
        assertEquals("abc", request.id.jsonPrimitive.content)

        val response = assertIs<JSONRPCResponse>(
            JSONRPCMessage.fromJson("""{"jsonrpc":"2.0","id":7,"result":{}}"""),
        )
        assertEquals("7", response.id.jsonPrimitive.content)
    }

    @Test
    fun `malformed notifications and responses are rejected at the envelope boundary`() {
        assertFailsWith<WireDecodeException> {
            JSONRPCMessage.fromJson("""{"jsonrpc":"2.0","params":{}}""")
        }
        assertFailsWith<WireDecodeException> {
            JSONRPCMessage.fromJson("""{"jsonrpc":"2.0","result":{}}""")
        }
        assertFailsWith<WireDecodeException> {
            JSONRPCMessage.fromJson("""{"jsonrpc":"2.0","method":3}""")
        }
    }

    @Test
    fun `duplicate response after request completion is treated as an unknown id`() = runTest {
        var completedToolsId: JsonElement? = null
        val transport = FakeMCPTransport { message ->
            when {
                message is JSONRPCRequest && message.method == "initialize" ->
                    respond(message.id, initializeResult())
                message is JSONRPCRequest && message.method == "tools/list" -> {
                    completedToolsId = message.id
                    respond(message.id, listToolsResult())
                }
            }
        }
        val client = CreateMCPClient(MCPClientConfig(transport = transport))

        assertEquals("echo", client.listTools().tools.single().name)
        assertFailsWith<MCPClientError> {
            transport.emitFromServer(
                JSONRPCResponse(
                    id = completedToolsId ?: fail("tools/list id missing"),
                    result = listToolsResult(),
                ),
            )
        }
        client.close()
    }

    @Test
    fun `late response after timeout cannot resurrect a drained request handler`() = runTest {
        val timedOutToolsId = CompletableDeferred<JsonElement>()
        val transport = FakeMCPTransport { message ->
            when {
                message is JSONRPCRequest && message.method == "initialize" ->
                    respond(message.id, initializeResult())
                message is JSONRPCRequest && message.method == "tools/list" ->
                    timedOutToolsId.complete(message.id)
            }
        }
        val client = CreateMCPClient(MCPClientConfig(transport = transport))

        assertFailsWith<TimeoutCancellationException> {
            client.listTools(options = MCPRequestOptions(timeoutMillis = 50))
        }
        assertFailsWith<MCPClientError> {
            transport.emitFromServer(JSONRPCResponse(id = timedOutToolsId.await(), result = listToolsResult()))
        }
        client.close()
    }

    private fun initializeResult(
        capabilities: MCPServerCapabilities = MCPServerCapabilities(tools = JsonObject(emptyMap())),
    ): JsonElement = json.encodeToJsonElement(
        InitializeResult.serializer(),
        InitializeResult(
            protocolVersion = LATEST_PROTOCOL_VERSION,
            capabilities = capabilities,
            serverInfo = Configuration(name = "chaos-server", version = "1.0.0"),
        ),
    )

    private fun listToolsResult(): JsonElement = json.encodeToJsonElement(
        ListToolsResult.serializer(),
        ListToolsResult(
            tools = listOf(
                MCPToolDefinition(
                    name = "echo",
                    description = "Echo",
                    inputSchema = buildJsonObject {
                        put("type", JsonPrimitive("object"))
                        put("required", JsonArray(listOf(JsonPrimitive("message"))))
                    },
                ),
            ),
        ),
    )

    private class FakeMCPTransport(
        private val handler: suspend FakeMCPTransport.(JSONRPCMessage) -> Unit,
    ) : MCPTransport {
        private var onClose: (() -> Unit)? = null
        private var onMessage: (suspend (JSONRPCMessage) -> Unit)? = null

        override fun setOnClose(handler: (() -> Unit)?) { onClose = handler }
        override fun setOnError(handler: ((Throwable) -> Unit)?) = Unit
        override fun setOnMessage(handler: (suspend (JSONRPCMessage) -> Unit)?) { onMessage = handler }
        override fun setProtocolVersion(version: String?) = Unit
        override suspend fun start() = Unit
        override suspend fun send(message: JSONRPCMessage) { handler(message) }
        override suspend fun close() { onClose?.invoke() }

        suspend fun respond(id: JsonElement, result: JsonElement) {
            onMessage?.invoke(JSONRPCResponse(id = id, result = result))
        }

        suspend fun emitFromServer(message: JSONRPCMessage) {
            onMessage?.invoke(message)
        }
    }

    private companion object {
        val json = mcpJson
    }
}
