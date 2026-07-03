package ai.torad.aisdk

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SchemaIntegerBoundaryTest {
    /**
     * Regression: schemaFallbackValue coerced "integer" via intOrNull, capping accepted values at
     * Int.MAX_VALUE — so a perfectly valid JSON integer above 2^31 (a millisecond timestamp, a
     * 64-bit id) was misclassified "not an integer" and failed validation.
     */
    @Test
    fun `schema validation accepts a 64-bit integer beyond Int range`() {
        val schema = Schemas.jsonSchema<Long>(buildJsonObject { put("type", JsonPrimitive("integer")) })
        val big = 2147483648L // 2^31, one past Int.MAX_VALUE

        val result = Schemas.safeValidateTypes(JsonPrimitive(big), schema)

        val success = assertIs<ValidationResult.Success<Long>>(result)
        assertEquals(big, success.value)
    }
}
