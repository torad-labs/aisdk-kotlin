package ai.torad.aisdk

import kotlinx.serialization.KSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class McpProtocolPokoTest {
    @Test
    fun `mcp Poko protocol results round trip through mcpJson`() {
        val initialize = InitializeResult(
            protocolVersion = LATEST_PROTOCOL_VERSION,
            capabilities = MCPServerCapabilities(
                tools = buildJsonObject { put("listChanged", JsonPrimitive(true)) },
                resources = buildJsonObject { put("subscribe", JsonPrimitive(false)) },
            ),
            serverInfo = Configuration {
                name("server")
                version("1.0.0")
            },
            instructions = "Use tools carefully.",
            meta = buildJsonObject { put("traceId", JsonPrimitive("init-1")) },
        )
        assertMcpRoundTrip(InitializeResult.serializer(), initialize)
        assertMetaWireName(InitializeResult.serializer(), initialize)

        val listTools = ListToolsResult(
            tools = listOf(
                MCPToolDefinition(
                    name = "echo",
                    title = "Echo",
                    description = "Echo input.",
                    inputSchema = buildJsonObject {
                        put("type", JsonPrimitive("object"))
                        put("required", JsonArray(listOf(JsonPrimitive("message"))))
                    },
                    outputSchema = buildJsonObject { put("type", JsonPrimitive("object")) },
                    annotations = buildJsonObject { put("readOnlyHint", JsonPrimitive(true)) },
                    meta = buildJsonObject { put("toolMeta", JsonPrimitive("kept")) },
                ),
            ),
            nextCursor = "cursor-1",
            meta = buildJsonObject { put("traceId", JsonPrimitive("tools-1")) },
        )
        assertMcpRoundTrip(ListToolsResult.serializer(), listTools)
        assertMetaWireName(ListToolsResult.serializer(), listTools)

        val callTool = CallToolResult(
            content = listOf(
                buildJsonObject {
                    put("type", JsonPrimitive("text"))
                    put("text", JsonPrimitive("""{"ok":true}"""))
                },
            ),
            structuredContent = buildJsonObject { put("ok", JsonPrimitive(true)) },
            toolResult = buildJsonObject { put("raw", JsonPrimitive("value")) },
            meta = buildJsonObject { put("traceId", JsonPrimitive("call-1")) },
        )
        assertMcpRoundTrip(CallToolResult.serializer(), callTool)
        assertMetaWireName(CallToolResult.serializer(), callTool)
    }

    @Test
    fun `mcp Poko protocol result types keep value semantics`() {
        val resources = ListResourcesResult(
            resources = listOf(
                MCPResource(
                    uri = "file:///a.txt",
                    name = "a.txt",
                    title = "A",
                    description = "First file",
                    mimeType = "text/plain",
                    size = 10,
                ),
            ),
            nextCursor = "next",
        )
        val equalResources = ListResourcesResult(
            resources = listOf(
                MCPResource(
                    uri = "file:///a.txt",
                    name = "a.txt",
                    title = "A",
                    description = "First file",
                    mimeType = "text/plain",
                    size = 10,
                ),
            ),
            nextCursor = "next",
        )
        val differentResources = ListResourcesResult(
            resources = listOf(
                MCPResource(
                    uri = "file:///b.txt",
                    name = "b.txt",
                    title = "B",
                    description = "Second file",
                    mimeType = "text/plain",
                    size = 11,
                ),
            ),
            nextCursor = "next",
        )

        assertEquals(resources, equalResources)
        assertEquals(resources.hashCode(), equalResources.hashCode())
        assertNotEquals(resources, differentResources)
    }

    private fun <T> assertMcpRoundTrip(serializer: KSerializer<T>, value: T) {
        val encoded = mcpJson.encodeToString(serializer, value)
        val decoded = mcpJson.decodeFromString(serializer, encoded)
        assertEquals(value, decoded)
    }

    private fun <T> assertMetaWireName(serializer: KSerializer<T>, value: T) {
        val encoded = mcpJson.encodeToString(serializer, value)
        val json = mcpJson.parseToJsonElement(encoded).jsonObject
        assertTrue("_meta" in json.keys, encoded)
        assertFalse("meta" in json.keys, encoded)
    }
}
