package ai.torad.aisdk.detekt

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.test.compileAndLint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NoNotNullAssertionTest {
    private val rule = NoNotNullAssertion(Config.empty)

    @Test
    fun `flags the not-null assertion operator`() {
        val findings = rule.compileAndLint("fun f(x: String?): Int = x!!.length")
        assertEquals(1, findings.size)
    }

    @Test
    fun `does not flag a safe call`() {
        val findings = rule.compileAndLint("fun f(x: String?): Int? = x?.length")
        assertEquals(0, findings.size)
    }
}
