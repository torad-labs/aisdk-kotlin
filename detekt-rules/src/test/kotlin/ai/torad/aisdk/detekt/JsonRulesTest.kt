package ai.torad.aisdk.detekt

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.test.compileAndLint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class JsonRulesTest {
    @Test
    fun `flags a force-cast to JsonObject`() {
        val findings = NoJsonContainerForceCast(Config.empty).compileAndLint("fun f(x: Any): Any = x as JsonObject")
        assertEquals(1, findings.size)
    }

    @Test
    fun `does not flag a non-json cast`() {
        val findings = NoJsonContainerForceCast(Config.empty).compileAndLint("fun f(x: Any): Any = x as String")
        assertEquals(0, findings.size)
    }

    @Test
    fun `does not flag the safe as-question cast`() {
        // `as?` returns null on mismatch (the recommended pattern) — only the unsafe `as` throws.
        val findings = NoJsonContainerForceCast(Config.empty).compileAndLint("fun f(x: Any): Any? = x as? JsonObject")
        assertEquals(0, findings.size)
    }

    @Test
    fun `flags an inline Json builder`() {
        val findings = NoInlineJsonInstance(Config.empty).compileAndLint("val j = Json { ignoreUnknownKeys = true }")
        assertEquals(1, findings.size)
    }

    @Test
    fun `does not flag a reference to a shared Json instance`() {
        val findings = NoInlineJsonInstance(Config.empty).compileAndLint("val s = aiSdkJson.encodeToString(1)")
        assertEquals(0, findings.size)
    }
}
