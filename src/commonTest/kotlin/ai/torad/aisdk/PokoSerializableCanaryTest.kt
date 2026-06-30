package ai.torad.aisdk

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class PokoSerializableCanaryTest {

    @Test
    fun `CallWarning round trips through aiSdkJson with Poko and Serializable`() {
        val original = CallWarning(
            type = "unsupported",
            message = "topK ignored",
            details = buildJsonObject {
                put("setting", JsonPrimitive("topK"))
            },
        )

        val encoded = aiSdkJson.encodeToString(CallWarning.serializer(), original)
        val decoded = aiSdkJson.decodeFromString(CallWarning.serializer(), encoded)

        assertEquals(original, decoded)
    }

    @Test
    fun `CallWarning has Poko value semantics`() {
        val details = buildJsonObject {
            put("setting", JsonPrimitive("topK"))
        }
        val first = CallWarning("unsupported", "topK ignored", details)
        val equal = CallWarning("unsupported", "topK ignored", details)
        val different = CallWarning("other", "topK ignored", details)

        assertEquals(first, equal)
        assertEquals(first.hashCode(), equal.hashCode())
        assertNotEquals(first, different)
    }
}
