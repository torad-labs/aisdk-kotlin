@file:OptIn(LowLevelLanguageModelApi::class)

package ai.torad.aisdk

import ai.torad.aisdk.providers.OpenResponses
import ai.torad.aisdk.providers.OpenResponsesProviderSettings
import ai.torad.aisdk.testing.FlowDrain.drainAllItems
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpenResponsesStreamFailureTest {
    @Test
    fun `stream uses final output item arguments when pending arguments are empty`() = runTest {
        val provider = OpenResponses(
            streamingClient(
                """
                    data: {"type":"response.output_item.added","item":{"type":"function_call","id":"fc_1","call_id":"call_1","name":"search","arguments":""}}

                    data: {"type":"response.output_item.done","item":{"type":"function_call","id":"fc_1","call_id":"call_1","name":"search","arguments":"{\"q\":\"final\"}"}}

                    data: {"type":"response.completed","response":{}}
                """,
            ),
            OpenResponsesProviderSettings {
                url("https://api.test/v1/responses")
                name("openresponses")
            },
        )

        val events = drainAllItems(
            provider.languageModel("gpt-resp").stream(
                LanguageModelCallParams {
                    messages(listOf(UserMessage("hi")))
                },
            ),
        )

        val toolCall = events.filterIsInstance<StreamEvent.ToolCall>().single()
        assertEquals(JsonPrimitive("final"), toolCall.inputJson.jsonObject["q"])
    }

    @Test
    fun `stream surfaces response failed as terminal error event`() = runTest {
        val provider = OpenResponses(
            streamingClient(
                """
                    data: {"type":"response.failed","response":{"status":"failed","error":{"code":"server_error","message":"bad gateway"},"usage":{"input_tokens":1,"output_tokens":0,"total_tokens":1}}}
                """,
            ),
            OpenResponsesProviderSettings {
                url("https://api.test/v1/responses")
                name("openresponses")
            },
        )

        val events = drainAllItems(
            provider.languageModel("gpt-resp").stream(
                LanguageModelCallParams {
                    messages(listOf(UserMessage("hi")))
                },
            ),
        )

        val error = events.filterIsInstance<StreamEvent.Error>().single()
        assertTrue(error.message.contains("bad gateway"), error.message)
        val finish = events.filterIsInstance<StreamEvent.Finish>().single()
        assertEquals(FinishReason.Error, finish.finishReason)
        assertEquals("server_error", finish.rawFinishReason)
        assertEquals(1, finish.usage.promptTokens)
    }

    private fun streamingClient(content: String): HttpClient = HttpClient(
        MockEngine {
            respond(
                content = content.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
            )
        },
    )
}
