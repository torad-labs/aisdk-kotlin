package ai.torad.aisdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer

/**
 * Invariants I-3 / I-6 — structured output goes through [Output] only.
 * `generateObject` and `streamObject` are deprecated compatibility shims.
 */
class OutputTest {

    @Serializable data class Recipe(val name: String, val ingredients: List<String>)

    @Test
    fun `given a typed-object Output and a matching JSON string when decoded then the object s fields are populated`() {
        // GIVEN
        val output: Output<Recipe> = OutputObj(serializer())

        // WHEN
        val decoded = output.decode("""{"name":"cake","ingredients":["flour","sugar"]}""")

        // THEN
        assertEquals("cake", decoded.name)
        assertEquals(listOf("flour", "sugar"), decoded.ingredients)
    }

    @Test
    fun `given an array Output and a JSON array string when decoded then a list of typed objects is returned`() {
        // GIVEN
        val output: Output<List<Recipe>> = OutputArray(serializer())

        // WHEN
        val decoded = output.decode("""[{"name":"a","ingredients":[]},{"name":"b","ingredients":[]}]""")

        // THEN
        assertEquals(2, decoded.size)
        assertEquals("a", decoded[0].name)
    }

    @Test
    fun `given an array Output and the v6 elements wrapper when decoded then a list of typed objects is returned`() {
        // GIVEN
        val output: Output<List<Recipe>> = Output.array(serializer())

        // WHEN
        val decoded = output.decode("""{"elements":[{"name":"a","ingredients":[]}]}""")

        // THEN
        assertEquals(1, decoded.size)
        assertEquals("a", decoded.single().name)
    }

    @Test
    fun `given a typed-object Output when its schemaJson is read then it advertises an object type`() {
        // GIVEN
        val output: Output<Recipe> = OutputObj(serializer())

        // WHEN
        val schema = output.schemaJson

        // THEN
        assertTrue(schema.contains("object"))
    }

    @Test
    fun `given an array Output when schemaJson is read then it advertises the v6 elements wrapper`() {
        // GIVEN
        val output = OutputArray<Recipe>(serializer())

        // WHEN
        val schema = Json.parseToJsonElement(output.schemaJson).jsonObject

        // THEN
        assertEquals("object", schema["type"]?.jsonPrimitive?.content)
        val elements = schema["properties"]?.jsonObject?.get("elements")?.jsonObject
        assertEquals("array", elements?.get("type")?.jsonPrimitive?.content)
    }

    @Test
    fun `given a choice Output and a v6 result wrapper when decoded then the selected value is returned`() {
        // GIVEN
        val output = Output.choice("small", "large")

        // WHEN
        val decoded = output.decode("""{"result":"large"}""")

        // THEN
        assertEquals("large", decoded)
    }

    @Test
    fun `given a choice Output when schemaJson is read then enum values are advertised`() {
        // GIVEN
        val output = OutputChoice("yes", "no")

        // WHEN
        val result = Json.parseToJsonElement(output.schemaJson)
            .jsonObject["properties"]
            ?.jsonObject
            ?.get("result")
            ?.jsonObject

        // THEN
        val enumValues = result?.get("enum")?.jsonArray?.map { it.jsonPrimitive.content }
        assertEquals(listOf("yes", "no"), enumValues)
    }

    @Test
    fun `given a generic JSON Output when decoded then the parsed JSON tree is returned`() {
        // GIVEN
        val output = Output.json()

        // WHEN
        val decoded = output.decode("""{"ok":true}""")

        // THEN
        assertTrue(decoded is JsonObject)
        assertEquals(JsonPrimitive(true), decoded.jsonObject["ok"])
    }

    @Test
    fun `given an Output when converted to response format then schema and name are preserved`() {
        // GIVEN
        val output = OutputObj<Recipe>(
            serializer = serializer(),
            name = "Recipe",
            description = "A generated recipe",
        )

        // WHEN
        val responseFormat = output.toResponseFormat()

        // THEN
        assertTrue(responseFormat is ResponseFormat.Json)
        assertEquals("Recipe", responseFormat.schemaName)
        assertEquals("A generated recipe", responseFormat.schemaDescription)
        assertEquals("object", responseFormat.schemaJson?.jsonObject?.get("type")?.jsonPrimitive?.content)
    }
}
