package ai.torad.aisdk

import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** H-8: every caller-owned tool-set construction path rejects duplicate names. */
class ToolSetDuplicateTest {

    @Serializable
    data class In(val x: String = "")

    private fun dummy(name: String): Tool<In, String, Unit> =
        Tool<In, String, Unit>(name = name, description = "d") { "ok" }

    @Test
    fun `toolSetOf throws on duplicate tool names`() {
        assertFailsWith<IllegalArgumentException> {
            ToolSet(dummy("a"), dummy("a"))
        }
    }

    @Test
    fun `ToolSet plus throws on overlapping tool names`() {
        val left = ToolSet(dummy("a"))
        val right = ToolSet(dummy("a"))
        assertFailsWith<IllegalArgumentException> { left + right }
    }
}
