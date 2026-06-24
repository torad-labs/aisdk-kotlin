package ai.torad.aisdk.detekt

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.test.compileAndLint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CoroutineRulesTest {
    private val rule = NoRunCatchingInSuspendFunction(Config.empty)

    @Test
    fun `flags runCatching inside a suspend function`() {
        val findings = rule.compileAndLint(
            """
            suspend fun fetch(): Result<String> = runCatching { "ok" }
            """.trimIndent(),
        )

        assertEquals(1, findings.size)
    }

    @Test
    fun `does not flag runCatching in non-suspend code`() {
        val findings = rule.compileAndLint("fun parse(): Result<String> = runCatching { \"ok\" }")

        assertEquals(0, findings.size)
    }
}
