package ai.torad.aisdk

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M-3: locks the behavior of the two core JSON codecs so the flag matrix can't
 * silently drift — outbound [aiSdkOutputJson] emits defaults (stable
 * round-trips), inbound [aiSdkJson] is lenient and tolerant of unknown keys.
 */
class CodecConfigTest {

    @Serializable
    data class Fixture(val a: String = "default", val b: Int = 0)

    @Serializable
    data class NullableFixture(val value: String? = "default")

    @Test
    fun `outbound codec emits default-valued fields`() {
        val obj = aiSdkOutputJson.parseToJsonElement(
            aiSdkOutputJson.encodeToString(Fixture.serializer(), Fixture()),
        ).jsonObject
        assertTrue("a" in obj && "b" in obj, "encodeDefaults=true must emit defaulted fields")
    }

    @Test
    fun `typed JSON operations encode through the outbound codec`() {
        val obj = TypedJsonOps.encodeJsonElement(NullableFixture(value = null)).jsonObject
        assertTrue("value" in obj, "explicit nulls must survive outbound encoding")
        assertEquals(null, aiSdkOutputJson.decodeFromJsonElement(NullableFixture.serializer(), obj).value)
    }

    @Test
    fun `inbound codec ignores unknown keys`() {
        val decoded = aiSdkJson.decodeFromString(
            Fixture.serializer(),
            """{"a":"x","b":3,"extra":true}""",
        )
        assertEquals(Fixture("x", 3), decoded)
    }
}
