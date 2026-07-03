package ai.torad.aisdk

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SchemaFallbackDefensiveParsingTest {
    /**
     * Regression (the M4 bug-class, Wave 6): schemaFallbackValue coerced "integer"/"number"/"boolean"
     * via the direct `value.jsonPrimitive.longOrNull`, which throws IllegalStateException on a
     * non-primitive model-supplied value — so the helpful "Expected JSON integer." error was unreachable
     * (the `.jsonPrimitive` access threw first). The safe `(value as? JsonPrimitive)?.longOrNull ?: throw`
     * preserves 64-bit `longOrNull` semantics AND makes the typed error reachable.
     */
    @Test
    fun `non-primitive value for an integer schema yields the typed error not a jsonPrimitive crash`() {
        val schema = Schemas.jsonSchema<Long>(JsonObject(mapOf("type" to JsonPrimitive("integer"))))
        val result = Schemas.safeValidateTypes(JsonObject(mapOf("oops" to JsonPrimitive(1))), schema)
        val failure = assertNotNull(
            result as? ValidationResult.Failure,
            "expected a validation failure, got $result",
        )
        assertTrue(
            failure.error.cause is IllegalArgumentException,
            "non-primitive integer yields the typed IllegalArgumentException, not an ISE from .jsonPrimitive",
        )
        assertTrue(
            failure.error.message?.contains("Expected JSON integer") == true,
            "the helpful schema coercion error reaches the caller",
        )
    }
}
