package ai.torad.aisdk

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** Compiles and executes the MCP wiki snippets that do not require a live MCP server. */
class McpDocSnippetTest : MCPClientTestBase() {
    @Test
    fun `mcp wiki transport config and client factory snippets compile and run`() = runTest {
        val httpClient = HttpClient(
            MockEngine {
                error("MCP documentation snippets should not make network requests.")
            },
        )
        val token = "token"
        val transport = CreateMcpTransport(
            client = httpClient,
            config = MCPTransportConfig {
                type(MCPTransportKind.Http)
                url("https://tools.example.com/mcp")
                headers(mapOf("Authorization" to "Bearer $token"))
            },
        )

        val fakeTransport = FakeMCPTransport { message ->
            if (message is JSONRPCRequest && message.method == "initialize") {
                respond(message.id, initializeResult())
            }
        }
        val mcp = CreateMCPClient(
            MCPClientConfig {
                transport(fakeTransport)
            },
        )

        assertNotNull(transport)
        assertEquals(listOf(MCPTransportKind.Http, MCPTransportKind.Sse), MCPTransportKind.entries.toList())
        assertEquals("fixture-server", mcp.serverInfo.name)
        assertEquals(1, fakeTransport.startCount)

        mcp.close()
    }
}
