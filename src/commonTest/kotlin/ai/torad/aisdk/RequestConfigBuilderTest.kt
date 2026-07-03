package ai.torad.aisdk

import ai.torad.aisdk.ui.ChatRequest
import ai.torad.aisdk.ui.UIMessage
import ai.torad.aisdk.ui.UIMessagePart
import ai.torad.aisdk.ui.UIMessageRole
import kotlinx.serialization.json.JsonPrimitive
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame

class RequestConfigBuilderTest {
    @Test
    fun `Poko request builders keep value semantics`() {
        val text = TextGenerationRequest {
            prompt("hello")
            system("system")
            settings {
                maxRetries(1)
            }
        }
        val equalText = TextGenerationRequest {
            prompt("hello")
            system("system")
            settings {
                maxRetries(1)
            }
        }
        val differentText = TextGenerationRequest {
            prompt("different")
        }
        val message = UIMessage("m1", UIMessageRole.User, listOf(UIMessagePart.Text("hi")))
        val chat = ChatRequest {
            messages(listOf(message))
            body(mapOf("x" to JsonPrimitive(1)))
            headers(mapOf("h" to "v"))
        }
        val equalChat = ChatRequest {
            messages(listOf(message))
            body(mapOf("x" to JsonPrimitive(1)))
            headers(mapOf("h" to "v"))
        }

        assertEquals(equalText, text)
        assertEquals(equalText.hashCode(), text.hashCode())
        assertNotEquals(differentText, text)
        assertEquals(equalChat, chat)
        assertEquals(equalChat.hashCode(), chat.hashCode())
    }

    @Test
    fun `regular request and config builders preserve fields and keep identity semantics`() {
        val abortController = AbortController()
        val completion = CompletionRequest {
            api("/api/custom")
            id("completion-1")
            prompt("hello")
            body(mapOf("temperature" to JsonPrimitive(0.4)))
            abortSignal(abortController.signal)
        }
        val sameShapeCompletion = CompletionRequest {
            api("/api/custom")
            id("completion-1")
            prompt("hello")
            body(mapOf("temperature" to JsonPrimitive(0.4)))
            abortSignal(abortController.signal)
        }
        val transport = NoopMCPTransport()
        val client = MCPClientConfig {
            transport(transport)
            onUncaughtError {}
            clientName("client")
        }
        val transportConfig = MCPTransportConfig {
            type(MCPTransportKind.Http)
            url("https://mcp.example/sse")
            headers(mapOf("authorization" to "Bearer token"))
            engineContext(EmptyCoroutineContext)
        }
        val telemetry = TelemetrySettings {
            functionId("fn")
            metadata(mapOf("trace" to JsonPrimitive("abc")))
            recordInputs(true)
        }
        val requestOptions = MCPRequestOptions {
            signal(abortController.signal)
            timeoutMillis(123)
        }

        assertEquals("/api/custom", completion.api)
        assertSame(abortController.signal, completion.abortSignal)
        assertNotEquals(sameShapeCompletion, completion)
        assertSame(transport, client.transport)
        assertEquals("client", client.clientName)
        assertEquals(MCPTransportKind.Http, transportConfig.type)
        assertEquals("https://mcp.example/sse", transportConfig.url)
        assertSame(EmptyCoroutineContext, transportConfig.engineContext)
        assertEquals("fn", telemetry.functionId)
        assertEquals(JsonPrimitive("abc"), telemetry.metadata["trace"])
        assertSame(abortController.signal, requestOptions.signal)
        assertEquals(123, requestOptions.timeoutMillis)
    }
}

private class NoopMCPTransport : MCPTransport {
    override fun setOnClose(handler: (() -> Unit)?) = Unit
    override fun setOnError(handler: ((Throwable) -> Unit)?) = Unit
    override fun setOnMessage(handler: (suspend (JSONRPCMessage) -> Unit)?) = Unit
    override fun setProtocolVersion(version: String?) = Unit
    override suspend fun start() = Unit
    override suspend fun send(message: JSONRPCMessage) = Unit
    override suspend fun close() = Unit
}
