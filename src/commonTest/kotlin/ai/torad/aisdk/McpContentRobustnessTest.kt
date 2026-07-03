package ai.torad.aisdk

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class McpContentRobustnessTest {
    /**
     * Regression: tool-result conversion read a content part's `type`/`text` via `.jsonPrimitive`,
     * which throws IllegalArgumentException on a JsonObject/JsonArray. MCP content is external/
     * untrusted, so a server returning a non-primitive `type` crashed the conversion with a
     * low-level cast error instead of degrading. Now the malformed part is skipped and the
     * documented MCPClientError is thrown.
     */
    @Test
    fun `extractStructuredContent tolerates a non-primitive type field`() {
        val result = CallToolResult(
            content = listOf(buildJsonObject { put("type", buildJsonObject { put("x", JsonPrimitive(1)) }) }),
        )

        val error = assertFailsWith<MCPClientError> {
            result.extractStructuredContent(JsonObject(emptyMap()), "tool")
        }
        assertTrue(
            error.message?.contains("did not return") == true,
            "a graceful MCP error, not a raw IllegalArgumentException from the cast",
        )
    }
}
