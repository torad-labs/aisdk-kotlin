package ai.torad.aisdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.serializer

/**
 * Validates Phase 4F #34 — opaque metadata bag on [Tool]. Per
 * historical parity gap #34, this is the v6 `tool.metadata` slot
 * that consumers (logger middleware, telemetry, host-side gating)
 * read via [ToolSet.byName] for tool-specific routing.
 *
 * The loop NEVER inspects this field — it's opaque-by-design.
 */
class ToolMetadataTest {

    @Serializable data class Input(val q: String)

    @Test
    fun `given a tool constructed without metadata when read then metadata is empty`() {
        val t = Tool<Input, String, Unit>(
            name = "noMeta",
            description = "",
            inputSerializer = serializer(),
            outputSerializer = serializer(),
        ) { _ -> "ok" }
        assertTrue(t.metadata.isEmpty(), "default is empty map")
    }

    @Test
    fun `given a tool constructed with metadata when read then values survive`() {
        // GIVEN — a metadata bag with arbitrary application-defined keys.
        val t = Tool<Input, String, Unit>(
            name = "tier",
            description = "",
            inputSerializer = serializer(),
            outputSerializer = serializer(),
            metadata = mapOf(
                "tier" to JsonPrimitive("premium"),
                "owner" to JsonPrimitive("@chat-team"),
            ),
        ) { _ -> "ok" }

        // WHEN/THEN — consumer can read both fields back unchanged.
        assertEquals(JsonPrimitive("premium"), t.metadata["tier"])
        assertEquals(JsonPrimitive("@chat-team"), t.metadata["owner"])
    }

    @Test
    fun `given a tool in a toolset when looked up by name then metadata flows through unchanged`() {
        // GIVEN
        val t = Tool<Input, String, Unit>(
            name = "telemetry",
            description = "",
            inputSerializer = serializer(),
            outputSerializer = serializer(),
            metadata = mapOf("sample-rate" to JsonPrimitive(0.1)),
        ) { _ -> "ok" }
        val ts: ToolSet<Unit> = toolSetOf(t)

        // WHEN — consumers reach into the toolset for the metadata bag.
        val found = ts.find("telemetry")
            ?: error("toolset must surface the tool by name")

        // THEN
        assertEquals(JsonPrimitive(0.1), found.metadata["sample-rate"])
    }
}
