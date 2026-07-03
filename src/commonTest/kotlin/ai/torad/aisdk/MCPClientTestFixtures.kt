package ai.torad.aisdk

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.test.assertNotNull

@OptIn(ExperimentalAiSdkApi::class, ExperimentalCoroutinesApi::class, InternalAiSdkApi::class)
abstract class MCPClientTestBase {
    protected fun objectSchema(vararg required: String): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put(
            "properties",
            buildJsonObject {
                required.forEach { name ->
                    put(name, buildJsonObject { put("type", JsonPrimitive("string")) })
                }
            },
        )
        put("required", kotlinx.serialization.json.JsonArray(required.map(::JsonPrimitive)))
    }

    protected suspend fun waitForRealTime(condition: () -> Boolean) {
        withContext(Dispatchers.Default) {
            // Real wall-clock wait on real I/O (subprocess round-trips, SSE
            // reconnects). Generous so a loaded CI/dev host doesn't flake a
            // genuinely-progressing test; a real hang still fails (at the cap).
            withTimeout(20_000) {
                while (!condition()) delay(10)
            }
        }
    }

    protected fun initializeResult(
        capabilities: MCPServerCapabilities = MCPServerCapabilities(
            tools = JsonObject(emptyMap()),
            resources = JsonObject(emptyMap()),
            prompts = JsonObject(emptyMap()),
            elicitation = ElicitationCapability {},
        ),
        instructions: String? = null,
    ): JsonElement = json.encodeToJsonElement(
        InitializeResult.serializer(),
        InitializeResult(
            protocolVersion = LATEST_PROTOCOL_VERSION,
            capabilities = capabilities,
            serverInfo = Configuration {
                name("fixture-server")
                version("1.0.0")
            },
            instructions = instructions,
        ),
    )

    protected fun listToolsResult(): JsonElement = json.encodeToJsonElement(
        ListToolsResult.serializer(),
        ListToolsResult(
            tools = listOf(
                MCPToolDefinition(
                    name = "echo",
                    title = "Echo",
                    description = "Echo a message",
                    inputSchema = objectSchema("message"),
                    meta = buildJsonObject { put("origin", JsonPrimitive("fixture")) },
                ),
            ),
        ),
    )

    protected fun callToolResult(
        content: List<JsonObject> = emptyList(),
        structuredContent: JsonElement? = null,
        isError: Boolean = false,
    ): JsonElement = json.encodeToJsonElement(
        CallToolResult.serializer(),
        CallToolResult(content = content, structuredContent = structuredContent, isError = isError),
    )

    @Suppress("UNCHECKED_CAST")
    protected fun Tool<*, *, *>?.asJsonTool(): Tool<JsonElement, JsonElement, Unit> =
        assertNotNull(this) as Tool<JsonElement, JsonElement, Unit>

    protected class FakeMCPTransport(
        val handler: suspend FakeMCPTransport.(JSONRPCMessage) -> Unit,
    ) : MCPTransport {
        val sent = mutableListOf<JSONRPCMessage>()
        var startCount = 0
        private var onClose: (() -> Unit)? = null
        private var onError: ((Throwable) -> Unit)? = null
        private var onMessage: (suspend (JSONRPCMessage) -> Unit)? = null
        private var _protocolVersion: String? = null
        val protocolVersion: String? get() = _protocolVersion

        override fun setOnClose(handler: (() -> Unit)?) { onClose = handler }
        override fun setOnError(handler: ((Throwable) -> Unit)?) { onError = handler }
        override fun setOnMessage(handler: (suspend (JSONRPCMessage) -> Unit)?) { onMessage = handler }
        override fun setProtocolVersion(version: String?) { _protocolVersion = version }

        // Concurrent client requests hit send() from multiple coroutines; on a
        // multi-threaded Kotlin/Native dispatcher (linuxX64) an unguarded
        // ArrayList.add races, so serialize the recording.
        private val sentMutex = Mutex()

        override suspend fun start() {
            startCount += 1
        }

        override suspend fun send(message: JSONRPCMessage) {
            sentMutex.withLock { sent += message }
            handler(message)
        }

        override suspend fun close() {
            onClose?.invoke()
        }

        suspend fun respond(id: JsonElement, result: JsonElement) {
            onMessage?.invoke(JSONRPCResponse(id = id, result = result))
        }

        suspend fun fail(id: JsonElement, code: Int, message: String) {
            onMessage?.invoke(JSONRPCError(id = id, error = JSONRPCErrorData(code = code, message = message)))
        }

        suspend fun emitFromServer(message: JSONRPCMessage) {
            onMessage?.invoke(message)
        }
    }

    protected class MemoryOAuthProvider(
        private var tokens: OAuthTokens?,
        private var clientInformation: OAuthClientInformation? = OAuthClientInformation {
            clientId("client-id")
        },
        private val onAddClientAuthentication: (
            suspend (
                headers: Map<String, String>,
                params: Map<String, String>,
                url: String,
                metadata: AuthorizationServerMetadata?,
            ) -> ClientAuthResult
        )? = null,
    ) : OAuthClientProvider {
        var lastAuthorizationUrl: String? = null
        var savedCodeVerifier: String = "verifier"
        private var savedState: String? = null
        override val redirectUrl: String = "https://client.example.com/callback"
        override val clientMetadata: OAuthClientMetadata = OAuthClientMetadata {
            redirectUris(listOf(redirectUrl))
            clientName("client-1")
        }

        override suspend fun tokens(): OAuthTokens? = tokens
        override suspend fun saveTokens(tokens: OAuthTokens) {
            this.tokens = tokens
        }

        override suspend fun redirectToAuthorization(authorizationUrl: String) {
            lastAuthorizationUrl = authorizationUrl
        }

        override suspend fun saveCodeVerifier(codeVerifier: String) {
            savedCodeVerifier = codeVerifier
        }

        override suspend fun codeVerifier(): String = savedCodeVerifier
        override suspend fun clientInformation(): OAuthClientInformation? = clientInformation

        override suspend fun saveClientInformation(clientInformation: OAuthClientInformation) {
            this.clientInformation = clientInformation
        }

        override suspend fun saveState(state: String) {
            savedState = state
        }

        override suspend fun storedState(): String? = savedState

        override suspend fun addClientAuthentication(
            headers: Map<String, String>,
            params: Map<String, String>,
            url: String,
            metadata: AuthorizationServerMetadata?,
        ): ClientAuthResult = onAddClientAuthentication?.invoke(headers, params, url, metadata) ?: ClientAuthResult()
    }

    protected fun Map<String, String>.headerValue(name: String): String? =
        entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    protected val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
}
