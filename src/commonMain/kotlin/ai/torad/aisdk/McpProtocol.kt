@file:Suppress("FunctionName", "PropertyName")

package ai.torad.aisdk

import dev.drewhamilton.poko.Poko
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
    val result: JsonElement? = JsonObject(emptyMap()),
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

public class MCPRequestOptions internal constructor(
    public val signal: AbortSignal? = null,
    public val timeoutMillis: Long? = null,
    public val maxTotalTimeoutMillis: Long? = null,
)

public class MCPRequestOptionsBuilder internal constructor() {
    private var signal: AbortSignal? = null
    private var timeoutMillis: Long? = null
    private var maxTotalTimeoutMillis: Long? = null

    public fun signal(value: AbortSignal?) {
        signal = value
    }

    public fun timeoutMillis(value: Long?) {
        timeoutMillis = value
    }

    public fun maxTotalTimeoutMillis(value: Long?) {
        maxTotalTimeoutMillis = value
    }

    internal fun build(): MCPRequestOptions =
        MCPRequestOptions(
            signal = signal,
            timeoutMillis = timeoutMillis,
            maxTotalTimeoutMillis = maxTotalTimeoutMillis,
        )
}

public fun MCPRequestOptions(
    block: MCPRequestOptionsBuilder.() -> Unit = {},
): MCPRequestOptions =
    MCPRequestOptionsBuilder().apply(block).build()

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
@Poko
public class MCPClientCapabilities(
    public val elicitation: ElicitationCapability? = null,
    public val experimental: JsonObject? = null,
)

@ExperimentalAiSdkApi
public typealias experimental_MCPClientCapabilities = MCPClientCapabilities

@Serializable
@Poko
public class MCPServerCapabilities(
    public val experimental: JsonObject? = null,
    public val logging: JsonObject? = null,
    public val prompts: JsonObject? = null,
    public val resources: JsonObject? = null,
    public val tools: JsonObject? = null,
    public val elicitation: ElicitationCapability? = null,
)

@Serializable
public data class MCPBaseParams(
    @SerialName("_meta") val meta: JsonObject? = null,
)

@Serializable
@Poko
public class InitializeResult(
    public val protocolVersion: String,
    public val capabilities: MCPServerCapabilities = MCPServerCapabilities(),
    public val serverInfo: Configuration,
    public val instructions: String? = null,
    @SerialName("_meta") public val meta: JsonObject? = null,
)

@Serializable
@Poko
public class MCPToolDefinition(
    public val name: String,
    public val title: String? = null,
    public val description: String? = null,
    public val inputSchema: JsonObject = JsonObject(mapOf("type" to JsonPrimitive("object"))),
    public val outputSchema: JsonObject? = null,
    public val annotations: JsonObject? = null,
    @SerialName("_meta") public val meta: JsonObject? = null,
) {
    internal fun toolMetadata(clientInfo: Configuration): Map<String, JsonElement> = buildMap {
        put("clientName", JsonPrimitive(clientInfo.name))
        put("mcpToolName", JsonPrimitive(name))
        title?.let { put("title", JsonPrimitive(it)) }
        meta?.let { put("_meta", it) }
    }
}

@Serializable
@Poko
public class ListToolsResult(
    public val tools: List<MCPToolDefinition>,
    public val nextCursor: String? = null,
    @SerialName("_meta") public val meta: JsonObject? = null,
)

@Serializable
@Poko
public class MCPToolSchema(
    public val inputSchema: JsonElement,
    public val outputSchema: JsonElement? = null,
)

public typealias MCPToolSchemas = Map<String, MCPToolSchema>

@Serializable
@Poko
public class CallToolResult(
    public val content: List<JsonObject> = emptyList(),
    public val structuredContent: JsonElement? = null,
    public val isError: Boolean = false,
    public val toolResult: JsonElement? = null,
    @SerialName("_meta") public val meta: JsonObject? = null,
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
@Poko
public class MCPResource(
    public val uri: String,
    public val name: String,
    public val title: String? = null,
    public val description: String? = null,
    public val mimeType: String? = null,
    public val size: Long? = null,
)

@Serializable
@Poko
public class ListResourcesResult(
    public val resources: List<MCPResource>,
    public val nextCursor: String? = null,
    @SerialName("_meta") public val meta: JsonObject? = null,
)

@Serializable
@Poko
public class MCPResourceTemplate(
    public val uriTemplate: String,
    public val name: String,
    public val title: String? = null,
    public val description: String? = null,
    public val mimeType: String? = null,
)

@Serializable
@Poko
public class ListResourceTemplatesResult(
    public val resourceTemplates: List<MCPResourceTemplate>,
    public val nextCursor: String? = null,
    @SerialName("_meta") public val meta: JsonObject? = null,
)

@Serializable
@Poko
public class ReadResourceResult(
    public val contents: List<JsonObject>,
    @SerialName("_meta") public val meta: JsonObject? = null,
)

@Serializable
@Poko
public class MCPPromptArgument(
    public val name: String,
    public val description: String? = null,
    public val required: Boolean? = null,
)

@Serializable
@Poko
public class MCPPrompt(
    public val name: String,
    public val title: String? = null,
    public val description: String? = null,
    public val arguments: List<MCPPromptArgument>? = null,
)

@Serializable
@Poko
public class ListPromptsResult(
    public val prompts: List<MCPPrompt>,
    public val nextCursor: String? = null,
    @SerialName("_meta") public val meta: JsonObject? = null,
)

@Serializable
@Poko
public class MCPPromptMessage(
    public val role: String,
    public val content: JsonObject,
)

@Serializable
@Poko
public class GetPromptResult(
    public val messages: List<MCPPromptMessage>,
    public val description: String? = null,
    @SerialName("_meta") public val meta: JsonObject? = null,
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
@Poko
public class ElicitResult(
    public val action: String,
    public val content: JsonObject? = null,
    @SerialName("_meta") public val meta: JsonObject? = null,
)
