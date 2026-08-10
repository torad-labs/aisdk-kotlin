@file:OptIn(LowLevelLanguageModelApi::class)

package ai.torad.aisdk

import ai.torad.aisdk.testing.FlowDrain.drainAllItems
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The gateway wire is raw LanguageModelV3 stream-part JSON, where a source part is
 * `{"type":"source","sourceType":"url"|"document",...}` — `source-url`/`source-document`
 * are UI-layer names. Pins that the V3 shape decodes to typed source events/parts
 * instead of degrading to [StreamEvent.Raw] / [ContentPart.Raw].
 */
class GatewayV3SourcePartTest {
    private val params = LanguageModelCallParams {
        messages(listOf(UserMessage("hi")))
    }

    private fun gateway(client: HttpClient): GatewayProvider =
        CreateGatewayHttpProvider(client, GatewayProviderSettings { apiKey("key") })

    private fun sseClient(body: String): HttpClient =
        HttpClient(
            MockEngine {
                respond(
                    content = body,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
                )
            },
        )

    private fun jsonClient(body: String): HttpClient =
        HttpClient(
            MockEngine {
                respond(
                    content = body,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )

    @Test
    fun `stream maps a v3 url source part to a typed source event`() = runTest {
        val body =
            """
            data: {"type":"source","sourceType":"url","id":"src_1","url":"https://example.test","title":"Example"}

            data: {"type":"finish","finishReason":"stop"}
            """.trimIndent()
        val events = drainAllItems(gateway(sseClient(body)).languageModel("m").stream(params))

        val source = assertIs<StreamEvent.SourcePart>(events.first { it is StreamEvent.SourcePart })
        assertEquals(StreamEvent.SourcePart.SourceType.Url, source.sourceType)
        assertEquals("src_1", source.id)
        assertEquals("https://example.test", source.url)
        assertEquals("Example", source.title)
    }

    @Test
    fun `stream maps a v3 document source part to a typed source event`() = runTest {
        val body =
            """
            data: {"type":"source","sourceType":"document","id":"doc_1","mediaType":"application/pdf","title":"Spec"}

            data: {"type":"finish","finishReason":"stop"}
            """.trimIndent()
        val events = drainAllItems(gateway(sseClient(body)).languageModel("m").stream(params))

        val source = assertIs<StreamEvent.SourcePart>(events.first { it is StreamEvent.SourcePart })
        assertEquals(StreamEvent.SourcePart.SourceType.Document, source.sourceType)
        assertEquals("doc_1", source.id)
        assertEquals("application/pdf", source.mediaType)
    }

    @Test
    fun `generate maps v3 source content parts to typed source parts`() = runTest {
        val body =
            """
            {
              "content":[
                {"type":"source","sourceType":"url","id":"src_1","url":"https://example.test","title":"Example"},
                {"type":"source","sourceType":"document","id":"doc_1","mediaType":"application/pdf","title":"Spec"}
              ],
              "finishReason":"stop"
            }
            """.trimIndent()

        val result = gateway(jsonClient(body)).languageModel("m").generate(params)

        val sources = result.content.filterIsInstance<ContentPart.Source>()
        assertEquals(2, sources.size)
        assertEquals(StreamEvent.SourcePart.SourceType.Url, sources[0].sourceType)
        assertEquals("src_1", sources[0].sourceId)
        assertEquals("https://example.test", sources[0].url)
        assertEquals(StreamEvent.SourcePart.SourceType.Document, sources[1].sourceType)
        assertEquals("doc_1", sources[1].sourceId)
        assertEquals("application/pdf", sources[1].mediaType)
    }
}
