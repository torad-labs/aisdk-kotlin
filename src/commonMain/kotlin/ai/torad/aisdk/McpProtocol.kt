@file:Suppress("FunctionName", "PropertyName")

package ai.torad.aisdk

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal const val JSONRPC_VERSION: String = "2.0"

@Serializable
public data class JSONRPCRequest(
    val id: JsonElement,
    val method: String,
    val params: JsonObject? = null,
    val jsonrpc: String = JSONRPC_VERSION,
) : JSONRPCMessage

@Serializable
public data class JSONRPCNotification(
    val method: String,
    val params: JsonObject? = null,
    val jsonrpc: String = JSONRPC_VERSION,
) : JSONRPCMessage

@Serializable
public data class JSONRPCResponse(
    val id: JsonElement,
    val result: JsonElement = JsonObject(emptyMap()),
    val jsonrpc: String = JSONRPC_VERSION,
) : JSONRPCMessage

@Serializable
public data class JSONRPCError(
    val id: JsonElement,
    val error: JSONRPCErrorData,
    val jsonrpc: String = JSONRPC_VERSION,
) : JSONRPCMessage

@Serializable
public data class JSONRPCErrorData(
    val code: Int,
    val message: String,
    val data: JsonElement? = null,
)

public data class MCPRequestOptions(
    val signal: AbortSignal? = null,
    val timeoutMillis: Long? = null,
    val maxTotalTimeoutMillis: Long? = null,
)

@Serializable
public data class Configuration(
    val name: String,
    val version: String,
    val title: String? = null,
)

@Serializable
public data class ElicitationCapability(
    val applyDefaults: Boolean? = null,
)

@Serializable
public data class MCPClientCapabilities(
    val elicitation: ElicitationCapability? = null,
    val experimental: JsonObject? = null,
)

@ExperimentalAiSdkApi
public typealias experimental_MCPClientCapabilities = MCPClientCapabilities

@Serializable
public data class MCPServerCapabilities(
    val experimental: JsonObject? = null,
    val logging: JsonObject? = null,
    val prompts: JsonObject? = null,
    val resources: JsonObject? = null,
    val tools: JsonObject? = null,
    val elicitation: ElicitationCapability? = null,
)

@Serializable
public data class MCPBaseParams(
    @SerialName("_meta") val meta: JsonObject? = null,
)

@Serializable
public data class InitializeResult(
    val protocolVersion: String,
    val capabilities: MCPServerCapabilities = MCPServerCapabilities(),
    val serverInfo: Configuration,
    val instructions: String? = null,
    @SerialName("_meta") val meta: JsonObject? = null,
)

@Serializable
public data class MCPToolDefinition(
    val name: String,
    val title: String? = null,
    val description: String? = null,
    val inputSchema: JsonObject = JsonObject(mapOf("type" to JsonPrimitive("object"))),
    val outputSchema: JsonObject? = null,
    val annotations: JsonObject? = null,
    @SerialName("_meta") val meta: JsonObject? = null,
) {
    internal fun toolMetadata(clientInfo: Configuration): Map<String, JsonElement> = buildMap {
        put("clientName", JsonPrimitive(clientInfo.name))
        put("mcpToolName", JsonPrimitive(name))
        title?.let { put("title", JsonPrimitive(it)) }
        meta?.let { put("_meta", it) }
    }
}

@Serializable
public data class ListToolsResult(
    val tools: List<MCPToolDefinition>,
    val nextCursor: String? = null,
    @SerialName("_meta") val meta: JsonObject? = null,
)

@Serializable
public data class MCPToolSchema(
    val inputSchema: JsonElement,
    val outputSchema: JsonElement? = null,
)

public typealias MCPToolSchemas = Map<String, MCPToolSchema>

@Serializable
public data class CallToolResult(
    val content: List<JsonObject> = emptyList(),
    val structuredContent: JsonElement? = null,
    val isError: Boolean = false,
    val toolResult: JsonElement? = null,
    @SerialName("_meta") val meta: JsonObject? = null,
) {
    internal fun extractStructuredContent(outputSchema: JsonElement, toolName: String): JsonElement {
        structuredContent?.let { return it }

        val text = content.firstNotNullOfOrNull { part ->
            // `as? JsonPrimitive` (server-controlled content): a non-primitive type/text degrades to
            // null and the part is skipped, rather than throwing IllegalArgumentException.
            if ((part["type"] as? JsonPrimitive)?.contentOrNull == "text") {
                (part["text"] as? JsonPrimitive)?.contentOrNull
            } else {
                null
            }
        }
        if (text != null) {
            return try {
                mcpJson.parseToJsonElement(text)
            } catch (error: Throwable) {
                throw MCPClientError(
                    message = "Tool \"$toolName\" returned content that does not match the expected outputSchema",
                    data = outputSchema,
                    cause = error,
                )
            }
        }

        throw MCPClientError("Tool \"$toolName\" did not return structuredContent or parseable text content")
    }
}

@Serializable
public data class MCPResource(
    val uri: String,
    val name: String,
    val title: String? = null,
    val description: String? = null,
    val mimeType: String? = null,
    val size: Long? = null,
)

@Serializable
public data class ListResourcesResult(
    val resources: List<MCPResource>,
    val nextCursor: String? = null,
    @SerialName("_meta") val meta: JsonObject? = null,
)

@Serializable
public data class MCPResourceTemplate(
    val uriTemplate: String,
    val name: String,
    val title: String? = null,
    val description: String? = null,
    val mimeType: String? = null,
)

@Serializable
public data class ListResourceTemplatesResult(
    val resourceTemplates: List<MCPResourceTemplate>,
    val nextCursor: String? = null,
    @SerialName("_meta") val meta: JsonObject? = null,
)

@Serializable
public data class ReadResourceResult(
    val contents: List<JsonObject>,
    @SerialName("_meta") val meta: JsonObject? = null,
)

@Serializable
public data class MCPPromptArgument(
    val name: String,
    val description: String? = null,
    val required: Boolean? = null,
)

@Serializable
public data class MCPPrompt(
    val name: String,
    val title: String? = null,
    val description: String? = null,
    val arguments: List<MCPPromptArgument>? = null,
)

@Serializable
public data class ListPromptsResult(
    val prompts: List<MCPPrompt>,
    val nextCursor: String? = null,
    @SerialName("_meta") val meta: JsonObject? = null,
)

@Serializable
public data class MCPPromptMessage(
    val role: String,
    val content: JsonObject,
)

@Serializable
public data class GetPromptResult(
    val messages: List<MCPPromptMessage>,
    val description: String? = null,
    @SerialName("_meta") val meta: JsonObject? = null,
)

public object ElicitationRequestSchema
public object ElicitResultSchema

@Serializable
public data class ElicitationRequestParams(
    val message: String,
    val requestedSchema: JsonElement,
    @SerialName("_meta") val meta: JsonObject? = null,
)

@Serializable
public data class ElicitationRequest(
    val params: ElicitationRequestParams,
    val method: String = "elicitation/create",
)

@Serializable
public data class ElicitResult(
    val action: String,
    val content: JsonObject? = null,
    @SerialName("_meta") val meta: JsonObject? = null,
)
