@file:Suppress("FunctionName", "PropertyName")

package ai.torad.aisdk

import ai.torad.aisdk.JSONRPCMessage.Companion.toJsonString
import dev.drewhamilton.poko.Poko
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readLine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
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
import kotlinx.serialization.json.jsonObject
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext
import kotlin.math.pow
import kotlin.math.roundToLong

public const val MCP_PACKAGE_VERSION: String = "1.0.46"
public const val LATEST_PROTOCOL_VERSION: String = "2025-11-25"

public val SUPPORTED_PROTOCOL_VERSIONS: List<String> = listOf(
    LATEST_PROTOCOL_VERSION,
    "2025-06-18",
    "2025-03-26",
    "2024-11-05",
)

private const val HTTP_NOT_FOUND = 404
private const val HTTP_METHOD_NOT_ALLOWED = 405
private const val DEFAULT_MCP_CLIENT_NAME = "ai-sdk-mcp-client"
private const val DEFAULT_MCP_CLIENT_VERSION = "1.0.0"
private const val MCP_SSE_MAX_DATA_LINES = 1_000

/** Default ceiling for the MCP connect handshake (initialize round-trip, SSE endpoint event). */
private const val MCP_DEFAULT_HANDSHAKE_TIMEOUT_MS = 30_000L

/** Default ceiling for non-init JSON-RPC requests that otherwise could await forever. */
private const val MCP_DEFAULT_REQUEST_TIMEOUT_MS = 30_000L

/** Best-effort timeout for the session-cleanup DELETE on HTTP transport close. */
private const val MCP_CLOSE_DELETE_TIMEOUT_MS = 5_000L

internal val mcpJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

public class MCPClientError(
    message: String,
    public val code: Int? = null,
    public val data: JsonElement? = null,
    cause: Throwable? = null,
) : AiSdkException(message, cause)

@Serializable
public sealed interface JSONRPCMessage {
    public companion object {
        internal fun JSONRPCMessage.toJsonElement(): JsonObject = when (this) {
            is JSONRPCRequest -> mcpJson.encodeToJsonElement(JSONRPCRequest.serializer(), this).jsonObject
            is JSONRPCNotification -> mcpJson.encodeToJsonElement(JSONRPCNotification.serializer(), this).jsonObject
            is JSONRPCResponse -> mcpJson.encodeToJsonElement(JSONRPCResponse.serializer(), this).jsonObject
            is JSONRPCError -> mcpJson.encodeToJsonElement(JSONRPCError.serializer(), this).jsonObject
        }

        internal fun JSONRPCMessage.toJsonString(): String = toJsonElement().toString()

        internal fun fromJson(text: String): JSONRPCMessage {
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
                envelope.method != null && hasId -> WireDecoder.decode(
                    JSONRPCRequest.serializer(),
                    obj,
                    "mcp",
                    "json-rpc request"
                )
                envelope.method != null -> WireDecoder.decode(
                    JSONRPCNotification.serializer(),
                    obj,
                    "mcp",
                    "json-rpc notification"
                )
                hasId && envelope.error == null -> WireDecoder.decode(
                    JSONRPCResponse.serializer(),
                    obj,
                    "mcp",
                    "json-rpc response"
                )
                hasId -> WireDecoder.decode(JSONRPCError.serializer(), obj, "mcp", "json-rpc error")
                else -> WireDecoder.fail("mcp", "json-rpc message", "$", "invalid JSON-RPC envelope", obj)
            }
        }

        internal fun fromJsonBatch(text: String): List<JSONRPCMessage> {
            val element = mcpJson.parseToJsonElement(text)
            return when (element) {
                is JsonArray -> element.map { fromJson(it.toString()) }
                else -> listOf(fromJson(element.toString()))
            }
        }
    }
}

@Serializable
private data class JSONRPCEnvelope(
    val jsonrpc: String,
    val id: JsonElement? = null,
    val method: String? = null,
    val params: JsonObject? = null,
    val result: JsonElement? = null,
    val error: JSONRPCErrorData? = null,
)

/**
 * Transport interface for MCP communication. Implementations may wrap stdio,
 * Streamable HTTP, SSE, WebSockets, or a test fixture.
 */
public interface MCPTransport {
    public fun setOnClose(handler: (() -> Unit)?)
    public fun setOnError(handler: ((Throwable) -> Unit)?)
    public fun setOnMessage(handler: (suspend (JSONRPCMessage) -> Unit)?)
    public fun setProtocolVersion(version: String?)

    public suspend fun start()
    public suspend fun send(message: JSONRPCMessage)
    public suspend fun close()
}

public class MCPClientConfig internal constructor(
    public val transport: MCPTransport,
    public val onUncaughtError: ((Throwable) -> Unit)? = null,
    public val clientName: String? = null,
    public val name: String? = null,
    public val version: String = DEFAULT_MCP_CLIENT_VERSION,
    public val capabilities: MCPClientCapabilities = MCPClientCapabilities(),
)

public class MCPClientConfigBuilder internal constructor() {
    private var transport: MCPTransport? = null
    private var onUncaughtError: ((Throwable) -> Unit)? = null
    private var clientName: String? = null
    private var name: String? = null
    private var version: String = DEFAULT_MCP_CLIENT_VERSION
    private var capabilities: MCPClientCapabilities = MCPClientCapabilities()

    public fun transport(value: MCPTransport) {
        transport = value
    }

    public fun onUncaughtError(value: ((Throwable) -> Unit)?) {
        onUncaughtError = value
    }

    public fun clientName(value: String?) {
        clientName = value
    }

    public fun name(value: String?) {
        name = value
    }

    public fun version(value: String) {
        version = value
    }

    public fun capabilities(value: MCPClientCapabilities) {
        capabilities = value
    }

    internal fun build(): MCPClientConfig =
        MCPClientConfig(
            transport = requireNotNull(transport) { "MCPClientConfig.transport is required" },
            onUncaughtError = onUncaughtError,
            clientName = clientName,
            name = name,
            version = version,
            capabilities = capabilities,
        )
}

public fun MCPClientConfig(
    block: MCPClientConfigBuilder.() -> Unit = {},
): MCPClientConfig =
    MCPClientConfigBuilder().apply(block).build()

@ExperimentalAiSdkApi
public typealias experimental_MCPClientConfig = MCPClientConfig

public interface MCPClient {
    public val serverInfo: Configuration
    public val instructions: String?

    public suspend fun <TContext> tools(schemas: MCPToolSchemas? = null): ToolSet<TContext>

    public suspend fun listTools(
        params: JsonObject? = null,
        options: MCPRequestOptions? = null,
    ): ListToolsResult

    public fun <TContext> toolsFromDefinitions(
        definitions: ListToolsResult,
        schemas: MCPToolSchemas? = null,
    ): ToolSet<TContext>

    public suspend fun listResources(
        params: JsonObject? = null,
        options: MCPRequestOptions? = null,
    ): ListResourcesResult

    public suspend fun readResource(
        uri: String,
        options: MCPRequestOptions? = null,
    ): ReadResourceResult

    public suspend fun listResourceTemplates(
        options: MCPRequestOptions? = null,
    ): ListResourceTemplatesResult

    @ExperimentalAiSdkApi
    public suspend fun experimental_listPrompts(
        params: JsonObject? = null,
        options: MCPRequestOptions? = null,
    ): ListPromptsResult

    @ExperimentalAiSdkApi
    public suspend fun experimental_getPrompt(
        name: String,
        arguments: JsonObject? = null,
        options: MCPRequestOptions? = null,
    ): GetPromptResult

    public fun onElicitationRequest(
        schema: ElicitationRequestSchema,
        handler: suspend (ElicitationRequest) -> ElicitResult,
    )

    public suspend fun close()
}

@ExperimentalAiSdkApi
public typealias experimental_MCPClient = MCPClient

public suspend fun CreateMCPClient(config: MCPClientConfig): MCPClient =
    DefaultMCPClient(config).also { it.init() }

@ExperimentalAiSdkApi
public suspend fun Experimental_CreateMCPClient(config: MCPClientConfig): MCPClient =
    CreateMCPClient(config)

@OptIn(ExperimentalAtomicApi::class)
private class DefaultMCPClient(config: MCPClientConfig) : MCPClient {
    private val transport: MCPTransport = config.transport
    private val onUncaughtError = config.onUncaughtError
    private val clientInfo = Configuration {
        name(config.clientName ?: config.name ?: DEFAULT_MCP_CLIENT_NAME)
        version(config.version)
    }
    private val clientCapabilities = config.capabilities
    private val responseHandlers = mutableMapOf<String, CompletableDeferred<JsonElement?>>()
    private val responseHandlersMutex = Mutex()
    private var requestMessageId: Long = 0
    private val isClosed = AtomicBoolean(true)

    // Structured scope the client owns, so the non-suspend transport onClose
    // callback can drain pending requests without resorting to GlobalScope.
    // Cancelled by close() once the synchronous drain has run.
    private val clientScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var serverCapabilities = MCPServerCapabilities()
    private var elicitationRequestHandler: (suspend (ElicitationRequest) -> ElicitResult)? = null
    private val serverInfoRef = AtomicReference(
        Configuration {
            name("")
            version("")
        },
    )
    private var _instructions: String? = null

    override val serverInfo: Configuration get() = serverInfoRef.load()
    override val instructions: String? get() = _instructions

    init {
        transport.setOnClose { onClose() }
        transport.setOnError { onError(it) }
        transport.setOnMessage { message -> onMessage(message) }
    }

    suspend fun init() {
        try {
            transport.start()
            isClosed.store(false)

            val result = request(
                method = "initialize",
                params = buildJsonObject {
                    put("protocolVersion", JsonPrimitive(LATEST_PROTOCOL_VERSION))
                    put(
                        "capabilities",
                        mcpJson.encodeToJsonElement(MCPClientCapabilities.serializer(), clientCapabilities)
                    )
                    put("clientInfo", mcpJson.encodeToJsonElement(Configuration.serializer(), clientInfo))
                },
                serializer = InitializeResult.serializer(),
                options = MCPRequestOptions {
                    timeoutMillis(MCP_DEFAULT_HANDSHAKE_TIMEOUT_MS)
                },
            )

            if (result.protocolVersion !in SUPPORTED_PROTOCOL_VERSIONS) {
                throw MCPClientError("Server's protocol version is not supported: ${result.protocolVersion}")
            }

            serverCapabilities = result.capabilities
            serverInfoRef.store(result.serverInfo)
            _instructions = result.instructions
            transport.setProtocolVersion(result.protocolVersion)

            notification(method = "notifications/initialized")
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    override suspend fun close() {
        isClosed.store(true)
        try {
            transport.close()
        } finally {
            withContext(NonCancellable) {
                // Authoritative synchronous drain: when close() returns, every pending
                // request is settled. Idempotent (clears the map), so it composes with a
                // drain the transport's onClose callback may already have launched.
                failAllPendingRequests()
                clientScope.cancel()
            }
        }
    }

    /**
     * Complete every in-flight request exceptionally and clear the table, all
     * under [responseHandlersMutex] so it can't race a concurrent registration
     * in [requestWithoutTimeout] (which checks `isClosed` and inserts under the
     * same lock). Idempotent: a second call drains an already-empty table.
     */
    private suspend fun failAllPendingRequests() {
        val error = MCPClientError("Connection closed")
        val pending = responseHandlersMutex.withLock {
            responseHandlers.values.toList().also { responseHandlers.clear() }
        }
        pending.forEach { it.completeExceptionally(error) }
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
            val inputSchema = selectedSchema?.inputSchema
                ?: schemaWithClosedAdditionalProperties(definition.inputSchema)
            val outputSchema = selectedSchema?.outputSchema
            val description = definition.description ?: definition.title ?: definition.name
            DynamicTool<TContext>(
                name = definition.name,
                description = description,
                inputSchemaJson = inputSchema.toString(),
                metadata = definition.toolMetadata(clientInfo),
                toModelOutput = { output, _ -> mcpToModelOutput(output) },
            ) { input ->
                val result = callTool(
                    name = definition.name,
                    args = asArgumentsObject(input),
                    options = MCPRequestOptions {
                        signal(abortSignal)
                    },
                )
                if (!result.isError && outputSchema != null) {
                    result.extractStructuredContent(outputSchema, definition.name)
                } else {
                    mcpJson.encodeToJsonElement(CallToolResult.serializer(), result)
                }
            }
        }
        // v6 parity: MCP tool names come from an external server and v6's
        // client tolerates duplicates with last-wins (not a hard failure).
        // The strict requireUniqueToolNames policy applies only to
        // caller-owned tool sets (ToolSet / ToolSet.plus / the builder).
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

    @ExperimentalAiSdkApi
    override suspend fun experimental_listPrompts(
        params: JsonObject?,
        options: MCPRequestOptions?,
    ): ListPromptsResult = request("prompts/list", params, ListPromptsResult.serializer(), options)

    @ExperimentalAiSdkApi
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
        val timeout = options?.timeoutMillis ?: options?.maxTotalTimeoutMillis ?: MCP_DEFAULT_REQUEST_TIMEOUT_MS
        // Real-time so a JSON-RPC response that never correlates back (server
        // ACKed the POST but never answers) can't hang the caller forever, and
        // so the bound doesn't spuriously fire under runTest's virtual clock.
        return HttpTransport.withRealTimeout(timeout) { requestWithoutTimeout(method, params, serializer, options) }
    }

    private suspend fun <T> requestWithoutTimeout(
        method: String,
        params: JsonObject?,
        serializer: KSerializer<T>,
        options: MCPRequestOptions?,
    ): T {
        assertCapability(method)
        options?.signal?.throwIfAborted()

        val deferred = CompletableDeferred<JsonElement?>()
        // Check isClosed under the same lock the drain uses, so a close()/onClose
        // draining concurrently can't run between the check and the insert and leave
        // this handler stranded (awaiting a response that will never come).
        val id = responseHandlersMutex.withLock {
            if (isClosed.load()) {
                throw MCPClientError("Attempted to send a request from a closed client")
            }
            JsonPrimitive(requestMessageId++).also { responseHandlers[rpcIdKey(it)] = deferred }
        }
        val abortRegistration = options?.signal?.register {
            deferred.completeExceptionally(AbortError())
        }
        try {
            transport.send(JSONRPCRequest(id = id, method = method, params = params))
            options?.signal?.throwIfAborted()
            val result = deferred.await()
            options?.signal?.throwIfAborted()
            return try {
                mcpJson.decodeFromJsonElement(serializer, result ?: JsonNull)
            } catch (error: Throwable) {
                throw MCPClientError("Failed to parse server response", cause = error)
            }
        } finally {
            // Cleanup MUST survive the in-flight cancellation (timeoutMillis elapsed / abort /
            // scope-cancel): a plain withLock takes its non-suspending fast path only when
            // uncontended — if it has to suspend it observes the cancellation and throws BEFORE
            // acquiring the lock, so the remove never runs and this handler is stranded in
            // responseHandlers until close(). NonCancellable guarantees the removal completes.
            withContext(NonCancellable) {
                abortRegistration?.cancel()
                responseHandlersMutex.withLock { responseHandlers.remove(rpcIdKey(id)) }
            }
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
            if (error is CancellationException) throw error
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
                        message = "Invalid elicitation request: ${ErrorMessages.of(error)}",
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
            if (error is CancellationException) throw error
            transport.send(
                JSONRPCError(
                    id = request.id,
                    error = JSONRPCErrorData(
                        code = -32603,
                        message = ErrorMessages.of(error),
                    ),
                ),
            )
            onError(error)
        }
    }

    private suspend fun onResponse(response: JSONRPCResponse) {
        val handler = responseHandlersMutex.withLock { responseHandlers[rpcIdKey(response.id)] }
            ?: throw MCPClientError(
                "Protocol error: Received a response for an unknown message ID: ${response.toJsonString()}"
            )
        handler.complete(response.result)
    }

    private suspend fun onResponse(response: JSONRPCError) {
        val handler = responseHandlersMutex.withLock { responseHandlers[rpcIdKey(response.id)] }
            ?: throw MCPClientError(
                "Protocol error: Received a response for an unknown message ID: ${response.toJsonString()}"
            )
        handler.completeExceptionally(
            MCPClientError(
                message = response.error.message,
                code = response.error.code,
                data = response.error.data,
            ),
        )
    }

    private fun onClose() {
        if (!isClosed.compareAndSet(expectedValue = false, newValue = true)) return
        // Natural connection drop (the transport's reader exited and no explicit
        // close() ran). onClose is () -> Unit, so drain on the client's own
        // structured scope rather than GlobalScope; an explicit close() would
        // drain synchronously and cancel this scope.
        clientScope.launch { failAllPendingRequests() }
    }

    private fun onError(error: Throwable) {
        onUncaughtError?.invoke(error)
    }

    private fun rpcIdKey(element: JsonElement): String {
        val primitive = element as? JsonPrimitive ?: return "json:$element"
        // Key by the primitive's content, NOT its JSON type: this client only ever
        // issues numeric ids, so a server that echoes a numeric id back as a JSON
        // string (e.g. 5 -> "5") must still resolve its registered handler. Mirrors
        // the reference's Number(id) coercion; without it the typed lookup misses and
        // the response is dropped as an "unknown message ID".
        return primitive.content
    }

    private fun schemaWithClosedAdditionalProperties(schema: JsonObject): JsonObject =
        JsonObject(
            schema + mapOf(
                "properties" to (schema["properties"] ?: JsonObject(emptyMap())),
                "additionalProperties" to JsonPrimitive(false),
            ),
        )

    private fun asArgumentsObject(element: JsonElement): JsonObject = when (element) {
        is JsonObject -> element
        JsonNull -> JsonObject(emptyMap())
        else -> JsonObject(mapOf("value" to element))
    }

    private fun mcpToModelOutput(output: JsonElement): ToolResultOutput {
        val obj = output as? JsonObject ?: return ToolResultOutput.Json(output)
        val content = JsonAccess.arr(obj, "content") ?: return ToolResultOutput.Json(output)
        val converted = content.map { part ->
            val partObj = part as? JsonObject
            // `as? JsonPrimitive` (not `?.jsonPrimitive`, which throws on a non-primitive value):
            // MCP content is server-controlled, so a malformed object/array type/text must degrade
            // gracefully (fall through / empty) rather than throw a low-level IllegalArgumentException.
            when ((partObj?.get("type") as? JsonPrimitive)?.contentOrNull) {
                "text" -> JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("text"),
                        "text" to JsonPrimitive((partObj["text"] as? JsonPrimitive)?.contentOrNull.orEmpty()),
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
            isError = (obj["isError"] as? JsonPrimitive)?.booleanOrNull == true,
        )
    }
}

public class ClientAuthResult(
    public val additionalHeaders: Map<String, String> = emptyMap(),
    public val additionalParams: Map<String, String> = emptyMap(),
)

public interface OAuthClientProvider {
    public val redirectUrl: String
    public val clientMetadata: OAuthClientMetadata

    public suspend fun tokens(): OAuthTokens?
    public suspend fun saveTokens(tokens: OAuthTokens)
    public suspend fun redirectToAuthorization(authorizationUrl: String)
    public suspend fun saveCodeVerifier(codeVerifier: String)
    public suspend fun codeVerifier(): String
    public suspend fun clientInformation(): OAuthClientInformation?

    public suspend fun saveClientInformation(clientInformation: OAuthClientInformation): Unit = Unit
    public suspend fun invalidateCredentials(scope: String): Unit = Unit
    public suspend fun state(): String? = null
    public suspend fun saveState(state: String): Unit = Unit
    public suspend fun storedState(): String? = null
    public suspend fun validateResourceURL(serverUrl: String, resource: String?): String? {
        if (resource == null) return null
        val requestedResource = McpResourceUrl.fromServerUrl(serverUrl)
        if (!McpResourceUrl.checkAllowed(requestedResource, resource)) {
            throw MCPClientError("Protected resource $resource does not match expected $requestedResource (or origin)")
        }
        return McpResourceUrl.stripSlash(resource)
    }
    public suspend fun addClientAuthentication(
        headers: Map<String, String>,
        params: Map<String, String>,
        url: String,
        metadata: AuthorizationServerMetadata?,
    ): ClientAuthResult = ClientAuthResult()
}

/** OAuth client handshake driver for the HTTP / SSE transports. */
internal object McpAuth {
@Suppress("LongMethod", "CyclomaticComplexMethod", "ReturnCount", "ThrowsCount")
suspend fun auth(
    provider: OAuthClientProvider,
    options: AuthOptions,
    reauthorize: Boolean = false,
): AuthResult {
    val currentTokens = provider.tokens()
    val hasReusableAccessToken = currentTokens?.accessToken?.isNotBlank() == true &&
        currentTokens.refreshToken.isNullOrBlank()
    if (hasReusableAccessToken && options.authorizationCode == null && !reauthorize) {
        return AuthResult.AUTHORIZED
    }
    val resourceMetadata = options.client?.let { client ->
        try {
            McpOAuthFlow.discoverOAuthProtectedResourceMetadata(
                client = client,
                serverUrl = options.serverUrl,
                resourceMetadataUrl = options.resourceMetadataUrl,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            null
        }
    }
    val authorizationServerUrl = resourceMetadata?.authorizationServers?.firstOrNull() ?: options.serverUrl
    val resource = provider.validateResourceURL(options.serverUrl, resourceMetadata?.resource)
    val metadata = options.client?.let { McpOAuthFlow.discoverAuthorizationServerMetadata(it, authorizationServerUrl) }
    var clientInformation = provider.clientInformation()
    if (clientInformation == null) {
        if (options.authorizationCode != null) {
            throw MCPClientError(
                "Existing OAuth client information is required when exchanging an authorization code.",
            )
        }
        if (options.client != null) {
            clientInformation = McpOAuthFlow.registerClient(
                client = options.client,
                authorizationServerUrl = authorizationServerUrl,
                metadata = metadata,
                clientMetadata = provider.clientMetadata,
            )
            provider.saveClientInformation(clientInformation)
        }
    }
    if (options.authorizationCode != null) {
        val client = options.client ?: throw MCPClientError(
            "OAuth authorization-code exchange requires AuthOptions.client.",
        )
        val info = clientInformation ?: throw MCPClientError(
            "Existing OAuth client information is required when exchanging an authorization code.",
        )
        provider.storedState()?.let { expected ->
            if (expected != options.callbackState) {
                throw MCPClientError("OAuth state parameter mismatch - possible CSRF attack.")
            }
        }
        val tokens = McpOAuthFlow.exchangeAuthorization(
            client = client,
            authorizationServerUrl = authorizationServerUrl,
            metadata = metadata,
            clientInformation = info,
            authorizationCode = options.authorizationCode,
            codeVerifier = provider.codeVerifier(),
            redirectUri = provider.redirectUrl,
            resource = resource,
            addClientAuthentication = { headers, params, url, metadata ->
                val result = provider.addClientAuthentication(headers, params, url, metadata)
                headers.putAll(result.additionalHeaders)
                params.putAll(result.additionalParams)
            },
        )
        provider.saveTokens(tokens)
        return AuthResult.AUTHORIZED
    }

    if (currentTokens?.refreshToken?.isNotBlank() == true && options.client != null && clientInformation != null) {
        try {
            val tokens = McpOAuthFlow.refreshAuthorization(
                client = options.client,
                authorizationServerUrl = authorizationServerUrl,
                metadata = metadata,
                clientInformation = clientInformation,
                refreshToken = currentTokens.refreshToken,
                resource = resource,
                addClientAuthentication = { headers, params, url, metadata ->
                    val result = provider.addClientAuthentication(headers, params, url, metadata)
                    headers.putAll(result.additionalHeaders)
                    params.putAll(result.additionalParams)
                },
            )
            provider.saveTokens(tokens)
            return AuthResult.AUTHORIZED
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            // Expired/revoked refresh tokens should fall through to a fresh authorization redirect.
        }
    }

    if (currentTokens?.accessToken?.isNotBlank() == true && !reauthorize) {
        return AuthResult.AUTHORIZED
    }

    // OAuth state (CSRF) and PKCE code_verifier must come from a CSPRNG, not the
    // seeded Random.Default — RFC 7636 §4.1 requires high-entropy cryptographic random.
    val state = provider.state() ?: IdGenerator.generate(prefix = "mcp", random = SecureRandom())
    provider.saveState(state)
    val codeVerifier = IdGenerator {
        prefix("mcp-verifier")
        size(48)
        random(SecureRandom())
    }.generate()
    provider.saveCodeVerifier(codeVerifier)

    val authClientInformation = clientInformation
        ?: OAuthClientInformation {
            clientId(provider.clientMetadata.clientName ?: DEFAULT_MCP_CLIENT_NAME)
        }
    val authorizationUrl = McpOAuthFlow.startAuthorization(
        serverUrl = options.serverUrl,
        metadata = metadata,
        clientInformation = authClientInformation,
        redirectUrl = provider.redirectUrl,
        scope = options.scope ?: provider.clientMetadata.scope,
        state = state,
        codeVerifier = codeVerifier,
        resource = resource,
    )
    provider.redirectToAuthorization(authorizationUrl)
    return AuthResult.REDIRECT
}
}

public enum class MCPTransportKind {
    Http,
    Sse,
}

public class MCPTransportConfig internal constructor(
    public val type: MCPTransportKind,
    public val url: String,
    public val headers: Map<String, String> = emptyMap(),
    public val authProvider: OAuthClientProvider? = null,
    public val engineContext: CoroutineContext = Dispatchers.Default,
    public val reconnectionOptions: MCPReconnectionOptions = MCPReconnectionOptions {},
)

public class MCPTransportConfigBuilder internal constructor() {
    private var type: MCPTransportKind? = null
    private var url: String? = null
    private var headers: Map<String, String> = emptyMap()
    private var authProvider: OAuthClientProvider? = null
    private var engineContext: CoroutineContext = Dispatchers.Default
    private var reconnectionOptions: MCPReconnectionOptions = MCPReconnectionOptions()

    public fun type(value: MCPTransportKind) {
        type = value
    }

    public fun url(value: String) {
        url = value
    }

    public fun headers(value: Map<String, String>) {
        headers = value
    }

    public fun authProvider(value: OAuthClientProvider?) {
        authProvider = value
    }

    public fun engineContext(value: CoroutineContext) {
        engineContext = value
    }

    public fun reconnectionOptions(value: MCPReconnectionOptions) {
        reconnectionOptions = value
    }

    internal fun build(): MCPTransportConfig =
        MCPTransportConfig(
            type = requireNotNull(type) { "MCPTransportConfig.type is required" },
            url = requireNotNull(url) { "MCPTransportConfig.url is required" },
            headers = headers,
            authProvider = authProvider,
            engineContext = engineContext,
            reconnectionOptions = reconnectionOptions,
        )
}

public fun MCPTransportConfig(
    block: MCPTransportConfigBuilder.() -> Unit = {},
): MCPTransportConfig =
    MCPTransportConfigBuilder().apply(block).build()

@Poko
public class MCPReconnectionOptions internal constructor(
    public val initialReconnectionDelayMillis: Long = 1_000,
    public val reconnectionDelayGrowFactor: Double = 1.5,
    public val maxReconnectionDelayMillis: Long = 30_000,
    public val maxRetries: Int = 2,
)

public class MCPReconnectionOptionsBuilder internal constructor() {
    private var initialReconnectionDelayMillis: Long = 1_000
    private var reconnectionDelayGrowFactor: Double = 1.5
    private var maxReconnectionDelayMillis: Long = 30_000
    private var maxRetries: Int = 2

    public fun initialReconnectionDelayMillis(value: Long) {
        initialReconnectionDelayMillis = value
    }

    public fun reconnectionDelayGrowFactor(value: Double) {
        reconnectionDelayGrowFactor = value
    }

    public fun maxReconnectionDelayMillis(value: Long) {
        maxReconnectionDelayMillis = value
    }

    public fun maxRetries(value: Int) {
        maxRetries = value
    }

    internal fun build(): MCPReconnectionOptions =
        MCPReconnectionOptions(
            initialReconnectionDelayMillis = initialReconnectionDelayMillis,
            reconnectionDelayGrowFactor = reconnectionDelayGrowFactor,
            maxReconnectionDelayMillis = maxReconnectionDelayMillis,
            maxRetries = maxRetries,
        )
}

public fun MCPReconnectionOptions(
    block: MCPReconnectionOptionsBuilder.() -> Unit = {},
): MCPReconnectionOptions =
    MCPReconnectionOptionsBuilder().apply(block).build()

@OptIn(InternalAiSdkApi::class)
public fun CreateMcpTransport(client: HttpClient, config: MCPTransportConfig): MCPTransport =
    when (config.type) {
        MCPTransportKind.Http ->
            HttpMCPTransport(
                client,
                config.url,
                config.headers,
                config.authProvider,
                config.engineContext,
                config.reconnectionOptions,
            )
        MCPTransportKind.Sse ->
            SseMCPTransport(client, config.url, config.headers, config.authProvider, config.engineContext)
    }

/**
 * Single owner of an MCP transport's connection lifecycle. The connection state
 * and its [CoroutineScope] + reader [Job] move together through one atomic
 * reference, so a lifecycle flag can never drift from the job's actual existence
 * — the structural fix for the recurring transport start/close/reader-teardown
 * races (a flag set without the job, a job left running after a flag flip, a
 * guard flag stuck after the reader died). Each transport keeps its own protocol
 * logic (handshake, retry, subprocess) and delegates only scope/reader ownership
 * here. The five-state contract is: Idle → Active(scope, reader?) → Idle (reader
 * died, reconnectable) or → Closed (permanent).
 */
@OptIn(ExperimentalAtomicApi::class)
internal class McpConnectionLifecycle {
    private sealed interface State {
        data object Idle : State
        class Active(val scope: CoroutineScope, val reader: Job?) : State
        data object Closed : State
    }

    private val state = AtomicReference<State>(State.Idle)

    /** True only while a connection is live (between a winning [begin] and a [close]/[onReaderExited]). */
    val isActive: Boolean get() = state.load() is State.Active

    /**
     * Attempt Idle → Active. The single caller that wins gets the freshly built
     * [CoroutineScope]; any caller that finds the lifecycle already Active or
     * Closed gets null. This is the concurrent-start / already-started guard.
     * The scope is built by [scopeFactory] only after the transition is claimed,
     * and cancelled if a CAS race is lost.
     */
    fun begin(scopeFactory: () -> CoroutineScope): CoroutineScope? {
        val current = state.load()
        if (current !is State.Idle) return null
        val scope = scopeFactory()
        return if (state.compareAndSet(current, State.Active(scope, reader = null))) {
            scope
        } else {
            scope.cancel()
            null
        }
    }

    /** Publish (or replace, e.g. on an inbound retry) the reader job while Active; no-op otherwise. */
    fun setReader(job: Job?) {
        while (true) {
            val current = state.load() as? State.Active ?: return
            if (state.compareAndSet(current, State.Active(current.scope, job))) return
        }
    }

    /** The live scope while Active, else null. */
    fun scopeOrNull(): CoroutineScope? = (state.load() as? State.Active)?.scope

    /**
     * Signal that the reader exited on its own (connection dropped). Active → Idle
     * so a fresh [begin] can reconnect, returning the now-defunct scope for the
     * caller to cancel. Returns null (no-op) once [close] has won — close stays
     * authoritative. Mutual exclusion with [close] is what makes onClose fire
     * exactly once.
     */
    fun onReaderExited(): CoroutineScope? {
        while (true) {
            val current = state.load()
            if (current !is State.Active) return null
            if (state.compareAndSet(current, State.Idle)) return current.scope
        }
    }

    /**
     * Active → Closed (permanent), handing back (scope, reader) for the caller to
     * cancel and join exactly once. Idempotent: returns null if not Active (a
     * never-started lifecycle still transitions Idle → Closed). When this returns
     * non-null the caller owns firing onClose; when it returns null either the
     * reader already cleaned up (and fired onClose) or close already ran.
     */
    fun close(): Pair<CoroutineScope, Job?>? {
        while (true) {
            val current = state.load()
            if (current is State.Closed) return null
            if (state.compareAndSet(current, State.Closed)) {
                return (current as? State.Active)?.let { it.scope to it.reader }
            }
        }
    }

    /**
     * Cancel [job] and wait for it to finish — UNLESS the current coroutine *is*
     * [job] (e.g. a user `onMessage`/`onClose` handler that calls `transport.close()`
     * runs inside the reader coroutine). Joining yourself never completes, so in that
     * case cancel without joining. Preserves the "reader is fully torn down before
     * close returns" guarantee on the normal (external) close path.
     */
    suspend fun cancelAndJoinUnlessSelf(job: Job?) {
        if (job == null) return
        if (job === coroutineContext[Job]) job.cancel() else job.cancelAndJoin()
    }
}

@InternalAiSdkApi
public class HttpMCPTransport(
    private val client: HttpClient,
    private val url: String,
    private val headers: Map<String, String> = emptyMap(),
    private val authProvider: OAuthClientProvider? = null,
    private val engineContext: CoroutineContext = Dispatchers.Default,
    private val reconnectionOptions: MCPReconnectionOptions = MCPReconnectionOptions {},
) : MCPTransport {
    private var onClose: (() -> Unit)? = null
    private var onError: ((Throwable) -> Unit)? = null
    private var onMessage: (suspend (JSONRPCMessage) -> Unit)? = null
    private var protocolVersion: String? = null

    override fun setOnClose(handler: (() -> Unit)?) { onClose = handler }
    override fun setOnError(handler: ((Throwable) -> Unit)?) { onError = handler }
    override fun setOnMessage(handler: (suspend (JSONRPCMessage) -> Unit)?) { onMessage = handler }
    override fun setProtocolVersion(version: String?) { protocolVersion = version }

    private val lifecycle = McpConnectionLifecycle()
    private var sessionId: String? = null
    private var inboundJob: Job? = null
    private var inboundRetryRequested: Boolean = false
    private val inboundReconnectAttempts = intArrayOf(0)
    private val inboundMutex = Mutex()

    override suspend fun start() {
        // begin() atomically claims Idle→Active and is the already-started guard.
        // A racing close() either hasn't run (we win) or transitions straight to
        // Closed, in which case ensureInboundSse() sees a null scope and no-ops.
        lifecycle.begin { CoroutineScope(SupervisorJob() + engineContext) }
            ?: throw MCPClientError("MCP HTTP Transport Error: Transport already started.")
        ensureInboundSse()
    }

    override suspend fun send(message: JSONRPCMessage) {
        if (!lifecycle.isActive) throw MCPClientError("MCP HTTP Transport Error: Not connected")
        postMessage(message, triedAuth = false)
    }

    override suspend fun close() {
        // Atomically claim the close (idempotent; no double-close TOCTOU) — only
        // the winning caller runs the teardown below.
        val (connectionScope, _) = lifecycle.close() ?: return
        var cancellation: CancellationException? = null
        try {
            sessionId?.let { session ->
                withTimeoutOrNull(MCP_CLOSE_DELETE_TIMEOUT_MS) {
                    client.request(url) {
                        method = HttpMethod.Delete
                        mcpCommonHeaders(emptyMap()).forEach { (name, value) -> header(name, value) }
                        header("mcp-session-id", session)
                    }
                }
            }
        } catch (error: CancellationException) {
            // Capture and rethrow only after local teardown runs: once close() has won
            // the lifecycle transition, the inbound job/scope MUST be released even when
            // the caller's coroutine is cancelled mid-DELETE, else the reader leaks.
            cancellation = error
        } catch (_: Throwable) {
            // Best-effort session teardown; ignore transport errors on close.
        } finally {
            withContext(NonCancellable) {
                val job = inboundMutex.withLock {
                    inboundRetryRequested = false
                    inboundReconnectAttempts[0] = 0
                    inboundJob.also { inboundJob = null }
                }
                connectionScope.cancel()
                lifecycle.cancelAndJoinUnlessSelf(job)
                onClose?.invoke()
            }
        }
        cancellation?.let { throw it }
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
        mcpSessionId(response)?.let { sessionId = it }
        if (response.status.value == 401 && authProvider != null && !triedAuth) {
            if (
                McpAuth.auth(
                    authProvider,
                    AuthOptions {
                        serverUrl(url)
                        client(client)
                    },
                    reauthorize = true,
                ) != AuthResult.AUTHORIZED
            ) {
                throw UnauthorizedError()
            }
            postMessage(message, triedAuth = true)
            return
        }
        // Surface transport errors for BOTH requests and notifications. This check must run
        // before the 202/notification short-circuit below, otherwise a non-2xx response to a
        // notification POST (e.g. a rejected notifications/initialized handshake) would be
        // silently swallowed and init() would report success on an uninitialized session.
        if (response.status.value !in 200..299) {
            val text = with(HttpTransport) { response.bodyAsTextCapped(url) }
            val hint = if (response.status.value == HTTP_NOT_FOUND) {
                ". This server does not support HTTP transport. Try using `sse` transport instead"
            } else {
                ""
            }
            val error =
                MCPClientError(
                    "MCP HTTP Transport Error: POSTing to endpoint (HTTP ${response.status.value})$hint: $text",
                )
            onError?.invoke(error)
            throw error
        }
        if (response.status.value == 202 || message is JSONRPCNotification) {
            ensureInboundSse()
            return
        }
        val contentType = response.headers[HttpHeaders.ContentType].orEmpty()
        when {
            contentType.contains("application/json", ignoreCase = true) -> {
                val text = with(HttpTransport) { response.bodyAsTextCapped(url) }
                JSONRPCMessage.fromJsonBatch(text).forEach { onMessage?.invoke(it) }
            }
            contentType.contains("text/event-stream", ignoreCase = true) -> launchPostResponseSse(response)
            else -> {
                val error = MCPClientError("MCP HTTP Transport Error: Unexpected content type: $contentType")
                onError?.invoke(error)
                throw error
            }
        }
    }

    // A POST may be answered with an SSE stream that stays open until the server has
    // streamed its response(s). Parsing it inline would block send() — and therefore
    // the caller's request — until the stream closes. Launch the parse on the
    // connection scope (the same way readInboundSse() is launched) and return
    // immediately: the background reader delivers the response via onMessage,
    // completing the caller's awaiting deferred. Mirrors the reference's non-blocking
    // `processEvents(); return;`.
    private fun launchPostResponseSse(response: HttpResponse) {
        val activeScope = lifecycle.scopeOrNull() ?: return
        activeScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                McpSseFrame.parseStreamReleasing(response.bodyAsChannel()) { event ->
                    if (event.event == "message") {
                        // Isolate per-message handling (mirrors readInboundSse): a malformed/
                        // unknown-ID frame is a NON-fatal protocol error routed to onError, not
                        // an unwind that kills the rest of the stream.
                        try {
                            onMessage?.invoke(JSONRPCMessage.fromJson(event.data))
                        } catch (error: Throwable) {
                            if (error is CancellationException) throw error
                            onError?.invoke(error)
                        }
                    }
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                if (lifecycle.isActive) onError?.invoke(error)
            }
        }
    }

    private suspend fun ensureInboundSse(resetReconnectAttempts: Boolean = true) {
        val activeScope = lifecycle.scopeOrNull() ?: return
        inboundMutex.withLock {
            val current = inboundJob
            if (current == null || current.isCompleted) {
                inboundRetryRequested = false
                if (resetReconnectAttempts) inboundReconnectAttempts[0] = 0
                inboundJob = activeScope.launch(start = CoroutineStart.UNDISPATCHED) { readInboundSse() }
            } else {
                inboundRetryRequested = true
            }
        }
    }

    private enum class InboundSseCompletion {
        Clean,
        Error,
    }

    @Suppress("CyclomaticComplexMethod")
    private suspend fun readInboundSse() {
        var completion = InboundSseCompletion.Clean
        try {
            val response = client.request(url) {
                method = HttpMethod.Get
                mcpCommonHeaders(
                    mapOf(HttpHeaders.Accept to "text/event-stream")
                ).forEach { (name, value) -> header(name, value) }
            }
            mcpSessionId(response)?.let { sessionId = it }
            if (response.status.value == 405) return
            if (response.status.value !in 200..299) {
                val error =
                    MCPClientError(
                        "MCP HTTP Transport Error: GET SSE failed: " +
                            "${response.status.value} ${response.status.description}"
                    )
                onError?.invoke(error)
                return
            }
            val receivedEvent = booleanArrayOf(false)
            McpSseFrame.parseStreamReleasing(response.bodyAsChannel()) { event ->
                if (!receivedEvent[0]) {
                    receivedEvent[0] = true
                    inboundMutex.withLock { inboundReconnectAttempts[0] = 0 }
                }
                if (event.event == "message") {
                    // Isolate per-message handling (mirrors the stdio reader): a malformed/unknown-ID
                    // frame is a NON-fatal protocol error routed to onError — it must not unwind
                    // parseStream and kill the inbound reader (dropping all later messages).
                    try {
                        onMessage?.invoke(JSONRPCMessage.fromJson(event.data))
                    } catch (error: Throwable) {
                        if (error is CancellationException) throw error
                        onError?.invoke(error)
                    }
                }
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            completion = InboundSseCompletion.Error
            if (lifecycle.isActive) onError?.invoke(error)
        } finally {
            finishInboundSse(completion)
        }
    }

    private suspend fun finishInboundSse(completion: InboundSseCompletion) {
        val currentJob = coroutineContext[Job]
        var restartImmediately = false
        var reconnectDelayMillis: Long? = null
        var maxRetriesExceeded: Int? = null
        inboundMutex.withLock {
            when {
                !lifecycle.isActive -> {
                    inboundRetryRequested = false
                    inboundReconnectAttempts[0] = 0
                    inboundJob = null
                }
                inboundRetryRequested -> {
                    inboundRetryRequested = false
                    inboundReconnectAttempts[0] = 0
                    inboundJob = null
                    restartImmediately = true
                }
                completion == InboundSseCompletion.Error -> {
                    reconnectDelayMillis = nextInboundReconnectDelayMillis()
                    if (reconnectDelayMillis == null) {
                        inboundJob = null
                        if (reconnectionOptions.maxRetries > 0) {
                            maxRetriesExceeded = reconnectionOptions.maxRetries
                        }
                    }
                }
                else -> {
                    inboundReconnectAttempts[0] = 0
                    inboundJob = null
                }
            }
        }
        maxRetriesExceeded?.let { maxRetries ->
            onError?.invoke(MCPClientError("MCP HTTP Transport Error: Maximum reconnection attempts ($maxRetries) exceeded."))
        }
        if (restartImmediately) {
            ensureInboundSse(resetReconnectAttempts = true)
            return
        }
        reconnectDelayMillis?.let { delayMillis ->
            delay(delayMillis)
            var restart = false
            inboundMutex.withLock {
                if (lifecycle.isActive && inboundJob === currentJob) {
                    inboundJob = null
                    restart = true
                }
            }
            if (restart) {
                ensureInboundSse(resetReconnectAttempts = false)
            }
        }
    }

    private fun nextInboundReconnectDelayMillis(): Long? {
        val maxRetries = reconnectionOptions.maxRetries
        if (maxRetries <= 0 || inboundReconnectAttempts[0] >= maxRetries) return null
        val initial = reconnectionOptions.initialReconnectionDelayMillis.coerceAtLeast(0)
        val factor = reconnectionOptions.reconnectionDelayGrowFactor
            .takeIf { it.isFinite() && it > 0.0 }
            ?: 1.0
        val maxDelay = reconnectionOptions.maxReconnectionDelayMillis.coerceAtLeast(0)
        val delayMillis = (initial * factor.pow(inboundReconnectAttempts[0])).roundToLong()
        inboundReconnectAttempts[0] += 1
        return delayMillis.coerceAtMost(maxDelay)
    }

    private suspend fun mcpCommonHeaders(base: Map<String, String>): Map<String, String> {
        val values = linkedMapOf<String, String?>()
        headers.forEach { (key, value) -> values[key] = value }
        base.forEach { (key, value) -> values[key] = value }
        values["mcp-protocol-version"] = protocolVersion ?: LATEST_PROTOCOL_VERSION
        sessionId?.let { values["mcp-session-id"] = it }
        authProvider?.tokens()?.accessToken?.takeIf {
            it.isNotBlank()
        }?.let { values[HttpHeaders.Authorization] = "Bearer $it" }
        return ProviderHeaders.withUserAgentSuffix(values, "ai-sdk/mcp/$MCP_PACKAGE_VERSION")
    }

    private fun mcpSessionId(response: HttpResponse): String? =
        response.headers["mcp-session-id"]?.takeIf { it.isNotBlank() }
}

@InternalAiSdkApi
public class SseMCPTransport(
    private val client: HttpClient,
    private val url: String,
    private val headers: Map<String, String> = emptyMap(),
    private val authProvider: OAuthClientProvider? = null,
    private val engineContext: CoroutineContext = Dispatchers.Default,
) : MCPTransport {
    private var onClose: (() -> Unit)? = null
    private var onError: ((Throwable) -> Unit)? = null
    private var onMessage: (suspend (JSONRPCMessage) -> Unit)? = null
    private var protocolVersion: String? = null

    override fun setOnClose(handler: (() -> Unit)?) { onClose = handler }
    override fun setOnError(handler: ((Throwable) -> Unit)?) { onError = handler }
    override fun setOnMessage(handler: (suspend (JSONRPCMessage) -> Unit)?) { onMessage = handler }
    override fun setProtocolVersion(version: String?) { protocolVersion = version }

    private val lifecycle = McpConnectionLifecycle()
    private var endpoint: String? = null

    // SSE handshake + per-event dispatch + reader teardown; the branchiness is
    // inherent to a transport's start path.
    @Suppress("CyclomaticComplexMethod")
    override suspend fun start() {
        // begin() is the concurrent-start / already-connected guard: exactly one
        // caller wins Idle→Active and receives the scope; others get null and return.
        val connectionScope = lifecycle.begin { CoroutineScope(SupervisorJob() + engineContext) } ?: return
        val ready = CompletableDeferred<Unit>()
        val reader = connectionScope.launch {
            // Single-coroutine local: the handshake completed at least once. Drives
            // both the onError and onClose "was it ever connected?" decisions
            // (replacing the old `connected` flag) without an experimental API.
            var established = false
            try {
                val response = openSseConnection(triedAuth = false)
                if (response.status.value !in 200..299) {
                    val hint = if (response.status.value == HTTP_METHOD_NOT_ALLOWED) {
                        ". This server does not support SSE transport. Try using `http` transport instead"
                    } else {
                        ""
                    }
                    throw MCPClientError(
                        "MCP SSE Transport Error: ${response.status.value} ${response.status.description}$hint",
                    )
                }
                McpSseFrame.parseStreamReleasing(response.bodyAsChannel()) { event ->
                    when (event.event) {
                        "endpoint" -> {
                            endpoint = McpUrl.resolve(event.data, url).also { resolved ->
                                if (McpUrl.origin(resolved) != McpUrl.origin(url)) {
                                    throw MCPClientError(
                                        "MCP SSE Transport Error: Endpoint origin does not " +
                                            "match connection origin: ${McpUrl.origin(resolved)}",
                                    )
                                }
                            }
                            established = true
                            if (!ready.isCompleted) ready.complete(Unit)
                        }
                        // Isolate per-message handling (mirrors the stdio reader): a malformed/
                        // unknown-ID frame is a NON-fatal protocol error routed to onError. Only the
                        // "message" branch is guarded — an "endpoint" handshake error stays fatal.
                        "message" -> try {
                            onMessage?.invoke(JSONRPCMessage.fromJson(event.data))
                        } catch (error: Throwable) {
                            if (error is CancellationException) throw error
                            onError?.invoke(error)
                        }
                    }
                }
            } catch (error: Throwable) {
                if (!ready.isCompleted) ready.completeExceptionally(error)
                if (error is CancellationException) throw error
                if (established) onError?.invoke(error)
            } finally {
                // Reader exited. Release the lifecycle (Active→Idle) so a later
                // start() can reconnect, and cancel the defunct scope. Fire onClose
                // only if the connection had been established (matching the prior
                // "if connected" contract); a pre-handshake failure surfaces via the
                // thrown start(). If close() already won the transition this is a
                // no-op and close() owns onClose — so it fires exactly once.
                // `endpoint` is intentionally NOT cleared here: the discovered
                // endpoint stays usable for outbound POSTs after the inbound SSE
                // stream ends (it is cleared only on close()).
                lifecycle.onReaderExited()?.let { deadScope ->
                    deadScope.cancel()
                    if (established) onClose?.invoke()
                }
            }
        }
        lifecycle.setReader(reader)
        try {
            // Bound the handshake (wait for the SSE `endpoint` event) in real time
            // so an unresponsive server can't hang start() forever; runTest-safe.
            HttpTransport.withRealTimeout(MCP_DEFAULT_HANDSHAKE_TIMEOUT_MS) { ready.await() }
        } catch (error: Throwable) {
            // Pre-handshake failure (incl. handshake timeout): the reader's finally
            // returns the lifecycle to Idle (startable again); join it so cleanup
            // completes, then rethrow.
            reader.cancelAndJoin()
            throw error
        }
    }

    private suspend fun openSseConnection(triedAuth: Boolean): HttpResponse {
        val response = client.request(url) {
            method = HttpMethod.Get
            mcpCommonHeaders(
                mapOf(HttpHeaders.Accept to "text/event-stream")
            ).forEach { (name, value) -> header(name, value) }
        }
        if (response.status.value == 401 && authProvider != null && !triedAuth) {
            if (
                McpAuth.auth(
                    authProvider,
                    AuthOptions {
                        serverUrl(url)
                        client(client)
                    },
                    reauthorize = true,
                ) != AuthResult.AUTHORIZED
            ) {
                throw UnauthorizedError()
            }
            return openSseConnection(triedAuth = true)
        }
        return response
    }

    override suspend fun send(message: JSONRPCMessage): Unit = sendInternal(message, triedAuth = false)

    // triedAuth guards the 401 re-auth retry to a SINGLE attempt — a server that keeps
    // returning 401 after a successful auth() must not recurse until stack overflow.
    @Suppress("ThrowsCount")
    private suspend fun sendInternal(message: JSONRPCMessage, triedAuth: Boolean) {
        val destination = endpoint ?: throw MCPClientError("MCP SSE Transport Error: Not connected")
        val response = client.request(destination) {
            method = HttpMethod.Post
            contentType(ContentType.Application.Json)
            mcpCommonHeaders(
                mapOf(HttpHeaders.ContentType to ContentType.Application.Json.toString())
            ).forEach { (name, value) -> header(name, value) }
            setBody(message.toJsonString())
        }
        if (response.status.value == 401 && authProvider != null && !triedAuth) {
            if (
                McpAuth.auth(
                    authProvider,
                    AuthOptions {
                        serverUrl(url)
                        client(client)
                    },
                    reauthorize = true,
                ) != AuthResult.AUTHORIZED
            ) {
                throw UnauthorizedError()
            }
            sendInternal(message, triedAuth = true)
            return
        }
        if (response.status.value !in 200..299) {
            val text = with(HttpTransport) { response.bodyAsTextCapped(destination) }
            val error =
                MCPClientError(
                    "MCP SSE Transport Error: POSTing to endpoint " +
                        "(HTTP ${response.status.value}): $text",
                )
            onError?.invoke(error)
            throw error
        }
    }

    override suspend fun close() {
        endpoint = null
        // close() wins Active→Closed and owns the teardown; if the reader already
        // died (Idle) this is a no-op and the reader fired onClose.
        val (scope, reader) = lifecycle.close() ?: return
        scope.cancel()
        lifecycle.cancelAndJoinUnlessSelf(reader)
        onClose?.invoke()
    }

    private suspend fun mcpCommonHeaders(base: Map<String, String>): Map<String, String> {
        val values = linkedMapOf<String, String?>()
        headers.forEach { (key, value) -> values[key] = value }
        base.forEach { (key, value) -> values[key] = value }
        values["mcp-protocol-version"] = protocolVersion ?: LATEST_PROTOCOL_VERSION
        authProvider?.tokens()?.accessToken?.takeIf {
            it.isNotBlank()
        }?.let { values[HttpHeaders.Authorization] = "Bearer $it" }
        return ProviderHeaders.withUserAgentSuffix(values, "ai-sdk/mcp/$MCP_PACKAGE_VERSION")
    }
}

internal data class McpSseFrame(
    val event: String,
    val data: String,
    val id: String? = null,
) {
    // Per-frame accumulator for the SSE line parser. Holds the mutable
    // event/id/data state so the companion's parseStream stays var-free.
    private class FrameBuffer {
        private var eventName: String = "message"
        private var eventId: String? = null
        private val data: MutableList<String> = mutableListOf()

        fun setEvent(value: String) { eventName = value }
        fun setId(value: String) { eventId = value }

        fun addData(value: String) {
            if (data.size >= MCP_SSE_MAX_DATA_LINES) {
                reset()
                throw MCPClientError(
                    "MCP SSE Transport Error: SSE event exceeded " +
                        "$MCP_SSE_MAX_DATA_LINES data lines; possible malformed stream"
                )
            }
            data += value
        }

        suspend fun flush(onEvent: suspend (McpSseFrame) -> Unit) {
            if (data.isEmpty()) return
            onEvent(McpSseFrame(eventName, data.joinToString("\n"), eventId))
            reset()
        }

        private fun reset() {
            eventName = "message"
            eventId = null
            data.clear()
        }
    }

    internal companion object {
        suspend fun parseStream(channel: ByteReadChannel, onEvent: suspend (McpSseFrame) -> Unit) {
            val frame = FrameBuffer()
            while (true) {
                val line = channel.readLine() ?: break
                when {
                    line.isEmpty() -> frame.flush(onEvent)
                    line.startsWith("event:") -> frame.setEvent(line.removePrefix("event:").trimStart())
                    line.startsWith("data:") -> frame.addData(line.removePrefix("data:").trimStart())
                    line.startsWith("id:") -> frame.setId(line.removePrefix("id:").trimStart())
                }
            }
            frame.flush(onEvent)
        }

        /**
         * [parseStream] that ALWAYS releases the channel on EOF/error/cancel — on some Ktor engines
         * the connection only closes when the body channel is explicitly cancelled (mirrors
         * HttpTransport.streamSse). Used by the MCP SSE read sites so the release isn't duplicated.
         */
        suspend fun parseStreamReleasing(channel: ByteReadChannel, onEvent: suspend (McpSseFrame) -> Unit) {
            try {
                parseStream(channel, onEvent)
            } finally {
                channel.cancel(null)
            }
        }
    }
}

/** URL origin/relative-resolution helpers shared by the SSE transport and the OAuth flow. */
internal object McpUrl {
    fun resolve(value: String, base: String): String {
        if (value.startsWith("http://") || value.startsWith("https://")) return value
        val origin = origin(base)
        if (value.startsWith("/")) return origin + value
        return base.substringBeforeLast('/', missingDelimiterValue = origin).trimEnd('/') + "/" + value
    }

    fun origin(url: String): String {
        val schemeEnd = url.indexOf("://")
        val authorityStart = if (schemeEnd >= 0) schemeEnd + 3 else 0
        val authorityEnd = url.indexOf('/', authorityStart).takeIf { it >= 0 } ?: url.length
        return url.substring(0, authorityEnd)
    }
}

@Serializable
@Poko
public class StdioConfig internal constructor(
    public val command: String,
    public val args: List<String> = emptyList(),
    public val env: Map<String, String> = emptyMap(),
    public val cwd: String? = null,
)

public class StdioConfigBuilder internal constructor() {
    private var command: String? = null
    private var args: List<String> = emptyList()
    private var env: Map<String, String> = emptyMap()
    private var cwd: String? = null

    public fun command(value: String) {
        command = value
    }

    public fun args(value: List<String>) {
        args = value
    }

    public fun env(value: Map<String, String>) {
        env = value
    }

    public fun cwd(value: String?) {
        cwd = value
    }

    internal fun build(): StdioConfig =
        StdioConfig(
            command = requireNotNull(command) { "StdioConfig.command is required" },
            args = args,
            env = env,
            cwd = cwd,
        )
}

public fun StdioConfig(
    block: StdioConfigBuilder.() -> Unit = {},
): StdioConfig =
    StdioConfigBuilder().apply(block).build()

internal interface MCPStdioProcess {
    suspend fun readLine(): String?
    suspend fun writeLine(line: String)
    suspend fun close()
}

internal expect fun CreateMCPStdioProcess(config: StdioConfig): MCPStdioProcess

@ExperimentalAiSdkApi
public class Experimental_StdioMCPTransport(
    public val config: StdioConfig,
    private val engineContext: CoroutineContext = Dispatchers.Default,
) : MCPTransport {
    private var onClose: (() -> Unit)? = null
    private var onError: ((Throwable) -> Unit)? = null
    private var onMessage: (suspend (JSONRPCMessage) -> Unit)? = null
    private var protocolVersion: String? = null

    override fun setOnClose(handler: (() -> Unit)?) { onClose = handler }
    override fun setOnError(handler: ((Throwable) -> Unit)?) { onError = handler }
    override fun setOnMessage(handler: (suspend (JSONRPCMessage) -> Unit)?) { onMessage = handler }
    override fun setProtocolVersion(version: String?) { protocolVersion = version }

    private val lifecycle = McpConnectionLifecycle()
    private var process: MCPStdioProcess? = null

    override suspend fun start() {
        val readerScope = lifecycle.begin { CoroutineScope(SupervisorJob() + engineContext) }
            ?: throw MCPClientError("StdioMCPTransport already started.")
        // Close any pre-existing process before overwriting the field — a reconnect after the
        // reader EOF'd would otherwise leak the prior child + its FDs.
        process?.let { stale -> closeProcessPreservingCancellation(stale) }
        val started = try {
            CreateMCPStdioProcess(config)
        } catch (@Suppress("TooGenericExceptionCaught") error: Throwable) {
            // Spawn failed (bad command/cwd/permissions) AFTER begin() already won Idle->Active: undo
            // the transition and cancel the freshly built scope, else the transport is wedged Active
            // (a later start() throws "already started") with a leaked scope. Rethrow the real cause.
            lifecycle.onReaderExited()?.cancel()
            throw error
        }
        process = started
        lifecycle.setReader(
            readerScope.launch {
                try {
                    while (true) {
                        val line = started.readLine() ?: break
                        try {
                            onMessage?.invoke(JSONRPCMessage.fromJson(line))
                        } catch (error: Throwable) {
                            if (error is CancellationException) throw error
                            onError?.invoke(error)
                        }
                    }
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    onError?.invoke(error)
                } finally {
                    // Reader/process exited (EOF or error). Release the lifecycle and, when this
                    // reader owns the teardown, destroy the child + close its streams — the reader
                    // exiting does NOT otherwise do so, leaking the process handle + FDs — then fire
                    // onClose. If close() already won the transition it owns onClose.
                    lifecycle.onReaderExited()?.let { deadScope ->
                        withContext(NonCancellable) {
                            closeProcessForTeardown(started)
                            deadScope.cancel()
                            if (process === started) process = null
                            onClose?.invoke()
                        }
                    }
                }
            },
        )
    }

    override suspend fun send(message: JSONRPCMessage) {
        val active = process ?: throw MCPClientError("StdioClientTransport not connected")
        try {
            active.writeLine(message.toJsonString())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            throw MCPClientError("StdioMCPTransport: write failed — process may have exited", cause = error)
        }
    }

    override suspend fun close() {
        val handback = lifecycle.close()
        val p = process
        process = null
        if (handback == null) {
            // Reader already exited (or never started): just release the process.
            withContext(NonCancellable) {
                p?.let { closeProcessForTeardown(it) }
            }
            return
        }
        val (scope, reader) = handback
        // Local teardown is non-cancellable so a cancelled caller can't strand the
        // reader job or skip onClose after close() won the lifecycle transition.
        // Cancel the scope, close the process to unblock a parked readLine, then
        // join — the ordering that prevents close() hanging on the blocking read.
        withContext(NonCancellable) {
            scope.cancel()
            p?.let { closeProcessForTeardown(it) }
            lifecycle.cancelAndJoinUnlessSelf(reader)
            onClose?.invoke()
        }
    }

    /**
     * Close a stdio process, swallowing only non-cancellation failures. Cancellation
     * is rethrown so structured concurrency is preserved (stdlib `runCatching` would
     * capture it into a `Result.failure` instead). Member extension (not a top-level
     * one) per the project's no-loose-top-level-function tenet.
     */
    private suspend fun closeProcessPreservingCancellation(process: MCPStdioProcess) {
        try {
            process.close()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            // Best-effort stdio teardown; the lifecycle owner still completes local cleanup.
        }
    }

    private suspend fun closeProcessForTeardown(process: MCPStdioProcess) {
        try {
            process.close()
        } catch (_: Throwable) {
            // Teardown is already running in NonCancellable; process close failures are best-effort.
        }
    }
}
