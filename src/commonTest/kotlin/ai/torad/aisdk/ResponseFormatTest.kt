package ai.torad.aisdk

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Surface-tests for the [ResponseFormat] sealed type added in Phase 4C
 * (gap #20). Validates default + variant construction + round-trip
 * serialization — the wire shape providers consume.
 *
 * Provider-side honoring of the constraint (forcing JSON via OpenAI's
 * `response_format`, Anthropic's `output_format`, on-device grammar
 * constraints) is per-provider; this file only asserts the port-level
 * type + the call-params field default.
 */
class ResponseFormatTest {

    private val codec = Json { encodeDefaults = true }

    @Test
    fun `given default call params when constructed then responseFormat is Text`() {
        // GIVEN / WHEN
        val params = LanguageModelCallParams {
            messages(emptyList())
        }

        // THEN
        assertEquals(
            ResponseFormat.Text,
            params.responseFormat,
            "default constraint allows prose — same as v6 default",
        )
    }

    @Test
    fun `given a Json response-format with a schema when round-tripped then fields survive`() {
        // GIVEN
        val schema = buildJsonObject {
            put("type", JsonPrimitive("object"))
        }
        val original = ResponseFormat.Json(
            schemaName = "Recipe",
            schemaDescription = "A cake recipe",
            schemaJson = schema,
        )

        // WHEN
        val encoded = codec.encodeToString(ResponseFormat.serializer(), original)
        val decoded = codec.decodeFromString(ResponseFormat.serializer(), encoded)

        // THEN
        assertTrue(decoded is ResponseFormat.Json, "decoded as Json variant")
        assertEquals("Recipe", decoded.schemaName)
        assertEquals("A cake recipe", decoded.schemaDescription)
        assertEquals(schema, decoded.schemaJson)
    }

    @Test
    fun `given Text response-format when round-tripped then it remains Text`() {
        // GIVEN
        val original: ResponseFormat = ResponseFormat.Text

        // WHEN
        val encoded = codec.encodeToString(ResponseFormat.serializer(), original)
        val decoded = codec.decodeFromString(ResponseFormat.serializer(), encoded)

        // THEN
        assertEquals(ResponseFormat.Text, decoded)
    }

    @Test
    fun `response format uses stable serial names not Kotlin class names`() {
        val encoded = codec.encodeToString(ResponseFormat.serializer(), ResponseFormat.Json(schemaName = "Recipe"))

        assertTrue("\"type\":\"json\"" in encoded, encoded)
        assertTrue("ai.torad.aisdk" !in encoded, encoded)
        assertTrue("ResponseFormat" !in encoded, encoded)
    }

    @Test
    fun `given call params with Json response-format when constructed then the field is wired`() {
        // GIVEN — the wire surface a provider actually reads.
        val params = LanguageModelCallParams {
            messages(emptyList())
            responseFormat(ResponseFormat.Json(schemaName = "Recipe"))
        }

        // WHEN / THEN
        val rf = params.responseFormat
        assertTrue(rf is ResponseFormat.Json)
        assertEquals("Recipe", rf.schemaName)
    }
}
