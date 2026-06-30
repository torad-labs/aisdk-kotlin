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
            GoogleGenerativeAIProviderSettings {
                apiKey("key")
                baseURL("https://google.test/v1beta")
            },
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
            LanguageModelCallParams {
                messages(listOf(UserMessage("hi")))
                responseFormat(ResponseFormat.Json(schemaJson = schema))
            },
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

    @Test
    fun `tool and response schemas collapse nullable unions for Google`() = runTest {
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
            GoogleGenerativeAIProviderSettings {
                apiKey("key")
                baseURL("https://google.test/v1beta")
            },
        )
        val nullableString = buildJsonObject {
            put(
                "anyOf",
                JsonArray(
                    listOf(
                        buildJsonObject { put("type", JsonPrimitive("string")) },
                        buildJsonObject { put("type", JsonPrimitive("null")) },
                    ),
                ),
            )
        }
        val nullableUnion = buildJsonObject {
            put(
                "anyOf",
                JsonArray(
                    listOf(
                        buildJsonObject { put("type", JsonPrimitive("string")) },
                        buildJsonObject { put("type", JsonPrimitive("number")) },
                        buildJsonObject { put("type", JsonPrimitive("null")) },
                    ),
                ),
            )
        }
        val multiType = buildJsonObject {
            put("type", JsonArray(listOf(JsonPrimitive("string"), JsonPrimitive("number"))))
        }
        val schema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put(
                "properties",
                buildJsonObject {
                    put("optionalName", nullableString)
                    put("optionalValue", nullableUnion)
                    put("stringOrNumber", multiType)
                },
            )
        }
        val toolSchema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("properties", buildJsonObject { put("unit", nullableString) })
        }

        provider(ModelId("gemini-2.5-flash")).generate(
            LanguageModelCallParams {
                messages(listOf(UserMessage("hi")))
                tools(listOf(LanguageModelTool("lookup", "Lookup.", toolSchema.toString())))
                responseFormat(ResponseFormat.Json(schemaJson = schema))
            },
        )

        val body = fixture.calls.single().requestBodyJson.jsonObject
        val bodyText = body.toString()
        assertTrue("\"type\":\"null\"" !in bodyText, "Google schemas must not include null as a type")
        assertTrue("\"type\":[" !in bodyText, "Google schemas must not include JSON-Schema type arrays")
        val generationConfig = assertNotNull(body["generationConfig"]).jsonObject
        val responseSchema = assertNotNull(generationConfig["responseSchema"]).jsonObject
        val responseProps = assertNotNull(responseSchema["properties"]).jsonObject
        val optionalName = assertNotNull(responseProps["optionalName"]).jsonObject
        assertEquals("string", optionalName["type"]?.jsonPrimitive?.content)
        assertEquals(true, optionalName["nullable"]?.jsonPrimitive?.content?.toBoolean())
        assertTrue("anyOf" !in optionalName, "single non-null nullable anyOf must flatten")
        val optionalValue = assertNotNull(responseProps["optionalValue"]).jsonObject
        assertEquals(true, optionalValue["nullable"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals(listOf("string", "number"), optionalValue["anyOf"]?.jsonArray?.map { it.jsonObject["type"]?.jsonPrimitive?.content })
        val stringOrNumber = assertNotNull(responseProps["stringOrNumber"]).jsonObject
        assertEquals(listOf("string", "number"), stringOrNumber["anyOf"]?.jsonArray?.map { it.jsonObject["type"]?.jsonPrimitive?.content })
        assertTrue("type" !in stringOrNumber, "multi-type schema must become anyOf")
        val toolParameters = assertNotNull(
            body["tools"]?.jsonArray?.single()?.jsonObject
                ?.get("functionDeclarations")?.jsonArray?.single()?.jsonObject
                ?.get("parameters"),
        ).jsonObject
        val unit = assertNotNull(toolParameters["properties"]?.jsonObject?.get("unit")).jsonObject
        assertEquals("string", unit["type"]?.jsonPrimitive?.content)
        assertEquals(true, unit["nullable"]?.jsonPrimitive?.content?.toBoolean())
        assertTrue("anyOf" !in unit, "tool nullable parameter must flatten")
    }
}
