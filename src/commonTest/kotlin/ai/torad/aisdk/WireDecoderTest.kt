package ai.torad.aisdk

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WireDecoderTest {
    @Serializable
    data class StrictShape(val name: String)

    @Test
    fun `strict decode rejects unknown fields`() {
        val error = assertFailsWith<WireDecodeException> {
            WireDecoder.decode(
                StrictShape.serializer(),
                buildJsonObject {
                    put("name", JsonPrimitive("ok"))
                    put("extra", JsonPrimitive("nope"))
                },
                provider = "fixture",
                operation = "strict-shape",
            )
        }

        assertEquals("fixture", error.provider)
        assertEquals("strict-shape", error.operation)
    }

    @Test
    fun `embedding float rejects nonnumeric values with wire context`() {
        val error = assertFailsWith<WireDecodeException> {
            WireDecoder.embeddingFloat(
                JsonPrimitive("not-a-number"),
                provider = "fixture.embedding",
                path = "$.data[0]"
            )
        }

        assertEquals("fixture.embedding", error.provider)
        assertEquals("$.data[0]", error.path)
    }
}
