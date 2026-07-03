package ai.torad.aisdk

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
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

    @Test
    fun `tool result output Poko leaves round trip polymorphically as sealed ToolResultOutput`() {
        val cases = listOf<ToolResultOutput>(
            ToolResultOutput.Text("ok"),
            ToolResultOutput.ErrorJson(JsonPrimitive("bad")),
            ToolResultOutput.Content(
                value = listOf(JsonPrimitive("visible")),
                isError = true,
            ),
        )

        for (original in cases) {
            val encoded = aiSdkOutputJson.encodeToString(ToolResultOutput.serializer(), original)
            val decoded = aiSdkOutputJson.decodeFromString(ToolResultOutput.serializer(), encoded)

            assertEquals(original, decoded)
            assertTrue("\"type\":" in encoded, encoded)
            assertTrue("ToolResultOutput" !in encoded, encoded)
        }
    }

    @Test
    fun `D7 Poko tool result types keep value semantics`() {
        val schema = ToolSchema(
            name = "lookup",
            description = "Lookup a city.",
            inputExamples = listOf("""{"city":"Paris"}"""),
            metadata = mapOf("providerToolId" to JsonPrimitive("provider.lookup")),
        )
        val equalSchema = ToolSchema(
            name = "lookup",
            description = "Lookup a city.",
            inputExamples = listOf("""{"city":"Paris"}"""),
            metadata = mapOf("providerToolId" to JsonPrimitive("provider.lookup")),
        )
        val differentSchema = ToolSchema(
            name = "lookup",
            description = "Lookup a different value.",
            inputExamples = listOf("""{"city":"Paris"}"""),
            metadata = mapOf("providerToolId" to JsonPrimitive("provider.lookup")),
        )
        assertEquals(schema, equalSchema)
        assertEquals(schema.hashCode(), equalSchema.hashCode())
        assertNotEquals(schema, differentSchema)

        val output = ToolResultOutput.Content(value = listOf(JsonPrimitive("ok")), isError = false)
        val equalOutput = ToolResultOutput.Content(value = listOf(JsonPrimitive("ok")), isError = false)
        val differentOutput = ToolResultOutput.Content(value = listOf(JsonPrimitive("ok")), isError = true)
        assertEquals(output, equalOutput)
        assertEquals(output.hashCode(), equalOutput.hashCode())
        assertNotEquals(output, differentOutput)

        val final = ExecuteToolResult.Final(JsonPrimitive("done"))
        val equalFinal = ExecuteToolResult.Final(JsonPrimitive("done"))
        val differentFinal = ExecuteToolResult.Final(JsonPrimitive("other"))
        assertEquals(final, equalFinal)
        assertEquals(final.hashCode(), equalFinal.hashCode())
        assertNotEquals(final, differentFinal)
    }

    @Test
    fun `PendingApproval round trips through aiSdkOutputJson with Poko and Serializable`() {
        val approval = PendingApproval(
            toolCallId = "call_1",
            toolName = "lookup",
            input = JsonPrimitive("Paris"),
            approvalId = "approval_1",
            signature = "sig",
        )

        val encoded = aiSdkOutputJson.encodeToString(PendingApproval.serializer(), approval)
        val decoded = aiSdkOutputJson.decodeFromString(PendingApproval.serializer(), encoded)

        assertEquals(approval, decoded)
        assertTrue("\"approvalId\":\"approval_1\"" in encoded, encoded)
    }
}
