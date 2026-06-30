@file:OptIn(LowLevelLanguageModelApi::class)

package ai.torad.aisdk

import ai.torad.aisdk.providers.GoogleGenerativeAI
import ai.torad.aisdk.providers.GoogleGenerativeAIProviderSettings
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Regression: JSON-Schema `const` has no OpenAPI 3.0 equivalent, so googleSchema must
 * translate it to a single-value `enum`; a sibling `enum`, if present, takes precedence
 * and `const` is dropped (upstream parity). Previously `const` was passed through verbatim.
 */
class GoogleSchemaConstTest {
    @Test
    fun `response schema converts const to single-value enum and lets enum win`() = runTest {
        val fixture = TestServer.createTestServer(
            mutableMapOf(
                "https://google.test/v1beta/models/gemini-2.5-flash:generateContent" to UrlHandler(
                    UrlResponse.JsonValue(
                        Json.parseToJsonElement(
                            """{"candidates":[{"content":{"role":"model","parts":[{"text":"ok"}]}}]}""",
                        ),
                    ),
                ),
            ),
        )
        fixture.server.start()
        val provider = GoogleGenerativeAI(
            fixture.httpClient(),
            GoogleGenerativeAIProviderSettings(apiKey = "key", baseURL = "https://google.test/v1beta"),
        )

        val enumAB = JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b")))
        val schema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put(
                "properties",
                buildJsonObject {
                    // Bare const -> enum:["fixed"].
                    put("kind", buildJsonObject { put("const", JsonPrimitive("fixed")) })
                    // enum present alongside const -> enum wins, const dropped.
                    put(
                        "tag",
                        buildJsonObject {
                            put("const", JsonPrimitive("ignored"))
                            put("enum", enumAB)
                        },
                    )
                },
            )
        }

        provider(ModelId("gemini-2.5-flash")).generate(
            LanguageModelCallParams(
                messages = listOf(UserMessage("hi")),
                responseFormat = ResponseFormat.Json(schemaJson = schema),
            ),
        )

        val body = fixture.calls.single().requestBodyJson.jsonObject
        val generationConfig = assertNotNull(body["generationConfig"]).jsonObject
        val responseSchema = assertNotNull(generationConfig["responseSchema"]).jsonObject
        val props = assertNotNull(responseSchema["properties"]).jsonObject
        val kind = assertNotNull(props["kind"]).jsonObject
        val tag = assertNotNull(props["tag"]).jsonObject

        assertTrue("const" !in kind, "const must be translated, not passed through")
        assertEquals(listOf("fixed"), kind["enum"]?.jsonArray?.map { it.jsonPrimitive.content })
        assertTrue("const" !in tag, "const must be dropped when enum is present")
        assertEquals(listOf("a", "b"), tag["enum"]?.jsonArray?.map { it.jsonPrimitive.content })
    }
}
