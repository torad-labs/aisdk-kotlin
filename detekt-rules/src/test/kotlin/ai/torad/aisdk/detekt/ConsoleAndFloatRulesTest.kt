package ai.torad.aisdk.detekt

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.test.compileAndLint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ConsoleAndFloatRulesTest {
    @Test
    fun `flags a bare println`() {
        val findings = NoConsoleOutputInLibrary(Config.empty).compileAndLint("""fun f() { println("hi") }""")
        assertEquals(1, findings.size)
    }

    @Test
    fun `does not flag a member call named print`() {
        val findings = NoConsoleOutputInLibrary(Config.empty).compileAndLint("""fun f(w: java.io.Writer) { w.print("hi") }""")
        assertEquals(0, findings.size)
    }

    @Test
    fun `flags exact equality on a float literal`() {
        val findings = NoFloatEqualityComparison(Config.empty).compileAndLint("fun f(x: Double): Boolean = x == 1.0")
        assertEquals(1, findings.size)
    }

    @Test
    fun `does not flag integer equality`() {
        val findings = NoFloatEqualityComparison(Config.empty).compileAndLint("fun f(x: Int): Boolean = x == 1")
        assertEquals(0, findings.size)
    }
}
