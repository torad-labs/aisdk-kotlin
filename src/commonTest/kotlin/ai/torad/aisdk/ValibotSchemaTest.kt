package ai.torad.aisdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ValibotSchemaTest {

    @Test
    fun `valibotSchema preserves JSON schema and validation callback`() {
        val adapter = valibotSchema<String>(personSchema()) { value ->
            value.jsonObject["name"]!!.jsonPrimitive.content.uppercase()
        }

        assertEquals(personSchema(), adapter.jsonSchema)
        assertEquals(
            "ADA",
            adapter.validate!!.invoke(
                buildJsonObject {
                    put("name", JsonPrimitive("ada"))
                },
            ),
        )
    }

    @Test
    fun `valibotSchema can wrap an existing provider-utils schema`() {
        val schema = jsonSchema<JsonObject>(personSchema()) { value -> value.jsonObject }

        assertEquals(schema, valibotSchema(schema))
    }

    private fun personSchema(): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put(
            "properties",
            buildJsonObject {
                put("name", buildJsonObject { put("type", JsonPrimitive("string")) })
            },
        )
        put("required", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("name"))))
        put("additionalProperties", JsonPrimitive(false))
    }
}
