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
/** @since 0.3.0-beta01 */
public data class JSONRPCRequest(
    val id: JsonElement,
    val method: String,
    val params: JsonObject? = null,
    val jsonrpc: String = JSONRPC_VERSION,
) : JSONRPCMessage

@Serializable
/** @since 0.3.0-beta01 */
public data class JSONRPCNotification(
    val method: String,
    val params: JsonObject? = null,
    val jsonrpc: String = JSONRPC_VERSION,
) : JSONRPCMessage

@Serializable
/** @since 0.3.0-beta01 */
public data class JSONRPCResponse(
    val id: JsonElement,
    val result: JsonElement? = JsonObject(emptyMap()),
    val jsonrpc: String = JSONRPC_VERSION,
) : JSONRPCMessage

@Serializable
/** @since 0.3.0-beta01 */
public data class JSONRPCError(
    val id: JsonElement,
    val error: JSONRPCErrorData,
    val jsonrpc: String = JSONRPC_VERSION,
) : JSONRPCMessage

@Serializable
/** @since 0.3.0-beta01 */
public data class JSONRPCErrorData(
    val code: Int,
    val message: String,
    val data: JsonElement? = null,
)

/** @since 0.3.0-beta01 */
public class MCPRequestOptions internal constructor(
    /** @since 0.3.0-beta01 */
    public val signal: AbortSignal? = null,
    /** @since 0.3.0-beta01 */
    public val timeoutMillis: Long? = null,
    /** @since 0.3.0-beta01 */
    public val maxTotalTimeoutMillis: Long? = null,
)

/** @since 0.3.0-beta01 */
public class MCPRequestOptionsBuilder {
    private var signal: AbortSignal? = null
    private var timeoutMillis: Long? = null
    private var maxTotalTimeoutMillis: Long? = null

    /** @since 0.3.0-beta01 */
    public fun signal(value: AbortSignal?): MCPRequestOptionsBuilder {
        signal = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun timeoutMillis(value: Long?): MCPRequestOptionsBuilder {
        timeoutMillis = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun maxTotalTimeoutMillis(value: Long?): MCPRequestOptionsBuilder {
        maxTotalTimeoutMillis = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun build(): MCPRequestOptions =
        MCPRequestOptions(
            signal = signal,
            timeoutMillis = timeoutMillis,
            maxTotalTimeoutMillis = maxTotalTimeoutMillis,
        )
}

/** @since 0.3.0-beta01 */
public fun MCPRequestOptions(
    block: MCPRequestOptionsBuilder.() -> Unit = {},
): MCPRequestOptions =
    MCPRequestOptionsBuilder().apply(block).build()

@Serializable
@Poko
/** @since 0.3.0-beta01 */
public class Configuration internal constructor(
    /** @since 0.3.0-beta01 */
    public val name: String,
    /** @since 0.3.0-beta01 */
    public val version: String,
    /** @since 0.3.0-beta01 */
    public val title: String? = null,
)

/** @since 0.3.0-beta01 */
public class ConfigurationBuilder {
    private var name: String? = null
    private var version: String? = null
    private var title: String? = null

    /** @since 0.3.0-beta01 */
    public fun name(value: String): ConfigurationBuilder {
        name = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun version(value: String): ConfigurationBuilder {
        version = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun title(value: String?): ConfigurationBuilder {
        title = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun build(): Configuration =
        Configuration(
            name = requireNotNull(name) { "Configuration.name is required" },
            version = requireNotNull(version) { "Configuration.version is required" },
            title = title,
        )
}

/** @since 0.3.0-beta01 */
public fun Configuration(
    block: ConfigurationBuilder.() -> Unit = {},
): Configuration =
    ConfigurationBuilder().apply(block).build()

@Serializable
@Poko
/** @since 0.3.0-beta01 */
public class ElicitationCapability internal constructor(
    /** @since 0.3.0-beta01 */
    public val applyDefaults: Boolean? = null,
)

/** @since 0.3.0-beta01 */
public class ElicitationCapabilityBuilder {
    private var applyDefaults: Boolean? = null

    /** @since 0.3.0-beta01 */
    public fun applyDefaults(value: Boolean?): ElicitationCapabilityBuilder {
        applyDefaults = value
        return this
    }

    /** @since 0.3.0-beta01 */
    public fun build(): ElicitationCapability =
        ElicitationCapability(
            applyDefaults = applyDefaults,
        )
}

/** @since 0.3.0-beta01 */
public fun ElicitationCapability(
    block: ElicitationCapabilityBuilder.() -> Unit = {},
): ElicitationCapability =
    ElicitationCapabilityBuilder().apply(block).build()

@Serializable
@Poko
/** @since 0.3.0-beta01 */
public class MCPClientCapabilities(
    /** @since 0.3.0-beta01 */
    public val elicitation: ElicitationCapability? = null,
    /** @since 0.3.0-beta01 */
    public val experimental: JsonObject? = null,
)

@ExperimentalAiSdkApi
public typealias experimental_MCPClientCapabilities = MCPClientCapabilities

@Serializable
@Poko
/** @since 0.3.0-beta01 */
public class MCPServerCapabilities(
    /** @since 0.3.0-beta01 */
    public val experimental: JsonObject? = null,
    /** @since 0.3.0-beta01 */
    public val logging: JsonObject? = null,
    /** @since 0.3.0-beta01 */
    public val prompts: JsonObject? = null,
    /** @since 0.3.0-beta01 */
    public val resources: JsonObject? = null,
    /** @since 0.3.0-beta01 */
    public val tools: JsonObject? = null,
    /** @since 0.3.0-beta01 */
    public val elicitation: ElicitationCapability? = null,
)

@Serializable
/** @since 0.3.0-beta01 */
public data class MCPBaseParams(
    @SerialName("_meta") val meta: JsonObject? = null,
)

@Serializable
@Poko
/** @since 0.3.0-beta01 */
public class InitializeResult(
    /** @since 0.3.0-beta01 */
    public val protocolVersion: String,
    /** @since 0.3.0-beta01 */
    public val capabilities: MCPServerCapabilities = MCPServerCapabilities(),
    /** @since 0.3.0-beta01 */
    public val serverInfo: Configuration,
    /** @since 0.3.0-beta01 */
    public val instructions: String? = null,
    @SerialName("_meta") public val meta: JsonObject? = null,
)

@Serializable
@Poko
/** @since 0.3.0-beta01 */
public class MCPToolDefinition(
    /** @since 0.3.0-beta01 */
    public val name: String,
    /** @since 0.3.0-beta01 */
    public val title: String? = null,
    /** @since 0.3.0-beta01 */
    public val description: String? = null,
    /** @since 0.3.0-beta01 */
    public val inputSchema: JsonObject = JsonObject(mapOf("type" to JsonPrimitive("object"))),
    /** @since 0.3.0-beta01 */
    public val outputSchema: JsonObject? = null,
    /** @since 0.3.0-beta01 */
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
/** @since 0.3.0-beta01 */
public class ListToolsResult(
    /** @since 0.3.0-beta01 */
    public val tools: List<MCPToolDefinition>,
    /** @since 0.3.0-beta01 */
    public val nextCursor: String? = null,
    @SerialName("_meta") public val meta: JsonObject? = null,
)

@Serializable
@Poko
/** @since 0.3.0-beta01 */
public class MCPToolSchema(
    /** @since 0.3.0-beta01 */
    public val inputSchema: JsonElement,
    /** @since 0.3.0-beta01 */
    public val outputSchema: JsonElement? = null,
)

public typealias MCPToolSchemas = Map<String, MCPToolSchema>

@Serializable
@Poko
/** @since 0.3.0-beta01 */
public class CallToolResult(
    /** @since 0.3.0-beta01 */
    public val content: List<JsonObject> = emptyList(),
    /** @since 0.3.0-beta01 */
    public val structuredContent: JsonElement? = null,
    /** @since 0.3.0-beta01 */
    public val isError: Boolean = false,
    /** @since 0.3.0-beta01 */
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
/** @since 0.3.0-beta01 */
public class MCPResource(
    /** @since 0.3.0-beta01 */
    public val uri: String,
    /** @since 0.3.0-beta01 */
    public val name: String,
    /** @since 0.3.0-beta01 */
    public val title: String? = null,
    /** @since 0.3.0-beta01 */
    public val description: String? = null,
    /** @since 0.3.0-beta01 */
    public val mimeType: String? = null,
    /** @since 0.3.0-beta01 */
    public val size: Long? = null,
)

@Serializable
@Poko
/** @since 0.3.0-beta01 */
public class ListResourcesResult(
    /** @since 0.3.0-beta01 */
    public val resources: List<MCPResource>,
    /** @since 0.3.0-beta01 */
    public val nextCursor: String? = null,
    @SerialName("_meta") public val meta: JsonObject? = null,
)

@Serializable
@Poko
/** @since 0.3.0-beta01 */
public class MCPResourceTemplate(
    /** @since 0.3.0-beta01 */
    public val uriTemplate: String,
    /** @since 0.3.0-beta01 */
    public val name: String,
    /** @since 0.3.0-beta01 */
    public val title: String? = null,
    /** @since 0.3.0-beta01 */
    public val description: String? = null,
    /** @since 0.3.0-beta01 */
    public val mimeType: String? = null,
)

@Serializable
@Poko
/** @since 0.3.0-beta01 */
public class ListResourceTemplatesResult(
    /** @since 0.3.0-beta01 */
    public val resourceTemplates: List<MCPResourceTemplate>,
    /** @since 0.3.0-beta01 */
    public val nextCursor: String? = null,
    @SerialName("_meta") public val meta: JsonObject? = null,
)

@Serializable
@Poko
/** @since 0.3.0-beta01 */
public class ReadResourceResult(
    /** @since 0.3.0-beta01 */
    public val contents: List<JsonObject>,
    @SerialName("_meta") public val meta: JsonObject? = null,
)

@Serializable
@Poko
/** @since 0.3.0-beta01 */
public class MCPPromptArgument(
    /** @since 0.3.0-beta01 */
    public val name: String,
    /** @since 0.3.0-beta01 */
    public val description: String? = null,
    /** @since 0.3.0-beta01 */
    public val required: Boolean? = null,
)

@Serializable
@Poko
/** @since 0.3.0-beta01 */
public class MCPPrompt(
    /** @since 0.3.0-beta01 */
    public val name: String,
    /** @since 0.3.0-beta01 */
    public val title: String? = null,
    /** @since 0.3.0-beta01 */
    public val description: String? = null,
    /** @since 0.3.0-beta01 */
    public val arguments: List<MCPPromptArgument>? = null,
)

@Serializable
@Poko
/** @since 0.3.0-beta01 */
public class ListPromptsResult(
    /** @since 0.3.0-beta01 */
    public val prompts: List<MCPPrompt>,
    /** @since 0.3.0-beta01 */
    public val nextCursor: String? = null,
    @SerialName("_meta") public val meta: JsonObject? = null,
)

@Serializable
@Poko
/** @since 0.3.0-beta01 */
public class MCPPromptMessage(
    /** @since 0.3.0-beta01 */
    public val role: String,
    /** @since 0.3.0-beta01 */
    public val content: JsonObject,
)

@Serializable
@Poko
/** @since 0.3.0-beta01 */
public class GetPromptResult(
    /** @since 0.3.0-beta01 */
    public val messages: List<MCPPromptMessage>,
    /** @since 0.3.0-beta01 */
    public val description: String? = null,
    @SerialName("_meta") public val meta: JsonObject? = null,
)

/** @since 0.3.0-beta01 */
public object ElicitationRequestSchema
/** @since 0.3.0-beta01 */
public object ElicitResultSchema

@Serializable
/** @since 0.3.0-beta01 */
public data class ElicitationRequestParams(
    val message: String,
    val requestedSchema: JsonElement,
    @SerialName("_meta") val meta: JsonObject? = null,
)

@Serializable
/** @since 0.3.0-beta01 */
public data class ElicitationRequest(
    val params: ElicitationRequestParams,
    val method: String = "elicitation/create",
)

@Serializable
@Poko
/** @since 0.3.0-beta01 */
public class ElicitResult(
    /** @since 0.3.0-beta01 */
    public val action: String,
    /** @since 0.3.0-beta01 */
    public val content: JsonObject? = null,
    @SerialName("_meta") public val meta: JsonObject? = null,
)
