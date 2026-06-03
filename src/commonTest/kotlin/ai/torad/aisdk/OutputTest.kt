package ai.torad.aisdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer

/**
 * Invariants I-3 / I-6 — structured output goes through [Output] only.
 * `generateObject` and `streamObject` do not exist in this port.
 */
class OutputTest {

    @Serializable data class Recipe(val name: String, val ingredients: List<String>)

    @Test
    fun `given a typed-object Output and a matching JSON string when decoded then the object s fields are populated`() {
        // GIVEN
        val output: Output<Recipe> = outputObj(serializer())

        // WHEN
        val decoded = output.decode("""{"name":"cake","ingredients":["flour","sugar"]}""")

        // THEN
        assertEquals("cake", decoded.name)
        assertEquals(listOf("flour", "sugar"), decoded.ingredients)
    }

    @Test
    fun `given an array Output and a JSON array string when decoded then a list of typed objects is returned`() {
        // GIVEN
        val output: Output<List<Recipe>> = outputArray(serializer())

        // WHEN
        val decoded = output.decode("""[{"name":"a","ingredients":[]},{"name":"b","ingredients":[]}]""")

        // THEN
        assertEquals(2, decoded.size)
        assertEquals("a", decoded[0].name)
    }

    @Test
    fun `given a typed-object Output when its schemaJson is read then it advertises an object type`() {
        // GIVEN
        val output: Output<Recipe> = outputObj(serializer())

        // WHEN
        val schema = output.schemaJson

        // THEN
        assertTrue(schema.contains("object"))
    }
}
