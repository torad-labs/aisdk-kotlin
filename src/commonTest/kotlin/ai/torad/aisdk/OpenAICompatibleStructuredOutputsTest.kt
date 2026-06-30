package ai.torad.aisdk

import ai.torad.aisdk.providers.OpenAICompatible
import ai.torad.aisdk.providers.OpenAICompatibleProviderSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenAICompatibleStructuredOutputsTest {
    // Regression: when supportsStructuredOutputs is false (the default) a caller-supplied
    // JSON schema must NOT be put on the wire as `json_schema` — it falls back to a plain
    // `json_object`, matching the warning the request builder already emits. Before the fix
    // the schema was sent regardless, contradicting the warning and breaking strict shims.
    @Test
    fun `json schema is dropped to json_object when structured outputs unsupported`() = runTest {
        val seenBodies = mutableListOf<JsonObject>()
        val client = HttpClient(
            MockEngine { request ->
                seenBodies += Json.parseToJsonElement(requestBodyText(request)).jsonObject
                respond(
                    content = """
                        {"choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}
                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        val provider = OpenAICompatible(
            client,
            OpenAICompatibleProviderSettings {
                name("openai")
                baseUrl("https://api.test/v1")
                apiKey("secret")
                // supportsStructuredOutputs defaults to false — the case under test.
            },
        )

        val result = TextGenerator(
            provider.languageModel("m"),
            CallConfig(
                responseFormat = ResponseFormat.Json(
                    schemaName = "Answer",
                    schemaJson = JsonObject(mapOf("type" to JsonPrimitive("object"))),
                ),
            ),
        ).generate(GenerationInput.Prompt("hi")).first()

        val responseFormat = seenBodies.single()["response_format"]?.jsonObject
        assertEquals("json_object", responseFormat?.get("type")?.jsonPrimitive?.content)
        assertNull(responseFormat?.get("json_schema"), "schema must not reach the wire when unsupported")
        assertTrue(
            result.warnings.any { it.message?.contains("supportsStructuredOutputs") == true },
            "the dropped schema must surface as a warning",
        )
    }

    private fun requestBodyText(request: HttpRequestData): String =
        when (val body = request.body) {
            is OutgoingContent.ByteArrayContent -> body.bytes().decodeToString()
            is OutgoingContent.NoContent -> ""
            else -> body.toString()
        }
}
