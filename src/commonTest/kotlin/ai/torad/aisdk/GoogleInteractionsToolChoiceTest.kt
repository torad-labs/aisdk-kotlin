package ai.torad.aisdk

import ai.torad.aisdk.providers.GoogleInteractions
import ai.torad.aisdk.providers.GoogleInteractionsModelInput
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoogleInteractionsToolChoiceTest {
    private val googleSearchTool = LanguageModelTool(
        name = "google_search",
        description = "",
        parametersSchemaJson = "{}",
        providerExecuted = true,
        metadata = mapOf("providerToolId" to JsonPrimitive("google.google_search")),
    )

    private val functionTool = LanguageModelTool(
        name = "lookup",
        description = "Lookup city details.",
        parametersSchemaJson = """{"type":"object","properties":{"city":{"type":"string"}}}""",
    )

    /**
     * Regression: with only provider-executed tools present, the Interactions API rejects
     * tool_choice. The old gate keyed off the assembled tools array (which still contains the
     * provider-executed entries), so tool_choice leaked into the body. It must be omitted.
     */
    @Test
    fun `omits tool_choice when only provider-executed tools are present`() {
        val prepared = GoogleInteractions.googleInteractionsRequestBody(
            input = GoogleInteractionsModelInput.Model("gemini-2.0"),
            params = LanguageModelCallParams {
    messages(listOf(UserMessage("Hello")))
    tools(listOf(googleSearchTool))
    toolChoice(ToolChoice.Required)
},
            stream = false,
        )

        val generationConfig = prepared.body["generation_config"]?.jsonObject
        assertNull(generationConfig?.get("tool_choice"), "tool_choice must be omitted for provider-executed-only tools")
    }

    /**
     * Guard the positive path: a real (non-provider-executed) function tool still emits tool_choice.
     */
    @Test
    fun `emits tool_choice when a real function tool is present`() {
        val prepared = GoogleInteractions.googleInteractionsRequestBody(
            input = GoogleInteractionsModelInput.Model("gemini-2.0"),
            params = LanguageModelCallParams {
    messages(listOf(UserMessage("Hello")))
    tools(listOf(googleSearchTool, functionTool))
    toolChoice(ToolChoice.Required)
},
            stream = false,
        )

        val toolChoice = prepared.body["generation_config"]?.jsonObject?.get("tool_choice")
        assertTrue(toolChoice != null, "tool_choice must be present when a function tool is requested")
        assertEquals(JsonPrimitive("any"), toolChoice, "ToolChoice.Required maps to \"any\"")
    }

    @Test
    fun `maps media URLs to uri and base64 media to data`() {
        val imageUrl = "https://generativelanguage.googleapis.com/v1beta/files/image-1"
        val fileUrl = "gs://bucket/doc.pdf"
        val prepared = GoogleInteractions.googleInteractionsRequestBody(
            input = GoogleInteractionsModelInput.Model("gemini-2.0"),
            params = LanguageModelCallParams {
    messages(listOf(
                    ModelMessage(
                        MessageRole.User,
                        listOf(
                            ContentPart.Image(mediaType = "image/png", url = imageUrl),
                            ContentPart.File(mediaType = "application/pdf", url = fileUrl),
                            ContentPart.Image(mediaType = "image/jpeg", base64 = "aW1n"),
                            ContentPart.File(mediaType = "text/plain", base64 = "ZG9j"),
                        ),
                    ),
                ))
},
            stream = false,
        )

        val content = prepared.body.getValue("input")
            .jsonArray
            .single()
            .jsonObject
            .getValue("content")
            .jsonArray
        val urlImage = content[0].jsonObject
        assertEquals("image", urlImage["type"]?.jsonPrimitive?.contentOrNull)
        assertEquals(imageUrl, urlImage["uri"]?.jsonPrimitive?.contentOrNull)
        assertEquals("image/png", urlImage["mime_type"]?.jsonPrimitive?.contentOrNull)
        assertNull(urlImage["data"])
        val urlFile = content[1].jsonObject
        assertEquals("document", urlFile["type"]?.jsonPrimitive?.contentOrNull)
        assertEquals(fileUrl, urlFile["uri"]?.jsonPrimitive?.contentOrNull)
        assertEquals("application/pdf", urlFile["mime_type"]?.jsonPrimitive?.contentOrNull)
        assertNull(urlFile["data"])
        val base64Image = content[2].jsonObject
        assertEquals("image", base64Image["type"]?.jsonPrimitive?.contentOrNull)
        assertEquals("aW1n", base64Image["data"]?.jsonPrimitive?.contentOrNull)
        assertEquals("image/jpeg", base64Image["mime_type"]?.jsonPrimitive?.contentOrNull)
        assertNull(base64Image["uri"])
        val base64File = content[3].jsonObject
        assertEquals("document", base64File["type"]?.jsonPrimitive?.contentOrNull)
        assertEquals("ZG9j", base64File["data"]?.jsonPrimitive?.contentOrNull)
        assertEquals("text/plain", base64File["mime_type"]?.jsonPrimitive?.contentOrNull)
        assertNull(base64File["uri"])
    }

    @Test
    fun `interactions maps tool and response schemas to Google OpenAPI subset`() {
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
        val multiType = buildJsonObject {
            put("type", JsonArray(listOf(JsonPrimitive("string"), JsonPrimitive("number"))))
        }
        val schema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put(
                "properties",
                buildJsonObject {
                    put("optionalName", nullableString)
                    put("stringOrNumber", multiType)
                },
            )
        }
        val toolSchema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("properties", buildJsonObject { put("unit", nullableString) })
        }

        val prepared = GoogleInteractions.googleInteractionsRequestBody(
            input = GoogleInteractionsModelInput.Model("gemini-2.0"),
            params = LanguageModelCallParams {
    messages(listOf(UserMessage("Hello")))
    tools(listOf(LanguageModelTool("lookup", "Lookup.", toolSchema.toString())))
    responseFormat(ResponseFormat.Json(schemaJson = schema))
},
            stream = false,
        )

        val bodyText = prepared.body.toString()
        assertTrue("\"type\":\"null\"" !in bodyText, "Interactions schemas must not include null as a type")
        assertTrue("\"type\":[" !in bodyText, "Interactions schemas must not include JSON-Schema type arrays")
        val responseSchema = prepared.body.getValue("response_format")
            .jsonArray
            .single()
            .jsonObject
            .getValue("schema")
            .jsonObject
        val optionalName = responseSchema.getValue("properties").jsonObject.getValue("optionalName").jsonObject
        assertEquals("string", optionalName["type"]?.jsonPrimitive?.contentOrNull)
        assertEquals(true, optionalName["nullable"]?.jsonPrimitive?.contentOrNull?.toBoolean())
        val stringOrNumber = responseSchema.getValue("properties").jsonObject.getValue("stringOrNumber").jsonObject
        assertEquals(listOf("string", "number"), stringOrNumber["anyOf"]?.jsonArray?.map { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull })
        val parameters = prepared.body.getValue("tools")
            .jsonArray
            .single()
            .jsonObject
            .getValue("parameters")
            .jsonObject
        val unit = parameters.getValue("properties").jsonObject.getValue("unit").jsonObject
        assertEquals("string", unit["type"]?.jsonPrimitive?.contentOrNull)
        assertEquals(true, unit["nullable"]?.jsonPrimitive?.contentOrNull?.toBoolean())
        assertTrue("anyOf" !in unit, "Interactions nullable parameter must flatten")
    }
}
