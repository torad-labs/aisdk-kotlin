@file:Suppress("FunctionName", "PropertyName")

package ai.torad.aisdk

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readLine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

const val MCP_PACKAGE_VERSION = "1.0.45"
const val LATEST_PROTOCOL_VERSION = "2025-11-25"

val SUPPORTED_PROTOCOL_VERSIONS: List<String> = listOf(
    LATEST_PROTOCOL_VERSION,
    "2025-06-18",
    "2025-03-26",
    "2024-11-05",
)

private const val JSONRPC_VERSION = "2.0"
private const val DEFAULT_MCP_CLIENT_NAME = "ai-sdk-mcp-client"
private const val DEFAULT_MCP_CLIENT_VERSION = "1.0.0"

internal val mcpJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

class MCPClientError(
    message: String,
    val code: Int? = null,
    val data: JsonElement? = null,
    cause: Throwable? = null,
) : AiSdkException(message, cause)

@Serializable
sealed interface JSONRPCMessage

@Serializable
data class JSONRPCRequest(
    val id: JsonElement,
    val method: String,
    val params: JsonObject? = null,
    val jsonrpc: String = JSONRPC_VERSION,
) : JSONRPCMessage

@Serializable
data class JSONRPCNotification(
    val method: String,
    val params: JsonObject? = null,
    val jsonrpc: String = JSONRPC_VERSION,
) : JSONRPCMessage

@Serializable
data class JSONRPCResponse(
    val id: JsonElement,
    val result: JsonElement = JsonObject(emptyMap()),
    val jsonrpc: String = JSONRPC_VERSION,
) : JSONRPCMessage

@Serializable
data class JSONRPCError(
    val id: JsonElement,
    val error: JSONRPCErrorData,
    val jsonrpc: String = JSONRPC_VERSION,
) : JSONRPCMessage

@Serializable
data class JSONRPCErrorData(
    val code: Int,
    val message: String,
    val data: JsonElement? = null,
)

@Serializable
private data class JSONRPCEnvelope(
    val jsonrpc: String,
    val id: JsonElement? = null,
    val method: String? = null,
    val params: JsonObject? = null,
    val result: JsonElement? = null,
    val error: JSONRPCErrorData? = null,
)

fun JSONRPCMessage.toJsonElement(): JsonObject = when (this) {
    is JSONRPCRequest -> mcpJson.encodeToJsonElement(JSONRPCRequest.serializer(), this).jsonObject
    is JSONRPCNotification -> mcpJson.encodeToJsonElement(JSONRPCNotification.serializer(), this).jsonObject
    is JSONRPCResponse -> mcpJson.encodeToJsonElement(JSONRPCResponse.serializer(), this).jsonObject
    is JSONRPCError -> mcpJson.encodeToJsonElement(JSONRPCError.serializer(), this).jsonObject
}

fun JSONRPCMessage.toJsonString(): String = toJsonElement().toString()

fun parseJSONRPCMessage(text: String): JSONRPCMessage {
    val obj = WireDecoder.parseObject(text, provider = "mcp", operation = "json-rpc message")
    val envelope = WireDecoder.decode(
        JSONRPCEnvelope.serializer(),
        obj,
        provider = "mcp",
        operation = "json-rpc message",
    )
    if (envelope.jsonrpc != JSONRPC_VERSION) {
        WireDecoder.fail(
            provider = "mcp",
            operation = "json-rpc message",
            path = "$.jsonrpc",
            message = "expected JSON-RPC version $JSONRPC_VERSION",
            value = obj["jsonrpc"],
        )
    }
    val hasId = "id" in obj
    return when {
        envelope.method != null && hasId -> WireDecoder.decode(JSONRPCRequest.serializer(), obj, "mcp", "json-rpc request")
        envelope.method != null -> WireDecoder.decode(JSONRPCNotification.serializer(), obj, "mcp", "json-rpc notification")
        envelope.result != null && hasId -> WireDecoder.decode(JSONRPCResponse.serializer(), obj, "mcp", "json-rpc response")
        envelope.error != null && hasId -> WireDecoder.decode(JSONRPCError.serializer(), obj, "mcp", "json-rpc error")
        else -> WireDecoder.fail("mcp", "json-rpc message", "$", "invalid JSON-RPC envelope", obj)
    }
}

/**
 * Transport interface for MCP communication. Implementations may wrap stdio,
 * Streamable HTTP, SSE, WebSockets, or a test fixture.
 */
interface MCPTransport {
    var onClose: (() -> Unit)?
    var onError: ((Throwable) -> Unit)?
    var onMessage: (suspend (JSONRPCMessage) -> Unit)?
    var protocolVersion: String?

    suspend fun start()
    suspend fun send(message: JSONRPCMessage)
    suspend fun close()
}

data class MCPRequestOptions(
    val signal: AbortSignal? = null,
    val timeoutMillis: Long? = null,
    val maxTotalTimeoutMillis: Long? = null,
)

@Serializable
data class Configuration(
    val name: String,
    val version: String,
    val title: String? = null,
)

@Serializable
data class ElicitationCapability(
    val applyDefaults: Boolean? = null,
)

@Serializable
data class MCPClientCapabilities(
    val elicitation: ElicitationCapability? = null,
    val experimental: JsonObject? = null,
)

typealias experimental_MCPClientCapabilities = MCPClientCapabilities

@Serializable
data class MCPServerCapabilities(
    val experimental: JsonObject? = null,
    val logging: JsonObject? = null,
    val prompts: JsonObject? = null,
    val resources: JsonObject? = null,
    val tools: JsonObject? = null,
    val elicitation: ElicitationCapability? = null,
)

@Serializable
data class MCPBaseParams(
    @SerialName("_meta") val meta: JsonObject? = null,
)

@Serializable
data class InitializeResult(
    val protocolVersion: String,
    val capabilities: MCPServerCapabilities = MCPServerCapabilities(),
    val serverInfo: Configuration,
    val instructions: String? = null,
    @SerialName("_meta") val meta: JsonObject? = null,
)

@Serializable
data class MCPToolDefinition(
    val name: String,
    val title: String? = null,
    val description: String? = null,
    val inputSchema: JsonObject = JsonObject(mapOf("type" to JsonPrimitive("object"))),
    val outputSchema: JsonObject? = null,
    val annotations: JsonObject? = null,
    @SerialName("_meta") val meta: JsonObject? = null,
)

@Serializable
data class ListToolsResult(
    val tools: List<MCPToolDefinition>,
    val nextCursor: String? = null,
    @SerialName("_meta") val meta: JsonObject? = null,
)

@Serializable
data class MCPToolSchema(
    val inputSchema: JsonElement,
    val outputSchema: JsonElement? = null,
)

typealias MCPToolSchemas = Map<String, MCPToolSchema>

@Serializable
data class CallToolResult(
    val content: List<JsonObject> = emptyList(),
    val structuredContent: JsonElement? = null,
    val isError: Boolean = false,
    val toolResult: JsonElement? = null,
    @SerialName("_meta") val meta: JsonObject? = null,
)

@Serializable
data class MCPResource(
    val uri: String,
    val name: String,
    val title: String? = null,
    val description: String? = null,
    val mimeType: String? = null,
    val size: Long? = null,
)

@Serializable
data class ListResourcesResult(
    val resources: List<MCPResource>,
    val nextCursor: String? = null,
    @SerialName("_meta") val meta: JsonObject? = null,
)

@Serializable
data class MCPResourceTemplate(
    val uriTemplate: String,
    val name: String,
    val title: String? = null,
    val description: String? = null,
    val mimeType: String? = null,
)

@Serializable
data class ListResourceTemplatesResult(
    val resourceTemplates: List<MCPResourceTemplate>,
    val nextCursor: String? = null,
    @SerialName("_meta") val meta: JsonObject? = null,
)

@Serializable
data class ReadResourceResult(
    val contents: List<JsonObject>,
    @SerialName("_meta") val meta: JsonObject? = null,
)

@Serializable
data class MCPPromptArgument(
    val name: String,
    val description: String? = null,
    val required: Boolean? = null,
)

@Serializable
data class MCPPrompt(
    val name: String,
    val title: String? = null,
    val description: String? = null,
    val arguments: List<MCPPromptArgument>? = null,
)

@Serializable
data class ListPromptsResult(
    val prompts: List<MCPPrompt>,
    val nextCursor: String? = null,
    @SerialName("_meta") val meta: JsonObject? = null,
)

@Serializable
data class MCPPromptMessage(
    val role: String,
    val content: JsonObject,
)

@Serializable
data class GetPromptResult(
    val messages: List<MCPPromptMessage>,
    val description: String? = null,
    @SerialName("_meta") val meta: JsonObject? = null,
)

object ElicitationRequestSchema
object ElicitResultSchema

@Serializable
data class ElicitationRequestParams(
    val message: String,
    val requestedSchema: JsonElement,
    @SerialName("_meta") val meta: JsonObject? = null,
)

@Serializable
data class ElicitationRequest(
    val params: ElicitationRequestParams,
    val method: String = "elicitation/create",
)

@Serializable
data class ElicitResult(
    val action: String,
    val content: JsonObject? = null,
    @SerialName("_meta") val meta: JsonObject? = null,
)

data class MCPClientConfig(
    val transport: MCPTransport,
    val onUncaughtError: ((Throwable) -> Unit)? = null,
    val clientName: String? = null,
    val name: String? = null,
    val version: String = DEFAULT_MCP_CLIENT_VERSION,
    val capabilities: MCPClientCapabilities = MCPClientCapabilities(),
)

typealias experimental_MCPClientConfig = MCPClientConfig

interface MCPClient {
    val serverInfo: Configuration
    val instructions: String?

    suspend fun <TContext> tools(schemas: MCPToolSchemas? = null): ToolSet<TContext>

    suspend fun listTools(
        params: JsonObject? = null,
        options: MCPRequestOptions? = null,
    ): ListToolsResult

    fun <TContext> toolsFromDefinitions(
        definitions: ListToolsResult,
        schemas: MCPToolSchemas? = null,
    ): ToolSet<TContext>

    suspend fun listResources(
        params: JsonObject? = null,
        options: MCPRequestOptions? = null,
    ): ListResourcesResult

    suspend fun readResource(
        uri: String,
        options: MCPRequestOptions? = null,
    ): ReadResourceResult

    suspend fun listResourceTemplates(
        options: MCPRequestOptions? = null,
    ): ListResourceTemplatesResult

    suspend fun experimental_listPrompts(
        params: JsonObject? = null,
        options: MCPRequestOptions? = null,
    ): ListPromptsResult

    suspend fun experimental_getPrompt(
        name: String,
        arguments: JsonObject? = null,
        options: MCPRequestOptions? = null,
    ): GetPromptResult

    fun onElicitationRequest(
        schema: ElicitationRequestSchema,
        handler: suspend (ElicitationRequest) -> ElicitResult,
    )

    suspend fun close()
}

typealias experimental_MCPClient = MCPClient

suspend fun createMCPClient(config: MCPClientConfig): MCPClient =
    DefaultMCPClient(config).also { it.init() }

suspend fun experimental_createMCPClient(config: MCPClientConfig): MCPClient =
    createMCPClient(config)

private class DefaultMCPClient(config: MCPClientConfig) : MCPClient {
    private val transport: MCPTransport = config.transport
    private val onUncaughtError = config.onUncaughtError
    private val clientInfo = Configuration(
        name = config.clientName ?: config.name ?: DEFAULT_MCP_CLIENT_NAME,
        version = config.version,
    )
    private val clientCapabilities = config.capabilities
    private val responseHandlers = mutableMapOf<String, CompletableDeferred<JsonElement>>()
    private val responseHandlersMutex = Mutex()
    private var requestMessageId: Long = 0
    private var isClosed = true
    private var serverCapabilities = MCPServerCapabilities()
    private var elicitationRequestHandler: (suspend (ElicitationRequest) -> ElicitResult)? = null
    private var _serverInfo = Configuration(name = "", version = "")
    private var _instructions: String? = null

    override val serverInfo: Configuration get() = _serverInfo
    override val instructions: String? get() = _instructions

    init {
        transport.onClose = { onClose() }
        transport.onError = { onError(it) }
        transport.onMessage = { message -> onMessage(message) }
    }

    suspend fun init() {
        try {
            transport.start()
            isClosed = false

            val result = request(
                method = "initialize",
                params = buildJsonObject {
                    put("protocolVersion", JsonPrimitive(LATEST_PROTOCOL_VERSION))
                    put("capabilities", mcpJson.encodeToJsonElement(MCPClientCapabilities.serializer(), clientCapabilities))
                    put("clientInfo", mcpJson.encodeToJsonElement(Configuration.serializer(), clientInfo))
                },
                serializer = InitializeResult.serializer(),
            )

            if (result.protocolVersion !in SUPPORTED_PROTOCOL_VERSIONS) {
                throw MCPClientError("Server's protocol version is not supported: ${result.protocolVersion}")
            }

            serverCapabilities = result.capabilities
            _serverInfo = result.serverInfo
            _instructions = result.instructions
            transport.protocolVersion = result.protocolVersion

            notification(method = "notifications/initialized")
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    override suspend fun close() {
        if (isClosed) return
        transport.close()
        onClose()
    }

    override suspend fun <TContext> tools(schemas: MCPToolSchemas?): ToolSet<TContext> =
        toolsFromDefinitions(listTools(), schemas)

    override suspend fun listTools(
        params: JsonObject?,
        options: MCPRequestOptions?,
    ): ListToolsResult = request("tools/list", params, ListToolsResult.serializer(), options)

    override fun <TContext> toolsFromDefinitions(
        definitions: ListToolsResult,
        schemas: MCPToolSchemas?,
    ): ToolSet<TContext> {
        val tools = definitions.tools.mapNotNull { definition ->
            if (schemas != null && definition.name !in schemas) {
                return@mapNotNull null
            }
            val selectedSchema = schemas?.get(definition.name)
            val inputSchema = selectedSchema?.inputSchema ?: schemaWithClosedAdditionalProperties(definition.inputSchema)
            val outputSchema = selectedSchema?.outputSchema
            val description = definition.description ?: definition.title ?: definition.name
            dynamicTool<TContext>(
                name = definition.name,
                description = description,
                inputSchemaJson = inputSchema.toString(),
                metadata = definition.toolMetadata(clientInfo),
                toModelOutput = { output, _ -> mcpToModelOutput(output) },
            ) { input ->
                val result = callTool(
                    name = definition.name,
                    args = input.asArgumentsObject(),
                    options = MCPRequestOptions(signal = abortSignal),
                )
                if (!result.isError && outputSchema != null) {
                    extractStructuredContent(result, outputSchema, definition.name)
                } else {
                    mcpJson.encodeToJsonElement(CallToolResult.serializer(), result)
                }
            }
        }
        return ToolSet(tools.associateBy { it.name })
    }

    override suspend fun listResources(
        params: JsonObject?,
        options: MCPRequestOptions?,
    ): ListResourcesResult = request("resources/list", params, ListResourcesResult.serializer(), options)

    override suspend fun readResource(
        uri: String,
        options: MCPRequestOptions?,
    ): ReadResourceResult = request(
        method = "resources/read",
        params = JsonObject(mapOf("uri" to JsonPrimitive(uri))),
        serializer = ReadResourceResult.serializer(),
        options = options,
    )

    override suspend fun listResourceTemplates(
        options: MCPRequestOptions?,
    ): ListResourceTemplatesResult = request(
        method = "resources/templates/list",
        serializer = ListResourceTemplatesResult.serializer(),
        options = options,
    )

    override suspend fun experimental_listPrompts(
        params: JsonObject?,
        options: MCPRequestOptions?,
    ): ListPromptsResult = request("prompts/list", params, ListPromptsResult.serializer(), options)

    override suspend fun experimental_getPrompt(
        name: String,
        arguments: JsonObject?,
        options: MCPRequestOptions?,
    ): GetPromptResult = request(
        method = "prompts/get",
        params = buildJsonObject {
            put("name", JsonPrimitive(name))
            if (arguments != null) put("arguments", arguments)
        },
        serializer = GetPromptResult.serializer(),
        options = options,
    )

    override fun onElicitationRequest(
        schema: ElicitationRequestSchema,
        handler: suspend (ElicitationRequest) -> ElicitResult,
    ) {
        require(schema === ElicitationRequestSchema) {
            "Unsupported request schema. Only ElicitationRequestSchema is supported."
        }
        elicitationRequestHandler = handler
    }

    private suspend fun callTool(
        name: String,
        args: JsonObject,
        options: MCPRequestOptions?,
    ): CallToolResult = request(
        method = "tools/call",
        params = JsonObject(
            mapOf(
                "name" to JsonPrimitive(name),
                "arguments" to args,
            ),
        ),
        serializer = CallToolResult.serializer(),
        options = options,
    )

    private suspend fun notification(
        method: String,
        params: JsonObject? = null,
    ) {
        transport.send(JSONRPCNotification(method = method, params = params))
    }

    private suspend fun <T> request(
        method: String,
        params: JsonObject? = null,
        serializer: KSerializer<T>,
        options: MCPRequestOptions? = null,
    ): T {
        val timeout = options?.timeoutMillis ?: options?.maxTotalTimeoutMillis
        return if (timeout != null) {
            withTimeout(timeout) { requestWithoutTimeout(method, params, serializer, options) }
        } else {
            requestWithoutTimeout(method, params, serializer, options)
        }
    }

    private suspend fun <T> requestWithoutTimeout(
        method: String,
        params: JsonObject?,
        serializer: KSerializer<T>,
        options: MCPRequestOptions?,
    ): T {
        if (isClosed) {
            throw MCPClientError("Attempted to send a request from a closed client")
        }
        assertCapability(method)
        options?.signal?.throwIfAborted()

        val deferred = CompletableDeferred<JsonElement>()
        val id = responseHandlersMutex.withLock {
            JsonPrimitive(requestMessageId++).also { responseHandlers[it.rpcIdKey()] = deferred }
        }
        try {
            transport.send(JSONRPCRequest(id = id, method = method, params = params))
            options?.signal?.throwIfAborted()
            val result = deferred.await()
            options?.signal?.throwIfAborted()
            return try {
                mcpJson.decodeFromJsonElement(serializer, result)
            } catch (error: Throwable) {
                throw MCPClientError("Failed to parse server response", cause = error)
            }
        } finally {
            responseHandlersMutex.withLock { responseHandlers.remove(id.rpcIdKey()) }
        }
    }

    private fun assertCapability(method: String) {
        when (method) {
            "initialize" -> Unit
            "tools/list",
            "tools/call",
            -> if (serverCapabilities.tools == null) throw MCPClientError("Server does not support tools")
            "resources/list",
            "resources/read",
            "resources/templates/list",
            -> if (serverCapabilities.resources == null) throw MCPClientError("Server does not support resources")
            "prompts/list",
            "prompts/get",
            -> if (serverCapabilities.prompts == null) throw MCPClientError("Server does not support prompts")
            else -> throw MCPClientError("Unsupported method: $method")
        }
    }

    private suspend fun onMessage(message: JSONRPCMessage) {
        when (message) {
            is JSONRPCRequest -> onRequestMessage(message)
            is JSONRPCResponse -> onResponse(message)
            is JSONRPCError -> onResponse(message)
            is JSONRPCNotification -> Unit
        }
    }

    private suspend fun onRequestMessage(request: JSONRPCRequest) {
        try {
            when (request.method) {
                "ping" -> transport.send(JSONRPCResponse(id = request.id, result = JsonObject(emptyMap())))
                "elicitation/create" -> handleElicitationRequest(request)
                else -> transport.send(
                    JSONRPCError(
                        id = request.id,
                        error = JSONRPCErrorData(
                            code = -32601,
                            message = "Unsupported request method: ${request.method}",
                        ),
                    ),
                )
            }
        } catch (error: Throwable) {
            onError(error)
        }
    }

    private suspend fun handleElicitationRequest(request: JSONRPCRequest) {
        val handler = elicitationRequestHandler
        if (handler == null) {
            transport.send(
                JSONRPCError(
                    id = request.id,
                    error = JSONRPCErrorData(
                        code = -32601,
                        message = "No elicitation handler registered on client",
                    ),
                ),
            )
            return
        }

        val parsed = try {
            ElicitationRequest(
                params = mcpJson.decodeFromJsonElement(
                    ElicitationRequestParams.serializer(),
                    request.params ?: JsonObject(emptyMap()),
                ),
            )
        } catch (error: Throwable) {
            transport.send(
                JSONRPCError(
                    id = request.id,
                    error = JSONRPCErrorData(
                        code = -32602,
                        message = "Invalid elicitation request: ${getErrorMessage(error)}",
                    ),
                ),
            )
            return
        }

        try {
            val result = handler(parsed)
            transport.send(
                JSONRPCResponse(
                    id = request.id,
                    result = mcpJson.encodeToJsonElement(ElicitResult.serializer(), result),
                ),
            )
        } catch (error: Throwable) {
            transport.send(
                JSONRPCError(
                    id = request.id,
                    error = JSONRPCErrorData(
                        code = -32603,
                        message = getErrorMessage(error),
                    ),
                ),
            )
            onError(error)
        }
    }

    private suspend fun onResponse(response: JSONRPCResponse) {
        val handler = responseHandlersMutex.withLock { responseHandlers[response.id.rpcIdKey()] }
            ?: throw MCPClientError("Protocol error: Received a response for an unknown message ID: ${response.toJsonString()}")
        handler.complete(response.result)
    }

    private suspend fun onResponse(response: JSONRPCError) {
        val handler = responseHandlersMutex.withLock { responseHandlers[response.id.rpcIdKey()] }
            ?: throw MCPClientError("Protocol error: Received a response for an unknown message ID: ${response.toJsonString()}")
        handler.completeExceptionally(
            MCPClientError(
                message = response.error.message,
                code = response.error.code,
                data = response.error.data,
            ),
        )
    }

    private fun onClose() {
        if (isClosed) return
        isClosed = true
        val error = MCPClientError("Connection closed")
        responseHandlers.values.forEach { it.completeExceptionally(error) }
        responseHandlers.clear()
    }

    private fun onError(error: Throwable) {
        onUncaughtError?.invoke(error)
    }
}

private fun MCPToolDefinition.toolMetadata(clientInfo: Configuration): Map<String, JsonElement> = buildMap {
    put("clientName", JsonPrimitive(clientInfo.name))
    put("mcpToolName", JsonPrimitive(name))
    title?.let { put("title", JsonPrimitive(it)) }
    meta?.let { put("_meta", it) }
}

private fun schemaWithClosedAdditionalProperties(schema: JsonObject): JsonObject =
    JsonObject(
        schema + mapOf(
            "properties" to (schema["properties"] ?: JsonObject(emptyMap())),
            "additionalProperties" to JsonPrimitive(false),
        ),
    )

private fun JsonElement.asArgumentsObject(): JsonObject = when (this) {
    is JsonObject -> this
    JsonNull -> JsonObject(emptyMap())
    else -> JsonObject(mapOf("value" to this))
}

private fun extractStructuredContent(
    result: CallToolResult,
    outputSchema: JsonElement,
    toolName: String,
): JsonElement {
    result.structuredContent?.let { return it }

    val text = result.content.firstNotNullOfOrNull { content ->
        if (content["type"]?.jsonPrimitive?.contentOrNull == "text") {
            content["text"]?.jsonPrimitive?.contentOrNull
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

private fun mcpToModelOutput(output: JsonElement): ToolResultOutput {
    val obj = output as? JsonObject ?: return ToolResultOutput.Json(output)
    val content = obj["content"] as? JsonArray ?: return ToolResultOutput.Json(output)
    val converted = content.map { part ->
        val partObj = part as? JsonObject
        when (partObj?.get("type")?.jsonPrimitive?.contentOrNull) {
            "text" -> JsonObject(
                mapOf(
                    "type" to JsonPrimitive("text"),
                    "text" to JsonPrimitive(partObj["text"]?.jsonPrimitive?.contentOrNull.orEmpty()),
                ),
            )
            "image" -> JsonObject(
                mapOf(
                    "type" to JsonPrimitive("image-data"),
                    "data" to (partObj["data"] ?: JsonPrimitive("")),
                    "mediaType" to (partObj["mimeType"] ?: JsonPrimitive("application/octet-stream")),
                ),
            )
            else -> JsonObject(
                mapOf(
                    "type" to JsonPrimitive("text"),
                    "text" to JsonPrimitive(part.toString()),
                ),
            )
        }
    }
    return ToolResultOutput.Content(
        value = converted,
        isError = obj["isError"]?.jsonPrimitive?.booleanOrNull == true,
    )
}

private fun JsonElement.rpcIdKey(): String {
    val primitive = this as? JsonPrimitive ?: return "json:$this"
    return if (primitive.isString) {
        "s:${primitive.content}"
    } else {
        "n:${primitive.content}"
    }
}

@Serializable
data class OAuthTokens(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String,
    @SerialName("id_token") val idToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
    val scope: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
)

@Serializable
data class OAuthClientInformation(
    @SerialName("client_id") val clientId: String,
    @SerialName("client_secret") val clientSecret: String? = null,
    @SerialName("client_id_issued_at") val clientIdIssuedAt: Long? = null,
    @SerialName("client_secret_expires_at") val clientSecretExpiresAt: Long? = null,
)

@Serializable
data class OAuthClientMetadata(
    @SerialName("redirect_uris") val redirectUris: List<String>,
    @SerialName("token_endpoint_auth_method") val tokenEndpointAuthMethod: String? = null,
    @SerialName("grant_types") val grantTypes: List<String>? = null,
    @SerialName("response_types") val responseTypes: List<String>? = null,
    @SerialName("client_name") val clientName: String? = null,
    @SerialName("client_uri") val clientUri: String? = null,
    @SerialName("logo_uri") val logoUri: String? = null,
    val scope: String? = null,
    val contacts: List<String>? = null,
    @SerialName("tos_uri") val tosUri: String? = null,
    @SerialName("policy_uri") val policyUri: String? = null,
    @SerialName("jwks_uri") val jwksUri: String? = null,
    val jwks: JsonElement? = null,
    @SerialName("software_id") val softwareId: String? = null,
    @SerialName("software_version") val softwareVersion: String? = null,
    @SerialName("software_statement") val softwareStatement: String? = null,
)

interface OAuthClientProvider {
    val redirectUrl: String
    val clientMetadata: OAuthClientMetadata

    suspend fun tokens(): OAuthTokens?
    suspend fun saveTokens(tokens: OAuthTokens)
    suspend fun redirectToAuthorization(authorizationUrl: String)
    suspend fun saveCodeVerifier(codeVerifier: String)
    suspend fun codeVerifier(): String
    suspend fun clientInformation(): OAuthClientInformation?

    suspend fun saveClientInformation(clientInformation: OAuthClientInformation) = Unit
    suspend fun invalidateCredentials(scope: String) = Unit
    suspend fun state(): String? = null
    suspend fun saveState(state: String) = Unit
    suspend fun storedState(): String? = null
    suspend fun validateResourceURL(serverUrl: String, resource: String?): String? = resource ?: serverUrl
}

class UnauthorizedError(message: String = "Unauthorized") : AiSdkException(message)

enum class AuthResult {
    AUTHORIZED,
    REDIRECT,
}

data class AuthOptions(
    val serverUrl: String,
    val authorizationCode: String? = null,
    val callbackState: String? = null,
    val scope: String? = null,
    val resourceMetadataUrl: String? = null,
)

suspend fun auth(
    provider: OAuthClientProvider,
    options: AuthOptions,
): AuthResult {
    val currentTokens = provider.tokens()
    if (currentTokens?.accessToken?.isNotBlank() == true && options.authorizationCode == null) {
        return AuthResult.AUTHORIZED
    }
    if (options.authorizationCode != null) {
        throw MCPClientError(
            "OAuth authorization-code exchange requires a platform transport; store tokens in OAuthClientProvider before calling auth.",
        )
    }

    val state = provider.state() ?: generateId(prefix = "mcp")
    provider.saveState(state)
    val codeVerifier = createIdGenerator(prefix = "mcp-verifier", size = 48).generate()
    provider.saveCodeVerifier(codeVerifier)

    val clientId = provider.clientInformation()?.clientId ?: provider.clientMetadata.clientName ?: DEFAULT_MCP_CLIENT_NAME
    val authorizationUrl = buildAuthorizationUrl(
        serverUrl = options.serverUrl,
        clientId = clientId,
        redirectUrl = provider.redirectUrl,
        scope = options.scope ?: provider.clientMetadata.scope,
        state = state,
    )
    provider.redirectToAuthorization(authorizationUrl)
    return AuthResult.REDIRECT
}

private fun buildAuthorizationUrl(
    serverUrl: String,
    clientId: String,
    redirectUrl: String,
    scope: String?,
    state: String,
): String {
    val base = serverUrl.trimEnd('/') + "/authorize"
    val params = listOfNotNull(
        "response_type=code",
        "client_id=${urlComponent(clientId)}",
        "redirect_uri=${urlComponent(redirectUrl)}",
        scope?.let { "scope=${urlComponent(it)}" },
        "state=${urlComponent(state)}",
    )
    return "$base?${params.joinToString("&")}"
}

private fun urlComponent(value: String): String = buildString {
    for (char in value) {
        when (char) {
            ' ' -> append("%20")
            ':' -> append("%3A")
            '/' -> append("%2F")
            '?' -> append("%3F")
            '&' -> append("%26")
            '=' -> append("%3D")
            '+' -> append("%2B")
            '#' -> append("%23")
            '%' -> append("%25")
            else -> append(char)
        }
    }
}

enum class MCPTransportKind {
    Http,
    Sse,
}

data class MCPTransportConfig(
    val type: MCPTransportKind,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val authProvider: OAuthClientProvider? = null,
)

fun createMcpTransport(client: HttpClient, config: MCPTransportConfig): MCPTransport =
    when (config.type) {
        MCPTransportKind.Http -> HttpMCPTransport(client, config.url, config.headers, config.authProvider)
        MCPTransportKind.Sse -> SseMCPTransport(client, config.url, config.headers, config.authProvider)
    }

class HttpMCPTransport(
    private val client: HttpClient,
    private val url: String,
    private val headers: Map<String, String> = emptyMap(),
    private val authProvider: OAuthClientProvider? = null,
) : MCPTransport {
    override var onClose: (() -> Unit)? = null
    override var onError: ((Throwable) -> Unit)? = null
    override var onMessage: (suspend (JSONRPCMessage) -> Unit)? = null
    override var protocolVersion: String? = null

    private var sessionId: String? = null
    private var scope: CoroutineScope? = null
    private var inboundJob: Job? = null
    private val inboundMutex = Mutex()
    private var closed = true

    override suspend fun start() {
        if (!closed) throw MCPClientError("MCP HTTP Transport Error: Transport already started.")
        closed = false
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        ensureInboundSse()
    }

    override suspend fun send(message: JSONRPCMessage) {
        if (closed) throw MCPClientError("MCP HTTP Transport Error: Not connected")
        postMessage(message, triedAuth = false)
    }

    override suspend fun close() {
        if (closed) return
        runCatching {
            sessionId?.let { session ->
                client.request(url) {
                    method = HttpMethod.Delete
                    mcpCommonHeaders(emptyMap()).forEach { (name, value) -> header(name, value) }
                    header("mcp-session-id", session)
                }
            }
        }
        closed = true
        inboundJob?.cancel()
        scope?.cancel()
        inboundJob = null
        scope = null
        onClose?.invoke()
    }

    private suspend fun postMessage(message: JSONRPCMessage, triedAuth: Boolean) {
        val response = client.request(url) {
            method = HttpMethod.Post
            contentType(ContentType.Application.Json)
            mcpCommonHeaders(
                mapOf(
                    HttpHeaders.ContentType to ContentType.Application.Json.toString(),
                    HttpHeaders.Accept to "application/json, text/event-stream",
                ),
            ).forEach { (name, value) -> header(name, value) }
            setBody(message.toJsonString())
        }
        response.mcpSessionId()?.let { sessionId = it }
        if (response.status.value == 401 && authProvider != null && !triedAuth) {
            if (auth(authProvider, AuthOptions(serverUrl = url)) != AuthResult.AUTHORIZED) {
                throw UnauthorizedError()
            }
            postMessage(message, triedAuth = true)
            return
        }
        if (response.status.value == 202 || message is JSONRPCNotification) {
            ensureInboundSse()
            return
        }
        if (response.status.value !in 200..299) {
            val text = response.bodyAsText()
            val error = MCPClientError("MCP HTTP Transport Error: POSTing to endpoint (HTTP ${response.status.value}): $text")
            onError?.invoke(error)
            throw error
        }
        val contentType = response.headers[HttpHeaders.ContentType].orEmpty()
        when {
            contentType.contains("application/json", ignoreCase = true) -> {
                response.bodyAsText().mcpJsonRpcMessages().forEach { onMessage?.invoke(it) }
            }
            contentType.contains("text/event-stream", ignoreCase = true) -> {
                processMcpSse(response.bodyAsChannel()) { event ->
                    if (event.event == "message") {
                        onMessage?.invoke(parseJSONRPCMessage(event.data))
                    }
                }
            }
            else -> {
                val error = MCPClientError("MCP HTTP Transport Error: Unexpected content type: $contentType")
                onError?.invoke(error)
                throw error
            }
        }
    }

    private suspend fun ensureInboundSse() {
        val activeScope = scope ?: return
        inboundMutex.withLock {
            if (inboundJob == null) {
                inboundJob = activeScope.launch { readInboundSse() }
            }
        }
    }

    private suspend fun readInboundSse() {
        try {
            val response = client.request(url) {
                method = HttpMethod.Get
                mcpCommonHeaders(mapOf(HttpHeaders.Accept to "text/event-stream")).forEach { (name, value) -> header(name, value) }
            }
            response.mcpSessionId()?.let { sessionId = it }
            if (response.status.value == 405) return
            if (response.status.value !in 200..299) {
                val error = MCPClientError("MCP HTTP Transport Error: GET SSE failed: ${response.status.value} ${response.status.description}")
                onError?.invoke(error)
                return
            }
            processMcpSse(response.bodyAsChannel()) { event ->
                if (event.event == "message") {
                    onMessage?.invoke(parseJSONRPCMessage(event.data))
                }
            }
        } catch (error: Throwable) {
            if (!closed) onError?.invoke(error)
        } finally {
            inboundMutex.withLock {
                inboundJob = null
            }
        }
    }

    private suspend fun mcpCommonHeaders(base: Map<String, String>): Map<String, String> {
        val values = linkedMapOf<String, String?>()
        headers.forEach { (key, value) -> values[key] = value }
        base.forEach { (key, value) -> values[key] = value }
        values["mcp-protocol-version"] = protocolVersion ?: LATEST_PROTOCOL_VERSION
        sessionId?.let { values["mcp-session-id"] = it }
        authProvider?.tokens()?.accessToken?.takeIf { it.isNotBlank() }?.let { values[HttpHeaders.Authorization] = "Bearer $it" }
        return withUserAgentSuffix(values, "ai-sdk/mcp/$MCP_PACKAGE_VERSION")
    }
}

class SseMCPTransport(
    private val client: HttpClient,
    private val url: String,
    private val headers: Map<String, String> = emptyMap(),
    private val authProvider: OAuthClientProvider? = null,
) : MCPTransport {
    override var onClose: (() -> Unit)? = null
    override var onError: ((Throwable) -> Unit)? = null
    override var onMessage: (suspend (JSONRPCMessage) -> Unit)? = null
    override var protocolVersion: String? = null

    private var endpoint: String? = null
    private var scope: CoroutineScope? = null
    private var readerJob: Job? = null
    private var connected = false

    override suspend fun start() {
        if (connected) return
        val connectionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = connectionScope
        val ready = CompletableDeferred<Unit>()
        readerJob = connectionScope.launch {
            try {
                val response = openSseConnection(triedAuth = false)
                if (response.status.value !in 200..299) {
                    throw MCPClientError("MCP SSE Transport Error: ${response.status.value} ${response.status.description}")
                }
                processMcpSse(response.bodyAsChannel()) { event ->
                    when (event.event) {
                        "endpoint" -> {
                            endpoint = resolveMcpUrl(event.data, url).also { resolved ->
                                if (mcpOrigin(resolved) != mcpOrigin(url)) {
                                    throw MCPClientError("MCP SSE Transport Error: Endpoint origin does not match connection origin: ${mcpOrigin(resolved)}")
                                }
                            }
                            connected = true
                            if (!ready.isCompleted) ready.complete(Unit)
                        }
                        "message" -> onMessage?.invoke(parseJSONRPCMessage(event.data))
                    }
                }
                if (connected) {
                    connected = false
                    onClose?.invoke()
                }
            } catch (error: Throwable) {
                if (!ready.isCompleted) ready.completeExceptionally(error)
                if (connected) onError?.invoke(error)
            }
        }
        ready.await()
    }

    private suspend fun openSseConnection(triedAuth: Boolean): HttpResponse {
        val response = client.request(url) {
            method = HttpMethod.Get
            mcpCommonHeaders(mapOf(HttpHeaders.Accept to "text/event-stream")).forEach { (name, value) -> header(name, value) }
        }
        if (response.status.value == 401 && authProvider != null && !triedAuth) {
            if (auth(authProvider, AuthOptions(serverUrl = url)) != AuthResult.AUTHORIZED) {
                throw UnauthorizedError()
            }
            return openSseConnection(triedAuth = true)
        }
        return response
    }

    override suspend fun send(message: JSONRPCMessage) {
        val destination = endpoint ?: throw MCPClientError("MCP SSE Transport Error: Not connected")
        val response = client.request(destination) {
            method = HttpMethod.Post
            contentType(ContentType.Application.Json)
            mcpCommonHeaders(mapOf(HttpHeaders.ContentType to ContentType.Application.Json.toString())).forEach { (name, value) -> header(name, value) }
            setBody(message.toJsonString())
        }
        if (response.status.value == 401 && authProvider != null) {
            if (auth(authProvider, AuthOptions(serverUrl = url)) != AuthResult.AUTHORIZED) {
                throw UnauthorizedError()
            }
            send(message)
            return
        }
        if (response.status.value !in 200..299) {
            val error = MCPClientError("MCP SSE Transport Error: POSTing to endpoint (HTTP ${response.status.value}): ${response.bodyAsText()}")
            onError?.invoke(error)
            throw error
        }
    }

    override suspend fun close() {
        connected = false
        readerJob?.cancel()
        scope?.cancel()
        readerJob = null
        scope = null
        onClose?.invoke()
    }

    private suspend fun mcpCommonHeaders(base: Map<String, String>): Map<String, String> {
        val values = linkedMapOf<String, String?>()
        headers.forEach { (key, value) -> values[key] = value }
        base.forEach { (key, value) -> values[key] = value }
        values["mcp-protocol-version"] = protocolVersion ?: LATEST_PROTOCOL_VERSION
        authProvider?.tokens()?.accessToken?.takeIf { it.isNotBlank() }?.let { values[HttpHeaders.Authorization] = "Bearer $it" }
        return withUserAgentSuffix(values, "ai-sdk/mcp/$MCP_PACKAGE_VERSION")
    }
}

private data class McpSseEvent(
    val event: String,
    val data: String,
    val id: String? = null,
)

private suspend fun processMcpSse(channel: ByteReadChannel, onEvent: suspend (McpSseEvent) -> Unit) {
    var eventName = "message"
    var eventId: String? = null
    val data = mutableListOf<String>()

    suspend fun flush() {
        if (data.isEmpty()) return
        onEvent(McpSseEvent(eventName, data.joinToString("\n"), eventId))
        eventName = "message"
        eventId = null
        data.clear()
    }

    while (true) {
        val line = channel.readLine() ?: break
        when {
            line.isEmpty() -> flush()
            line.startsWith("event:") -> eventName = line.removePrefix("event:").trimStart()
            line.startsWith("data:") -> data += line.removePrefix("data:").trimStart()
            line.startsWith("id:") -> eventId = line.removePrefix("id:").trimStart()
        }
    }
    flush()
}

private fun String.mcpJsonRpcMessages(): List<JSONRPCMessage> {
    val element = mcpJson.parseToJsonElement(this)
    return when (element) {
        is JsonArray -> element.map { parseJSONRPCMessage(it.toString()) }
        else -> listOf(parseJSONRPCMessage(element.toString()))
    }
}

private fun HttpResponse.mcpSessionId(): String? =
    headers["mcp-session-id"]?.takeIf { it.isNotBlank() }

private fun resolveMcpUrl(value: String, base: String): String {
    if (value.startsWith("http://") || value.startsWith("https://")) return value
    val origin = mcpOrigin(base)
    if (value.startsWith("/")) return origin + value
    return base.substringBeforeLast('/', missingDelimiterValue = origin).trimEnd('/') + "/" + value
}

private fun mcpOrigin(url: String): String {
    val schemeEnd = url.indexOf("://")
    val authorityStart = if (schemeEnd >= 0) schemeEnd + 3 else 0
    val authorityEnd = url.indexOf('/', authorityStart).takeIf { it >= 0 } ?: url.length
    return url.substring(0, authorityEnd)
}

@Serializable
data class StdioConfig(
    val command: String,
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val cwd: String? = null,
)

internal interface MCPStdioProcess {
    suspend fun readLine(): String?
    suspend fun writeLine(line: String)
    suspend fun close()
}

internal expect fun createMCPStdioProcess(config: StdioConfig): MCPStdioProcess

class Experimental_StdioMCPTransport(
    val config: StdioConfig,
) : MCPTransport {
    override var onClose: (() -> Unit)? = null
    override var onError: ((Throwable) -> Unit)? = null
    override var onMessage: (suspend (JSONRPCMessage) -> Unit)? = null
    override var protocolVersion: String? = null

    private var process: MCPStdioProcess? = null
    private var scope: CoroutineScope? = null
    private var readJob: Job? = null

    override suspend fun start() {
        if (process != null) throw MCPClientError("StdioMCPTransport already started.")
        val started = createMCPStdioProcess(config)
        process = started
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        readJob = scope!!.launch {
            try {
                while (true) {
                    val line = started.readLine() ?: break
                    try {
                        onMessage?.invoke(parseJSONRPCMessage(line))
                    } catch (error: Throwable) {
                        onError?.invoke(error)
                    }
                }
            } catch (error: Throwable) {
                onError?.invoke(error)
            } finally {
                onClose?.invoke()
            }
        }
    }

    override suspend fun send(message: JSONRPCMessage) {
        val active = process ?: throw MCPClientError("StdioClientTransport not connected")
        active.writeLine(message.toJsonString())
    }

    override suspend fun close() {
        readJob?.cancel()
        scope?.cancel()
        process?.close()
        readJob = null
        scope = null
        process = null
        onClose?.invoke()
    }
}
