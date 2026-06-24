package ai.torad.aisdk

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertTrue

class ToolWireFormatTest {
    @Test
    fun `tool choice uses stable serial names not Kotlin class names`() {
        val encoded = aiSdkOutputJson.encodeToString(ToolChoice.serializer(), ToolChoice.Specific("lookup"))

        assertTrue("\"type\":\"specific\"" in encoded, encoded)
        assertTrue("ai.torad.aisdk" !in encoded, encoded)
        assertTrue("ToolChoice" !in encoded, encoded)
    }

    @Test
    fun `tool result output uses stable serial names not Kotlin class names`() {
        val encoded = aiSdkOutputJson.encodeToString(
            ToolResultOutput.serializer(),
            ToolResultOutput.Json(JsonPrimitive("ok")),
        )

        assertTrue("\"type\":\"json\"" in encoded, encoded)
        assertTrue("ai.torad.aisdk" !in encoded, encoded)
        assertTrue("ToolResultOutput" !in encoded, encoded)
    }
}
