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
class MCPJsonRpcTest : MCPClientTestBase() {

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
}
