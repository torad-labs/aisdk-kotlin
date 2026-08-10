@file:OptIn(LowLevelLanguageModelApi::class)

package ai.torad.aisdk
import ai.torad.aisdk.providers.Anthropic
import ai.torad.aisdk.providers.AnthropicProviderSettings
import ai.torad.aisdk.testing.FlowDrain.drainAllItems
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

class AnthropicProviderAbortTest {
    @Test
    fun `messages generate is cancelled when abort fires in flight`() = runTest {
        val requestStarted = CompletableDeferred<Unit>()
        val responseBody = TestResponseController()
        val controller = AbortController()
        val client = HttpClient(
            MockEngine {
                requestStarted.complete(Unit)
                respond(
                    content = responseBody.stream,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            }
        )
        val model = Anthropic(
            client,
            AnthropicProviderSettings {
                apiKey("key")
                baseURL("https://anthropic.test/v1")
            },
        ).messages(ModelId("claude-sonnet-4-5"))

        val pending = async {
            model.generate(
                LanguageModelCallParams {
                    messages(listOf(UserMessage("hi")))
                    abortSignal(controller.signal)
                },
            )
        }
        requestStarted.await()
        controller.abort()

        try {
            assertFailsWith<AbortError> { HttpTransport.withRealTimeout(5_000) { pending.await() } }
        } finally {
            pending.cancel()
            responseBody.close()
        }
    }

    @Test
    fun `messages stream honors an already aborted signal`() = runTest {
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://anthropic.test/v1/messages" to UrlHandler(
                    UrlResponse.StreamChunks(listOf(BLOCK_START_CHUNK, TEXT_DELTA_CHUNK)),
                ),
            ),
        )
        fixture.server.start()
        val controller = AbortController()
        controller.abort()
        val model = Anthropic(
            fixture.httpClient(),
            AnthropicProviderSettings {
                apiKey("key")
                baseURL("https://anthropic.test/v1")
            },
        ).messages(ModelId("claude-sonnet-4-5"))

        assertFailsWith<AbortError> {
            drainAllItems(
                model.stream(
                    LanguageModelCallParams {
                        messages(listOf(UserMessage("hi")))
                        abortSignal(controller.signal)
                    },
                ),
            )
        }
    }

    @Test
    fun `messages stream is cancelled when abort fires in flight`() = runTest {
        val responseBody = TestResponseController()
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://anthropic.test/v1/messages" to UrlHandler(UrlResponse.ControlledStream(responseBody)),
            ),
        )
        fixture.server.start()
        val controller = AbortController()
        val model = Anthropic(
            fixture.httpClient(),
            AnthropicProviderSettings {
                apiKey("key")
                baseURL("https://anthropic.test/v1")
            },
        ).messages(ModelId("claude-sonnet-4-5"))

        val firstDelta = CompletableDeferred<Unit>()
        val pending = async {
            model.stream(
                LanguageModelCallParams {
                    messages(listOf(UserMessage("hi")))
                    abortSignal(controller.signal)
                },
            ).collect { event -> if (event is StreamEvent.TextDelta) firstDelta.complete(Unit) }
        }
        responseBody.write(BLOCK_START_CHUNK)
        responseBody.write(TEXT_DELTA_CHUNK)
        firstDelta.await()
        controller.abort()
        responseBody.write(TEXT_DELTA_CHUNK)

        try {
            assertFailsWith<AbortError> { HttpTransport.withRealTimeout(5_000) { pending.await() } }
        } finally {
            pending.cancel()
            responseBody.close()
        }
    }

    private companion object {
        const val BLOCK_START_CHUNK =
            """data: {"type":"content_block_start","index":0,""" +
                """"content_block":{"type":"text","id":"b0"}}""" + "\n\n"
        const val TEXT_DELTA_CHUNK =
            """data: {"type":"content_block_delta","index":0,""" +
                """"delta":{"type":"text_delta","text":"hi"}}""" + "\n\n"
    }
}
