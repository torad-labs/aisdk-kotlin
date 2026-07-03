package ai.torad.aisdk

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class FromOpenAIMetadataTest {
    /**
     * Regression: fromOpenAI read id/model/created via `?.jsonPrimitive`, which only guards null —
     * a present-but-non-primitive value (object/array) made the `jsonPrimitive` accessor throw
     * IllegalArgumentException, and since this runs inside the OpenAI-compatible stream collector
     * (no try/catch), one quirky metadata field aborted the ENTIRE stream. Now it degrades to null.
     */
    @Test
    fun `fromOpenAI tolerates non-primitive id and created without throwing`() {
        val meta = StreamEvent.ResponseMetadata.fromOpenAI(
            buildJsonObject {
                put("id", buildJsonObject { put("x", JsonPrimitive(1)) }) // object, not primitive
                put("created", JsonArray(listOf(JsonPrimitive(1)))) // array, not primitive
                put("model", JsonPrimitive("gpt-test")) // valid primitive — kept
            },
        )

        assertNotNull(meta, "a valid model field still yields metadata")
        assertEquals("gpt-test", meta.modelId)
        assertNull(meta.id, "a non-primitive id degrades to null, not a thrown stream abort")
        assertNull(meta.timestampMillis, "a non-primitive created degrades to null")
    }
}
